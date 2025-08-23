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
class WatermarkProcessor(context: Context) {

    private val exifReader = ExifReader(context)

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

        // 计算水印位置
        val watermarkPosition =
            calculateWatermarkPosition(resultBitmap, config.positionX, config.positionY)

        var currentY = watermarkPosition.y

        // 绘制图片水印
        if (config.enableImageWatermark && config.imagePath.isNotEmpty()) {
            val imageWatermarkSize = calculateWatermarkSize(resultBitmap, config.imageSize)
            val watermarkImage = loadWatermarkImage(config.imagePath, imageWatermarkSize)
            watermarkImage?.let { image ->
                val imageX = watermarkPosition.x - image.width / 2f
                val imageY = currentY - image.height / 2f

                val paint = Paint().apply {
                    alpha = (config.opacity * 255 / 100).toInt()
                }

                canvas.drawBitmap(image, imageX, imageY, paint)
                currentY += image.height / 2f + calculateSpacing(
                    resultBitmap,
                    config.textImageSpacing
                )
            }
        }

        // 绘制文字水印
        if (config.enableTextWatermark && config.textContent.isNotEmpty()) {
            val processedText = exifReader.replaceExifVariables(config.textContent, exifData)
            drawTextWatermark(
                canvas,
                processedText,
                watermarkPosition.x,
                currentY,
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
        if (config.borderWidth <= 0) {
            return originalBitmap
        }

        val shortSide = min(originalBitmap.width, originalBitmap.height)
        val borderWidth = (shortSide * config.borderWidth / 100).toInt()

        if (borderWidth <= 0) {
            return originalBitmap
        }

        val newWidth = originalBitmap.width + borderWidth * 2
        val newHeight = originalBitmap.height + borderWidth * 2

        val resultBitmap = createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // 绘制边框背景
        val borderPaint = Paint().apply {
            color = config.getBorderColorInt()
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, newWidth.toFloat(), newHeight.toFloat(), borderPaint)

        // 绘制原图
        canvas.drawBitmap(originalBitmap, borderWidth.toFloat(), borderWidth.toFloat(), null)

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
        targetWidthPercent: Float,
        bitmap: Bitmap,
        config: WatermarkConfig
    ) {
        val paint = Paint().apply {
            color = config.getTextColorInt()
            alpha = (config.opacity * 255 / 100).toInt()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER

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

        // 处理多行文本
        val lines = text.split("\n")

        // 计算目标宽度（基于图片宽度的百分比）
        val targetWidth = bitmap.width * targetWidthPercent / 100

        // 找到最长的行来计算字体大小
        val longestLine = lines.maxByOrNull { it.length } ?: ""

        // 通过二分查找找到合适的字体大小
        var minSize = 1f
        var maxSize = bitmap.width.toFloat()
        var optimalSize = minSize

        while (maxSize - minSize > 1f) {
            val testSize = (minSize + maxSize) / 2
            paint.textSize = testSize
            val textWidth = paint.measureText(longestLine)

            if (textWidth <= targetWidth) {
                optimalSize = testSize
                minSize = testSize
            } else {
                maxSize = testSize
            }
        }

        paint.textSize = optimalSize
        val lineHeight = paint.textSize * 1.2f
        val totalHeight = lines.size * lineHeight
        val startY = centerY - totalHeight / 2 + lineHeight / 2

        lines.forEachIndexed { index, line ->
            val y = startY + index * lineHeight
            canvas.drawText(line, centerX, y, paint)
        }
    }
}