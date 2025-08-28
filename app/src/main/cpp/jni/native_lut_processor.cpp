#include "../include/native_lut_processor.h"
#include "../utils/memory_manager.h"
#include "../lut_image_processor.h"
#include "../interfaces/media_processor_interface.h"
#include "../utils/exception_handler.h"
#include "../core/image_processor.h"
#include "../core/lut_processor.h"
#include "../utils/bitmap_utils.h"
#include <sstream>
#include <memory>
#include <map>
#include <mutex>
#include <algorithm>

// 全局变量
static std::map<jlong, std::unique_ptr<LutImageProcessor>> g_enhanced_processors;
static MemoryManager *g_global_memory_manager = nullptr;
static std::mutex g_processor_mutex;
static bool g_init_flag = false;
static JavaVM *g_java_vm = nullptr;

// 初始化全局组件
void initializeGlobalComponents() {
    if (g_init_flag) return;

    try {
        // 初始化全局内存管理器
        g_global_memory_manager = &MemoryManager::getInstance();
        g_global_memory_manager->setMemoryLimit(2048LL * 1024 * 1024); // 2GB默认限制，支持大图片处理

        // 注册LUT处理器工厂
        LutProcessorUtils::registerLutProcessorFactory();

        // 设置异常处理器
        auto &exceptionHandler = ExceptionHandler::getInstance();
        exceptionHandler.setExceptionThreshold(ExceptionType::MEMORY_ALLOCATION_FAILED, 3,
                                               std::chrono::seconds(60));
        exceptionHandler.setExceptionThreshold(ExceptionType::MEMORY_LIMIT_EXCEEDED, 2,
                                               std::chrono::seconds(30));

        // 设置JavaVM引用
        if (g_java_vm) {
            g_global_memory_manager->setJavaVM(g_java_vm);
        }

        g_init_flag = true;
        LOGD("全局组件初始化完成");
    } catch (const std::exception &e) {
        LOGE("初始化全局组件失败: %s", e.what());
    }
}

// 获取增强处理器
LutImageProcessor *getEnhancedProcessor(jlong handle) {
    std::lock_guard<std::mutex> lock(g_processor_mutex);
    auto it = g_enhanced_processors.find(handle);
    return (it != g_enhanced_processors.end()) ? it->second.get() : nullptr;
}

// Native处理器实现（保持向后兼容）
NativeLutProcessor::NativeLutProcessor() : nativeMemoryUsage_(0) {
    initializeGlobalComponents();
    LOGD("NativeLutProcessor构造函数调用");
}

NativeLutProcessor::~NativeLutProcessor() {
    clearLuts();
    LOGD("NativeLutProcessor析构函数调用，释放内存: %zu bytes", nativeMemoryUsage_);
}

ProcessResult NativeLutProcessor::loadLutFromArray(const float *lutData, int lutSize) {
    if (!lutData || lutSize <= 0) {
        LOGE("无效的LUT数据参数");
        return ProcessResult::ERROR_INVALID_PARAMETERS;
    }

    try {
        primaryLut_.size = lutSize;
        size_t dataSize = lutSize * lutSize * lutSize * 3;

        // 使用全局内存管理器分配内存
        if (g_global_memory_manager) {
            void *lutMemory = g_global_memory_manager->allocate(dataSize * sizeof(float));
            if (!lutMemory) {
                LOGE("内存分配失败");
                return ProcessResult::ERROR_MEMORY_ALLOCATION;
            }

            primaryLut_.data.resize(dataSize);
            std::memcpy(primaryLut_.data.data(), lutData, dataSize * sizeof(float));
            primaryLut_.isLoaded = true;

            nativeMemoryUsage_ += dataSize * sizeof(float);
        } else {
            // 回退到标准分配
            primaryLut_.data.resize(dataSize);
            std::memcpy(primaryLut_.data.data(), lutData, dataSize * sizeof(float));
            primaryLut_.isLoaded = true;
        }

        LOGI("主LUT加载成功，尺寸: %dx%dx%d, 数据大小: %zu", lutSize, lutSize, lutSize, dataSize);
        return ProcessResult::SUCCESS;
    } catch (const std::exception &e) {
        LOGE("加载LUT时发生异常: %s", e.what());
        return ProcessResult::ERROR_MEMORY_ALLOCATION;
    }
}

ProcessResult NativeLutProcessor::loadSecondaryLutFromArray(const float *lutData, int lutSize) {
    if (!lutData || lutSize <= 0) {
        LOGE("无效的第二LUT数据参数");
        return ProcessResult::ERROR_INVALID_PARAMETERS;
    }

    try {
        secondaryLut_.size = lutSize;
        size_t dataSize = lutSize * lutSize * lutSize * 3;

        // 使用全局内存管理器分配内存
        if (g_global_memory_manager) {
            void *lutMemory = g_global_memory_manager->allocate(dataSize * sizeof(float));
            if (!lutMemory) {
                LOGE("内存分配失败");
                return ProcessResult::ERROR_MEMORY_ALLOCATION;
            }
        }

        secondaryLut_.data.resize(dataSize);
        std::memcpy(secondaryLut_.data.data(), lutData, dataSize * sizeof(float));
        secondaryLut_.isLoaded = true;

        nativeMemoryUsage_ += dataSize * sizeof(float);

        LOGI("第二LUT加载成功，尺寸: %dx%dx%d", lutSize, lutSize, lutSize);
        return ProcessResult::SUCCESS;
    } catch (const std::exception &e) {
        LOGE("加载第二LUT时发生异常: %s", e.what());
        return ProcessResult::ERROR_MEMORY_ALLOCATION;
    }
}

void NativeLutProcessor::clearLuts() {
    if (primaryLut_.isLoaded) {
        size_t dataSize = primaryLut_.data.size() * sizeof(float);
        primaryLut_.data.clear();
        primaryLut_.isLoaded = false;
        nativeMemoryUsage_ -= dataSize;
    }

    if (secondaryLut_.isLoaded) {
        size_t dataSize = secondaryLut_.data.size() * sizeof(float);
        secondaryLut_.data.clear();
        secondaryLut_.isLoaded = false;
        nativeMemoryUsage_ -= dataSize;
    }

    LOGD("LUT数据已清理");
}

ProcessResult NativeLutProcessor::processImage(
        const ImageInfo &inputImage,
        ImageInfo &outputImage,
        const ProcessingParams &params,
        NativeProgressCallback callback
) {
    if (!primaryLut_.isLoaded) {
        LOGE("主LUT未加载");
        return ProcessResult::ERROR_LUT_NOT_LOADED;
    }

    try {
        // 检查内存压力
        if (g_global_memory_manager && g_global_memory_manager->isMemoryPressureHigh()) {
            LOGW("内存压力高，触发优化");
            g_global_memory_manager->handleMemoryPressure();
        }

        // 根据参数选择处理方式
        if (params.useMultiThreading && getOptimalThreadCount() > 1) {
            return processImageMultiThreaded(inputImage, outputImage, params, callback);
        } else {
            return processImageSingleThreaded(inputImage, outputImage, params, callback);
        }
    } catch (const std::exception &e) {
        LOGE("图片处理时发生异常: %s", e.what());
        return ProcessResult::ERROR_PROCESSING_FAILED;
    }
}

void *NativeLutProcessor::allocateNativeMemory(size_t size) {
    if (g_global_memory_manager) {
        return g_global_memory_manager->allocate(size);
    }
    return malloc(size);
}

void NativeLutProcessor::freeNativeMemory(void *ptr) {
    if (g_global_memory_manager && ptr) {
        // 注意：这里需要知道原始大小，实际使用中应该记录分配大小
        // 这是一个简化实现
        free(ptr);
    } else if (ptr) {
        free(ptr);
    }
}

size_t NativeLutProcessor::getNativeMemoryUsage() const {
    if (g_global_memory_manager) {
        return g_global_memory_manager->getTotalAllocatedBytes();
    }
    return nativeMemoryUsage_;
}

void NativeLutProcessor::forceGarbageCollection() {
    if (g_global_memory_manager) {
        g_global_memory_manager->forceGarbageCollection();
    }
}

int NativeLutProcessor::getOptimalThreadCount() const {
    return std::thread::hardware_concurrency();
}

ProcessResult NativeLutProcessor::processImageSingleThreaded(
        const ImageInfo &input,
        ImageInfo &output,
        const ProcessingParams &params,
        NativeProgressCallback callback
) {
    return ImageProcessor::processSingleThreaded(
            input, output, primaryLut_, secondaryLut_, params, callback
    );
}

ProcessResult NativeLutProcessor::processImageMultiThreaded(
        const ImageInfo &input,
        ImageInfo &output,
        const ProcessingParams &params,
        NativeProgressCallback callback
) {
    return ImageProcessor::processMultiThreaded(
            input, output, primaryLut_, secondaryLut_, params, callback
    );
}

// 配置方法实现
void NativeLutProcessor::setMultiThreadingEnabled(bool enabled) {
    multiThreadingEnabled_ = enabled;
}

bool NativeLutProcessor::isMultiThreadingEnabled() const {
    return multiThreadingEnabled_;
}

void NativeLutProcessor::setThreadCount(int count) {
    threadCount_ = count;
}

int NativeLutProcessor::getThreadCount() const {
    return threadCount_;
}

void NativeLutProcessor::setIntensity(float intensity) {
    intensity_ = std::max(0.0f, std::min(1.0f, intensity));
}

float NativeLutProcessor::getIntensity() const {
    return intensity_;
}

void NativeLutProcessor::setDitheringEnabled(bool enabled) {
    ditheringEnabled_ = enabled;
}

bool NativeLutProcessor::isDitheringEnabled() const {
    return ditheringEnabled_;
}

bool NativeLutProcessor::loadLut(const char *lutPath) {
    // TODO: 实现从文件加载LUT的逻辑
    (void) lutPath; // 抑制未使用参数警告
    return false;
}

bool NativeLutProcessor::loadLutFromMemory(const void *lutData, size_t dataSize) {
    // TODO: 实现从内存加载LUT的逻辑
    (void) lutData; // 抑制未使用参数警告
    (void) dataSize; // 抑制未使用参数警告
    return false;
}

void NativeLutProcessor::unloadLut() {
    // TODO: 实现卸载LUT的逻辑
    clearLuts();
}

// JNI接口实现
extern "C" {

// JNI_OnLoad - 初始化
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved; // 抑制未使用参数警告
    g_java_vm = vm;
    // 设置MemoryManager的JVM指针
    MemoryManager::getInstance().setJavaVM(vm);
    initializeGlobalComponents();
    LOGI("Native库加载完成");
    return JNI_VERSION_1_6;
}

// JNI_OnUnload - 清理
JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void) vm; // 抑制未使用参数警告
    (void) reserved; // 抑制未使用参数警告
    std::lock_guard<std::mutex> lock(g_processor_mutex);
    g_enhanced_processors.clear();
    g_global_memory_manager = nullptr;
    g_java_vm = nullptr;
    g_init_flag = false;
    LOGI("Native库卸载完成");
}

// 原有接口保持兼容
JNIEXPORT jlong JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeCreate(
        JNIEnv *env, jobject thiz
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    try {
        initializeGlobalComponents();
        auto processor = new NativeLutProcessor();
        LOGD("Native处理器创建成功，地址: %p", processor);
        return reinterpret_cast<jlong>(processor);
    } catch (const std::exception &e) {
        LOGE("创建Native处理器失败: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    if (handle != 0) {
        auto processor = reinterpret_cast<NativeLutProcessor *>(handle);
        LOGD("销毁Native处理器，地址: %p", processor);
        delete processor;
    }
}

JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeLoadLut(
        JNIEnv *env, jobject thiz, jlong handle, jfloatArray lutData, jint lutSize
) {
    (void) thiz; // 抑制未使用参数警告
    if (handle == 0) {
        LOGE("无效的处理器句柄");
        return static_cast<jint>(ProcessResult::ERROR_INVALID_PARAMETERS);
    }

    auto processor = reinterpret_cast<NativeLutProcessor *>(handle);

    // 获取Java数组数据
    jfloat *lutArray = env->GetFloatArrayElements(lutData, nullptr);
    if (!lutArray) {
        LOGE("无法获取LUT数组数据");
        return static_cast<jint>(ProcessResult::ERROR_INVALID_PARAMETERS);
    }

    jsize arrayLength = env->GetArrayLength(lutData);
    LOGD("加载LUT，数组长度: %d, LUT尺寸: %d", arrayLength, lutSize);

    ProcessResult result = processor->loadLutFromArray(lutArray, lutSize);

    // 释放Java数组
    env->ReleaseFloatArrayElements(lutData, lutArray, JNI_ABORT);

    return static_cast<jint>(result);
}

JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeProcessBitmap(
        JNIEnv *env, jobject thiz, jlong handle, jobject inputBitmap, jobject outputBitmap,
        jfloat strength, jfloat lut2Strength, jint quality, jint ditherType,
        jboolean useMultiThreading
) {
    (void) thiz; // 抑制未使用参数警告
    if (handle == 0) {
        LOGE("无效的处理器句柄");
        return static_cast<jint>(ProcessResult::ERROR_INVALID_PARAMETERS);
    }

    auto processor = reinterpret_cast<NativeLutProcessor *>(handle);

    // 获取输入Bitmap信息
    ImageInfo inputInfo, outputInfo;
    if (!BitmapUtils::getBitmapInfo(env, inputBitmap, inputInfo)) {
        LOGE("无法获取输入Bitmap信息");
        return static_cast<jint>(ProcessResult::ERROR_INVALID_BITMAP);
    }

    if (!BitmapUtils::getBitmapInfo(env, outputBitmap, outputInfo)) {
        LOGE("无法获取输出Bitmap信息");
        return static_cast<jint>(ProcessResult::ERROR_INVALID_BITMAP);
    }

    // 锁定Bitmap像素
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inputInfo.pixels) !=
        ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("无法锁定输入Bitmap像素");
        return static_cast<jint>(ProcessResult::ERROR_INVALID_BITMAP);
    }

    if (AndroidBitmap_lockPixels(env, outputBitmap, &outputInfo.pixels) !=
        ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("无法锁定输出Bitmap像素");
        AndroidBitmap_unlockPixels(env, inputBitmap);
        return static_cast<jint>(ProcessResult::ERROR_INVALID_BITMAP);
    }

    // 设置处理参数
    ProcessingParams params;
    params.strength = strength;
    params.lut2Strength = lut2Strength;
    params.quality = quality;
    params.ditherType = ditherType;
    params.useMultiThreading = useMultiThreading;

    // 执行处理
    ProcessResult result = processor->processImage(inputInfo, outputInfo, params);

    // 解锁Bitmap像素
    AndroidBitmap_unlockPixels(env, inputBitmap);
    AndroidBitmap_unlockPixels(env, outputBitmap);

    return static_cast<jint>(result);
}

JNIEXPORT jlong JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeGetMemoryUsage(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    if (handle == 0) {
        return 0;
    }

    auto processor = reinterpret_cast<NativeLutProcessor *>(handle);
    return static_cast<jlong>(processor->getNativeMemoryUsage());
}

JNIEXPORT jstring JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeGetMemoryStats(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) thiz; // 抑制未使用参数警告
    (void) handle; // 抑制未使用参数警告
    if (handle == 0) {
        return env->NewStringUTF("无效句柄");
    }

    if (!g_global_memory_manager) {
        return env->NewStringUTF("内存管理器未初始化");
    }

    std::string stats = g_global_memory_manager->getDetailedStats();
    return env->NewStringUTF(stats.c_str());
}

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeSetMemoryLimit(
        JNIEnv *env, jobject thiz, jlong handle, jlong limitBytes
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    (void) handle; // 抑制未使用参数警告
    if (handle == 0 || !g_global_memory_manager) {
        return;
    }

    g_global_memory_manager->setMemoryLimit(static_cast<size_t>(limitBytes));

    LOGD("设置Native内存限制: %ld bytes (%.2f MB)",
         static_cast<long>(limitBytes), limitBytes / (1024.0 * 1024.0));
}

JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeIsNearMemoryLimit(
        JNIEnv *env, jobject thiz, jlong handle, jfloat threshold
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    (void) handle; // 抑制未使用参数警告
    if (handle == 0 || !g_global_memory_manager) {
        return JNI_FALSE;
    }

    double usage = g_global_memory_manager->getMemoryUsageRatio();
    bool isNear = usage >= threshold;

    return isNear ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeForceGC(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    if (handle != 0) {
        auto processor = reinterpret_cast<NativeLutProcessor *>(handle);
        processor->forceGarbageCollection();
    }
}

// 内存配置接口
JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeSetMemoryConfig(
        JNIEnv *env, jobject thiz, jlong handle, jint maxMemoryMB, jboolean enablePooling,
        jboolean enableCompression
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    try {
        if (handle == 0) {
            LOGE("无效的处理器句柄");
            return -1;
        }

        if (!g_global_memory_manager) {
            LOGE("内存管理器未初始化");
            return -1;
        }

        // 设置内存限制，确保至少1024MB
        size_t memoryLimit = static_cast<size_t>(maxMemoryMB) * 1024 * 1024;
        if (memoryLimit < 1024 * 1024 * 1024) {
            memoryLimit = 1024 * 1024 * 1024; // 最小1024MB (1GB)
            LOGW("内存限制过小，自动调整为1024MB");
        }
        g_global_memory_manager->setMemoryLimit(memoryLimit);

        // 配置内存池（如果支持）
        if (enablePooling) {
            // 配置内存池参数
            size_t maxPoolSize = memoryLimit / 2; // 使用一半内存作为池大小
            g_global_memory_manager->configureMemoryPool(maxPoolSize, 0.8);
            LOGD("内存池已配置，最大池大小: %zu bytes", maxPoolSize);
        }

        // 配置压缩（如果支持）
        if (enableCompression) {
            // 这里可以添加压缩相关的配置
            LOGD("启用内存压缩功能");
        }

        LOGD("内存配置已更新: 最大内存=%dMB, 内存池=%s, 压缩=%s",
             maxMemoryMB,
             enablePooling ? "启用" : "禁用",
             enableCompression ? "启用" : "禁用");

        return 0;
    } catch (const std::exception &e) {
        LOGE("设置内存配置失败: %s", e.what());
        return -1;
    }
}

// 新增的增强处理器接口
JNIEXPORT jlong JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeCreateEnhanced(
        JNIEnv *env, jobject thiz
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    try {
        initializeGlobalComponents();

        auto processor = LutProcessorUtils::createLutProcessor();
        if (!processor) {
            LOGE("创建增强处理器失败");
            return 0;
        }

        ProcessingConfig config = LutProcessorUtils::createLutProcessingConfig();
        processor->initialize(config);

        jlong handle = reinterpret_cast<jlong>(processor.get());

        std::lock_guard<std::mutex> lock(g_processor_mutex);
        g_enhanced_processors[handle] = std::move(processor);

        LOGD("增强处理器创建成功，句柄: %lld", (long long) handle);
        return handle;
    } catch (const std::exception &e) {
        LOGE("创建增强处理器失败: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeDestroyEnhanced(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    if (handle != 0) {
        std::lock_guard<std::mutex> lock(g_processor_mutex);
        auto it = g_enhanced_processors.find(handle);
        if (it != g_enhanced_processors.end()) {
            it->second->cleanup();
            g_enhanced_processors.erase(it);
            LOGD("增强处理器已销毁，句柄: %lld", (long long) handle);
        }
    }
}

JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeLoadLutEnhanced(
        JNIEnv *env, jobject thiz, jlong handle, jfloatArray lutData, jint lutSize
) {
    (void) thiz; // 抑制未使用参数警告
    auto processor = getEnhancedProcessor(handle);
    if (!processor) {
        LOGE("无效的增强处理器句柄");
        return static_cast<jint>(ProcessResult::ERROR_INVALID_PARAMETERS);
    }

    jfloat *lutArray = env->GetFloatArrayElements(lutData, nullptr);
    if (!lutArray) {
        LOGE("无法获取LUT数组数据");
        return static_cast<jint>(ProcessResult::ERROR_INVALID_PARAMETERS);
    }

    bool success = processor->loadLutFromMemory(lutArray, lutSize * sizeof(float));

    env->ReleaseFloatArrayElements(lutData, lutArray, JNI_ABORT);

    return success ? static_cast<jint>(ProcessResult::SUCCESS) :
           static_cast<jint>(ProcessResult::ERROR_MEMORY_ALLOCATION);
}

JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeProcessBitmapEnhanced(
        JNIEnv *env, jobject thiz, jlong handle, jobject inputBitmap, jobject outputBitmap,
        jfloat strength, jfloat lut2Strength, jint quality, jint ditherType,
        jboolean useMultiThreading
) {
    (void) thiz; // 抑制未使用参数警告
    (void) lut2Strength; // 抑制未使用参数警告
    (void) ditherType; // 抑制未使用参数警告
    auto processor = getEnhancedProcessor(handle);
    if (!processor) {
        LOGE("无效的增强处理器句柄");
        return static_cast<jint>(ProcessResult::ERROR_INVALID_PARAMETERS);
    }

    try {
        // 创建MediaFrame从Bitmap
        MediaFrame inputFrame, outputFrame;

        // 获取Bitmap信息
        AndroidBitmapInfo bitmapInfo;
        if (AndroidBitmap_getInfo(env, inputBitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("无法获取输入Bitmap信息");
            return static_cast<jint>(ProcessResult::ERROR_INVALID_BITMAP);
        }

        inputFrame.width = bitmapInfo.width;
        inputFrame.height = bitmapInfo.height;
        inputFrame.format = PixelFormat::RGBA8888;

        // 锁定像素数据
        void *inputPixels;
        if (AndroidBitmap_lockPixels(env, inputBitmap, &inputPixels) !=
            ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("无法锁定输入Bitmap像素");
            return static_cast<jint>(ProcessResult::ERROR_INVALID_BITMAP);
        }

        void *outputPixels;
        if (AndroidBitmap_lockPixels(env, outputBitmap, &outputPixels) !=
            ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("无法锁定输出Bitmap像素");
            AndroidBitmap_unlockPixels(env, inputBitmap);
            return static_cast<jint>(ProcessResult::ERROR_INVALID_BITMAP);
        }

        // 设置MediaFrame数据
        size_t dataSize = inputFrame.width * inputFrame.height * 4;
        inputFrame.data = inputPixels;
        inputFrame.dataSize = dataSize;

        // 设置处理配置
        ProcessingConfig config;
        config.quality = static_cast<QualityLevel>(quality);
        config.enableGPU = false; // 在CPU模式下运行
        config.enableStreaming = true;
        config.mode = useMultiThreading ? ProcessingMode::MULTI_THREADED
                                        : ProcessingMode::SINGLE_THREADED;
        config.maxMemoryUsage = 256 * 1024 * 1024; // 256MB

        processor->updateConfig(config);
        processor->setLutIntensity(strength);

        // 处理图像
        auto result = processor->processFrame(inputFrame);

        if (result && result->data && result->dataSize > 0) {
            // 复制结果到输出Bitmap
            std::memcpy(outputPixels, result->data, std::min(dataSize, result->dataSize));
        }

        // 解锁像素
        AndroidBitmap_unlockPixels(env, inputBitmap);
        AndroidBitmap_unlockPixels(env, outputBitmap);

        return result ? static_cast<jint>(ProcessResult::SUCCESS) :
               static_cast<jint>(ProcessResult::ERROR_PROCESSING_FAILED);

    } catch (const std::exception &e) {
        LOGE("增强处理器处理失败: %s", e.what());
        return static_cast<jint>(ProcessResult::ERROR_PROCESSING_FAILED);
    }
}

// 性能测试接口
JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_PerformanceTestRunner_runAllTests(
        JNIEnv *env, jobject thiz
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    // 这里会调用性能测试套件
    // 实际实现需要包含test_runner.cpp中的TestRunner类
    LOGI("性能测试接口被调用 - 需要链接测试模块");
}

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_PerformanceTestRunner_runQuickTests(
        JNIEnv *env, jobject thiz
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    LOGI("快速性能测试接口被调用 - 需要链接测试模块");
}

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_PerformanceTestRunner_runMemoryTests(
        JNIEnv *env, jobject thiz
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    LOGI("内存性能测试接口被调用 - 需要链接测试模块");
}

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_PerformanceTestRunner_runProcessingTests(
        JNIEnv *env, jobject thiz
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    LOGI("处理性能测试接口被调用 - 需要链接测试模块");
}

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_PerformanceTestRunner_runStressTests(
        JNIEnv *env, jobject thiz
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    // TODO: 实现压力测试
}

// 全局组件管理接口
JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeInitializeGlobalComponents(
        JNIEnv *env, jobject thiz, jint memoryLimitMB
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    try {
        if (!g_init_flag) {
            initializeGlobalComponents();
            if (g_global_memory_manager) {
                g_global_memory_manager->setMemoryLimit(memoryLimitMB * 1024 * 1024);
            }
        }
        return 0;
    } catch (const std::exception &e) {
        LOGE("初始化全局组件失败: %s", e.what());
        return -1;
    }
}

JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeCleanupGlobalComponents(
        JNIEnv *env, jobject thiz
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    try {
        std::lock_guard<std::mutex> lock(g_processor_mutex);
        g_enhanced_processors.clear();
        g_global_memory_manager = nullptr;
        g_init_flag = false;
        return 0;
    } catch (const std::exception &e) {
        LOGE("清理全局组件失败: %s", e.what());
        return -1;
    }
}

// 无参数版本的内存优化
JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeOptimizeMemory__(
        JNIEnv *env, jobject thiz
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    try {
        if (g_global_memory_manager) {
            g_global_memory_manager->optimizeMemoryUsage();
            return 0;
        }
        return -1;
    } catch (const std::exception &e) {
        LOGE("内存优化失败: %s", e.what());
        return -1;
    }
}

// 带句柄参数版本的内存优化
JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeOptimizeMemory__J(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env; // 抑制未使用参数警告
    (void) thiz; // 抑制未使用参数警告
    try {
        auto processor = getEnhancedProcessor(handle);
        if (processor) {
            // 对特定处理器进行内存优化
            processor->cleanup();
        }

        // 同时进行全局内存优化
        if (g_global_memory_manager) {
            g_global_memory_manager->optimizeMemoryUsage();
            return 0;
        }
        return -1;
    } catch (const std::exception &e) {
        LOGE("内存优化失败: %s", e.what());
        return -1;
    }
}

} // extern "C"