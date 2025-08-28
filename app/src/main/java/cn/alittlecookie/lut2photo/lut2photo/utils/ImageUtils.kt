package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlin.math.min

/**
 * 图片工具类
 * 提供图片加载、缩放、旋转等功能
 */
object ImageUtils {
    private const val TAG = "ImageUtils"

    /**
     * 从URI加载位图并缩放到指定尺寸
     * @param context 上下文
     * @param uri 图片URI
     * @param maxWidth 最大宽度
     * @param maxHeight 最大高度
     * @return 缩放后的位图，失败返回null
     */
    fun loadBitmapFromUri(
        context: Context,
        uri: Uri,
        maxWidth: Int,
        maxHeight: Int
    ): Bitmap? {
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

            if (originalWidth <= 0 || originalHeight <= 0) {
                Log.e(TAG, "无效的图片尺寸: ${originalWidth}x${originalHeight}")
                return null
            }

            // 计算缩放比例
            val sampleSize = calculateSampleSize(originalWidth, originalHeight, maxWidth, maxHeight)

            // 加载缩放后的图片
            val decodingOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inJustDecodeBounds = false
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodingOptions)
            }

            if (bitmap == null) {
                Log.e(TAG, "解码图片失败")
                return null
            }

            // 处理EXIF旋转
            val rotatedBitmap = handleExifRotation(context, uri, bitmap)

            // 如果需要进一步缩放到精确尺寸
            val finalBitmap =
                if (rotatedBitmap.width > maxWidth || rotatedBitmap.height > maxHeight) {
                    scaleBitmapToFit(rotatedBitmap, maxWidth, maxHeight)
                } else {
                    rotatedBitmap
                }

            Log.d(TAG, "成功加载图片: ${finalBitmap.width}x${finalBitmap.height}")
            finalBitmap

        } catch (e: Exception) {
            Log.e(TAG, "加载图片失败", e)
            null
        }
    }

    /**
     * 计算采样率
     */
    private fun calculateSampleSize(
        originalWidth: Int,
        originalHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sampleSize = 1

        if (originalHeight > targetHeight || originalWidth > targetWidth) {
            val halfHeight = originalHeight / 2
            val halfWidth = originalWidth / 2

            while ((halfHeight / sampleSize) >= targetHeight &&
                (halfWidth / sampleSize) >= targetWidth
            ) {
                sampleSize *= 2
            }
        }

        return sampleSize
    }

    /**
     * 处理EXIF旋转信息
     */
    private fun handleExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val exif = context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream)
            }

            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipBitmap(bitmap, horizontal = true)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> flipBitmap(bitmap, horizontal = false)
                else -> bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理EXIF旋转失败，使用原图", e)
            bitmap
        }
    }

    /**
     * 旋转位图
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        return try {
            val matrix = android.graphics.Matrix().apply {
                postRotate(degrees)
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }

            rotatedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "旋转位图失败", e)
            bitmap
        }
    }

    /**
     * 翻转位图
     */
    private fun flipBitmap(bitmap: Bitmap, horizontal: Boolean): Bitmap {
        return try {
            val matrix = android.graphics.Matrix().apply {
                if (horizontal) {
                    preScale(-1f, 1f)
                } else {
                    preScale(1f, -1f)
                }
            }

            val flippedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            if (flippedBitmap != bitmap) {
                bitmap.recycle()
            }

            flippedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "翻转位图失败", e)
            bitmap
        }
    }

    /**
     * 缩放位图以适应指定尺寸（保持宽高比）
     */
    private fun scaleBitmapToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        val scaleX = maxWidth.toFloat() / originalWidth
        val scaleY = maxHeight.toFloat() / originalHeight
        val scale = min(scaleX, scaleY)

        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()

        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

            if (scaledBitmap != bitmap) {
                bitmap.recycle()
            }

            scaledBitmap
        } catch (e: Exception) {
            Log.e(TAG, "缩放位图失败", e)
            bitmap
        }
    }

    /**
     * 获取图片文件大小（字节）
     */
    fun getImageSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.available().toLong()
            } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "获取图片大小失败", e)
            0L
        }
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> "${bytes / (1024 * 1024 * 1024)}GB"
        }
    }
}