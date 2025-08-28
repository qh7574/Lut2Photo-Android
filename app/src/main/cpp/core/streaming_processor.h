#ifndef STREAMING_PROCESSOR_H
#define STREAMING_PROCESSOR_H

#include "image_processor.h"
#include "../utils/memory_pool.h"
#include "../interfaces/media_processor_interface.h"
#include <vector>
#include <memory>
#include <functional>
#include <android/log.h>

#define STREAM_TAG "StreamingProcessor"
#define STREAM_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, STREAM_TAG, __VA_ARGS__)
#define STREAM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, STREAM_TAG, __VA_ARGS__)
#define STREAM_LOGW(...) __android_log_print(ANDROID_LOG_WARN, STREAM_TAG, __VA_ARGS__)
#define STREAM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, STREAM_TAG, __VA_ARGS__)

/**
 * 图像块信息
 */
struct ImageTile {
    int x, y;           // 块的起始坐标
    int width, height;  // 块的尺寸
    int originalX, originalY; // 在原图中的坐标
    size_t dataSize;    // 数据大小
    void *data;         // 像素数据

    ImageTile() : x(0), y(0), width(0), height(0),
                  originalX(0), originalY(0), dataSize(0), data(nullptr) {}
};

/**
 * 流式处理配置
 */
struct StreamingConfig {
    size_t maxTileSize = 32 * 1024 * 1024;  // 32MB 最大块大小
    int tileOverlap = 16;                   // 块之间的重叠像素数
    int minTileSize = 512;                  // 最小块尺寸
    bool enableParallelProcessing = true;   // 启用并行处理
    int maxConcurrentTiles = 4;             // 最大并发处理块数
    bool enableProgressiveOutput = false;   // 启用渐进式输出
    int threadCount = 4;                    // 线程数量

    // 内存管理配置
    size_t maxMemoryUsage = 128 * 1024 * 1024; // 128MB 最大内存使用
    double memoryPressureThreshold = 0.8;      // 内存压力阈值
};

/**
 * 流式处理进度回调
 */
struct StreamingProgress {
    int processedTiles = 0;
    int totalTiles = 0;
    size_t processedBytes = 0;
    size_t totalBytes = 0;
    double memoryUsage = 0.0; // 内存使用率 (0.0-1.0)

    double getProgress() const {
        return totalTiles > 0 ? static_cast<double>(processedTiles) / totalTiles : 0.0;
    }
};

typedef std::function<void(const StreamingProgress &)> StreamingProgressCallback;
typedef std::function<bool()> StreamingCancelCallback; // 返回true表示取消处理

/**
 * 流式图像处理器
 * 专为大图片处理设计，支持分块处理和内存优化
 */
class StreamingProcessor {
public:
    StreamingProcessor();

    ~StreamingProcessor();

    // 配置管理
    void setConfig(const StreamingConfig &config);

    const StreamingConfig &getConfig() const { return config_; }

    // 流式处理主接口
    ProcessResult processImageStreaming(
            const ImageInfo &input,
            ImageInfo &output,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params,
            StreamingProgressCallback progressCallback = nullptr,
            StreamingCancelCallback cancelCallback = nullptr
    );

    // 内存优化处理（自动选择最佳策略）
    ProcessResult processImageOptimized(
            const ImageInfo &input,
            ImageInfo &output,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params,
            ProgressCallback progressCallback = nullptr
    );

    // 工具方法
    bool shouldUseStreamingForImage(int width, int height) const;

    std::vector<ImageTile> calculateOptimalTiling(int width, int height) const;

    size_t estimateMemoryRequirement(int width, int height) const;

    // 内存管理
    void optimizeMemoryUsage();

    // 统计信息
    struct ProcessingStats {
        size_t totalImagesProcessed = 0;
        size_t totalBytesProcessed = 0;
        size_t streamingProcessCount = 0;
        size_t directProcessCount = 0;
        double averageProcessingTime = 0.0;
        double peakMemoryUsage = 0.0;
    };

    ProcessingStats getStats() const { return stats_; }

    void resetStats();

private:
    // 内部处理方法
    ProcessResult processImageDirect(
            const ImageInfo &input,
            ImageInfo &output,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params,
            ProgressCallback progressCallback
    );

    ProcessResult processTile(
            const ImageTile &inputTile,
            ImageTile &outputTile,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params
    );

    // 分块管理
    std::vector<ImageTile> createTiles(const ImageInfo &image) const;

    bool allocateTileData(ImageTile &tile) const;

    void deallocateTileData(ImageTile &tile) const;

    // 内存管理
    bool checkMemoryPressure() const;

    void cleanupTileCache();

    // 并行处理
    ProcessResult processParallel(
            const std::vector<ImageTile> &inputTiles,
            std::vector<ImageTile> &outputTiles,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params,
            StreamingProgressCallback progressCallback,
            StreamingCancelCallback cancelCallback
    );

    // 串行处理
    ProcessResult processSequential(
            const std::vector<ImageTile> &inputTiles,
            std::vector<ImageTile> &outputTiles,
            const LutData &primaryLut,
            const LutData &secondaryLut,
            const ProcessingParams &params,
            StreamingProgressCallback progressCallback,
            StreamingCancelCallback cancelCallback
    );

    // 输出合成
    ProcessResult assembleTiles(
            const std::vector<ImageTile> &tiles,
            ImageInfo &output
    ) const;

    // 边缘处理（处理块之间的重叠区域）
    void blendTileEdges(
            const ImageTile &tile1,
            const ImageTile &tile2,
            ImageInfo &output
    ) const;

    // 成员变量
    StreamingConfig config_;
    std::unique_ptr<ImageProcessor> imageProcessor_;
    mutable ProcessingStats stats_;

    // 缓存管理
    std::vector<std::unique_ptr<ImageTile>> tileCache_;
    size_t maxCacheSize_ = 8; // 最多缓存8个块

    // 线程管理
    mutable std::mutex processingMutex_;
    std::atomic<bool> isProcessing_{false};

    // 常量定义
    static constexpr size_t LARGE_IMAGE_THRESHOLD = 64 * 1024 * 1024; // 64MB
    static constexpr int DEFAULT_TILE_SIZE = 2048; // 默认块尺寸
    static constexpr int MAX_TILE_SIZE = 4096;     // 最大块尺寸
};

/**
 * 自适应处理策略选择器
 */
class ProcessingStrategySelector {
public:
    enum class Strategy {
        DIRECT,      // 直接处理
        STREAMING,   // 流式处理
        HYBRID       // 混合策略
    };

    static Strategy selectOptimalStrategy(
            int width, int height,
            size_t availableMemory,
            const ProcessingParams &params
    );

    static StreamingConfig generateOptimalConfig(
            int width, int height,
            size_t availableMemory
    );

private:
    static size_t calculateDirectMemoryRequirement(int width, int height);

    static size_t calculateStreamingMemoryRequirement(int width, int height, int tileSize);
};

#endif // STREAMING_PROCESSOR_H