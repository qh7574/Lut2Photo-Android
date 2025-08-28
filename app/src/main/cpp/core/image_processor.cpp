#include "image_processor.h"
#include "lut_processor.h"
#include "../utils/simd_utils.h"
#include <algorithm>
#include <random>
#include <cmath>

ImageProcessor::ImageProcessor() {
    LOGD("ImageProcessor构造函数");
}

ImageProcessor::~ImageProcessor() {
    LOGD("ImageProcessor析构函数");
}

ProcessResult ImageProcessor::processSingleThreaded(
        const ImageInfo &input,
        ImageInfo &output,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params,
        NativeProgressCallback callback
) {
    if (!input.pixels || !output.pixels) {
        LOGE("输入或输出像素数据为空");
        return ProcessResult::ERROR_INVALID_BITMAP;
    }

    const uint8_t *inputPixels = static_cast<const uint8_t *>(input.pixels);
    uint8_t *outputPixels = static_cast<uint8_t *>(output.pixels);

    const int totalPixels = input.width * input.height;
    const int bytesPerPixel = 4; // ARGB_8888

    LOGD("开始单线程处理，总像素数: %d", totalPixels);

    // 逐像素处理
    for (int y = 0; y < input.height; ++y) {
        for (int x = 0; x < input.width; ++x) {
            const int pixelIndex = y * input.stride + x * bytesPerPixel;

            processPixel(
                    &inputPixels[pixelIndex],
                    &outputPixels[pixelIndex],
                    primaryLut,
                    secondaryLut,
                    params
            );
        }

        // 更新进度
        if (callback && y % 100 == 0) {
            float progress = static_cast<float>(y) / input.height;
            callback(progress);
        }
    }

    // 应用抖动处理
    if (params.ditherType > 0) {
        applyDithering(outputPixels, output.width, output.height, output.stride, params);
    }

    if (callback) {
        callback(1.0f);
    }

    LOGD("单线程处理完成");
    return ProcessResult::SUCCESS;
}

ProcessResult ImageProcessor::processMultiThreaded(
        const ImageInfo &input,
        ImageInfo &output,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params,
        NativeProgressCallback callback
) {
    if (!input.pixels || !output.pixels) {
        LOGE("输入或输出像素数据为空");
        return ProcessResult::ERROR_INVALID_BITMAP;
    }

    const int threadCount = calculateOptimalThreadCount(input.width, input.height);
    const int rowsPerThread = input.height / threadCount;

    LOGD("开始多线程处理，线程数: %d, 每线程行数: %d", threadCount, rowsPerThread);

    std::vector<std::thread> workers;
    std::vector<std::atomic<float>> threadProgress(threadCount);

    const uint8_t *inputPixels = static_cast<const uint8_t *>(input.pixels);
    uint8_t *outputPixels = static_cast<uint8_t *>(output.pixels);

    // 启动工作线程
    for (int i = 0; i < threadCount; ++i) {
        const int startRow = i * rowsPerThread;
        const int endRow = (i == threadCount - 1) ? input.height : (i + 1) * rowsPerThread;

        threadProgress[i] = 0.0f;

        workers.emplace_back(
                workerFunction,
                inputPixels,
                outputPixels,
                startRow,
                endRow,
                input.width,
                input.stride,
                std::cref(primaryLut),
                std::cref(secondaryLut),
                std::cref(params),
                std::ref(threadProgress[i])
        );
    }

    // 监控进度
    if (callback) {
        while (true) {
            float totalProgress = 0.0f;
            bool allCompleted = true;

            for (int i = 0; i < threadCount; ++i) {
                float progress = threadProgress[i].load();
                totalProgress += progress;
                if (progress < 1.0f) {
                    allCompleted = false;
                }
            }

            callback(totalProgress / threadCount);

            if (allCompleted) {
                break;
            }

            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
    }

    // 等待所有线程完成
    for (auto &worker: workers) {
        if (worker.joinable()) {
            worker.join();
        }
    }

    // 应用抖动处理
    if (params.ditherType > 0) {
        applyDithering(outputPixels, output.width, output.height, output.stride, params);
    }

    LOGD("多线程处理完成");
    return ProcessResult::SUCCESS;
}

void ImageProcessor::processPixel(
        const uint8_t *inputPixel,
        uint8_t *outputPixel,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params
) {
    // ARGB_8888格式：A=3, R=2, G=1, B=0
    const uint8_t alpha = inputPixel[3];
    const uint8_t red = inputPixel[2];
    const uint8_t green = inputPixel[1];
    const uint8_t blue = inputPixel[0];

    // 归一化到[0,1]范围
    float r = red / 255.0f;
    float g = green / 255.0f;
    float b = blue / 255.0f;

    // 应用主LUT
    float lutR, lutG, lutB;
    LutProcessor::applyLut(r, g, b, lutR, lutG, lutB, primaryLut);

    // 应用第二LUT（如果存在）
    if (secondaryLut.isLoaded && params.lut2Strength > 0.0f) {
        float lut2R, lut2G, lut2B;
        LutProcessor::applyLut(lutR, lutG, lutB, lut2R, lut2G, lut2B, secondaryLut);

        // 混合两个LUT的结果
        lutR = lutR * (1.0f - params.lut2Strength) + lut2R * params.lut2Strength;
        lutG = lutG * (1.0f - params.lut2Strength) + lut2G * params.lut2Strength;
        lutB = lutB * (1.0f - params.lut2Strength) + lut2B * params.lut2Strength;
    }

    // 应用强度混合
    if (params.strength < 1.0f) {
        lutR = r * (1.0f - params.strength) + lutR * params.strength;
        lutG = g * (1.0f - params.strength) + lutG * params.strength;
        lutB = b * (1.0f - params.strength) + lutB * params.strength;
    }

    // 限制范围并转换回8位
    lutR = std::clamp(lutR, 0.0f, 1.0f);
    lutG = std::clamp(lutG, 0.0f, 1.0f);
    lutB = std::clamp(lutB, 0.0f, 1.0f);

    outputPixel[3] = alpha; // 保持Alpha通道
    outputPixel[2] = static_cast<uint8_t>(lutR * 255.0f + 0.5f);
    outputPixel[1] = static_cast<uint8_t>(lutG * 255.0f + 0.5f);
    outputPixel[0] = static_cast<uint8_t>(lutB * 255.0f + 0.5f);
}

void ImageProcessor::processPixelsBatch(
        const uint8_t *inputPixels,
        uint8_t *outputPixels,
        int pixelCount,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params
) {
#ifdef USE_NEON_SIMD
    // 使用NEON SIMD优化
    if (SIMDUtils::isNeonAvailable()) {
        SIMDUtils::processPixelsNeon(
                inputPixels, outputPixels, pixelCount,
                primaryLut, secondaryLut, params
        );
        return;
    }
#endif

    // 回退到标准处理
    const int bytesPerPixel = 4;
    for (int i = 0; i < pixelCount; ++i) {
        const int offset = i * bytesPerPixel;
        processPixel(
                &inputPixels[offset],
                &outputPixels[offset],
                primaryLut,
                secondaryLut,
                params
        );
    }
}

void ImageProcessor::applyDithering(
        uint8_t *pixels,
        int width,
        int height,
        int stride,
        const ProcessingParams &params
) {
    switch (params.ditherType) {
        case 1: // Floyd-Steinberg
            applyFloydSteinbergDithering(pixels, width, height, stride);
            break;
        case 2: // Random
            applyRandomDithering(pixels, width, height, stride);
            break;
        default:
            // 无抖动
            break;
    }
}

void ImageProcessor::workerFunction(
        const uint8_t *inputPixels,
        uint8_t *outputPixels,
        int startRow,
        int endRow,
        int width,
        int stride,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params,
        std::atomic<float> &progress
) {
    const int bytesPerPixel = 4;
    const int totalRows = endRow - startRow;

    for (int y = startRow; y < endRow; ++y) {
        for (int x = 0; x < width; ++x) {
            const int pixelIndex = y * stride + x * bytesPerPixel;

            processPixel(
                    &inputPixels[pixelIndex],
                    &outputPixels[pixelIndex],
                    primaryLut,
                    secondaryLut,
                    params
            );
        }

        // 更新进度
        float currentProgress = static_cast<float>(y - startRow + 1) / totalRows;
        progress.store(currentProgress);
    }
}

void ImageProcessor::applyFloydSteinbergDithering(
        uint8_t *pixels,
        int width,
        int height,
        int stride
) {
    const int bytesPerPixel = 4;

    for (int y = 0; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            const int currentIndex = y * stride + x * bytesPerPixel;

            for (int channel = 0; channel < 3; ++channel) { // 跳过Alpha通道
                const int oldPixel = pixels[currentIndex + channel];
                const int newPixel = (oldPixel > 127) ? 255 : 0;
                const int error = oldPixel - newPixel;

                pixels[currentIndex + channel] = newPixel;

                // 分布误差
                if (x + 1 < width) {
                    const int rightIndex = currentIndex + bytesPerPixel + channel;
                    pixels[rightIndex] = std::clamp(
                            pixels[rightIndex] + error * 7 / 16, 0, 255
                    );
                }

                if (y + 1 < height) {
                    if (x > 0) {
                        const int bottomLeftIndex =
                                (y + 1) * stride + (x - 1) * bytesPerPixel + channel;
                        pixels[bottomLeftIndex] = std::clamp(
                                pixels[bottomLeftIndex] + error * 3 / 16, 0, 255
                        );
                    }

                    const int bottomIndex = (y + 1) * stride + x * bytesPerPixel + channel;
                    pixels[bottomIndex] = std::clamp(
                            pixels[bottomIndex] + error * 5 / 16, 0, 255
                    );

                    if (x + 1 < width) {
                        const int bottomRightIndex =
                                (y + 1) * stride + (x + 1) * bytesPerPixel + channel;
                        pixels[bottomRightIndex] = std::clamp(
                                pixels[bottomRightIndex] + error * 1 / 16, 0, 255
                        );
                    }
                }
            }
        }
    }
}

void ImageProcessor::applyRandomDithering(
        uint8_t *pixels,
        int width,
        int height,
        int stride
) {
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_real_distribution<float> dis(-0.5f, 0.5f);

    const int bytesPerPixel = 4;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const int pixelIndex = y * stride + x * bytesPerPixel;

            for (int channel = 0; channel < 3; ++channel) { // 跳过Alpha通道
                float noise = dis(gen) * 32.0f; // 调整噪声强度
                int newValue = static_cast<int>(pixels[pixelIndex + channel] + noise);
                pixels[pixelIndex + channel] = std::clamp(newValue, 0, 255);
            }
        }
    }
}

int ImageProcessor::calculateOptimalThreadCount(int imageWidth, int imageHeight) {
    const int totalPixels = imageWidth * imageHeight;
    const int coreCount = std::thread::hardware_concurrency();

    // 根据图片大小和CPU核心数计算最优线程数
    int optimalThreads;

    if (totalPixels < 1000000) { // 小于100万像素
        optimalThreads = std::min(2, coreCount);
    } else if (totalPixels < 4000000) { // 小于400万像素
        optimalThreads = std::min(4, coreCount);
    } else { // 大图片
        optimalThreads = std::min(8, coreCount);
    }

    return std::max(1, optimalThreads);
}