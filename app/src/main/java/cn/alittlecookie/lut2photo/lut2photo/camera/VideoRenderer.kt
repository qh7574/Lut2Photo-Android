package cn.alittlecookie.lut2photo.lut2photo.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0视频渲染器
 * 支持LUT效果、峰值显示、波形图等视频处理功能
 */
class VideoRenderer(private val context: Context) : GLSurfaceView.Renderer {
    companion object {
        private const val TAG = "VideoRenderer"

        // 顶点着色器代码
        private const val VERTEX_SHADER_CODE = """
            #version 300 es
            layout (location = 0) in vec4 aPosition;
            layout (location = 1) in vec2 aTexCoord;
            
            uniform mat4 uMVPMatrix;
            
            out vec2 vTexCoord;
            
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // 基础片段着色器代码
        private const val FRAGMENT_SHADER_CODE = """
            #version 300 es
            precision mediump float;
            
            in vec2 vTexCoord;
            out vec4 fragColor;
            
            uniform sampler2D uTexture;
            uniform sampler2D uLutTexture;
            uniform float uLutIntensity;
            uniform bool uEnableLut;
            uniform bool uEnablePeaking;
            uniform float uPeakingThreshold;
            uniform vec3 uPeakingColor;
            
            vec3 applyLut(vec3 color) {
                if (!uEnableLut) return color;
                
                float lutSize = 64.0;
                float scale = (lutSize - 1.0) / lutSize;
                float offset = 1.0 / (2.0 * lutSize);
                
                float blue = color.b * scale + offset;
                vec2 quad1;
                quad1.y = floor(floor(blue * lutSize) / 8.0);
                quad1.x = floor(blue * lutSize) - (quad1.y * 8.0);
                
                vec2 quad2;
                quad2.y = floor(ceil(blue * lutSize) / 8.0);
                quad2.x = ceil(blue * lutSize) - (quad2.y * 8.0);
                
                vec2 texPos1;
                texPos1.x = (quad1.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * color.r);
                texPos1.y = (quad1.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * color.g);
                
                vec2 texPos2;
                texPos2.x = (quad2.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * color.r);
                texPos2.y = (quad2.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * color.g);
                
                vec3 lut1 = texture(uLutTexture, texPos1).rgb;
                vec3 lut2 = texture(uLutTexture, texPos2).rgb;
                
                vec3 lutColor = mix(lut1, lut2, fract(blue * lutSize));
                return mix(color, lutColor, uLutIntensity);
            }
            
            vec3 applyPeaking(vec3 color, vec2 texCoord) {
                if (!uEnablePeaking) return color;
                
                vec2 texelSize = 1.0 / textureSize(uTexture, 0);
                
                // Sobel边缘检测
                vec3 tl = texture(uTexture, texCoord + vec2(-texelSize.x, -texelSize.y)).rgb;
                vec3 tm = texture(uTexture, texCoord + vec2(0.0, -texelSize.y)).rgb;
                vec3 tr = texture(uTexture, texCoord + vec2(texelSize.x, -texelSize.y)).rgb;
                vec3 ml = texture(uTexture, texCoord + vec2(-texelSize.x, 0.0)).rgb;
                vec3 mr = texture(uTexture, texCoord + vec2(texelSize.x, 0.0)).rgb;
                vec3 bl = texture(uTexture, texCoord + vec2(-texelSize.x, texelSize.y)).rgb;
                vec3 bm = texture(uTexture, texCoord + vec2(0.0, texelSize.y)).rgb;
                vec3 br = texture(uTexture, texCoord + vec2(texelSize.x, texelSize.y)).rgb;
                
                vec3 sobelX = tl + 2.0*ml + bl - tr - 2.0*mr - br;
                vec3 sobelY = tl + 2.0*tm + tr - bl - 2.0*bm - br;
                
                float edge = length(sobelX) + length(sobelY);
                
                if (edge > uPeakingThreshold) {
                    return mix(color, uPeakingColor, 0.5);
                }
                
                return color;
            }
            
            void main() {
                vec3 color = texture(uTexture, vTexCoord).rgb;
                
                // 应用LUT
                color = applyLut(color);
                
                // 应用峰值显示
                color = applyPeaking(color, vTexCoord);
                
                fragColor = vec4(color, 1.0);
            }
        """

        // 波形图片段着色器代码
        private const val WAVEFORM_FRAGMENT_SHADER_CODE = """
            #version 300 es
            precision mediump float;
            
            in vec2 vTexCoord;
            out vec4 fragColor;
            
            uniform sampler2D uTexture;
            uniform float uWaveformHeight;
            uniform vec2 uResolution;
            
            void main() {
                vec2 uv = vTexCoord;
                
                // 计算波形图
                float x = uv.x;
                float y = uv.y;
                
                // 采样原始图像
                vec3 color = texture(uTexture, vec2(x, 0.5)).rgb;
                float luminance = dot(color, vec3(0.299, 0.587, 0.114));
                
                // 绘制波形
                float waveY = luminance * uWaveformHeight;
                float dist = abs(y * uWaveformHeight - waveY);
                
                if (dist < 2.0) {
                    fragColor = vec4(1.0, 1.0, 1.0, 0.8);
                } else {
                    fragColor = vec4(0.0, 0.0, 0.0, 0.3);
                }
            }
        """
    }

    // 渲染状态
    private var isInitialized = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // OpenGL对象
    private var shaderProgram = 0
    private var waveformShaderProgram = 0
    private var vertexBuffer: FloatBuffer? = null
    private var textureCoordBuffer: FloatBuffer? = null
    private var textureId = 0
    private var lutTextureId = 0

    // 变换矩阵
    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    // 渲染设置
    private var enableLut = false
    private var lutIntensity = 1.0f
    private var enablePeaking = false
    private var peakingThreshold = 0.3f
    private val peakingColor = floatArrayOf(1.0f, 0.0f, 0.0f) // 红色
    private var enableWaveform = false
    private var waveformHeight = 0.3f

    // 当前帧数据
    private var currentFrame: Bitmap? = null
    private var lutBitmap: Bitmap? = null

    // 顶点数据
    private val vertices = floatArrayOf(
        -1.0f, -1.0f, 0.0f,  // 左下
        1.0f, -1.0f, 0.0f,  // 右下
        -1.0f, 1.0f, 0.0f,  // 左上
        1.0f, 1.0f, 0.0f   // 右上
    )

    private val textureCoords = floatArrayOf(
        0.0f, 1.0f,  // 左下
        1.0f, 1.0f,  // 右下
        0.0f, 0.0f,  // 左上
        1.0f, 0.0f   // 右上
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i(TAG, "Surface created")

        // 设置清除颜色
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // 启用混合
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // 初始化着色器程序
        initShaders()

        // 初始化缓冲区
        initBuffers()

        // 初始化纹理
        initTextures()

        isInitialized = true
        Log.i(TAG, "Renderer initialized")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.i(TAG, "Surface changed: ${width}x${height}")

        surfaceWidth = width
        surfaceHeight = height

        GLES30.glViewport(0, 0, width, height)

        // 设置投影矩阵
        val ratio = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 3f, 7f)

        // 设置视图矩阵
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 1f, 0f)

        // 计算MVP矩阵
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!isInitialized) return

        // 清除缓冲区
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // 更新纹理数据
        updateTexture()

        // 绘制主视频
        drawVideo()

        // 绘制波形图（如果启用）
        if (enableWaveform) {
            drawWaveform()
        }
    }

    /**
     * 初始化着色器程序
     */
    private fun initShaders() {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
        val waveformFragmentShader =
            loadShader(GLES30.GL_FRAGMENT_SHADER, WAVEFORM_FRAGMENT_SHADER_CODE)

        // 创建主着色器程序
        shaderProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(shaderProgram, vertexShader)
        GLES30.glAttachShader(shaderProgram, fragmentShader)
        GLES30.glLinkProgram(shaderProgram)

        // 创建波形图着色器程序
        waveformShaderProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(waveformShaderProgram, vertexShader)
        GLES30.glAttachShader(waveformShaderProgram, waveformFragmentShader)
        GLES30.glLinkProgram(waveformShaderProgram)

        // 删除着色器对象
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        GLES30.glDeleteShader(waveformFragmentShader)

        Log.i(TAG, "Shaders initialized")
    }

    /**
     * 加载着色器
     */
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)

        // 检查编译状态
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES30.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compilation failed: $error")
            GLES30.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    /**
     * 初始化缓冲区
     */
    private fun initBuffers() {
        // 顶点缓冲区
        val vertexByteBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
        vertexByteBuffer.order(ByteOrder.nativeOrder())
        vertexBuffer = vertexByteBuffer.asFloatBuffer()
        vertexBuffer?.put(vertices)
        vertexBuffer?.position(0)

        // 纹理坐标缓冲区
        val textureByteBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
        textureByteBuffer.order(ByteOrder.nativeOrder())
        textureCoordBuffer = textureByteBuffer.asFloatBuffer()
        textureCoordBuffer?.put(textureCoords)
        textureCoordBuffer?.position(0)

        Log.i(TAG, "Buffers initialized")
    }

    /**
     * 初始化纹理
     */
    private fun initTextures() {
        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)

        textureId = textures[0]
        lutTextureId = textures[1]

        // 配置主纹理
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
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

        // 配置LUT纹理
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTextureId)
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

        Log.i(TAG, "Textures initialized")
    }

    /**
     * 更新纹理数据
     */
    private fun updateTexture() {
        currentFrame?.let { frame ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, frame, 0)
        }

        lutBitmap?.let { lut ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTextureId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, lut, 0)
        }
    }

    /**
     * 绘制视频
     */
    private fun drawVideo() {
        GLES30.glUseProgram(shaderProgram)

        // 设置顶点属性
        val positionHandle = GLES30.glGetAttribLocation(shaderProgram, "aPosition")
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer)

        val texCoordHandle = GLES30.glGetAttribLocation(shaderProgram, "aTexCoord")
        GLES30.glEnableVertexAttribArray(texCoordHandle)
        GLES30.glVertexAttribPointer(
            texCoordHandle,
            2,
            GLES30.GL_FLOAT,
            false,
            0,
            textureCoordBuffer
        )

        // 设置uniform变量
        val mvpMatrixHandle = GLES30.glGetUniformLocation(shaderProgram, "uMVPMatrix")
        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        val textureHandle = GLES30.glGetUniformLocation(shaderProgram, "uTexture")
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(textureHandle, 0)

        val lutTextureHandle = GLES30.glGetUniformLocation(shaderProgram, "uLutTexture")
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTextureId)
        GLES30.glUniform1i(lutTextureHandle, 1)

        // 设置效果参数
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(shaderProgram, "uEnableLut"),
            if (enableLut) 1 else 0
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(shaderProgram, "uLutIntensity"),
            lutIntensity
        )
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(shaderProgram, "uEnablePeaking"),
            if (enablePeaking) 1 else 0
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(shaderProgram, "uPeakingThreshold"),
            peakingThreshold
        )
        GLES30.glUniform3fv(
            GLES30.glGetUniformLocation(shaderProgram, "uPeakingColor"),
            1,
            peakingColor,
            0
        )

        // 绘制
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // 禁用顶点属性
        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(texCoordHandle)
    }

    /**
     * 绘制波形图
     */
    private fun drawWaveform() {
        // TODO: 实现波形图绘制
        // 这里可以在屏幕的一角绘制波形图
    }

    /**
     * 设置当前帧
     */
    fun setFrame(frame: Bitmap) {
        currentFrame = frame
    }

    /**
     * 加载LUT文件
     */
    fun loadLut(lutFile: File): Boolean {
        return try {
            lutBitmap = android.graphics.BitmapFactory.decodeFile(lutFile.absolutePath)
            lutBitmap != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load LUT file", e)
            false
        }
    }

    /**
     * 设置LUT效果
     */
    fun setLutEffect(enabled: Boolean, intensity: Float = 1.0f) {
        enableLut = enabled
        lutIntensity = intensity.coerceIn(0.0f, 1.0f)
    }

    /**
     * 设置峰值显示
     */
    fun setPeakingEffect(enabled: Boolean, threshold: Float = 0.3f) {
        enablePeaking = enabled
        peakingThreshold = threshold.coerceIn(0.0f, 1.0f)
    }

    /**
     * 设置波形图显示
     */
    fun setWaveformDisplay(enabled: Boolean, height: Float = 0.3f) {
        enableWaveform = enabled
        waveformHeight = height.coerceIn(0.1f, 1.0f)
    }

    /**
     * 释放资源
     */
    fun release() {
        if (shaderProgram != 0) {
            GLES30.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        }

        if (waveformShaderProgram != 0) {
            GLES30.glDeleteProgram(waveformShaderProgram)
            waveformShaderProgram = 0
        }

        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }

        if (lutTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = 0
        }

        currentFrame?.recycle()
        currentFrame = null

        lutBitmap?.recycle()
        lutBitmap = null

        isInitialized = false
        Log.i(TAG, "VideoRenderer released")
    }
}