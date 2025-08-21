package cn.alittlecookie.lut2photo.lut2photo.core

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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread manager for handling CPU parallel and GPU serial processing
 * CPU: Maximum 5 concurrent image processing tasks
 * GPU: Serial processing (one at a time)
 */
class ThreadManager(context: Context) {
    companion object {
        private const val TAG = "ThreadManager"
        private const val MAX_CPU_CONCURRENT = 5
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

    // Preferred processor type
    private var preferredProcessor = ILutProcessor.ProcessorType.CPU
    private var isGpuAvailable = false

    // PreferencesManager for reading user settings
    private val preferencesManager = PreferencesManager(context)

    // CPU processing semaphore (max 5 concurrent)
    private val cpuSemaphore = Semaphore(MAX_CPU_CONCURRENT)

    // GPU processing channel (serial processing)
    private val gpuChannel = Channel<ProcessingTask>(Channel.UNLIMITED)

    // Active tasks tracking
    private val activeTasks = ConcurrentHashMap<String, Job>()
    private val taskCounter = AtomicInteger(0)

    // Coroutine scopes
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val cpuScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gpuScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // GPU processing job
    private var gpuProcessingJob: Job? = null

    init {
        initializeProcessors()
    }

    private fun initializeProcessors() {
        Log.d(TAG, "开始初始化处理器")

        // 添加：立即进行一次同步配置，确保有默认设置
        configureProcessorFromSettings()

        // 添加：立即启动GPU处理循环
        startGpuProcessingLoop()

        // 在协程中异步检查GPU可用性并重新配置
        managerScope.launch {
            try {
                Log.d(TAG, "检查GPU可用性")
                val gpuAvailable = gpuProcessor.isAvailable()
                Log.d(TAG, "GPU可用性检查结果: $gpuAvailable")

                // 修复：正确更新isGpuAvailable变量
                isGpuAvailable = gpuAvailable

                if (!gpuAvailable) {
                    Log.w(TAG, "GPU不可用，回退到CPU处理器")
                    preferredProcessor = ILutProcessor.ProcessorType.CPU
                }

                // 重新根据用户设置配置处理器
                configureProcessorFromSettings()

            } catch (e: Exception) {
                Log.e(TAG, "初始化处理器时发生错误", e)
                isGpuAvailable = false  // 修复：确保错误时也更新变量
                preferredProcessor = ILutProcessor.ProcessorType.CPU
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
        configureProcessorFromSettings()
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
                val result = cpuProcessor.processImage(task.bitmap, task.params)
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

}