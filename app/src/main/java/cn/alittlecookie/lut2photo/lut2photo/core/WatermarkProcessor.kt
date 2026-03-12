package cn.alittlecookie.lut2photo.lut2photo.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.graphics.createBitmap
import androidx.palette.graphics.Palette
import cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode
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
        private const val MAX_PROCESSING_PIXELS = 800_000_000L // 提升到8亿像素，支持更大图片水印处理
    }

    private val exifReader = ExifReader(context)
    private val density = context.resources.displayMetrics.density
    private val memoryManager = MemoryManager.getInstance(context)

    /**
     * 在主线程显示Toast提示
     */
    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 为图片添加水印
     * @param originalBitmap 原始图片
     * @param config 水印配置
     * @param imageUri 图片URI，用于读取EXIF信息
     * @param lut1Name LUT1文件名
     * @param lut2Name LUT2文件名
     * @param lut1Strength LUT1强度
     * @param lut2Strength LUT2强度
     * @return 添加水印后的图片
     */
    suspend fun addWatermark(
        originalBitmap: Bitmap,
        config: WatermarkConfig,
        imageUri: Uri? = null,
        lut1Name: String? = null,
        lut2Name: String? = null,
        lut1Strength: Float? = null,
        lut2Strength: Float? = null
    ): Bitmap = withContext(Dispatchers.Default) {

        if (!config.isEnabled || (!config.enableTextWatermark && !config.enableImageWatermark)) {
            return@withContext addBorderOnly(originalBitmap, config)
        }

        // 先添加边框（内部已处理压缩逻辑）
        val bitmapWithBorder = addBorderOnly(originalBitmap, config)

        // 计算复制Bitmap需要的内存
        val copyMemoryBytes = bitmapWithBorder.width.toLong() * bitmapWithBorder.height.toLong() * 4

        // 检查内存是否足够复制Bitmap
        if (!memoryManager.canAllocate(copyMemoryBytes)) {
            android.util.Log.w(
                TAG,
                "内存不足，无法复制${bitmapWithBorder.width}x${bitmapWithBorder.height}的Bitmap用于水印绘制"
            )

            // 尝试垃圾回收后再次检查
            memoryManager.performGarbageCollection()

            if (!memoryManager.canAllocate(copyMemoryBytes)) {
                android.util.Log.w(TAG, "即使在GC后仍无法分配内存，返回边框图片")
                return@withContext bitmapWithBorder
            }
        }

        // 请求内存分配
        if (!memoryManager.requestAllocation(copyMemoryBytes)) {
            android.util.Log.w(TAG, "内存分配请求被拒绝，返回边框图片")
            return@withContext bitmapWithBorder
        }

        // 创建可变的Bitmap用于绘制水印
        val resultBitmap = try {
            bitmapWithBorder.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: OutOfMemoryError) {
            android.util.Log.e(TAG, "复制Bitmap时发生OOM，返回边框图片", e)
            return@withContext bitmapWithBorder
        }
        
        val canvas = Canvas(resultBitmap)

        // 及时释放边框图片（如果它与原图不同）
        if (bitmapWithBorder != originalBitmap && !bitmapWithBorder.isRecycled) {
            bitmapWithBorder.recycle()
            android.util.Log.d(TAG, "已释放边框图片Bitmap")
        }

        // 读取EXIF信息
        val exifData = imageUri?.let { exifReader.readExifFromUri(it) } ?: emptyMap()

        // 判断是否启用文字跟随模式
        if (config.enableTextFollowMode && config.enableTextWatermark && config.enableImageWatermark &&
            config.textContent.isNotEmpty() && config.imagePath.isNotEmpty()
        ) {
            // 文字跟随模式：根据图片水印位置计算文字位置
            drawWatermarksInFollowMode(
                canvas,
                resultBitmap,
                config,
                exifData,
                lut1Name,
                lut2Name,
                lut1Strength,
                lut2Strength
            )
        } else {
            // 普通模式：分别独立绘制图片和文字水印
            drawWatermarksInNormalMode(
                canvas,
                resultBitmap,
                config,
                exifData,
                lut1Name,
                lut2Name,
                lut1Strength,
                lut2Strength
            )
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

            // 使用新的压缩逻辑，确保最终输出图片（包括边框）不超过8亿像素
            val processedBitmap = compressBitmapForProcessing(originalBitmap, config)

            // 基于压缩后的图片重新计算边框
            val shortSide = min(processedBitmap.width, processedBitmap.height)
        val borderTopPx = (shortSide * config.borderTopWidth / 100).toInt()
        val borderBottomPx = (shortSide * config.borderBottomWidth / 100).toInt()
        val borderLeftPx = (shortSide * config.borderLeftWidth / 100).toInt()
        val borderRightPx = (shortSide * config.borderRightWidth / 100).toInt()

            // 计算新的图片尺寸
            val newWidth = processedBitmap.width + borderLeftPx + borderRightPx
            val newHeight = processedBitmap.height + borderTopPx + borderBottomPx

            // 计算需要的内存大小（ARGB_8888每像素4字节）
            val requiredMemoryBytes = newWidth.toLong() * newHeight.toLong() * 4

            // 检查内存是否足够
            if (!memoryManager.canAllocate(requiredMemoryBytes)) {
                android.util.Log.w(
                    TAG,
                    "内存不足，无法创建${newWidth}x${newHeight}的边框Bitmap，需要${requiredMemoryBytes / (1024 * 1024)}MB"
                )

                // 尝试垃圾回收后再次检查
                memoryManager.performGarbageCollection()

                if (!memoryManager.canAllocate(requiredMemoryBytes)) {
                    android.util.Log.w(TAG, "即使在GC后仍无法分配内存，返回处理后的图片")
                    showToast("超出像素限制，已返回无边框图片")
                    return@withContext processedBitmap
                }
            }

            try {
                // 请求内存分配
                if (!memoryManager.requestAllocation(requiredMemoryBytes)) {
                    android.util.Log.w(TAG, "内存分配请求被拒绝，返回处理后的图片")
                    showToast("内存分配被拒绝，已返回无边框图片")
                    return@withContext processedBitmap
            }

                val resultBitmap = createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(resultBitmap)

                // 获取边框颜色（支持动态提取）
                val borderColor = when (config.borderColorMode) {
                    BorderColorMode.PALETTE -> {
                        // 从图片中提取主要颜色
                        extractDominantColorFromBitmap(processedBitmap)
                            ?: config.getBorderColorInt()
                    }

                    else -> config.getBorderColorInt()
                }

                // 绘制边框背景
                val borderPaint = Paint().apply {
                    color = borderColor
                    style = Paint.Style.FILL
                }

                // 绘制整个背景
                canvas.drawRect(0f, 0f, newWidth.toFloat(), newHeight.toFloat(), borderPaint)

                // 绘制原图（放置在边框内）
                canvas.drawBitmap(
                    processedBitmap,  // 使用可能已压缩的图片
                    borderLeftPx.toFloat(),
                    borderTopPx.toFloat(),
                    null
                )

                // 及时释放处理后的图片（如果它是压缩后的新图片）
                if (processedBitmap != originalBitmap && !processedBitmap.isRecycled) {
                    processedBitmap.recycle()
                    android.util.Log.d(TAG, "已释放压缩后的中间Bitmap")
                }

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
     * 当最终输出图片（包括边框）像素数超过8亿时，压缩图片到合适尺寸
     * @param bitmap 原始图片
     * @param config 水印配置（用于计算边框大小）
     * @return 压缩后的图片（如果不需要压缩则返回原图）
     */
    private suspend fun compressBitmapForProcessing(
        bitmap: Bitmap,
        config: WatermarkConfig
    ): Bitmap =
        withContext(Dispatchers.Default) {
            // 计算边框大小
            val shortSide = min(bitmap.width, bitmap.height)
            val borderTopPx = (shortSide * config.borderTopWidth / 100).toInt()
            val borderBottomPx = (shortSide * config.borderBottomWidth / 100).toInt()
            val borderLeftPx = (shortSide * config.borderLeftWidth / 100).toInt()
            val borderRightPx = (shortSide * config.borderRightWidth / 100).toInt()

            // 计算最终输出尺寸（包括边框）
            val finalWidth = bitmap.width + borderLeftPx + borderRightPx
            val finalHeight = bitmap.height + borderTopPx + borderBottomPx
            val finalPixels = finalWidth.toLong() * finalHeight.toLong()

            if (finalPixels <= MAX_PROCESSING_PIXELS) {
                android.util.Log.d(TAG, "最终输出图片像素数($finalPixels)在8亿像素限制内，无需压缩")
                return@withContext bitmap
            }

            // 计算压缩比例，确保最终输出图片（包括边框）不超过8亿像素
            val scale = sqrt(MAX_PROCESSING_PIXELS.toDouble() / finalPixels.toDouble())
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()

            // 验证压缩后的最终输出尺寸
            val newShortSide = min(newWidth, newHeight)
            val newBorderTopPx = (newShortSide * config.borderTopWidth / 100).toInt()
            val newBorderBottomPx = (newShortSide * config.borderBottomWidth / 100).toInt()
            val newBorderLeftPx = (newShortSide * config.borderLeftWidth / 100).toInt()
            val newBorderRightPx = (newShortSide * config.borderRightWidth / 100).toInt()
            val newFinalWidth = newWidth + newBorderLeftPx + newBorderRightPx
            val newFinalHeight = newHeight + newBorderTopPx + newBorderBottomPx
            val newFinalPixels = newFinalWidth.toLong() * newFinalHeight.toLong()

            android.util.Log.d(
                TAG,
                "最终输出图片像素数($finalPixels)超过8亿像素限制，将压缩原图从${bitmap.width}x${bitmap.height}至${newWidth}x${newHeight}，最终输出尺寸将为${newFinalWidth}x${newFinalHeight}($newFinalPixels)"
            )

            // 计算压缩后需要的内存
            val compressedMemoryBytes = newWidth.toLong() * newHeight.toLong() * 4

            // 检查内存是否足够
            if (!memoryManager.canAllocate(compressedMemoryBytes)) {
                android.util.Log.w(
                    TAG,
                    "内存不足，无法创建压缩后的${newWidth}x${newHeight}Bitmap，需要${compressedMemoryBytes / (1024 * 1024)}MB"
                )

                // 尝试垃圾回收后再次检查
                memoryManager.performGarbageCollection()

                if (!memoryManager.canAllocate(compressedMemoryBytes)) {
                    android.util.Log.w(TAG, "即使在GC后仍无法分配内存，返回原图")
                    return@withContext bitmap
                }
            }
            
            try {
                // 请求内存分配
                if (!memoryManager.requestAllocation(compressedMemoryBytes)) {
                    android.util.Log.w(TAG, "内存分配请求被拒绝，返回原图")
                    return@withContext bitmap
                }
                
                val compressedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                val compressedPixels =
                    compressedBitmap.width.toLong() * compressedBitmap.height.toLong()

                // 重新计算压缩后的最终输出尺寸（用于验证）
                val verifyShortSide = min(compressedBitmap.width, compressedBitmap.height)
                val verifyBorderTopPx = (verifyShortSide * config.borderTopWidth / 100).toInt()
                val verifyBorderBottomPx =
                    (verifyShortSide * config.borderBottomWidth / 100).toInt()
                val verifyBorderLeftPx = (verifyShortSide * config.borderLeftWidth / 100).toInt()
                val verifyBorderRightPx = (verifyShortSide * config.borderRightWidth / 100).toInt()
                val verifyFinalWidth =
                    compressedBitmap.width + verifyBorderLeftPx + verifyBorderRightPx
                val verifyFinalHeight =
                    compressedBitmap.height + verifyBorderTopPx + verifyBorderBottomPx
                val verifyFinalPixels = verifyFinalWidth.toLong() * verifyFinalHeight.toLong()
                
                android.util.Log.d(
                    TAG,
                    "图片压缩成功：${bitmap.width}x${bitmap.height}($finalPixels) -> ${compressedBitmap.width}x${compressedBitmap.height}($compressedPixels)，最终输出将为${verifyFinalWidth}x${verifyFinalHeight}($verifyFinalPixels)"
                )

                // 如果创建了新的bitmap，且与原始的不同，则释放原始的
                if (compressedBitmap != bitmap && !bitmap.isRecycled) {
                    bitmap.recycle()
                    android.util.Log.d(TAG, "已释放原始Bitmap")
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
     * @param bitmap 目标图片（带边框的最终图片）
     * @param xPercent X位置百分比 (0-100)
     * @param yPercent Y位置百分比 (0-100)
     * @param reference 定位参考系
     * @param config 水印配置（用于获取边框信息）
     * @return 水印中心点坐标
     */
    private fun calculateWatermarkPosition(
        bitmap: Bitmap,
        xPercent: Float,
        yPercent: Float,
        reference: cn.alittlecookie.lut2photo.lut2photo.model.WatermarkPositionReference,
        config: WatermarkConfig
    ): PointF {
        // 首先需要确定原图尺寸，用于统一的边框计算
        val originalRect = calculateOriginalImageRect(bitmap, config)
        val originalWidth = originalRect.width().toInt()
        val originalHeight = originalRect.height().toInt()
        
        return when (reference) {
            cn.alittlecookie.lut2photo.lut2photo.model.WatermarkPositionReference.CANVAS -> {
                // 整个画布（现有逻辑）
                val x = bitmap.width * xPercent / 100
                val y = bitmap.height * yPercent / 100
                PointF(x, y)
            }
            cn.alittlecookie.lut2photo.lut2photo.model.WatermarkPositionReference.ORIGINAL -> {
                // 原图区域
                val x = originalRect.left + originalRect.width() * xPercent / 100
                val y = originalRect.top + originalRect.height() * yPercent / 100
                PointF(x, y)
            }
            cn.alittlecookie.lut2photo.lut2photo.model.WatermarkPositionReference.TOP_BORDER -> {
                // 上边框区域 - 基于原图尺寸计算边框大小
                val borderRect = calculateTopBorderRect(originalWidth, originalHeight, config)
                val x = borderRect.left + borderRect.width() * xPercent / 100
                val y = borderRect.top + borderRect.height() * yPercent / 100
                PointF(x, y)
            }
            cn.alittlecookie.lut2photo.lut2photo.model.WatermarkPositionReference.BOTTOM_BORDER -> {
                // 下边框区域 - 基于原图尺寸计算边框大小
                val borderRect = calculateBottomBorderRect(originalWidth, originalHeight, config)
                val x = borderRect.left + borderRect.width() * xPercent / 100
                val y = borderRect.top + borderRect.height() * yPercent / 100
                PointF(x, y)
            }
            cn.alittlecookie.lut2photo.lut2photo.model.WatermarkPositionReference.LEFT_BORDER -> {
                // 左边框区域 - 基于原图尺寸计算边框大小
                val borderRect = calculateLeftBorderRect(originalWidth, originalHeight, config)
                val x = borderRect.left + borderRect.width() * xPercent / 100
                val y = borderRect.top + borderRect.height() * yPercent / 100
                PointF(x, y)
            }
            cn.alittlecookie.lut2photo.lut2photo.model.WatermarkPositionReference.RIGHT_BORDER -> {
                // 右边框区域 - 基于原图尺寸计算边框大小
                val borderRect = calculateRightBorderRect(originalWidth, originalHeight, config)
                val x = borderRect.left + borderRect.width() * xPercent / 100
                val y = borderRect.top + borderRect.height() * yPercent / 100
                PointF(x, y)
            }
        }
    }

    /**
     * 兼容旧版本的计算水印位置方法
     */
    private fun calculateWatermarkPosition(
        bitmap: Bitmap,
        xPercent: Float,
        yPercent: Float
    ): PointF {
        return calculateWatermarkPosition(
            bitmap, 
            xPercent, 
            yPercent, 
            cn.alittlecookie.lut2photo.lut2photo.model.WatermarkPositionReference.CANVAS,
            WatermarkConfig()
        )
    }

    /**
     * 计算间距
     */
    private fun calculateSpacing(bitmap: Bitmap, spacingPercent: Float): Float {
        return bitmap.height * spacingPercent / 100
    }

    /**
     * 计算原图区域矩形
     * @param bitmap 包含边框的完整图片
     * @param config 水印配置
     * @return 原图区域的矩形
     */
    private fun calculateOriginalImageRect(bitmap: Bitmap, config: WatermarkConfig): android.graphics.RectF {
        // 基于最终图片尺寸反推原图尺寸，然后计算边框像素大小
        // 这里需要解方程：finalWidth = originalWidth + leftBorder + rightBorder
        // 其中 leftBorder = originalShortSide * leftPercent / 100
        
        // 为了保持与addBorderOnly一致的计算逻辑，我们需要反推原图尺寸
        // 假设原图的短边为 s，则：
        // finalWidth = originalWidth + s * (leftPercent + rightPercent) / 100
        // finalHeight = originalHeight + s * (topPercent + bottomPercent) / 100
        
        val totalHorizontalBorderPercent = config.borderLeftWidth + config.borderRightWidth
        val totalVerticalBorderPercent = config.borderTopWidth + config.borderBottomWidth
        
        // 通过迭代求解原图尺寸（简化处理：假设原图比例与最终图片相近）
        val estimatedOriginalWidth = bitmap.width / (1 + totalHorizontalBorderPercent / 100)
        val estimatedOriginalHeight = bitmap.height / (1 + totalVerticalBorderPercent / 100)
        val estimatedShortSide = min(estimatedOriginalWidth, estimatedOriginalHeight)
        
        // 基于估算的原图短边计算边框像素大小
        val borderTopPx = (estimatedShortSide * config.borderTopWidth / 100).toInt()
        val borderBottomPx = (estimatedShortSide * config.borderBottomWidth / 100).toInt()
        val borderLeftPx = (estimatedShortSide * config.borderLeftWidth / 100).toInt()
        val borderRightPx = (estimatedShortSide * config.borderRightWidth / 100).toInt()

        return android.graphics.RectF(
            borderLeftPx.toFloat(),
            borderTopPx.toFloat(),
            (bitmap.width - borderRightPx).toFloat(),
            (bitmap.height - borderBottomPx).toFloat()
        )
    }

    /**
     * 计算上边框区域矩形
     * @param originalWidth 原图宽度
     * @param originalHeight 原图高度  
     * @param config 水印配置
     */
    private fun calculateTopBorderRect(originalWidth: Int, originalHeight: Int, config: WatermarkConfig): android.graphics.RectF {
        val shortSide = min(originalWidth, originalHeight)
        val borderTopPx = (shortSide * config.borderTopWidth / 100).toInt()
        val borderLeftPx = (shortSide * config.borderLeftWidth / 100).toInt()
        val borderRightPx = (shortSide * config.borderRightWidth / 100).toInt()
        
        return android.graphics.RectF(
            0f,
            0f,
            (originalWidth + borderLeftPx + borderRightPx).toFloat(),
            borderTopPx.toFloat()
        )
    }

    /**
     * 计算下边框区域矩形
     * @param originalWidth 原图宽度
     * @param originalHeight 原图高度
     * @param config 水印配置
     */
    private fun calculateBottomBorderRect(originalWidth: Int, originalHeight: Int, config: WatermarkConfig): android.graphics.RectF {
        val shortSide = min(originalWidth, originalHeight)
        val borderTopPx = (shortSide * config.borderTopWidth / 100).toInt()
        val borderBottomPx = (shortSide * config.borderBottomWidth / 100).toInt()
        val borderLeftPx = (shortSide * config.borderLeftWidth / 100).toInt()
        val borderRightPx = (shortSide * config.borderRightWidth / 100).toInt()
        
        val finalHeight = originalHeight + borderTopPx + borderBottomPx
        
        return android.graphics.RectF(
            0f,
            (finalHeight - borderBottomPx).toFloat(),
            (originalWidth + borderLeftPx + borderRightPx).toFloat(),
            finalHeight.toFloat()
        )
    }

    /**
     * 计算左边框区域矩形
     * @param originalWidth 原图宽度
     * @param originalHeight 原图高度
     * @param config 水印配置
     */
    private fun calculateLeftBorderRect(originalWidth: Int, originalHeight: Int, config: WatermarkConfig): android.graphics.RectF {
        val shortSide = min(originalWidth, originalHeight)
        val borderTopPx = (shortSide * config.borderTopWidth / 100).toInt()
        val borderBottomPx = (shortSide * config.borderBottomWidth / 100).toInt()
        val borderLeftPx = (shortSide * config.borderLeftWidth / 100).toInt()
        
        return android.graphics.RectF(
            0f,
            0f,
            borderLeftPx.toFloat(),
            (originalHeight + borderTopPx + borderBottomPx).toFloat()
        )
    }

    /**
     * 计算右边框区域矩形
     * @param originalWidth 原图宽度
     * @param originalHeight 原图高度
     * @param config 水印配置
     */
    private fun calculateRightBorderRect(originalWidth: Int, originalHeight: Int, config: WatermarkConfig): android.graphics.RectF {
        val shortSide = min(originalWidth, originalHeight)
        val borderTopPx = (shortSide * config.borderTopWidth / 100).toInt()
        val borderBottomPx = (shortSide * config.borderBottomWidth / 100).toInt()
        val borderLeftPx = (shortSide * config.borderLeftWidth / 100).toInt()
        val borderRightPx = (shortSide * config.borderRightWidth / 100).toInt()
        
        val finalWidth = originalWidth + borderLeftPx + borderRightPx
        
        return android.graphics.RectF(
            (finalWidth - borderRightPx).toFloat(),
            0f,
            finalWidth.toFloat(),
            (originalHeight + borderTopPx + borderBottomPx).toFloat()
        )
    }

    /**
     * 从图片中提取主要颜色
     * @param bitmap 要提取颜色的图片
     * @return 提取到的主要颜色，如果提取失败返回null
     */
    private suspend fun extractDominantColorFromBitmap(bitmap: Bitmap): Int? =
        withContext(Dispatchers.Default) {
            try {
                // 为了提高性能，如果图片太大，先缩小再提取颜色
                val scaledBitmap = if (bitmap.width > 200 || bitmap.height > 200) {
                    val scale = 200f / kotlin.math.max(bitmap.width, bitmap.height)
                    val newWidth = (bitmap.width * scale).toInt()
                    val newHeight = (bitmap.height * scale).toInt()
                    Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, false)
                } else {
                    bitmap
                }

                var dominantColor: Int? = null

                // 使用Palette库提取颜色
                val palette = Palette.from(scaledBitmap).generate()

                // 优先选择有活力的颜色，其次是柔和的颜色
                dominantColor = palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                            ?: palette.darkVibrantSwatch?.rgb
                            ?: palette.mutedSwatch?.rgb
                            ?: palette.lightMutedSwatch?.rgb
                            ?: palette.darkMutedSwatch?.rgb
                            ?: palette.dominantSwatch?.rgb

                // 如果缩放了图片，释放缩放后的图片
                if (scaledBitmap != bitmap && !scaledBitmap.isRecycled) {
                    scaledBitmap.recycle()
                }

                android.util.Log.d(
                    TAG,
                    "从图片中提取到主要颜色: ${
                        dominantColor?.let {
                            String.format(
                                "#%06X",
                                0xFFFFFF and it
                            )
                        }
                    }"
                )
                dominantColor
            } catch (e: Exception) {
                android.util.Log.e(TAG, "提取图片主要颜色失败", e)
                null
            }
    }

    /**
     * 跟随模式下的水印绘制
     */
    private suspend fun drawWatermarksInFollowMode(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig,
        exifData: Map<String, String>,
        lut1Name: String? = null,
        lut2Name: String? = null,
        lut1Strength: Float? = null,
        lut2Strength: Float? = null
    ) {
        // 首先加载和绘制图片水印，获取其位置和尺寸
        val imageWatermarkSize = calculateWatermarkSize(bitmap, config.imageSize)
        val watermarkImage = loadWatermarkImage(config.imagePath, imageWatermarkSize)

        watermarkImage?.let { image ->
            // 计算图片水印位置（使用图片水印位置参数）
            val imagePosition = calculateWatermarkPosition(
                bitmap,
                config.imagePositionX,
                config.imagePositionY,
                config.imagePositionReference,
                config
            )
            val imageX = imagePosition.x - image.width / 2f
            val imageY = imagePosition.y - image.height / 2f

            // 绘制图片水印
            val imagePaint = Paint().apply {
                alpha = (config.imageOpacity * 255 / 100).toInt()
            }
            canvas.drawBitmap(image, imageX, imageY, imagePaint)

            // 根据跟随方向计算文字位置
            val processedText = exifReader.replaceExifVariables(
                config.textContent,
                exifData,
                lut1Name,
                lut2Name,
                lut1Strength,
                lut2Strength
            )
            val textPosition = calculateTextFollowPosition(
                bitmap,
                imagePosition,
                image.width,
                image.height,
                config.textFollowDirection,
                config.textImageSpacing,
                config.textAlignment,
                config.textSize,
                processedText,
                config.fontPath,
                config.letterSpacing
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
        exifData: Map<String, String>,
        lut1Name: String? = null,
        lut2Name: String? = null,
        lut1Strength: Float? = null,
        lut2Strength: Float? = null
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
                    config.imagePositionY,
                    config.imagePositionReference,
                    config
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
            val processedText = exifReader.replaceExifVariables(
                config.textContent,
                exifData,
                lut1Name,
                lut2Name,
                lut1Strength,
                lut2Strength
            )
            // 计算文字水印位置
            val textPosition = calculateWatermarkPosition(
                bitmap, 
                config.textPositionX, 
                config.textPositionY,
                config.textPositionReference,
                config
            )
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
     * 计算单个文字的尺寸
     * @param bitmap 图片
     * @param textSizePercent 文字大小百分比
     * @param text 要测量的文本
     * @param fontPath 字体路径
     * @return Pair(单个文字的宽度, 单个文字的高度)（像素）
     */
    private fun calculateSingleCharSize(
        bitmap: Bitmap,
        textSizePercent: Float,
        text: String,
        fontPath: String,
        letterSpacing: Float = 0f
    ): Pair<Float, Float> {
        val lines = text.split("\n")
        if (lines.isEmpty()) return Pair(0f, 0f)
        
        val tempPaint = Paint().apply {
            isAntiAlias = true
            if (fontPath.isNotEmpty()) {
                try {
                    val fontFile = File(fontPath)
                    if (fontFile.exists()) {
                        typeface = Typeface.createFromFile(fontFile)
                    }
                } catch (_: Exception) {
                    // 使用默认字体
                }
            }
        }
        
        // 目标宽度：图片宽度的百分比
        val targetWidth = bitmap.width * textSizePercent / 100f
        
        // 二分查找合适的字体大小（建议3：考虑字间距）
        var minSize = 1f
        var maxSize = bitmap.width.toFloat()
        var finalTextSize = minSize
        
        for (i in 0 until 10) { // 优化迭代次数到10次，提升性能
            val testSize = (minSize + maxSize) / 2f
            tempPaint.textSize = testSize
            
            // 建议3：在二分查找时就设置字间距
            if (letterSpacing != 0f) {
                val singleCharWidth = tempPaint.measureText("字")
                val letterSpacingPx = singleCharWidth * letterSpacing / 100f
                tempPaint.letterSpacing = letterSpacingPx / testSize
            } else {
                tempPaint.letterSpacing = 0f
            }
            
            // 找到实际渲染宽度最宽的行
            val maxLineWidth = lines.maxOfOrNull { line -> 
                if (line.isEmpty()) 0f else tempPaint.measureText(line)
            } ?: 0f
            
            if (kotlin.math.abs(maxLineWidth - targetWidth) < 1f) {
                finalTextSize = testSize
                break
            } else if (maxLineWidth < targetWidth) {
                // 测量宽度小于目标，需要增大字体
                minSize = testSize
            } else {
                // 测量宽度大于目标，需要减小字体
                maxSize = testSize
            }
            
            // 使用当前范围的中点作为最终结果
            finalTextSize = (minSize + maxSize) / 2f
        }
        
        tempPaint.textSize = finalTextSize
        
        // 设置最终的字间距
        if (letterSpacing != 0f) {
            val singleCharWidth = tempPaint.measureText("字")
            val letterSpacingPx = singleCharWidth * letterSpacing / 100f
            tempPaint.letterSpacing = letterSpacingPx / finalTextSize
        } else {
            tempPaint.letterSpacing = 0f
        }
        
        // 找到实际渲染宽度最宽的行（用于日志）
        val longestLine = lines.maxByOrNull { line -> 
            if (line.isEmpty()) 0f else tempPaint.measureText(line)
        } ?: "字"
        val maxLineWidth = if (longestLine.isEmpty()) 0f else tempPaint.measureText(longestLine)
        
        val charWidth = tempPaint.measureText("字")
        
        // 建议1：使用真实字体度量获取字符高度
        val fontMetrics = tempPaint.fontMetrics
        val charHeight = fontMetrics.descent - fontMetrics.ascent
        
        android.util.Log.d(TAG, "calculateSingleCharSize - 图片宽度: ${bitmap.width}px, 目标百分比: $textSizePercent%, 目标宽度: ${targetWidth}px")
        android.util.Log.d(TAG, "calculateSingleCharSize - 最宽行: \"$longestLine\", 实际宽度: ${maxLineWidth}px")
        android.util.Log.d(TAG, "calculateSingleCharSize - 最终字体大小: ${finalTextSize}px, 单字宽度: ${charWidth}px")
        android.util.Log.d(TAG, "calculateSingleCharSize - 真实字符高度: ${charHeight}px (ascent: ${fontMetrics.ascent}, descent: ${fontMetrics.descent})")
        
        return Pair(charWidth, charHeight)
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
        textAlignment: cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment,
        textSizePercent: Float,
        text: String,
        fontPath: String,
        letterSpacing: Float = 0f
    ): PointF {
        // 计算间距（基于增加边框后的图片尺寸的百分比）
        // 上方和下方使用图片高度百分比，左侧和右侧使用图片宽度百分比
        val spacing = when (followDirection) {
            TextFollowDirection.TOP, TextFollowDirection.BOTTOM -> {
                // 上方和下方：使用增加边框后的图片高度百分比
                bitmap.height * spacingPercent / 100f
            }
            TextFollowDirection.LEFT, TextFollowDirection.RIGHT -> {
                // 左侧和右侧：使用增加边框后的图片宽度百分比
                bitmap.width * spacingPercent / 100f
            }
        }

        // 计算文字段落视觉中心的位置
        // 文字跟随模式下，锚点始终在文字段落的视觉中心，不受对齐方式影响
        val textCenterX: Float
        val textCenterY: Float

        when (followDirection) {
            TextFollowDirection.TOP -> {
                // 文字在图片上方，文字段落的视觉中心位置
                textCenterY = imagePosition.y - imageHeight / 2f - spacing
                textCenterX = imagePosition.x // 始终居中对齐到图片中心
            }

            TextFollowDirection.BOTTOM -> {
                // 文字在图片下方，文字段落的视觉中心位置
                textCenterY = imagePosition.y + imageHeight / 2f + spacing
                textCenterX = imagePosition.x // 始终居中对齐到图片中心
            }

            TextFollowDirection.LEFT -> {
                // 文字在图片左侧，文字段落的视觉中心位置
                textCenterX = imagePosition.x - imageWidth / 2f - spacing
                textCenterY = imagePosition.y // 始终垂直居中对齐到图片中心
            }

            TextFollowDirection.RIGHT -> {
                // 文字在图片右侧，文字段落的视觉中心位置
                textCenterX = imagePosition.x + imageWidth / 2f + spacing
                textCenterY = imagePosition.y // 始终垂直居中对齐到图片中心
            }
        }

        // 返回文字段落的视觉中心位置
        // drawTextWatermark方法会基于这个中心点和对齐方式来绘制文字
        return PointF(textCenterX, textCenterY)
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
        
        val paint = Paint().apply {
            color = config.getTextColorInt()
            alpha = (config.textOpacity * 255 / 100).toInt()
            isAntiAlias = true

            // 设置文本对齐方式为LEFT，因为我们手动计算每行的精确位置
            textAlign = Paint.Align.LEFT

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

        // 目标宽度：图片宽度的百分比
        val targetWidth = bitmap.width * textSizePercent / 100f
        
        android.util.Log.d(TAG, "========== 开始文字大小计算 ==========")
        android.util.Log.d(TAG, "图片宽度: ${bitmap.width}px, 目标百分比: $textSizePercent%, 目标宽度: ${targetWidth}px")
        android.util.Log.d(TAG, "文本行数: ${lines.size}")
        lines.forEachIndexed { index, line ->
            android.util.Log.d(TAG, "第${index + 1}行: \"$line\" (${line.length}个字符)")
        }
        
        // 二分查找合适的字体大小，使最宽行的实际渲染宽度等于目标宽度
        // 建议3：在二分查找时就考虑字间距
        var minSize = 1f
        var maxSize = bitmap.width.toFloat()
        var finalTextSize = minSize
        
        // 迭代查找最佳字体大小
        for (i in 0 until 10) { // 优化迭代次数到10次，提升性能
            val testSize = (minSize + maxSize) / 2f
            paint.textSize = testSize
            
            // 建议3：在二分查找时就设置字间距
            if (config.letterSpacing != 0f) {
                val singleCharWidth = paint.measureText("字")
                val letterSpacingPx = singleCharWidth * config.letterSpacing / 100f
                paint.letterSpacing = letterSpacingPx / testSize
            } else {
                paint.letterSpacing = 0f
            }
            
            // 找到实际渲染宽度最宽的行
            val maxLineWidth = lines.maxOfOrNull { line -> 
                if (line.isEmpty()) 0f else paint.measureText(line)
            } ?: 0f
            
            android.util.Log.d(TAG, "迭代 $i - 字体: ${testSize}px, 最宽行宽: ${maxLineWidth}px, 目标: ${targetWidth}px, 差值: ${maxLineWidth - targetWidth}px, minSize: ${minSize}px, maxSize: ${maxSize}px")
            
            if (kotlin.math.abs(maxLineWidth - targetWidth) < 1f) {
                // 足够接近目标宽度
                finalTextSize = testSize
                android.util.Log.d(TAG, "✓ 找到合适的字体大小: ${finalTextSize}px (迭代${i}次)")
                break
            } else if (maxLineWidth < targetWidth) {
                // 测量宽度小于目标，需要增大字体
                minSize = testSize
            } else {
                // 测量宽度大于目标，需要减小字体
                maxSize = testSize
            }
            
            // 使用当前范围的中点作为最终结果
            finalTextSize = (minSize + maxSize) / 2f
        }

        // 设置最终的字体大小和字间距
        paint.textSize = finalTextSize
        
        if (config.letterSpacing != 0f) {
            val singleCharWidth = paint.measureText("字")
            val letterSpacingPx = singleCharWidth * config.letterSpacing / 100f
            paint.letterSpacing = letterSpacingPx / finalTextSize
        } else {
            paint.letterSpacing = 0f
        }
        
        // 找到实际渲染宽度最宽的行（用于日志）
        val longestLine = lines.maxByOrNull { line -> 
            if (line.isEmpty()) 0f else paint.measureText(line)
        } ?: ""
        val longestLineWidth = if (longestLine.isEmpty()) 0f else paint.measureText(longestLine)

        android.util.Log.d(TAG, "最终字体大小: ${finalTextSize}px")
        android.util.Log.d(TAG, "最宽行: \"$longestLine\", 实际宽度: ${longestLineWidth}px")
        android.util.Log.d(TAG, "目标宽度: ${targetWidth}px, 误差: ${longestLineWidth - targetWidth}px (${((longestLineWidth - targetWidth) / targetWidth * 100)}%)")
        android.util.Log.d(TAG, "========== 文字大小计算完成 ==========\n")

        // 建议1和建议2：使用真实字体度量计算行间距和基线偏移
        val fontMetrics = paint.fontMetrics
        val actualCharHeight = fontMetrics.descent - fontMetrics.ascent
        val baselineOffset = -fontMetrics.ascent
        
        // 使用字体推荐的行高（包含leading）
        val recommendedLineHeight = actualCharHeight + fontMetrics.leading
        val baseLineHeight = recommendedLineHeight
        val additionalLineSpacing = if (config.lineSpacing != 0f) {
            actualCharHeight * config.lineSpacing / 100f
        } else {
            0f
        }
        val lineHeight = baseLineHeight + additionalLineSpacing
        
        val totalHeight = lines.size * lineHeight
        
        // 计算文字段落的最小外接矩形
        // 先计算所有行的实际宽度，找出最宽的行
        val maxLineWidth = lines.maxOfOrNull { line -> 
            if (line.isEmpty()) 0f else paint.measureText(line)
        } ?: 0f
        
        // 文字段落的视觉中心：最小外接矩形的中心
        // 垂直方向：使用真实基线位置计算
        val textCenterY = centerY
        val startY = textCenterY - totalHeight / 2 + baselineOffset
        
        android.util.Log.d(TAG, "========== 文字锚点计算 ==========")
        android.util.Log.d(TAG, "字体度量 - ascent: ${fontMetrics.ascent}, descent: ${fontMetrics.descent}, leading: ${fontMetrics.leading}")
        android.util.Log.d(TAG, "实际字符高度: ${actualCharHeight}px, 基线偏移: ${baselineOffset}px")
        android.util.Log.d(TAG, "推荐行高: ${recommendedLineHeight}px, 最终行高: ${lineHeight}px")
        android.util.Log.d(TAG, "总高度: ${totalHeight}px, 起始Y: ${startY}px")
        android.util.Log.d(TAG, "========== 锚点计算完成 ==========\n")
        
        // 水平方向：根据对齐方式计算，但锚点始终在段落视觉中心
        val textCenterX = centerX
        
        lines.forEachIndexed { index, line ->
            if (line.isNotEmpty()) {
                val y = startY + index * lineHeight
                
                // 根据对齐方式计算每行的X坐标
                // 无论哪种对齐方式，都确保整个段落的视觉中心在centerX上
                val lineWidth = paint.measureText(line)
                val lineX = when (config.textAlignment) {
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT -> {
                        // 左对齐：段落左边界距离视觉中心 maxLineWidth/2
                        textCenterX - maxLineWidth / 2
                    }
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.CENTER -> {
                        // 居中对齐：每行都居中到段落视觉中心
                        textCenterX - lineWidth / 2
                    }
                    cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.RIGHT -> {
                        // 右对齐：段落右边界距离视觉中心 maxLineWidth/2
                        textCenterX + maxLineWidth / 2 - lineWidth
                    }
                }
                
                // 直接绘制，不做任何避让处理，允许超出画布范围
                canvas.drawText(line, lineX, y, paint)
            }
        }
    }
}