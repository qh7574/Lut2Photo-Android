#ifndef NATIVE_LUT_PROCESSOR_H
#define NATIVE_LUT_PROCESSOR_H

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <memory>
#include <vector>
#include <cstdint>

// 日志宏定义
#define LOG_TAG "NativeLutProcessor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 错误码定义
enum class ProcessResult {
    SUCCESS = 0,
    ERROR_INVALID_BITMAP = -1,
    ERROR_MEMORY_ALLOCATION = -2,
    ERROR_LUT_NOT_LOADED = -3,
    ERROR_PROCESSING_FAILED = -4,
    ERROR_INVALID_PARAMETERS = -5
};

// 处理参数结构
struct ProcessingParams {
    // 原有参数
    float strength = 1.0f;
    float lut2Strength = 1.0f;
    int quality = 90;
    int ditherType = 0; // 0=NONE, 1=FLOYD_STEINBERG, 2=RANDOM
    bool useMultiThreading = true;
    int threadCount = 0; // 0表示自动检测

    // LutImageProcessor需要的参数
    const uint8_t *inputData = nullptr;
    uint8_t *outputData = nullptr;
    int width = 0;
    int height = 0;
    int channels = 4;
    float intensity = 1.0f;
    bool enableDithering = false;
};

// LUT数据结构
struct LutData {
    std::vector<float> data;
    int size = 0; // LUT立方体的边长（通常是32或64）
    bool isLoaded = false;

    void clear() {
        data.clear();
        size = 0;
        isLoaded = false;
    }
};

// 图片信息结构
struct ImageInfo {
    int width = 0;
    int height = 0;
    int stride = 0;
    AndroidBitmapFormat format = ANDROID_BITMAP_FORMAT_NONE;
    void *pixels = nullptr;
    size_t pixelSize = 0;
};

// 进度回调类型
typedef void (*NativeProgressCallback)(float progress);

// Native处理器类声明
class NativeLutProcessor {
public:
    NativeLutProcessor();

    ~NativeLutProcessor();

    // LUT管理
    ProcessResult loadLutFromArray(const float *lutData, int lutSize);

    ProcessResult loadSecondaryLutFromArray(const float *lutData, int lutSize);

    void clearLuts();

    // 图片处理
    ProcessResult processImage(
            const ImageInfo &inputImage,
            ImageInfo &outputImage,
            const ProcessingParams &params,
            NativeProgressCallback callback = nullptr
    );

    // 内存管理
    void *allocateNativeMemory(size_t size);

    void freeNativeMemory(void *ptr);

    size_t getNativeMemoryUsage() const;

    void forceGarbageCollection();

    // 工具方法
    bool isLutLoaded() const { return primaryLut_.isLoaded; }

    bool isSecondaryLutLoaded() const { return secondaryLut_.isLoaded; }

    int getOptimalThreadCount() const;

    // 配置方法
    void setMultiThreadingEnabled(bool enabled);

    bool isMultiThreadingEnabled() const;

    void setThreadCount(int count);

    int getThreadCount() const;

    void setIntensity(float intensity);

    float getIntensity() const;

    void setDitheringEnabled(bool enabled);

    bool isDitheringEnabled() const;

    bool loadLut(const char *lutPath);

    bool loadLutFromMemory(const void *lutData, size_t dataSize);

    void unloadLut();

private:
    LutData primaryLut_;
    LutData secondaryLut_;
    size_t nativeMemoryUsage_;

    // 配置成员变量
    bool multiThreadingEnabled_ = true;
    int threadCount_ = 0; // 0表示自动检测
    float intensity_ = 1.0f;
    bool ditheringEnabled_ = false;

    // 内部处理方法
    ProcessResult processImageSingleThreaded(
            const ImageInfo &input,
            ImageInfo &output,
            const ProcessingParams &params,
            NativeProgressCallback callback
    );

    ProcessResult processImageMultiThreaded(
            const ImageInfo &input,
            ImageInfo &output,
            const ProcessingParams &params,
            NativeProgressCallback callback
    );

    // SIMD优化方法
    void processPixelsSIMD(
            const uint8_t *input,
            uint8_t *output,
            int pixelCount,
            const ProcessingParams &params
    );

    // LUT应用方法
    void applyLutToPixel(
            const uint8_t *inputPixel,
            uint8_t *outputPixel,
            const ProcessingParams &params
    ) const;

    // 三线性插值
    void trilinearInterpolation(
            float r, float g, float b,
            float &outR, float &outG, float &outB,
            const LutData &lut
    ) const;
};

// 全局实例管理
extern "C" {
// JNI导出函数声明
JNIEXPORT jlong JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeCreate(JNIEnv *env,
                                                                               jobject thiz);

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeDestroy(JNIEnv *env,
                                                                                jobject thiz,
                                                                                jlong handle);

JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeLoadLut(JNIEnv *env,
                                                                                jobject thiz,
                                                                                jlong handle,
                                                                                jfloatArray lutData,
                                                                                jint lutSize);

JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeProcessBitmap(
        JNIEnv *env, jobject thiz, jlong handle, jobject inputBitmap, jobject outputBitmap,
        jfloat strength, jfloat lut2Strength, jint quality, jint ditherType,
        jboolean useMultiThreading
);

JNIEXPORT jlong JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeGetMemoryUsage(JNIEnv *env,
                                                                                       jobject thiz,
                                                                                       jlong handle);

JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_NativeLutProcessor_nativeForceGC(JNIEnv *env,
                                                                                jobject thiz,
                                                                                jlong handle);
}

#endif // NATIVE_LUT_PROCESSOR_H