package cn.alittlecookie.lut2photo.lut2photo.core

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native LUT处理器
 * 使用JNI调用C++代码进行图片处理，突破Java堆内存限制
 */
class NativeLutProcessor : ILutProcessor {

    companion object {
        private const val TAG = "NativeLutProcessor"

        // 加载Native库
        init {
            try {
                Log.i(TAG, "开始加载Native库: native_lut_processor")
                System.loadLibrary("native_lut_processor")
                Log.i(TAG, "Native库加载成功")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native库加载失败 - 详细错误信息:", e)
                Log.e(TAG, "错误消息: ${e.message}")
                Log.e(TAG, "可能的原因:")
                Log.e(TAG, "1. Native库未正确编译")
                Log.e(TAG, "2. 目标架构不匹配")
                Log.e(TAG, "3. 依赖库缺失")
                Log.e(TAG, "4. 库文件路径错误")
                throw RuntimeException("无法加载Native LUT处理器库: ${e.message}", e)
            }
        }

        // 错误码常量
        private const val SUCCESS = 0
        private const val ERROR_INVALID_BITMAP = -1
        private const val ERROR_MEMORY_ALLOCATION = -2
        private const val ERROR_LUT_NOT_LOADED = -3
        private const val ERROR_PROCESSING_FAILED = -4
        private const val ERROR_INVALID_PARAMETERS = -5
    }

    // Native实例句柄
    private var nativeHandle: Long = 0
    private var isInitialized = false

    init {
        initialize()
    }

    /**
     * 初始化Native处理器
     */
    private fun initialize() {
        try {
            nativeHandle = nativeCreate()
            if (nativeHandle != 0L) {
                isInitialized = true
                Log.d(TAG, "Native处理器初始化成功，句柄: $nativeHandle")
            } else {
                throw RuntimeException("Native处理器创建失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Native处理器初始化失败", e)
            throw e
        }
    }

    override fun getProcessorType(): ILutProcessor.ProcessorType {
        return ILutProcessor.ProcessorType.CPU // Native处理器归类为CPU类型
    }

    override suspend fun isAvailable(): Boolean {
        return isInitialized && nativeHandle != 0L
    }

    override suspend fun loadCubeLut(inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized) {
                    Log.e(TAG, "Native处理器未初始化")
                    return@withContext false
                }

                // 读取LUT数据
                val lutData = parseCubeLut(inputStream)
                if (lutData.isEmpty()) {
                    Log.e(TAG, "LUT数据解析失败")
                    return@withContext false
                }

                // 计算LUT尺寸
                val lutSize = Math.round(Math.cbrt(lutData.size / 3.0)).toInt()
                Log.d(
                    TAG,
                    "加载LUT，尺寸: ${lutSize}x${lutSize}x${lutSize}, 数据点: ${lutData.size}"
                )

                // 调用Native方法加载LUT
                val result = nativeLoadLut(nativeHandle, lutData.toFloatArray(), lutSize)

                when (result) {
                    SUCCESS -> {
                        Log.i(TAG, "LUT加载成功")
                        true
                    }

                    ERROR_INVALID_PARAMETERS -> {
                        Log.e(TAG, "LUT参数无效")
                        false
                    }

                    ERROR_MEMORY_ALLOCATION -> {
                        Log.e(TAG, "Native内存分配失败")
                        false
                    }

                    else -> {
                        Log.e(TAG, "LUT加载失败，错误码: $result")
                        false
                    }
                }
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
        return withContext(Dispatchers.Default) {
            try {
                if (!isInitialized) {
                    Log.e(TAG, "Native处理器未初始化")
                    return@withContext null
                }

                if (bitmap.isRecycled) {
                    Log.e(TAG, "输入Bitmap已被回收")
                    return@withContext null
                }

                Log.d(TAG, "开始Native处理，图片尺寸: ${bitmap.width}x${bitmap.height}")

                // 创建输出Bitmap
                val outputBitmap = Bitmap.createBitmap(
                    bitmap.width,
                    bitmap.height,
                    Bitmap.Config.ARGB_8888
                )

                // 调用Native处理方法
                val result = nativeProcessBitmap(
                    nativeHandle,
                    bitmap,
                    outputBitmap,
                    params.strength,
                    params.lut2Strength,
                    params.quality,
                    params.ditherType.ordinal,
                    true // 使用多线程
                )

                when (result) {
                    SUCCESS -> {
                        Log.d(TAG, "Native处理成功")
                        outputBitmap
                    }

                    ERROR_INVALID_BITMAP -> {
                        Log.e(TAG, "无效的Bitmap")
                        outputBitmap.recycle()
                        null
                    }

                    ERROR_LUT_NOT_LOADED -> {
                        Log.e(TAG, "LUT未加载")
                        outputBitmap.recycle()
                        null
                    }

                    ERROR_MEMORY_ALLOCATION -> {
                        Log.e(TAG, "Native内存分配失败")
                        outputBitmap.recycle()
                        null
                    }

                    ERROR_PROCESSING_FAILED -> {
                        Log.e(TAG, "Native处理失败")
                        outputBitmap.recycle()
                        null
                    }

                    else -> {
                        Log.e(TAG, "未知错误，错误码: $result")
                        outputBitmap.recycle()
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Native处理时发生异常", e)
                null
            }
        }
    }

    override suspend fun release() {
        withContext(Dispatchers.IO) {
            try {
                if (isInitialized && nativeHandle != 0L) {
                    nativeDestroy(nativeHandle)
                    nativeHandle = 0L
                    isInitialized = false
                    Log.d(TAG, "Native处理器已释放")
                }
            } catch (e: Exception) {
                Log.e(TAG, "释放Native处理器时发生异常", e)
            }
        }
    }

    override fun getProcessorInfo(): String {
        val memoryUsage = if (isInitialized) {
            nativeGetMemoryUsage(nativeHandle)
        } else {
            0L
        }

        val memoryStats = if (isInitialized) {
            nativeGetMemoryStats(nativeHandle)
        } else {
            "未初始化"
        }

        return "Native LUT Processor (优化版)\n" +
                "状态: ${if (isInitialized) "已初始化" else "未初始化"}\n" +
                "句柄: $nativeHandle\n" +
                "Native内存使用: ${memoryUsage / 1024 / 1024}MB\n" +
                "内存统计: $memoryStats\n" +
                "特性: SIMD优化, 多线程处理, Native内存管理"
    }

    /**
     * 设置Native内存限制
     * @param limitMB 内存限制（MB）
     */
    fun setMemoryLimit(limitMB: Int) {
        if (isInitialized) {
            nativeSetMemoryLimit(nativeHandle, limitMB.toLong() * 1024 * 1024)
            Log.d(TAG, "设置Native内存限制: ${limitMB}MB")
        }
    }

    /**
     * 检查是否接近内存限制
     * @param threshold 阈值（0.0-1.0）
     * @return 是否接近限制
     */
    fun isNearMemoryLimit(threshold: Float = 0.9f): Boolean {
        return if (isInitialized) {
            nativeIsNearMemoryLimit(nativeHandle, threshold)
        } else {
            false
        }
    }

    /**
     * 强制执行垃圾回收
     */
    fun forceGarbageCollection() {
        if (isInitialized) {
            nativeForceGC(nativeHandle)
            System.gc() // 同时执行Java GC
            Log.d(TAG, "执行强制垃圾回收")
        }
    }

    /**
     * 强制Native垃圾回收
     */
    fun forceNativeGarbageCollection() {
        if (isInitialized) {
            nativeForceGC(nativeHandle)
        }
    }

    /**
     * 获取Native内存使用量
     */
    fun getNativeMemoryUsage(): Long {
        return if (isInitialized) {
            nativeGetMemoryUsage(nativeHandle)
        } else {
            0L
        }
    }

    /**
     * 解析Cube LUT文件
     */
    private fun parseCubeLut(inputStream: InputStream): List<Float> {
        val lutData = mutableListOf<Float>()

        try {
            inputStream.bufferedReader().use { reader ->
                var lutSize = 0

                reader.forEachLine { line ->
                    val trimmedLine = line.trim()

                    // 跳过注释和空行
                    if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                        return@forEachLine
                    }

                    // 解析LUT_3D_SIZE
                    if (trimmedLine.startsWith("LUT_3D_SIZE")) {
                        lutSize = trimmedLine.split("\\s+".toRegex())[1].toInt()
                        Log.d(TAG, "检测到LUT尺寸: $lutSize")
                        return@forEachLine
                    }

                    // 解析RGB数据
                    if (trimmedLine.matches("^[0-9.\\s-]+$".toRegex())) {
                        val values = trimmedLine.split("\\s+".toRegex())
                            .filter { it.isNotEmpty() }
                            .map { it.toFloat() }

                        if (values.size == 3) {
                            lutData.addAll(values)
                        }
                    }
                }
            }

            Log.d(TAG, "LUT解析完成，数据点数: ${lutData.size}")
        } catch (e: Exception) {
            Log.e(TAG, "解析LUT文件失败", e)
            return emptyList()
        }

        return lutData
    }

    // Native方法声明
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoadLut(handle: Long, lutData: FloatArray, lutSize: Int): Int
    private external fun nativeProcessBitmap(
        handle: Long,
        inputBitmap: Bitmap,
        outputBitmap: Bitmap,
        strength: Float,
        lut2Strength: Float,
        quality: Int,
        ditherType: Int,
        useMultiThreading: Boolean
    ): Int

    external fun nativeGetMemoryUsage(handle: Long): Long
    private external fun nativeForceGC(handle: Long)

    // 新增的Native内存管理方法
    external fun nativeGetMemoryStats(handle: Long): String
    external fun nativeSetMemoryLimit(handle: Long, limitBytes: Long)
    external fun nativeIsNearMemoryLimit(handle: Long, threshold: Float): Boolean

    // 增强版处理器接口
    external fun nativeCreateEnhanced(): Long
    external fun nativeDestroyEnhanced(handle: Long)
    external fun nativeLoadLutEnhanced(handle: Long, lutData: FloatArray, lutSize: Int): Int
    external fun nativeProcessBitmapEnhanced(
        handle: Long,
        inputBitmap: Bitmap,
        outputBitmap: Bitmap,
        strength: Float,
        lut2Strength: Float,
        quality: Int,
        ditherType: Int,
        useMultiThreading: Boolean
    ): Int

    // 批量处理接口
    external fun nativeProcessBitmapBatch(
        handle: Long,
        inputBitmaps: Array<Bitmap>,
        outputBitmaps: Array<Bitmap>,
        strength: Float,
        lut2Strength: Float,
        quality: Int,
        ditherType: Int,
        useMultiThreading: Boolean
    ): IntArray

    // 异步处理接口
    external fun nativeProcessBitmapAsync(
        handle: Long,
        inputBitmap: Bitmap,
        outputBitmap: Bitmap,
        strength: Float,
        lut2Strength: Float,
        quality: Int,
        ditherType: Int,
        useMultiThreading: Boolean
    ): Int

    // 流式处理接口
    external fun nativeStartStreamProcessing(handle: Long): Int
    external fun nativeStopStreamProcessing(handle: Long): Int
    external fun nativeProcessStreamFrame(
        handle: Long,
        inputBitmap: Bitmap,
        outputBitmap: Bitmap,
        strength: Float
    ): Int

    // 全局组件管理接口
    external fun nativeInitializeGlobalComponents(memoryLimitMB: Int): Int
    external fun nativeCleanupGlobalComponents(): Int

    // 内存优化接口
    external fun nativeOptimizeMemory(handle: Long): Int
    external fun nativeOptimizeMemory(): Int
    external fun nativeSetMemoryConfig(
        handle: Long,
        maxMemoryMB: Int,
        enablePooling: Boolean,
        enableCompression: Boolean
    ): Int

    // 性能测试接口
    external fun nativeRunPerformanceTest(
        testType: Int,
        iterations: Int,
        imageWidth: Int,
        imageHeight: Int
    ): String

    external fun nativeGetPerformanceStats(handle: Long): String

    /**
     * 析构函数，确保资源释放
     */
    protected fun finalize() {
        if (isInitialized) {
            Log.w(TAG, "Native处理器未正确释放，在finalize中清理")
            try {
                nativeDestroy(nativeHandle)
            } catch (e: Exception) {
                Log.e(TAG, "finalize中释放Native资源失败", e)
            }
        }
    }
}