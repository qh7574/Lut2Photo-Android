#ifndef IMAGE_PROCESSOR_H
#define IMAGE_PROCESSOR_H

#include "../include/native_lut_processor.h"
#include <thread>
#include <vector>
#include <functional>
#include <atomic>

/**
 * 图片处理核心类
 * 负责像素级的图片处理操作
 */
class ImageProcessor {
public:
    ImageProcessor();

    ~ImageProcessor();

    /**
     * 单线程处理图片
     */
    static ProcessResult processSingleThreaded(
            const ImageInfo &input,
            ImageInfo &output,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params,
            NativeProgressCallback callback = nullptr
    );

    /**
     * 多线程处理图片
     */
    static ProcessResult processMultiThreaded(
            const ImageInfo &input,
            ImageInfo &output,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params,
            NativeProgressCallback callback = nullptr
    );

    /**
     * 处理单个像素
     */
    static void processPixel(
            const uint8_t *inputPixel,
            uint8_t *outputPixel,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params
    );

    /**
     * 批量处理像素（SIMD优化）
     */
    static void processPixelsBatch(
            const uint8_t *inputPixels,
            uint8_t *outputPixels,
            int pixelCount,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params
    );

    /**
     * 应用抖动处理
     */
    static void applyDithering(
            uint8_t *pixels,
            int width,
            int height,
            int stride,
            const ProcessingParams &params
    );

private:
    /**
     * 工作线程结构
     */
    struct WorkerThread {
        std::thread thread;
        std::atomic<bool> isRunning{false};
        std::atomic<float> progress{0.0f};
    };

    /**
     * 线程工作函数
     */
    static void workerFunction(
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
    );

    /**
     * Floyd-Steinberg抖动
     */
    static void applyFloydSteinbergDithering(
            uint8_t *pixels,
            int width,
            int height,
            int stride
    );

    /**
     * 随机抖动
     */
    static void applyRandomDithering(
            uint8_t *pixels,
            int width,
            int height,
            int stride
    );

    /**
     * 计算最优线程数
     */
    static int calculateOptimalThreadCount(int imageWidth, int imageHeight);
};

#endif // IMAGE_PROCESSOR_H