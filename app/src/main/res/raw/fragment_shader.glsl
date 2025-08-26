precision mediump float;

uniform sampler2D u_texture;
uniform sampler2D u_lutTexture;
uniform sampler2D u_lut2Texture;
uniform float u_strength;
uniform float u_lut2Strength;
uniform float u_lutSize;
uniform float u_lut2Size;
uniform int u_ditherType;
uniform vec2 u_textureSize;

varying vec2 v_texCoord;

// Bayer矩阵用于抖动
const mat4 bayerMatrix = mat4(
    0.0/16.0,  8.0/16.0,  2.0/16.0, 10.0/16.0,
   12.0/16.0,  4.0/16.0, 14.0/16.0,  6.0/16.0,
    3.0/16.0, 11.0/16.0,  1.0/16.0,  9.0/16.0,
   15.0/16.0,  7.0/16.0, 13.0/16.0,  5.0/16.0
);

// 获取Bayer矩阵值
float getBayerValue(ivec2 coord) {
    int x = coord.x % 4;
    int y = coord.y % 4;
    
    if (y == 0) {
        if (x == 0) return bayerMatrix[0][0];
        else if (x == 1) return bayerMatrix[0][1];
        else if (x == 2) return bayerMatrix[0][2];
        else return bayerMatrix[0][3];
    } else if (y == 1) {
        if (x == 0) return bayerMatrix[1][0];
        else if (x == 1) return bayerMatrix[1][1];
        else if (x == 2) return bayerMatrix[1][2];
        else return bayerMatrix[1][3];
    } else if (y == 2) {
        if (x == 0) return bayerMatrix[2][0];
        else if (x == 1) return bayerMatrix[2][1];
        else if (x == 2) return bayerMatrix[2][2];
        else return bayerMatrix[2][3];
    } else {
        if (x == 0) return bayerMatrix[3][0];
        else if (x == 1) return bayerMatrix[3][1];
        else if (x == 2) return bayerMatrix[3][2];
        else return bayerMatrix[3][3];
    }
}

// 应用LUT查找（支持动态尺寸）
vec3 applyLut(vec3 color, sampler2D lutTexture, float lutSize) {
    // 将RGB值映射到LUT坐标
    // 计算LUT坐标
    float blue = color.b * (lutSize - 1.0);
    float blueFloor = floor(blue);
    float blueCeil = ceil(blue);
    float blueFrac = blue - blueFloor;
    
    // 计算两个LUT切片的坐标
    vec2 coord1 = vec2(
        (color.r * (lutSize - 1.0) + blueFloor * lutSize) / (lutSize * lutSize),
        color.g * (lutSize - 1.0) / lutSize
    );
    
    vec2 coord2 = vec2(
        (color.r * (lutSize - 1.0) + blueCeil * lutSize) / (lutSize * lutSize),
        color.g * (lutSize - 1.0) / lutSize
    );
    
    // 从LUT纹理采样
    vec3 color1 = texture2D(lutTexture, coord1).rgb;
    vec3 color2 = texture2D(lutTexture, coord2).rgb;
    
    // 在两个切片之间插值
    return mix(color1, color2, blueFrac);
}

// 应用抖动
vec3 applyDither(vec3 color, ivec2 coord) {
    if (u_ditherType == 0) {
        return color; // 无抖动
    }
    
    float ditherValue = getBayerValue(coord);
    float ditherStrength = 1.0 / 255.0; // 抖动强度
    
    return color + (ditherValue - 0.5) * ditherStrength;
}

void main() {
    // 采样原始纹理
    vec4 originalColor = texture2D(u_texture, v_texCoord);
    vec3 processedColor = originalColor.rgb;
    
    // 步骤1：应用第一个LUT
    vec3 lut1Color = applyLut(processedColor, u_lutTexture, u_lutSize);
    processedColor = mix(processedColor, lut1Color, clamp(u_strength, 0.0, 1.0));
    
    // 步骤2：应用第二个LUT（如果强度大于0）
    if (u_lut2Strength > 0.0) {
        vec3 lut2Color = applyLut(processedColor, u_lut2Texture, u_lut2Size);
        processedColor = mix(processedColor, lut2Color, clamp(u_lut2Strength, 0.0, 1.0));
        // 调试：如果应用了第二个LUT，增加一点绿色来确认
        // processedColor.g += 0.1; // 可选的调试代码
    }
    
    // 应用抖动
    ivec2 pixelCoord = ivec2(v_texCoord * u_textureSize);
    processedColor = applyDither(processedColor, pixelCoord);
    
    gl_FragColor = vec4(processedColor, originalColor.a);
}