package cn.alittlecookie.lut2photo.lut2photo.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.databinding.ActivityFullscreenImageBinding
import java.io.File

/**
 * 全屏图片查看 Activity
 * 使用 ZoomImage 库实现图片的缩放、拖动等手势操作
 */
class FullscreenImageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFullscreenImageBinding
    private var tempImageFile: File? = null

    data class DecodeResult(
        val bitmap: Bitmap?,
        val downsampled: Boolean
    )

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_IS_PROCESSED_IMAGE = "extra_is_processed_image"
        const val EXTRA_DRAWABLE_RES_ID = "extra_drawable_res_id"

        private const val MAX_ESTIMATED_BYTES: Long = 150L * 1024L * 1024L
        private const val MAX_LONG_EDGE_PX = 6000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置全屏显示
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityFullscreenImageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 隐藏系统 UI
        hideSystemUI()

        // 是否是已处理的图片（来自处理历史）
        val isProcessedImage = intent.getBooleanExtra(EXTRA_IS_PROCESSED_IMAGE, false)

        // 只有非处理后的图片才显示预览提示
        if (!isProcessedImage) {
            Toast.makeText(this, "非全尺寸预览图，效果仅供参考", Toast.LENGTH_SHORT).show()
        }

        // 检查是否从 Drawable 资源加载
        val drawableResId = intent.getIntExtra(EXTRA_DRAWABLE_RES_ID, 0)
        if (drawableResId != 0) {
            loadImageFromDrawable(drawableResId)
        } else {
            // 获取图片 URI
            val imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI)
            if (imageUriString != null) {
                loadImage(imageUriString, isProcessedImage)
            } else {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun loadImage(imageUriString: String, isProcessedImage: Boolean) {
        showLoadingIndicator()
        try {
            when {
                // content:// URI
                imageUriString.startsWith("content://") -> {
                    val uri = imageUriString.toUri()
                    loadImageAsync(uri)
                }
                // file:// URI 或文件路径
                imageUriString.startsWith("file://") || imageUriString.startsWith("/") -> {
                    val uri = if (imageUriString.startsWith("/")) {
                        Uri.fromFile(File(imageUriString))
                    } else {
                        Uri.parse(imageUriString)
                    }
                    tempImageFile = File(uri.path ?: "")
                    loadImageAsync(uri)
                }
                // 其他情况尝试作为 URI 解析
                else -> {
                    val uri = Uri.parse(imageUriString)
                    loadImageAsync(uri)
                }
            }

            // 点击退出
            binding.zoomImageView.setOnClickListener {
                finish()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadImageFromDrawable(drawableResId: Int) {
        try {
            val bitmap = BitmapFactory.decodeResource(resources, drawableResId)
            if (bitmap != null) {
                binding.zoomImageView.setImageBitmap(bitmap)
                hideLoadingIndicator()
                
                // 点击退出
                binding.zoomImageView.setOnClickListener {
                    finish()
                }
            } else {
                hideLoadingIndicator()
                Toast.makeText(this, "无法加载图片资源", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            hideLoadingIndicator()
            Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showLoadingIndicator() {
        binding.loadingIndicator.visibility = View.VISIBLE
    }

    private fun hideLoadingIndicator() {
        binding.loadingIndicator.visibility = View.GONE
    }

    private fun loadImageAsync(uri: Uri) {
        showLoadingIndicator()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                decodeBitmapWithGuard(uri)
            }
            val bitmap = result.bitmap
            if (bitmap != null) {
                if (result.downsampled) {
                    Toast.makeText(this@FullscreenImageActivity, "图片过大，已降采样显示", Toast.LENGTH_SHORT).show()
                }
                binding.zoomImageView.setImageBitmap(bitmap)
                hideLoadingIndicator()
            } else {
                hideLoadingIndicator()
                Toast.makeText(this@FullscreenImageActivity, "无法解码图片", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun decodeBitmapWithGuard(uri: Uri): DecodeResult {
        val isFileUri = uri.scheme == null || uri.scheme == "file"
        return if (isFileUri) {
            val path = uri.path
            if (path.isNullOrBlank()) DecodeResult(null, false) else decodeBitmapFromFile(path)
        } else {
            decodeBitmapFromContent(uri)
        }
    }

    private fun decodeBitmapFromFile(path: String): DecodeResult {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(path, boundsOptions)

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) {
            Log.w("FullscreenImageActivity", "decodeFile bounds failed: path=$path, w=$width, h=$height")
            return DecodeResult(null, false)
        }

        val estimatedBytes = estimateBitmapBytes(width, height)
        val shouldDownsample = estimatedBytes > MAX_ESTIMATED_BYTES
        val sampleSize = if (shouldDownsample) {
            val rawSampleSize = calculateInSampleSize(width, height, MAX_LONG_EDGE_PX)
            maxOf(2, rawSampleSize)
        } else {
            1
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = BitmapFactory.decodeFile(path, decodeOptions)
        return DecodeResult(bitmap, shouldDownsample && bitmap != null)
    }

    private fun decodeBitmapFromContent(uri: Uri): DecodeResult {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        val usedFileDescriptor = contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, boundsOptions)
            true
        } ?: run {
            Log.w("FullscreenImageActivity", "openFileDescriptor failed for uri=$uri, fallback to InputStream")
            false
        }

        if (!usedFileDescriptor) {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, boundsOptions)
            } ?: run {
                Log.w("FullscreenImageActivity", "openInputStream failed for uri=$uri")
                return DecodeResult(null, false)
            }
        }

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) {
            Log.w("FullscreenImageActivity", "decode bounds failed: uri=$uri, w=$width, h=$height")
            return DecodeResult(null, false)
        }

        val estimatedBytes = estimateBitmapBytes(width, height)
        val shouldDownsample = estimatedBytes > MAX_ESTIMATED_BYTES
        val sampleSize = if (shouldDownsample) {
            val rawSampleSize = calculateInSampleSize(width, height, MAX_LONG_EDGE_PX)
            maxOf(2, rawSampleSize)
        } else {
            1
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val bitmap = if (usedFileDescriptor) {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, decodeOptions)
            }
        } else {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            }
        }

        return DecodeResult(bitmap, shouldDownsample && bitmap != null)
    }

    private fun estimateBitmapBytes(width: Int, height: Int): Long {
        return width.toLong() * height.toLong() * 4L
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxLongEdgePx: Int): Int {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdgePx) {
            return 1
        }
        val ratio = longEdge.toFloat() / maxLongEdgePx.toFloat()
        val rawSample = kotlin.math.ceil(ratio).toInt()
        return maxOf(1, rawSample)
    }
    
    /**
     * 隐藏系统状态栏和导航栏
     */
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // 兼容旧版本
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 删除临时文件
        tempImageFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
    }
}
