#ifndef SIMD_UTILS_H
#define SIMD_UTILS_H

#include "../include/native_lut_processor.h"
#include <cstdint>

// 检测NEON支持
#ifdef __ARM_NEON
#include <arm_neon.h>
#define USE_NEON_SIMD 1
#else
#define USE_NEON_SIMD 0
#endif

/**
 * SIMD优化工具类
 * 提供ARM NEON指令集优化的图片处理功能
 */
class SIMDUtils {
public:
    /**
     * 检查NEON是否可用
     * @return 是否支持NEON
     */
    static bool isNeonAvailable();
    
    /**
     * 获取SIMD处理的最优批次大小
     * @return 像素数量
     */
    static int getOptimalBatchSize();
    
#ifdef USE_NEON_SIMD
    /**
     * 使用NEON优化的像素批处理
     * @param inputPixels 输入像素数据
     * @param outputPixels 输出像素数据
     * @param pixelCount 像素数量
     * @param primaryLut 主LUT数据
     * @param secondaryLut 次LUT数据
     * @param params 处理参数
     */
    static void processPixelsNeon(
        const uint8_t* inputPixels,
        uint8_t* outputPixels,
        int pixelCount,
        const LutData& primaryLut,
        const LutData& secondaryLut,
        const ProcessingParams& params
    );
    
    /**
     * NEON优化的RGB到浮点转换
     * @param rgbPixels 输入RGB像素（4个像素，16字节）
     * @param r, g, b 输出浮点数组（4个元素）
     */
    static void convertRgbToFloat4x(
        const uint8_t* rgbPixels,
        float32x4_t& r,
        float32x4_t& g,
        float32x4_t& b
    );
    
    /**
     * NEON优化的浮点到RGB转换
     * @param r, g, b 输入浮点数组（4个元素）
     * @param rgbPixels 输出RGB像素（4个像素，16字节）
     */
    static void convertFloatToRgb4x(
        const float32x4_t& r,
        const float32x4_t& g,
        const float32x4_t& b,
        uint8_t* rgbPixels
    );
    
    /**
     * NEON优化的LUT查找（4个像素并行）
     * @param r, g, b 输入RGB浮点值
     * @param outR, outG, outB 输出RGB浮点值
     * @param lutData LUT数据
     */
    static void applyLutNeon4x(
        const float32x4_t& r,
        const float32x4_t& g,
        const float32x4_t& b,
        float32x4_t& outR,
        float32x4_t& outG,
        float32x4_t& outB,
        const LutData& lutData
    );
    
    /**
     * NEON优化的线性插值
     * @param a, b 插值端点
     * @param t 插值参数
     * @return 插值结果
     */
    static float32x4_t lerpNeon(
        const float32x4_t& a,
        const float32x4_t& b,
        const float32x4_t& t
    );
    
    /**
     * NEON优化的值限制到[0,1]范围
     * @param values 输入值
     * @return 限制后的值
     */
    static float32x4_t clampNeon(
        const float32x4_t& values
    );
    
    /**
     * NEON优化的内存复制
     * @param dst 目标地址
     * @param src 源地址
     * @param bytes 字节数（必须是16的倍数）
     */
    static void memcpyNeon(
        void* dst,
        const void* src,
        size_t bytes
    );
    
    /**
     * NEON优化的内存设置
     * @param dst 目标地址
     * @param value 设置值
     * @param bytes 字节数（必须是16的倍数）
     */
    static void memsetNeon(
        void* dst,
        uint8_t value,
        size_t bytes
    );
    
    /**
     * NEON优化的图片缩放（双线性插值）
     * @param srcPixels 源图片像素
     * @param srcWidth, srcHeight 源图片尺寸
     * @param srcStride 源图片stride
     * @param dstPixels 目标图片像素
     * @param dstWidth, dstHeight 目标图片尺寸
     * @param dstStride 目标图片stride
     */
    static void resizeImageNeon(
        const uint8_t* srcPixels,
        int srcWidth, int srcHeight, int srcStride,
        uint8_t* dstPixels,
        int dstWidth, int dstHeight, int dstStride
    );
    
private:
    /**
     * 获取LUT中指定位置的值（NEON优化）
     * @param indices 索引数组（4个元素）
     * @param lutData LUT数据
     * @param outR, outG, outB 输出RGB值
     */
    static void getLutValuesNeon4x(
        const uint32x4_t& indices,
        const LutData& lutData,
        float32x4_t& outR,
        float32x4_t& outG,
        float32x4_t& outB
    );
    
    /**
     * 计算LUT索引（NEON优化）
     * @param r, g, b 输入RGB值
     * @param lutSize LUT尺寸
     * @return 线性索引
     */
    static uint32x4_t calculateLutIndicesNeon(
        const float32x4_t& r,
        const float32x4_t& g,
        const float32x4_t& b,
        int lutSize
    );
    
    /**
     * NEON优化的三线性插值
     * @param x, y, z 插值坐标
     * @param lutData LUT数据
     * @param outR, outG, outB 输出RGB值
     */
    static void trilinearInterpolationNeon4x(
        const float32x4_t& x,
        const float32x4_t& y,
        const float32x4_t& z,
        const LutData& lutData,
        float32x4_t& outR,
        float32x4_t& outG,
        float32x4_t& outB
    );
    
#endif // USE_NEON_SIMD
    
    /**
     * 标量版本的像素处理（回退实现）
     * @param inputPixels 输入像素数据
     * @param outputPixels 输出像素数据
     * @param pixelCount 像素数量
     * @param primaryLut 主LUT数据
     * @param secondaryLut 次LUT数据
     * @param params 处理参数
     */
    static void processPixelsScalar(
        const uint8_t* inputPixels,
        uint8_t* outputPixels,
        int pixelCount,
        const LutData& primaryLut,
        const LutData& secondaryLut,
        const ProcessingParams& params
    );
    
    /**
     * 检测CPU特性
     */
    static void detectCpuFeatures();
    
    static bool neonAvailable_;
    static bool featuresDetected_;
};

/**
 * SIMD对齐的内存分配器
 */
template<typename T>
class SIMDAllocator {
public:
    static T* allocate(size_t count) {
        size_t bytes = count * sizeof(T);
        size_t alignment = 16; // NEON需要16字节对齐
        
#ifdef _WIN32
        return static_cast<T*>(_aligned_malloc(bytes, alignment));
#else
        void* ptr = nullptr;
        if (posix_memalign(&ptr, alignment, bytes) == 0) {
            return static_cast<T*>(ptr);
        }
        return nullptr;
#endif
    }
    
    static void deallocate(T* ptr) {
        if (ptr) {
#ifdef _WIN32
            _aligned_free(ptr);
#else
            free(ptr);
#endif
        }
    }
};

#endif // SIMD_UTILS_H