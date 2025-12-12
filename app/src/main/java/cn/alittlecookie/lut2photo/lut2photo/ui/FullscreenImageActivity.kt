package cn.alittlecookie.lut2photo.lut2photo.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置全屏显示
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityFullscreenImageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 隐藏系统 UI
        hideSystemUI()
        
        // 显示提示
        Toast.makeText(this, "非全尺寸预览图，仅供参考", Toast.LENGTH_SHORT).show()
        
        // 获取图片 URI
        val imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (imageUriString != null) {
            val imageUri = Uri.parse(imageUriString)
            tempImageFile = File(imageUri.path ?: "")
            
            // 加载图片到 ZoomImageView
            binding.zoomImageView.apply {
                setImageURI(imageUri)
                
                // 点击退出
                setOnClickListener {
                    finish()
                }
            }
        } else {
            Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show()
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
