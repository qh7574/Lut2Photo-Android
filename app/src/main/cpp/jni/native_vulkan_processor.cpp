#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <memory>
#include <string>
#include <vector>

#include "vulkan/vk_context.h"
#include "vulkan/vk_memory_pool.h"
#include "vulkan/vk_compute_pipeline.h"

#define LOG_TAG "NativeVulkanProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Vulkan处理器实例
struct VulkanProcessor {
    std::unique_ptr<vulkan::VkContext> context;
    std::unique_ptr<vulkan::VkMemoryPool> memoryPool;
    std::unique_ptr<vulkan::VkComputePipeline> computePipeline;
    AAssetManager* assetManager = nullptr;
    bool initialized = false;
};

// 全局Vulkan处理器实例
static std::unique_ptr<VulkanProcessor> g_vulkanProcessor = nullptr;

extern "C" {

/**
 * 检查Vulkan是否可用
 */
JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_gpu_VulkanLutProcessor_nativeIsVulkanAvailable(
    JNIEnv* env, jobject thiz
) {
    LOGD("Checking Vulkan availability...");
    
    bool supported = vulkan::VkContext::isVulkanSupported();
    LOGI("Vulkan supported: %s", supported ? "true" : "false");
    
    return supported ? JNI_TRUE : JNI_FALSE;
}

/**
 * 初始化Vulkan处理器
 */
JNIEXPORT jlong JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_gpu_VulkanLutProcessor_nativeCreate(
    JNIEnv* env, jobject thiz, jobject assetManager
) {
    LOGI("Creating Vulkan processor...");
    
    try {
        auto processor = std::make_unique<VulkanProcessor>();
        
        // 获取AAssetManager
        if (assetManager != nullptr) {
            processor->assetManager = AAssetManager_fromJava(env, assetManager);
            LOGI("Asset manager obtained: %p", processor->assetManager);
        }
        
        // 创建Vulkan上下文
        processor->context = std::make_unique<vulkan::VkContext>();
        if (!processor->context->initialize()) {
            LOGE("Failed to initialize Vulkan context");
            return 0;
        }
        
        // 创建内存池
        processor->memoryPool = std::make_unique<vulkan::VkMemoryPool>(
            processor->context.get()
        );
        
        // 创建计算管线
        processor->computePipeline = std::make_unique<vulkan::VkComputePipeline>(
            processor->context.get(),
            processor->memoryPool.get()
        );
        
        // 设置Asset管理器
        if (processor->assetManager) {
            processor->computePipeline->setAssetManager(processor->assetManager);
        }
        
        if (!processor->computePipeline->initialize()) {
            LOGE("Failed to initialize compute pipeline");
            return 0;
        }
        
        processor->initialized = true;
        
        // 返回处理器句柄
        jlong handle = reinterpret_cast<jlong>(processor.release());
        LOGI("Vulkan processor created successfully, handle: %ld", handle);
        
        return handle;
    } catch (const std::exception& e) {
        LOGE("Exception creating Vulkan processor: %s", e.what());
        return 0;
    }
}

/**
 * 销毁Vulkan处理器
 */
JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_gpu_VulkanLutProcessor_nativeDestroy(
    JNIEnv* env, jobject thiz, jlong handle
) {
    if (handle == 0) {
        LOGW("Invalid handle for destroy");
        return;
    }
    
    LOGI("Destroying Vulkan processor, handle: %lld", handle);
    
    try {
        auto processor = reinterpret_cast<VulkanProcessor*>(handle);
        delete processor;
        LOGI("Vulkan processor destroyed");
    } catch (const std::exception& e) {
        LOGE("Exception destroying Vulkan processor: %s", e.what());
    }
}

/**
 * 获取设备信息
 */
JNIEXPORT jstring JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_gpu_VulkanLutProcessor_nativeGetDeviceInfo(
    JNIEnv* env, jobject thiz, jlong handle
) {
    if (handle == 0) {
        return env->NewStringUTF("Invalid handle");
    }
    
    try {
        auto processor = reinterpret_cast<VulkanProcessor*>(handle);
        if (!processor->initialized || !processor->context) {
            return env->NewStringUTF("Not initialized");
        }
        
        const auto& props = processor->context->getDeviceProperties();
        
        std::string info = "Device: ";
        info += props.deviceName;
        info += "\nAPI Version: ";
        info += std::to_string(VK_VERSION_MAJOR(props.apiVersion)) + ".";
        info += std::to_string(VK_VERSION_MINOR(props.apiVersion)) + ".";
        info += std::to_string(VK_VERSION_PATCH(props.apiVersion));
        info += "\nMax Image Size: ";
        info += std::to_string(props.limits.maxImageDimension2D);
        
        return env->NewStringUTF(info.c_str());
    } catch (const std::exception& e) {
        LOGE("Exception getting device info: %s", e.what());
        return env->NewStringUTF("Error");
    }
}

/**
 * 获取最大纹理尺寸
 */
JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_gpu_VulkanLutProcessor_nativeGetMaxTextureSize(
    JNIEnv* env, jobject thiz, jlong handle
) {
    if (handle == 0) {
        return 4096; // 默认值
    }
    
    try {
        auto processor = reinterpret_cast<VulkanProcessor*>(handle);
        if (!processor->initialized || !processor->context) {
            return 4096;
        }
        
        return static_cast<jint>(processor->context->getMaxImageDimension2D());
    } catch (const std::exception& e) {
        LOGE("Exception getting max texture size: %s", e.what());
        return 4096;
    }
}

/**
 * 加载LUT数据到Vulkan
 */
JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_gpu_VulkanLutProcessor_nativeLoadLut(
    JNIEnv* env, jobject thiz, jlong handle,
    jfloatArray lutData, jint lutSize, jboolean isSecondLut
) {
    if (handle == 0) {
        LOGE("Invalid handle for load LUT");
        return JNI_FALSE;
    }
    
    LOGI("Loading LUT data: size=%d, isSecond=%d", lutSize, isSecondLut);
    
    try {
        auto processor = reinterpret_cast<VulkanProcessor*>(handle);
        if (!processor->initialized || !processor->computePipeline) {
            LOGE("Processor not initialized");
            return JNI_FALSE;
        }
        
        // 获取LUT数据
        jfloat* data = env->GetFloatArrayElements(lutData, nullptr);
        if (data == nullptr) {
            LOGE("Failed to get LUT data array");
            return JNI_FALSE;
        }
        
        // 上传到Vulkan
        bool success = processor->computePipeline->loadLut(
            data, lutSize, isSecondLut
        );
        
        // 释放数组
        env->ReleaseFloatArrayElements(lutData, data, JNI_ABORT);
        
        if (!success) {
            LOGE("Failed to load LUT to Vulkan");
            return JNI_FALSE;
        }
        
        LOGI("LUT loaded successfully");
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception loading LUT: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * 处理图像
 */
JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_gpu_VulkanLutProcessor_nativeProcessImage(
    JNIEnv* env, jobject thiz, jlong handle,
    jobject inputBitmap, jobject outputBitmap,
    jfloat lutStrength, jfloat lut2Strength, jint ditherType,
    jboolean grainEnabled, jfloat grainStrength, jfloat grainSize, jfloat grainSeed
) {
    if (handle == 0) {
        LOGE("Invalid handle for process image");
        return JNI_FALSE;
    }
    
    LOGI("Processing image with Vulkan...");
    
    try {
        auto processor = reinterpret_cast<VulkanProcessor*>(handle);
        if (!processor->initialized || !processor->computePipeline) {
            LOGE("Processor not initialized");
            return JNI_FALSE;
        }
        
        // 获取输入bitmap信息
        AndroidBitmapInfo inputInfo;
        if (AndroidBitmap_getInfo(env, inputBitmap, &inputInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to get input bitmap info");
            return JNI_FALSE;
        }
        
        if (inputInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LOGE("Unsupported bitmap format: %d", inputInfo.format);
            return JNI_FALSE;
        }
        
        // 获取输出bitmap信息
        AndroidBitmapInfo outputInfo;
        if (AndroidBitmap_getInfo(env, outputBitmap, &outputInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to get output bitmap info");
            return JNI_FALSE;
        }
        
        if (outputInfo.width != inputInfo.width || outputInfo.height != inputInfo.height) {
            LOGE("Input and output bitmap sizes don't match");
            return JNI_FALSE;
        }
        
        // 锁定输入bitmap
        void* inputPixels;
        if (AndroidBitmap_lockPixels(env, inputBitmap, &inputPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to lock input bitmap");
            return JNI_FALSE;
        }
        
        // 锁定输出bitmap
        void* outputPixels;
        if (AndroidBitmap_lockPixels(env, outputBitmap, &outputPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("Failed to lock output bitmap");
            AndroidBitmap_unlockPixels(env, inputBitmap);
            return JNI_FALSE;
        }
        
        // 准备处理参数
        vulkan::VkComputePipeline::ProcessingParams params;
        params.lutStrength = lutStrength;
        params.lut2Strength = lut2Strength;
        params.ditherType = ditherType;
        params.grainEnabled = grainEnabled ? 1 : 0;
        params.grainStrength = grainStrength;
        params.grainSize = grainSize;
        params.grainSeed = grainSeed;
        
        // 处理图像
        bool success = processor->computePipeline->processImage(
            inputInfo.width,
            inputInfo.height,
            static_cast<const uint8_t*>(inputPixels),
            static_cast<uint8_t*>(outputPixels),
            params
        );
        
        // 解锁bitmap
        AndroidBitmap_unlockPixels(env, inputBitmap);
        AndroidBitmap_unlockPixels(env, outputBitmap);
        
        if (!success) {
            LOGE("Failed to process image with Vulkan");
            return JNI_FALSE;
        }
        
        LOGI("Image processed successfully");
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception processing image: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * 释放资源
 */
JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_gpu_VulkanLutProcessor_nativeRelease(
    JNIEnv* env, jobject thiz, jlong handle
) {
    if (handle == 0) {
        return;
    }
    
    LOGI("Releasing Vulkan processor resources...");
    
    try {
        auto processor = reinterpret_cast<VulkanProcessor*>(handle);
        if (processor->computePipeline) {
            processor->computePipeline->cleanup();
        }
        if (processor->memoryPool) {
            processor->memoryPool->cleanup();
        }
        if (processor->context) {
            processor->context->cleanup();
        }
        processor->initialized = false;
        
        LOGI("Vulkan processor resources released");
    } catch (const std::exception& e) {
        LOGE("Exception releasing resources: %s", e.what());
    }
}

/**
 * 创建用于查询的轻量级Vulkan上下文
 */
JNIEXPORT jlong JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_ProcessorSelectionStrategy_nativeCreateForQuery(
    JNIEnv* env, jobject thiz
) {
    LOGI("Creating Vulkan context for query...");
    
    try {
        auto context = std::make_unique<vulkan::VkContext>();
        if (!context->initialize()) {
            LOGE("Failed to initialize Vulkan context for query");
            return 0;
        }
        
        return reinterpret_cast<jlong>(context.release());
    } catch (const std::exception& e) {
        LOGE("Exception creating Vulkan context for query: %s", e.what());
        return 0;
    }
}

/**
 * 销毁查询用的Vulkan上下文
 */
JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_ProcessorSelectionStrategy_nativeDestroyForQuery(
    JNIEnv* env, jobject thiz, jlong handle
) {
    if (handle == 0) {
        return;
    }
    
    try {
        auto context = reinterpret_cast<vulkan::VkContext*>(handle);
        context->cleanup();
        delete context;
    } catch (const std::exception& e) {
        LOGE("Exception destroying Vulkan context for query: %s", e.what());
    }
}

/**
 * 获取最大纹理尺寸
 */
JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_ProcessorSelectionStrategy_nativeGetMaxTextureSize(
    JNIEnv* env, jobject thiz, jlong handle
) {
    if (handle == 0) {
        return 4096;
    }
    
    try {
        auto context = reinterpret_cast<vulkan::VkContext*>(handle);
        return static_cast<jint>(context->getMaxImageDimension2D());
    } catch (const std::exception& e) {
        LOGE("Exception getting max texture size: %s", e.what());
        return 4096;
    }
}

} // extern "C"
