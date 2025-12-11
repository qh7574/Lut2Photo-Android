package cn.alittlecookie.lut2photo.lut2photo.model

/**
 * 胶片颗粒效果配置
 * 模拟真实胶片在不同影调区域的颗粒特性
 */
data class FilmGrainConfig(
    // 主控制
    val isEnabled: Boolean = false,
    val globalStrength: Float = 0.5f,      // 0-1，全局颗粒强度
    
    // 颗粒特性
    val grainSize: Float = 1.0f,           // 0.5-3.0，基础颗粒大小
    val grainSharpness: Float = 0.7f,      // 0-1，颗粒锐度
    
    // 各向异性参数
    val anisotropy: Float = 0.3f,          // 0-1，各向异性程度
    val directionVariation: Float = 15f,   // 0-30，方向变化角度（度）
    
    // 影调分区强度（以中间调为基准1.0）
    val shadowGrainRatio: Float = 0.6f,    // 阴影颗粒强度比例
    val midtoneGrainRatio: Float = 1.0f,   // 中间调颗粒强度比例（基准）
    val highlightGrainRatio: Float = 0.3f, // 高光颗粒强度比例
    
    // 影调分区尺寸系数（以中间调为基准1.0）
    val shadowSizeRatio: Float = 1.5f,     // 阴影颗粒尺寸比例
    val midtoneSizeRatio: Float = 1.0f,    // 中间调颗粒尺寸比例（基准）
    val highlightSizeRatio: Float = 0.6f,  // 高光颗粒尺寸比例
    
    // 色彩通道差异（以绿色通道为基准1.0）
    val redChannelRatio: Float = 0.9f,     // 红色通道颗粒系数
    val greenChannelRatio: Float = 1.0f,   // 绿色通道颗粒系数（基准）
    val blueChannelRatio: Float = 1.2f,    // 蓝色通道颗粒系数
    val channelCorrelation: Float = 0.9f,  // 通道相关性（0.8-0.95）
    
    // 色彩保护
    val colorPreservation: Float = 0.95f   // 0.9-1.0，避免偏色
) {
    
    /**
     * 根据全局强度和亮度计算实际颗粒强度（带平滑过渡）
     */
    fun getActualStrength(luminance: Float): Float {
        val baseRatio = when {
            luminance < 0.25f -> shadowGrainRatio
            luminance < 0.35f -> {
                val t = smoothstep((luminance - 0.25f) / 0.1f)
                lerp(shadowGrainRatio, midtoneGrainRatio, t)
            }
            luminance < 0.65f -> midtoneGrainRatio
            luminance < 0.75f -> {
                val t = smoothstep((luminance - 0.65f) / 0.1f)
                lerp(midtoneGrainRatio, highlightGrainRatio, t)
            }
            else -> highlightGrainRatio
        }
        return globalStrength * baseRatio
    }

    
    /**
     * 根据亮度计算颗粒尺寸系数
     */
    fun getGrainSizeRatio(luminance: Float): Float {
        return when {
            luminance < 0.25f -> shadowSizeRatio
            luminance < 0.35f -> {
                val t = smoothstep((luminance - 0.25f) / 0.1f)
                lerp(shadowSizeRatio, midtoneSizeRatio, t)
            }
            luminance < 0.65f -> midtoneSizeRatio
            luminance < 0.75f -> {
                val t = smoothstep((luminance - 0.65f) / 0.1f)
                lerp(midtoneSizeRatio, highlightSizeRatio, t)
            }
            else -> highlightSizeRatio
        }
    }
    
    /**
     * 获取通道颗粒系数
     */
    fun getChannelRatio(channel: ColorChannel): Float {
        return when (channel) {
            ColorChannel.RED -> redChannelRatio
            ColorChannel.GREEN -> greenChannelRatio
            ColorChannel.BLUE -> blueChannelRatio
        }
    }
    
    /**
     * 平滑插值函数（3t²-2t³）
     */
    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
    
    /**
     * 线性插值
     */
    private fun lerp(a: Float, b: Float, t: Float): Float {
        return a + t * (b - a)
    }
    
    companion object {
        fun default() = FilmGrainConfig()
        
        // 预设：经典胶片
        fun classicFilm() = FilmGrainConfig(
            isEnabled = true,
            globalStrength = 0.4f,
            grainSize = 1.2f,
            shadowGrainRatio = 0.7f,
            highlightGrainRatio = 0.25f
        )
        
        // 预设：轻微颗粒
        fun subtle() = FilmGrainConfig(
            isEnabled = true,
            globalStrength = 0.25f,
            grainSize = 0.8f,
            shadowGrainRatio = 0.5f,
            highlightGrainRatio = 0.2f
        )
        
        // 预设：强烈颗粒
        fun heavy() = FilmGrainConfig(
            isEnabled = true,
            globalStrength = 0.7f,
            grainSize = 1.8f,
            shadowGrainRatio = 0.8f,
            highlightGrainRatio = 0.4f
        )
    }
}

/**
 * 颜色通道枚举
 */
enum class ColorChannel {
    RED, GREEN, BLUE
}
