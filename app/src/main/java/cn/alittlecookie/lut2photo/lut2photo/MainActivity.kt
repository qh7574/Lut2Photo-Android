package cn.alittlecookie.lut2photo.lut2photo

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import cn.alittlecookie.lut2photo.lut2photo.databinding.ActivityMainBinding
import cn.alittlecookie.lut2photo.lut2photo.utils.ThemeManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        // 在super.onCreate之前应用主题
        themeManager = ThemeManager(this)
        themeManager.applyTheme()
        
        // 应用动态颜色（如果支持）
        if (themeManager.getCurrentTheme() == ThemeManager.THEME_DYNAMIC) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置系统栏样式
        setupSystemBars()

        val navView: BottomNavigationView = binding.navView

        // 延迟初始化NavController以避免IllegalStateException
        binding.root.post {
            try {
                val navController = findNavController(R.id.nav_host_fragment_activity_main)
                AppBarConfiguration(
                    setOf(
                        R.id.navigation_home, 
                        R.id.navigation_dashboard, 
                        R.id.navigation_processing_history,
                        R.id.navigation_lut_manager,
                        R.id.navigation_notifications
                    )
                )
                navView.setupWithNavController(navController)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 延迟设置，确保在所有主题应用完成后执行
        window.decorView.post {
            try {
                // 获取底部导航栏的实际背景色
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
                val windowBackgroundColor =
                    if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                        typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT
                    ) {
                        typedValue.data
                    } else {
                        // 备用方案：使用colorSurface
                        val surfaceTypedValue = android.util.TypedValue()
                        theme.resolveAttribute(
                            com.google.android.material.R.attr.colorSurface,
                            surfaceTypedValue,
                            true
                        )
                        surfaceTypedValue.data
                    }

                // 强制设置导航栏颜色
                window.navigationBarColor = windowBackgroundColor
                window.statusBarColor = ContextCompat.getColor(this, android.R.color.transparent)

                // 根据主题设置导航栏图标颜色
                val isLightTheme = when (themeManager.getCurrentTheme()) {
                    ThemeManager.THEME_LIGHT -> true
                    ThemeManager.THEME_DARK -> false
                    else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
                }

                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightNavigationBars = isLightTheme
                    isAppearanceLightStatusBars = isLightTheme
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 每次应用恢复时检查权限
        checkAndRequestPermissions()
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        }
    }
    
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
}