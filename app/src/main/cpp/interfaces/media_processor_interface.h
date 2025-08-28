#ifndef MEDIA_PROCESSOR_INTERFACE_H
#define MEDIA_PROCESSOR_INTERFACE_H

#include <memory>
#include <string>
#include <vector>
#include <functional>
#include <chrono>
#include <future>

// 前向声明
struct MediaFrame;
struct ProcessingConfig;
struct MediaMetadata;

// 媒体类型枚举
enum class MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    UNKNOWN
};

// 像素格式枚举
enum class PixelFormat {
    RGBA8888,
    RGB888,
    BGRA8888,
    BGR888,
    YUV420P,
    NV21,
    NV12,
    UNKNOWN
};

// 处理状态枚举
enum class ProcessingStatus {
    IDLE,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
};

// 处理质量级别
enum class QualityLevel {
    LOW,
    MEDIUM,
    HIGH,
    ULTRA
};

// 处理模式
enum class ProcessingMode {
    SINGLE_THREADED,
    MULTI_THREADED,
    GPU_ACCELERATED,
    HYBRID
};

// 媒体帧结构
struct MediaFrame {
    void *data = nullptr;
    size_t dataSize = 0;
    int width = 0;
    int height = 0;
    PixelFormat format = PixelFormat::UNKNOWN;
    int stride = 0;
    std::chrono::microseconds timestamp{0};

    // 视频特有属性
    int frameIndex = -1;
    double frameRate = 0.0;

    // 内存管理
    bool ownsData = false;
    std::function<void()> deleter;

    MediaFrame() = default;

    MediaFrame(void *d, size_t size, int w, int h, PixelFormat fmt)
            : data(d), dataSize(size), width(w), height(h), format(fmt) {}

    ~MediaFrame() {
        if (ownsData && deleter) {
            deleter();
        }
    }

    // 禁止拷贝，允许移动
    MediaFrame(const MediaFrame &) = delete;

    MediaFrame &operator=(const MediaFrame &) = delete;

    MediaFrame(MediaFrame &&other) noexcept
            : data(other.data), dataSize(other.dataSize), width(other.width),
              height(other.height), format(other.format), stride(other.stride),
              timestamp(other.timestamp), frameIndex(other.frameIndex),
              frameRate(other.frameRate), ownsData(other.ownsData),
              deleter(std::move(other.deleter)) {
        other.data = nullptr;
        other.ownsData = false;
    }

    MediaFrame &operator=(MediaFrame &&other) noexcept {
        if (this != &other) {
            if (ownsData && deleter) {
                deleter();
            }

            data = other.data;
            dataSize = other.dataSize;
            width = other.width;
            height = other.height;
            format = other.format;
            stride = other.stride;
            timestamp = other.timestamp;
            frameIndex = other.frameIndex;
            frameRate = other.frameRate;
            ownsData = other.ownsData;
            deleter = std::move(other.deleter);

            other.data = nullptr;
            other.ownsData = false;
        }
        return *this;
    }

    bool isValid() const {
        return data != nullptr && dataSize > 0 && width > 0 && height > 0;
    }

    size_t getExpectedSize() const {
        switch (format) {
            case PixelFormat::RGBA8888:
            case PixelFormat::BGRA8888:
                return width * height * 4;
            case PixelFormat::RGB888:
            case PixelFormat::BGR888:
                return width * height * 3;
            case PixelFormat::YUV420P:
                return width * height * 3 / 2;
            case PixelFormat::NV21:
            case PixelFormat::NV12:
                return width * height * 3 / 2;
            default:
                return 0;
        }
    }
};

// 处理配置结构
struct ProcessingConfig {
    QualityLevel quality = QualityLevel::HIGH;
    ProcessingMode mode = ProcessingMode::MULTI_THREADED;
    bool enableGPU = true;
    bool enableStreaming = false;
    size_t maxMemoryUsage = 0; // 0表示无限制
    int threadCount = 0; // 0表示自动检测

    // LUT处理特有配置
    std::string lutPath;
    float lutIntensity = 1.0f;

    // 视频处理特有配置
    int startFrame = 0;
    int endFrame = -1; // -1表示处理到结尾
    bool maintainAspectRatio = true;

    // 输出配置
    PixelFormat outputFormat = PixelFormat::RGBA8888;
    int outputWidth = 0; // 0表示保持原始尺寸
    int outputHeight = 0;

    // 性能配置
    std::chrono::milliseconds timeout{30000}; // 30秒超时
    bool enableProgressCallback = true;

    ProcessingConfig() = default;
};

// 媒体元数据结构
struct MediaMetadata {
    MediaType type = MediaType::UNKNOWN;
    std::string filePath;
    size_t fileSize = 0;

    // 图像/视频共有属性
    int width = 0;
    int height = 0;
    PixelFormat format = PixelFormat::UNKNOWN;

    // 视频特有属性
    double duration = 0.0; // 秒
    double frameRate = 0.0;
    int totalFrames = 0;
    std::string codec;

    // 其他属性
    std::chrono::system_clock::time_point creationTime;
    std::string description;

    bool isValid() const {
        return type != MediaType::UNKNOWN && width > 0 && height > 0;
    }
};

// 进度回调类型
typedef std::function<void(float progress, const std::string &status)> ProgressCallback;

// 错误回调类型
typedef std::function<void(const std::string &error, int errorCode)> ErrorCallback;

/**
 * 媒体处理器基础接口
 * 为图像和视频处理提供统一的接口
 */
class IMediaProcessor {
public:
    virtual ~IMediaProcessor() = default;

    // 基础处理接口
    virtual bool initialize(const ProcessingConfig &config) = 0;

    virtual void cleanup() = 0;

    virtual bool isInitialized() const = 0;

    // 同步处理接口
    virtual std::unique_ptr<MediaFrame> processFrame(const MediaFrame &input) = 0;

    virtual bool processFrameInPlace(MediaFrame &frame) = 0;

    // 异步处理接口
    virtual std::future<std::unique_ptr<MediaFrame>> processFrameAsync(const MediaFrame &input) = 0;

    virtual void cancelProcessing() = 0;

    // 批处理接口
    virtual std::vector<std::unique_ptr<MediaFrame>> processFrames(
            const std::vector<std::reference_wrapper<const MediaFrame>> &inputs) = 0;

    // 流式处理接口（主要用于视频）
    virtual bool startStreaming(const std::string &inputPath, const std::string &outputPath) = 0;

    virtual void stopStreaming() = 0;

    virtual bool isStreaming() const = 0;

    // 状态查询
    virtual ProcessingStatus getStatus() const = 0;

    virtual float getProgress() const = 0;

    virtual std::string getLastError() const = 0;

    // 回调设置
    virtual void setProgressCallback(ProgressCallback callback) = 0;

    virtual void setErrorCallback(ErrorCallback callback) = 0;

    // 配置管理
    virtual bool updateConfig(const ProcessingConfig &config) = 0;

    virtual ProcessingConfig getConfig() const = 0;

    // 性能统计
    virtual double getAverageProcessingTime() const = 0;

    virtual size_t getProcessedFrameCount() const = 0;

    virtual void resetStatistics() = 0;

    // 内存管理
    virtual size_t getMemoryUsage() const = 0;

    virtual void optimizeMemoryUsage() = 0;
};

/**
 * 图像处理器接口
 * 专门用于静态图像处理
 */
class IImageProcessor : public IMediaProcessor {
public:
    // 图像特有的处理方法
    virtual std::unique_ptr<MediaFrame> processImage(const std::string &inputPath) = 0;

    virtual bool
    processImageToFile(const std::string &inputPath, const std::string &outputPath) = 0;

    // 图像格式转换
    virtual std::unique_ptr<MediaFrame>
    convertFormat(const MediaFrame &input, PixelFormat targetFormat) = 0;

    virtual std::unique_ptr<MediaFrame> resize(const MediaFrame &input, int width, int height) = 0;

    // 图像分析
    virtual MediaMetadata analyzeImage(const std::string &filePath) = 0;

    virtual bool validateImageFormat(const MediaFrame &frame) = 0;
};

/**
 * 视频处理器接口
 * 专门用于视频处理
 */
class IVideoProcessor : public IMediaProcessor {
public:
    // 视频特有的处理方法
    virtual bool processVideo(const std::string &inputPath, const std::string &outputPath) = 0;

    virtual std::unique_ptr<MediaFrame>
    extractFrame(const std::string &videoPath, int frameIndex) = 0;

    // 视频分析
    virtual MediaMetadata analyzeVideo(const std::string &filePath) = 0;

    virtual std::vector<std::unique_ptr<MediaFrame>> extractFrames(
            const std::string &videoPath, int startFrame, int endFrame) = 0;

    // 视频编码/解码
    virtual bool setEncoder(const std::string &codecName) = 0;

    virtual bool setDecoder(const std::string &codecName) = 0;

    virtual std::vector<std::string> getSupportedCodecs() const = 0;

    // 帧率控制
    virtual bool setOutputFrameRate(double fps) = 0;

    virtual double getInputFrameRate() const = 0;
};

/**
 * 处理器工厂接口
 * 用于创建不同类型的处理器实例
 */
class IProcessorFactory {
public:
    virtual ~IProcessorFactory() = default;

    // 创建处理器实例
    virtual std::unique_ptr<IImageProcessor> createImageProcessor() = 0;

    virtual std::unique_ptr<IVideoProcessor> createVideoProcessor() = 0;

    // 查询支持的格式
    virtual std::vector<PixelFormat> getSupportedImageFormats() const = 0;

    virtual std::vector<std::string> getSupportedVideoCodecs() const = 0;

    // 系统能力查询
    virtual bool isGPUSupported() const = 0;

    virtual int getOptimalThreadCount() const = 0;

    virtual size_t getAvailableMemory() const = 0;
};

/**
 * 处理器注册表
 * 用于管理和获取处理器工厂
 */
class ProcessorRegistry {
public:
    static ProcessorRegistry &getInstance();

    // 工厂注册
    void registerFactory(const std::string &name, std::unique_ptr<IProcessorFactory> factory);

    void unregisterFactory(const std::string &name);

    // 工厂获取
    IProcessorFactory *getFactory(const std::string &name) const;

    std::vector<std::string> getAvailableFactories() const;

    // 默认工厂
    void setDefaultFactory(const std::string &name);

    IProcessorFactory *getDefaultFactory() const;

private:
    ProcessorRegistry() = default;

    ~ProcessorRegistry() = default;

    ProcessorRegistry(const ProcessorRegistry &) = delete;

    ProcessorRegistry &operator=(const ProcessorRegistry &) = delete;

    std::unordered_map<std::string, std::unique_ptr<IProcessorFactory>> factories_;
    std::string defaultFactoryName_;
    mutable std::mutex mutex_;
};

// 便利函数
namespace MediaProcessorUtils {
    // 格式转换工具
    std::string pixelFormatToString(PixelFormat format);

    PixelFormat stringToPixelFormat(const std::string &formatStr);

    // 大小计算工具
    size_t calculateFrameSize(int width, int height, PixelFormat format);

    bool isFormatSupported(PixelFormat format);

    // 性能工具
    int getOptimalThreadCount();

    size_t getAvailableMemory();

    bool isGPUAvailable();

    // 文件工具
    MediaType detectMediaType(const std::string &filePath);

    bool isValidMediaFile(const std::string &filePath);

    // 配置工具
    ProcessingConfig createDefaultImageConfig();

    ProcessingConfig createDefaultVideoConfig();

    ProcessingConfig createLowMemoryConfig();
}

#endif // MEDIA_PROCESSOR_INTERFACE_H