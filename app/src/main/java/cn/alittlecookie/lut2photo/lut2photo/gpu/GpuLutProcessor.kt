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
        // 移除固定的LUT_3D_SIZE，改为动态获取

        // 添加CPU处理器作为后备
        private val cpuProcessor = CpuLutProcessor()

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
                uniform float u_strength;
                uniform int u_ditherType;
                uniform float u_lutSize;
                
                // Random function for dithering
                float random(vec2 co) {
                    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
                }
                
                // Floyd-Steinberg dithering approximation
                vec3 applyFloydSteinbergDither(vec3 color, vec2 coord) {
                    float noise = (random(coord) - 0.5) / 255.0;
                    return color + vec3(noise);
                }
                
                // Random dithering
                vec3 applyRandomDither(vec3 color, vec2 coord) {
                    float noise = (random(coord) - 0.5) / 128.0;
                    return color + vec3(noise);
                }
                
                void main() {
                    vec4 originalColor = texture(u_inputTexture, v_texCoord);
                    
                    // 正确的LUT坐标计算
                    vec3 scaledColor = originalColor.rgb * (u_lutSize - 1.0);
                    vec3 lutCoord = (scaledColor + 0.5) / u_lutSize;
                    
                    // 确保坐标在有效范围内
                    lutCoord = clamp(lutCoord, 0.0, 1.0);
                    
                    // 直接使用LUT查找结果，移除错误的调试逻辑
                    vec3 lutColor = texture(u_lutTexture, lutCoord).rgb;
                    
                    // 限制强度范围
                    float clampedStrength = clamp(u_strength, 0.0, 1.0);
                    vec3 blendedColor = mix(originalColor.rgb, lutColor, clampedStrength);
                    
                    // Apply dithering if enabled
                    vec3 finalColor = blendedColor;
                    if (u_ditherType == 1) { // Floyd-Steinberg
                        finalColor = applyFloydSteinbergDither(blendedColor, gl_FragCoord.xy);
                    } else if (u_ditherType == 2) { // Random
                        finalColor = applyRandomDither(blendedColor, gl_FragCoord.xy);
                    }
                    
                    fragColor = vec4(clamp(finalColor, 0.0, 1.0), originalColor.a);
                }
            """
        }
    }

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
    private var strengthLocation: Int = 0
    private var ditherTypeLocation: Int = 0
    private var lutSizeLocation: Int = 0

    private var isInitialized = false
    private var lutTexture: Int = 0
    private var currentLut: Array<Array<Array<FloatArray>>>? = null
    private var currentLutSize: Int = 0


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
                        Log.e(TAG, "GPU处理器初始化失败", e)
                        throw e
                    }
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

    override suspend fun processImage(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? {
        Log.d(TAG, "开始GPU处理，图片尺寸: ${bitmap.width}x${bitmap.height}")

        return try {
            val result = processImageOnGpu(bitmap, params)
            Log.d(TAG, "GPU处理成功")
            Log.i(TAG, "最终处理结果：GPU处理成功")
            result
        } catch (e: Exception) {
            Log.w(TAG, "GPU处理失败，回退到CPU处理: ${e.message}")

            // 确保CPU处理器有正确的LUT数据
            if (currentLut != null) {
                Log.d(
                    TAG,
                    "同步LUT数据到CPU处理器，尺寸: ${currentLutSize}x${currentLutSize}x${currentLutSize}"
                )
                cpuProcessor.setLutData(currentLut, currentLutSize)

                // 验证同步是否成功
                if (cpuProcessor.getLutData() != null) {
                    Log.d(TAG, "LUT数据同步成功，CPU处理器LUT尺寸: ${cpuProcessor.getLutSize()}")
                } else {
                    Log.e(TAG, "LUT数据同步失败")
                    return null
                }
            } else {
                Log.e(TAG, "GPU处理器没有LUT数据，无法同步到CPU处理器")
                return null
            }

            Log.d(TAG, "使用CPU处理器进行回退处理")
            val cpuResult = cpuProcessor.processImage(bitmap, params)
            if (cpuResult != null) {
                Log.d(TAG, "CPU回退处理成功")
                Log.i(TAG, "最终处理结果：CPU回退处理成功")
            } else {
                Log.e(TAG, "CPU回退处理失败")
                Log.e(TAG, "最终处理结果：处理失败")
            }
            cpuResult
        }
    }

    // 添加 release 方法
    override suspend fun release() {
        withContext(Dispatchers.Main) {
            cleanupGpu()
        }
    }

    private suspend fun processImageOnGpu(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap = withContext(Dispatchers.Main) {
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

        val width = bitmap.width
        val height = bitmap.height

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

        // 创建输入纹理
        val inputTexture = IntArray(1)
        GLES30.glGenTextures(1, inputTexture, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture[0])
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

        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
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

        // 验证着色器程序状态并使用
        if (shaderProgram == 0) {
            throw RuntimeException("着色器程序未初始化")
        }

        // 验证着色器程序是否有效
        val isValid = IntArray(1)
        GLES30.glGetProgramiv(shaderProgram, GLES30.GL_LINK_STATUS, isValid, 0)
        if (isValid[0] == 0) {
            Log.e(TAG, "着色器程序无效，重新初始化GPU")
            cleanupGpu()
            initializeGpu()

            // 重新验证
            if (shaderProgram == 0) {
                throw RuntimeException("重新初始化后着色器程序仍然无效")
            }
        }

        Log.d(TAG, "使用着色器程序，ID: $shaderProgram")
        GLES30.glUseProgram(shaderProgram)
        checkGLError("使用着色器程序")

        // 验证uniform位置是否有效
        if (inputTextureLocation == -1 || lutTextureLocation == -1 || strengthLocation == -1 ||
            ditherTypeLocation == -1 || lutSizeLocation == -1
        ) {
            Log.e(TAG, "uniform位置无效，重新获取")

            // 重新获取uniform位置
            inputTextureLocation = GLES30.glGetUniformLocation(shaderProgram, "u_inputTexture")
            lutTextureLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lutTexture")
            strengthLocation = GLES30.glGetUniformLocation(shaderProgram, "u_strength")
            ditherTypeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_ditherType")
            lutSizeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lutSize")

            Log.d(
                TAG,
                "重新获取uniform位置: input=$inputTextureLocation, lut=$lutTextureLocation, strength=$strengthLocation, dither=$ditherTypeLocation, size=$lutSizeLocation"
            )

            if (inputTextureLocation == -1 || lutTextureLocation == -1 || strengthLocation == -1 ||
                ditherTypeLocation == -1 || lutSizeLocation == -1
            ) {
                throw RuntimeException("重新获取uniform位置失败")
            }
        }

        // 修复：先激活纹理单元，再绑定纹理，最后设置uniform
        // 绑定输入纹理到纹理单元0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture[0])
        GLES30.glUniform1i(inputTextureLocation, 0)

        // 绑定LUT纹理到纹理单元1
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glUniform1i(lutTextureLocation, 1)

        // 设置其他uniform变量
        GLES30.glUniform1f(strengthLocation, params.strength)
        GLES30.glUniform1i(ditherTypeLocation, params.ditherType.ordinal)
        GLES30.glUniform1f(lutSizeLocation, currentLutSize.toFloat())

        checkGLError("设置uniform变量")

        // 验证纹理绑定
        val boundTexture = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_3D, boundTexture, 0)
        Log.d(TAG, "当前绑定的3D纹理ID: ${boundTexture[0]}, 期望: $lutTexture")

        Log.d(
            TAG,
            "Uniform变量设置: strength=${params.strength}, ditherType=${params.ditherType.ordinal}, lutSize=$currentLutSize"
        )

        // 在设置uniform变量后添加
        Log.d(TAG, "详细调试信息:")
        Log.d(TAG, "  输入纹理ID: ${inputTexture[0]}")
        Log.d(TAG, "  LUT纹理ID: $lutTexture")
        Log.d(TAG, "  着色器程序ID: $shaderProgram")
        Log.d(TAG, "  强度值: ${params.strength}")
        Log.d(TAG, "  LUT尺寸: $currentLutSize")
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
        Log.d(TAG, "当前绑定的3D纹理: ${lutBound[0]}, 期望: $lutTexture")

        // 渲染
        Log.d(TAG, "开始渲染")
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        checkGLError("渲染")

        // 强制完成所有OpenGL操作
        GLES30.glFinish()

        // 清理VBO
        GLES30.glDeleteBuffers(2, vbo, 0)

        // 读取结果
        Log.d(TAG, "开始读取像素数据")
        val pixels = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4)
        pixels.order(ByteOrder.nativeOrder())

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

        // 修复：手动翻转像素数据以解决上下翻转问题
        val flippedPixels = ByteBuffer.allocateDirect(width * height * 4)
        flippedPixels.order(ByteOrder.nativeOrder())

        // 逐行翻转像素数据
        val rowSize = width * 4 // 每行字节数（RGBA = 4字节/像素）
        val tempRow = ByteArray(rowSize)

        for (y in 0 until height) {
            // 从原始数据的底部开始读取（OpenGL坐标系）
            val srcOffset = (height - 1 - y) * rowSize
            pixels.position(srcOffset)
            pixels.get(tempRow, 0, rowSize)

            // 写入到翻转后的buffer的顶部（Android坐标系）
            val dstOffset = y * rowSize
            flippedPixels.position(dstOffset)
            flippedPixels.put(tempRow)
        }

        // 重置buffer位置
        flippedPixels.rewind()

        // 创建结果bitmap
        val resultBitmap = createBitmap(bitmap.width, bitmap.height)
        resultBitmap.copyPixelsFromBuffer(flippedPixels)

        // 验证输出不是纯黑色
        val testPixels = IntArray(100)
        resultBitmap.getPixels(testPixels, 0, 10, 0, 0, 10, 10)
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
            val debugPixels = IntArray(16)
            resultBitmap.getPixels(debugPixels, 0, 4, 0, 0, 4, 4)
            Log.d(TAG, "前16个像素值: ${debugPixels.joinToString { "0x${it.toString(16)}" }}")
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
            strengthLocation = GLES30.glGetUniformLocation(shaderProgram, "u_strength")
            ditherTypeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_ditherType")
            lutSizeLocation = GLES30.glGetUniformLocation(shaderProgram, "u_lutSize")

            Log.d(TAG, "uniform位置获取结果:")
            Log.d(TAG, "  u_inputTexture: $inputTextureLocation")
            Log.d(TAG, "  u_lutTexture: $lutTextureLocation")
            Log.d(TAG, "  u_strength: $strengthLocation")
            Log.d(TAG, "  u_ditherType: $ditherTypeLocation")
            Log.d(TAG, "  u_lutSize: $lutSizeLocation")

            if (inputTextureLocation == -1 || lutTextureLocation == -1 || strengthLocation == -1 ||
                ditherTypeLocation == -1 || lutSizeLocation == -1
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
                Log.d(TAG, "重新上传LUT数据到GPU，尺寸: $currentLutSize")
                uploadLutToGpu(currentLut!!)
                Log.d(TAG, "LUT数据重新上传完成，纹理ID: $lutTexture")
            } else {
                Log.w(TAG, "没有可用的LUT数据进行重新上传")
            }

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
            strengthLocation = -1
            ditherTypeLocation = -1
            lutSizeLocation = -1

            // 删除LUT纹理
            if (lutTexture != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
                lutTexture = 0
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
        return if (isInitialized) {
            "GPU LUT处理器 - OpenGL ES 3.0 硬件加速处理，当前LUT尺寸: ${currentLutSize}x${currentLutSize}x${currentLutSize}"
        } else {
            "GPU LUT处理器 - 未初始化"
        }
    }
}
