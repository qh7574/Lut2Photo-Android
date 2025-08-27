package cn.alittlecookie.lut2photo.lut2photo.utils

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 内存优化工具类
 * 提供智能的图片加载和内存管理策略
 */
class MemoryOptimizer(private val context: Context) {

    companion object {
        private const val TAG = "MemoryOptimizer"
        private const val MEMORY_SAFETY_FACTOR = 0.7f // 使用70%的可用内存作为安全阈值
        private const val MIN_TILE_SIZE = 512 // 最小瓦片尺寸
        private const val MAX_TILE_SIZE = 2048 // 最大瓦片尺寸
        private const val BYTES_PER_PIXEL = 4 // ARGB_8888格式每像素4字节
    }

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /**
     * 获取可用内存信息
     */
    data class MemoryInfo(
        val totalMemory: Long,
        val availableMemory: Long,
        val usedMemory: Long,
        val memoryClass: Int,
        val largeMemoryClass: Int
    )

    /**
     * 获取当前内存状态
     */
    fun getMemoryInfo(): MemoryInfo {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedMemory = totalMemory - freeMemory
        val availableMemory = maxMemory - usedMemory

        return MemoryInfo(
            totalMemory = totalMemory,
            availableMemory = availableMemory,
            usedMemory = usedMemory,
            memoryClass = activityManager.memoryClass,
            largeMemoryClass = activityManager.largeMemoryClass
        )
    }

    /**
     * 检查是否有足够内存加载指定尺寸的图片
     */
    fun canLoadImage(width: Int, height: Int): Boolean {
        val memInfo = getMemoryInfo()
        val requiredMemory = width.toLong() * height.toLong() * BYTES_PER_PIXEL
        val safeAvailableMemory = (memInfo.availableMemory * MEMORY_SAFETY_FACTOR).toLong()

        Log.d(
            TAG,
            "内存检查: 需要${requiredMemory / 1024 / 1024}MB, 可用${safeAvailableMemory / 1024 / 1024}MB"
        )
        return requiredMemory <= safeAvailableMemory
    }

    /**
     * 计算最优的采样率
     */
    fun calculateOptimalSampleSize(
        originalWidth: Int,
        originalHeight: Int,
        targetMaxPixels: Long = 16_000_000
    ): Int {
        val originalPixels = originalWidth.toLong() * originalHeight.toLong()

        if (originalPixels <= targetMaxPixels) {
            return 1
        }

        val ratio = originalPixels.toDouble() / targetMaxPixels.toDouble()
        val sampleSize = sqrt(ratio).toInt()

        // 确保采样率是2的幂次方，这样效率更高
        var powerOfTwo = 1
        while (powerOfTwo < sampleSize) {
            powerOfTwo *= 2
        }

        Log.d(TAG, "计算采样率: 原始${originalWidth}x${originalHeight}, 采样率$powerOfTwo")
        return powerOfTwo
    }

    /**
     * 智能加载图片，根据内存情况自动选择策略
     */
    fun loadImageSmart(uri: Uri): Bitmap? {
        return try {
            // 首先获取图片尺寸
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            Log.d(TAG, "图片原始尺寸: ${originalWidth}x${originalHeight}")

            // 检查是否可以直接加载
            if (canLoadImage(originalWidth, originalHeight)) {
                Log.d(TAG, "内存充足，直接加载原图")
                return loadImageDirect(uri)
            }

            // 计算采样率
            val sampleSize = calculateOptimalSampleSize(originalWidth, originalHeight)
            val sampledWidth = originalWidth / sampleSize
            val sampledHeight = originalHeight / sampleSize

            // 检查采样后是否可以加载
            if (canLoadImage(sampledWidth, sampledHeight)) {
                Log.d(TAG, "使用采样加载，采样率: $sampleSize")
                return loadImageWithSampling(uri, sampleSize)
            }

            // 如果采样后仍然太大，使用瓦片加载
            Log.d(TAG, "图片过大，使用瓦片加载")
            return loadImageWithTiling(uri, originalWidth, originalHeight)

        } catch (e: Exception) {
            Log.e(TAG, "智能加载图片失败", e)
            null
        }
    }

    /**
     * 直接加载图片
     */
    private fun loadImageDirect(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "直接加载OOM", e)
            null
        }
    }

    /**
     * 使用采样率加载图片
     */
    private fun loadImageWithSampling(uri: Uri, sampleSize: Int): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "采样加载OOM", e)
            null
        }
    }

    /**
     * 使用瓦片方式加载大图片
     */
    private fun loadImageWithTiling(uri: Uri, originalWidth: Int, originalHeight: Int): Bitmap? {
        return try {
            // 计算瓦片尺寸
            val tileSize = calculateOptimalTileSize(originalWidth, originalHeight)

            Log.d(TAG, "瓦片加载: 原图${originalWidth}x${originalHeight}, 瓦片尺寸${tileSize}")

            // 创建结果bitmap
            val resultBitmap =
                Bitmap.createBitmap(originalWidth, originalHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(resultBitmap)

            // 使用BitmapRegionDecoder分块加载
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val decoder = BitmapRegionDecoder.newInstance(stream, false)

                decoder?.let { regionDecoder ->
                    val tilesX = (originalWidth + tileSize - 1) / tileSize
                    val tilesY = (originalHeight + tileSize - 1) / tileSize

                    for (y in 0 until tilesY) {
                        for (x in 0 until tilesX) {
                            val left = x * tileSize
                            val top = y * tileSize
                            val right = min(left + tileSize, originalWidth)
                            val bottom = min(top + tileSize, originalHeight)

                            val rect = Rect(left, top, right, bottom)
                            val tileBitmap =
                                regionDecoder.decodeRegion(rect, BitmapFactory.Options().apply {
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                })

                            if (tileBitmap != null) {
                                canvas.drawBitmap(tileBitmap, left.toFloat(), top.toFloat(), null)
                                tileBitmap.recycle()
                            }

                            // 强制垃圾回收，释放瓦片内存
                            if ((x * tilesY + y) % 4 == 0) {
                                System.gc()
                            }
                        }
                    }

                    regionDecoder.recycle()
                }
            }

            resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "瓦片加载失败", e)
            null
        }
    }

    /**
     * 计算最优瓦片尺寸
     */
    private fun calculateOptimalTileSize(width: Int, height: Int): Int {
        val memInfo = getMemoryInfo()
        val safeMemory = (memInfo.availableMemory * MEMORY_SAFETY_FACTOR).toLong()

        // 计算单个瓦片可以使用的最大像素数
        val maxTilePixels = safeMemory / BYTES_PER_PIXEL / 4 // 预留4倍缓冲
        val maxTileSize = sqrt(maxTilePixels.toDouble()).toInt()

        // 限制在合理范围内
        val tileSize = maxTileSize.coerceIn(MIN_TILE_SIZE, MAX_TILE_SIZE)

        Log.d(TAG, "计算瓦片尺寸: 可用内存${safeMemory / 1024 / 1024}MB, 瓦片尺寸${tileSize}")
        return tileSize
    }

    /**
     * 优化的图片加载方法，包含EXIF处理
     */
    fun loadOptimizedBitmap(uri: Uri): Bitmap? {
        return try {
            // 首先读取EXIF信息
            val exif = context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream)
            }
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            // 使用智能加载策略
            val bitmap = loadImageSmart(uri)

            // 应用EXIF方向
            if (bitmap != null && orientation != ExifInterface.ORIENTATION_NORMAL) {
                applyExifOrientation(bitmap, orientation)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "优化加载图片失败", e)
            null
        }
    }

    /**
     * 应用EXIF方向信息
     */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap // ORIENTATION_NORMAL 或未知方向
        }

        return try {
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            rotatedBitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "内存不足，无法旋转图像", e)
            bitmap
        }
    }

    /**
     * 强制垃圾回收并记录内存状态
     */
    fun forceGarbageCollection() {
        val beforeMemInfo = getMemoryInfo()
        System.gc()
        Thread.sleep(100) // 给GC一些时间
        val afterMemInfo = getMemoryInfo()

        val freedMemory = afterMemInfo.availableMemory - beforeMemInfo.availableMemory
        Log.d(TAG, "垃圾回收: 释放${freedMemory / 1024 / 1024}MB内存")
    }

    /**
     * 检查内存压力状态
     */
    fun isMemoryPressureHigh(): Boolean {
        val memInfo = getMemoryInfo()
        val usageRatio = memInfo.usedMemory.toDouble() / (memInfo.totalMemory.toDouble())
        return usageRatio > 0.8 // 使用超过80%认为是高压力
    }
}