package cn.alittlecookie.lut2photo.lut2photo.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
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

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_IS_PROCESSED_IMAGE = "extra_is_processed_image"
        const val EXTRA_DRAWABLE_RES_ID = "extra_drawable_res_id"
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
            Toast.makeText(this, "非全尺寸预览图，颗粒比输出略大", Toast.LENGTH_SHORT).show()
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
        try {
            when {
                // content:// URI
                imageUriString.startsWith("content://") -> {
                    val uri = imageUriString.toUri()
                    // 使用 ContentResolver 加载图片
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            binding.zoomImageView.setImageBitmap(bitmap)
                        } else {
                            Toast.makeText(this, "无法解码图片", Toast.LENGTH_SHORT).show()
                            finish()
                            return
                        }
                    } ?: run {
                        Toast.makeText(this, "无法打开图片文件", Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }
                }
                // file:// URI 或文件路径
                imageUriString.startsWith("file://") || imageUriString.startsWith("/") -> {
                    val uri = if (imageUriString.startsWith("/")) {
                        Uri.fromFile(File(imageUriString))
                    } else {
                        Uri.parse(imageUriString)
                    }
                    tempImageFile = File(uri.path ?: "")
                    binding.zoomImageView.setImageURI(uri)
                }
                // 其他情况尝试作为 URI 解析
                else -> {
                    val uri = Uri.parse(imageUriString)
                    binding.zoomImageView.setImageURI(uri)
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
                
                // 点击退出
                binding.zoomImageView.setOnClickListener {
                    finish()
                }
            } else {
                Toast.makeText(this, "无法加载图片资源", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
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
