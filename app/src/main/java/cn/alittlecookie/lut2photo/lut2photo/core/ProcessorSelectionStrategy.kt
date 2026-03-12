package cn.alittlecookie.lut2photo.lut2photo.core

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.os.Debug
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 智能处理器选择策略
 * 根据图片特征、设备能力和内存状况智能选择最优处理器
 */
class ProcessorSelectionStrategy(private val context: Context) {
    
    companion object {
        private const val TAG = "ProcessorSelectionStrategy"
        
        // 内存安全阈值
        private const val MEMORY_SAFETY_THRESHOLD_MB = 150L
        private const val MEMORY_CRITICAL_THRESHOLD_MB = 200L

        // 内存边界策略
        private const val BORDERLINE_MARGIN_MB = 64L
        private const val BORDERLINE_RATIO = 0.05f
        
        // 像素数阈值
        private const val SAFE_GPU_PIXELS = 30_000_000L // 3000万像素
        private const val MAX_CPU_PIXELS = 50_000_000L  // 5000万像素
        
        // GPU纹理尺寸缓存
        private var cachedMaxTextureSize: Int? = null
    }
    
    /**
     * 回退原因枚举
     */
    enum class FallbackReason(val message: String) {
        TEXTURE_SIZE_LIMIT("图片尺寸超过GPU纹理限制"),
        MEMORY_INSUFFICIENT("内存不足"),
        OPENGL_ERROR("OpenGL错误"),
        PIXEL_COUNT_LIMIT("像素数量过大"),
        GPU_UNAVAILABLE("GPU不可用"),
        DEVICE_PERFORMANCE("设备性能限制")
    }
    
    /**
     * 处理器选择结果
     */
    data class SelectionResult(
        val processorType: ILutProcessor.ProcessorType,
        val reason: String,
        val fallbackReason: FallbackReason? = null,
        val shouldShowToast: Boolean = false
    )
    
    /**
     * 智能选择处理器
     */
    suspend fun selectOptimalProcessor(
        bitmap: Bitmap,
        userPreference: ILutProcessor.ProcessorType,
        isGpuAvailable: Boolean
    ): SelectionResult = withContext(Dispatchers.Default) {
        
        val width = bitmap.width
        val height = bitmap.height
        val pixels = width.toLong() * height
        val maxDimension = maxOf(width, height)
        
        Log.d(TAG, "========== 智能处理器选择 ==========")
        Log.d(TAG, "图片尺寸: ${width}x${height}")
        Log.d(TAG, "像素总数: $pixels")
        Log.d(TAG, "最大边长: $maxDimension")
        Log.d(TAG, "用户偏好: $userPreference")
        Log.d(TAG, "GPU可用: $isGpuAvailable")
        
        // 1. 检查GPU基本可用性
        if (!isGpuAvailable) {
            Log.w(TAG, "GPU不可用，选择CPU处理器")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "GPU不可用",
                fallbackReason = FallbackReason.GPU_UNAVAILABLE,
                shouldShowToast = userPreference == ILutProcessor.ProcessorType.GPU
            )
        }
        
        // 2. 检查GPU纹理尺寸限制
        val maxTextureSize = getMaxTextureSize()
        if (maxDimension > maxTextureSize) {
            Log.w(TAG, "图片尺寸超过GPU纹理限制: $maxDimension > $maxTextureSize")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "图片尺寸超过GPU纹理限制($maxDimension > $maxTextureSize)",
                fallbackReason = FallbackReason.TEXTURE_SIZE_LIMIT,
                shouldShowToast = userPreference == ILutProcessor.ProcessorType.GPU
            )
        }
        
        // 3. 检查内存可用性
        val estimatedMemoryMB = estimateMemoryUsage(width, height)
        val memoryCheckResult = checkMemoryAvailability(estimatedMemoryMB, pixels)
        
        if (!memoryCheckResult.isSafe) {
            if (memoryCheckResult.isBorderline) {
                Log.w(TAG, "内存临界，尝试清理后再评估")
                aggressiveMemoryCleanup()
                val recheck = checkMemoryAvailability(estimatedMemoryMB, pixels)
                if (recheck.isSafe) {
                    Log.i(TAG, "清理后内存足够，允许GPU处理")
                    return@withContext SelectionResult(
                        processorType = ILutProcessor.ProcessorType.GPU,
                        reason = "内存临界但清理后可用，尝试GPU处理"
                    )
                }
                Log.w(TAG, "内存仍然临界，允许GPU试跑，失败自动回退")
                return@withContext SelectionResult(
                    processorType = ILutProcessor.ProcessorType.GPU,
                    reason = "内存临界，尝试GPU处理，失败回退CPU",
                    fallbackReason = FallbackReason.MEMORY_INSUFFICIENT,
                    shouldShowToast = false
                )
            }
            Log.w(TAG, "内存不足，预估需要${estimatedMemoryMB}MB，可用${memoryCheckResult.availableMB}MB")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "内存不足(需要${estimatedMemoryMB}MB，可用${memoryCheckResult.availableMB}MB)",
                fallbackReason = FallbackReason.MEMORY_INSUFFICIENT,
                shouldShowToast = userPreference == ILutProcessor.ProcessorType.GPU
            )
        }
        
        // 4. 根据像素数量和用户偏好选择
        return@withContext when (userPreference) {
            ILutProcessor.ProcessorType.GPU -> {
                if (pixels > SAFE_GPU_PIXELS) {
                    Log.w(TAG, "像素数量过大($pixels > $SAFE_GPU_PIXELS)，建议使用CPU")
                    SelectionResult(
                        processorType = ILutProcessor.ProcessorType.CPU,
                        reason = "像素数量过大，为避免OOM使用CPU处理",
                        fallbackReason = FallbackReason.PIXEL_COUNT_LIMIT,
                        shouldShowToast = true
                    )
                } else {
                    Log.d(TAG, "选择GPU处理器")
                    SelectionResult(
                        processorType = ILutProcessor.ProcessorType.GPU,
                        reason = "GPU处理器可用且图片适合GPU处理"
                    )
                }
            }
            
            ILutProcessor.ProcessorType.CPU -> {
                Log.d(TAG, "用户选择CPU处理器")
                SelectionResult(
                    processorType = ILutProcessor.ProcessorType.CPU,
                    reason = "用户选择CPU处理器"
                )
            }
        }
    }
    
    /**
     * 智能选择处理器（支持AUTO模式的重载方法）
     */
    suspend fun selectOptimalProcessor(
        bitmap: Bitmap,
        userPreferenceString: String,
        isGpuAvailable: Boolean
    ): SelectionResult = withContext(Dispatchers.Default) {
        
        val width = bitmap.width
        val height = bitmap.height
        val pixels = width.toLong() * height
        val maxDimension = maxOf(width, height)
        
        Log.d(TAG, "========== 智能处理器选择 (字符串模式) ==========")
        Log.d(TAG, "图片尺寸: ${width}x${height}")
        Log.d(TAG, "像素总数: $pixels")
        Log.d(TAG, "最大边长: $maxDimension")
        Log.d(TAG, "用户偏好: $userPreferenceString")
        Log.d(TAG, "GPU可用: $isGpuAvailable")
        
        // 1. 检查GPU基本可用性
        if (!isGpuAvailable) {
            Log.w(TAG, "GPU不可用，选择CPU处理器")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "GPU不可用",
                fallbackReason = FallbackReason.GPU_UNAVAILABLE,
                shouldShowToast = userPreferenceString.uppercase() == "GPU"
            )
        }
        
        // 2. 检查GPU纹理尺寸限制
        val maxTextureSize = getMaxTextureSize()
        if (maxDimension > maxTextureSize) {
            Log.w(TAG, "图片尺寸超过GPU纹理限制: $maxDimension > $maxTextureSize")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "图片尺寸超过GPU纹理限制($maxDimension > $maxTextureSize)",
                fallbackReason = FallbackReason.TEXTURE_SIZE_LIMIT,
                shouldShowToast = userPreferenceString.uppercase() == "GPU"
            )
        }
        
        // 3. 检查内存可用性
        val estimatedMemoryMB = estimateMemoryUsage(width, height)
        val memoryCheckResult = checkMemoryAvailability(estimatedMemoryMB, pixels)
        
        if (!memoryCheckResult.isSafe) {
            if (memoryCheckResult.isBorderline) {
                Log.w(TAG, "内存临界，尝试清理后再评估")
                aggressiveMemoryCleanup()
                val recheck = checkMemoryAvailability(estimatedMemoryMB, pixels)
                if (recheck.isSafe) {
                    Log.i(TAG, "清理后内存足够，允许GPU处理")
                    return@withContext SelectionResult(
                        processorType = ILutProcessor.ProcessorType.GPU,
                        reason = "内存临界但清理后可用，尝试GPU处理"
                    )
                }
                Log.w(TAG, "内存仍然临界，允许GPU试跑，失败自动回退")
                return@withContext SelectionResult(
                    processorType = ILutProcessor.ProcessorType.GPU,
                    reason = "内存临界，尝试GPU处理，失败回退CPU",
                    fallbackReason = FallbackReason.MEMORY_INSUFFICIENT,
                    shouldShowToast = false
                )
            }
            Log.w(TAG, "内存不足，预估需要${estimatedMemoryMB}MB，可用${memoryCheckResult.availableMB}MB")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "内存不足(需要${estimatedMemoryMB}MB，可用${memoryCheckResult.availableMB}MB)",
                fallbackReason = FallbackReason.MEMORY_INSUFFICIENT,
                shouldShowToast = userPreferenceString.uppercase() == "GPU"
            )
        }
        
        // 4. 根据像素数量和用户偏好选择
        return@withContext when (userPreferenceString.uppercase()) {
            "GPU" -> {
                if (pixels > SAFE_GPU_PIXELS) {
                    Log.w(TAG, "像素数量过大($pixels > $SAFE_GPU_PIXELS)，强制回退到CPU")
                    SelectionResult(
                        processorType = ILutProcessor.ProcessorType.CPU,
                        reason = "像素数量过大，为避免OOM使用CPU处理",
                        fallbackReason = FallbackReason.PIXEL_COUNT_LIMIT,
                        shouldShowToast = true  // 用户明确选择GPU但被强制回退，需要提示
                    )
                } else {
                    Log.d(TAG, "选择GPU处理器")
                    SelectionResult(
                        processorType = ILutProcessor.ProcessorType.GPU,
                        reason = "GPU处理器可用且图片适合GPU处理"
                    )
                }
            }
            
            "CPU" -> {
                Log.d(TAG, "用户选择CPU处理器")
                SelectionResult(
                    processorType = ILutProcessor.ProcessorType.CPU,
                    reason = "用户选择CPU处理器"
                )
            }
            
            "AUTO" -> {
                if (pixels > SAFE_GPU_PIXELS) {
                    Log.d(TAG, "自动模式：像素数量大，选择CPU")
                    SelectionResult(
                        processorType = ILutProcessor.ProcessorType.CPU,
                        reason = "自动模式：像素数量大，选择CPU处理",
                        fallbackReason = FallbackReason.PIXEL_COUNT_LIMIT,
                        shouldShowToast = false  // 自动模式不需要提示用户
                    )
                } else {
                    Log.d(TAG, "自动模式：选择GPU")
                    SelectionResult(
                        processorType = ILutProcessor.ProcessorType.GPU,
                        reason = "自动模式：选择GPU处理"
                    )
                }
            }
            
            else -> {
                Log.w(TAG, "未知的处理器类型: $userPreferenceString，默认选择CPU")
                SelectionResult(
                    processorType = ILutProcessor.ProcessorType.CPU,
                    reason = "未知的处理器类型，默认选择CPU"
                )
            }
        }
    }
    
    /**
     * 获取GPU最大纹理尺寸
     */
    private suspend fun getMaxTextureSize(): Int = withContext(Dispatchers.Main) {
        cachedMaxTextureSize?.let { return@withContext it }
        
        return@withContext try {
            val maxTextureSizeArray = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSizeArray, 0)
            val maxSize = maxTextureSizeArray[0]
            
            // 使用保守值，确保兼容性
            val safeSize = minOf(maxSize, 8192)
            cachedMaxTextureSize = safeSize
            
            Log.d(TAG, "GPU最大纹理尺寸: $maxSize, 使用安全值: $safeSize")
            safeSize
        } catch (e: Exception) {
            Log.w(TAG, "无法获取GPU纹理尺寸，使用默认值4096", e)
            val defaultSize = 4096
            cachedMaxTextureSize = defaultSize
            defaultSize
        }
    }
    
    /**
     * 估算内存使用量（MB）
     */
    private fun estimateMemoryUsage(width: Int, height: Int): Long {
        val pixels = width.toLong() * height
        // ARGB_8888 = 4字节/像素，GPU处理需要额外的缓冲区
        val bitmapMemoryMB = (pixels * 4) / (1024 * 1024)
        val bufferMemoryMB = (pixels * 4) / (1024 * 1024) // ByteBuffer
        val totalMemoryMB = bitmapMemoryMB + bufferMemoryMB + 50 // 额外开销
        
        Log.d(TAG, "内存估算: Bitmap=${bitmapMemoryMB}MB, Buffer=${bufferMemoryMB}MB, 总计=${totalMemoryMB}MB")
        return totalMemoryMB
    }
    
    /**
     * 内存检查结果
     */
    data class MemoryCheckResult(
        val isSafe: Boolean,
        val availableMB: Long,
        val usedMB: Long,
        val maxMB: Long,
        val requiredWithBufferMB: Long,
        val totalPssMB: Long,
        val systemAvailableMB: Long,
        val heapAvailableMB: Long,
        val isBorderline: Boolean
    )
    
    /**
     * 检查内存可用性
     */
    private fun checkMemoryAvailability(requiredMB: Long, pixels: Long): MemoryCheckResult {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory

        val maxMB = maxMemory / (1024 * 1024)
        val usedMB = usedMemory / (1024 * 1024)
        val heapAvailableMB = availableMemory / (1024 * 1024)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val systemAvailableMB = memoryInfo.availMem / (1024 * 1024)

        val debugMemoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemoryInfo)
        val totalPssMB = debugMemoryInfo.totalPss / 1024L

        val safetyMultiplier = when {
            pixels > 40_000_000L -> 1.5
            pixels > 25_000_000L -> 1.35
            pixels > 15_000_000L -> 1.25
            else -> 1.2
        }

        // 允许一定比例的系统可回收内存参与判断，避免过度保守
        val extraFromSystem = (systemAvailableMB - heapAvailableMB).coerceAtLeast(0) * 0.7
        val effectiveAvailableMB = (heapAvailableMB + extraFromSystem).toLong().coerceAtMost(systemAvailableMB)

        val requiredWithBuffer = (requiredMB * safetyMultiplier).toLong()
        val isSafe = effectiveAvailableMB > requiredWithBuffer

        val delta = requiredWithBuffer - effectiveAvailableMB
        val isBorderline = !isSafe && (delta <= BORDERLINE_MARGIN_MB || (delta.toDouble() / requiredWithBuffer.toDouble()) <= BORDERLINE_RATIO)

        Log.d(TAG, "内存状态: Heap可用=${heapAvailableMB}MB, 系统可用=${systemAvailableMB}MB, PSS=${totalPssMB}MB, 有效可用=${effectiveAvailableMB}MB")
        Log.d(TAG, "内存需求: 预估=${requiredMB}MB, 缓冲倍率=${"%.2f".format(safetyMultiplier)}, 含缓冲=${requiredWithBuffer}MB, 安全=${isSafe}, 临界=${isBorderline}")

        return MemoryCheckResult(
            isSafe = isSafe,
            availableMB = effectiveAvailableMB,
            usedMB = usedMB,
            maxMB = maxMB,
            requiredWithBufferMB = requiredWithBuffer,
            totalPssMB = totalPssMB,
            systemAvailableMB = systemAvailableMB,
            heapAvailableMB = heapAvailableMB,
            isBorderline = isBorderline
        )
    }
    
    /**
     * 显示回退提示Toast
     */
    suspend fun showFallbackToast(fallbackReason: FallbackReason) {
        withContext(Dispatchers.Main) {
            val message = "由于${fallbackReason.message}，回退到CPU处理"
            Log.i(TAG, "准备显示Toast: $message")
            try {
                // 确保Context有效
                if (context is android.app.Activity) {
                    val activity = context as android.app.Activity
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        Log.i(TAG, "Toast显示成功: $message")
                    } else {
                        Log.w(TAG, "Activity已销毁，无法显示Toast: $message")
                    }
                } else {
                    // 对于非Activity的Context（如Application Context），直接显示
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    Log.i(TAG, "Toast显示成功（非Activity Context）: $message")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Toast显示失败: $message", e)
            }
        }
    }
    
    /**
     * 积极的内存清理
     */
    suspend fun aggressiveMemoryCleanup() = withContext(Dispatchers.Default) {
        Log.d(TAG, "开始积极内存清理")
        
        // 强制GC
        System.gc()
        System.runFinalization()
        
        // 等待GC完成
        kotlinx.coroutines.delay(100)
        
        // 再次GC确保清理完成
        System.gc()
        
        Log.d(TAG, "积极内存清理完成")
    }
}