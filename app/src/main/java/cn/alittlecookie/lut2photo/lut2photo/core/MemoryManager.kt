package cn.alittlecookie.lut2photo.lut2photo.core

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 应用层内存管理器
 * 监控内存使用，预防OOM，优化内存分配
 */
class MemoryManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MemoryManager"

        @Volatile
        private var INSTANCE: MemoryManager? = null

        fun getInstance(context: Context): MemoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // 内存阈值常量
        private const val DEFAULT_WARNING_THRESHOLD = 0.75f  // 75%
        private const val DEFAULT_CRITICAL_THRESHOLD = 0.9f  // 90%
        private const val DEFAULT_MAX_HEAP_RATIO = 0.8f     // 80%

        // 监控间隔
        private const val MONITORING_INTERVAL_MS = 5000L    // 5秒
        private const val FAST_MONITORING_INTERVAL_MS = 1000L // 1秒（高压力时）
    }

    // 内存配置
    data class MemoryConfig(
        val maxHeapSizeMB: Int,
        val warningThreshold: Float = DEFAULT_WARNING_THRESHOLD,
        val criticalThreshold: Float = DEFAULT_CRITICAL_THRESHOLD,
        val enableAutoGC: Boolean = true,
        val enableMemoryCompression: Boolean = true,
        val maxCacheSize: Int = 100 // MB
    )

    // 内存状态
    data class MemoryStatus(
        val totalHeapMB: Long,
        val usedHeapMB: Long,
        val freeHeapMB: Long,
        val maxHeapMB: Long,
        val nativeHeapMB: Long,
        val usageRatio: Float,
        val isWarning: Boolean,
        val isCritical: Boolean,
        val availableMemoryMB: Long
    )

    // 内存监听器
    interface MemoryListener {
        fun onMemoryWarning(status: MemoryStatus)
        fun onMemoryCritical(status: MemoryStatus)
        fun onMemoryNormal(status: MemoryStatus)
        fun onLowMemory()
    }

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()

    private var memoryConfig: MemoryConfig
    private val listeners = mutableSetOf<MemoryListener>()
    private val isMonitoring = AtomicBoolean(false)
    private val lastGCTime = AtomicLong(0)
    private var monitoringJob: Job? = null

    // 内存统计
    private val totalAllocations = AtomicLong(0)
    private val peakMemoryUsage = AtomicLong(0)
    private val gcCount = AtomicLong(0)

    init {
        // 获取设备内存信息并设置默认配置
        activityManager.getMemoryInfo(memoryInfo)
        val deviceMemoryMB = memoryInfo.totalMem / (1024 * 1024)
        val maxHeapSize = Runtime.getRuntime().maxMemory() / (1024 * 1024)

        memoryConfig = MemoryConfig(
            maxHeapSizeMB = maxOf(1024, (maxHeapSize * DEFAULT_MAX_HEAP_RATIO).toInt()),
            maxCacheSize = min(deviceMemoryMB.toInt() / 8, 200) // 设备内存的1/8或200MB
        )

        Log.i(TAG, "内存管理器初始化完成")
        Log.i(TAG, "设备总内存: ${deviceMemoryMB}MB, 最大堆内存: ${maxHeapSize}MB")
        Log.i(TAG, "配置的最大堆使用: ${memoryConfig.maxHeapSizeMB}MB")
    }

    /**
     * 设置内存配置
     */
    fun setMemoryConfig(config: MemoryConfig) {
        memoryConfig = config
        Log.i(TAG, "内存配置已更新: $config")
    }

    /**
     * 获取当前内存配置
     */
    fun getMemoryConfig(): MemoryConfig = memoryConfig

    /**
     * 添加内存监听器
     */
    fun addMemoryListener(listener: MemoryListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * 移除内存监听器
     */
    fun removeMemoryListener(listener: MemoryListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * 开始内存监控
     */
    fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            monitoringJob = CoroutineScope(Dispatchers.Default).launch {
                Log.i(TAG, "开始内存监控")

                while (isActive && isMonitoring.get()) {
                    try {
                        val status = getCurrentMemoryStatus()
                        handleMemoryStatus(status)

                        // 根据内存压力调整监控频率
                        val interval = if (status.isCritical || status.isWarning) {
                            FAST_MONITORING_INTERVAL_MS
                        } else {
                            MONITORING_INTERVAL_MS
                        }

                        delay(interval)
                    } catch (e: Exception) {
                        Log.e(TAG, "内存监控异常", e)
                        delay(MONITORING_INTERVAL_MS)
                    }
                }
            }
        }
    }

    /**
     * 停止内存监控
     */
    fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            monitoringJob?.cancel()
            monitoringJob = null
            Log.i(TAG, "内存监控已停止")
        }
    }

    /**
     * 获取当前内存状态
     */
    fun getCurrentMemoryStatus(): MemoryStatus {
        val runtime = Runtime.getRuntime()
        val systemMaxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap

        // 使用我们配置的内存限制，而不是系统的堆内存限制
        val configuredMaxHeapMB = memoryConfig.maxHeapSizeMB.toLong()
        val systemMaxHeapMB = systemMaxHeap / (1024 * 1024)
        val totalHeapMB = totalHeap / (1024 * 1024)
        val freeHeapMB = freeHeap / (1024 * 1024)
        val usedHeapMB = usedHeap / (1024 * 1024)

        val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        activityManager.getMemoryInfo(memoryInfo)
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)

        // 使用配置的内存限制来计算使用率
        val usageRatio = usedHeapMB.toFloat() / configuredMaxHeapMB.toFloat()
        val isWarning = usageRatio >= memoryConfig.warningThreshold
        val isCritical = usageRatio >= memoryConfig.criticalThreshold

        // 更新峰值内存使用
        val currentTotal = usedHeapMB + nativeHeapMB
        peakMemoryUsage.updateAndGet { current -> max(current, currentTotal) }

        return MemoryStatus(
            totalHeapMB = totalHeapMB,
            usedHeapMB = usedHeapMB,
            freeHeapMB = freeHeapMB,
            maxHeapMB = configuredMaxHeapMB, // 使用配置的内存限制
            nativeHeapMB = nativeHeapMB,
            usageRatio = usageRatio,
            isWarning = isWarning,
            isCritical = isCritical,
            availableMemoryMB = availableMemoryMB
        )
    }

    /**
     * 检查是否可以分配指定大小的内存
     */
    fun canAllocate(sizeBytes: Long): Boolean {
        val status = getCurrentMemoryStatus()
        val sizeMB = sizeBytes / (1024 * 1024)

        // 检查是否超过配置的最大堆使用
        val projectedUsage = status.usedHeapMB + sizeMB
        if (projectedUsage > memoryConfig.maxHeapSizeMB) {
            Log.w(TAG, "分配${sizeMB}MB将超过配置的最大堆使用限制")
            return false
        }

        // 检查是否会导致内存压力（使用配置的内存限制）
        val projectedRatio = projectedUsage.toFloat() / memoryConfig.maxHeapSizeMB.toFloat()
        if (projectedRatio > memoryConfig.criticalThreshold) {
            Log.w(TAG, "分配${sizeMB}MB将导致内存压力")
            return false
        }

        return true
    }

    /**
     * 请求内存分配
     */
    fun requestAllocation(sizeBytes: Long): Boolean {
        if (!canAllocate(sizeBytes)) {
            // 尝试释放内存
            if (memoryConfig.enableAutoGC) {
                performGarbageCollection()

                // 再次检查
                if (!canAllocate(sizeBytes)) {
                    Log.w(TAG, "即使在GC后也无法分配${sizeBytes / (1024 * 1024)}MB内存")
                    return false
                }
            } else {
                return false
            }
        }

        totalAllocations.incrementAndGet()
        return true
    }

    /**
     * 执行垃圾回收
     */
    fun performGarbageCollection() {
        val currentTime = System.currentTimeMillis()
        val lastGC = lastGCTime.get()

        // 避免频繁GC（至少间隔2秒）
        if (currentTime - lastGC < 2000) {
            Log.d(TAG, "跳过GC，距离上次GC时间过短")
            return
        }

        if (lastGCTime.compareAndSet(lastGC, currentTime)) {
            Log.i(TAG, "执行垃圾回收")
            val beforeStatus = getCurrentMemoryStatus()

            System.gc()
            System.runFinalization()

            // 等待GC完成
            Thread.sleep(100)

            val afterStatus = getCurrentMemoryStatus()
            val freedMB = beforeStatus.usedHeapMB - afterStatus.usedHeapMB

            gcCount.incrementAndGet()
            Log.i(TAG, "GC完成，释放了${freedMB}MB内存")
        }
    }

    /**
     * 优化内存使用
     */
    fun optimizeMemory() {
        Log.i(TAG, "开始内存优化")

        // 执行GC
        if (memoryConfig.enableAutoGC) {
            performGarbageCollection()
        }

        // 不再通知监听器onLowMemory，避免循环调用
        // 只执行内部的内存清理逻辑

        Log.i(TAG, "内存优化完成")
    }

    /**
     * 获取内存统计信息
     */
    fun getMemoryStatistics(): Map<String, Any> {
        val status = getCurrentMemoryStatus()

        return mapOf(
            "currentUsedHeapMB" to status.usedHeapMB,
            "currentNativeHeapMB" to status.nativeHeapMB,
            "maxHeapMB" to status.maxHeapMB,
            "usageRatio" to status.usageRatio,
            "peakMemoryUsageMB" to peakMemoryUsage.get(),
            "totalAllocations" to totalAllocations.get(),
            "gcCount" to gcCount.get(),
            "availableMemoryMB" to status.availableMemoryMB,
            "isLowMemory" to memoryInfo.lowMemory,
            "memoryClass" to activityManager.memoryClass,
            "largeMemoryClass" to activityManager.largeMemoryClass
        )
    }

    /**
     * 重置统计信息
     */
    fun resetStatistics() {
        totalAllocations.set(0)
        peakMemoryUsage.set(0)
        gcCount.set(0)
        Log.i(TAG, "内存统计信息已重置")
    }

    /**
     * 处理内存状态变化
     */
    private fun handleMemoryStatus(status: MemoryStatus) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    when {
                        status.isCritical -> {
                            listener.onMemoryCritical(status)
                            if (memoryConfig.enableAutoGC) {
                                performGarbageCollection()
                            }
                        }

                        status.isWarning -> {
                            listener.onMemoryWarning(status)
                        }

                        else -> {
                            listener.onMemoryNormal(status)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理内存状态时发生异常", e)
                }
            }
        }

        // 记录内存状态（仅在警告或严重时）
        if (status.isWarning || status.isCritical) {
            Log.w(
                TAG,
                "内存状态: 使用${status.usedHeapMB}MB/${status.maxHeapMB}MB (${(status.usageRatio * 100).toInt()}%), Native: ${status.nativeHeapMB}MB"
            )
        }
    }

    /**
     * 获取推荐的图像处理配置
     */
    fun getRecommendedImageConfig(): EnhancedLutProcessor.MemoryConfig {
        val status = getCurrentMemoryStatus()
        val availableMemoryMB = status.freeHeapMB + (status.availableMemoryMB / 4) // 保守估计

        return when {
            status.isCritical || availableMemoryMB < 50 -> {
                // 低内存配置
                EnhancedLutProcessor.MemoryConfig(
                    maxMemoryMB = 32,
                    enablePooling = true,
                    enableCompression = true,
                    memoryWarningThreshold = 0.7f
                )
            }

            status.isWarning || availableMemoryMB < 100 -> {
                // 中等内存配置
                EnhancedLutProcessor.MemoryConfig(
                    maxMemoryMB = 64,
                    enablePooling = true,
                    enableCompression = true,
                    memoryWarningThreshold = 0.8f
                )
            }

            else -> {
                // 高内存配置
                EnhancedLutProcessor.MemoryConfig(
                    maxMemoryMB = min(availableMemoryMB.toInt() / 2, 256),
                    enablePooling = true,
                    enableCompression = false,
                    memoryWarningThreshold = 0.85f
                )
            }
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopMonitoring()
        synchronized(listeners) {
            listeners.clear()
        }
        Log.i(TAG, "内存管理器已清理")
    }
}