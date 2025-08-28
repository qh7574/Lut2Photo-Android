package cn.alittlecookie.lut2photo.lut2photo.core

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 增强版LUT处理器
 * 提供内存管理、异常处理、批量处理等高级功能
 */
class EnhancedLutProcessor : ILutProcessor {

    companion object {
        private const val TAG = "EnhancedLutProcessor"

        // 错误码常量
        const val SUCCESS = 0
        const val ERROR_INVALID_BITMAP = -1
        const val ERROR_MEMORY_ALLOCATION = -2
        const val ERROR_LUT_NOT_LOADED = -3
        const val ERROR_PROCESSING_FAILED = -4
        const val ERROR_INVALID_PARAMETERS = -5
        const val ERROR_MEMORY_LIMIT_EXCEEDED = -6
        const val ERROR_ASYNC_PROCESSING_FAILED = -7

        // 处理质量常量 - 全部设置为100以保持最佳质量
        const val QUALITY_LOW = 100
        const val QUALITY_MEDIUM = 100
        const val QUALITY_HIGH = 100

        // 抖动类型常量
        const val DITHER_NONE = 0
        const val DITHER_ORDERED = 1
        const val DITHER_FLOYD_STEINBERG = 2

        // 测试类型常量
        const val TEST_MEMORY_ALLOCATION = 0
        const val TEST_LUT_PROCESSING = 1
        const val TEST_BATCH_PROCESSING = 2
        const val TEST_ASYNC_PROCESSING = 3
        const val TEST_MEMORY_PRESSURE = 4
    }

    // Native实例句柄
    private var nativeHandle: AtomicLong = AtomicLong(0)
    private var isInitialized: AtomicBoolean = AtomicBoolean(false)
    private var isStreamProcessing: AtomicBoolean = AtomicBoolean(false)

    // 内存配置
    data class MemoryConfig(
        val maxMemoryMB: Int = 1024,  // 默认1GB，与系统配置保持一致
        val enablePooling: Boolean = true,
        val enableCompression: Boolean = true,
        val memoryWarningThreshold: Float = 0.8f
    )

    // 处理结果
    data class ProcessResult(
        val success: Boolean,
        val errorCode: Int,
        val processingTimeMs: Long = 0,
        val memoryUsedMB: Long = 0,
        val errorMessage: String = ""
    ) {
        companion object {
            fun success(timeMs: Long = 0, memoryMB: Long = 0) =
                ProcessResult(true, SUCCESS, timeMs, memoryMB)

            fun error(code: Int, message: String = "") =
                ProcessResult(false, code, errorMessage = message)
        }
    }

    // 性能统计
    data class PerformanceStats(
        val totalProcessedImages: Long,
        val averageProcessingTimeMs: Double,
        val peakMemoryUsageMB: Long,
        val currentMemoryUsageMB: Long,
        val memoryEfficiency: Double,
        val errorRate: Double
    )

    private var currentMemoryConfig = MemoryConfig()

    init {
        // 延迟初始化，等待正确的内存配置
    }

    /**
     * 初始化增强版处理器
     */
    fun initialize(memoryConfig: MemoryConfig? = null): Boolean {
        // 如果提供了内存配置，使用它；否则尝试从MemoryManager获取
        if (memoryConfig != null) {
            currentMemoryConfig = memoryConfig
        } else {
            // 尝试从全局MemoryManager获取配置
            try {
                val globalMemoryManager =
                    cn.alittlecookie.lut2photo.lut2photo.MyApplication.getMemoryManager()
                globalMemoryManager?.let { manager ->
                    val globalConfig = manager.getMemoryConfig()
                    currentMemoryConfig = MemoryConfig(
                        maxMemoryMB = globalConfig.maxHeapSizeMB,
                        enablePooling = true,
                        enableCompression = true,
                        memoryWarningThreshold = globalConfig.warningThreshold
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "无法获取全局内存配置，使用默认配置", e)
            }
        }
        return try {
            if (isInitialized.get()) {
                Log.w(TAG, "处理器已经初始化")
                return true
            }

            val handle = NativeLutProcessor().nativeCreateEnhanced()
            if (handle != 0L) {
                nativeHandle.set(handle)
                isInitialized.set(true)

                // 设置默认内存配置
                setMemoryConfig(currentMemoryConfig)

                Log.i(TAG, "增强版处理器初始化成功，句柄: $handle")
                true
            } else {
                Log.e(TAG, "增强版处理器初始化失败")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化时发生异常", e)
            false
        }
    }

    /**
     * 设置内存配置
     */
    fun setMemoryConfig(config: MemoryConfig): Boolean {
        return try {
            val handle = nativeHandle.get()
            if (handle == 0L) {
                Log.e(TAG, "处理器未初始化")
                return false
            }

            val result = NativeLutProcessor().nativeSetMemoryConfig(
                handle,
                config.maxMemoryMB,
                config.enablePooling,
                config.enableCompression
            )

            if (result == SUCCESS) {
                currentMemoryConfig = config
                Log.i(TAG, "内存配置更新成功: $config")
                true
            } else {
                Log.e(TAG, "内存配置更新失败，错误码: $result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置内存配置时发生异常", e)
            false
        }
    }

    /**
     * 获取内存使用统计
     */
    fun getMemoryStats(): String? {
        return try {
            val handle = nativeHandle.get()
            if (handle == 0L) return null

            NativeLutProcessor().nativeGetMemoryStats(handle)
        } catch (e: Exception) {
            Log.e(TAG, "获取内存统计时发生异常", e)
            null
        }
    }

    /**
     * 检查是否接近内存限制
     */
    fun isNearMemoryLimit(): Boolean {
        return try {
            val handle = nativeHandle.get()
            if (handle == 0L) return false

            NativeLutProcessor().nativeIsNearMemoryLimit(
                handle,
                currentMemoryConfig.memoryWarningThreshold
            )
        } catch (e: Exception) {
            Log.e(TAG, "检查内存限制时发生异常", e)
            true // 安全起见返回true
        }
    }

    /**
     * 优化内存使用
     */
    fun optimizeMemory(): Boolean {
        return try {
            val handle = nativeHandle.get()
            if (handle == 0L) return false

            val result = NativeLutProcessor().nativeOptimizeMemory(handle)
            result == SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "内存优化时发生异常", e)
            false
        }
    }

    /**
     * 批量处理图像
     */
    suspend fun processBitmapBatch(
        inputBitmaps: Array<Bitmap>,
        outputBitmaps: Array<Bitmap>,
        strength: Float = 1.0f,
        lut2Strength: Float = 0.0f,
        quality: Int = QUALITY_HIGH,
        ditherType: Int = DITHER_NONE,
        useMultiThreading: Boolean = true
    ): Array<ProcessResult> = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        try {
            val handle = nativeHandle.get()
            if (handle == 0L) {
                return@withContext Array(inputBitmaps.size) {
                    ProcessResult.error(ERROR_INVALID_PARAMETERS, "处理器未初始化")
                }
            }

            if (inputBitmaps.size != outputBitmaps.size) {
                return@withContext Array(inputBitmaps.size) {
                    ProcessResult.error(ERROR_INVALID_PARAMETERS, "输入输出数组大小不匹配")
                }
            }

            // 检查内存限制
            if (isNearMemoryLimit()) {
                Log.w(TAG, "接近内存限制，尝试优化内存")
                optimizeMemory()
            }

            val results = NativeLutProcessor().nativeProcessBitmapBatch(
                handle,
                inputBitmaps,
                outputBitmaps,
                strength,
                lut2Strength,
                quality,
                ditherType,
                useMultiThreading
            )

            val endTime = System.currentTimeMillis()
            val totalTime = endTime - startTime

            // 转换结果
            Array(results.size) { index ->
                val result = results[index]
                if (result == SUCCESS) {
                    ProcessResult.success(totalTime / results.size, getCurrentMemoryUsage())
                } else {
                    ProcessResult.error(result, getErrorMessage(result))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "批量处理时发生异常", e)
            Array(inputBitmaps.size) {
                ProcessResult.error(ERROR_PROCESSING_FAILED, e.message ?: "未知异常")
            }
        }
    }

    /**
     * 异步处理图像
     */
    suspend fun processBitmapAsync(
        inputBitmap: Bitmap,
        outputBitmap: Bitmap,
        strength: Float = 1.0f,
        lut2Strength: Float = 0.0f,
        quality: Int = QUALITY_HIGH,
        ditherType: Int = DITHER_NONE,
        useMultiThreading: Boolean = true
    ): ProcessResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        try {
            val handle = nativeHandle.get()
            if (handle == 0L) {
                return@withContext ProcessResult.error(ERROR_INVALID_PARAMETERS, "处理器未初始化")
            }

            // 检查内存限制
            if (isNearMemoryLimit()) {
                Log.w(TAG, "接近内存限制，尝试优化内存")
                optimizeMemory()
            }

            val result = NativeLutProcessor().nativeProcessBitmapAsync(
                handle,
                inputBitmap,
                outputBitmap,
                strength,
                lut2Strength,
                quality,
                ditherType,
                useMultiThreading
            )

            val endTime = System.currentTimeMillis()
            val processingTime = endTime - startTime

            if (result == SUCCESS) {
                ProcessResult.success(processingTime, getCurrentMemoryUsage())
            } else {
                ProcessResult.error(result, getErrorMessage(result))
            }
        } catch (e: Exception) {
            Log.e(TAG, "异步处理时发生异常", e)
            ProcessResult.error(ERROR_ASYNC_PROCESSING_FAILED, e.message ?: "未知异常")
        }
    }

    /**
     * 开始流式处理
     */
    fun startStreamProcessing(): Boolean {
        return try {
            val handle = nativeHandle.get()
            if (handle == 0L) return false

            val result = NativeLutProcessor().nativeStartStreamProcessing(handle)
            if (result == SUCCESS) {
                isStreamProcessing.set(true)
                Log.i(TAG, "流式处理已开始")
                true
            } else {
                Log.e(TAG, "开始流式处理失败，错误码: $result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "开始流式处理时发生异常", e)
            false
        }
    }

    /**
     * 停止流式处理
     */
    fun stopStreamProcessing(): Boolean {
        return try {
            val handle = nativeHandle.get()
            if (handle == 0L) return false

            val result = NativeLutProcessor().nativeStopStreamProcessing(handle)
            if (result == SUCCESS) {
                isStreamProcessing.set(false)
                Log.i(TAG, "流式处理已停止")
                true
            } else {
                Log.e(TAG, "停止流式处理失败，错误码: $result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止流式处理时发生异常", e)
            false
        }
    }

    /**
     * 处理流式帧
     */
    suspend fun processStreamFrame(
        inputBitmap: Bitmap,
        outputBitmap: Bitmap,
        strength: Float = 1.0f
    ): ProcessResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        try {
            val handle = nativeHandle.get()
            if (handle == 0L) {
                return@withContext ProcessResult.error(ERROR_INVALID_PARAMETERS, "处理器未初始化")
            }

            if (!isStreamProcessing.get()) {
                return@withContext ProcessResult.error(ERROR_INVALID_PARAMETERS, "流式处理未开始")
            }

            val result = NativeLutProcessor().nativeProcessStreamFrame(
                handle,
                inputBitmap,
                outputBitmap,
                strength
            )

            val endTime = System.currentTimeMillis()
            val processingTime = endTime - startTime

            if (result == SUCCESS) {
                ProcessResult.success(processingTime, getCurrentMemoryUsage())
            } else {
                ProcessResult.error(result, getErrorMessage(result))
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理流式帧时发生异常", e)
            ProcessResult.error(ERROR_PROCESSING_FAILED, e.message ?: "未知异常")
        }
    }

    /**
     * 运行性能测试
     */
    fun runPerformanceTest(
        testType: Int,
        iterations: Int = 100,
        imageWidth: Int = 1920,
        imageHeight: Int = 1080
    ): String? {
        return try {
            NativeLutProcessor().nativeRunPerformanceTest(
                testType,
                iterations,
                imageWidth,
                imageHeight
            )
        } catch (e: Exception) {
            Log.e(TAG, "运行性能测试时发生异常", e)
            null
        }
    }

    /**
     * 获取性能统计
     */
    fun getPerformanceStats(): String? {
        return try {
            val handle = nativeHandle.get()
            if (handle == 0L) return null

            NativeLutProcessor().nativeGetPerformanceStats(handle)
        } catch (e: Exception) {
            Log.e(TAG, "获取性能统计时发生异常", e)
            null
        }
    }

    // 实现ILutProcessor接口
    override fun getProcessorType(): ILutProcessor.ProcessorType {
        return ILutProcessor.ProcessorType.CPU
    }

    override suspend fun isAvailable(): Boolean {
        return isInitialized.get() && nativeHandle.get() != 0L
    }

    override suspend fun loadCubeLut(inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val handle = nativeHandle.get()
                if (handle == 0L) {
                    Log.e(TAG, "处理器未初始化")
                    return@withContext false
                }

                // 解析LUT数据（复用现有逻辑）
                val lutData = parseCubeLut(inputStream)
                if (lutData.isEmpty()) {
                    Log.e(TAG, "LUT数据解析失败")
                    return@withContext false
                }

                val lutSize = Math.round(Math.cbrt(lutData.size / 3.0)).toInt()
                Log.d(TAG, "加载增强版LUT，尺寸: ${lutSize}x${lutSize}x${lutSize}")

                val result = NativeLutProcessor().nativeLoadLutEnhanced(
                    handle,
                    lutData.toFloatArray(),
                    lutSize
                )

                result == SUCCESS
            } catch (e: Exception) {
                Log.e(TAG, "加载LUT时发生异常", e)
                false
            }
        }
    }

    override suspend fun processImage(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? {
        return try {
            // 创建输出bitmap
            val outputBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

            val result = processBitmapAsync(
                bitmap,
                outputBitmap,
                params.strength,
                params.lut2Strength,
                QUALITY_HIGH,
                DITHER_NONE,
                true
            )

            if (result.success) outputBitmap else null
        } catch (e: Exception) {
            Log.e(TAG, "处理图片失败", e)
            null
        }
    }

    // 辅助方法
    private fun getCurrentMemoryUsage(): Long {
        return try {
            val handle = nativeHandle.get()
            if (handle == 0L) return 0L

            NativeLutProcessor().nativeGetMemoryUsage(handle) / (1024 * 1024) // 转换为MB
        } catch (e: Exception) {
            0L
        }
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            ERROR_INVALID_BITMAP -> "无效的Bitmap"
            ERROR_MEMORY_ALLOCATION -> "内存分配失败"
            ERROR_LUT_NOT_LOADED -> "LUT未加载"
            ERROR_PROCESSING_FAILED -> "处理失败"
            ERROR_INVALID_PARAMETERS -> "参数无效"
            ERROR_MEMORY_LIMIT_EXCEEDED -> "超出内存限制"
            ERROR_ASYNC_PROCESSING_FAILED -> "异步处理失败"
            else -> "未知错误 ($errorCode)"
        }
    }

    private fun parseCubeLut(inputStream: InputStream): List<Float> {
        // 复用现有的LUT解析逻辑
        val lutData = mutableListOf<Float>()

        try {
            inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                        val parts = trimmedLine.split("\\s+".toRegex())
                        if (parts.size >= 3) {
                            try {
                                lutData.add(parts[0].toFloat())
                                lutData.add(parts[1].toFloat())
                                lutData.add(parts[2].toFloat())
                            } catch (e: NumberFormatException) {
                                // 忽略无效行
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析LUT文件时发生异常", e)
        }

        return lutData
    }

    override suspend fun release() {
        cleanup()
    }

    override fun getProcessorInfo(): String {
        return "Enhanced LUT Processor with memory management and advanced features"
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            // 停止流式处理
            if (isStreamProcessing.get()) {
                stopStreamProcessing()
            }

            val handle = nativeHandle.get()
            if (handle != 0L) {
                NativeLutProcessor().nativeDestroyEnhanced(handle)
                nativeHandle.set(0)
                isInitialized.set(false)
                Log.i(TAG, "增强版处理器资源已清理")
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理资源时发生异常", e)
        }
    }

    /**
     * 析构函数
     */
    protected fun finalize() {
        cleanup()
    }
}