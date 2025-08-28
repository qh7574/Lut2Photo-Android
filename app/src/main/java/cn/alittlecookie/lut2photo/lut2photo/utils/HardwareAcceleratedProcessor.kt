package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig

/**
 * 硬件加速图片处理工具类
 * 使用GPU加速和优化的渲染管线提升图片处理性能
 */
object HardwareAcceleratedProcessor {

    /**
     * 使用硬件加速批量处理图片效果
     */
    fun processImageWithHardwareAcceleration(
        sourceBitmap: Bitmap,
        effects: List<ImageEffect>,
        context: Context
    ): Bitmap {
        if (effects.isEmpty()) return sourceBitmap

        // 创建可变的目标Bitmap
        val resultBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // 启用硬件加速
        if (canvas.isHardwareAccelerated) {
            // 使用硬件加速的Paint
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }

            // 批量应用效果
            var currentBitmap = sourceBitmap
            for (effect in effects) {
                currentBitmap = applyEffect(currentBitmap, effect, paint, context)
            }

            // 绘制最终结果
            canvas.drawBitmap(currentBitmap, 0f, 0f, paint)
        } else {
            // 回退到软件渲染
            canvas.drawBitmap(sourceBitmap, 0f, 0f, null)
        }

        return resultBitmap
    }

    /**
     * 应用单个图片效果
     */
    private fun applyEffect(
        bitmap: Bitmap,
        effect: ImageEffect,
        paint: Paint,
        context: Context
    ): Bitmap {
        return when (effect.type) {
            EffectType.LUT -> {
                // 应用LUT效果
                LutUtils.applyLut(bitmap, effect.lutPath!!, effect.intensity)
            }

            EffectType.WATERMARK -> {
                // 应用水印效果
                WatermarkUtils.addWatermark(
                    bitmap,
                    effect.watermarkConfig as WatermarkConfig,
                    context,
                    null, // imageUri - 硬件加速处理器中没有原始URI
                    effect.lut1Name,
                    effect.lut2Name,
                    effect.lut1Strength,
                    effect.lut2Strength
                )
            }

            EffectType.COLOR_FILTER -> {
                // 应用颜色滤镜
                applyColorFilter(bitmap, effect.colorMatrix!!, paint)
            }

            EffectType.BLEND -> {
                // 应用混合模式
                applyBlendMode(bitmap, effect.blendBitmap!!, effect.blendMode!!, paint)
            }
        }
    }

    /**
     * 应用颜色滤镜
     */
    private fun applyColorFilter(bitmap: Bitmap, colorMatrix: FloatArray, paint: Paint): Bitmap {
        val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        paint.colorFilter = null

        return resultBitmap
    }

    /**
     * 应用混合模式
     */
    private fun applyBlendMode(
        baseBitmap: Bitmap,
        overlayBitmap: Bitmap,
        blendMode: PorterDuff.Mode,
        paint: Paint
    ): Bitmap {
        val resultBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // 绘制基础图片
        canvas.drawBitmap(baseBitmap, 0f, 0f, null)

        // 应用混合模式绘制覆盖图片
        paint.xfermode = PorterDuffXfermode(blendMode)
        canvas.drawBitmap(overlayBitmap, 0f, 0f, paint)
        paint.xfermode = null

        return resultBitmap
    }

    /**
     * 检查是否支持硬件加速
     */
    fun isHardwareAccelerationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
    }

    /**
     * 图片效果数据类
     */
    data class ImageEffect(
        val type: EffectType,
        val intensity: Float = 1.0f,
        val lutPath: String? = null,
        val watermarkConfig: WatermarkConfig? = null,
        val colorMatrix: FloatArray? = null,
        val blendBitmap: Bitmap? = null,
        val blendMode: PorterDuff.Mode? = null,
        val lut1Name: String? = null,
        val lut2Name: String? = null,
        val lut1Strength: Float = 1.0f,
        val lut2Strength: Float = 1.0f
    )

    /**
     * 效果类型枚举
     */
    enum class EffectType {
        LUT,
        WATERMARK,
        COLOR_FILTER,
        BLEND
    }
}