package cn.alittlecookie.lut2photo.lut2photo.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.graphics.createBitmap
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig
import cn.alittlecookie.lut2photo.lut2photo.utils.ExifReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * 水印处理器
 * 使用Canvas进行水印绘制
 */
class WatermarkProcessor(private val context: Context) {

    private val exifReader = ExifReader(context)
    private val density = context.resources.displayMetrics.density

    /**
     * 为图片添加水印
     * @param originalBitmap 原始图片
     * @param config 水印配置
     * @param imageUri 图片URI，用于读取EXIF信息
     * @return 添加水印后的图片
     */
    suspend fun addWatermark(
        originalBitmap: Bitmap,
        config: WatermarkConfig,
        imageUri: Uri? = null
    ): Bitmap = withContext(Dispatchers.Default) {

        if (!config.isEnabled || (!config.enableTextWatermark && !config.enableImageWatermark)) {
            return@withContext addBorderOnly(originalBitmap, config)
        }

        // 先添加边框
        val bitmapWithBorder = addBorderOnly(originalBitmap, config)

        // 创建可变的Bitmap用于绘制水印
        val resultBitmap = bitmapWithBorder.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // 读取EXIF信息
        val exifData = imageUri?.let { exifReader.readExifFromUri(it) } ?: emptyMap()

        // 绘制图片水印 - 使用独立的位置和透明度
        if (config.enableImageWatermark && config.imagePath.isNotEmpty()) {
            val imageWatermarkSize = calculateWatermarkSize(resultBitmap, config.imageSize)
            val watermarkImage = loadWatermarkImage(config.imagePath, imageWatermarkSize)
            watermarkImage?.let { image ->
                // 计算图片水印位置
                val imagePosition = calculateWatermarkPosition(
                    resultBitmap,
                    config.imagePositionX,
                    config.imagePositionY
                )
                val imageX = imagePosition.x - image.width / 2f
                val imageY = imagePosition.y - image.height / 2f

                val paint = Paint().apply {
                    alpha = (config.imageOpacity * 255 / 100).toInt()
                }

                canvas.drawBitmap(image, imageX, imageY, paint)
            }
        }

        // 绘制文字水印 - 使用独立的位置和透明度
        if (config.enableTextWatermark && config.textContent.isNotEmpty()) {
            val processedText = exifReader.replaceExifVariables(config.textContent, exifData)
            // 计算文字水印位置
            val textPosition =
                calculateWatermarkPosition(resultBitmap, config.textPositionX, config.textPositionY)
            drawTextWatermark(
                canvas,
                processedText,
                textPosition.x,
                textPosition.y,
                config.textSize,
                resultBitmap,
                config
            )
        }

        resultBitmap
    }

    /**
     * 仅添加边框
     */
    private fun addBorderOnly(originalBitmap: Bitmap, config: WatermarkConfig): Bitmap {
        // 检查是否有任意边框宽度大于0
        if (config.borderTopWidth <= 0 && config.borderBottomWidth <= 0 &&
            config.borderLeftWidth <= 0 && config.borderRightWidth <= 0
        ) {
            return originalBitmap
        }

        val shortSide = min(originalBitmap.width, originalBitmap.height)

        // 计算四个方向的边框宽度
        val borderTopPx = (shortSide * config.borderTopWidth / 100).toInt()
        val borderBottomPx = (shortSide * config.borderBottomWidth / 100).toInt()
        val borderLeftPx = (shortSide * config.borderLeftWidth / 100).toInt()
        val borderRightPx = (shortSide * config.borderRightWidth / 100).toInt()

        // 计算新的图片尺寸
        val newWidth = originalBitmap.width + borderLeftPx + borderRightPx
        val newHeight = originalBitmap.height + borderTopPx + borderBottomPx

        val resultBitmap = createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // 绘制边框背景
        val borderPaint = Paint().apply {
            color = config.getBorderColorInt()
            style = Paint.Style.FILL
        }

        // 绘制整个背景
        canvas.drawRect(0f, 0f, newWidth.toFloat(), newHeight.toFloat(), borderPaint)

        // 绘制原图（放置在边框内）
        canvas.drawBitmap(
            originalBitmap,
            borderLeftPx.toFloat(),
            borderTopPx.toFloat(),
            null
        )

        return resultBitmap
    }

    /**
     * 计算水印大小
     */
    private fun calculateWatermarkSize(bitmap: Bitmap, sizePercent: Float): Int {
        return (bitmap.width * sizePercent / 100).toInt()
    }

    /**
     * 计算水印位置
     */
    private fun calculateWatermarkPosition(
        bitmap: Bitmap,
        xPercent: Float,
        yPercent: Float
    ): PointF {
        val x = bitmap.width * xPercent / 100
        val y = bitmap.height * yPercent / 100
        return PointF(x, y)
    }

    /**
     * 计算间距
     */
    private fun calculateSpacing(bitmap: Bitmap, spacingPercent: Float): Float {
        return bitmap.height * spacingPercent / 100
    }

    /**
     * 加载水印图片
     */
    private suspend fun loadWatermarkImage(imagePath: String, targetHeight: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val file = File(imagePath)
                if (!file.exists()) return@withContext null

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(imagePath, options)

                // 计算缩放比例
                val scale = targetHeight.toFloat() / options.outHeight
                val targetWidth = (options.outWidth * scale).toInt()

                val finalOptions = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                }

                val bitmap = BitmapFactory.decodeFile(imagePath, finalOptions)
                bitmap?.let {
                    Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true)
                }
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 计算采样率
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 绘制文字水印
     */
    /**
     * 绘制文字水印
     */
    private fun drawTextWatermark(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        textSizeDp: Float,
        bitmap: Bitmap,
        config: WatermarkConfig
    ) {
        // 处理多行文本
        val lines = text.split("\n")
        if (lines.isEmpty()) return

        // 将dp转换为像素作为基础文字大小
        val baseTextSizePx = textSizeDp * density

        // 确保基础文字大小有效
        if (baseTextSizePx <= 0) return

        // 找到最长的行来计算合适的字体大小
        val longestLine = lines.maxByOrNull { it.length } ?: ""
        if (longestLine.isEmpty()) return
        
        val paint = Paint().apply {
            color = config.getTextColorInt()
            alpha = (config.textOpacity * 255 / 100).toInt() // 使用新的文字透明度
            isAntiAlias = true

            // 设置文本对齐方式
            textAlign = when (config.textAlignment) {
                cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT -> Paint.Align.LEFT
                cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.CENTER -> Paint.Align.CENTER
                cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.RIGHT -> Paint.Align.RIGHT
            }

            // 加载自定义字体
            if (config.fontPath.isNotEmpty()) {
                try {
                    val fontFile = File(config.fontPath)
                    if (fontFile.exists()) {
                        typeface = Typeface.createFromFile(fontFile)
                    }
                } catch (_: Exception) {
                    // 使用默认字体
                }
            }
        }

        // 计算最大允许宽度（图片宽度的80%）
        val maxAllowedWidth = bitmap.width * 0.8f

        // 先设置基础文字大小
        paint.textSize = baseTextSizePx

        // 测量文字宽度（不考虑字间距）
        val measuredWidth = paint.measureText(longestLine)

        // 如果文字宽度超过最大允许宽度，按比例缩小
        val finalTextSize = if (measuredWidth > maxAllowedWidth && measuredWidth > 0) {
            baseTextSizePx * (maxAllowedWidth / measuredWidth)
        } else {
            baseTextSizePx
        }

        // 设置最终的字体大小
        paint.textSize = finalTextSize

        // 设置字间距（转换为相对单位）
        if (config.letterSpacing > 0 && finalTextSize > 0) {
            val letterSpacingPx = config.letterSpacing * density
            paint.letterSpacing = letterSpacingPx / finalTextSize
        } else {
            paint.letterSpacing = 0f
        }

        // 计算行间距
        val baseLineHeight = finalTextSize * 1.2f
        val additionalLineSpacing = if (config.lineSpacing > 0) {
            bitmap.height * config.lineSpacing / 100
        } else {
            0f
        }
        val lineHeight = baseLineHeight + additionalLineSpacing
        
        val totalHeight = lines.size * lineHeight
        val startY = centerY - totalHeight / 2 + lineHeight / 2

        // 根据对齐方式计算X坐标
        val drawX = when (config.textAlignment) {
            cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT -> {
                // 左对齐：从图片左边缘开始，留出一些边距
                bitmap.width * 0.05f
            }

            cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.CENTER -> {
                // 居中对齐：使用传入的centerX
                centerX
            }

            cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.RIGHT -> {
                // 右对齐：从图片右边缘开始，留出一些边距
                bitmap.width * 0.95f
            }
        }

        lines.forEachIndexed { index, line ->
            if (line.isNotEmpty()) {
                val y = startY + index * lineHeight
                canvas.drawText(line, drawX, y, paint)
            }
        }
    }
}