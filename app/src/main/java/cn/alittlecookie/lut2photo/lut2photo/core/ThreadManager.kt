package cn.alittlecookie.lut2photo.lut2photo.core

import cn.alittlecookie.lut2photo.lut2photo.core.NativeLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.EnhancedLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import cn.alittlecookie.lut2photo.lut2photo.gpu.GpuLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Thread manager for handling CPU parallel and GPU serial processing
 * CPU: Maximum 5 concurrent image processing tasks
 * GPU: Serial processing (one at a time)
 */
class ThreadManager(context: Context) {
    companion object {
        private const val TAG = "ThreadManager"
        private const val MAX_CPU_CONCURRENT = 5

        // 图片保存时的尺寸限制，防止OOM
        private const val MAX_SAVE_PIXELS = 800_000_000L // 提升到8亿像素，支持更大图片保存
    }

    data class ProcessingTask(
        val id: String,
        val bitmap: Bitmap,
        val params: ILutProcessor.ProcessingParams,
        val onProgress: ((String) -> Unit)? = null,
        val onComplete: (Result<Bitmap?>) -> Unit
    )

    // Processors
    private val cpuProcessor = CpuLutProcessor()
    private val gpuProcessor = GpuLutProcessor(context)
    private val filmGrainProcessor = FilmGrainProcessor()

    // Preferred processor type
    private var preferredProcessor = ILutProcessor.ProcessorType.CPU
    private var isGpuAvailable = false
    
    // 当前颗粒配置
    private var currentGrainConfig: cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig? = null

    // PreferencesManager for reading user settings
    private val preferencesManager = PreferencesManager(context)

    // CPU processing semaphore (max 5 concurrent)
    private val cpuSemaphore = Semaphore(MAX_CPU_CONCURRENT)

    // GPU processing channel (serial processing)
    private val gpuChannel = Channel<ProcessingTask>(Channel.UNLIMITED)

    // Active tasks tracking
    private val activeTasks = ConcurrentHashMap<String, Job>()
    private val taskCounter = AtomicInteger(0)
    private var nativeProcessor: ILutProcessor? = null

    // Coroutine scopes
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val cpuScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gpuScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // GPU processing job
    private var gpuProcessingJob: Job? = null

    init {
        initializeProcessors()
        initializeNativeProcessor()
    }

    /**
     * 初始化Native处理器用于内存监控
     */
    private fun initializeNativeProcessor() {
        try {
            // 创建增强版处理器实例
            val enhancedProcessor = EnhancedLutProcessor()

            // 初始化增强版处理器
            if (!enhancedProcessor.initialize()) {
                Log.e(TAG, "增强版处理器初始化失败，降级到Native处理器")
                nativeProcessor = NativeLutProcessor()
            } else {
                nativeProcessor = enhancedProcessor
            }
            Log.d(TAG, "Native处理器初始化成功，用于内存监控")
        } catch (e: Exception) {
            Log.e(TAG, "Native处理器初始化失败", e)
        }
    }

    private fun initializeProcessors() {
        Log.d(TAG, "开始初始化处理器")

        // 添加：立即启动GPU处理循环
        startGpuProcessingLoop()

        // 在协程中同步检查GPU可用性，然后配置处理器
        managerScope.launch {
            try {
                Log.d(TAG, "检查GPU可用性")
                val gpuAvailable = gpuProcessor.isAvailable()
                Log.d(TAG, "GPU可用性检查结果: $gpuAvailable")

                // 修复：正确更新isGpuAvailable变量
                isGpuAvailable = gpuAvailable

                if (!gpuAvailable) {
                    Log.w(TAG, "GPU不可用，回退到CPU处理器")
                }

                // GPU检查完成后再根据用户设置配置处理器
                configureProcessorFromSettings()

            } catch (e: Exception) {
                Log.e(TAG, "初始化处理器时发生错误", e)
                isGpuAvailable = false  // 修复：确保错误时也更新变量
                // 即使出错也要配置处理器，确保有默认设置
                configureProcessorFromSettings()
            }
        }
    }

    /**
     * 根据用户设置配置处理器类型
     */
    private fun configureProcessorFromSettings() {
        try {
            // 将设置值转换为大写以确保兼容性
            val processorTypeSetting = preferencesManager.processorType.uppercase()

            val userProcessorType = when (processorTypeSetting) {
                "GPU" -> {
                    if (isGpuAvailable) {
                        ILutProcessor.ProcessorType.GPU
                    } else {
                        Log.w(TAG, "User selected GPU but GPU not available, falling back to CPU")
                        ILutProcessor.ProcessorType.CPU
                    }
                }

                "CPU" -> ILutProcessor.ProcessorType.CPU
                "AUTO" -> {
                    // 自动模式：如果GPU可用则使用GPU，否则使用CPU
                    if (isGpuAvailable) ILutProcessor.ProcessorType.GPU else ILutProcessor.ProcessorType.CPU
                }

                else -> {
                    Log.w(
                        TAG,
                        "Unknown processor type: ${preferencesManager.processorType}, using CPU"
                    )
                    ILutProcessor.ProcessorType.CPU
                }
            }

            preferredProcessor = userProcessorType
            Log.d(
                TAG,
                "Processor configured from settings: $userProcessorType (user setting: ${preferencesManager.processorType})"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read processor settings, using CPU as default", e)
            preferredProcessor = ILutProcessor.ProcessorType.CPU
        }
    }

    /**
     * 更新处理器设置（用于响应设置变化）
     */
    fun updateProcessorFromSettings() {
        Log.d(TAG, "Updating processor settings...")

        // 如果用户选择GPU但当前不可用，重新检查GPU可用性
        if (preferencesManager.processorType.uppercase() == "GPU" && !isGpuAvailable) {
            Log.d(
                TAG,
                "User selected GPU but currently unavailable, rechecking GPU availability..."
            )
            managerScope.launch {
                try {
                    val gpuAvailable = gpuProcessor.isAvailable()
                    Log.d(TAG, "GPU重新检查结果: $gpuAvailable")
                    isGpuAvailable = gpuAvailable

                    // 重新配置处理器
                    configureProcessorFromSettings()
                } catch (e: Exception) {
                    Log.e(TAG, "GPU重新检查失败", e)
                    configureProcessorFromSettings()
                }
            }
        } else {
            // 直接配置
            configureProcessorFromSettings()
        }
    }

    private fun startGpuProcessingLoop() {
        gpuProcessingJob = gpuScope.launch {
            try {
                for (task in gpuChannel) {
                    try {
                        task.onProgress?.invoke("Processing on GPU...")
                        val result = gpuProcessor.processImage(task.bitmap, task.params)
                        task.onComplete(Result.success(result))
                    } catch (e: Exception) {
                        Log.e(TAG, "GPU processing failed for task ${task.id}", e)
                        // Fallback to CPU processing
                        processCpuFallback(task)
                    } finally {
                        activeTasks.remove(task.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GPU processing loop error", e)
            }
        }
    }

    private suspend fun processCpuFallback(task: ProcessingTask) {
        try {
            task.onProgress?.invoke("Fallback to CPU processing...")
            val result = cpuProcessor.processImage(task.bitmap, task.params)
            task.onComplete(Result.success(result))
        } catch (e: Exception) {
            Log.e(TAG, "CPU fallback processing failed for task ${task.id}", e)
            task.onComplete(Result.failure(e))
        }
    }

    /**
     * Load LUT file into both processors
     * @param inputStream LUT file input stream
     * @return true if loaded successfully
     */
    suspend fun loadLut(inputStream: InputStream): Boolean {
        return try {
            // 读取所有数据到字节数组，避免流被关闭的问题
            val lutData = inputStream.readBytes()

            // 为CPU处理器创建新的输入流
            val cpuInputStream = ByteArrayInputStream(lutData)
            val cpuLoaded = cpuProcessor.loadCubeLut(cpuInputStream)
            cpuInputStream.close()

            // 为GPU处理器创建新的输入流
            val gpuLoaded = if (isGpuAvailable) {
                val gpuInputStream = ByteArrayInputStream(lutData)
                val result = gpuProcessor.loadCubeLut(gpuInputStream)
                gpuInputStream.close()
                result
            } else {
                true // Skip GPU loading if not available
            }

            cpuLoaded && gpuLoaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LUT", e)
            false
        }
    }

    /**
     * Load second LUT file into both processors
     * @param inputStream Second LUT file input stream
     * @return true if loaded successfully
     */
    suspend fun loadSecondLut(inputStream: InputStream): Boolean {
        return try {
            // 读取所有数据到字节数组，避免流被关闭的问题
            val lut2Data = inputStream.readBytes()

            // 为CPU处理器创建新的输入流
            val cpuInputStream = ByteArrayInputStream(lut2Data)
            val cpuLoaded = cpuProcessor.loadSecondCubeLut(cpuInputStream)
            cpuInputStream.close()

            // 为GPU处理器创建新的输入流
            val gpuLoaded = if (isGpuAvailable) {
                val gpuInputStream = ByteArrayInputStream(lut2Data)
                val result = gpuProcessor.loadSecondCubeLut(gpuInputStream)
                gpuInputStream.close()
                result
            } else {
                true // Skip GPU loading if not available
            }

            Log.d(TAG, "Second LUT loading result: CPU=$cpuLoaded, GPU=$gpuLoaded")
            // 添加更详细的日志
            if (cpuLoaded && gpuLoaded) {
                Log.d(TAG, "第二个LUT成功加载到CPU和GPU处理器")
            } else if (cpuLoaded) {
                Log.d(TAG, "第二个LUT仅成功加载到CPU处理器")
            } else if (gpuLoaded) {
                Log.d(TAG, "第二个LUT仅成功加载到GPU处理器")
            } else {
                Log.e(TAG, "第二个LUT加载失败")
            }

            cpuLoaded && gpuLoaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load second LUT", e)
            false
        }
    }

    /**
     * Submit a processing task
     * @param bitmap Input bitmap to process
     * @param params Processing parameters
     * @param forceProcessor Force specific processor type (optional)
     * @param onProgress Progress callback
     * @param onComplete Completion callback
     * @return Task ID for tracking
     */
    fun submitTask(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams,
        forceProcessor: ILutProcessor.ProcessorType? = null,
        onProgress: ((String) -> Unit)? = null,
        onComplete: (Result<Bitmap?>) -> Unit
    ): String {
        val taskId = "task_${taskCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val task = ProcessingTask(taskId, bitmap, params, onProgress, onComplete)

        val processorType = forceProcessor ?: preferredProcessor

        // 增强日志记录
        Log.d(TAG, "提交任务 $taskId:")
        Log.d(TAG, "  - 强制处理器: $forceProcessor")
        Log.d(TAG, "  - 首选处理器: $preferredProcessor")
        Log.d(TAG, "  - 实际使用: $processorType")
        Log.d(TAG, "  - GPU可用: $isGpuAvailable")
        Log.d(TAG, "  - 用户设置: ${preferencesManager.processorType}")
        Log.d(
            TAG,
            "  - 处理参数: 强度=${params.strength}, LUT2强度=${params.lut2Strength}, 质量=${params.quality}, 抖动=${params.ditherType}"
        )
        
        val job = when (processorType) {
            ILutProcessor.ProcessorType.GPU -> {
                if (isGpuAvailable) {
                    Log.d(TAG, "任务 $taskId 提交给GPU处理")
                    managerScope.launch {
                        gpuChannel.send(task)
                    }
                } else {
                    Log.w(TAG, "GPU请求但不可用，任务 $taskId 回退到CPU")
                    submitCpuTask(task)
                }
            }

            ILutProcessor.ProcessorType.CPU -> {
                Log.d(TAG, "任务 $taskId 提交给CPU处理")
                submitCpuTask(task)
            }
        }

        activeTasks[taskId] = job
        return taskId
    }

    private fun submitCpuTask(task: ProcessingTask): Job {
        return cpuScope.launch {
            cpuSemaphore.acquire()
            try {
                task.onProgress?.invoke("Processing on CPU...")

                // 检查Native内存使用情况
                nativeProcessor?.let { processor ->
                    if (processor is NativeLutProcessor) {
                        if (processor.isNearMemoryLimit(0.85f)) {
                            Log.w(TAG, "Native内存使用接近限制，强制执行垃圾回收")
                            processor.forceGarbageCollection()

                            // 等待一段时间让GC完成
                            kotlinx.coroutines.delay(100)

                            // 再次检查，如果仍然接近限制，则拒绝处理
                            if (processor.isNearMemoryLimit(0.9f)) {
                                task.onComplete(Result.failure(Exception("Native内存不足，无法处理图片")))
                                return@launch
                            }
                        }
                    }
                }
                
                val result = cpuProcessor.processImage(task.bitmap, task.params)

                // 记录内存使用情况
                nativeProcessor?.let { nativeProc ->
                    if (nativeProc is NativeLutProcessor) {
                        val memoryUsage = nativeProc.getNativeMemoryUsage()
                        Log.d(TAG, "CPU处理完成，Native内存使用: ${memoryUsage / 1024 / 1024}MB")
                    }
                }
                
                task.onComplete(Result.success(result))
            } catch (e: Exception) {
                Log.e(TAG, "CPU processing failed for task ${task.id}", e)
                task.onComplete(Result.failure(e))
            } finally {
                cpuSemaphore.release()
                activeTasks.remove(task.id)
            }
        }
    }

    /**
     * Cancel all active tasks
     */
    fun cancelAllTasks() {
        val taskIds = activeTasks.keys.toList()
        activeTasks.values.forEach { it.cancel() }
        activeTasks.clear()
        Log.d(TAG, "Cancelled ${taskIds.size} active tasks")
    }

    /**
     * Get processor information
     */
    fun getProcessorInfo(): ProcessorInfo {
        return ProcessorInfo(
            cpuInfo = cpuProcessor.getProcessorInfo(),
            gpuInfo = if (isGpuAvailable) gpuProcessor.getProcessorInfo() else "GPU not available",
            preferredProcessor = preferredProcessor,
            isGpuAvailable = isGpuAvailable
        )
    }
    
    /**
     * 设置胶片颗粒配置
     * @param config 颗粒配置，传null则禁用颗粒效果
     */
    fun setFilmGrainConfig(config: cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig?) {
        currentGrainConfig = config
        // 同步到GPU处理器
        gpuProcessor.setFilmGrainConfig(config)
        Log.d(TAG, "设置胶片颗粒配置: ${if (config?.isEnabled == true) "启用，强度=${config.globalStrength}" else "禁用"}")
    }
    
    /**
     * 获取当前胶片颗粒配置
     */
    fun getFilmGrainConfig(): cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig? {
        return currentGrainConfig
    }
    
    /**
     * 对图片应用颗粒效果（CPU版本，用于GPU回退或独立调用）
     * @param bitmap 输入图片
     * @param config 颗粒配置
     * @return 处理后的图片
     */
    suspend fun applyFilmGrain(
        bitmap: Bitmap,
        config: cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig?
    ): Bitmap? {
        if (config == null || !config.isEnabled || config.globalStrength <= 0f) {
            return bitmap
        }
        return filmGrainProcessor.processImage(bitmap, config)
    }

    /**
     * 清除主 LUT（当用户选择"未选择"时调用）
     */
    suspend fun clearMainLut() = withContext(Dispatchers.IO) {
        try {
            // 清除 CPU 处理器的主 LUT
            cpuProcessor.clearMainLut()
            
            // 清除 GPU 处理器的主 LUT
            if (isGpuAvailable) {
                gpuProcessor.clearMainLut()
            }
            
            Log.d(TAG, "主 LUT 已清除")
        } catch (e: Exception) {
            Log.e(TAG, "清除主 LUT 失败", e)
            throw e
        }
    }
    
    /**
     * 清除第二个 LUT（当用户选择"未选择"时调用）
     */
    suspend fun clearSecondLut() = withContext(Dispatchers.IO) {
        try {
            // 清除 CPU 处理器的第二个 LUT
            cpuProcessor.clearSecondLut()
            
            // 清除 GPU 处理器的第二个 LUT
            if (isGpuAvailable) {
                gpuProcessor.clearSecondLut()
            }
            
            Log.d(TAG, "第二个 LUT 已清除")
        } catch (e: Exception) {
            Log.e(TAG, "清除第二个 LUT 失败", e)
            throw e
        }
    }

    /**
     * Release all resources
     */
    suspend fun release() {
        // Cancel all active tasks
        cancelAllTasks()

        // Close GPU channel
        gpuChannel.close()

        // Cancel GPU processing job
        gpuProcessingJob?.cancel()

        // Cancel all scopes
        managerScope.cancel()
        cpuScope.cancel()
        gpuScope.cancel()

        // Release processors
        cpuProcessor.release()
        if (isGpuAvailable) {
            gpuProcessor.release()
        }

        // 释放Native处理器资源
        nativeProcessor?.let {
            try {
                it.release()
                Log.d(TAG, "Native处理器资源已释放")
            } catch (e: Exception) {
                Log.e(TAG, "释放Native处理器资源失败", e)
            }
        }

        Log.d(TAG, "ThreadManager released")
    }

    /**
     * Processor information data class
     */
    data class ProcessorInfo(
        val cpuInfo: String,
        val gpuInfo: String,
        val preferredProcessor: ILutProcessor.ProcessorType,
        val isGpuAvailable: Boolean
    )

    /**
     * 压缩图片防止保存时OOM
     * 当图片像素数超过8亿时，压缩图片到合适尺寸
     * @param bitmap 原始图片
     * @param fileName 文件名（用于提示）
     * @param onCompressed 压缩回调（fileName, newWidth, newHeight）
     * @return 压缩后的图片（如果不需要压缩则返回原图）
     */
    suspend fun compressBitmapForSaving(
        bitmap: Bitmap,
        fileName: String = "image.jpg",
        onCompressed: ((String, Int, Int) -> Unit)? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        val currentPixels = bitmap.width.toLong() * bitmap.height.toLong()

        if (currentPixels <= MAX_SAVE_PIXELS) {
            Log.d(TAG, "图片像素数($currentPixels)在安全范围内，无需压缩")
            return@withContext bitmap
        }

        // 计算压缩比例
        val scale = sqrt(MAX_SAVE_PIXELS.toDouble() / currentPixels.toDouble())
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        Log.d(
            TAG,
            "图片像素数($currentPixels)超过限制($MAX_SAVE_PIXELS)，将压缩至${newWidth}x${newHeight}"
        )

        try {
            val compressedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            val compressedPixels =
                compressedBitmap.width.toLong() * compressedBitmap.height.toLong()
            Log.d(
                TAG,
                "图片压缩成功：${bitmap.width}x${bitmap.height}($currentPixels) -> ${compressedBitmap.width}x${compressedBitmap.height}($compressedPixels)"
            )

            // 触发压缩回调
            onCompressed?.invoke(fileName, newWidth, newHeight)

            // 如果创建了新的bitmap，且与原始的不同，则释放原始的
            if (compressedBitmap != bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }

            compressedBitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "压缩图片时发生OOM，返回原图", e)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "压缩图片失败，返回原图", e)
            bitmap
        }
    }

}