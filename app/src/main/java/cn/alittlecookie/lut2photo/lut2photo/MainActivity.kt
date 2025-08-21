package cn.alittlecookie.lut2photo.lut2photo

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
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

    // 添加权限请求标志，避免重复请求
    private var isPermissionRequesting = false
    private val PERMISSION_REQUEST_CODE = 1001

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
                val windowBackgroundColor = typedValue.data


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

    // 添加权限检查标志，避免重复检查
    private var hasCheckedPermissions = false
    
    override fun onResume() {
        super.onResume()
        // 只在首次启动时检查权限，避免重复检查导致循环
        if (!isPermissionRequesting && !hasCheckedPermissions) {
            checkAndRequestPermissions()
            hasCheckedPermissions = true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            isPermissionRequesting = false

            // 检查权限授权结果
            val allGranted =
                grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Log.d("MainActivity", "所有权限已授权")
            } else {
                Log.w("MainActivity", "部分权限被拒绝")
                // 权限被拒绝时，不再重复请求
                hasCheckedPermissions = true
            }
        }
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
            isPermissionRequesting = true
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

}