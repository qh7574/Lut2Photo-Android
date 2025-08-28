#ifndef LUT_IMAGE_PROCESSOR_H
#define LUT_IMAGE_PROCESSOR_H

#include "interfaces/media_processor_interface.h"
#include "utils/memory_manager.h"
#include "core/streaming_processor.h"
#include "utils/exception_handler.h"
#include "native_lut_processor.h"
#include <memory>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <queue>

/**
 * LUT图像处理器实现
 * 继承IImageProcessor接口，提供完整的LUT处理功能
 * 集成内存管理、流式处理和异常处理机制
 */
class LutImageProcessor : public IImageProcessor {
public:
    LutImageProcessor();

    virtual ~LutImageProcessor();

    // IMediaProcessor接口实现
    bool initialize(const ProcessingConfig &config) override;

    void cleanup() override;

    bool isInitialized() const override;

    std::unique_ptr<MediaFrame> processFrame(const MediaFrame &input) override;

    bool processFrameInPlace(MediaFrame &frame) override;

    std::future<std::unique_ptr<MediaFrame>> processFrameAsync(const MediaFrame &input) override;

    void cancelProcessing() override;

    std::vector<std::unique_ptr<MediaFrame>> processFrames(
            const std::vector<std::reference_wrapper<const MediaFrame>> &inputs) override;

    bool startStreaming(const std::string &inputPath, const std::string &outputPath) override;

    void stopStreaming() override;

    bool isStreaming() const override;

    ProcessingStatus getStatus() const override;

    float getProgress() const override;

    std::string getLastError() const override;

    void setProgressCallback(ProgressCallback callback) override;

    void setErrorCallback(ErrorCallback callback) override;

    bool updateConfig(const ProcessingConfig &config) override;

    ProcessingConfig getConfig() const override;

    double getAverageProcessingTime() const override;

    size_t getProcessedFrameCount() const override;

    void resetStatistics() override;

    size_t getMemoryUsage() const override;

    void optimizeMemoryUsage() override;

    // IImageProcessor接口实现
    std::unique_ptr<MediaFrame> processImage(const std::string &inputPath) override;

    bool processImageToFile(const std::string &inputPath, const std::string &outputPath) override;

    std::unique_ptr<MediaFrame>
    convertFormat(const MediaFrame &input, PixelFormat targetFormat) override;

    std::unique_ptr<MediaFrame> resize(const MediaFrame &input, int width, int height) override;

    MediaMetadata analyzeImage(const std::string &filePath) override;

    bool validateImageFormat(const MediaFrame &frame) override;

    // LUT特有功能
    bool loadLut(const std::string &lutPath);

    bool loadLutFromMemory(const void *lutData, size_t dataSize);

    void unloadLut();

    bool isLutLoaded() const;

    void setLutIntensity(float intensity);

    float getLutIntensity() const;

    // 高级处理选项
    void setDitheringEnabled(bool enabled);

    bool isDitheringEnabled() const;

    void setMultiThreadingEnabled(bool enabled);

    bool isMultiThreadingEnabled() const;

    // 内存管理控制
    void setMemoryLimit(size_t limitBytes);

    size_t getMemoryLimit() const;

    void enableMemoryOptimization(bool enable);

    bool isMemoryOptimizationEnabled() const;

    // 性能调优
    void setThreadCount(int count);

    int getThreadCount() const;

    void setProcessingQuality(QualityLevel quality);

    QualityLevel getProcessingQuality() const;

private:
    // 内部状态
    mutable std::mutex mutex_;
    std::atomic<bool> initialized_{false};
    std::atomic<ProcessingStatus> status_{ProcessingStatus::IDLE};
    std::atomic<float> progress_{0.0f};
    std::string lastError_;

    // 配置
    ProcessingConfig config_;

    // 核心组件
    MemoryManager *memoryManager_;
    std::unique_ptr<StreamingProcessor> streamingProcessor_;
    std::unique_ptr<NativeLutProcessor> lutProcessor_;

    // 回调函数
    ProgressCallback progressCallback_;
    ErrorCallback errorCallback_;

    // 异步处理
    std::atomic<bool> cancelRequested_{false};
    std::thread asyncWorker_;
    std::queue<std::function<void()>> taskQueue_;
    std::mutex taskMutex_;
    std::condition_variable taskCondition_;
    std::atomic<bool> workerRunning_{false};

    // 流式处理
    std::atomic<bool> streamingActive_{false};
    std::thread streamingWorker_;

    // 统计信息
    mutable std::mutex statsMutex_;
    size_t processedFrameCount_{0};
    double totalProcessingTime_{0.0};
    std::chrono::high_resolution_clock::time_point lastProcessingStart_;

    // LUT相关
    std::atomic<bool> lutLoaded_{false};
    std::string currentLutPath_;
    std::atomic<float> lutIntensity_{1.0f};

    // 处理选项
    std::atomic<bool> ditheringEnabled_{true};
    std::atomic<bool> multiThreadingEnabled_{true};
    std::atomic<bool> memoryOptimizationEnabled_{true};

    // 内部方法
    bool initializeComponents();

    void cleanupComponents();

    bool setupMemoryManager();

    bool setupStreamingProcessor();

    bool setupLutProcessor();

    void startAsyncWorker();

    void stopAsyncWorker();

    void asyncWorkerLoop();

    void startStreamingWorker(const std::string &inputPath, const std::string &outputPath);

    void stopStreamingWorker();

    void streamingWorkerLoop(const std::string &inputPath, const std::string &outputPath);

    std::unique_ptr<MediaFrame> processFrameInternal(const MediaFrame &input);

    bool processFrameInPlaceInternal(MediaFrame &frame);

    std::unique_ptr<MediaFrame> loadImageFromFile(const std::string &filePath);

    bool saveImageToFile(const MediaFrame &frame, const std::string &filePath);

    void updateProgress(float progress);

    void reportError(const std::string &error, int errorCode = -1);

    void recordProcessingTime(double timeMs);

    bool validateConfig(const ProcessingConfig &config);

    void applyConfig(const ProcessingConfig &config);

    // 内存管理辅助方法
    std::unique_ptr<MediaFrame> allocateFrame(int width, int height, PixelFormat format);

    void deallocateFrame(MediaFrame &frame);

    // 格式转换辅助方法
    bool convertPixelFormat(const MediaFrame &input, MediaFrame &output, PixelFormat targetFormat);

    bool resizeFrame(const MediaFrame &input, MediaFrame &output, int width, int height);

    // 异常处理辅助方法
    template<typename Func>
    auto executeWithExceptionHandling(Func &&func) -> decltype(func()) {
        try {
            return func();
        } catch (const MemoryException &e) {
            reportError(std::string("Memory error: ") + e.what(), static_cast<int>(e.getType()));
            ExceptionHandler::getInstance().handleException(
                    e.getType(), ExceptionSeverity::HIGH, "LutImageProcessor");
            throw;
        } catch (const std::exception &e) {
            reportError(std::string("Processing error: ") + e.what());
            ExceptionHandler::getInstance().handleException(
                    ExceptionType::PROCESSING_ERROR, ExceptionSeverity::MEDIUM,
                    "LutImageProcessor");
            throw;
        }
    }

    // 禁止拷贝和赋值
    LutImageProcessor(const LutImageProcessor &) = delete;

    LutImageProcessor &operator=(const LutImageProcessor &) = delete;
};

/**
 * LUT处理器工厂实现
 */
class LutProcessorFactory : public IProcessorFactory {
public:
    LutProcessorFactory() = default;

    virtual ~LutProcessorFactory() = default;

    std::unique_ptr<IImageProcessor> createImageProcessor() override {
        return std::make_unique<LutImageProcessor>();
    }

    std::unique_ptr<IVideoProcessor> createVideoProcessor() override {
        // 暂时返回nullptr，视频处理器将在后续实现
        return nullptr;
    }

    std::vector<PixelFormat> getSupportedImageFormats() const override {
        return {
                PixelFormat::RGBA8888,
                PixelFormat::RGB888,
                PixelFormat::BGRA8888,
                PixelFormat::BGR888
        };
    }

    std::vector<std::string> getSupportedVideoCodecs() const override {
        // 暂时返回空列表，视频编解码器将在后续实现
        return {};
    }

    bool isGPUSupported() const override {
        return MediaProcessorUtils::isGPUAvailable();
    }

    int getOptimalThreadCount() const override {
        return MediaProcessorUtils::getOptimalThreadCount();
    }

    size_t getAvailableMemory() const override {
        return MediaProcessorUtils::getAvailableMemory();
    }
};

// 便利函数
namespace LutProcessorUtils {
    // 注册LUT处理器工厂
    void registerLutProcessorFactory();

    // 创建LUT处理器实例
    std::unique_ptr<LutImageProcessor> createLutProcessor();

    // 创建预配置的处理器
    std::unique_ptr<LutImageProcessor> createHighQualityProcessor();

    std::unique_ptr<LutImageProcessor> createLowMemoryProcessor();

    std::unique_ptr<LutImageProcessor> createFastProcessor();

    // 配置工具
    ProcessingConfig createLutProcessingConfig(QualityLevel quality = QualityLevel::HIGH);

    ProcessingConfig createStreamingConfig(size_t maxMemoryMB = 256);
}

#endif // LUT_IMAGE_PROCESSOR_H