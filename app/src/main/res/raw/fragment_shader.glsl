#version 300 es
precision mediump float;

uniform sampler2D u_texture;
uniform sampler2D u_lutTexture;
uniform float u_lutStrength;
uniform float u_lutSize;
uniform bool u_lutEnabled;
uniform bool u_peakingEnabled;
uniform float u_peakingThreshold;
uniform bool u_waveformEnabled;
uniform float u_waveformHeight;
uniform vec2 u_textureSize;

in vec2 v_texCoord;
out vec4 fragColor;



// 应用LUT查找（支持动态尺寸）
vec3 applyLut(vec3 color, sampler2D lutTexture, float lutSize) {
    // 将RGB值映射到LUT坐标
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
    vec3 color1 = texture(lutTexture, coord1).rgb;
    vec3 color2 = texture(lutTexture, coord2).rgb;
    
    // 在两个切片之间插值
    return mix(color1, color2, blueFrac);
}

// 计算亮度
float getLuminance(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

// 峰值显示效果
vec3 applyPeaking(vec3 color, vec2 texCoord) {
    if (!u_peakingEnabled) {
        return color;
    }
    
    vec2 texelSize = 1.0 / u_textureSize;
    
    // 采样周围像素
    float center = getLuminance(texture(u_texture, texCoord).rgb);
    float left = getLuminance(texture(u_texture, texCoord + vec2(-texelSize.x, 0.0)).rgb);
    float right = getLuminance(texture(u_texture, texCoord + vec2(texelSize.x, 0.0)).rgb);
    float top = getLuminance(texture(u_texture, texCoord + vec2(0.0, -texelSize.y)).rgb);
    float bottom = getLuminance(texture(u_texture, texCoord + vec2(0.0, texelSize.y)).rgb);
    
    // 计算边缘强度
    float edgeStrength = abs(center - left) + abs(center - right) + 
                        abs(center - top) + abs(center - bottom);
    
    // 如果边缘强度超过阈值，添加峰值显示
    if (edgeStrength > u_peakingThreshold) {
        return mix(color, vec3(1.0, 0.0, 0.0), 0.5); // 红色峰值显示
    }
    
    return color;
}

// 波形图显示
vec3 applyWaveform(vec3 color, vec2 texCoord) {
    if (!u_waveformEnabled) {
        return color;
    }
    
    // 在屏幕底部显示波形图
    if (texCoord.y > (1.0 - u_waveformHeight)) {
        float luminance = getLuminance(color);
        float waveformY = (texCoord.y - (1.0 - u_waveformHeight)) / u_waveformHeight;
        
        // 简单的波形图显示
        if (abs(waveformY - luminance) < 0.02) {
            return vec3(0.0, 1.0, 0.0); // 绿色波形线
        } else {
            return color * 0.5; // 暗化背景
        }
    }
    
    return color;
}

void main() {
    // 采样原始纹理
    vec4 originalColor = texture(u_texture, v_texCoord);
    vec3 processedColor = originalColor.rgb;
    
    // 应用LUT效果（如果启用）
    if (u_lutEnabled && u_lutStrength > 0.0) {
        vec3 lutColor = applyLut(processedColor, u_lutTexture, u_lutSize);
        processedColor = mix(processedColor, lutColor, clamp(u_lutStrength, 0.0, 1.0));
    }
    
    // 应用峰值显示
    processedColor = applyPeaking(processedColor, v_texCoord);
    
    // 应用波形图显示
    processedColor = applyWaveform(processedColor, v_texCoord);
    
    fragColor = vec4(processedColor, originalColor.a);
}