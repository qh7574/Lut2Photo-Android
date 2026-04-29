package cn.alittlecookie.lut2photo.lut2photo.core

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
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
        
        // GPU纹理尺寸缓存
        private var cachedMaxTextureSize: Int? = null
        
        // Native方法声明（用于查询GPU信息）
        @JvmStatic
        external fun nativeCreateForQuery(): Long
        @JvmStatic
        external fun nativeDestroyForQuery(handle: Long)
        @JvmStatic
        external fun nativeGetMaxTextureSize(handle: Long): Int
        
        init {
            try {
                System.loadLibrary("native_lut_processor")
            } catch (e: Exception) {
                // 忽略，可能已经加载
            }
        }
    }
    
    /**
     * 回退原因枚举
     */
    enum class FallbackReason(val message: String) {
        TEXTURE_SIZE_LIMIT("图片尺寸超过GPU纹理限制"),
        MEMORY_INSUFFICIENT("内存不足"),
        VULKAN_ERROR("Vulkan错误"),
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
        isVulkanAvailable: Boolean
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
        Log.d(TAG, "Vulkan可用: $isVulkanAvailable")
        
        // 1. 检查Vulkan基本可用性
        if (!isVulkanAvailable) {
            Log.w(TAG, "Vulkan不可用，选择CPU处理器")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "Vulkan不可用",
                fallbackReason = FallbackReason.GPU_UNAVAILABLE,
                shouldShowToast = userPreference == ILutProcessor.ProcessorType.VULKAN
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
                shouldShowToast = userPreference == ILutProcessor.ProcessorType.VULKAN
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
                    Log.i(TAG, "清理后内存足够，允许Vulkan处理")
                    return@withContext SelectionResult(
                        processorType = ILutProcessor.ProcessorType.VULKAN,
                        reason = "内存临界但清理后可用，尝试Vulkan处理"
                    )
                }
                Log.w(TAG, "内存仍然临界，允许Vulkan试跑，失败自动回退")
                return@withContext SelectionResult(
                    processorType = ILutProcessor.ProcessorType.VULKAN,
                    reason = "内存临界，尝试Vulkan处理，失败回退CPU",
                    fallbackReason = FallbackReason.MEMORY_INSUFFICIENT,
                    shouldShowToast = false
                )
            }
            Log.w(TAG, "内存不足，预估需要${estimatedMemoryMB}MB，可用${memoryCheckResult.availableMB}MB")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "内存不足(需要${estimatedMemoryMB}MB，可用${memoryCheckResult.availableMB}MB)",
                fallbackReason = FallbackReason.MEMORY_INSUFFICIENT,
                shouldShowToast = userPreference == ILutProcessor.ProcessorType.VULKAN
            )
        }
        
        // 4. 根据用户偏好选择
        return@withContext when (userPreference) {
            ILutProcessor.ProcessorType.VULKAN -> {
                Log.d(TAG, "选择Vulkan处理器")
                SelectionResult(
                    processorType = ILutProcessor.ProcessorType.VULKAN,
                    reason = "Vulkan处理器可用且图片适合Vulkan处理"
                )
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
        isVulkanAvailable: Boolean
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
        Log.d(TAG, "Vulkan可用: $isVulkanAvailable")
        
        // 1. 检查Vulkan基本可用性
        if (!isVulkanAvailable) {
            Log.w(TAG, "Vulkan不可用，选择CPU处理器")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "Vulkan不可用",
                fallbackReason = FallbackReason.GPU_UNAVAILABLE,
                shouldShowToast = userPreferenceString.uppercase() == "VULKAN"
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
                shouldShowToast = userPreferenceString.uppercase() == "VULKAN"
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
                    Log.i(TAG, "清理后内存足够，允许Vulkan处理")
                    return@withContext SelectionResult(
                        processorType = ILutProcessor.ProcessorType.VULKAN,
                        reason = "内存临界但清理后可用，尝试Vulkan处理"
                    )
                }
                Log.w(TAG, "内存仍然临界，允许Vulkan试跑，失败自动回退")
                return@withContext SelectionResult(
                    processorType = ILutProcessor.ProcessorType.VULKAN,
                    reason = "内存临界，尝试Vulkan处理，失败回退CPU",
                    fallbackReason = FallbackReason.MEMORY_INSUFFICIENT,
                    shouldShowToast = false
                )
            }
            Log.w(TAG, "内存不足，预估需要${estimatedMemoryMB}MB，可用${memoryCheckResult.availableMB}MB")
            return@withContext SelectionResult(
                processorType = ILutProcessor.ProcessorType.CPU,
                reason = "内存不足(需要${estimatedMemoryMB}MB，可用${memoryCheckResult.availableMB}MB)",
                fallbackReason = FallbackReason.MEMORY_INSUFFICIENT,
                shouldShowToast = userPreferenceString.uppercase() == "VULKAN"
            )
        }
        
        // 4. 根据用户偏好选择
        return@withContext when (userPreferenceString.uppercase()) {
            "VULKAN" -> {
                Log.d(TAG, "选择Vulkan处理器")
                SelectionResult(
                    processorType = ILutProcessor.ProcessorType.VULKAN,
                    reason = "Vulkan处理器可用且图片适合Vulkan处理"
                )
            }
            
            "CPU" -> {
                Log.d(TAG, "用户选择CPU处理器")
                SelectionResult(
                    processorType = ILutProcessor.ProcessorType.CPU,
                    reason = "用户选择CPU处理器"
                )
            }
            
            "AUTO" -> {
                // 自动模式：优先使用Vulkan
                Log.d(TAG, "自动模式：选择Vulkan")
                SelectionResult(
                    processorType = ILutProcessor.ProcessorType.VULKAN,
                    reason = "自动模式：选择Vulkan处理"
                )
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
     * 通过Vulkan获取实际硬件限制
     */
    private suspend fun getMaxTextureSize(): Int = withContext(Dispatchers.Default) {
        cachedMaxTextureSize?.let { return@withContext it }
        
        // 尝试通过Vulkan获取实际纹理尺寸
        try {
            // 调用native方法获取最大纹理尺寸
            val nativeHandle = nativeCreateForQuery()
            if (nativeHandle != 0L) {
                val maxSize = nativeGetMaxTextureSize(nativeHandle)
                nativeDestroyForQuery(nativeHandle)
                
                if (maxSize > 0) {
                    cachedMaxTextureSize = maxSize
                    Log.d(TAG, "从Vulkan获取GPU纹理尺寸: $maxSize")
                    return@withContext maxSize
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "无法从Vulkan获取纹理尺寸: ${e.message}")
        }
        
        // 回退到保守的默认值
        val defaultSize = 8192  // 大多数现代GPU支持至少8192
        cachedMaxTextureSize = defaultSize
        
        Log.d(TAG, "使用默认GPU纹理尺寸: $defaultSize")
        return@withContext defaultSize
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