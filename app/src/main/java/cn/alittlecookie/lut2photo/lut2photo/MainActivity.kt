package cn.alittlecookie.lut2photo.lut2photo

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import cn.alittlecookie.lut2photo.lut2photo.databinding.ActivityMainBinding
import cn.alittlecookie.lut2photo.lut2photo.utils.ThemeManager
import cn.alittlecookie.lut2photo.lut2photo.core.NativeLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.EnhancedLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.service.MemoryMonitorService
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var themeManager: ThemeManager
    private var nativeProcessor: NativeLutProcessor? = null
    private var enhancedProcessor: EnhancedLutProcessor? = null

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

        // 使用全局内存管理器
        initializeActivityMemoryManagement()

        // 启动内存监控服务
        startMemoryMonitoringService()

        Log.i("MainActivity", "Activity内存管理初始化完成")

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

    override fun onDestroy() {
        super.onDestroy()

        // 停止内存监控服务
        stopMemoryMonitoringService()

        // 释放Native处理器资源
        nativeProcessor?.let {
            try {
                lifecycleScope.launch {
                    it.release()
                    Log.d("MainActivity", "Native处理器资源已释放")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "释放Native处理器资源失败", e)
            }
        }
    }

    /**
     * 初始化Activity级别的内存管理
     */
    private fun initializeActivityMemoryManagement() {
        try {
            Log.i("MainActivity", "开始初始化Activity内存管理")

            // 获取全局内存管理器
            val globalMemoryManager = MyApplication.getMemoryManager()
            if (globalMemoryManager != null) {
                Log.i("MainActivity", "全局内存管理器可用")

                // 设置内存监听器
                globalMemoryManager.addMemoryListener(object :
                    cn.alittlecookie.lut2photo.lut2photo.core.MemoryManager.MemoryListener {
                    override fun onMemoryWarning(status: cn.alittlecookie.lut2photo.lut2photo.core.MemoryManager.MemoryStatus) {
                        Log.w(
                            "MainActivity",
                            "内存警告: 当前使用 ${status.usedHeapMB}MB / ${status.maxHeapMB}MB (${(status.usageRatio * 100).toInt()}%)"
                        )
                        // 可以在这里触发UI更新或其他响应
                    }

                    override fun onMemoryCritical(status: cn.alittlecookie.lut2photo.lut2photo.core.MemoryManager.MemoryStatus) {
                        Log.e(
                            "MainActivity",
                            "内存临界: 当前使用 ${status.usedHeapMB}MB / ${status.maxHeapMB}MB (${(status.usageRatio * 100).toInt()}%)"
                        )
                        // 触发紧急内存清理
                        runOnUiThread {
                            // 可以显示内存不足的提示
                        }
                    }

                    override fun onMemoryNormal(status: cn.alittlecookie.lut2photo.lut2photo.core.MemoryManager.MemoryStatus) {
                        Log.i(
                            "MainActivity",
                            "内存正常: 当前使用 ${status.usedHeapMB}MB / ${status.maxHeapMB}MB (${(status.usageRatio * 100).toInt()}%)"
                        )
                    }

                    override fun onLowMemory() {
                        Log.w("MainActivity", "系统内存不足，触发内存清理")
                        // 触发内存清理
                    }
                })

                // 创建增强版处理器实例（如果需要）
                if (enhancedProcessor == null && nativeProcessor == null) {
                    try {
                        enhancedProcessor = EnhancedLutProcessor()

                        // 检查增强版处理器是否可用
                        lifecycleScope.launch {
                            if (!enhancedProcessor!!.isAvailable()) {
                                Log.e("MainActivity", "增强版处理器初始化失败，降级到Native处理器")
                                enhancedProcessor = null
                                nativeProcessor = NativeLutProcessor()
                            }
                        }
                        Log.i("MainActivity", "处理器实例创建成功")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "处理器创建失败", e)
                    }
                }

            } else {
                Log.w("MainActivity", "全局内存管理器不可用，使用本地内存管理")
                // 降级到本地内存管理
                initializeFallbackMemoryManagement()
            }

            Log.i("MainActivity", "Activity内存管理初始化完成")

        } catch (e: Exception) {
            Log.e("MainActivity", "Activity内存管理初始化失败", e)
            // 降级到本地内存管理
            initializeFallbackMemoryManagement()
        }
    }

    /**
     * 降级内存管理（当全局内存管理器不可用时）
     */
    private fun initializeFallbackMemoryManagement() {
        try {
            Log.i("MainActivity", "开始降级内存管理初始化")

            // 获取设备可用内存
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            // 创建增强版处理器实例
            if (nativeProcessor == null && enhancedProcessor == null) {
                enhancedProcessor = EnhancedLutProcessor()

                // 在协程中检查增强版处理器是否可用
                lifecycleScope.launch {
                    if (!enhancedProcessor!!.isAvailable()) {
                        Log.e("MainActivity", "增强版处理器不可用，使用基础Native处理器")
                        enhancedProcessor = null
                        nativeProcessor = NativeLutProcessor()
                    }
                    Log.i("MainActivity", "处理器实例创建成功")
                }
            }

            // 设置Native内存限制为可用内存的20%（更保守）
            val nativeMemoryLimitMB = ((memoryInfo.availMem * 0.2) / (1024L * 1024L)).toInt()
            Log.i("MainActivity", "设置保守内存限制: ${nativeMemoryLimitMB}MB")
            nativeProcessor?.setMemoryLimit(nativeMemoryLimitMB)

            Log.i("MainActivity", "降级内存管理初始化完成")

        } catch (e: Exception) {
            Log.e("MainActivity", "降级内存管理初始化失败", e)
            nativeProcessor = null
        }
    }

    /**
     * 启动内存监控服务
     */
    private fun startMemoryMonitoringService() {
        try {
            Log.i("MainActivity", "启动内存监控服务")
            MemoryMonitorService.startService(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "启动内存监控服务失败", e)
        }
    }

    /**
     * 停止内存监控服务
     */
    private fun stopMemoryMonitoringService() {
        try {
            Log.i("MainActivity", "停止内存监控服务")
            MemoryMonitorService.stopService(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "停止内存监控服务失败", e)
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