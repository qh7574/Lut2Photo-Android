package cn.alittlecookie.lut2photo.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 区域解码器管理类
 * 用于处理大图片的分块加载，避免OOM问题
 */
class RegionDecoderManager {
    companion object {
        private const val TAG = "RegionDecoderManager"

        // 默认块大小（像素）
        private const val DEFAULT_BLOCK_SIZE = 1024

        // 最大块大小，防止单个块过大导致OOM
        private const val MAX_BLOCK_SIZE = 2048

        // 内存安全阈值（字节）
        private const val MEMORY_SAFE_THRESHOLD = 50 * 1024 * 1024 // 50MB
    }

    /**
     * 图片块信息
     */
    data class ImageBlock(
        val rect: Rect,
        val blockIndex: Int,
        val totalBlocks: Int
    )

    /**
     * 分块处理回调接口
     */
    interface BlockProcessingCallback {
        suspend fun onBlockLoaded(bitmap: Bitmap, block: ImageBlock): Bitmap?
        fun onProgress(processedBlocks: Int, totalBlocks: Int)
        fun onError(error: Exception)
    }

    /**
     * 检查图片是否需要分块处理
     */
    suspend fun shouldUseRegionDecoding(context: Context, uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                val width = options.outWidth
                val height = options.outHeight

                if (width <= 0 || height <= 0) {
                    return@withContext false
                }

                // 计算图片大小（ARGB_8888格式，每像素4字节）
                val estimatedSize = width.toLong() * height.toLong() * 4

                // 如果图片大小超过阈值，使用分块处理
                val needRegionDecoding = estimatedSize > MEMORY_SAFE_THRESHOLD

                Log.d(
                    TAG,
                    "图片尺寸: ${width}x${height}, 估计大小: ${estimatedSize / 1024 / 1024}MB, 需要分块处理: $needRegionDecoding"
                )

                needRegionDecoding
            } catch (e: Exception) {
                Log.e(TAG, "检查图片是否需要分块处理失败", e)
                false
            }
        }
    }

    /**
     * 加载完整图片（小图片直接加载）
     */
    suspend fun loadFullImage(context: Context, uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "加载完整图片失败", e)
                null
            }
        }
    }

    /**
     * 分块处理大图片
     */
    suspend fun processImageInRegions(
        context: Context,
        uri: Uri,
        blockSize: Int = DEFAULT_BLOCK_SIZE,
        callback: BlockProcessingCallback
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            var regionDecoder: BitmapRegionDecoder? = null
            var resultBitmap: Bitmap? = null

            try {
                // 创建区域解码器
                val inputStream = context.contentResolver.openInputStream(uri)
                regionDecoder = inputStream?.let { BitmapRegionDecoder.newInstance(it, false) }
                inputStream?.close()

                if (regionDecoder == null) {
                    Log.e(TAG, "无法创建BitmapRegionDecoder")
                    return@withContext null
                }

                val imageWidth = regionDecoder.width
                val imageHeight = regionDecoder.height

                Log.d(
                    TAG,
                    "开始分块处理图片，尺寸: ${imageWidth}x${imageHeight}, 块大小: $blockSize"
                )

                // 计算块的数量
                val blocksX = (imageWidth + blockSize - 1) / blockSize
                val blocksY = (imageHeight + blockSize - 1) / blockSize
                val totalBlocks = blocksX * blocksY

                Log.d(TAG, "总共需要处理 $totalBlocks 个块 (${blocksX}x${blocksY})")

                // 创建结果位图
                resultBitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(resultBitmap)

                var processedBlocks = 0

                // 逐块处理
                for (y in 0 until blocksY) {
                    for (x in 0 until blocksX) {
                        val left = x * blockSize
                        val top = y * blockSize
                        val right = min(left + blockSize, imageWidth)
                        val bottom = min(top + blockSize, imageHeight)

                        val rect = Rect(left, top, right, bottom)
                        val block = ImageBlock(rect, processedBlocks, totalBlocks)

                        // 解码当前块
                        val blockBitmap = regionDecoder.decodeRegion(rect, null)

                        if (blockBitmap != null) {
                            // 处理当前块
                            val processedBlock = callback.onBlockLoaded(blockBitmap, block)

                            // 将处理后的块绘制到结果位图上
                            val targetBitmap = processedBlock ?: blockBitmap
                            canvas.drawBitmap(targetBitmap, left.toFloat(), top.toFloat(), null)

                            // 回收临时位图
                            if (processedBlock != null && processedBlock != blockBitmap) {
                                blockBitmap.recycle()
                            }
                            if (processedBlock != null && processedBlock != targetBitmap) {
                                processedBlock.recycle()
                            }
                        }

                        processedBlocks++
                        callback.onProgress(processedBlocks, totalBlocks)

                        // 强制垃圾回收，释放内存
                        if (processedBlocks % 10 == 0) {
                            System.gc()
                        }
                    }
                }

                Log.d(TAG, "分块处理完成，共处理 $processedBlocks 个块")
                resultBitmap

            } catch (e: Exception) {
                Log.e(TAG, "分块处理图片失败", e)
                callback.onError(e)
                resultBitmap?.recycle()
                null
            } finally {
                regionDecoder?.recycle()
            }
        }
    }

    /**
     * 计算最优块大小
     */
    fun calculateOptimalBlockSize(imageWidth: Int, imageHeight: Int): Int {
        val totalPixels = imageWidth.toLong() * imageHeight.toLong()

        return when {
            totalPixels > 100_000_000 -> 512  // 超大图片使用小块
            totalPixels > 50_000_000 -> 768   // 大图片使用中等块
            totalPixels > 25_000_000 -> 1024  // 中等图片使用标准块
            else -> DEFAULT_BLOCK_SIZE
        }.coerceAtMost(MAX_BLOCK_SIZE)
    }

    /**
     * 获取图片基本信息
     */
    suspend fun getImageInfo(context: Context, uri: Uri): ImageInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                if (options.outWidth > 0 && options.outHeight > 0) {
                    ImageInfo(
                        width = options.outWidth,
                        height = options.outHeight,
                        mimeType = options.outMimeType ?: "unknown"
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取图片信息失败", e)
                null
            }
        }
    }

    /**
     * 图片信息数据类
     */
    data class ImageInfo(
        val width: Int,
        val height: Int,
        val mimeType: String
    ) {
        val totalPixels: Long get() = width.toLong() * height.toLong()
        val estimatedSizeBytes: Long get() = totalPixels * 4 // ARGB_8888
        val estimatedSizeMB: Float get() = estimatedSizeBytes / 1024f / 1024f
    }
}