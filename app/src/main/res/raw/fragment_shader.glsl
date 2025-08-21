precision mediump float;

uniform sampler2D u_texture;
uniform sampler2D u_lutTexture;
uniform float u_strength;
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

// 应用LUT查找
vec3 applyLut(vec3 color) {
    // 将RGB值映射到LUT坐标
    float lutSize = 64.0; // 假设LUT大小为64x64x64
    
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
    vec3 color1 = texture2D(u_lutTexture, coord1).rgb;
    vec3 color2 = texture2D(u_lutTexture, coord2).rgb;
    
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
    
    // 应用LUT
    vec3 lutColor = applyLut(originalColor.rgb);
    
    // 根据强度混合原始颜色和LUT颜色
    vec3 finalColor = mix(originalColor.rgb, lutColor, u_strength);
    
    // 应用抖动
    ivec2 pixelCoord = ivec2(v_texCoord * u_textureSize);
    finalColor = applyDither(finalColor, pixelCoord);
    
    gl_FragColor = vec4(finalColor, originalColor.a);
}