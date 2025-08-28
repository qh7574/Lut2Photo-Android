#include "streaming_processor.h"
#include "lut_processor.h"
#include <algorithm>
#include <thread>
#include <future>
#include <chrono>
#include <cmath>

StreamingProcessor::StreamingProcessor() {
    imageProcessor_ = std::make_unique<ImageProcessor>();
    STREAM_LOGI("流式处理器初始化完成");
}

StreamingProcessor::~StreamingProcessor() {
    cleanupTileCache();
    STREAM_LOGI("流式处理器销毁，处理统计 - 总图片: %zu, 流式处理: %zu, 直接处理: %zu",
                stats_.totalImagesProcessed, stats_.streamingProcessCount,
                stats_.directProcessCount);
}

void StreamingProcessor::setConfig(const StreamingConfig &config) {
    config_ = config;
    STREAM_LOGI("更新流式处理配置 - 最大块大小: %.2f MB, 重叠: %d px, 并发块数: %d",
                config_.maxTileSize / (1024.0 * 1024.0), config_.tileOverlap,
                config_.maxConcurrentTiles);
}

ProcessResult StreamingProcessor::processImageOptimized(
        const ImageInfo &input,
        ImageInfo &output,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params,
        ProgressCallback progressCallback
) {
    auto startTime = std::chrono::high_resolution_clock::now();

    // 选择最优处理策略
    auto strategy = ProcessingStrategySelector::selectOptimalStrategy(
            input.width, input.height,
            config_.maxMemoryUsage, params
    );

    ProcessResult result;

    switch (strategy) {
        case ProcessingStrategySelector::Strategy::DIRECT:
            STREAM_LOGI("使用直接处理策略 - 图片尺寸: %dx%d", input.width, input.height);
            result = processImageDirect(input, output, primaryLut, secondaryLut, params,
                                        progressCallback);
            stats_.directProcessCount++;
            break;

        case ProcessingStrategySelector::Strategy::STREAMING:
            STREAM_LOGI("使用流式处理策略 - 图片尺寸: %dx%d", input.width, input.height);
            result = processImageStreaming(input, output, primaryLut, secondaryLut, params,
                                           [progressCallback](const StreamingProgress &progress) {
                                               if (progressCallback) {
                                                   progressCallback(
                                                           static_cast<float>(progress.getProgress()),
                                                           "流式处理中...");
                                               }
                                           });
            stats_.streamingProcessCount++;
            break;

        case ProcessingStrategySelector::Strategy::HYBRID:
            STREAM_LOGI("使用混合处理策略 - 图片尺寸: %dx%d", input.width, input.height);
            // 对于混合策略，优先尝试直接处理，失败时回退到流式处理
            result = processImageDirect(input, output, primaryLut, secondaryLut, params,
                                        progressCallback);
            if (result != ProcessResult::SUCCESS) {
                STREAM_LOGW("直接处理失败，回退到流式处理");
                result = processImageStreaming(input, output, primaryLut, secondaryLut, params,
                                               [progressCallback](
                                                       const StreamingProgress &progress) {
                                                   if (progressCallback) {
                                                       progressCallback(
                                                               static_cast<float>(progress.getProgress()),
                                                               "混合处理中...");
                                                   }
                                               });
                stats_.streamingProcessCount++;
            } else {
                stats_.directProcessCount++;
            }
            break;
    }

    // 更新统计信息
    auto endTime = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration<double>(endTime - startTime).count();

    stats_.totalImagesProcessed++;
    stats_.totalBytesProcessed += static_cast<size_t>(input.width) * input.height * 4;
    stats_.averageProcessingTime =
            (stats_.averageProcessingTime * (stats_.totalImagesProcessed - 1) + duration) /
            stats_.totalImagesProcessed;

    // 更新峰值内存使用
    auto poolStats = MemoryPool::getInstance().getStats();
    double currentMemoryUsage =
            static_cast<double>(poolStats.totalAllocated) / config_.maxMemoryUsage;
    stats_.peakMemoryUsage = std::max(stats_.peakMemoryUsage, currentMemoryUsage);

    STREAM_LOGI("图片处理完成 - 耗时: %.2fs, 策略: %s, 内存使用: %.1f%%",
                duration,
                strategy == ProcessingStrategySelector::Strategy::DIRECT ? "直接" :
                strategy == ProcessingStrategySelector::Strategy::STREAMING ? "流式" : "混合",
                currentMemoryUsage * 100);

    return result;
}

ProcessResult StreamingProcessor::processImageStreaming(
        const ImageInfo &input,
        ImageInfo &output,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params,
        StreamingProgressCallback progressCallback,
        StreamingCancelCallback cancelCallback
) {
    std::lock_guard<std::mutex> lock(processingMutex_);
    isProcessing_ = true;

    STREAM_LOGI("开始流式处理 - 图片尺寸: %dx%d, 估计内存需求: %.2f MB",
                input.width, input.height,
                estimateMemoryRequirement(input.width, input.height) / (1024.0 * 1024.0));

    // 创建分块
    auto inputTiles = createTiles(input);
    std::vector<ImageTile> outputTiles(inputTiles.size());

    STREAM_LOGI("创建了 %zu 个处理块", inputTiles.size());

    ProcessResult result;

    // 根据配置选择处理方式
    if (config_.enableParallelProcessing && inputTiles.size() > 1) {
        result = processParallel(inputTiles, outputTiles, primaryLut, secondaryLut, params,
                                 progressCallback, cancelCallback);
    } else {
        result = processSequential(inputTiles, outputTiles, primaryLut, secondaryLut, params,
                                   progressCallback, cancelCallback);
    }

    // 合成最终结果
    if (result == ProcessResult::SUCCESS) {
        result = assembleTiles(outputTiles, output);
    }

    // 清理分块数据
    for (auto &tile: inputTiles) {
        deallocateTileData(tile);
    }
    for (auto &tile: outputTiles) {
        deallocateTileData(tile);
    }

    isProcessing_ = false;
    return result;
}

ProcessResult StreamingProcessor::processImageDirect(
        const ImageInfo &input,
        ImageInfo &output,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params,
        ProgressCallback progressCallback
) {
    // 检查内存是否足够进行直接处理
    size_t requiredMemory = estimateMemoryRequirement(input.width, input.height);
    if (requiredMemory > config_.maxMemoryUsage) {
        STREAM_LOGW("内存不足进行直接处理，需要: %.2f MB, 可用: %.2f MB",
                    requiredMemory / (1024.0 * 1024.0),
                    config_.maxMemoryUsage / (1024.0 * 1024.0));
        return ProcessResult::ERROR_MEMORY_ALLOCATION;
    }

    // 创建NativeProgressCallback适配器
    NativeProgressCallback nativeCallback = nullptr;
    if (progressCallback) {
        // 创建一个静态的lambda来适配回调
        static thread_local ProgressCallback *currentCallback = nullptr;
        currentCallback = &progressCallback;
        nativeCallback = [](float progress) {
            if (currentCallback && *currentCallback) {
                (*currentCallback)(progress, "Processing");
            }
        };
    }

    // 使用标准图像处理器进行处理
    if (params.useMultiThreading) {
        return imageProcessor_->processMultiThreaded(input, output, primaryLut, secondaryLut,
                                                     params, nativeCallback);
    } else {
        return imageProcessor_->processSingleThreaded(input, output, primaryLut, secondaryLut,
                                                      params, nativeCallback);
    }
}

std::vector<ImageTile> StreamingProcessor::createTiles(const ImageInfo &image) const {
    std::vector<ImageTile> tiles;

    // 计算最优分块尺寸
    int tileWidth, tileHeight;
    size_t pixelsPerTile = config_.maxTileSize / 4; // RGBA = 4 bytes per pixel

    // 尝试创建正方形块
    int tileSide = static_cast<int>(std::sqrt(pixelsPerTile));
    tileSide = std::min(tileSide, std::min(image.width, image.height));
    tileSide = std::max(tileSide, config_.minTileSize);

    tileWidth = std::min(tileSide, image.width);
    tileHeight = std::min(tileSide, image.height);

    STREAM_LOGD("计算分块尺寸: %dx%d, 重叠: %d px", tileWidth, tileHeight, config_.tileOverlap);

    // 创建分块
    for (int y = 0; y < image.height; y += tileHeight - config_.tileOverlap) {
        for (int x = 0; x < image.width; x += tileWidth - config_.tileOverlap) {
            ImageTile tile;
            tile.originalX = x;
            tile.originalY = y;
            tile.x = 0;
            tile.y = 0;
            tile.width = std::min(tileWidth, image.width - x);
            tile.height = std::min(tileHeight, image.height - y);
            tile.dataSize = static_cast<size_t>(tile.width) * tile.height * 4;

            // 分配块数据
            if (allocateTileData(tile)) {
                // 复制像素数据
                const uint8_t *srcPixels = static_cast<const uint8_t *>(image.pixels);
                uint8_t *dstPixels = static_cast<uint8_t *>(tile.data);

                for (int row = 0; row < tile.height; ++row) {
                    const uint8_t *srcRow = srcPixels + ((y + row) * image.stride) + (x * 4);
                    uint8_t *dstRow = dstPixels + (row * tile.width * 4);
                    std::memcpy(dstRow, srcRow, tile.width * 4);
                }

                tiles.push_back(tile);
            } else {
                STREAM_LOGE("分配块数据失败: %dx%d", tile.width, tile.height);
            }
        }
    }

    return tiles;
}

bool StreamingProcessor::allocateTileData(ImageTile &tile) const {
    tile.data = MemoryPool::getInstance().allocate(tile.dataSize, 32);
    return tile.data != nullptr;
}

void StreamingProcessor::deallocateTileData(ImageTile &tile) const {
    if (tile.data) {
        MemoryPool::getInstance().deallocate(tile.data);
        tile.data = nullptr;
    }
}

ProcessResult StreamingProcessor::processParallel(
        const std::vector<ImageTile> &inputTiles,
        std::vector<ImageTile> &outputTiles,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params,
        StreamingProgressCallback progressCallback,
        StreamingCancelCallback cancelCallback
) {
    const int maxConcurrent = std::min(config_.maxConcurrentTiles,
                                       static_cast<int>(inputTiles.size()));
    std::vector<std::future<ProcessResult>> futures;
    std::atomic<int> completedTiles{0};

    STREAM_LOGI("开始并行处理 - 并发数: %d, 总块数: %zu", maxConcurrent, inputTiles.size());

    // 分批处理块
    for (size_t i = 0; i < inputTiles.size(); i += maxConcurrent) {
        futures.clear();

        // 启动当前批次的处理
        size_t batchEnd = std::min(i + maxConcurrent, inputTiles.size());
        for (size_t j = i; j < batchEnd; ++j) {
            // 为输出块分配内存
            outputTiles[j] = inputTiles[j]; // 复制基本信息
            if (!allocateTileData(outputTiles[j])) {
                STREAM_LOGE("分配输出块数据失败: %zu", j);
                return ProcessResult::ERROR_MEMORY_ALLOCATION;
            }

            // 异步处理块
            futures.push_back(std::async(std::launch::async,
                                         [this, &inputTiles, &outputTiles, &primaryLut, &secondaryLut, &params, j]() {
                                             return processTile(inputTiles[j], outputTiles[j],
                                                                primaryLut, secondaryLut, params);
                                         }));
        }

        // 等待当前批次完成
        for (size_t j = 0; j < futures.size(); ++j) {
            ProcessResult result = futures[j].get();
            if (result != ProcessResult::SUCCESS) {
                STREAM_LOGE("块处理失败: %zu", i + j);
                return result;
            }

            completedTiles++;

            // 更新进度
            if (progressCallback) {
                StreamingProgress progress;
                progress.processedTiles = completedTiles.load();
                progress.totalTiles = static_cast<int>(inputTiles.size());
                progress.processedBytes =
                        static_cast<size_t>(completedTiles.load()) * inputTiles[0].dataSize;
                progress.totalBytes = inputTiles.size() * inputTiles[0].dataSize;

                auto poolStats = MemoryPool::getInstance().getStats();
                progress.memoryUsage =
                        static_cast<double>(poolStats.totalAllocated) / config_.maxMemoryUsage;

                progressCallback(progress);
            }

            // 检查取消请求
            if (cancelCallback && cancelCallback()) {
                STREAM_LOGI("处理被用户取消");
                return ProcessResult::ERROR_PROCESSING_FAILED;
            }
        }

        // 检查内存压力
        if (checkMemoryPressure()) {
            STREAM_LOGW("检测到内存压力，触发优化");
            optimizeMemoryUsage();
        }
    }

    return ProcessResult::SUCCESS;
}

ProcessResult StreamingProcessor::processSequential(
        const std::vector<ImageTile> &inputTiles,
        std::vector<ImageTile> &outputTiles,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params,
        StreamingProgressCallback progressCallback,
        StreamingCancelCallback cancelCallback
) {
    STREAM_LOGI("开始串行处理 - 总块数: %zu", inputTiles.size());

    for (size_t i = 0; i < inputTiles.size(); ++i) {
        // 为输出块分配内存
        outputTiles[i] = inputTiles[i];
        if (!allocateTileData(outputTiles[i])) {
            STREAM_LOGE("分配输出块数据失败: %zu", i);
            return ProcessResult::ERROR_MEMORY_ALLOCATION;
        }

        // 处理块
        ProcessResult result = processTile(inputTiles[i], outputTiles[i], primaryLut, secondaryLut,
                                           params);
        if (result != ProcessResult::SUCCESS) {
            STREAM_LOGE("块处理失败: %zu", i);
            return result;
        }

        // 更新进度
        if (progressCallback) {
            StreamingProgress progress;
            progress.processedTiles = static_cast<int>(i + 1);
            progress.totalTiles = static_cast<int>(inputTiles.size());
            progress.processedBytes = (i + 1) * inputTiles[0].dataSize;
            progress.totalBytes = inputTiles.size() * inputTiles[0].dataSize;

            auto poolStats = MemoryPool::getInstance().getStats();
            progress.memoryUsage =
                    static_cast<double>(poolStats.totalAllocated) / config_.maxMemoryUsage;

            progressCallback(progress);
        }

        // 检查取消请求
        if (cancelCallback && cancelCallback()) {
            STREAM_LOGI("处理被用户取消");
            return ProcessResult::ERROR_PROCESSING_FAILED;
        }

        // 定期检查内存压力
        if (i % 4 == 0 && checkMemoryPressure()) {
            STREAM_LOGW("检测到内存压力，触发优化");
            optimizeMemoryUsage();
        }
    }

    return ProcessResult::SUCCESS;
}

ProcessResult StreamingProcessor::processTile(
        const ImageTile &inputTile,
        ImageTile &outputTile,
        const LutData &primaryLut,
        const LutData &secondaryLut,
        const ProcessingParams &params
) {
    // 创建临时ImageInfo结构
    ImageInfo inputInfo, outputInfo;

    inputInfo.width = inputTile.width;
    inputInfo.height = inputTile.height;
    inputInfo.stride = inputTile.width * 4;
    inputInfo.format = ANDROID_BITMAP_FORMAT_RGBA_8888;
    inputInfo.pixels = inputTile.data;
    inputInfo.pixelSize = inputTile.dataSize;

    outputInfo = inputInfo;
    outputInfo.pixels = outputTile.data;

    // 使用单线程处理（块已经足够小）
    return imageProcessor_->processSingleThreaded(inputInfo, outputInfo, primaryLut, secondaryLut,
                                                  params, nullptr);
}

ProcessResult StreamingProcessor::assembleTiles(
        const std::vector<ImageTile> &tiles,
        ImageInfo &output
) const {
    STREAM_LOGI("开始合成 %zu 个块到最终图片", tiles.size());

    uint8_t *outputPixels = static_cast<uint8_t *>(output.pixels);

    for (const auto &tile: tiles) {
        const uint8_t *tilePixels = static_cast<const uint8_t *>(tile.data);

        // 复制块数据到输出图片
        for (int row = 0; row < tile.height; ++row) {
            int outputRow = tile.originalY + row;
            if (outputRow >= output.height) break;

            const uint8_t *srcRow = tilePixels + (row * tile.width * 4);
            uint8_t *dstRow = outputPixels + (outputRow * output.stride) + (tile.originalX * 4);

            int copyWidth = std::min(tile.width, output.width - tile.originalX);
            std::memcpy(dstRow, srcRow, copyWidth * 4);
        }
    }

    return ProcessResult::SUCCESS;
}

bool StreamingProcessor::shouldUseStreamingForImage(int width, int height) const {
    size_t imageSize = static_cast<size_t>(width) * height * 4;
    return imageSize > LARGE_IMAGE_THRESHOLD;
}

size_t StreamingProcessor::estimateMemoryRequirement(int width, int height) const {
    // 估算处理所需的内存：输入 + 输出 + 处理缓冲区
    size_t imageSize = static_cast<size_t>(width) * height * 4;
    return imageSize * 3; // 3倍安全系数
}

bool StreamingProcessor::checkMemoryPressure() const {
    auto poolStats = MemoryPool::getInstance().getStats();
    double usage = static_cast<double>(poolStats.totalAllocated) / config_.maxMemoryUsage;
    return usage > config_.memoryPressureThreshold;
}

void StreamingProcessor::optimizeMemoryUsage() {
    // 清理内存池
    MemoryPool::getInstance().cleanup(false);

    // 清理块缓存
    cleanupTileCache();

    STREAM_LOGI("内存优化完成");
}

void StreamingProcessor::cleanupTileCache() {
    tileCache_.clear();
}

void StreamingProcessor::resetStats() {
    stats_ = ProcessingStats{};
    STREAM_LOGI("处理统计已重置");
}

// ProcessingStrategySelector 实现
ProcessingStrategySelector::Strategy ProcessingStrategySelector::selectOptimalStrategy(
        int width, int height,
        size_t availableMemory,
        const ProcessingParams &params
) {
    (void) params; // 抑制未使用参数警告
    size_t directMemory = calculateDirectMemoryRequirement(width, height);

    // 如果直接处理内存需求在可用内存的70%以内，使用直接处理
    if (directMemory <= availableMemory * 0.7) {
        return Strategy::DIRECT;
    }

    // 如果图片非常大，强制使用流式处理
    size_t imageSize = static_cast<size_t>(width) * height * 4;
    if (imageSize > 128 * 1024 * 1024) { // 128MB
        return Strategy::STREAMING;
    }

    // 其他情况使用混合策略
    return Strategy::HYBRID;
}

StreamingConfig ProcessingStrategySelector::generateOptimalConfig(
        int width, int height,
        size_t availableMemory
) {
    StreamingConfig config;

    // 根据可用内存调整配置
    config.maxMemoryUsage = availableMemory;
    config.maxTileSize = std::min(config.maxTileSize, availableMemory / 8); // 最多使用1/8内存作为单个块

    // 根据图片尺寸调整并发数
    size_t imageSize = static_cast<size_t>(width) * height * 4;
    if (imageSize > 256 * 1024 * 1024) { // 256MB+
        config.maxConcurrentTiles = 2;
    } else if (imageSize > 64 * 1024 * 1024) { // 64MB+
        config.maxConcurrentTiles = 3;
    } else {
        config.maxConcurrentTiles = 4;
    }

    return config;
}

size_t ProcessingStrategySelector::calculateDirectMemoryRequirement(int width, int height) {
    size_t imageSize = static_cast<size_t>(width) * height * 4;
    return imageSize * 3; // 输入 + 输出 + 处理缓冲区
}

size_t ProcessingStrategySelector::calculateStreamingMemoryRequirement(int width, int height,
                                                                       int tileSize) {
    (void) width; // 抑制未使用参数警告
    (void) height; // 抑制未使用参数警告
    size_t tileMemory = static_cast<size_t>(tileSize) * tileSize * 4 * 2; // 输入 + 输出块
    return tileMemory * 4; // 最多4个并发块
}