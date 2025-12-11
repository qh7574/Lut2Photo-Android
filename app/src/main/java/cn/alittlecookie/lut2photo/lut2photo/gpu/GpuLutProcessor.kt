package cn.alittlecookie.lut2photo.lut2photo.gpu

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import androidx.core.graphics.createBitmap
import cn.alittlecookie.lut2photo.lut2photo.core.CpuLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU-based LUT processor using OpenGL ES shaders
 * Provides hardware-accelerated image processing with LUT application
 */
class GpuLutProcessor(private val context: Context) : ILutProcessor {
    companion object {
        private const val TAG = "GpuLutProcessor"

        // 2410万像素限制（24.1 megapixels）
        private const val MAX_PIXELS_FOR_DIRECT_PROCESSING = 100_000_000L // 提升到1亿像素，支持更大图片直接GPU处理
        private const val MAX_CACHE_SIZE = 3 // 最多缓存3个不同尺寸的buffer
        // GPU纹理尺寸安全限制（大多数设备支持4096，但使用更保守的值）
        private const val SAFE_TEXTURE_SIZE = 2048
        // 分块处理的最大像素数（1600万像素）
        private const val MAX_BLOCK_PIXELS = 64_000_000L // 提升到6400万像素，减少分块数量

        // 更宽松的正则表达式，支持更多数字格式
        private val DATA_LINE_REGEX =
            Regex("^\\s*([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s+([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s+([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s*$")


        private fun checkGLError(operation: String) {
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
                val errorMsg = when (error) {
                    GLES30.GL_INVALID_ENUM -> "GL_INVALID_ENUM"
                    GLES30.GL_INVALID_VALUE -> "GL_INVALID_VALUE"
                    GLES30.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION"
                    GLES30.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY"
                    GLES30.GL_INVALID_FRAMEBUFFER_OPERATION -> "GL_INVALID_FRAMEBUFFER_OPERATION"
                    else -> "Unknown error $error"
                }
                Log.e(TAG, "OpenGL错误在 $operation: $errorMsg")
                throw RuntimeException("OpenGL错误在 $operation: $errorMsg")
            }
        }

        // Vertex shader for full-screen quad
        private const val VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            
            layout(location = 0) in vec2 a_position;
            layout(location = 1) in vec2 a_texCoord;
            
            out vec2 v_texCoord;
            
            void main() {
                gl_Position = vec4(a_position, 0.0, 1.0);
                v_texCoord = a_texCoord;
            }
        """

        private fun createFragmentShader(): String {
            return """
                #version 300 es
                precision highp float;
                
                in vec2 v_texCoord;
                out vec4 fragColor;
                
                uniform sampler2D u_inputTexture;
                uniform sampler3D u_lutTexture;
                uniform sampler3D u_lut2Texture;
                uniform float u_strength;
                uniform float u_lut2Strength;
                uniform int u_ditherType;
                uniform float u_lutSize;
                uniform float u_lut2Size;
                
                // 胶片颗粒参数
                uniform int u_grainEnabled;
                uniform float u_grainStrength;
                uniform float u_grainSize;
                uniform float u_grainSeed;
                uniform float u_shadowGrainRatio;
                uniform float u_midtoneGrainRatio;
                uniform float u_highlightGrainRatio;
                uniform float u_shadowSizeRatio;
                uniform float u_highlightSizeRatio;
                uniform float u_redChannelRatio;
                uniform float u_greenChannelRatio;
                uniform float u_blueChannelRatio;
                uniform float u_channelCorrelation;
                uniform float u_colorPreservation;
                
                // Random function for dithering
                float random(vec2 co) {
                    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
                }
                
                // 高斯噪声生成（Box-Muller变换简化版）
                float gaussianNoise(vec2 uv, float seed) {
                    float u1 = max(random(uv + seed), 0.0001);
                    float u2 = random(uv + seed + 0.5);
                    return sqrt(-2.0 * log(u1)) * cos(6.28318 * u2);
                }
                
                // 平滑插值函数
                float smoothstep3(float t) {
                    float x = clamp(t, 0.0, 1.0);
                    return x * x * (3.0 - 2.0 * x);
                }
                
                // 根据亮度获取颗粒强度比例
                float getGrainStrengthRatio(float luminance) {
                    if (luminance < 0.25) {
                        return u_shadowGrainRatio;
                    } else if (luminance < 0.35) {
                        float t = smoothstep3((luminance - 0.25) / 0.1);
                        return mix(u_shadowGrainRatio, u_midtoneGrainRatio, t);
                    } else if (luminance < 0.65) {
                        return u_midtoneGrainRatio;
                    } else if (luminance < 0.75) {
                        float t = smoothstep3((luminance - 0.65) / 0.1);
                        return mix(u_midtoneGrainRatio, u_highlightGrainRatio, t);
                    } else {
                        return u_highlightGrainRatio;
                    }
                }
                
                // 根据亮度获取颗粒尺寸比例
                float getGrainSizeRatio(float luminance) {
                    if (luminance < 0.25) {
                        return u_shadowSizeRatio;
                    } else if (luminance < 0.35) {
                        float t = smoothstep3((luminance - 0.25) / 0.1);
                        return mix(u_shadowSizeRatio, 1.0, t);
                    } else if (luminance < 0.65) {
                        return 1.0;
                    } else if (luminance < 0.75) {
                        float t = smoothstep3((luminance - 0.65) / 0.1);
                        return mix(1.0, u_highlightSizeRatio, t);
                    } else {
                        return u_highlightSizeRatio;
                    }
                }
                
                // 应用胶片颗粒效果
                vec3 applyFilmGrain(vec3 color, vec2 uv) {
                    // 计算亮度
                    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
                    
                    // 获取当前亮度下的颗粒参数
                    float strengthRatio = getGrainStrengthRatio(luminance);
                    float sizeRatio = getGrainSizeRatio(luminance);
                    
                    // 计算实际噪声强度
                    float noiseStrength = u_grainStrength * strengthRatio * u_grainSize * sizeRatio * 0.1;
                    
                    // 生成基础噪声（用于通道相关性）
                    vec2 grainUV = uv * u_grainSize * sizeRatio * 100.0;
                    float baseNoise = gaussianNoise(grainUV, u_grainSeed);
                    
                    // 生成各通道噪声（考虑通道相关性）
                    float rNoise = mix(gaussianNoise(grainUV, u_grainSeed + 0.1), baseNoise, u_channelCorrelation) * u_redChannelRatio;
                    float gNoise = mix(gaussianNoise(grainUV, u_grainSeed + 0.2), baseNoise, u_channelCorrelation) * u_greenChannelRatio;
                    float bNoise = mix(gaussianNoise(grainUV, u_grainSeed + 0.3), baseNoise, u_channelCorrelation) * u_blueChannelRatio;
                    
                    // 应用颗粒并保护色彩
                    vec3 noise = vec3(rNoise, gNoise, bNoise) * noiseStrength * u_colorPreservation;
                    
                    return color + noise;
                }
                
                // Floyd-Steinberg dithering approximation - 修复分块处理时的坐标问题
                vec3 applyFloydSteinbergDither(vec3 color, vec2 coord) {
                    // 使用纹理坐标而非屏幕坐标，避免分块时的坐标不连续问题
                    vec2 texelSize = vec2(1.0) / vec2(textureSize(u_inputTexture, 0));
                    vec2 ditherCoord = coord / texelSize;
                    float noise = (random(ditherCoord) - 0.5) / 255.0;
                    return color + vec3(noise);
                }
                
                // Random dithering - 修复分块处理时的坐标问题
                vec3 applyRandomDither(vec3 color, vec2 coord) {
                    // 使用纹理坐标而非屏幕坐标，避免分块时的坐标不连续问题
                    vec2 texelSize = vec2(1.0) / vec2(textureSize(u_inputTexture, 0));
                    vec2 ditherCoord = coord / texelSize;
                    float noise = (random(ditherCoord) - 0.5) / 128.0;
                    return color + vec3(noise);
                }
                
                void main() {
                    vec4 originalColor = texture(u_inputTexture, v_texCoord);
                    vec3 processedColor = originalColor.rgb;
                    
                    // 步骤1：应用第一个LUT
                    vec3 scaledColor1 = processedColor * (u_lutSize - 1.0);
                    vec3 lutCoord1 = (scaledColor1 + 0.5) / u_lutSize;
                    lutCoord1 = clamp(lutCoord1, 0.0, 1.0);
                    
                    vec3 lut1Color = texture(u_lutTexture, lutCoord1).rgb;
                    float clampedStrength1 = clamp(u_strength, 0.0, 1.0);
                    processedColor = mix(processedColor, lut1Color, clampedStrength1);
                    
                    // 步骤2：应用第二个LUT（如果强度大于0）
                    if (u_lut2Strength > 0.0) {
                        vec3 scaledColor2 = processedColor * (u_lut2Size - 1.0);
                        vec3 lutCoord2 = (scaledColor2 + 0.5) / u_lut2Size;
                        lutCoord2 = clamp(lutCoord2, 0.0, 1.0);
                        
                        vec3 lut2Color = texture(u_lut2Texture, lutCoord2).rgb;
                        float clampedStrength2 = clamp(u_lut2Strength, 0.0, 1.0);
                        processedColor = mix(processedColor, lut2Color, clampedStrength2);
                    }
                    
                    // 步骤3：应用抖动 - 使用纹理坐标而非屏幕坐标
                    vec3 ditheredColor = processedColor;
                    if (u_ditherType == 1) { // Floyd-Steinberg
                        ditheredColor = applyFloydSteinbergDither(processedColor, v_texCoord);
                    } else if (u_ditherType == 2) { // Random
                        ditheredColor = applyRandomDither(processedColor, v_texCoord);
                    }
                    
                    // 步骤4：应用胶片颗粒（在抖动之后）
                    vec3 finalColor = ditheredColor;
                    if (u_grainEnabled == 1 && u_grainStrength > 0.0) {
                        finalColor = applyFilmGrain(ditheredColor, v_texCoord);
                    }
                    
                    fragColor = vec4(clamp(finalColor, 0.0, 1.0), originalColor.a);
                }
            """
        }
    }

    // CPU处理器作为实例变量，用于OOM回退
    private val cpuProcessor = CpuLutProcessor()

    // ByteBuffer缓存池，复用内存
    private val bufferCache = mutableMapOf<String, ByteBuffer>()

    // 删除这行：private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null

    private var shaderProgram: Int = 0
    private var vertexBuffer: FloatBuffer? = null
    private var textureCoordBuffer: FloatBuffer? = null

    private var inputTextureLocation: Int = 0
    private var lutTextureLocation: Int = 0
    private var lut2TextureLocation: Int = 0  // 第二个LUT纹理位置
    private var strengthLocation: Int = 0
    private var lut2StrengthLocation: Int = 0  // 第二个LUT强度位置
    private var ditherTypeLocation: Int = 0
    private var lutSizeLocation: Int = 0
    private var lut2SizeLocation: Int = 0  // 第二个LUT尺寸位置
    
    // 胶片颗粒uniform位置
    private var grainEnabledLocation: Int = 0
    private var grainStrengthLocation: Int = 0
    private var grainSizeLocation: Int = 0
    private var grainSeedLocation: Int = 0
    private var shadowGrainRatioLocation: Int = 0
    private var midtoneGrainRatioLocation: Int = 0
    private var highlightGrainRatioLocation: Int = 0
    private var shadowSizeRatioLocation: Int = 0
    private var highlightSizeRatioLocation: Int = 0
    private var redChannelRatioLocation: Int = 0
    private var greenChannelRatioLocation: Int = 0
    private var blueChannelRatioLocation: Int = 0
    private var channelCorrelationLocation: Int = 0
    private var colorPreservationLocation: Int = 0
    
    // 当前颗粒配置
    private var currentGrainConfig: cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig? = null

    private var isInitialized = false
    private var lutTexture: Int = 0
    private var lut2Texture: Int = 0  // 第二个LUT纹理
    private var currentLut: Array<Array<Array<FloatArray>>>? = null
    private var currentLut2: Array<Array<Array<FloatArray>>>? = null  // 第二个LUT数据
    private var currentLutSize: Int = 0
    private var currentLut2Size: Int = 0  // 第二个LUT尺寸

    // GPU最大纹理尺寸（初始化时获取）
    private var maxTextureSize: Int = SAFE_TEXTURE_SIZE

    /**
     * 获取或创建指定尺寸的ByteBuffer，带内存压力检测
     */
    private fun getOrCreateBuffer(width: Int, height: Int, name: String): ByteBuffer {
        val size = width * height * 4
        val sizeMB = size / 1024 / 1024
        val key = "${name}_${width}_${height}"

        // 高内存限制：支持1G-2G内存使用，单个缓冲区最大512MB
        if (sizeMB > 512) {
            Log.w(TAG, "缓冲区大小过大(${sizeMB}MB)，超过最大限制(512MB)")
            throw OutOfMemoryError("GPU缓冲区大小超过最大限制: ${sizeMB}MB > 512MB")
        }

        Log.d(TAG, "缓冲区大小检查通过: ${sizeMB}MB")

        var buffer = bufferCache[key]
        if (buffer == null || buffer.capacity() < size) {
            // 在分配新缓冲区前，检查总缓存大小
            val totalCacheSize = bufferCache.values.sumOf { it.capacity() }
            val totalCacheSizeMB = totalCacheSize / 1024 / 1024

            // 高内存缓存限制：支持最大1GB总缓存
            if (totalCacheSizeMB > 1024) {
                Log.w(
                    TAG,
                    "缓存总大小过大(${totalCacheSizeMB}MB)，超过最大限制(1024MB)，清理所有缓存"
                )
                bufferCache.clear()
            } else if (bufferCache.size >= MAX_CACHE_SIZE) {
                // 清理最旧的缓存
                val oldestKey = bufferCache.keys.first()
                bufferCache.remove(oldestKey)
                Log.d(TAG, "清理旧ByteBuffer缓存: $oldestKey")
            }

            try {
                buffer = ByteBuffer.allocateDirect(size)
                buffer.order(ByteOrder.nativeOrder())
                bufferCache[key] = buffer
                Log.d(TAG, "创建新ByteBuffer: $key, 大小: ${sizeMB}MB")
            } catch (e: OutOfMemoryError) {
                // 清理所有缓存后重试一次
                Log.w(TAG, "ByteBuffer分配失败，清理缓存后重试")
                bufferCache.clear()
                System.gc() // 建议垃圾回收

                try {
                    buffer = ByteBuffer.allocateDirect(size)
                    buffer.order(ByteOrder.nativeOrder())
                    bufferCache[key] = buffer
                    Log.d(TAG, "重试创建ByteBuffer成功: $key, 大小: ${sizeMB}MB")
                } catch (e2: OutOfMemoryError) {
                    Log.e(TAG, "ByteBuffer分配最终失败，大小: ${sizeMB}MB")
                    throw e2
                }
            }
        } else {
            buffer.clear() // 重置位置
            Log.d(TAG, "复用ByteBuffer: $key")
        }

        // 确保返回非空buffer
        return buffer ?: throw RuntimeException("无法创建或获取ByteBuffer")
    }

    /**
     * 清理所有缓存的ByteBuffer
     */
    private fun clearBufferCache() {
        bufferCache.clear()
        Log.d(TAG, "清理所有ByteBuffer缓存")
    }


    override fun getProcessorType(): ILutProcessor.ProcessorType {
        return ILutProcessor.ProcessorType.GPU
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            withContext(Dispatchers.Main) {
                Log.d(TAG, "开始检查GPU可用性")

                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val configurationInfo = activityManager.deviceConfigurationInfo
                val supportsEs3 = configurationInfo.reqGlEsVersion >= 0x30000

                Log.d(TAG, "设备OpenGL ES版本: 0x${configurationInfo.reqGlEsVersion.toString(16)}")
                Log.d(TAG, "是否支持OpenGL ES 3.0: $supportsEs3")

                if (!supportsEs3) {
                    Log.w(TAG, "设备不支持OpenGL ES 3.0，GPU处理不可用")
                    return@withContext false
                }

                // 尝试初始化GPU
                if (!isInitialized) {
                    Log.d(TAG, "开始初始化GPU处理器")
                    try {
                        initializeGpu()
                        Log.d(TAG, "GPU处理器初始化成功")
                    } catch (e: Exception) {
                        Log.e(TAG, "GPU处理器初始化失败: ${e.message}", e)
                        // 不要重新抛出异常，而是返回 false
                        return@withContext false
                    }
                } else {
                    Log.d(TAG, "GPU处理器已初始化")
                }

                Log.d(TAG, "GPU处理器可用性检查完成: $isInitialized")
                isInitialized
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPU不可用: ${e.message}", e)
            false
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

                // 验证LUT数据加载
                val lutData = cpuProcessor.getLutData()
                val lutSize = cpuProcessor.getLutSize()
                if (lutData == null || lutSize == 0) {
                    Log.e(TAG, "LUT数据加载失败，数据为空")
                    return@withContext false
                }

                Log.d(TAG, "CPU处理器LUT加载成功，尺寸: $lutSize")

                // 获取LUT数据和尺寸
                currentLut = lutData
                currentLutSize = lutSize

                if (isInitialized && currentLut != null) {
                    uploadLutToGpu(currentLut!!)
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load LUT", e)
                false
            }
        }
    }

    /**
     * 加载第二个LUT文件
     * @param inputStream 第二个LUT文件输入流
     * @return 是否加载成功
     */
    suspend fun loadSecondCubeLut(inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "开始加载第二个LUT文件到GPU")

                val reader = inputStream.bufferedReader()
                val lines = reader.readLines()
                reader.close()

                Log.d(TAG, "第二个LUT读取到 ${lines.size} 行数据")

                var lutSize = 0
                var dataStartIndex = -1

                // 解析头部信息
                for ((index, line) in lines.withIndex()) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.startsWith("LUT_3D_SIZE")) {
                        Log.d(TAG, "第二个LUT找到LUT_3D_SIZE行: $trimmedLine")
                        val parts = trimmedLine.split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            lutSize = parts[1].toInt()
                            Log.d(TAG, "第二个LUT尺寸: $lutSize")
                            dataStartIndex = index + 1
                            break
                        }
                    }
                }

                if (lutSize == 0) {
                    Log.e(TAG, "第二个LUT未找到有效的LUT_3D_SIZE")
                    return@withContext false
                }

                // 初始化第二个LUT数组
                val lut2Array =
                    Array(lutSize) { Array(lutSize) { Array(lutSize) { FloatArray(3) } } }
                Log.d(
                    TAG,
                    "第二个LUT数组初始化完成，尺寸: ${lut2Array.size}x${lut2Array[0].size}x${lut2Array[0][0].size}"
                )

                // 解析数据行
                var dataIndex = 0
                val expectedSize = lutSize * lutSize * lutSize

                for (i in dataStartIndex until lines.size) {
                    if (dataIndex >= expectedSize) break

                    val line = lines[i].trim()
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE") || line.startsWith(
                            "DOMAIN_"
                        )
                    ) {
                        continue
                    }

                    val match = DATA_LINE_REGEX.matchEntire(line)
                    if (match != null) {
                        try {
                            val r = match.groupValues[1].toFloat()
                            val g = match.groupValues[2].toFloat()
                            val b = match.groupValues[3].toFloat()

                            val rIndex = dataIndex % lutSize
                            val gIndex = (dataIndex / lutSize) % lutSize
                            val bIndex = dataIndex / (lutSize * lutSize)

                            lut2Array[rIndex][gIndex][bIndex][0] = r
                            lut2Array[rIndex][gIndex][bIndex][1] = g
                            lut2Array[rIndex][gIndex][bIndex][2] = b

                            dataIndex++
                        } catch (e: NumberFormatException) {
                            Log.w(TAG, "第二个LUT无法解析数据行 ${i + 1}: $line", e)
                        }
                    } else {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 3) {
                            try {
                                val r = parts[0].toFloat()
                                val g = parts[1].toFloat()
                                val b = parts[2].toFloat()

                                val rIndex = dataIndex % lutSize
                                val gIndex = (dataIndex / lutSize) % lutSize
                                val bIndex = dataIndex / (lutSize * lutSize)

                                lut2Array[rIndex][gIndex][bIndex][0] = r
                                lut2Array[rIndex][gIndex][bIndex][1] = g
                                lut2Array[rIndex][gIndex][bIndex][2] = b

                                dataIndex++
                            } catch (_: NumberFormatException) {
                                Log.w(TAG, "第二个LUT备用解析也失败，跳过行 ${i + 1}: $line")
                            }
                        }
                    }
                }

                Log.d(
                    TAG,
                    "第二个LUT数据加载完成，期望数据点: $expectedSize，实际数据点: $dataIndex"
                )

                if (dataIndex == expectedSize) {
                    this@GpuLutProcessor.currentLut2 = lut2Array
                    this@GpuLutProcessor.currentLut2Size = lutSize
                    Log.d(TAG, "第二个LUT数据解析成功，数据尺寸: ${lutSize}, 数据点: $dataIndex")

                    // 上传到GPU（如果GPU已初始化）
                    if (isInitialized) {
                        Log.d(TAG, "GPU已初始化，立即上传第二个LUT到GPU")
                        uploadSecondLutToGpu(lut2Array)
                        Log.d(TAG, "第二个LUT成功上传到GPU，纹理ID: $lut2Texture")
                    } else {
                        Log.d(TAG, "GPU未初始化，第二个LUT数据已保存，将在GPU初始化时上传")
                    }
                    true
                } else {
                    Log.e(TAG, "第二个LUT数据不完整: 期望 $expectedSize，实际 $dataIndex")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载第二个LUT文件失败", e)
                false
            }
        }
    }


    private fun uploadLutToGpu(lut: Array<Array<Array<FloatArray>>>) {
        // 删除旧的LUT纹理
        if (lutTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        }

        // 验证LUT数据
        var nonZeroCount = 0
        var totalCount = 0
        for (r in 0 until currentLutSize) {
            for (g in 0 until currentLutSize) {
                for (b in 0 until currentLutSize) {
                    val rgb = lut[r][g][b]
                    totalCount++
                    if (rgb[0] > 0.001f || rgb[1] > 0.001f || rgb[2] > 0.001f) {
                        nonZeroCount++
                    }
                }
            }
        }
        Log.d(TAG, "LUT数据验证: 总数据点=$totalCount, 非零数据点=$nonZeroCount")

        if (nonZeroCount < totalCount * 0.1) {
            Log.w(TAG, "警告：LUT数据中非零点过少，可能导致黑色输出")
        }

        // 创建新的3D纹理
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        lutTexture = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)

        // 使用动态尺寸创建LUT数据数组
        val lutData = FloatArray(currentLutSize * currentLutSize * currentLutSize * 3)
        var index = 0

        // 使用动态尺寸填充数据 - 修复RGB通道顺序
        for (b in 0 until currentLutSize) {  // Z轴 - Blue
            for (g in 0 until currentLutSize) {  // Y轴 - Green  
                for (r in 0 until currentLutSize) {  // X轴 - Red
                    lutData[index++] = lut[r][g][b][0]
                    lutData[index++] = lut[r][g][b][1]
                    lutData[index++] = lut[r][g][b][2]
                }
            }
        }

        val buffer = ByteBuffer.allocateDirect(lutData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(lutData)
        buffer.position(0)

        // 使用动态尺寸上传纹理
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F,
            currentLutSize, currentLutSize, currentLutSize, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buffer
        )

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_R,
            GLES30.GL_CLAMP_TO_EDGE
        )

        Log.d(TAG, "LUT uploaded to GPU with size: $currentLutSize")
    }

    private fun uploadSecondLutToGpu(lut2: Array<Array<Array<FloatArray>>>) {
        // 删除旧的第二个LUT纹理
        if (lut2Texture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(lut2Texture), 0)
        }

        // 验证第二个LUT数据
        var nonZeroCount = 0
        var totalCount = 0
        for (r in 0 until currentLut2Size) {
            for (g in 0 until currentLut2Size) {
                for (b in 0 until currentLut2Size) {
                    val rgb = lut2[r][g][b]
                    totalCount++
                    if (rgb[0] > 0.001f || rgb[1] > 0.001f || rgb[2] > 0.001f) {
                        nonZeroCount++
                    }
                }
            }
        }
        Log.d(TAG, "第二个LUT数据验证: 总数据点=$totalCount, 非零数据点=$nonZeroCount")

        if (nonZeroCount < totalCount * 0.1) {
            Log.w(TAG, "警告：第二个LUT数据中非零点过少，可能导致黑色输出")
        }

        // 创建新的3D纹理
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        lut2Texture = textures[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut2Texture)

        // 使用动态尺寸创建第二个LUT数据数组
        val lut2Data = FloatArray(currentLut2Size * currentLut2Size * currentLut2Size * 3)
        var index = 0

        // 使用动态尺寸填充数据 - 修复RGB通道顺序
        for (b in 0 until currentLut2Size) {  // Z轴 - Blue
            for (g in 0 until currentLut2Size) {  // Y轴 - Green  
                for (r in 0 until currentLut2Size) {  // X轴 - Red
                    lut2Data[index++] = lut2[r][g][b][0]
                    lut2Data[index++] = lut2[r][g][b][1]
                    lut2Data[index++] = lut2[r][g][b][2]
                }
            }
        }

        val buffer = ByteBuffer.allocateDirect(lut2Data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(lut2Data)
        buffer.position(0)

        // 使用动态尺寸上传纹理
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F,
            currentLut2Size, currentLut2Size, currentLut2Size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buffer
        )

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_3D,
            GLES30.GL_TEXTURE_WRAP_R,
            GLES30.GL_CLAMP_TO_EDGE
        )

        Log.d(TAG, "第二个LUT uploaded to GPU with size: $currentLut2Size")
    }

    override suspend fun processImage(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? {
        Log.d(TAG, "开始GPU处理，图片尺寸: ${bitmap.width}x${bitmap.height}")

        val totalPixels = bitmap.width.toLong() * bitmap.height.toLong()
        Log.d(TAG, "总像素数: $totalPixels, 限制: $MAX_PIXELS_FOR_DIRECT_PROCESSING")

        return try {
            // 1. 判断像素数量决定处理方式
            if (totalPixels <= MAX_PIXELS_FOR_DIRECT_PROCESSING) {
                // 直接GPU处理（≤2410万像素）
                Log.d(TAG, "图片像素数($totalPixels)在直接处理范围内，使用GPU直接处理")
                processImageOnGpu(bitmap, params)
            } else {
                // 分块GPU处理（>2410万像素）
                Log.d(TAG, "图片像素数($totalPixels)超过直接处理限制，使用GPU分块处理")
                processImageInBlocks(bitmap, params)
            }
        } catch (e: OutOfMemoryError) {
            // 2. 只有在真正OOM时才回退到CPU
            Log.w(TAG, "GPU处理发生OOM，回退到CPU处理器", e)
            handleGpuOomFallback(bitmap, params)
        } catch (e: Exception) {
            // 3. 其他异常也尝试CPU回退
            Log.w(TAG, "GPU处理失败，回退到CPU处理: ${e.message}")
            handleGpuErrorFallback(bitmap, params, e)
        }
    }



    /**
     * 处理GPU OOM情况的回退逻辑
     */
    private suspend fun handleGpuOomFallback(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? {
        return try {
            // 清理GPU资源
            cleanupGpu()
            System.gc()
            Thread.sleep(100)

            Log.d(TAG, "GPU OOM后清理完成，回退到CPU处理器")
            
            // 确保CPU处理器有正确的LUT数据
            if (currentLut != null) {
                cpuProcessor.setLutData(currentLut, currentLutSize)
                Log.d(
                    TAG,
                    "主LUT数据已同步到CPU处理器，尺寸: ${currentLutSize}x${currentLutSize}x${currentLutSize}"
                )
            }

            // 同步第二个LUT数据
            if (currentLut2 != null && currentLut2Size > 0) {
                cpuProcessor.setSecondLutData(currentLut2, currentLut2Size)
                Log.d(
                    TAG,
                    "第二个LUT数据已同步到CPU处理器，尺寸: ${currentLut2Size}x${currentLut2Size}x${currentLut2Size}"
                )
            } else {
                Log.d(TAG, "没有第二个LUT数据需要同步")
            }

            cpuProcessor.processImage(bitmap, params)
        } catch (e: Exception) {
            Log.e(TAG, "CPU回退处理也失败", e)
            null
        }
    }

    /**
     * 处理GPU其他错误的回退逻辑
     */
    private suspend fun handleGpuErrorFallback(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams,
        originalError: Exception
    ): Bitmap? {
        return try {
            Log.d(TAG, "GPU处理错误，准备CPU回退: ${originalError.message}")
            
            // 确保CPU处理器有正确的LUT数据
            if (currentLut != null) {
                Log.d(
                    TAG,
                    "同步主LUT数据到CPU处理器，尺寸: ${currentLutSize}x${currentLutSize}x${currentLutSize}"
                )
                cpuProcessor.setLutData(currentLut, currentLutSize)

                // 验证主LUT同步是否成功
                if (cpuProcessor.getLutData() != null) {
                    Log.d(TAG, "主LUT数据同步成功")
                } else {
                    Log.e(TAG, "主LUT数据同步失败")
                    return null
                }
            } else {
                Log.e(TAG, "GPU处理器没有主LUT数据，无法同步到CPU处理器")
                return null
            }

            // 同步第二个LUT数据
            if (currentLut2 != null && currentLut2Size > 0) {
                Log.d(
                    TAG,
                    "同步第二个LUT数据到CPU处理器，尺寸: ${currentLut2Size}x${currentLut2Size}x${currentLut2Size}"
                )
                cpuProcessor.setSecondLutData(currentLut2, currentLut2Size)

                // 验证第二个LUT同步是否成功
                if (cpuProcessor.getSecondLutData() != null) {
                    Log.d(TAG, "第二个LUT数据同步成功")
                } else {
                    Log.w(TAG, "第二个LUT数据同步失败，但继续处理")
                }
            } else {
                Log.d(TAG, "没有第二个LUT数据需要同步")
            }

            val cpuResult = cpuProcessor.processImage(bitmap, params)
            if (cpuResult != null) {
                Log.d(TAG, "CPU回退处理成功")
            } else {
                Log.e(TAG, "CPU回退处理失败")
            }
            cpuResult
        } catch (e: Exception) {
            Log.e(TAG, "CPU回退处理过程中发生异常", e)
            null
        }
    }

    // 删除calculateRequiredMemory、getDeviceMemoryClass、getAvailableMemory等内存预检查方法
    // 这些方法会导致不必要的CPU回退，现在只在真正OOM时才回退

    // 添加 release 方法
    override suspend fun release() {
        withContext(Dispatchers.Main) {
            cleanupGpu()
            clearBufferCache() // 清理ByteBuffer缓存
        }
    }

    /**
     * GPU二维分块处理方法
     * 当图片超过2410万像素或单边超过GPU最大纹理尺寸时，使用X和Y轴二维分块处理
     * 支持田字形/9宫格分块，优先GPU处理，只在OOM时才回退CPU
     */
    private suspend fun processImageInBlocks(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height

        Log.d(TAG, "开始GPU二维分块处理，图片尺寸: ${width}x${height}")
        Log.d(TAG, "GPU最大纹理尺寸: $maxTextureSize, 最大块像素数: $MAX_BLOCK_PIXELS")

        // 计算合适的块尺寸
        val blockDimensions = calculateOptimalBlockSize(width, height)
        val blockWidth = blockDimensions.first
        val blockHeight = blockDimensions.second
        val blocksX = (width + blockWidth - 1) / blockWidth // 向上取整
        val blocksY = (height + blockHeight - 1) / blockHeight // 向上取整

        Log.d(
            TAG,
            "分块参数: 块尺寸=${blockWidth}x${blockHeight}, 分块数=${blocksX}x${blocksY}, 总块数=${blocksX * blocksY}"
        )

        // 创建结果bitmap
        val resultBitmap = createBitmap(width, height)

        var blockIndex = 0
        var hasOomOccurred = false // 标记是否已发生OOM

        try {
            // 遍历所有块（Y轴优先）
            for (blockY in 0 until blocksY) {
                for (blockX in 0 until blocksX) {
                    // 计算当前块的实际尺寸和位置
                    val currentX = blockX * blockWidth
                    val currentY = blockY * blockHeight
                    val currentBlockWidth = minOf(blockWidth, width - currentX)
                    val currentBlockHeight = minOf(blockHeight, height - currentY)

                    Log.d(
                        TAG,
                        "处理块 $blockIndex (${blockX},${blockY}): 位置=($currentX,$currentY), 尺寸=${currentBlockWidth}x${currentBlockHeight}"
                    )

                    // 验证块参数有效性
                    if (currentX + currentBlockWidth > width || currentY + currentBlockHeight > height) {
                        Log.e(
                            TAG,
                            "块参数越界: currentX=$currentX, currentY=$currentY, blockWidth=$currentBlockWidth, blockHeight=$currentBlockHeight, totalSize=${width}x${height}"
                        )
                        throw RuntimeException("块参数越界")
                    }

                    if (currentBlockWidth <= 0 || currentBlockHeight <= 0) {
                        Log.e(
                            TAG,
                            "块尺寸无效: width=$currentBlockWidth, height=$currentBlockHeight"
                        )
                        throw RuntimeException("块尺寸无效")
                    }

                    // 创建当前块的bitmap
                    val blockBitmap = try {
                        Log.d(
                            TAG,
                            "创建块bitmap: x=$currentX, y=$currentY, width=$currentBlockWidth, height=$currentBlockHeight"
                        )
                        Bitmap.createBitmap(
                            bitmap,
                            currentX,
                            currentY,
                            currentBlockWidth,
                            currentBlockHeight
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "创建块bitmap失败: ${e.message}")
                        throw RuntimeException("创建块bitmap失败: ${e.message}", e)
                    }

                    // 验证创建的块bitmap
                    if (blockBitmap == null || blockBitmap.isRecycled ||
                        blockBitmap.width != currentBlockWidth || blockBitmap.height != currentBlockHeight
                    ) {
                        Log.e(
                            TAG,
                            "块bitmap创建异常: width=${blockBitmap?.width}, height=${blockBitmap?.height}, isRecycled=${blockBitmap?.isRecycled}"
                        )
                        blockBitmap?.recycle()
                        throw RuntimeException("块bitmap创建异常")
                    }

                    Log.d(
                        TAG,
                        "块bitmap创建成功: ${blockBitmap.width}x${blockBitmap.height}, 格式: ${blockBitmap.config}"
                    )

                    // 如果之前没有发生OOM，优先尝试GPU处理
                    val processedBlock = if (!hasOomOccurred) {
                        try {
                            Log.d(TAG, "块 $blockIndex 使用GPU处理")
                            processImageOnGpu(blockBitmap, params)
                        } catch (e: OutOfMemoryError) {
                            Log.w(TAG, "块 $blockIndex GPU处理OOM，标记回退状态并使用CPU处理", e)
                            hasOomOccurred = true // 标记已发生OOM

                            // 清理GPU资源
                            try {
                                Log.d(TAG, "OOM后清理GPU资源并重新初始化")
                                cleanupGpu()
                                System.gc()
                                Thread.sleep(100)
                                Log.d(TAG, "GPU资源清理完成，等待下次重新初始化")
                            } catch (cleanupException: Exception) {
                                Log.w(TAG, "GPU资源清理失败", cleanupException)
                            }

                            // 回退到CPU处理当前块
                            processSingleBlockWithCpu(blockBitmap, params)
                        }
                    } else {
                        // 如果之前已发生OOM，后续块直接使用CPU处理
                        Log.d(TAG, "块 $blockIndex 使用CPU处理（之前OOM回退）")
                        processSingleBlockWithCpu(blockBitmap, params)
                    }

                    if (processedBlock == null) {
                        Log.e(TAG, "块 $blockIndex 处理失败")
                        // 清理已创建的资源
                        cleanupBitmaps(resultBitmap, blockBitmap)
                        return@withContext null
                    }

                    // 将处理后的块拷贝到结果bitmap
                    try {
                        val canvas = android.graphics.Canvas(resultBitmap)
                        canvas.drawBitmap(
                            processedBlock,
                            currentX.toFloat(),
                            currentY.toFloat(),
                            null
                        )
                        Log.d(TAG, "块 $blockIndex 已拷贝到结果bitmap")
                    } catch (e: Exception) {
                        Log.e(TAG, "拷贝块 $blockIndex 到结果bitmap失败", e)
                        // 清理资源并返回失败
                        cleanupBitmaps(resultBitmap, blockBitmap, processedBlock)
                        return@withContext null
                    }

                    // 释放临时bitmap
                    cleanupBitmaps(blockBitmap, processedBlock)

                    blockIndex++

                    // 让出CPU时间，避免阻塞UI
                    kotlinx.coroutines.yield()

                    Log.d(
                        TAG,
                        "块 ${blockIndex - 1} 处理完成，进度: $blockIndex/${blocksX * blocksY}"
                    )
                }
            }

            if (hasOomOccurred) {
                Log.i(TAG, "GPU二维分块处理完成（部分块回退到CPU），总共处理了 $blockIndex 个块")
            } else {
                Log.d(TAG, "GPU二维分块处理完成（全GPU处理），总共处理了 $blockIndex 个块")
            }
            return@withContext resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "GPU二维分块处理过程中出错", e)
            if (!resultBitmap.isRecycled) {
                resultBitmap.recycle()
            }

            // 完全回退到CPU处理器
            Log.w(TAG, "GPU二维分块处理失败，完全回退到CPU处理器")
            return@withContext fallbackToCpuProcessor(bitmap, params)
        }
    }

    /**
     * 计算最佳分块尺寸
     * 考虑GPU最大纹理尺寸限制和像素数量限制，智能选择X和Y轴的分块方案
     */
    private fun calculateOptimalBlockSize(width: Int, height: Int): Pair<Int, Int> {
        Log.d(
            TAG,
            "计算最佳分块尺寸，原图尺寸: ${width}x${height}, GPU最大纹理尺寸: $maxTextureSize"
        )

        // 1. 检查是否需要因为尺寸限制进行分块
        val needWidthSplit = width > maxTextureSize
        val needHeightSplit = height > maxTextureSize

        // 2. 计算基于尺寸限制的分块数量
        val minBlocksX = if (needWidthSplit) {
            (width + maxTextureSize - 1) / maxTextureSize // 向上取整
        } else {
            1
        }

        val minBlocksY = if (needHeightSplit) {
            (height + maxTextureSize - 1) / maxTextureSize // 向上取整
        } else {
            1
        }

        // 3. 基于尺寸限制计算的初始块尺寸
        var blockWidth = width / minBlocksX
        var blockHeight = height / minBlocksY

        Log.d(
            TAG,
            "基于尺寸限制的分块: ${minBlocksX}x${minBlocksY}, 块尺寸: ${blockWidth}x${blockHeight}"
        )

        // 4. 检查像素数量是否超过限制，如果超过则需要进一步分块
        val blockPixels = blockWidth.toLong() * blockHeight.toLong()
        if (blockPixels > MAX_BLOCK_PIXELS) {
            Log.d(TAG, "块像素数($blockPixels)超过限制($MAX_BLOCK_PIXELS)，需要进一步分块")

            // 计算需要额外分块的倍数
            val pixelRatio = blockPixels.toDouble() / MAX_BLOCK_PIXELS.toDouble()
            val additionalBlocks = kotlin.math.ceil(kotlin.math.sqrt(pixelRatio)).toInt()

            // 优先在较长的边上进行分块
            if (blockWidth >= blockHeight) {
                // 宽度较大，优先在X轴分块
                val totalBlocksX = minBlocksX * additionalBlocks
                blockWidth = width / totalBlocksX

                // 如果还是太大，也在Y轴分块
                val newBlockPixels = blockWidth.toLong() * blockHeight.toLong()
                if (newBlockPixels > MAX_BLOCK_PIXELS) {
                    val remainingRatio = newBlockPixels.toDouble() / MAX_BLOCK_PIXELS.toDouble()
                    val additionalYBlocks = kotlin.math.ceil(remainingRatio).toInt()
                    val totalBlocksY = minBlocksY * additionalYBlocks
                    blockHeight = height / totalBlocksY
                }
            } else {
                // 高度较大，优先在Y轴分块
                val totalBlocksY = minBlocksY * additionalBlocks
                blockHeight = height / totalBlocksY

                // 如果还是太大，也在X轴分块
                val newBlockPixels = blockWidth.toLong() * blockHeight.toLong()
                if (newBlockPixels > MAX_BLOCK_PIXELS) {
                    val remainingRatio = newBlockPixels.toDouble() / MAX_BLOCK_PIXELS.toDouble()
                    val additionalXBlocks = kotlin.math.ceil(remainingRatio).toInt()
                    val totalBlocksX = minBlocksX * additionalXBlocks
                    blockWidth = width / totalBlocksX
                }
            }
        }

        // 5. 确保块尺寸不超过GPU限制
        blockWidth = blockWidth.coerceAtMost(maxTextureSize)
        blockHeight = blockHeight.coerceAtMost(maxTextureSize)

        // 6. 确保块尺寸至少为1
        blockWidth = blockWidth.coerceAtLeast(1)
        blockHeight = blockHeight.coerceAtLeast(1)

        val finalBlockPixels = blockWidth.toLong() * blockHeight.toLong()
        val finalBlocksX = (width + blockWidth - 1) / blockWidth
        val finalBlocksY = (height + blockHeight - 1) / blockHeight

        Log.d(
            TAG,
            "最终分块方案: 块尺寸=${blockWidth}x${blockHeight}, 分块数=${finalBlocksX}x${finalBlocksY}, 块像素数=$finalBlockPixels"
        )

        // 7. 验证分块方案的合理性
        if (blockWidth > maxTextureSize || blockHeight > maxTextureSize) {
            Log.w(TAG, "警告：计算出的块尺寸仍超过GPU限制，可能导致纹理创建失败")
        }

        if (finalBlockPixels > MAX_BLOCK_PIXELS) {
            Log.w(TAG, "警告：计算出的块像素数仍超过限制，可能导致OOM")
        }

        return Pair(blockWidth, blockHeight)
    }

    /**
     * 使用CPU处理单个块
     */
    private suspend fun processSingleBlockWithCpu(
        blockBitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? {
        return try {
            // 确保CPU处理器有LUT数据
            if (currentLut != null) {
                cpuProcessor.setLutData(currentLut, currentLutSize)
            }

            cpuProcessor.processImage(blockBitmap, params)
        } catch (e: Exception) {
            Log.e(TAG, "CPU处理单个块失败", e)
            null
        }
    }

    /**
     * 完全回退到CPU处理器
     */
    private suspend fun fallbackToCpuProcessor(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? {
        return try {
            Log.d(TAG, "完全回退到CPU处理器")

            // 同步主LUT数据
            if (currentLut != null) {
                cpuProcessor.setLutData(currentLut, currentLutSize)
                Log.d(
                    TAG,
                    "主LUT数据已同步到CPU处理器，尺寸: ${currentLutSize}x${currentLutSize}x${currentLutSize}"
                )
            }

            // 同步第二个LUT数据
            if (currentLut2 != null && currentLut2Size > 0) {
                cpuProcessor.setSecondLutData(currentLut2, currentLut2Size)
                Log.d(
                    TAG,
                    "第二个LUT数据已同步到CPU处理器，尺寸: ${currentLut2Size}x${currentLut2Size}x${currentLut2Size}"
                )
            } else {
                Log.d(TAG, "没有第二个LUT数据需要同步")
            }

            cpuProcessor.processImage(bitmap, params)
        } catch (e: Exception) {
            Log.e(TAG, "CPU回退处理失败", e)
            null
        }
    }

    /**
     * 清理bitmap资源
     */
    private fun cleanupBitmaps(vararg bitmaps: Bitmap?) {
        bitmaps.forEach { bitmap ->
            try {
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bitmap清理失败", e)
            }
        }
    }

    private suspend fun processImageOnGpu(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap = withContext(Dispatchers.Main) {
        // 验证bitmap有效性
        if (bitmap.isRecycled) {
            throw RuntimeException("Bitmap已被回收，无法处理")
        }

        val width = bitmap.width
        val height = bitmap.height

        if (width <= 0 || height <= 0) {
            throw RuntimeException("Bitmap尺寸无效: ${width}x${height}")
        }

        Log.d(TAG, "开始GPU处理，bitmap尺寸: ${width}x${height}, 格式: ${bitmap.config}")
        Log.d(
            TAG,
            "处理参数详情: 强度=${params.strength}, LUT2强度=${params.lut2Strength}, 质量=${params.quality}, 抖动类型=${params.ditherType}"
        )
        Log.d(
            TAG,
            "LUT状态: 主LUT纹理ID=$lutTexture, 第二个LUT纹理ID=$lut2Texture, 主LUT尺寸=$currentLutSize, 第二个LUT尺寸=$currentLut2Size"
        )

        // 检查第二个LUT是否加载
        if (lut2Texture != 0 && currentLut2Size > 0) {
            Log.d(TAG, "第二个LUT已加载，纹理ID: $lut2Texture, 尺寸: $currentLut2Size")
        } else {
            Log.d(TAG, "第二个LUT未加载或无效，纹理ID: $lut2Texture, 尺寸: $currentLut2Size")
        }

        // 检查GPU初始化状态
        if (!isInitialized) {
            Log.w(TAG, "GPU未初始化，尝试重新初始化")
            initializeGpu()
            if (!isInitialized) {
                throw RuntimeException("GPU重新初始化失败")
            }
        }

        // 验证bitmap格式是否支持
        Log.d(TAG, "Bitmap原始格式: ${bitmap.config}")

        // 对于所有非ARGB_8888格式，都转换为ARGB_8888以确保兼容性
        val processableBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            Log.w(TAG, "Bitmap格式不是ARGB_8888: ${bitmap.config}，转换为ARGB_8888")
            try {
                val convertedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                if (convertedBitmap == null) {
                    throw RuntimeException("无法转换Bitmap格式从${bitmap.config}到ARGB_8888")
                }
                Log.d(TAG, "Bitmap格式转换成功: ${bitmap.config} -> ${convertedBitmap.config}")
                convertedBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Bitmap格式转换失败", e)
                throw RuntimeException("Bitmap格式转换失败: ${e.message}", e)
            }
        } else {
            bitmap
        }

        // 再次验证转换后的bitmap
        if (processableBitmap.isRecycled) {
            throw RuntimeException("转换后的Bitmap已被回收")
        }

        if (processableBitmap.width != width || processableBitmap.height != height) {
            Log.w(
                TAG,
                "Bitmap尺寸在转换后发生变化: ${width}x${height} -> ${processableBitmap.width}x${processableBitmap.height}"
            )
        }

        // 如果转换了bitmap，递归调用处理新的bitmap
        if (processableBitmap !== bitmap) {
            return@withContext processImageOnGpu(processableBitmap, params)
        }
        // 增强EGL上下文验证和线程安全处理
        synchronized(this@GpuLutProcessor) {
            // 验证EGL上下文是否有效
            if (eglDisplay == null || eglContext == null || eglSurface == null) {
                throw RuntimeException("EGL上下文未初始化")
            }

            // 检查当前线程是否已有EGL上下文
            val currentContext = EGL14.eglGetCurrentContext()
            val currentDisplay = EGL14.eglGetCurrentDisplay()

            // 如果当前线程已有不同的EGL上下文，先释放
            if (currentContext != EGL14.EGL_NO_CONTEXT && currentContext != eglContext) {
                Log.w(TAG, "检测到不同的EGL上下文，先释放当前上下文")
                EGL14.eglMakeCurrent(
                    currentDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }

            // 确保当前上下文是活动的，增加重试机制
            var retryCount = 0
            val maxRetries = 3

            while (true) {
                if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    break
                }

                val error = EGL14.eglGetError()
                retryCount++

                if (retryCount >= maxRetries) {
                    Log.e(TAG, "无法激活EGL上下文，已重试$maxRetries 次，错误代码: $error")

                    // 尝试重新初始化EGL上下文
                    Log.w(TAG, "尝试重新初始化EGL上下文")
                    cleanupEGL()
                    if (!initializeEGL()) {
                        throw RuntimeException("无法激活EGL上下文，重新初始化失败，错误代码: $error")
                    }

                    // 重新尝试激活上下文
                    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        val newError = EGL14.eglGetError()
                        throw RuntimeException("无法激活EGL上下文，重新初始化后仍失败，错误代码: $newError")
                    }
                    break
                } else {
                    Log.w(TAG, "EGL上下文激活失败，重试第$retryCount 次，错误代码: $error")
                    Thread.sleep(10) // 短暂等待后重试
                }
            }

            // 检查OpenGL错误状态
            val glError = GLES30.glGetError()
            if (glError != GLES30.GL_NO_ERROR) {
                GLES30.glGetError() // 清除错误状态
                Log.w(TAG, "清除之前的OpenGL错误: $glError")
            }
        }

        // **关键修复：在创建帧缓冲区之前验证着色器程序**
        // 这样如果需要重新初始化GPU，不会影响后续创建的帧缓冲区
        if (shaderProgram == 0) {
            Log.w(TAG, "着色器程序未初始化，重新初始化GPU")
            cleanupGpu()
            initializeGpu()
            if (shaderProgram == 0) {
                throw RuntimeException("着色器程序初始化失败")
            }
        } else {
            // 验证着色器程序是否有效
            val isValid = IntArray(1)
            GLES30.glGetProgramiv(shaderProgram, GLES30.GL_LINK_STATUS, isValid, 0)
            if (isValid[0] == 0) {
                Log.e(TAG, "着色器程序无效，重新初始化GPU（在创建帧缓冲区之前）")
                cleanupGpu()
                initializeGpu()
                if (shaderProgram == 0) {
                    throw RuntimeException("重新初始化后着色器程序仍然无效")
                }
                Log.d(TAG, "着色器程序重新初始化成功，ID: $shaderProgram")
            }
        }

        // **关键修复：验证LUT纹理是否有效**
        if (lutTexture == 0) {
            Log.e(TAG, "LUT纹理无效，尝试重新上传")
            if (currentLut != null && currentLutSize > 0) {
                uploadLutToGpu(currentLut!!)
                Log.d(TAG, "LUT纹理重新创建完成，ID: $lutTexture")
            } else {
                throw RuntimeException("LUT纹理无效且没有可用的LUT数据")
            }
        }

        // 验证LUT纹理是否真的存在
        val isValidTexture = GLES30.glIsTexture(lutTexture)
        if (!isValidTexture) {
            Log.e(TAG, "LUT纹理ID无效: $lutTexture")
            if (currentLut != null && currentLutSize > 0) {
                uploadLutToGpu(currentLut!!)
                Log.d(TAG, "LUT纹理重新创建完成，ID: $lutTexture")
            } else {
                throw RuntimeException("LUT纹理ID无效且没有可用的LUT数据")
            }
        }

        Log.d(TAG, "LUT纹理验证通过，ID: $lutTexture")

        // **关键修复：验证第二个LUT纹理是否有效**
        if (currentLut2 != null && currentLut2Size > 0) {
            if (lut2Texture == 0) {
                Log.e(TAG, "第二个LUT纹理无效，尝试重新上传")
                uploadSecondLutToGpu(currentLut2!!)
                Log.d(TAG, "第二个LUT纹理重新创建完成，ID: $lut2Texture")
            } else {
                // 验证第二个LUT纹理是否真的存在
                val isValidLut2Texture = GLES30.glIsTexture(lut2Texture)
                if (!isValidLut2Texture) {
                    Log.e(TAG, "第二个LUT纹理ID无效: $lut2Texture")
                    uploadSecondLutToGpu(currentLut2!!)
                    Log.d(TAG, "第二个LUT纹理重新创建完成，ID: $lut2Texture")
                }
            }
            Log.d(TAG, "第二个LUT纹理验证通过，ID: $lut2Texture")
        } else {
            Log.d(TAG, "没有第二个LUT数据需要验证")
        }

        // 创建输入纹理前的额外检查
        Log.d(TAG, "开始创建输入纹理，尺寸: ${width}x${height}")

        // 检查OpenGL状态
        val glError = GLES30.glGetError()
        if (glError != GLES30.GL_NO_ERROR) {
            Log.w(TAG, "创建输入纹理前检测到OpenGL错误: $glError，清除状态")
        }

        // 检查bitmap的可访问性
        try {
            val testPixel = bitmap.getPixel(0, 0)
            Log.d(TAG, "Bitmap可访问，测试像素值: 0x${testPixel.toString(16)}")
        } catch (e: Exception) {
            throw RuntimeException("Bitmap不可访问: ${e.message}")
        }

        // 创建输入纹理
        val inputTexture = IntArray(1)
        GLES30.glGenTextures(1, inputTexture, 0)

        if (inputTexture[0] == 0) {
            throw RuntimeException("无法生成输入纹理ID")
        }

        Log.d(TAG, "生成输入纹理ID: ${inputTexture[0]}")
        
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture[0])
        checkGLError("绑定输入纹理")
        
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        checkGLError("设置输入纹理参数")

        // 使用GLUtils上传bitmap数据
        Log.d(TAG, "开始上传bitmap数据到GPU纹理")
        try {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            Log.d(TAG, "Bitmap数据上传成功")
        } catch (e: Exception) {
            Log.e(TAG, "GLUtils.texImage2D失败: ${e.message}")
            throw RuntimeException("GLUtils.texImage2D失败: ${e.message}", e)
        }
        checkGLError("创建输入纹理")

        // 创建帧缓冲区
        val framebuffer = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffer, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
        checkGLError("创建帧缓冲区")

        // 创建输出纹理
        val outputTexture = IntArray(1)
        GLES30.glGenTextures(1, outputTexture, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outputTexture[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        checkGLError("创建输出纹理")

        // 附加纹理到帧缓冲区
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            outputTexture[0],
            0
        )
        checkGLError("附加纹理到帧缓冲区")

        // 增强的帧缓冲区状态检查
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        Log.d(TAG, "帧缓冲区状态检查: $status (期望: ${GLES30.GL_FRAMEBUFFER_COMPLETE})")

        when (status) {
            GLES30.GL_FRAMEBUFFER_COMPLETE -> {
                Log.d(TAG, "帧缓冲区创建成功")
            }

            GLES30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> {
                throw RuntimeException("帧缓冲区附件不完整: $status")
            }

            GLES30.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> {
                throw RuntimeException("帧缓冲区缺少附件: $status")
            }

            GLES30.GL_FRAMEBUFFER_UNSUPPORTED -> {
                throw RuntimeException("帧缓冲区格式不支持: $status")
            }

            0 -> {
                // 状态为0通常表示EGL上下文问题，尝试重新初始化
                Log.w(TAG, "帧缓冲区状态为0，尝试重新初始化EGL上下文")
                cleanupGpu()
                initializeGpu()
                throw RuntimeException("帧缓冲区状态未定义，已重新初始化EGL上下文: ${0}")
            }

            else -> {
                throw RuntimeException("帧缓冲区状态异常: $status")
            }
        }

        // 设置视口
        GLES30.glViewport(0, 0, width, height)

        // 着色器程序已在创建帧缓冲区之前验证过，直接使用
        Log.d(TAG, "使用着色器程序，ID: $shaderProgram")
        GLES30.glUseProgram(shaderProgram)
        checkGLError("使用着色器程序")

        // 验证uniform位置是否有效
        if (inputTextureLocation == -1 || lutTextureLocation == -1 || strengthLocation == -1 ||
            ditherTypeLocation == -1 || lutSizeLocation == -1 || lut2TextureLocation == -1 ||
            lut2StrengthLocation == -1 || lut2SizeLocation == -1
        ) {
            Log.e(TAG, "uniform位置无效，重新获取")

            // 重新获取uniform位置
            inputTextureLocation = GLES30.glGetUniformLocation(shaderProgram, "u_inputTexture")
            lutTextureLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lutTexture")
            lut2TextureLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lut2Texture")
            strengthLocation = GLES30.glGetUniformLocation(shaderProgram, "u_strength")
            lut2StrengthLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lut2Strength")
            ditherTypeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_ditherType")
            lutSizeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lutSize")
            lut2SizeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lut2Size")

            Log.d(
                TAG,
                "重新获取uniform位置: input=$inputTextureLocation, lut=$lutTextureLocation, lut2=$lut2TextureLocation, strength=$strengthLocation, lut2Strength=$lut2StrengthLocation, dither=$ditherTypeLocation, size=$lutSizeLocation, lut2Size=$lut2SizeLocation"
            )

            if (inputTextureLocation == -1 || lutTextureLocation == -1 || strengthLocation == -1 ||
                ditherTypeLocation == -1 || lutSizeLocation == -1 || lut2TextureLocation == -1 ||
                lut2StrengthLocation == -1 || lut2SizeLocation == -1
            ) {
                throw RuntimeException("重新获取uniform位置失败")
            }
        }

        // 修复：先激活纹理单元，再绑定纹理，最后设置uniform
        // 绑定输入纹理到纹理单元0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture[0])
        GLES30.glUniform1i(inputTextureLocation, 0)

        // 绑定主LUT纹理到纹理单元1
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(lutTextureLocation, 1)

        // 绑定第二个LUT纹理到纹理单元2（如果存在）
        var hasSecondLut = false
        if (lut2Texture != 0 && params.lut2Strength > 0f) {
            Log.d(
                TAG,
                "绑定第二个LUT纹理到纹理单元2，纹理ID: $lut2Texture，强度: ${params.lut2Strength}"
            )
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lut2Texture)
            GLES30.glUniform1i(lut2TextureLocation, 2)
            hasSecondLut = true
        } else {
            Log.d(
                TAG,
                "未绑定第二个LUT纹理，lut2Texture: $lut2Texture，lut2Strength: ${params.lut2Strength}"
            )
            // 如果没有第二个LUT，也需要设置一个默认纹理位置（可以使用主LUT）
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES30.glUniform1i(lut2TextureLocation, 2)
        }

        // 设置其他uniform变量
        GLES30.glUniform1f(strengthLocation, params.strength)
        GLES30.glUniform1f(lut2StrengthLocation, if (hasSecondLut) params.lut2Strength else 0f)
        GLES30.glUniform1i(ditherTypeLocation, params.ditherType.ordinal)
        GLES30.glUniform1f(lutSizeLocation, currentLutSize.toFloat())
        GLES30.glUniform1f(
            lut2SizeLocation,
            if (hasSecondLut) currentLut2Size.toFloat() else currentLutSize.toFloat()
        )
        
        // 设置胶片颗粒uniform变量
        val grainConfig = currentGrainConfig
        val grainEnabled = grainConfig?.isEnabled == true && grainConfig.globalStrength > 0f
        GLES30.glUniform1i(grainEnabledLocation, if (grainEnabled) 1 else 0)
        if (grainEnabled && grainConfig != null) {
            GLES30.glUniform1f(grainStrengthLocation, grainConfig.globalStrength)
            GLES30.glUniform1f(grainSizeLocation, grainConfig.grainSize)
            GLES30.glUniform1f(grainSeedLocation, kotlin.random.Random.nextFloat() * 1000f)
            GLES30.glUniform1f(shadowGrainRatioLocation, grainConfig.shadowGrainRatio)
            GLES30.glUniform1f(midtoneGrainRatioLocation, grainConfig.midtoneGrainRatio)
            GLES30.glUniform1f(highlightGrainRatioLocation, grainConfig.highlightGrainRatio)
            GLES30.glUniform1f(shadowSizeRatioLocation, grainConfig.shadowSizeRatio)
            GLES30.glUniform1f(highlightSizeRatioLocation, grainConfig.highlightSizeRatio)
            GLES30.glUniform1f(redChannelRatioLocation, grainConfig.redChannelRatio)
            GLES30.glUniform1f(greenChannelRatioLocation, grainConfig.greenChannelRatio)
            GLES30.glUniform1f(blueChannelRatioLocation, grainConfig.blueChannelRatio)
            GLES30.glUniform1f(channelCorrelationLocation, grainConfig.channelCorrelation)
            GLES30.glUniform1f(colorPreservationLocation, grainConfig.colorPreservation)
            Log.d(TAG, "胶片颗粒已启用，强度: ${grainConfig.globalStrength}")
        } else {
            Log.d(TAG, "胶片颗粒未启用")
        }

        Log.d(TAG, "设置uniform变量完成:")
        Log.d(TAG, "  - u_strength: ${params.strength}")
        Log.d(TAG, "  - u_lut2Strength: ${if (hasSecondLut) params.lut2Strength else 0f}")
        Log.d(TAG, "  - u_ditherType: ${params.ditherType.ordinal}")
        Log.d(TAG, "  - u_lutSize: ${currentLutSize.toFloat()}")
        Log.d(
            TAG,
            "  - u_lut2Size: ${if (hasSecondLut) currentLut2Size.toFloat() else currentLutSize.toFloat()}"
        )
        Log.d(TAG, "  - hasSecondLut: $hasSecondLut")

        checkGLError("设置uniform变量")

        // 验证纹理绑定
        val boundTexture = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_3D, boundTexture, 0)
        Log.d(TAG, "当前绑定的3D纹理ID: ${boundTexture[0]}, 期望: $lutTexture")

        Log.d(
            TAG,
            "Uniform变量设置: strength=${params.strength}, lut2Strength=${params.lut2Strength}, ditherType=${params.ditherType.ordinal}, lutSize=$currentLutSize, lut2Size=$currentLut2Size"
        )

        // 在设置uniform变量后添加
        Log.d(TAG, "详细调试信息:")
        Log.d(TAG, "  输入纹理ID: ${inputTexture[0]}")
        Log.d(TAG, "  主LUT纹理ID: $lutTexture")
        Log.d(TAG, "  第二个LUT纹理ID: $lut2Texture")
        Log.d(TAG, "  着色器程序ID: $shaderProgram")
        Log.d(TAG, "  主LUT强度值: ${params.strength}")
        Log.d(TAG, "  第二个LUT强度值: ${params.lut2Strength}")
        Log.d(TAG, "  主LUT尺寸: $currentLutSize")
        Log.d(TAG, "  第二个LUT尺寸: $currentLut2Size")
        Log.d(TAG, "  视口大小: ${width}x${height}")

        // 创建并使用VBO来绑定顶点数据
        val vbo = IntArray(2)
        GLES30.glGenBuffers(2, vbo, 0)

        // 绑定顶点坐标
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexBuffer!!.capacity() * 4,
            vertexBuffer,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

        // 绑定纹理坐标
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            textureCoordBuffer!!.capacity() * 4,
            textureCoordBuffer,
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, 0)

        checkGLError("绑定顶点缓冲区")

        // 禁用深度测试和混合
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        // 在渲染前添加纹理验证
        val inputBound = IntArray(1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, inputBound, 0)
        Log.d(TAG, "当前绑定的2D纹理: ${inputBound[0]}, 期望: ${inputTexture[0]}")

        val lutBound = IntArray(1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_3D, lutBound, 0)
        Log.d(TAG, "当前绑定的主LUT 3D纹理: ${lutBound[0]}, 期望: $lutTexture")

        if (hasSecondLut) {
            val lut2Bound = IntArray(1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_3D, lut2Bound, 0)
            Log.d(TAG, "当前绑定的第二个LUT 3D纹理: ${lut2Bound[0]}, 期望: $lut2Texture")
        }

        // 渲染
        Log.d(TAG, "开始渲染")
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        checkGLError("渲染")

        // 强制完成所有OpenGL操作
        GLES30.glFinish()

        // 清理VBO
        GLES30.glDeleteBuffers(2, vbo, 0)

        // 读取结果 - 使用内存优化策略
        Log.d(TAG, "开始读取像素数据，图片尺寸: ${bitmap.width}x${bitmap.height}")

        val pixels = try {
            getOrCreateBuffer(bitmap.width, bitmap.height, "pixels")
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "像素缓冲区分配失败，图片过大: ${bitmap.width}x${bitmap.height}")
            throw e
        }

        // 确保从正确的帧缓冲区读取
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
        GLES30.glReadPixels(
            0,
            0,
            bitmap.width,
            bitmap.height,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels
        )
        checkGLError("读取像素")

        // 重置buffer位置到开始
        pixels.rewind()

        // 内存优化：直接在原buffer中进行翻转，避免分配第二个大缓冲区
        Log.d(TAG, "开始原地翻转像素数据")
        val rowSize = width * 4 // 每行字节数（RGBA = 4字节/像素）
        val tempRow = ByteArray(rowSize)

        // 原地翻转：交换上下行
        for (y in 0 until height / 2) {
            val topRowOffset = y * rowSize
            val bottomRowOffset = (height - 1 - y) * rowSize

            // 读取顶部行
            pixels.position(topRowOffset)
            pixels.get(tempRow, 0, rowSize)

            // 将底部行复制到顶部
            pixels.position(bottomRowOffset)
            val bottomRow = ByteArray(rowSize)
            pixels.get(bottomRow, 0, rowSize)
            pixels.position(topRowOffset)
            pixels.put(bottomRow)

            // 将原顶部行复制到底部
            pixels.position(bottomRowOffset)
            pixels.put(tempRow)
        }

        // 重置buffer位置
        pixels.rewind()

        // 创建结果bitmap
        val resultBitmap = createBitmap(bitmap.width, bitmap.height)
        resultBitmap.copyPixelsFromBuffer(pixels)

        // 验证输出不是纯黑色
        val testWidth = minOf(10, resultBitmap.width)
        val testHeight = minOf(10, resultBitmap.height)
        val testPixels = IntArray(testWidth * testHeight)
        resultBitmap.getPixels(testPixels, 0, testWidth, 0, 0, testWidth, testHeight)
        val hasNonBlackPixels = testPixels.any { (it and 0xFFFFFF) != 0 }
        Log.d(TAG, "输出验证: 是否包含非黑色像素: $hasNonBlackPixels")

        if (!hasNonBlackPixels) {
            Log.w(TAG, "检测到纯黑色输出，可能存在渲染问题")
            // 添加更详细的调试信息
            Log.d(
                TAG,
                "调试信息: inputTexture=${inputTexture[0]}, lutTexture=$lutTexture, shaderProgram=$shaderProgram"
            )
            Log.d(TAG, "调试信息: strength=${params.strength}, lutSize=$currentLutSize")
            Log.d(
                TAG,
                "调试信息: 帧缓冲区状态=${GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)}"
            )

            // 读取一些像素进行详细分析
            val debugWidth = minOf(4, resultBitmap.width)
            val debugHeight = minOf(4, resultBitmap.height)
            val debugPixels = IntArray(debugWidth * debugHeight)
            resultBitmap.getPixels(debugPixels, 0, debugWidth, 0, 0, debugWidth, debugHeight)
            Log.d(
                TAG,
                "前${debugWidth * debugHeight}个像素值: ${
                    debugPixels.joinToString {
                        "0x${
                            it.toString(16)
                        }"
                    }
                }"
            )
        }

        Log.d(TAG, "GPU处理完成，输出图片尺寸: ${resultBitmap.width}x${resultBitmap.height}")

        // 清理资源
        GLES30.glDeleteTextures(1, inputTexture, 0)
        GLES30.glDeleteTextures(1, outputTexture, 0)
        GLES30.glDeleteFramebuffers(1, framebuffer, 0)

        return@withContext resultBitmap
    }

    private fun initializeGpu() {
        try {
            Log.d(TAG, "步骤1: 开始初始化EGL上下文")
            // 初始化EGL上下文
            if (!initializeEGL()) {
                throw RuntimeException("EGL初始化失败")
            }
            Log.d(TAG, "步骤1: EGL上下文初始化成功")

            Log.d(TAG, "步骤2: 开始编译着色器")
            // 编译着色器
            val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, createFragmentShader())
            Log.d(
                TAG,
                "步骤2: 着色器编译成功，顶点着色器ID: $vertexShader, 片段着色器ID: $fragmentShader"
            )

            Log.d(TAG, "步骤3: 开始创建着色器程序")
            // 创建着色器程序
            shaderProgram = GLES30.glCreateProgram()
            if (shaderProgram == 0) {
                throw RuntimeException("创建着色器程序失败")
            }

            GLES30.glAttachShader(shaderProgram, vertexShader)
            checkGLError("附加顶点着色器")

            GLES30.glAttachShader(shaderProgram, fragmentShader)
            checkGLError("附加片段着色器")

            GLES30.glLinkProgram(shaderProgram)
            checkGLError("链接着色器程序")

            // 检查链接状态
            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(shaderProgram, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val error = GLES30.glGetProgramInfoLog(shaderProgram)
                GLES30.glDeleteProgram(shaderProgram)
                shaderProgram = 0
                throw RuntimeException("着色器程序链接失败: $error")
            }
            Log.d(TAG, "步骤3: 着色器程序创建成功，ID: $shaderProgram")

            Log.d(TAG, "步骤4: 开始获取uniform位置")
            // 获取uniform位置
            inputTextureLocation = GLES30.glGetUniformLocation(shaderProgram, "u_inputTexture")
            lutTextureLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lutTexture")
            lut2TextureLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lut2Texture")
            strengthLocation = GLES30.glGetUniformLocation(shaderProgram, "u_strength")
            lut2StrengthLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lut2Strength")
            ditherTypeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_ditherType")
            lutSizeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lutSize")
            lut2SizeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lut2Size")
            
            // 获取胶片颗粒uniform位置
            grainEnabledLocation = GLES30.glGetUniformLocation(shaderProgram, "u_grainEnabled")
            grainStrengthLocation = GLES30.glGetUniformLocation(shaderProgram, "u_grainStrength")
            grainSizeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_grainSize")
            grainSeedLocation = GLES30.glGetUniformLocation(shaderProgram, "u_grainSeed")
            shadowGrainRatioLocation = GLES30.glGetUniformLocation(shaderProgram, "u_shadowGrainRatio")
            midtoneGrainRatioLocation = GLES30.glGetUniformLocation(shaderProgram, "u_midtoneGrainRatio")
            highlightGrainRatioLocation = GLES30.glGetUniformLocation(shaderProgram, "u_highlightGrainRatio")
            shadowSizeRatioLocation = GLES30.glGetUniformLocation(shaderProgram, "u_shadowSizeRatio")
            highlightSizeRatioLocation = GLES30.glGetUniformLocation(shaderProgram, "u_highlightSizeRatio")
            redChannelRatioLocation = GLES30.glGetUniformLocation(shaderProgram, "u_redChannelRatio")
            greenChannelRatioLocation = GLES30.glGetUniformLocation(shaderProgram, "u_greenChannelRatio")
            blueChannelRatioLocation = GLES30.glGetUniformLocation(shaderProgram, "u_blueChannelRatio")
            channelCorrelationLocation = GLES30.glGetUniformLocation(shaderProgram, "u_channelCorrelation")
            colorPreservationLocation = GLES30.glGetUniformLocation(shaderProgram, "u_colorPreservation")

            Log.d(TAG, "uniform位置获取结果:")
            Log.d(TAG, "  u_inputTexture: $inputTextureLocation")
            Log.d(TAG, "  u_lutTexture: $lutTextureLocation")
            Log.d(TAG, "  u_lut2Texture: $lut2TextureLocation")
            Log.d(TAG, "  u_strength: $strengthLocation")
            Log.d(TAG, "  u_lut2Strength: $lut2StrengthLocation")
            Log.d(TAG, "  u_ditherType: $ditherTypeLocation")
            Log.d(TAG, "  u_lutSize: $lutSizeLocation")
            Log.d(TAG, "  u_lut2Size: $lut2SizeLocation")
            Log.d(TAG, "  u_grainEnabled: $grainEnabledLocation")
            Log.d(TAG, "  u_grainStrength: $grainStrengthLocation")

            if (inputTextureLocation == -1 || lutTextureLocation == -1 || strengthLocation == -1 ||
                ditherTypeLocation == -1 || lutSizeLocation == -1 || lut2TextureLocation == -1 ||
                lut2StrengthLocation == -1 || lut2SizeLocation == -1
            ) {
                throw RuntimeException("获取uniform位置失败")
            }
            Log.d(TAG, "步骤4: uniform位置获取成功")

            Log.d(TAG, "步骤5: 开始创建顶点缓冲区")
            // 创建顶点缓冲区
            createVertexBuffers()
            Log.d(TAG, "步骤5: 顶点缓冲区创建成功")

            // **关键修复：重新上传LUT数据**
            Log.d(TAG, "步骤6: 检查并重新上传LUT数据")
            if (currentLut != null && currentLutSize > 0) {
                Log.d(TAG, "重新上传主LUT数据到GPU，尺寸: $currentLutSize")
                uploadLutToGpu(currentLut!!)
                Log.d(TAG, "主LUT数据重新上传完成，纹理ID: $lutTexture")
            } else {
                Log.w(TAG, "没有可用的主LUT数据进行重新上传")
            }

            if (currentLut2 != null && currentLut2Size > 0) {
                Log.d(TAG, "重新上传第二个LUT数据到GPU，尺寸: $currentLut2Size")
                uploadSecondLutToGpu(currentLut2!!)
                Log.d(TAG, "第二个LUT数据重新上传完成，纹理ID: $lut2Texture")
            } else {
                Log.d(TAG, "没有第二个LUT数据，跳过重新上传")
            }

            // **新增：获取GPU最大纹理尺寸**
            Log.d(TAG, "步骤7: 获取GPU最大纹理尺寸")
            val maxTextureSizeArray = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSizeArray, 0)
            maxTextureSize = maxTextureSizeArray[0].coerceAtMost(8192) // 限制在合理范围内
            Log.d(TAG, "GPU最大纹理尺寸: $maxTextureSize")

            isInitialized = true
            Log.d(TAG, "GPU处理器初始化完成")

            // 清理着色器对象
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)

        } catch (e: Exception) {
            Log.e(TAG, "GPU初始化失败", e)
            cleanupGpu()
            throw e
        }
    }

    private fun initializeEGL(): Boolean {
        try {
            // 清理之前的EGL资源
            cleanupEGL()

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "无法获取EGL显示")
                return false
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                val error = EGL14.eglGetError()
                Log.e(TAG, "无法初始化EGL，错误代码: $error")
                return false
            }

            Log.d(TAG, "EGL版本: ${version[0]}.${version[1]}")

            // 修复EGL配置 - 优先尝试OpenGL ES 3.0，失败则回退到2.0
            val configs = arrayOfNulls<EGLConfig>(10)
            val numConfigs = IntArray(1)

            // 首先尝试OpenGL ES 3.0配置
            val configAttribs30 = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x0040, // EGL_OPENGL_ES3_BIT
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )

            var success = EGL14.eglChooseConfig(
                eglDisplay,
                configAttribs30,
                0,
                configs,
                0,
                configs.size,
                numConfigs,
                0
            )
            val useES3 = success && numConfigs[0] > 0

            if (!useES3) {
                Log.w(TAG, "OpenGL ES 3.0不可用，回退到ES 2.0")
                // 回退到OpenGL ES 2.0配置
                val configAttribs20 = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 0,
                    EGL14.EGL_STENCIL_SIZE, 0,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
                )
                success = EGL14.eglChooseConfig(
                    eglDisplay,
                    configAttribs20,
                    0,
                    configs,
                    0,
                    configs.size,
                    numConfigs,
                    0
                )
            }

            if (!success || numConfigs[0] <= 0) {
                val error = EGL14.eglGetError()
                Log.e(TAG, "无法选择EGL配置，错误代码: $error")
                return false
            }

            eglConfig = configs[0]!!
            Log.d(TAG, "选择了EGL配置，可用配置数: ${numConfigs[0]}")

            // 创建EGL上下文，增加线程安全属性
            val contextAttribs = if (useES3) {
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    0x3098, 1, // EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT
                    EGL14.EGL_NONE
                )
            } else {
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    0x3098, 1, // EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT
                    EGL14.EGL_NONE
                )
            }

            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                eglConfig,
                EGL14.EGL_NO_CONTEXT,
                contextAttribs,
                0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                val error = EGL14.eglGetError()
                Log.e(TAG, "无法创建EGL上下文，错误代码: $error")

                // 如果带扩展属性失败，尝试基本配置
                val basicContextAttribs = if (useES3) {
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
                } else {
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                }

                eglContext = EGL14.eglCreateContext(
                    eglDisplay,
                    eglConfig,
                    EGL14.EGL_NO_CONTEXT,
                    basicContextAttribs,
                    0
                )
                if (eglContext == EGL14.EGL_NO_CONTEXT) {
                    val newError = EGL14.eglGetError()
                    Log.e(TAG, "无法创建基本EGL上下文，错误代码: $newError")
                    return false
                }
            }

            // 创建PBuffer表面
            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                val error = EGL14.eglGetError()
                Log.e(TAG, "无法创建EGL表面，错误代码: $error")
                return false
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                val error = EGL14.eglGetError()
                Log.e(TAG, "无法设置当前EGL上下文，错误代码: $error")
                return false
            }

            Log.d(TAG, "EGL初始化成功，使用OpenGL ES ${if (useES3) "3.0" else "2.0"}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "EGL初始化异常", e)
            return false
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            throw RuntimeException("无法创建着色器，类型: $type")
        }

        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("着色器编译失败，类型: $type, 错误: $error")
        }

        Log.d(TAG, "着色器编译成功，类型: $type")
        return shader
    }

    private fun createVertexBuffers() {
        // 创建全屏四边形的顶点坐标
        val vertices = floatArrayOf(
            -1.0f, -1.0f,  // 左下
            1.0f, -1.0f,  // 右下
            -1.0f, 1.0f,  // 左上
            1.0f, 1.0f   // 右上
        )

        // 修复纹理坐标，Android需要Y轴翻转
        val textureCoords = floatArrayOf(
            0.0f, 1.0f,  // 左下 -> 对应纹理左上（Y轴翻转）
            1.0f, 1.0f,  // 右下 -> 对应纹理右上（Y轴翻转）
            0.0f, 0.0f,  // 左上 -> 对应纹理左下（Y轴翻转）
            1.0f, 0.0f   // 右上 -> 对应纹理右下（Y轴翻转）
        )

        // 创建顶点缓冲区
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer?.put(vertices)
        vertexBuffer?.position(0)

        // 创建纹理坐标缓冲区
        textureCoordBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        textureCoordBuffer?.put(textureCoords)
        textureCoordBuffer?.position(0)

        Log.d(TAG, "顶点缓冲区创建完成")
    }


    private fun cleanupEGL() {
        try {
            // 线程安全的EGL清理
            synchronized(this) {
                if (eglDisplay != null) {
                    // 先释放当前上下文
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )

                    if (eglSurface != null) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        eglSurface = null
                    }

                    if (eglContext != null) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                        eglContext = null
                    }

                    EGL14.eglTerminate(eglDisplay)
                    eglDisplay = null
                }

                eglConfig = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理EGL资源时发生异常", e)
        }
    }

    private fun cleanupGpu() {
        try {
            Log.d(TAG, "开始清理GPU资源")

            // 重置uniform位置
            inputTextureLocation = -1
            lutTextureLocation = -1
            lut2TextureLocation = -1
            strengthLocation = -1
            lut2StrengthLocation = -1
            ditherTypeLocation = -1
            lutSizeLocation = -1
            lut2SizeLocation = -1

            // 删除主LUT纹理
            if (lutTexture != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
                lutTexture = 0
            }

            // 删除第二个LUT纹理
            if (lut2Texture != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lut2Texture), 0)
                lut2Texture = 0
            }

            // 删除着色器程序
            if (shaderProgram != 0) {
                GLES30.glDeleteProgram(shaderProgram)
                shaderProgram = 0
            }

            // 清理顶点缓冲区
            vertexBuffer = null
            textureCoordBuffer = null

            isInitialized = false
            Log.d(TAG, "GPU资源清理完成")

        } catch (e: Exception) {
            Log.e(TAG, "清理GPU资源时发生异常", e)
        }
    }

    // 添加缺失的getProcessorInfo方法
    override fun getProcessorInfo(): String {
        val lut1Info =
            if (currentLutSize > 0) "主LUT: ${currentLutSize}x${currentLutSize}x${currentLutSize}" else "无主LUT"
        val lut2Info =
            if (currentLut2Size > 0) ", 第二个LUT: ${currentLut2Size}x${currentLut2Size}x${currentLut2Size}" else ", 无第二个LUT"
        return if (isInitialized) {
            "GPU LUT处理器 - OpenGL ES 3.0 硬件加速处理，当前LUT: $lut1Info$lut2Info"
        } else {
            "GPU LUT处理器 - 未初始化"
        }
    }
    
    /**
     * 设置胶片颗粒配置
     * @param config 颗粒配置，传null则禁用颗粒效果
     */
    fun setFilmGrainConfig(config: cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig?) {
        currentGrainConfig = config
        Log.d(TAG, "设置胶片颗粒配置: ${if (config?.isEnabled == true) "启用，强度=${config.globalStrength}" else "禁用"}")
    }
    
    /**
     * 获取当前胶片颗粒配置
     */
    fun getFilmGrainConfig(): cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig? {
        return currentGrainConfig
    }
}
