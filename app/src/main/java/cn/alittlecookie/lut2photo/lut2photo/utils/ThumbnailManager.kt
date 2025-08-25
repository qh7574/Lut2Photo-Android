package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max

/**
 * 缩略图管理器
 * 负责生成、缓存和管理处理历史的输出图片缩略图
 * 使用内存缓存 + 磁盘缓存的策略，提供高效的缩略图服务
 */
class ThumbnailManager(private val context: Context) {

    companion object {
        private const val TAG = "ThumbnailManager"

        // 缩略图尺寸设置
        private const val THUMBNAIL_WIDTH = 120 // dp转换为px后的宽度
        private const val THUMBNAIL_HEIGHT = 80 // dp转换为px后的高度
        private const val THUMBNAIL_CORNER_RADIUS = 8f // 圆角半径

        // 缓存设置
        private const val MEMORY_CACHE_SIZE = 20 * 1024 * 1024 // 20MB内存缓存
        private const val CACHE_DIR_NAME = "thumbnails"

        // 图片质量
        private const val JPEG_QUALITY = 80
    }

    // 内存缓存
    private val memoryCache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(MEMORY_CACHE_SIZE) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.allocationByteCount
            }
        }

    // 磁盘缓存目录
    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    // dp转px工具
    private val density = context.resources.displayMetrics.density
    private val thumbnailWidthPx = (THUMBNAIL_WIDTH * density).toInt()
    private val thumbnailHeightPx = (THUMBNAIL_HEIGHT * density).toInt()

    /**
     * 获取缩略图（异步）
     * @param outputPath 输出图片路径
     * @return 缩略图Bitmap，失败返回null
     */
    suspend fun getThumbnail(outputPath: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (outputPath.isBlank()) {
                Log.w(TAG, "输出路径为空，跳过缩略图生成")
                return@withContext null
            }

            val cacheKey = generateCacheKey(outputPath)
            Log.d(TAG, "尝试获取缩略图: $outputPath, 缓存key: $cacheKey")

            // 1. 先从内存缓存获取
            memoryCache.get(cacheKey)?.let {
                Log.d(TAG, "从内存缓存获取缩略图: $outputPath")
                return@withContext it
            }

            // 2. 从磁盘缓存获取
            loadFromDiskCache(cacheKey)?.let { bitmap ->
                memoryCache.put(cacheKey, bitmap)
                Log.d(TAG, "从磁盘缓存获取缩略图: $outputPath")
                return@withContext bitmap
            }

            // 3. 生成新的缩略图
            Log.d(TAG, "生成新缩略图: $outputPath")
            generateThumbnail(outputPath)?.let { bitmap ->
                // 保存到缓存
                memoryCache.put(cacheKey, bitmap)
                saveToDiskCache(cacheKey, bitmap)
                return@withContext bitmap
            }

            Log.w(TAG, "生成缩略图失败: $outputPath")
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取缩略图失败: $outputPath", e)
            null
        }
    }

    /**
     * 生成缩略图
     * 使用充满方式（最短边等比例中心缩放到图形边缘）
     */
    private fun generateThumbnail(imagePath: String): Bitmap? {
        return try {
            val originalBitmap = loadOriginalImage(imagePath) ?: return null

            // 计算缩放比例和裁剪区域
            val sourceWidth = originalBitmap.width.toFloat()
            val sourceHeight = originalBitmap.height.toFloat()
            val targetWidth = thumbnailWidthPx.toFloat()
            val targetHeight = thumbnailHeightPx.toFloat()

            // 计算充满缩放比例（最短边适配）
            val scaleX = targetWidth / sourceWidth
            val scaleY = targetHeight / sourceHeight
            val scale = max(scaleX, scaleY) // 使用较大的缩放比例确保充满

            // 计算缩放后的尺寸
            val scaledWidth = sourceWidth * scale
            val scaledHeight = sourceHeight * scale

            // 计算居中裁剪的起始位置
            val startX = (scaledWidth - targetWidth) / 2
            val startY = (scaledHeight - targetHeight) / 2

            // 创建目标bitmap
            val targetBitmap =
                Bitmap.createBitmap(thumbnailWidthPx, thumbnailHeightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(targetBitmap)

            // 绘制背景色（可选）
            canvas.drawColor(0xFFF5F5F5.toInt())

            // 创建变换矩阵
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(-startX, -startY)
            }

            // 绘制图片
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(originalBitmap, matrix, paint)

            // 添加圆角效果
            val roundedBitmap = applyRoundCorners(targetBitmap, THUMBNAIL_CORNER_RADIUS * density)

            // 回收原始bitmap
            if (!originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }
            if (!targetBitmap.isRecycled && targetBitmap != roundedBitmap) {
                targetBitmap.recycle()
            }

            Log.d(TAG, "缩略图生成成功: ${roundedBitmap.width}x${roundedBitmap.height}")
            roundedBitmap

        } catch (e: Exception) {
            Log.e(TAG, "生成缩略图失败: $imagePath", e)
            null
        }
    }

    /**
     * 加载原始图片（支持大图片的高效加载）
     */
    private fun loadOriginalImage(imagePath: String): Bitmap? {
        return try {
            Log.d(TAG, "尝试加载原始图片: $imagePath")

            when {
                imagePath.startsWith("content://") -> {
                    // ContentUri方式加载
                    val uri = imagePath.toUri()

                    // 检查URI访问权限
                    try {
                        context.contentResolver.openInputStream(uri)?.use { testStream ->
                            // 尝试读取一个字节来验证权限
                            testStream.read()
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "没有访问 URI 的权限: $imagePath", e)
                        return null
                    } catch (e: java.io.FileNotFoundException) {
                        Log.e(TAG, "URI 指向的文件不存在: $imagePath", e)
                        return null
                    }

                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        // 首先获取图片尺寸信息
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeStream(inputStream, null, options)

                        if (options.outWidth <= 0 || options.outHeight <= 0) {
                            Log.e(
                                TAG,
                                "无效的图片尺寸: ${options.outWidth}x${options.outHeight}, 路径: $imagePath"
                            )
                            return null
                        }

                        Log.d(TAG, "原始图片尺寸: ${options.outWidth}x${options.outHeight}")

                        // 计算合适的采样率
                        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight)
                        Log.d(TAG, "使用采样率: $sampleSize")

                        // 重新打开流进行实际解码
                        context.contentResolver.openInputStream(uri)?.use { decodingStream ->
                            val bitmap = BitmapFactory.decodeStream(
                                decodingStream,
                                null,
                                BitmapFactory.Options().apply {
                                    inSampleSize = sampleSize
                                    inPreferredConfig = Bitmap.Config.RGB_565 // 减少内存占用
                                })

                            if (bitmap != null) {
                                Log.d(TAG, "加载成功，缩放后尺寸: ${bitmap.width}x${bitmap.height}")
                            } else {
                                Log.e(TAG, "BitmapFactory.decodeStream 返回 null")
                            }

                            bitmap
                        }
                    }
                }

                else -> {
                    // 文件路径方式加载
                    val file = File(imagePath)
                    if (!file.exists()) {
                        Log.e(TAG, "文件不存在: $imagePath")
                        return null
                    }

                    if (!file.canRead()) {
                        Log.e(TAG, "没有文件读取权限: $imagePath")
                        return null
                    }

                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(imagePath, options)

                    if (options.outWidth <= 0 || options.outHeight <= 0) {
                        Log.e(
                            TAG,
                            "无效的图片文件: ${options.outWidth}x${options.outHeight}, 路径: $imagePath"
                        )
                        return null
                    }

                    val sampleSize = calculateSampleSize(options.outWidth, options.outHeight)

                    val bitmap = BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    })

                    if (bitmap != null) {
                        Log.d(TAG, "文件加载成功: ${bitmap.width}x${bitmap.height}")
                    } else {
                        Log.e(TAG, "文件解码失败: $imagePath")
                    }

                    bitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载原始图片失败: $imagePath", e)
            null
        }
    }

    /**
     * 计算合适的采样率（避免加载过大的图片）
     */
    private fun calculateSampleSize(width: Int, height: Int): Int {
        val maxDimension = max(width, height)
        val targetMaxDimension = max(thumbnailWidthPx, thumbnailHeightPx) * 4 // 预留4倍缓冲

        var sampleSize = 1
        while (maxDimension / sampleSize > targetMaxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 应用圆角效果
     */
    private fun applyRoundCorners(bitmap: Bitmap, cornerRadius: Float): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            color = 0xFFFFFFFF.toInt()
        }

        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)

        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)

        return output
    }

    /**
     * 生成缓存键
     */
    private fun generateCacheKey(imagePath: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(imagePath.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            imagePath.replace(Regex("[^a-zA-Z0-9]"), "_")
        }
    }

    /**
     * 从磁盘缓存加载
     */
    private fun loadFromDiskCache(cacheKey: String): Bitmap? {
        return try {
            val cacheFile = File(cacheDir, "$cacheKey.jpg")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                if (bitmap != null && !bitmap.isRecycled) {
                    Log.d(
                        TAG,
                        "磁盘缓存加载成功: ${cacheFile.absolutePath}, 尺寸: ${bitmap.width}x${bitmap.height}"
                    )
                    bitmap
                } else {
                    Log.w(TAG, "磁盘缓存文件解码失败，删除损坏的缓存: ${cacheFile.absolutePath}")
                    cacheFile.delete() // 删除损坏的缓存文件
                    null
                }
            } else {
                if (cacheFile.exists()) {
                    Log.w(TAG, "磁盘缓存文件为空，删除: ${cacheFile.absolutePath}")
                    cacheFile.delete() // 删除空文件
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "从磁盘缓存加载失败: $cacheKey", e)
            // 尝试删除可能损坏的缓存文件
            try {
                val cacheFile = File(cacheDir, "$cacheKey.jpg")
                if (cacheFile.exists()) {
                    cacheFile.delete()
                    Log.d(TAG, "已删除损坏的缓存文件: ${cacheFile.absolutePath}")
                }
            } catch (deleteException: Exception) {
                Log.w(TAG, "删除损坏缓存文件失败", deleteException)
            }
            null
        }
    }

    /**
     * 保存到磁盘缓存
     */
    private fun saveToDiskCache(cacheKey: String, bitmap: Bitmap) {
        try {
            val cacheFile = File(cacheDir, "$cacheKey.jpg")
            FileOutputStream(cacheFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                fos.flush()
            }
            Log.d(TAG, "缩略图已保存到磁盘缓存: ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "保存到磁盘缓存失败: $cacheKey", e)
        }
    }

    /**
     * 清空所有缓存
     */
    fun clearAllCache() {
        try {
            // 清空内存缓存
            memoryCache.evictAll()

            // 清空磁盘缓存
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.delete()
                }
            }

            Log.d(TAG, "所有缓存已清空")
        } catch (e: Exception) {
            Log.e(TAG, "清空缓存失败", e)
        }
    }

    /**
     * 获取缓存大小信息
     */
    fun getCacheInfo(): CacheInfo {
        val memorySize = memoryCache.size()
        val diskSize = try {
            cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            0L
        }

        return CacheInfo(memorySize, diskSize, memoryCache.hitCount(), memoryCache.missCount())
    }

    /**
     * 缓存信息数据类
     */
    data class CacheInfo(
        val memorySizeBytes: Int,
        val diskSizeBytes: Long,
        val memoryHitCount: Int,
        val memoryMissCount: Int
    ) {
        val memoryHitRate: Float
            get() = if (memoryHitCount + memoryMissCount > 0) {
                memoryHitCount.toFloat() / (memoryHitCount + memoryMissCount)
            } else 0f
    }
}