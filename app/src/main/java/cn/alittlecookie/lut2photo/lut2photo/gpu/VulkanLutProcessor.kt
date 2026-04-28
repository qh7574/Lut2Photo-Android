package cn.alittlecookie.lut2photo.lut2photo.gpu

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import cn.alittlecookie.lut2photo.lut2photo.core.CpuLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor
import cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vulkan LUT处理器
 * 使用Vulkan计算着色器进行GPU加速图像处理
 */
class VulkanLutProcessor(private val context: Context) : ILutProcessor {

    companion object {
        private const val TAG = "VulkanLutProcessor"

        init {
            try {
                Log.i(TAG, "Loading native Vulkan library...")
                System.loadLibrary("native_lut_processor")
                Log.i(TAG, "Native Vulkan library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native Vulkan library", e)
            }
        }
    }

    // Native方法声明
    private external fun nativeIsVulkanAvailable(): Boolean
    private external fun nativeCreate(assetManager: android.content.res.AssetManager): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeGetDeviceInfo(handle: Long): String
    private external fun nativeGetMaxTextureSize(handle: Long): Int
    private external fun nativeLoadLut(
        handle: Long,
        lutData: FloatArray,
        lutSize: Int,
        isSecondLut: Boolean
    ): Boolean
    private external fun nativeProcessImage(
        handle: Long,
        inputBitmap: Bitmap,
        outputBitmap: Bitmap,
        lutStrength: Float,
        lut2Strength: Float,
        ditherType: Int,
        grainEnabled: Boolean,
        grainStrength: Float,
        grainSize: Float,
        grainSeed: Float
    ): Boolean
    private external fun nativeRelease(handle: Long)

    // 处理器状态
    private var nativeHandle: Long = 0
    private var isInitialized = false
    private var isVulkanSupported = false

    // CPU处理器作为回退
    private val cpuProcessor = CpuLutProcessor()

    // LUT数据
    private var currentLut: Array<Array<Array<FloatArray>>>? = null
    private var currentLutSize: Int = 0
    private var currentLut2: Array<Array<Array<FloatArray>>>? = null
    private var currentLut2Size: Int = 0

    // 胶片颗粒配置
    private var currentGrainConfig: FilmGrainConfig? = null

    override fun getProcessorType(): ILutProcessor.ProcessorType {
        return ILutProcessor.ProcessorType.VULKAN
    }

    override suspend fun isAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isVulkanSupported) {
                    isVulkanSupported = nativeIsVulkanAvailable()
                    Log.d(TAG, "Vulkan support check: $isVulkanSupported")
                }
                isVulkanSupported
            } catch (e: Exception) {
                Log.e(TAG, "Error checking Vulkan availability", e)
                false
            }
        }
    }

    override suspend fun loadCubeLut(inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 使用CPU处理器加载LUT数据
                val success = cpuProcessor.loadCubeLut(inputStream)
                if (!success) {
                    Log.e(TAG, "Failed to load LUT with CPU processor")
                    return@withContext false
                }

                // 获取LUT数据
                currentLut = cpuProcessor.getLutData()
                currentLutSize = cpuProcessor.getLutSize()

                // 上传到Vulkan
                if (isInitialized && currentLut != null) {
                    uploadLutToVulkan(currentLut!!, currentLutSize, false)
                }

                Log.d(TAG, "LUT loaded successfully, size: $currentLutSize")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error loading LUT", e)
                false
            }
        }
    }

    /**
     * 加载第二个LUT文件
     */
    suspend fun loadSecondCubeLut(inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val success = cpuProcessor.loadSecondCubeLut(inputStream)
                if (!success) {
                    Log.e(TAG, "Failed to load second LUT")
                    return@withContext false
                }

                currentLut2 = cpuProcessor.getSecondLutData()
                // 从LUT数据计算尺寸
                currentLut2Size = if (currentLut2 != null) {
                    currentLut2!!.size
                } else {
                    0
                }

                // 上传到Vulkan
                if (isInitialized && currentLut2 != null) {
                    uploadLutToVulkan(currentLut2!!, currentLut2Size, true)
                }

                Log.d(TAG, "Second LUT loaded successfully, size: $currentLut2Size")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error loading second LUT", e)
                false
            }
        }
    }

    /**
     * 上传LUT数据到Vulkan
     */
    private fun uploadLutToVulkan(
        lutData: Array<Array<Array<FloatArray>>>,
        lutSize: Int,
        isSecondLut: Boolean
    ) {
        try {
            // 转换LUT数据为一维数组
            val flatData = FloatArray(lutSize * lutSize * lutSize * 3)
            var index = 0

            for (b in 0 until lutSize) {
                for (g in 0 until lutSize) {
                    for (r in 0 until lutSize) {
                        val rgb = lutData[r][g][b]
                        flatData[index++] = rgb[0]
                        flatData[index++] = rgb[1]
                        flatData[index++] = rgb[2]
                    }
                }
            }

            // 调用Native方法上传
            val success = nativeLoadLut(nativeHandle, flatData, lutSize, isSecondLut)
            if (!success) {
                Log.e(TAG, "Failed to upload LUT to Vulkan")
            } else {
                Log.d(TAG, "LUT uploaded to Vulkan successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading LUT to Vulkan", e)
        }
    }

    /**
     * 设置胶片颗粒配置
     */
    fun setFilmGrainConfig(config: FilmGrainConfig?) {
        currentGrainConfig = config
        Log.d(TAG, "Film grain config set: ${config?.isEnabled}")
    }

    override suspend fun processImage(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? {
        Log.d(TAG, "Processing image: ${bitmap.width}x${bitmap.height}")

        // 检查是否需要处理
        val hasLut = currentLut != null || currentLut2 != null
        val hasGrain = currentGrainConfig?.isEnabled == true &&
                (currentGrainConfig?.globalStrength ?: 0f) > 0f

        if (!hasLut && !hasGrain) {
            Log.d(TAG, "No processing needed, returning copy")
            return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        }

        // 尝试初始化Vulkan
        if (!isInitialized) {
            initializeVulkan()
        }

        // 如果Vulkan可用，尝试使用Vulkan处理
        if (isInitialized && nativeHandle != 0L) {
            try {
                // 创建输出Bitmap
                val outputBitmap = Bitmap.createBitmap(
                    bitmap.width, bitmap.height,
                    Bitmap.Config.ARGB_8888
                )

                // 准备颗粒参数
                val grainConfig = currentGrainConfig
                val grainEnabled = grainConfig?.isEnabled == true
                val grainStrength = grainConfig?.globalStrength ?: 0f
                val grainSize = grainConfig?.grainSize ?: 1f
                val grainSeed = (System.currentTimeMillis() % 1000).toFloat() / 1000f

                // 当LUT为空时，传递强度0
                val lut1Strength = if (currentLut != null) params.strength else 0f
                val lut2Strength = if (currentLut2 != null) params.lut2Strength else 0f

                Log.d(TAG, "LUT1 strength: $lut1Strength (hasLut1: ${currentLut != null})")
                Log.d(TAG, "LUT2 strength: $lut2Strength (hasLut2: ${currentLut2 != null})")

                val success = nativeProcessImage(
                    nativeHandle,
                    bitmap,
                    outputBitmap,
                    lut1Strength,
                    lut2Strength,
                    params.ditherType.ordinal,
                    grainEnabled,
                    grainStrength,
                    grainSize,
                    grainSeed
                )

                if (success) {
                    Log.d(TAG, "Vulkan processing successful")
                    return outputBitmap
                } else {
                    outputBitmap.recycle()
                    Log.w(TAG, "Vulkan processing failed, falling back to CPU")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vulkan processing failed, falling back to CPU", e)
            }
        }

        // 回退到CPU处理
        return processWithCpu(bitmap, params)
    }

    /**
     * 初始化Vulkan
     */
    private suspend fun initializeVulkan() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing Vulkan...")
                
                // 检查Vulkan支持
                if (!nativeIsVulkanAvailable()) {
                    Log.w(TAG, "Vulkan not available on this device")
                    return@withContext
                }

                // 获取AssetManager
                val assetManager = context.assets

                // 创建Vulkan处理器
                nativeHandle = nativeCreate(assetManager)
                if (nativeHandle == 0L) {
                    Log.e(TAG, "Failed to create Vulkan processor")
                    return@withContext
                }

                isInitialized = true
                
                val deviceInfo = nativeGetDeviceInfo(nativeHandle)
                Log.i(TAG, "Vulkan initialized successfully")
                Log.i(TAG, "Device info: $deviceInfo")
                Log.i(TAG, "Max texture size: ${nativeGetMaxTextureSize(nativeHandle)}")

                // 上传已加载的LUT数据
                if (currentLut != null) {
                    uploadLutToVulkan(currentLut!!, currentLutSize, false)
                }
                if (currentLut2 != null) {
                    uploadLutToVulkan(currentLut2!!, currentLut2Size, true)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Vulkan", e)
                isInitialized = false
            }
        }
    }

    /**
     * 使用CPU处理图像
     */
    private suspend fun processWithCpu(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? {
        return withContext(Dispatchers.Default) {
            try {
                Log.d(TAG, "Processing with CPU fallback")

                // 同步LUT数据到CPU处理器
                if (currentLut != null) {
                    cpuProcessor.setLutData(currentLut, currentLutSize)
                }
                if (currentLut2 != null) {
                    cpuProcessor.setSecondLutData(currentLut2, currentLut2Size)
                }

                // 处理图像
                val result = cpuProcessor.processImage(bitmap, params)

                // 应用胶片颗粒（如果启用）
                if (result != null && currentGrainConfig?.isEnabled == true) {
                    val grainProcessor = cn.alittlecookie.lut2photo.lut2photo.core.FilmGrainProcessor()
                    grainProcessor.processImage(result, currentGrainConfig!!)
                } else {
                    result
                }
            } catch (e: Exception) {
                Log.e(TAG, "CPU processing failed", e)
                null
            }
        }
    }

    /**
     * 清除主LUT
     */
    fun clearMainLut() {
        currentLut = null
        currentLutSize = 0
        cpuProcessor.clearMainLut()
        Log.d(TAG, "Main LUT cleared")
    }

    /**
     * 清除第二个LUT
     */
    fun clearSecondLut() {
        currentLut2 = null
        currentLut2Size = 0
        cpuProcessor.clearSecondLut()
        Log.d(TAG, "Second LUT cleared")
    }

    override suspend fun release() {
        withContext(Dispatchers.IO) {
            try {
                if (nativeHandle != 0L) {
                    nativeRelease(nativeHandle)
                    nativeDestroy(nativeHandle)
                    nativeHandle = 0
                }
                isInitialized = false
                cpuProcessor.release()
                Log.d(TAG, "Vulkan processor released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing Vulkan processor", e)
            }
        }
    }

    override fun getProcessorInfo(): String {
        return if (isInitialized && nativeHandle != 0L) {
            try {
                nativeGetDeviceInfo(nativeHandle)
            } catch (e: Exception) {
                "Vulkan: Error getting info"
            }
        } else {
            "Vulkan: Not initialized"
        }
    }
}
