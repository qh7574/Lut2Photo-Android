precision mediump float;

uniform sampler2D u_texture;
uniform sampler2D u_lutTexture;
uniform float u_strength;

varying vec2 v_texCoord;

// 简化的LUT应用，适用于较低性能的设备
vec3 applySimpleLut(vec3 color) {
    // 简化的LUT查找，只使用单一切片
    float lutSize = 32.0; // 使用较小的LUT尺寸
    
    vec2 coord = vec2(
        (color.r * (lutSize - 1.0) + color.b * (lutSize - 1.0) * lutSize) / (lutSize * lutSize),
        color.g * (lutSize - 1.0) / lutSize
    );
    
    return texture2D(u_lutTexture, coord).rgb;
}

void main() {
    vec4 originalColor = texture2D(u_texture, v_texCoord);
    vec3 lutColor = applySimpleLut(originalColor.rgb);
    vec3 finalColor = mix(originalColor.rgb, lutColor, u_strength);
    
    gl_FragColor = vec4(finalColor, originalColor.a);
}