#include "simd_utils.h"
#include "../core/lut_processor.h"
#include <cstring>
#include <algorithm>

// 静态成员初始化
bool SIMDUtils::neonAvailable_ = false;
bool SIMDUtils::featuresDetected_ = false;

bool SIMDUtils::isNeonAvailable() {
    if (!featuresDetected_) {
        detectCpuFeatures();
    }
    return neonAvailable_;
}

int SIMDUtils::getOptimalBatchSize() {
#ifdef USE_NEON_SIMD
    if (isNeonAvailable()) {
        return 4; // NEON可以并行处理4个像素
    }
#endif
    return 1; // 标量处理
}

void SIMDUtils::detectCpuFeatures() {
#ifdef USE_NEON_SIMD
    // 在ARM设备上，如果编译时启用了NEON，运行时通常也支持
    neonAvailable_ = true;
    LOGD("检测到NEON支持");
#else
    neonAvailable_ = false;
    LOGD("未检测到NEON支持");
#endif
    featuresDetected_ = true;
}

#ifdef USE_NEON_SIMD

void SIMDUtils::processPixelsNeon(
    const uint8_t* inputPixels,
    uint8_t* outputPixels,
    int pixelCount,
    const LutData& primaryLut,
    const LutData& secondaryLut,
    const ProcessingParams& params
) {
    const int batchSize = 4;
    const int fullBatches = pixelCount / batchSize;
    const int remainingPixels = pixelCount % batchSize;
    
    const uint8_t* input = inputPixels;
    uint8_t* output = outputPixels;
    
    // 处理完整的4像素批次
    for (int batch = 0; batch < fullBatches; ++batch) {
        // 转换为浮点RGB
        float32x4_t r, g, b;
        convertRgbToFloat4x(input, r, g, b);
        
        // 应用主LUT
        float32x4_t lutR, lutG, lutB;
        applyLutNeon4x(r, g, b, lutR, lutG, lutB, primaryLut);
        
        // 应用次LUT（如果存在）
        if (secondaryLut.isLoaded && params.lut2Strength > 0.0f) {
            float32x4_t lut2R, lut2G, lut2B;
            applyLutNeon4x(lutR, lutG, lutB, lut2R, lut2G, lut2B, secondaryLut);
            
            // 混合两个LUT的结果
            float32x4_t strength = vdupq_n_f32(params.lut2Strength);
            float32x4_t invStrength = vsubq_f32(vdupq_n_f32(1.0f), strength);
            
            lutR = vmlaq_f32(vmulq_f32(lutR, invStrength), lut2R, strength);
            lutG = vmlaq_f32(vmulq_f32(lutG, invStrength), lut2G, strength);
            lutB = vmlaq_f32(vmulq_f32(lutB, invStrength), lut2B, strength);
        }
        
        // 应用强度混合
        if (params.strength < 1.0f) {
            float32x4_t strength = vdupq_n_f32(params.strength);
            float32x4_t invStrength = vsubq_f32(vdupq_n_f32(1.0f), strength);
            
            lutR = vmlaq_f32(vmulq_f32(r, invStrength), lutR, strength);
            lutG = vmlaq_f32(vmulq_f32(g, invStrength), lutG, strength);
            lutB = vmlaq_f32(vmulq_f32(b, invStrength), lutB, strength);
        }
        
        // 限制范围
        lutR = clampNeon(lutR);
        lutG = clampNeon(lutG);
        lutB = clampNeon(lutB);
        
        // 转换回RGB并存储
        convertFloatToRgb4x(lutR, lutG, lutB, output);
        
        input += 16; // 4像素 * 4字节
        output += 16;
    }
    
    // 处理剩余像素（标量方式）
    if (remainingPixels > 0) {
        processPixelsScalar(
            input, output, remainingPixels,
            primaryLut, secondaryLut, params
        );
    }
}

void SIMDUtils::convertRgbToFloat4x(
    const uint8_t* rgbPixels,
    float32x4_t& r,
    float32x4_t& g,
    float32x4_t& b
) {
    // 简化NEON实现，直接使用标量方式提取RGB值
    // uint8x4_t在某些NDK版本中不可用，改用直接内存访问
    // 简化实现：直接访问内存
    uint8_t r_vals[4] = {rgbPixels[2], rgbPixels[6], rgbPixels[10], rgbPixels[14]};
    uint8_t g_vals[4] = {rgbPixels[1], rgbPixels[5], rgbPixels[9], rgbPixels[13]};
    uint8_t b_vals[4] = {rgbPixels[0], rgbPixels[4], rgbPixels[8], rgbPixels[12]};
    
    // 转换为浮点并归一化
    uint16x4_t r_u16 = vget_low_u16(vmovl_u8(vld1_u8(r_vals)));
    uint16x4_t g_u16 = vget_low_u16(vmovl_u8(vld1_u8(g_vals)));
    uint16x4_t b_u16 = vget_low_u16(vmovl_u8(vld1_u8(b_vals)));
    
    uint32x4_t r_u32 = vmovl_u16(r_u16);
    uint32x4_t g_u32 = vmovl_u16(g_u16);
    uint32x4_t b_u32 = vmovl_u16(b_u16);
    
    r = vmulq_f32(vcvtq_f32_u32(r_u32), vdupq_n_f32(1.0f / 255.0f));
    g = vmulq_f32(vcvtq_f32_u32(g_u32), vdupq_n_f32(1.0f / 255.0f));
    b = vmulq_f32(vcvtq_f32_u32(b_u32), vdupq_n_f32(1.0f / 255.0f));
}

void SIMDUtils::convertFloatToRgb4x(
    const float32x4_t& r,
    const float32x4_t& g,
    const float32x4_t& b,
    uint8_t* rgbPixels
) {
    // 转换为8位整数
    uint32x4_t r_u32 = vcvtq_u32_f32(vmulq_f32(r, vdupq_n_f32(255.0f)));
    uint32x4_t g_u32 = vcvtq_u32_f32(vmulq_f32(g, vdupq_n_f32(255.0f)));
    uint32x4_t b_u32 = vcvtq_u32_f32(vmulq_f32(b, vdupq_n_f32(255.0f)));
    
    uint16x4_t r_u16 = vmovn_u32(r_u32);
    uint16x4_t g_u16 = vmovn_u32(g_u32);
    uint16x4_t b_u16 = vmovn_u32(b_u32);
    
    uint8x8_t r_u8 = vmovn_u16(vcombine_u16(r_u16, r_u16));
    uint8x8_t g_u8 = vmovn_u16(vcombine_u16(g_u16, g_u16));
    uint8x8_t b_u8 = vmovn_u16(vcombine_u16(b_u16, b_u16));
    
    // 简化实现：直接写入内存
    uint8_t r_vals[4], g_vals[4], b_vals[4];
    vst1_u8(r_vals, r_u8);
    vst1_u8(g_vals, g_u8);
    vst1_u8(b_vals, b_u8);
    
    // 组装ARGB像素
    for (int i = 0; i < 4; ++i) {
        rgbPixels[i * 4 + 0] = b_vals[i]; // B
        rgbPixels[i * 4 + 1] = g_vals[i]; // G
        rgbPixels[i * 4 + 2] = r_vals[i]; // R
        rgbPixels[i * 4 + 3] = rgbPixels[i * 4 + 3]; // 保持Alpha
    }
}

void SIMDUtils::applyLutNeon4x(
    const float32x4_t& r,
    const float32x4_t& g,
    const float32x4_t& b,
    float32x4_t& outR,
    float32x4_t& outG,
    float32x4_t& outB,
    const LutData& lutData
) {
    if (!lutData.isLoaded || lutData.data.empty()) {
        outR = r;
        outG = g;
        outB = b;
        return;
    }
    
    // 使用三线性插值
    trilinearInterpolationNeon4x(r, g, b, lutData, outR, outG, outB);
}

float32x4_t SIMDUtils::lerpNeon(
    const float32x4_t& a,
    const float32x4_t& b,
    const float32x4_t& t
) {
    // lerp(a, b, t) = a + t * (b - a) = a * (1 - t) + b * t
    float32x4_t diff = vsubq_f32(b, a);
    return vmlaq_f32(a, diff, t);
}

float32x4_t SIMDUtils::clampNeon(const float32x4_t& values) {
    float32x4_t zero = vdupq_n_f32(0.0f);
    float32x4_t one = vdupq_n_f32(1.0f);
    return vminq_f32(vmaxq_f32(values, zero), one);
}

void SIMDUtils::memcpyNeon(void* dst, const void* src, size_t bytes) {
    if (bytes % 16 != 0) {
        // 回退到标准memcpy
        std::memcpy(dst, src, bytes);
        return;
    }
    
    const uint8_t* srcPtr = static_cast<const uint8_t*>(src);
    uint8_t* dstPtr = static_cast<uint8_t*>(dst);
    
    size_t blocks = bytes / 16;
    for (size_t i = 0; i < blocks; ++i) {
        uint8x16_t data = vld1q_u8(srcPtr);
        vst1q_u8(dstPtr, data);
        srcPtr += 16;
        dstPtr += 16;
    }
}

void SIMDUtils::memsetNeon(void* dst, uint8_t value, size_t bytes) {
    if (bytes % 16 != 0) {
        // 回退到标准memset
        std::memset(dst, value, bytes);
        return;
    }
    
    uint8_t* dstPtr = static_cast<uint8_t*>(dst);
    uint8x16_t valueVec = vdupq_n_u8(value);
    
    size_t blocks = bytes / 16;
    for (size_t i = 0; i < blocks; ++i) {
        vst1q_u8(dstPtr, valueVec);
        dstPtr += 16;
    }
}

void SIMDUtils::trilinearInterpolationNeon4x(
    const float32x4_t& x,
    const float32x4_t& y,
    const float32x4_t& z,
    const LutData& lutData,
    float32x4_t& outR,
    float32x4_t& outG,
    float32x4_t& outB
) {
    // 简化实现：回退到标量版本
    // 完整的NEON三线性插值实现较为复杂，这里提供基础框架
    float x_vals[4], y_vals[4], z_vals[4];
    float r_vals[4], g_vals[4], b_vals[4];
    
    vst1q_f32(x_vals, x);
    vst1q_f32(y_vals, y);
    vst1q_f32(z_vals, z);
    
    for (int i = 0; i < 4; ++i) {
        LutProcessor::applyLut(
            x_vals[i], y_vals[i], z_vals[i],
            r_vals[i], g_vals[i], b_vals[i],
            lutData
        );
    }
    
    outR = vld1q_f32(r_vals);
    outG = vld1q_f32(g_vals);
    outB = vld1q_f32(b_vals);
}

#endif // USE_NEON_SIMD

void SIMDUtils::processPixelsScalar(
    const uint8_t* inputPixels,
    uint8_t* outputPixels,
    int pixelCount,
    const LutData& primaryLut,
    const LutData& secondaryLut,
    const ProcessingParams& params
) {
    const int bytesPerPixel = 4;
    
    for (int i = 0; i < pixelCount; ++i) {
        const int offset = i * bytesPerPixel;
        
        // ARGB_8888格式：A=3, R=2, G=1, B=0
        const uint8_t alpha = inputPixels[offset + 3];
        const uint8_t red = inputPixels[offset + 2];
        const uint8_t green = inputPixels[offset + 1];
        const uint8_t blue = inputPixels[offset + 0];
        
        // 归一化到[0,1]范围
        float r = red / 255.0f;
        float g = green / 255.0f;
        float b = blue / 255.0f;
        
        // 应用主LUT
        float lutR, lutG, lutB;
        LutProcessor::applyLut(r, g, b, lutR, lutG, lutB, primaryLut);
        
        // 应用第二LUT（如果存在）
        if (secondaryLut.isLoaded && params.lut2Strength > 0.0f) {
            float lut2R, lut2G, lut2B;
            LutProcessor::applyLut(lutR, lutG, lutB, lut2R, lut2G, lut2B, secondaryLut);
            
            // 混合两个LUT的结果
            lutR = lutR * (1.0f - params.lut2Strength) + lut2R * params.lut2Strength;
            lutG = lutG * (1.0f - params.lut2Strength) + lut2G * params.lut2Strength;
            lutB = lutB * (1.0f - params.lut2Strength) + lut2B * params.lut2Strength;
        }
        
        // 应用强度混合
        if (params.strength < 1.0f) {
            lutR = r * (1.0f - params.strength) + lutR * params.strength;
            lutG = g * (1.0f - params.strength) + lutG * params.strength;
            lutB = b * (1.0f - params.strength) + lutB * params.strength;
        }
        
        // 限制范围并转换回8位
        lutR = std::clamp(lutR, 0.0f, 1.0f);
        lutG = std::clamp(lutG, 0.0f, 1.0f);
        lutB = std::clamp(lutB, 0.0f, 1.0f);
        
        outputPixels[offset + 3] = alpha; // 保持Alpha通道
        outputPixels[offset + 2] = static_cast<uint8_t>(lutR * 255.0f + 0.5f);
        outputPixels[offset + 1] = static_cast<uint8_t>(lutG * 255.0f + 0.5f);
        outputPixels[offset + 0] = static_cast<uint8_t>(lutB * 255.0f + 0.5f);
    }
}