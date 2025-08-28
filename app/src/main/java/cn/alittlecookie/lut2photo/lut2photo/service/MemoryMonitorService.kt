package cn.alittlecookie.lut2photo.lut2photo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.alittlecookie.lut2photo.lut2photo.MyApplication
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.core.ErrorHandler
import cn.alittlecookie.lut2photo.lut2photo.core.MemoryManager
import cn.alittlecookie.lut2photo.lut2photo.core.EnhancedLutProcessor
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内存监控服务
 * 提供实时内存监控、预警通知和自动优化功能
 */
class MemoryMonitorService : Service() {

    companion object {
        private const val TAG = "MemoryMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "memory_monitor_channel"
        private const val MONITORING_INTERVAL_MS = 5000L // 5秒监控间隔
        private const val WARNING_NOTIFICATION_INTERVAL_MS = 30000L // 30秒警告间隔

        fun startService(context: Context) {
            val intent = Intent(context, MemoryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MemoryMonitorService::class.java)
            context.stopService(intent)
        }
    }

    private var monitoringJob: Job? = null
    private val isMonitoring = AtomicBoolean(false)
    private var lastWarningTime = 0L
    private var errorHandler: ErrorHandler? = null
    private var memoryManager: MemoryManager? = null

    // 监控统计
    data class MonitoringStats(
        val totalChecks: Long,
        val warningCount: Long,
        val criticalCount: Long,
        val optimizationCount: Long,
        val averageMemoryUsage: Float,
        val peakMemoryUsage: Float
    )

    private var stats = MonitoringStats(0, 0, 0, 0, 0f, 0f)
    private val memoryUsageHistory = mutableListOf<Float>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "内存监控服务创建")

        // 创建通知渠道
        createNotificationChannel()

        // 初始化组件
        errorHandler = ErrorHandler(this)
        memoryManager = MyApplication.getMemoryManager()

        // 设置内存监听器
        setupMemoryListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "内存监控服务启动")

        // 启动前台服务
        startForeground(
            NOTIFICATION_ID,
            createNotification("内存监控已启动", "正在监控应用内存使用情况")
        )

        // 开始监控
        startMonitoring()

        return START_STICKY // 服务被杀死后自动重启
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "内存监控服务销毁")

        // 停止监控
        stopMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "内存监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "监控应用内存使用情况"
                setShowBadge(false)
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_memory_24) // 需要添加内存图标
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 设置内存监听器
     */
    private fun setupMemoryListener() {
        memoryManager?.addMemoryListener(object : MemoryManager.MemoryListener {
            override fun onMemoryWarning(status: MemoryManager.MemoryStatus) {
                handleMemoryWarning(status.usedHeapMB, status.maxHeapMB)
            }

            override fun onMemoryCritical(status: MemoryManager.MemoryStatus) {
                handleMemoryCritical(status.usedHeapMB, status.maxHeapMB)
            }

            override fun onMemoryNormal(status: MemoryManager.MemoryStatus) {
                // 处理内存正常状态
                updateNormalNotification(status)
            }

            override fun onLowMemory() {
                // 处理系统内存不足
                triggerEmergencyOptimization()
            }
        })
    }

    /**
     * 开始监控
     */
    private fun startMonitoring() {
        if (isMonitoring.get()) {
            Log.w(TAG, "监控已在运行")
            return
        }

        isMonitoring.set(true)

        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "开始内存监控循环")

            while (isMonitoring.get()) {
                try {
                    performMemoryCheck()
                    delay(MONITORING_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "监控循环异常", e)
                    delay(MONITORING_INTERVAL_MS * 2) // 异常时延长间隔
                }
            }

            Log.i(TAG, "内存监控循环结束")
        }
    }

    /**
     * 停止监控
     */
    private fun stopMonitoring() {
        isMonitoring.set(false)
        monitoringJob?.cancel()
        monitoringJob = null
    }

    /**
     * 执行内存检查
     */
    private suspend fun performMemoryCheck() {
        try {
            val memoryStatus = memoryManager?.getCurrentMemoryStatus()
            if (memoryStatus != null) {
                // 更新统计
                updateStats(memoryStatus)

                // 检查内存状态
                when {
                    (memoryStatus.usageRatio * 100f) >= 90f -> {
                        // 临界状态
                        handleMemoryCritical(memoryStatus.usedHeapMB, memoryStatus.maxHeapMB)
                    }

                    (memoryStatus.usageRatio * 100f) >= 75f -> {
                        // 警告状态
                        handleMemoryWarning(memoryStatus.usedHeapMB, memoryStatus.maxHeapMB)
                    }

                    else -> {
                        // 正常状态
                        updateNormalNotification(memoryStatus)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "内存检查失败", e)
        }
    }

    /**
     * 更新统计信息
     */
    private fun updateStats(memoryStatus: MemoryManager.MemoryStatus) {
        val usage = memoryStatus.usageRatio * 100f

        // 添加到历史记录
        memoryUsageHistory.add(usage)
        if (memoryUsageHistory.size > 100) {
            memoryUsageHistory.removeAt(0)
        }

        // 更新统计
        stats = stats.copy(
            totalChecks = stats.totalChecks + 1,
            averageMemoryUsage = memoryUsageHistory.average().toFloat(),
            peakMemoryUsage = maxOf(stats.peakMemoryUsage, usage)
        )
    }

    /**
     * 处理内存警告
     */
    private fun handleMemoryWarning(currentUsage: Long, maxMemory: Long) {
        val currentTime = System.currentTimeMillis()

        // 避免频繁警告
        if (currentTime - lastWarningTime < WARNING_NOTIFICATION_INTERVAL_MS) {
            return
        }

        lastWarningTime = currentTime
        stats = stats.copy(warningCount = stats.warningCount + 1)

        Log.w(TAG, "内存警告: ${currentUsage}MB / ${maxMemory}MB")

        // 更新通知
        val usagePercent = (currentUsage.toDouble() / maxMemory * 100).toInt()
        updateNotification(
            "内存使用警告",
            "内存使用率: ${usagePercent}% (${currentUsage}MB/${maxMemory}MB)"
        )

        // 触发自动优化
        triggerAutoOptimization()
    }

    /**
     * 处理内存临界
     */
    private fun handleMemoryCritical(currentUsage: Long, maxMemory: Long) {
        stats = stats.copy(criticalCount = stats.criticalCount + 1)

        Log.e(TAG, "内存临界: ${currentUsage}MB / ${maxMemory}MB")

        // 更新通知
        val usagePercent = (currentUsage.toDouble() / maxMemory * 100).toInt()
        updateNotification(
            "内存临界警告",
            "内存使用率: ${usagePercent}% - 正在优化..."
        )

        // 立即触发优化
        triggerEmergencyOptimization()

        // 通知错误处理器
        errorHandler?.handleMemoryError(currentUsage, maxMemory, false)
    }

    /**
     * 处理内存优化完成
     */
    private fun handleMemoryOptimized(freedMemoryMB: Long) {
        stats = stats.copy(optimizationCount = stats.optimizationCount + 1)

        Log.i(TAG, "内存优化完成，释放了 ${freedMemoryMB}MB")

        // 更新通知
        updateNotification(
            "内存优化完成",
            "已释放 ${freedMemoryMB}MB 内存"
        )
    }

    /**
     * 更新正常状态通知
     */
    private fun updateNormalNotification(memoryStatus: MemoryManager.MemoryStatus) {
        val usagePercent = (memoryStatus.usageRatio * 100f).toInt()
        updateNotification(
            "内存监控运行中",
            "内存使用率: ${usagePercent}% - 状态正常"
        )
    }

    /**
     * 更新通知
     */
    private fun updateNotification(title: String, content: String) {
        val notification = createNotification(title, content)
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 触发自动优化
     */
    private fun triggerAutoOptimization() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "触发自动内存优化")
                memoryManager?.optimizeMemory()
            } catch (e: Exception) {
                Log.e(TAG, "自动优化失败", e)
            }
        }
    }

    /**
     * 触发紧急优化
     */
    private fun triggerEmergencyOptimization() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.w(TAG, "触发紧急内存优化")

                // 强制垃圾回收
                System.gc()

                // 内存管理器优化
                memoryManager?.optimizeMemory()

                // Native内存优化（避免创建新实例导致死循环）
                try {
                    // 直接调用Native内存优化，不创建新的处理器实例
                    cn.alittlecookie.lut2photo.lut2photo.core.NativeLutProcessor()
                        .nativeOptimizeMemory()
                    Log.d(TAG, "Native内存优化完成")
                } catch (e: Exception) {
                    Log.e(TAG, "Native内存优化失败", e)
                }

                delay(1000) // 等待优化完成

                // 重新检查内存状态
                val newStatus = memoryManager?.getCurrentMemoryStatus()
                if (newStatus != null) {
                    Log.i(TAG, "紧急优化后内存使用率: ${(newStatus.usageRatio * 100f).toInt()}%")
                }

            } catch (e: Exception) {
                Log.e(TAG, "紧急优化失败", e)
            }
        }
    }

    /**
     * 获取监控统计
     */
    fun getMonitoringStats(): MonitoringStats = stats

    /**
     * 重置监控统计
     */
    fun resetMonitoringStats() {
        stats = MonitoringStats(0, 0, 0, 0, 0f, 0f)
        memoryUsageHistory.clear()
    }

    /**
     * 获取内存使用历史
     */
    fun getMemoryUsageHistory(): List<Float> = memoryUsageHistory.toList()
}