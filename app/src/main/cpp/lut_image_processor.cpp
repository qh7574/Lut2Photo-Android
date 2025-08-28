#include "lut_image_processor.h"

#ifdef LOG_TAG
#undef LOG_TAG
#endif

#include "native_lut_processor.h"
#include <chrono>
#include <algorithm>
#include <fstream>
#include <future>

#ifdef __ANDROID__

#include <android/log.h>
#include <android/bitmap.h>

#define LOG_TAG "LutImageProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <iostream>
#define LOGI(...) printf(__VA_ARGS__); printf("\n")
#define LOGW(...) printf("WARN: "); printf(__VA_ARGS__); printf("\n")
#define LOGE(...) printf("ERROR: "); printf(__VA_ARGS__); printf("\n")
#endif

// LutImageProcessor 实现
LutImageProcessor::LutImageProcessor() {
    LOGI("LutImageProcessor created");
}

LutImageProcessor::~LutImageProcessor() {
    cleanup();
    LOGI("LutImageProcessor destroyed");
}

bool LutImageProcessor::initialize(const ProcessingConfig &config) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (initialized_.load()) {
        LOGW("Processor already initialized");
        return true;
    }

    try {
        if (!validateConfig(config)) {
            reportError("Invalid configuration provided");
            return false;
        }

        config_ = config;

        if (!initializeComponents()) {
            reportError("Failed to initialize components");
            return false;
        }

        applyConfig(config_);
        startAsyncWorker();

        initialized_.store(true);
        status_.store(ProcessingStatus::IDLE);

        LOGI("LutImageProcessor initialized successfully");
        return true;

    } catch (const std::exception &e) {
        reportError(std::string("Initialization failed: ") + e.what());
        cleanup();
        return false;
    }
}

void LutImageProcessor::cleanup() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!initialized_.load()) {
        return;
    }

    cancelProcessing();
    stopStreaming();
    stopAsyncWorker();

    cleanupComponents();

    initialized_.store(false);
    status_.store(ProcessingStatus::IDLE);
    progress_.store(0.0f);

    LOGI("LutImageProcessor cleaned up");
}

bool LutImageProcessor::isInitialized() const {
    return initialized_.load();
}

std::unique_ptr<MediaFrame> LutImageProcessor::processFrame(const MediaFrame &input) {
    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return nullptr;
    }

    return executeWithExceptionHandling([&]() {
        auto start = std::chrono::high_resolution_clock::now();

        auto result = processFrameInternal(input);

        auto end = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
        recordProcessingTime(duration.count() / 1000.0);

        return result;
    });
}

bool LutImageProcessor::processFrameInPlace(MediaFrame &frame) {
    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return false;
    }

    return executeWithExceptionHandling([&]() {
        auto start = std::chrono::high_resolution_clock::now();

        bool result = processFrameInPlaceInternal(frame);

        auto end = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
        recordProcessingTime(duration.count() / 1000.0);

        return result;
    });
}

std::future<std::unique_ptr<MediaFrame>>
LutImageProcessor::processFrameAsync(const MediaFrame &input) {
    auto promise = std::make_shared<std::promise<std::unique_ptr<MediaFrame>>>();
    auto future = promise->get_future();

    if (!initialized_.load()) {
        promise->set_exception(std::make_exception_ptr(
                std::runtime_error("Processor not initialized")));
        return future;
    }

    // 创建输入帧的副本
    auto inputCopy = std::make_unique<MediaFrame>();
    inputCopy->width = input.width;
    inputCopy->height = input.height;
    inputCopy->format = input.format;
    inputCopy->dataSize = input.dataSize;

    if (input.data && input.dataSize > 0) {
        inputCopy->data = memoryManager_->allocate(input.dataSize);
        if (!inputCopy->data) {
            promise->set_exception(std::make_exception_ptr(
                    MemoryException(ExceptionType::MEMORY_ALLOCATION_FAILED,
                                    "Failed to allocate memory for async processing")));
            return future;
        }

        std::memcpy(inputCopy->data, input.data, input.dataSize);
        inputCopy->ownsData = true;
        inputCopy->deleter = [this, data = inputCopy->data]() {
            memoryManager_->deallocate(data);
        };
    }

    // 添加任务到队列
    {
        std::lock_guard<std::mutex> lock(taskMutex_);
        taskQueue_.push([this, promise, inputPtr = inputCopy.release()]() mutable {
            std::unique_ptr<MediaFrame> inputCopy(inputPtr);
            try {
                auto result = processFrame(*inputCopy);
                promise->set_value(std::move(result));
            } catch (...) {
                promise->set_exception(std::current_exception());
            }
        });
    }

    taskCondition_.notify_one();
    return future;
}

void LutImageProcessor::cancelProcessing() {
    cancelRequested_.store(true);

    if (status_.load() == ProcessingStatus::PROCESSING) {
        status_.store(ProcessingStatus::CANCELLED);
        LOGI("Processing cancelled");
    }
}

std::vector<std::unique_ptr<MediaFrame>> LutImageProcessor::processFrames(
        const std::vector<std::reference_wrapper<const MediaFrame>> &inputs) {

    std::vector<std::unique_ptr<MediaFrame>> results;
    results.reserve(inputs.size());

    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return results;
    }

    status_.store(ProcessingStatus::PROCESSING);

    try {
        for (size_t i = 0; i < inputs.size(); ++i) {
            if (cancelRequested_.load()) {
                status_.store(ProcessingStatus::CANCELLED);
                break;
            }

            updateProgress(static_cast<float>(i) / inputs.size());

            auto result = processFrame(inputs[i].get());
            results.push_back(std::move(result));
        }

        if (!cancelRequested_.load()) {
            status_.store(ProcessingStatus::COMPLETED);
            updateProgress(1.0f);
        }

    } catch (const std::exception &e) {
        reportError(std::string("Batch processing failed: ") + e.what());
        status_.store(ProcessingStatus::FAILED);
    }

    return results;
}

bool
LutImageProcessor::startStreaming(const std::string &inputPath, const std::string &outputPath) {
    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return false;
    }

    if (streamingActive_.load()) {
        LOGW("Streaming already active");
        return false;
    }

    try {
        startStreamingWorker(inputPath, outputPath);
        return true;
    } catch (const std::exception &e) {
        reportError(std::string("Failed to start streaming: ") + e.what());
        return false;
    }
}

void LutImageProcessor::stopStreaming() {
    if (streamingActive_.load()) {
        stopStreamingWorker();
    }
}

bool LutImageProcessor::isStreaming() const {
    return streamingActive_.load();
}

ProcessingStatus LutImageProcessor::getStatus() const {
    return status_.load();
}

float LutImageProcessor::getProgress() const {
    return progress_.load();
}

std::string LutImageProcessor::getLastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
}

void LutImageProcessor::setProgressCallback(ProgressCallback callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    progressCallback_ = std::move(callback);
}

void LutImageProcessor::setErrorCallback(ErrorCallback callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    errorCallback_ = std::move(callback);
}

bool LutImageProcessor::updateConfig(const ProcessingConfig &config) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!validateConfig(config)) {
        reportError("Invalid configuration provided");
        return false;
    }

    config_ = config;
    applyConfig(config_);

    LOGI("Configuration updated");
    return true;
}

ProcessingConfig LutImageProcessor::getConfig() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return config_;
}

double LutImageProcessor::getAverageProcessingTime() const {
    std::lock_guard<std::mutex> lock(statsMutex_);

    if (processedFrameCount_ == 0) {
        return 0.0;
    }

    return totalProcessingTime_ / processedFrameCount_;
}

size_t LutImageProcessor::getProcessedFrameCount() const {
    std::lock_guard<std::mutex> lock(statsMutex_);
    return processedFrameCount_;
}

void LutImageProcessor::resetStatistics() {
    std::lock_guard<std::mutex> lock(statsMutex_);
    processedFrameCount_ = 0;
    totalProcessingTime_ = 0.0;
    LOGI("Statistics reset");
}

size_t LutImageProcessor::getMemoryUsage() const {
    if (memoryManager_) {
        return memoryManager_->getTotalAllocatedBytes();
    }
    return 0;
}

void LutImageProcessor::optimizeMemoryUsage() {
    if (memoryManager_) {
        memoryManager_->optimizeMemoryUsage();
    }

    if (streamingProcessor_) {
        streamingProcessor_->optimizeMemoryUsage();
    }

    LOGI("Memory usage optimized");
}

// IImageProcessor接口实现
std::unique_ptr<MediaFrame> LutImageProcessor::processImage(const std::string &inputPath) {
    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return nullptr;
    }

    return executeWithExceptionHandling([&]() {
        auto inputFrame = loadImageFromFile(inputPath);
        if (!inputFrame) {
            reportError("Failed to load image from: " + inputPath);
            return std::unique_ptr<MediaFrame>(nullptr);
        }

        return processFrame(*inputFrame);
    });
}

bool
LutImageProcessor::processImageToFile(const std::string &inputPath, const std::string &outputPath) {
    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return false;
    }

    return executeWithExceptionHandling([&]() {
        auto processedFrame = processImage(inputPath);
        if (!processedFrame) {
            return false;
        }

        return saveImageToFile(*processedFrame, outputPath);
    });
}

std::unique_ptr<MediaFrame>
LutImageProcessor::convertFormat(const MediaFrame &input, PixelFormat targetFormat) {
    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return nullptr;
    }

    return executeWithExceptionHandling([&]() {
        auto output = allocateFrame(input.width, input.height, targetFormat);
        if (!output) {
            return std::unique_ptr<MediaFrame>(nullptr);
        }

        if (!convertPixelFormat(input, *output, targetFormat)) {
            return std::unique_ptr<MediaFrame>(nullptr);
        }

        return output;
    });
}

std::unique_ptr<MediaFrame>
LutImageProcessor::resize(const MediaFrame &input, int width, int height) {
    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return nullptr;
    }

    return executeWithExceptionHandling([&]() {
        auto output = allocateFrame(width, height, input.format);
        if (!output) {
            return std::unique_ptr<MediaFrame>(nullptr);
        }

        if (!resizeFrame(input, *output, width, height)) {
            return std::unique_ptr<MediaFrame>(nullptr);
        }

        return output;
    });
}

MediaMetadata LutImageProcessor::analyzeImage(const std::string &filePath) {
    MediaMetadata metadata;

    if (!MediaProcessorUtils::isValidMediaFile(filePath)) {
        return metadata;
    }

    metadata.type = MediaProcessorUtils::detectMediaType(filePath);
    metadata.filePath = filePath;

    // 尝试加载图像以获取尺寸信息
    auto frame = loadImageFromFile(filePath);
    if (frame) {
        metadata.width = frame->width;
        metadata.height = frame->height;
        metadata.format = frame->format;
    }

    return metadata;
}

bool LutImageProcessor::validateImageFormat(const MediaFrame &frame) {
    if (!frame.isValid()) {
        return false;
    }

    // 检查支持的格式
    switch (frame.format) {
        case PixelFormat::RGBA8888:
        case PixelFormat::RGB888:
        case PixelFormat::BGRA8888:
        case PixelFormat::BGR888:
            return true;
        default:
            return false;
    }
}

// LUT特有功能实现
bool LutImageProcessor::loadLut(const std::string &lutPath) {
    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return false;
    }

    if (!lutProcessor_) {
        reportError("LUT processor not available");
        return false;
    }

    try {
        if (lutProcessor_->loadLut(lutPath.c_str())) {
            currentLutPath_ = lutPath;
            lutLoaded_.store(true);
            LOGI("LUT loaded successfully: %s", lutPath.c_str());
            return true;
        } else {
            reportError("Failed to load LUT: " + lutPath);
            return false;
        }
    } catch (const std::exception &e) {
        reportError(std::string("LUT loading error: ") + e.what());
        return false;
    }
}

bool LutImageProcessor::loadLutFromMemory(const void *lutData, size_t dataSize) {
    if (!initialized_.load()) {
        reportError("Processor not initialized");
        return false;
    }

    if (!lutProcessor_) {
        reportError("LUT processor not available");
        return false;
    }

    try {
        if (lutProcessor_->loadLutFromMemory(lutData, dataSize)) {
            currentLutPath_ = "<memory>";
            lutLoaded_.store(true);
            LOGI("LUT loaded from memory successfully");
            return true;
        } else {
            reportError("Failed to load LUT from memory");
            return false;
        }
    } catch (const std::exception &e) {
        reportError(std::string("LUT loading error: ") + e.what());
        return false;
    }
}

void LutImageProcessor::unloadLut() {
    if (lutProcessor_) {
        lutProcessor_->unloadLut();
    }

    lutLoaded_.store(false);
    currentLutPath_.clear();
    LOGI("LUT unloaded");
}

bool LutImageProcessor::isLutLoaded() const {
    return lutLoaded_.load();
}

void LutImageProcessor::setLutIntensity(float intensity) {
    lutIntensity_.store(std::clamp(intensity, 0.0f, 1.0f));

    if (lutProcessor_) {
        lutProcessor_->setIntensity(lutIntensity_.load());
    }
}

float LutImageProcessor::getLutIntensity() const {
    return lutIntensity_.load();
}

void LutImageProcessor::setDitheringEnabled(bool enabled) {
    ditheringEnabled_.store(enabled);

    if (lutProcessor_) {
        lutProcessor_->setDitheringEnabled(enabled);
    }
}

bool LutImageProcessor::isDitheringEnabled() const {
    return ditheringEnabled_.load();
}

void LutImageProcessor::setMultiThreadingEnabled(bool enabled) {
    multiThreadingEnabled_.store(enabled);

    if (lutProcessor_) {
        lutProcessor_->setMultiThreadingEnabled(enabled);
    }
}

bool LutImageProcessor::isMultiThreadingEnabled() const {
    return multiThreadingEnabled_.load();
}

void LutImageProcessor::setMemoryLimit(size_t limitBytes) {
    if (memoryManager_) {
        memoryManager_->setMemoryLimit(limitBytes);
    }

    config_.maxMemoryUsage = limitBytes;
}

size_t LutImageProcessor::getMemoryLimit() const {
    if (memoryManager_) {
        return memoryManager_->getMemoryLimit();
    }
    return config_.maxMemoryUsage;
}

void LutImageProcessor::enableMemoryOptimization(bool enable) {
    memoryOptimizationEnabled_.store(enable);

    if (memoryManager_) {
        memoryManager_->enableAutoOptimization(enable);
    }
}

bool LutImageProcessor::isMemoryOptimizationEnabled() const {
    return memoryOptimizationEnabled_.load();
}

void LutImageProcessor::setThreadCount(int count) {
    config_.threadCount = std::max(1, count);

    if (lutProcessor_) {
        lutProcessor_->setThreadCount(config_.threadCount);
    }
}

int LutImageProcessor::getThreadCount() const {
    return config_.threadCount;
}

void LutImageProcessor::setProcessingQuality(QualityLevel quality) {
    config_.quality = quality;
    applyConfig(config_);
}

QualityLevel LutImageProcessor::getProcessingQuality() const {
    return config_.quality;
}

// 内部方法实现
bool LutImageProcessor::initializeComponents() {
    try {
        if (!setupMemoryManager()) {
            LOGE("Failed to setup memory manager");
            return false;
        }

        if (!setupStreamingProcessor()) {
            LOGE("Failed to setup streaming processor");
            return false;
        }

        if (!setupLutProcessor()) {
            LOGE("Failed to setup LUT processor");
            return false;
        }

        return true;

    } catch (const std::exception &e) {
        LOGE("Component initialization failed: %s", e.what());
        return false;
    }
}

void LutImageProcessor::cleanupComponents() {
    lutProcessor_.reset();
    streamingProcessor_.reset();
    memoryManager_ = nullptr;
}

bool LutImageProcessor::setupMemoryManager() {
    memoryManager_ = &MemoryManager::getInstance();

    if (config_.maxMemoryUsage > 0) {
        memoryManager_->setMemoryLimit(config_.maxMemoryUsage);
    }

    memoryManager_->enableAutoOptimization(memoryOptimizationEnabled_.load());

    return true;
}

bool LutImageProcessor::setupStreamingProcessor() {
    streamingProcessor_ = std::make_unique<StreamingProcessor>();
    return true;
}

bool LutImageProcessor::setupLutProcessor() {
    lutProcessor_ = std::make_unique<NativeLutProcessor>();

    lutProcessor_->setIntensity(lutIntensity_.load());
    lutProcessor_->setDitheringEnabled(ditheringEnabled_.load());
    lutProcessor_->setMultiThreadingEnabled(multiThreadingEnabled_.load());

    if (config_.threadCount > 0) {
        lutProcessor_->setThreadCount(config_.threadCount);
    }

    return true;
}

void LutImageProcessor::startAsyncWorker() {
    workerRunning_.store(true);
    asyncWorker_ = std::thread(&LutImageProcessor::asyncWorkerLoop, this);
}

void LutImageProcessor::stopAsyncWorker() {
    workerRunning_.store(false);
    taskCondition_.notify_all();

    if (asyncWorker_.joinable()) {
        asyncWorker_.join();
    }
}

void LutImageProcessor::asyncWorkerLoop() {
    while (workerRunning_.load()) {
        std::unique_lock<std::mutex> lock(taskMutex_);

        taskCondition_.wait(lock, [this] {
            return !taskQueue_.empty() || !workerRunning_.load();
        });

        if (!workerRunning_.load()) {
            break;
        }

        if (!taskQueue_.empty()) {
            auto task = std::move(taskQueue_.front());
            taskQueue_.pop();
            lock.unlock();

            task();
        }
    }
}

void LutImageProcessor::startStreamingWorker(const std::string &inputPath,
                                             const std::string &outputPath) {
    streamingActive_.store(true);
    streamingWorker_ = std::thread(&LutImageProcessor::streamingWorkerLoop, this, inputPath,
                                   outputPath);
}

void LutImageProcessor::stopStreamingWorker() {
    streamingActive_.store(false);

    if (streamingWorker_.joinable()) {
        streamingWorker_.join();
    }
}

void LutImageProcessor::streamingWorkerLoop(const std::string &inputPath,
                                            const std::string &outputPath) {
    (void) inputPath; // 抑制未使用参数警告
    (void) outputPath; // 抑制未使用参数警告
    try {
        if (!streamingProcessor_) {
            reportError("Streaming processor not available");
            return;
        }

        // 配置流式处理
        StreamingConfig streamConfig;
        streamConfig.maxMemoryUsage = config_.maxMemoryUsage;
        streamConfig.enableParallelProcessing = multiThreadingEnabled_.load();
        streamConfig.threadCount = config_.threadCount;

        // 设置进度回调
        StreamingProgressCallback progressCallback = [this](const StreamingProgress &progress) {
            updateProgress(static_cast<float>(progress.getProgress()));
            if (progressCallback_) {
                progressCallback_(static_cast<float>(progress.getProgress()), "Processing");
            }
        };

        // TODO: 实现文件加载到ImageInfo的逻辑
        // 这里需要实际的图像加载实现
        LOGW("Streaming processing from file not fully implemented yet");

        // 暂时标记为失败，需要实现文件加载逻辑
        status_.store(ProcessingStatus::FAILED);
        reportError("File-based streaming processing not implemented");

    } catch (const std::exception &e) {
        reportError(std::string("Streaming error: ") + e.what());
        status_.store(ProcessingStatus::FAILED);
    }

    streamingActive_.store(false);
}

std::unique_ptr<MediaFrame> LutImageProcessor::processFrameInternal(const MediaFrame &input) {
    if (!validateImageFormat(input)) {
        reportError("Invalid image format");
        return nullptr;
    }

    if (!lutLoaded_.load()) {
        reportError("No LUT loaded");
        return nullptr;
    }

    // 分配输出帧
    auto output = allocateFrame(input.width, input.height, input.format);
    if (!output) {
        reportError("Failed to allocate output frame");
        return nullptr;
    }

    // 构造输入和输出ImageInfo
    ImageInfo inputImage;
    inputImage.width = input.width;
    inputImage.height = input.height;
    inputImage.stride = input.width * ((input.format == PixelFormat::RGBA8888 ||
                                        input.format == PixelFormat::BGRA8888) ? 4 : 3);
    inputImage.pixels = const_cast<void *>(input.data);
    inputImage.pixelSize = input.dataSize;

    ImageInfo outputImage;
    outputImage.width = output->width;
    outputImage.height = output->height;
    outputImage.stride = output->width * ((output->format == PixelFormat::RGBA8888 ||
                                           output->format == PixelFormat::BGRA8888) ? 4 : 3);
    outputImage.pixels = output->data;
    outputImage.pixelSize = output->dataSize;

    // 执行LUT处理
    ProcessingParams params;
    params.inputData = static_cast<const uint8_t *>(input.data);
    params.outputData = static_cast<uint8_t *>(output->data);
    params.width = input.width;
    params.height = input.height;
    params.channels = (input.format == PixelFormat::RGBA8888 ||
                       input.format == PixelFormat::BGRA8888) ? 4 : 3;
    params.intensity = lutIntensity_.load();
    params.enableDithering = ditheringEnabled_.load();

    auto result = lutProcessor_->processImage(inputImage, outputImage, params);
    if (result != ProcessResult::SUCCESS) {
        reportError("LUT processing failed");
        return nullptr;
    }

    return output;
}

bool LutImageProcessor::processFrameInPlaceInternal(MediaFrame &frame) {
    if (!validateImageFormat(frame)) {
        reportError("Invalid image format");
        return false;
    }

    if (!lutLoaded_.load()) {
        reportError("No LUT loaded");
        return false;
    }

    // 执行原地LUT处理
    ImageInfo inputImage;
    inputImage.width = frame.width;
    inputImage.height = frame.height;
    inputImage.stride = frame.width * ((frame.format == PixelFormat::RGBA8888 ||
                                        frame.format == PixelFormat::BGRA8888) ? 4 : 3);
    inputImage.pixels = frame.data;
    inputImage.pixelSize = frame.dataSize;

    ImageInfo outputImage = inputImage; // 原地处理，输出和输入相同

    ProcessingParams params;
    params.inputData = static_cast<const uint8_t *>(frame.data);
    params.outputData = static_cast<uint8_t *>(frame.data);
    params.width = frame.width;
    params.height = frame.height;
    params.channels = (frame.format == PixelFormat::RGBA8888 ||
                       frame.format == PixelFormat::BGRA8888) ? 4 : 3;
    params.intensity = lutIntensity_.load();
    params.enableDithering = ditheringEnabled_.load();

    auto result = lutProcessor_->processImage(inputImage, outputImage, params);
    return result == ProcessResult::SUCCESS;
}

std::unique_ptr<MediaFrame> LutImageProcessor::loadImageFromFile(const std::string &filePath) {
    (void) filePath; // 抑制未使用参数警告
    // 这里应该实现实际的图像加载逻辑
    // 暂时返回nullptr，实际实现需要根据平台选择合适的图像加载库
    LOGW("loadImageFromFile not implemented yet");
    return nullptr;
}

bool LutImageProcessor::saveImageToFile(const MediaFrame &frame, const std::string &filePath) {
    (void) frame; // 抑制未使用参数警告
    (void) filePath; // 抑制未使用参数警告
    // 这里应该实现实际的图像保存逻辑
    // 暂时返回false，实际实现需要根据平台选择合适的图像保存库
    LOGW("saveImageToFile not implemented yet");
    return false;
}

void LutImageProcessor::updateProgress(float progress) {
    progress_.store(std::clamp(progress, 0.0f, 1.0f));

    if (progressCallback_) {
        progressCallback_(progress_.load(), "");
    }
}

void LutImageProcessor::reportError(const std::string &error, int errorCode) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        lastError_ = error;
    }

    LOGE("%s", error.c_str());

    if (errorCallback_) {
        errorCallback_(error, errorCode);
    }
}

void LutImageProcessor::recordProcessingTime(double timeMs) {
    std::lock_guard<std::mutex> lock(statsMutex_);
    totalProcessingTime_ += timeMs;
    processedFrameCount_++;
}

bool LutImageProcessor::validateConfig(const ProcessingConfig &config) {
    if (config.threadCount < 0) {
        return false;
    }

    if (config.lutIntensity < 0.0f || config.lutIntensity > 1.0f) {
        return false;
    }

    return true;
}

void LutImageProcessor::applyConfig(const ProcessingConfig &config) {
    setLutIntensity(config.lutIntensity);
    setThreadCount(config.threadCount);
    setMemoryLimit(config.maxMemoryUsage);
    setMultiThreadingEnabled(config.mode != ProcessingMode::SINGLE_THREADED);
}

std::unique_ptr<MediaFrame>
LutImageProcessor::allocateFrame(int width, int height, PixelFormat format) {
    size_t dataSize = MediaProcessorUtils::calculateFrameSize(width, height, format);
    if (dataSize == 0) {
        return nullptr;
    }

    void *data = memoryManager_->allocate(dataSize);
    if (!data) {
        return nullptr;
    }

    auto frame = std::make_unique<MediaFrame>(data, dataSize, width, height, format);
    frame->ownsData = true;
    frame->deleter = [this, data]() {
        memoryManager_->deallocate(data);
    };

    return frame;
}

void LutImageProcessor::deallocateFrame(MediaFrame &frame) {
    if (frame.ownsData && frame.deleter) {
        frame.deleter();
        frame.data = nullptr;
        frame.ownsData = false;
    }
}

bool LutImageProcessor::convertPixelFormat(const MediaFrame &input, MediaFrame &output,
                                           PixelFormat targetFormat) {
    (void) input; // 抑制未使用参数警告
    (void) output; // 抑制未使用参数警告
    (void) targetFormat; // 抑制未使用参数警告
    // 这里应该实现实际的像素格式转换逻辑
    LOGW("convertPixelFormat not implemented yet");
    return false;
}

bool
LutImageProcessor::resizeFrame(const MediaFrame &input, MediaFrame &output, int width, int height) {
    (void) input; // 抑制未使用参数警告
    (void) output; // 抑制未使用参数警告
    (void) width; // 抑制未使用参数警告
    (void) height; // 抑制未使用参数警告
    // 这里应该实现实际的图像缩放逻辑
    LOGW("resizeFrame not implemented yet");
    return false;
}

// LutProcessorUtils 实现
namespace LutProcessorUtils {

    void registerLutProcessorFactory() {
        auto factory = std::make_unique<LutProcessorFactory>();
        ProcessorRegistry::getInstance().registerFactory("LutProcessor", std::move(factory));
        ProcessorRegistry::getInstance().setDefaultFactory("LutProcessor");
        LOGI("LUT processor factory registered");
    }

    std::unique_ptr<LutImageProcessor> createLutProcessor() {
        return std::make_unique<LutImageProcessor>();
    }

    std::unique_ptr<LutImageProcessor> createHighQualityProcessor() {
        auto processor = std::make_unique<LutImageProcessor>();
        auto config = createLutProcessingConfig(QualityLevel::HIGH);
        processor->initialize(config);
        return processor;
    }

    std::unique_ptr<LutImageProcessor> createLowMemoryProcessor() {
        auto processor = std::make_unique<LutImageProcessor>();
        auto config = MediaProcessorUtils::createLowMemoryConfig();
        config.lutIntensity = 1.0f;
        processor->initialize(config);
        return processor;
    }

    std::unique_ptr<LutImageProcessor> createFastProcessor() {
        auto processor = std::make_unique<LutImageProcessor>();
        auto config = createLutProcessingConfig(QualityLevel::LOW);
        config.mode = ProcessingMode::MULTI_THREADED;
        config.threadCount = MediaProcessorUtils::getOptimalThreadCount();
        processor->initialize(config);
        return processor;
    }

    ProcessingConfig createLutProcessingConfig(QualityLevel quality) {
        ProcessingConfig config = MediaProcessorUtils::createDefaultImageConfig();
        config.quality = quality;
        config.lutIntensity = 1.0f;

        switch (quality) {
            case QualityLevel::LOW:
                config.mode = ProcessingMode::SINGLE_THREADED;
                config.enableGPU = false;
                break;
            case QualityLevel::MEDIUM:
                config.mode = ProcessingMode::MULTI_THREADED;
                config.threadCount = 2;
                break;
            case QualityLevel::HIGH:
            case QualityLevel::ULTRA:
                config.mode = ProcessingMode::MULTI_THREADED;
                config.threadCount = MediaProcessorUtils::getOptimalThreadCount();
                break;
        }

        return config;
    }

    ProcessingConfig createStreamingConfig(size_t maxMemoryMB) {
        ProcessingConfig config = MediaProcessorUtils::createDefaultImageConfig();
        config.enableStreaming = true;
        config.maxMemoryUsage = maxMemoryMB * 1024 * 1024;
        config.mode = ProcessingMode::MULTI_THREADED;

        return config;
    }

} // namespace LutProcessorUtils