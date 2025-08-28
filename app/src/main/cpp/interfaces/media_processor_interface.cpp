#include "media_processor_interface.h"
#include <unordered_map>
#include <mutex>
#include <thread>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <sys/stat.h>

#ifdef __ANDROID__

#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG "MediaProcessorInterface"
#endif
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <iostream>
#define LOGI(...) printf(__VA_ARGS__); printf("\n")
#define LOGW(...) printf("WARN: "); printf(__VA_ARGS__); printf("\n")
#define LOGE(...) printf("ERROR: "); printf(__VA_ARGS__); printf("\n")
#endif

// ProcessorRegistry 实现
ProcessorRegistry &ProcessorRegistry::getInstance() {
    static ProcessorRegistry instance;
    return instance;
}

void ProcessorRegistry::registerFactory(const std::string &name,
                                        std::unique_ptr<IProcessorFactory> factory) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!factory) {
        LOGE("Cannot register null factory for %s", name.c_str());
        return;
    }

    if (factories_.find(name) != factories_.end()) {
        LOGW("Factory %s already exists, replacing", name.c_str());
    }

    factories_[name] = std::move(factory);
    LOGI("Registered factory: %s", name.c_str());

    // 如果这是第一个工厂，设为默认
    if (defaultFactoryName_.empty()) {
        defaultFactoryName_ = name;
        LOGI("Set %s as default factory", name.c_str());
    }
}

void ProcessorRegistry::unregisterFactory(const std::string &name) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = factories_.find(name);
    if (it != factories_.end()) {
        factories_.erase(it);
        LOGI("Unregistered factory: %s", name.c_str());

        // 如果删除的是默认工厂，重新选择默认工厂
        if (defaultFactoryName_ == name) {
            if (!factories_.empty()) {
                defaultFactoryName_ = factories_.begin()->first;
                LOGI("Set %s as new default factory", defaultFactoryName_.c_str());
            } else {
                defaultFactoryName_.clear();
                LOGI("No default factory available");
            }
        }
    } else {
        LOGW("Factory %s not found for unregistration", name.c_str());
    }
}

IProcessorFactory *ProcessorRegistry::getFactory(const std::string &name) const {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = factories_.find(name);
    if (it != factories_.end()) {
        return it->second.get();
    }

    LOGW("Factory %s not found", name.c_str());
    return nullptr;
}

std::vector<std::string> ProcessorRegistry::getAvailableFactories() const {
    std::lock_guard<std::mutex> lock(mutex_);

    std::vector<std::string> names;
    names.reserve(factories_.size());

    for (const auto &pair: factories_) {
        names.push_back(pair.first);
    }

    return names;
}

void ProcessorRegistry::setDefaultFactory(const std::string &name) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (factories_.find(name) != factories_.end()) {
        defaultFactoryName_ = name;
        LOGI("Set %s as default factory", name.c_str());
    } else {
        LOGE("Cannot set %s as default factory - not found", name.c_str());
    }
}

IProcessorFactory *ProcessorRegistry::getDefaultFactory() const {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!defaultFactoryName_.empty()) {
        auto it = factories_.find(defaultFactoryName_);
        if (it != factories_.end()) {
            return it->second.get();
        }
    }

    LOGW("No default factory available");
    return nullptr;
}

// MediaProcessorUtils 实现
namespace MediaProcessorUtils {

    std::string pixelFormatToString(PixelFormat format) {
        switch (format) {
            case PixelFormat::RGBA8888:
                return "RGBA8888";
            case PixelFormat::RGB888:
                return "RGB888";
            case PixelFormat::BGRA8888:
                return "BGRA8888";
            case PixelFormat::BGR888:
                return "BGR888";
            case PixelFormat::YUV420P:
                return "YUV420P";
            case PixelFormat::NV21:
                return "NV21";
            case PixelFormat::NV12:
                return "NV12";
            case PixelFormat::UNKNOWN:
            default:
                return "UNKNOWN";
        }
    }

    PixelFormat stringToPixelFormat(const std::string &formatStr) {
        std::string upperStr = formatStr;
        std::transform(upperStr.begin(), upperStr.end(), upperStr.begin(), ::toupper);

        if (upperStr == "RGBA8888") return PixelFormat::RGBA8888;
        if (upperStr == "RGB888") return PixelFormat::RGB888;
        if (upperStr == "BGRA8888") return PixelFormat::BGRA8888;
        if (upperStr == "BGR888") return PixelFormat::BGR888;
        if (upperStr == "YUV420P") return PixelFormat::YUV420P;
        if (upperStr == "NV21") return PixelFormat::NV21;
        if (upperStr == "NV12") return PixelFormat::NV12;

        return PixelFormat::UNKNOWN;
    }

    size_t calculateFrameSize(int width, int height, PixelFormat format) {
        if (width <= 0 || height <= 0) {
            return 0;
        }

        switch (format) {
            case PixelFormat::RGBA8888:
            case PixelFormat::BGRA8888:
                return static_cast<size_t>(width) * height * 4;
            case PixelFormat::RGB888:
            case PixelFormat::BGR888:
                return static_cast<size_t>(width) * height * 3;
            case PixelFormat::YUV420P:
            case PixelFormat::NV21:
            case PixelFormat::NV12:
                return static_cast<size_t>(width) * height * 3 / 2;
            case PixelFormat::UNKNOWN:
            default:
                return 0;
        }
    }

    bool isFormatSupported(PixelFormat format) {
        return format != PixelFormat::UNKNOWN;
    }

    int getOptimalThreadCount() {
        int hardwareConcurrency = static_cast<int>(std::thread::hardware_concurrency());

        if (hardwareConcurrency <= 0) {
            // 如果无法检测到硬件并发数，使用保守值
            return 2;
        }

        // 对于图像处理，通常使用CPU核心数或稍少一些
        // 为系统其他任务保留一些资源
        if (hardwareConcurrency <= 2) {
            return hardwareConcurrency;
        } else if (hardwareConcurrency <= 4) {
            return hardwareConcurrency - 1;
        } else {
            return hardwareConcurrency - 2;
        }
    }

    size_t getAvailableMemory() {
#ifdef __ANDROID__
        // Android平台的内存检测
        std::ifstream meminfo("/proc/meminfo");
        if (!meminfo.is_open()) {
            LOGW("Cannot read /proc/meminfo");
            return 0;
        }

        std::string line;
        size_t availableKB = 0;

        while (std::getline(meminfo, line)) {
            if (line.find("MemAvailable:") == 0) {
                std::istringstream iss(line);
                std::string label;
                iss >> label >> availableKB;
                break;
            }
        }

        if (availableKB == 0) {
            // 如果没有MemAvailable，尝试计算MemFree + Buffers + Cached
            meminfo.clear();
            meminfo.seekg(0);

            size_t memFree = 0, buffers = 0, cached = 0;

            while (std::getline(meminfo, line)) {
                std::istringstream iss(line);
                std::string label;
                size_t value;

                if (line.find("MemFree:") == 0) {
                    iss >> label >> value;
                    memFree = value;
                } else if (line.find("Buffers:") == 0) {
                    iss >> label >> value;
                    buffers = value;
                } else if (line.find("Cached:") == 0) {
                    iss >> label >> value;
                    cached = value;
                }
            }

            availableKB = memFree + buffers + cached;
        }

        return availableKB * 1024; // 转换为字节
#else
        // 其他平台的简单实现
        return 1024 * 1024 * 1024; // 假设1GB可用内存
#endif
    }

    bool isGPUAvailable() {
#ifdef __ANDROID__
        // 在Android上，大多数设备都有GPU
        // 这里可以添加更详细的GPU检测逻辑
        return true;
#else
        // 其他平台的GPU检测
        return false;
#endif
    }

    MediaType detectMediaType(const std::string &filePath) {
        if (filePath.empty()) {
            return MediaType::UNKNOWN;
        }

        // 获取文件扩展名
        size_t dotPos = filePath.find_last_of('.');
        if (dotPos == std::string::npos) {
            return MediaType::UNKNOWN;
        }

        std::string extension = filePath.substr(dotPos + 1);
        std::transform(extension.begin(), extension.end(), extension.begin(), ::tolower);

        // 图像格式
        if (extension == "jpg" || extension == "jpeg" || extension == "png" ||
            extension == "bmp" || extension == "gif" || extension == "tiff" ||
            extension == "tif" || extension == "webp") {
            return MediaType::IMAGE;
        }

        // 视频格式
        if (extension == "mp4" || extension == "avi" || extension == "mov" ||
            extension == "mkv" || extension == "wmv" || extension == "flv" ||
            extension == "webm" || extension == "m4v" || extension == "3gp") {
            return MediaType::VIDEO;
        }

        // 音频格式
        if (extension == "mp3" || extension == "wav" || extension == "aac" ||
            extension == "ogg" || extension == "flac" || extension == "m4a") {
            return MediaType::AUDIO;
        }

        return MediaType::UNKNOWN;
    }

    bool isValidMediaFile(const std::string &filePath) {
        if (filePath.empty()) {
            return false;
        }

        // 检查文件是否存在
        struct stat buffer;
        if (stat(filePath.c_str(), &buffer) != 0) {
            return false;
        }

        // 检查是否为常规文件
        if (!S_ISREG(buffer.st_mode)) {
            return false;
        }

        // 检查文件大小
        if (buffer.st_size <= 0) {
            return false;
        }

        // 检查媒体类型
        MediaType type = detectMediaType(filePath);
        return type != MediaType::UNKNOWN;
    }

    ProcessingConfig createDefaultImageConfig() {
        ProcessingConfig config;
        config.quality = QualityLevel::HIGH;
        config.mode = ProcessingMode::MULTI_THREADED;
        config.enableGPU = isGPUAvailable();
        config.enableStreaming = false;
        config.threadCount = getOptimalThreadCount();
        config.maxMemoryUsage = getAvailableMemory() / 4; // 使用25%的可用内存
        config.outputFormat = PixelFormat::RGBA8888;
        config.timeout = std::chrono::milliseconds(30000);
        config.enableProgressCallback = true;

        return config;
    }

    ProcessingConfig createDefaultVideoConfig() {
        ProcessingConfig config;
        config.quality = QualityLevel::MEDIUM; // 视频处理默认使用中等质量
        config.mode = ProcessingMode::MULTI_THREADED;
        config.enableGPU = isGPUAvailable();
        config.enableStreaming = true; // 视频默认启用流式处理
        config.threadCount = getOptimalThreadCount();
        config.maxMemoryUsage = getAvailableMemory() / 2; // 视频处理可以使用更多内存
        config.outputFormat = PixelFormat::YUV420P; // 视频常用格式
        config.timeout = std::chrono::milliseconds(300000); // 5分钟超时
        config.enableProgressCallback = true;
        config.maintainAspectRatio = true;

        return config;
    }

    ProcessingConfig createLowMemoryConfig() {
        ProcessingConfig config;
        config.quality = QualityLevel::LOW;
        config.mode = ProcessingMode::SINGLE_THREADED;
        config.enableGPU = false; // 低内存模式禁用GPU
        config.enableStreaming = true; // 启用流式处理以减少内存使用
        config.threadCount = 1;
        config.maxMemoryUsage = getAvailableMemory() / 8; // 只使用12.5%的可用内存
        config.outputFormat = PixelFormat::RGB888; // 使用较少内存的格式
        config.timeout = std::chrono::milliseconds(60000);
        config.enableProgressCallback = true;

        return config;
    }

} // namespace MediaProcessorUtils