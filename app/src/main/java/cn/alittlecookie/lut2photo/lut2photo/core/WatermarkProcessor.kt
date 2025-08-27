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
import cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig
import cn.alittlecookie.lut2photo.lut2photo.utils.ExifReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 水印处理器
 * 使用Canvas进行水印绘制
 */
class WatermarkProcessor(private val context: Context) {

    companion object {
        private const val TAG = "WatermarkProcessor"

        // 图片处理时的尺寸限制，防止OOM
        private const val MAX_PROCESSING_PIXELS = 200_000_000L // 2亿像素
    }

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

        // 先添加边框（内部已处理压缩逻辑）
        val bitmapWithBorder = addBorderOnly(originalBitmap, config)

        // 创建可变的Bitmap用于绘制水印
        val resultBitmap = bitmapWithBorder.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // 读取EXIF信息
        val exifData = imageUri?.let { exifReader.readExifFromUri(it) } ?: emptyMap()

        // 判断是否启用文字跟随模式
        if (config.enableTextFollowMode && config.enableTextWatermark && config.enableImageWatermark &&
            config.textContent.isNotEmpty() && config.imagePath.isNotEmpty()
        ) {
            // 文字跟随模式：根据图片水印位置计算文字位置
            drawWatermarksInFollowMode(canvas, resultBitmap, config, exifData)
        } else {
            // 普通模式：分别独立绘制图片和文字水印
            drawWatermarksInNormalMode(canvas, resultBitmap, config, exifData)
        }

        resultBitmap
    }

    /**
     * 仅添加边框
     */
    private suspend fun addBorderOnly(originalBitmap: Bitmap, config: WatermarkConfig): Bitmap =
        withContext(Dispatchers.Default) {
        // 检查是否有任意边框宽度大于0
        if (config.borderTopWidth <= 0 && config.borderBottomWidth <= 0 &&
            config.borderLeftWidth <= 0 && config.borderRightWidth <= 0
        ) {
            return@withContext originalBitmap
        }

        val shortSide = min(originalBitmap.width, originalBitmap.height)

        // 计算四个方向的边框宽度
        val borderTopPx = (shortSide * config.borderTopWidth / 100).toInt()
        val borderBottomPx = (shortSide * config.borderBottomWidth / 100).toInt()
        val borderLeftPx = (shortSide * config.borderLeftWidth / 100).toInt()
        val borderRightPx = (shortSide * config.borderRightWidth / 100).toInt()

            // 计算添加边框后的最终尺寸
            val finalWidth = originalBitmap.width + borderLeftPx + borderRightPx
            val finalHeight = originalBitmap.height + borderTopPx + borderBottomPx
            val finalPixels = finalWidth.toLong() * finalHeight.toLong()

            // 如果最终尺寸超过限制，需要预先压缩原图
            val processedBitmap = if (finalPixels > MAX_PROCESSING_PIXELS) {
                android.util.Log.d(
                    TAG,
                    "边框后尺寸${finalWidth}x${finalHeight}($finalPixels)超过限制，预压缩原图"
                )

                // 计算需要的压缩比例，确保边框后的尺寸在限制内
                val scale = sqrt(MAX_PROCESSING_PIXELS.toDouble() / finalPixels.toDouble())
                val newOriginalWidth = (originalBitmap.width * scale).toInt()
                val newOriginalHeight = (originalBitmap.height * scale).toInt()

                android.util.Log.d(
                    TAG,
                    "压缩原图从${originalBitmap.width}x${originalBitmap.height}到${newOriginalWidth}x${newOriginalHeight}"
                )

                try {
                    val compressedBitmap = Bitmap.createScaledBitmap(
                        originalBitmap,
                        newOriginalWidth,
                        newOriginalHeight,
                        true
                    )

                    // 如果创建了新的bitmap，且与原始的不同，则释放原始的
                    if (compressedBitmap != originalBitmap && !originalBitmap.isRecycled) {
                        originalBitmap.recycle()
                    }

                    compressedBitmap
                } catch (e: OutOfMemoryError) {
                    android.util.Log.e(TAG, "预压缩原图时发生OOM，使用原图", e)
                    originalBitmap
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "预压缩原图失败，使用原图", e)
                    originalBitmap
                }
            } else {
                android.util.Log.d(
                    TAG,
                    "边框后尺寸${finalWidth}x${finalHeight}($finalPixels)在安全范围内"
                )
                originalBitmap
            }

            // 重新计算边框像素（基于可能已压缩的图片）
            val processedShortSide = min(processedBitmap.width, processedBitmap.height)
            val newBorderTopPx = (processedShortSide * config.borderTopWidth / 100).toInt()
            val newBorderBottomPx = (processedShortSide * config.borderBottomWidth / 100).toInt()
            val newBorderLeftPx = (processedShortSide * config.borderLeftWidth / 100).toInt()
            val newBorderRightPx = (processedShortSide * config.borderRightWidth / 100).toInt()

        // 计算新的图片尺寸
            val newWidth = processedBitmap.width + newBorderLeftPx + newBorderRightPx
            val newHeight = processedBitmap.height + newBorderTopPx + newBorderBottomPx

            try {
                val resultBitmap = createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(resultBitmap)

                // 绘制边框背景
                val borderPaint = Paint().apply {
                    color = config.getBorderColorInt()
                    style = Paint.Style.FILL
                }

                // 绘制整个背景
                canvas.drawRect(0f, 0f, newWidth.toFloat(), newHeight.toFloat(), borderPaint)

                // 绘制原图（放置在边框内），使用重新计算的边框值
                canvas.drawBitmap(
                    processedBitmap,  // 使用可能已压缩的图片
                    newBorderLeftPx.toFloat(),
                    newBorderTopPx.toFloat(),
                    null
                )

                android.util.Log.d(
                    TAG,
                    "边框添加成功: ${processedBitmap.width}x${processedBitmap.height} -> ${newWidth}x${newHeight}"
                )
                return@withContext resultBitmap
            } catch (e: OutOfMemoryError) {
                android.util.Log.e(TAG, "创建边框时发生OOM: ${newWidth}x${newHeight}", e)
                // OOM时返回处理后的图片（可能已压缩）
                return@withContext processedBitmap
            } catch (e: Exception) {
                android.util.Log.e(TAG, "创建边框时发生错误", e)
                return@withContext processedBitmap
            }
        }

    /**
     * 压缩图片防止处理时OOM
     * 当图片像素数超过2亿时，压缩图片到合适尺寸
     * @param bitmap 原始图片
     * @return 压缩后的图片（如果不需要压缩则返回原图）
     */
    private suspend fun compressBitmapForProcessing(bitmap: Bitmap): Bitmap =
        withContext(Dispatchers.Default) {
            val currentPixels = bitmap.width.toLong() * bitmap.height.toLong()

            if (currentPixels <= MAX_PROCESSING_PIXELS) {
                android.util.Log.d(TAG, "图片像素数($currentPixels)在处理安全范围内，无需压缩")
                return@withContext bitmap
            }

            // 计算压缩比例
            val scale = sqrt(MAX_PROCESSING_PIXELS.toDouble() / currentPixels.toDouble())
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()

            android.util.Log.d(
                TAG,
                "图片像素数($currentPixels)超过处理限制($MAX_PROCESSING_PIXELS)，将压缩至${newWidth}x${newHeight}"
            )

            try {
                val compressedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                val compressedPixels =
                    compressedBitmap.width.toLong() * compressedBitmap.height.toLong()
                android.util.Log.d(
                    TAG,
                    "图片压缩成功：${bitmap.width}x${bitmap.height}($currentPixels) -> ${compressedBitmap.width}x${compressedBitmap.height}($compressedPixels)"
                )

                // 如果创建了新的bitmap，且与原始的不同，则释放原始的
                if (compressedBitmap != bitmap && !bitmap.isRecycled) {
                    bitmap.recycle()
                }

                return@withContext compressedBitmap
            } catch (e: OutOfMemoryError) {
                android.util.Log.e(TAG, "压缩图片时发生OOM，返回原图", e)
                return@withContext bitmap
            } catch (e: Exception) {
                android.util.Log.e(TAG, "压缩图片失败，返回原图", e)
                return@withContext bitmap
            }
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
     * 文字跟随模式下的水印绘制
     */
    private suspend fun drawWatermarksInFollowMode(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig,
        exifData: Map<String, String>
    ) {
        // 首先加载和绘制图片水印，获取其位置和尺寸
        val imageWatermarkSize = calculateWatermarkSize(bitmap, config.imageSize)
        val watermarkImage = loadWatermarkImage(config.imagePath, imageWatermarkSize)

        watermarkImage?.let { image ->
            // 计算图片水印位置（使用图片水印位置参数）
            val imagePosition = calculateWatermarkPosition(
                bitmap,
                config.imagePositionX,
                config.imagePositionY
            )
            val imageX = imagePosition.x - image.width / 2f
            val imageY = imagePosition.y - image.height / 2f

            // 绘制图片水印
            val imagePaint = Paint().apply {
                alpha = (config.imageOpacity * 255 / 100).toInt()
            }
            canvas.drawBitmap(image, imageX, imageY, imagePaint)

            // 根据跟随方向计算文字位置
            val processedText = exifReader.replaceExifVariables(config.textContent, exifData)
            val textPosition = calculateTextFollowPosition(
                bitmap,
                imagePosition,
                image.width,
                image.height,
                config.textFollowDirection,
                config.textImageSpacing,
                config.textAlignment
            )

            // 绘制文字水印
            drawTextWatermark(
                canvas,
                processedText,
                textPosition.x,
                textPosition.y,
                config.textSize,
                bitmap,
                config
            )
        }
    }

    /**
     * 普通模式下的水印绘制
     */
    private suspend fun drawWatermarksInNormalMode(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig,
        exifData: Map<String, String>
    ) {
        // 绘制图片水印 - 使用独立的位置和透明度
        if (config.enableImageWatermark && config.imagePath.isNotEmpty()) {
            val imageWatermarkSize = calculateWatermarkSize(bitmap, config.imageSize)
            val watermarkImage = loadWatermarkImage(config.imagePath, imageWatermarkSize)
            watermarkImage?.let { image ->
                // 计算图片水印位置
                val imagePosition = calculateWatermarkPosition(
                    bitmap,
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
                calculateWatermarkPosition(bitmap, config.textPositionX, config.textPositionY)
            drawTextWatermark(
                canvas,
                processedText,
                textPosition.x,
                textPosition.y,
                config.textSize,
                bitmap,
                config
            )
        }
    }

    /**
     * 计算文字跟随模式下的文字位置
     */
    private fun calculateTextFollowPosition(
        bitmap: Bitmap,
        imagePosition: PointF,
        imageWidth: Int,
        imageHeight: Int,
        followDirection: TextFollowDirection,
        spacingPercent: Float,
        textAlignment: cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment
    ): PointF {
        // 计算间距（根据方向使用不同的参考尺寸）
        val spacing = when (followDirection) {
            TextFollowDirection.TOP, TextFollowDirection.BOTTOM -> {
                // 上下方向使用图片高度百分比
                imageHeight * spacingPercent / 100f
            }

            TextFollowDirection.LEFT, TextFollowDirection.RIGHT -> {
                // 左右方向使用图片宽度百分比
                imageWidth * spacingPercent / 100f
            }
        }

        // 计算文字位置
        val textX: Float
        val textY: Float

        when (followDirection) {
            TextFollowDirection.TOP -> {
                // 文字在图片上方
                textY = imagePosition.y - imageHeight / 2f - spacing
                textX = when (textAlignment) {
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT -> imagePosition.x - imageWidth / 2f
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.CENTER -> imagePosition.x
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.RIGHT -> imagePosition.x + imageWidth / 2f
                }
            }

            TextFollowDirection.BOTTOM -> {
                // 文字在图片下方
                textY = imagePosition.y + imageHeight / 2f + spacing
                textX = when (textAlignment) {
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT -> imagePosition.x - imageWidth / 2f
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.CENTER -> imagePosition.x
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.RIGHT -> imagePosition.x + imageWidth / 2f
                }
            }

            TextFollowDirection.LEFT -> {
                // 文字在图片左侧
                textX = imagePosition.x - imageWidth / 2f - spacing
                textY = when (textAlignment) {
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT -> imagePosition.y - imageHeight / 2f // 上对齐
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.CENTER -> imagePosition.y // 垂直居中
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.RIGHT -> imagePosition.y + imageHeight / 2f // 下对齐
                }
            }

            TextFollowDirection.RIGHT -> {
                // 文字在图片右侧
                textX = imagePosition.x + imageWidth / 2f + spacing
                textY = when (textAlignment) {
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT -> imagePosition.y - imageHeight / 2f // 上对齐
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.CENTER -> imagePosition.y // 垂直居中
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.RIGHT -> imagePosition.y + imageHeight / 2f // 下对齐
                }
            }
        }

        return PointF(textX, textY)
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
        textSizePercent: Float,
        bitmap: Bitmap,
        config: WatermarkConfig
    ) {
        // 处理多行文本
        val lines = text.split("\n")
        if (lines.isEmpty()) return

        // 根据背景图宽度计算文字大小
        val baseTextSizePx = bitmap.width * textSizePercent / 100f

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

        // 设置字间距（使用背景图宽度百分比）
        if (config.letterSpacing > 0 && finalTextSize > 0) {
            val letterSpacingPx = bitmap.width * config.letterSpacing / 100f
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