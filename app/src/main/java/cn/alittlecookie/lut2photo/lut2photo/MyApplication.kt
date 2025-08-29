package cn.alittlecookie.lut2photo.lut2photo

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import cn.alittlecookie.lut2photo.lut2photo.core.MemoryManager
import cn.alittlecookie.lut2photo.lut2photo.core.NativeLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.EnhancedLutProcessor

/**
 * 自定义Application类
 * 手动初始化WorkManager以避免自动初始化时的依赖问题
 * 集成全局内存管理功能
 */
class MyApplication : Application() {

    companion object {
        private const val TAG = "MyApplication"

        @Volatile
        private var instance: MyApplication? = null
        
        @Volatile
        private var memoryManager: MemoryManager? = null

        @Volatile
        private var currentProcessor: Any? = null

        fun getInstance(): MyApplication? = instance
        
        fun getMemoryManager(): MemoryManager? = memoryManager

        fun getCurrentProcessor(): Any? = currentProcessor

        fun setCurrentProcessor(processor: Any?) {
            currentProcessor = processor
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 设置全局实例
        instance = this

        Log.i(TAG, "应用启动，开始初始化全局组件")

        // 手动初始化 WorkManager
        try {
            val config = Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()

            WorkManager.initialize(this, config)
            Log.i(TAG, "WorkManager初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager初始化失败", e)
        }

        // 初始化全局内存管理
        initializeGlobalMemoryManagement()
    }

    /**
     * 初始化全局内存管理
     */
    private fun initializeGlobalMemoryManagement() {
        try {
            Log.i(TAG, "开始初始化全局内存管理")

            // 获取设备内存信息
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val totalMemoryMB = memoryInfo.totalMem / (1024L * 1024L)
            val availableMemoryMB = memoryInfo.availMem / (1024L * 1024L)

            Log.i(TAG, "设备总内存: ${totalMemoryMB}MB, 可用内存: ${availableMemoryMB}MB")

            // 初始化内存管理器
            memoryManager = MemoryManager.getInstance(this)

            // 创建内存管理器配置
            // 设置内存限制为1GB-2GB范围，根据设备总内存动态调整
            val targetMemoryMB = when {
                totalMemoryMB >= 8192 -> 2048  // 8GB+设备使用2GB
                totalMemoryMB >= 6144 -> 1536  // 6GB+设备使用1.5GB
                totalMemoryMB >= 4096 -> 1280  // 4GB+设备使用1.28GB
                else -> 1024                   // 其他设备使用1GB
            }

            val config = MemoryManager.MemoryConfig(
                maxHeapSizeMB = targetMemoryMB,
                warningThreshold = 0.75f, // 75%时警告
                criticalThreshold = 0.9f, // 90%时临界
                enableAutoGC = true,
                enableMemoryCompression = true
            )

            // 设置内存配置并开始监控
            memoryManager?.setMemoryConfig(config)
            memoryManager?.startMonitoring()

            // 初始化Native全局组件
            initializeNativeGlobalComponents(availableMemoryMB, targetMemoryMB)

            Log.i(TAG, "全局内存管理初始化完成")

        } catch (e: Exception) {
            Log.e(TAG, "全局内存管理初始化失败", e)
        }
    }

    /**
     * 初始化Native全局组件
     */
    private fun initializeNativeGlobalComponents(availableMemoryMB: Long, targetMemoryMB: Int) {
        try {
            Log.i(TAG, "开始初始化Native全局组件")

            // 设置Native内存限制与Java堆内存保持一致，确保在1GB-2GB范围内
            val nativeMemoryLimitMB = targetMemoryMB

            // 调用Native全局初始化
            val result = NativeLutProcessor().nativeInitializeGlobalComponents(nativeMemoryLimitMB)

            if (result == 0) {
                Log.i(TAG, "Native全局组件初始化成功，内存限制: ${nativeMemoryLimitMB}MB")
            } else {
                Log.w(TAG, "Native全局组件初始化返回错误码: $result")
            }

        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native库未加载，跳过Native全局组件初始化", e)
        } catch (e: Exception) {
            Log.e(TAG, "Native全局组件初始化失败", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()

        Log.i(TAG, "应用终止，清理全局资源")

        // 停止内存监控
        memoryManager?.stopMonitoring()

        // 清理Native全局组件
        try {
            NativeLutProcessor().nativeCleanupGlobalComponents()
            Log.i(TAG, "Native全局组件清理完成")
        } catch (e: Exception) {
            Log.e(TAG, "Native全局组件清理失败", e)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()

        Log.w(TAG, "系统内存不足，触发内存优化")

        // 触发内存管理器的内存优化
        memoryManager?.optimizeMemory()

        // 触发Native内存优化（使用全局处理器实例）
        try {
            val processor = currentProcessor
            when (processor) {
                is cn.alittlecookie.lut2photo.lut2photo.core.NativeLutProcessor -> {
                    processor.optimizeMemory()
                    Log.d(TAG, "全局Native内存优化完成")
                }

                is cn.alittlecookie.lut2photo.lut2photo.core.EnhancedLutProcessor -> {
                    processor.optimizeMemory()
                    Log.d(TAG, "全局Enhanced内存优化完成")
                }

                else -> {
                    Log.w(TAG, "未找到可用的处理器实例，跳过Native内存优化")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native内存优化失败", e)
        }
    }
}