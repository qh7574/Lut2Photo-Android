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
    val grainSize: Float = 1.0f,           // 0.5-6.0，基础颗粒大小
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
        // 视觉尺度锚定基准参数
        const val REFERENCE_SHORT_SIDE = 3000.0f
        const val REFERENCE_GRAIN_SIZE = 6.0f
        
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
     * 计算视觉尺度锚定后的颗粒参数
     * 
     * @param width 当前图像宽度
     * @param height 当前图像高度
     * @return Pair(实际颗粒直径像素值, 强度补偿系数)
     */
    fun calculateAnchoredParams(width: Int, height: Int): Pair<Float, Float> {
        // 1. 获取当前图像尺寸（短边）
        val currentShort = kotlin.math.min(width, height).toFloat()
        
        // 2. 计算基准比例 (6 / 3000 = 0.002)
        val referenceRatio = REFERENCE_GRAIN_SIZE / REFERENCE_SHORT_SIDE
        
        // 3. 计算实际颗粒直径 (基于基准比例和用户设定的grainSize倍率)
        // grainSize 是用户的调节系数 (0.5 - 6.0)，默认为 1.0
        // 如果 grainSize=1.0，则 3000px 图片的颗粒直径为 6px
        val targetDiameterPx = currentShort * referenceRatio * grainSize
        
        // 确保最小直径为 1.0px (防止过小导致采样异常)
        val finalDiameterPx = targetDiameterPx.coerceAtLeast(1.0f)
        
        // 4. 计算强度自动补偿机制
        // 面积补偿因子：(理想基准直径 / 实际直径)^2
        // 但这里我们使用简化推导：
        // 视觉强度与颗粒面积成反比（颗粒越小，视觉强度越弱，需要补偿）
        // 补偿系数 = 理想基准直径 / 实际直径
        // 注意：这里的"理想基准"是指在当前分辨率下，如果完全按照比例缩放应该有的大小
        // 但由于我们限制了最小 1px，或者用户调整了 grainSize，导致实际直径变化
        // 为了保持能量守恒，我们使用相对于"单位像素"的能量密度补偿
        
        // 使用用户确认的公式：
        // areaRatio = (referenceGrainDiameterPx / grainDiameterPx)²
        // strengthCorr = strength * sqrt(areaRatio)
        // => strengthCorr = strength * (referenceGrainDiameterPx / grainDiameterPx)
        
        // 这里的 referenceGrainDiameterPx 应该理解为"在当前分辨率下期望的颗粒大小"还是"基准分辨率下的颗粒大小"？
        // 根据方案示例：
        // 8000x6000 -> 12px
        // 2000x1500 -> 3px
        // 此时 areaRatio 应该怎么算？
        // 如果按照方案字面意思：referenceGrainDiameterPx = 6px (固定常量)
        // 那么 8000x6000 时，grainDiameterPx = 12px
        // areaRatio = (6/12)^2 = 0.25
        // strengthCorr = strength * 0.5
        // 这意味着大图（颗粒大）强度减半？这似乎反了。大图颗粒大，单位面积颗粒数少，通常视觉上如果不调整，可能会显得粗糙但强度是否需要减弱？
        // 或者，方案的意思是：保持"颗粒覆盖率"一致。
        
        // 让我们重新审视方案意图：
        // "缩略图采用相同比例计算...强度自动补偿"
        // 通常小图预览时，像素少，如果颗粒直径按比例缩小到小于1px，会被强制设为1px，此时颗粒相对于画面的比例变大了（比理论值大），所以需要降低强度？
        // 或者，如果颗粒变小了（例如从 6px 变到 3px），为了维持同样的"噪点感"，需要调整强度？
        
        // 方案原文：
        // referenceGrainDiameterPx = 6 px
        // areaRatio = (referenceGrainDiameterPx / grainDiameterPx)²
        // strengthCorr = strength * sqrt(areaRatio)
        
        // 如果 2000x1500 -> 3px
        // areaRatio = (6/3)^2 = 4
        // strengthCorr = strength * 2
        // 小图（3px颗粒），强度翻倍。
        // 这符合逻辑：颗粒越小，高频分量越多，但在缩小时如果不补偿，视觉上可能会变弱（被平均掉），或者是因为颗粒变小了，为了让人看清楚"这是颗粒"，增强对比度？
        
        // 实际上，这个公式是为了在不同分辨率下，让颗粒的"能量密度"看起来一致。
        // 当颗粒直径为 6px 时，补偿为 1.0。
        // 当颗粒直径为 3px 时，补偿为 2.0。
        // 当颗粒直径为 12px 时，补偿为 0.5。
        
        val strengthCorrection = REFERENCE_GRAIN_SIZE / finalDiameterPx
        
        return Pair(finalDiameterPx, strengthCorrection)
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
     * 为预览图缩放颗粒参数（基于像素密度归一化）
     * 
     * 注意：新的视觉尺度锚定算法（calculateAnchoredParams）已经内置了自动缩放逻辑
     * 因此此方法不再需要手动修改 grainSize，为了保持 API 兼容性，仅返回当前配置副本
     * 并确保 isEnabled 为 true
     */
    fun scaleForPreview(
        previewWidth: Int,
        previewHeight: Int,
        originalWidth: Int,
        originalHeight: Int
    ): FilmGrainConfig {
        val previewShort = kotlin.math.min(previewWidth, previewHeight).toFloat()
        val originalShort = kotlin.math.min(originalWidth, originalHeight).toFloat()

        if (previewShort <= 0f || originalShort <= 0f) {
            android.util.Log.w(
                "FilmGrainConfig",
                "scaleForPreview: invalid size preview=${previewWidth}x${previewHeight}, original=${originalWidth}x${originalHeight}"
            )
            return this.copy(isEnabled = true)
        }

        val strengthScale = (previewShort / originalShort).coerceIn(0f, 1f)
        val scaledStrength = (globalStrength * strengthScale).coerceIn(0f, 1f)

        android.util.Log.d(
            "FilmGrainConfig",
            "scaleForPreview: previewShort=$previewShort, originalShort=$originalShort, strengthScale=$strengthScale, strength=$globalStrength->$scaledStrength"
        )
        return this.copy(isEnabled = true, globalStrength = scaledStrength)
    }
}

/**
 * 颜色通道枚举
 */
enum class ColorChannel {
    RED, GREEN, BLUE
}
