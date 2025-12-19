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
    val grainSize: Float = 1.0f,           // 0.1-10.0，基础颗粒大小
    val grainSharpness: Float = 0.7f,      // 0-1，颗粒锐度
    
    // 各向异性参数
    val anisotropy: Float = 0.3f,          // 0-1，各向异性程度
    val directionVariation: Float = 15f,   // 0-30，方向变化角度（度）
    
    // 影调范围阈值（0-255亮度值）
    val shadowThreshold: Int = 85,         // 阴影/中间调分界点（默认85）
    val highlightThreshold: Int = 170,     // 中间调/高光分界点（默认170）
    
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
            highlightGrainRatio = 0.3f
        )
    }

    /**
     * 根据全局强度和亮度计算实际颗粒强度（带平滑过渡）
     */
    fun getActualStrength(luminance: Float): Float {
        // 将阈值从0-255转换为0-1范围
        val shadowThresholdNorm = shadowThreshold / 255f
        val highlightThresholdNorm = highlightThreshold / 255f
        val transitionWidth = 0.04f  // 过渡区域宽度（约10个亮度值）
        
        val baseRatio = when {
            luminance < shadowThresholdNorm - transitionWidth -> shadowGrainRatio
            luminance < shadowThresholdNorm + transitionWidth -> {
                val t = smoothstep((luminance - (shadowThresholdNorm - transitionWidth)) / (2 * transitionWidth))
                lerp(shadowGrainRatio, midtoneGrainRatio, t)
            }
            luminance < highlightThresholdNorm - transitionWidth -> midtoneGrainRatio
            luminance < highlightThresholdNorm + transitionWidth -> {
                val t = smoothstep((luminance - (highlightThresholdNorm - transitionWidth)) / (2 * transitionWidth))
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
        // 将阈值从0-255转换为0-1范围
        val shadowThresholdNorm = shadowThreshold / 255f
        val highlightThresholdNorm = highlightThreshold / 255f
        val transitionWidth = 0.04f  // 过渡区域宽度
        
        return when {
            luminance < shadowThresholdNorm - transitionWidth -> shadowSizeRatio
            luminance < shadowThresholdNorm + transitionWidth -> {
                val t = smoothstep((luminance - (shadowThresholdNorm - transitionWidth)) / (2 * transitionWidth))
                lerp(shadowSizeRatio, midtoneSizeRatio, t)
            }
            luminance < highlightThresholdNorm - transitionWidth -> midtoneSizeRatio
            luminance < highlightThresholdNorm + transitionWidth -> {
                val t = smoothstep((luminance - (highlightThresholdNorm - transitionWidth)) / (2 * transitionWidth))
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
    
    /**
     * 为预览图缩放颗粒参数（基于物理尺寸归一化）
     * 
     * 使用屏幕物理尺寸计算缩放比例，确保预览图和原图在全屏显示时颗粒物理大小一致。
     * 颗粒大小与物理显示尺寸成反比，强度与物理显示尺寸成正比。
     * 
     * @param previewWidth 预览图宽度（像素）
     * @param previewHeight 预览图高度（像素）
     * @param originalWidth 原图宽度（像素）
     * @param originalHeight 原图高度（像素）
     * @param screenDensity 屏幕密度（dpi）
     * @param screenWidth 屏幕宽度（像素）
     * @param screenHeight 屏幕高度（像素）
     */
    fun scaleForPreview(
        previewWidth: Int,
        previewHeight: Int,
        originalWidth: Int,
        originalHeight: Int,
        screenDensity: Float,
        screenWidth: Int,
        screenHeight: Int
    ): FilmGrainConfig {
        if (screenDensity <= 0f || screenWidth <= 0 || screenHeight <= 0) {
            android.util.Log.w(
                "FilmGrainConfig",
                "scaleForPreview: invalid screen params density=$screenDensity, screen=${screenWidth}x${screenHeight}"
            )
            return this.copy(isEnabled = true)
        }

        val previewPixels = previewWidth * previewHeight
        val originalPixels = originalWidth * originalHeight
        
        if (previewPixels <= 0 || originalPixels <= 0) {
            android.util.Log.w(
                "FilmGrainConfig",
                "scaleForPreview: invalid size preview=${previewWidth}x${previewHeight}, original=${originalWidth}x${originalHeight}"
            )
            return this.copy(isEnabled = true)
        }

        val previewShort = kotlin.math.min(previewWidth, previewHeight).toFloat()
        val originalShort = kotlin.math.min(originalWidth, originalHeight).toFloat()

        if (previewShort <= 0f || originalShort <= 0f) {
            android.util.Log.w(
                "FilmGrainConfig",
                "scaleForPreview: invalid short side preview=$previewShort, original=$originalShort"
            )
            return this.copy(isEnabled = true)
        }

        val previewLong = kotlin.math.max(previewWidth, previewHeight).toFloat()
        val originalLong = kotlin.math.max(originalWidth, originalHeight).toFloat()

        val previewScaleX = screenWidth.toFloat() / previewWidth
        val previewScaleY = screenHeight.toFloat() / previewHeight
        val originalScaleX = screenWidth.toFloat() / originalWidth
        val originalScaleY = screenHeight.toFloat() / originalHeight

        val previewDisplayScale = kotlin.math.min(previewScaleX, previewScaleY)
        val originalDisplayScale = kotlin.math.min(originalScaleX, originalScaleY)

        if (originalDisplayScale < 0.001f) {
            android.util.Log.w(
                "FilmGrainConfig",
                "scaleForPreview: originalDisplayScale too small: $originalDisplayScale"
            )
            return this.copy(isEnabled = true)
        }

        val physicalScale = previewDisplayScale / originalDisplayScale

        val densityScale = kotlin.math.sqrt(previewPixels.toFloat() / originalPixels.toFloat())
        val perceptualScale = kotlin.math.sqrt(physicalScale)

        val rawScaledStrength = globalStrength * perceptualScale / densityScale
        val scaledStrength = rawScaledStrength.coerceIn(0f, 10f)

        val baseSize = grainSize / physicalScale
        val densityCompensation = 1.0f / densityScale
        val strengthCompensation = globalStrength / scaledStrength
        val scaledGrainSize = baseSize * densityCompensation * strengthCompensation
        val finalGrainSize = scaledGrainSize.coerceIn(0.1f, 10f)

        android.util.Log.d(
            "FilmGrainConfig",
            "scaleForPreview: physicalScale=$physicalScale, densityScale=$densityScale, perceptualScale=$perceptualScale, " +
            "strengthRatio=${scaledStrength/globalStrength}, " +
            "originalSize=$grainSize→$finalGrainSize, originalStrength=$globalStrength→$scaledStrength"
        )

        return this.copy(isEnabled = true, globalStrength = scaledStrength, grainSize = finalGrainSize)
    }
}

/**
 * 颜色通道枚举
 */
enum class ColorChannel {
    RED, GREEN, BLUE
}
