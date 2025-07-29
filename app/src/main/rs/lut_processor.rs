#pragma version(1)
#pragma rs java_package_name(cn.alittlecookie.lut2photo.lut2photo.processor)
#pragma rs_fp_relaxed

// LUT数据分配
rs_allocation lutData;
float strength;
int lutSize;

// 三线性插值函数
static float3 trilinearInterpolation(float3 color) {
    float scaledR = color.r * (lutSize - 1);
    float scaledG = color.g * (lutSize - 1);
    float scaledB = color.b * (lutSize - 1);
    
    int r0 = (int)floor(scaledR);
    int g0 = (int)floor(scaledG);
    int b0 = (int)floor(scaledB);
    
    int r1 = min(r0 + 1, lutSize - 1);
    int g1 = min(g0 + 1, lutSize - 1);
    int b1 = min(b0 + 1, lutSize - 1);
    
    float rFrac = scaledR - r0;
    float gFrac = scaledG - g0;
    float bFrac = scaledB - b0;
    
    // 获取8个顶点的颜色值
    int idx000 = (b0 * lutSize * lutSize + g0 * lutSize + r0) * 3;
    int idx001 = (b0 * lutSize * lutSize + g0 * lutSize + r1) * 3;
    int idx010 = (b0 * lutSize * lutSize + g1 * lutSize + r0) * 3;
    int idx011 = (b0 * lutSize * lutSize + g1 * lutSize + r1) * 3;
    int idx100 = (b1 * lutSize * lutSize + g0 * lutSize + r0) * 3;
    int idx101 = (b1 * lutSize * lutSize + g0 * lutSize + r1) * 3;
    int idx110 = (b1 * lutSize * lutSize + g1 * lutSize + r0) * 3;
    int idx111 = (b1 * lutSize * lutSize + g1 * lutSize + r1) * 3;
    
    float3 c000 = {rsGetElementAt_float(lutData, idx000), rsGetElementAt_float(lutData, idx000 + 1), rsGetElementAt_float(lutData, idx000 + 2)};
    float3 c001 = {rsGetElementAt_float(lutData, idx001), rsGetElementAt_float(lutData, idx001 + 1), rsGetElementAt_float(lutData, idx001 + 2)};
    float3 c010 = {rsGetElementAt_float(lutData, idx010), rsGetElementAt_float(lutData, idx010 + 1), rsGetElementAt_float(lutData, idx010 + 2)};
    float3 c011 = {rsGetElementAt_float(lutData, idx011), rsGetElementAt_float(lutData, idx011 + 1), rsGetElementAt_float(lutData, idx011 + 2)};
    float3 c100 = {rsGetElementAt_float(lutData, idx100), rsGetElementAt_float(lutData, idx100 + 1), rsGetElementAt_float(lutData, idx100 + 2)};
    float3 c101 = {rsGetElementAt_float(lutData, idx101), rsGetElementAt_float(lutData, idx101 + 1), rsGetElementAt_float(lutData, idx101 + 2)};
    float3 c110 = {rsGetElementAt_float(lutData, idx110), rsGetElementAt_float(lutData, idx110 + 1), rsGetElementAt_float(lutData, idx110 + 2)};
    float3 c111 = {rsGetElementAt_float(lutData, idx111), rsGetElementAt_float(lutData, idx111 + 1), rsGetElementAt_float(lutData, idx111 + 2)};
    
    // 三线性插值计算
    float3 c00 = mix(c000, c001, rFrac);
    float3 c01 = mix(c010, c011, rFrac);
    float3 c10 = mix(c100, c101, rFrac);
    float3 c11 = mix(c110, c111, rFrac);
    
    float3 c0 = mix(c00, c01, gFrac);
    float3 c1 = mix(c10, c11, gFrac);
    
    return mix(c0, c1, bFrac);
}

// Floyd-Steinberg抖动
static float3 applyFloydSteinbergDithering(float3 color, uint32_t x, uint32_t y) {
    // 简化的抖动实现
    float noise = (sin(x * 12.9898 + y * 78.233) * 43758.5453);
    noise = noise - floor(noise);
    noise = (noise - 0.5) * 0.01; // 抖动强度
    
    return clamp(color + noise, 0.0f, 1.0f);
}

// 主处理内核
uchar4 RS_KERNEL processPixel(uchar4 in, uint32_t x, uint32_t y) {
    // 归一化颜色值
    float3 color = {in.r / 255.0f, in.g / 255.0f, in.b / 255.0f};
    
    // 应用LUT
    float3 lutColor = trilinearInterpolation(color);
    
    // 应用强度混合
    float3 result = mix(color, lutColor, strength);
    
    // 应用抖动（可选）
    result = applyFloydSteinbergDithering(result, x, y);
    
    // 转换回8位颜色
    uchar4 output;
    output.r = (uchar)(clamp(result.r * 255.0f, 0.0f, 255.0f));
    output.g = (uchar)(clamp(result.g * 255.0f, 0.0f, 255.0f));
    output.b = (uchar)(clamp(result.b * 255.0f, 0.0f, 255.0f));
    output.a = in.a;
    
    return output;
}