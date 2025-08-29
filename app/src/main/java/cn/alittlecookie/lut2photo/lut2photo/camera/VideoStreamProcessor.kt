package cn.alittlecookie.lut2photo.lut2photo.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频流处理器
 * 负责处理来自相机的实时视频流数据
 */
class VideoStreamProcessor {
    companion object {
        private const val TAG = "VideoStreamProcessor"
        private const val FRAME_RATE_TARGET = 30 // 目标帧率
        private const val FRAME_INTERVAL_MS = 1000L / FRAME_RATE_TARGET
    }

    // 处理状态
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // 当前帧数据
    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    // 帧率统计
    private val _frameRate = MutableStateFlow(0f)
    val frameRate: StateFlow<Float> = _frameRate.asStateFlow()

    // 错误信息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 协程作用域
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 控制标志
    private val isRunning = AtomicBoolean(false)

    // 帧率计算相关
    private var frameCount = 0
    private var lastFrameRateUpdate = 0L

    // 相机设备引用
    private var cameraDevice: ICameraDevice? = null

    /**
     * 设置相机设备
     */
    fun setCameraDevice(device: ICameraDevice?) {
        this.cameraDevice = device
    }

    /**
     * 开始视频流处理
     */
    fun startProcessing() {
        if (isRunning.get()) {
            Log.w(TAG, "视频流处理已在运行中")
            return
        }

        if (cameraDevice == null) {
            _errorMessage.value = "相机设备未连接"
            return
        }

        Log.i(TAG, "开始视频流处理")
        isRunning.set(true)
        _isProcessing.value = true
        _errorMessage.value = null

        // 启动处理协程
        processingScope.launch {
            processVideoStream()
        }
    }

    /**
     * 停止视频流处理
     */
    fun stopProcessing() {
        if (!isRunning.get()) {
            Log.w(TAG, "视频流处理未在运行")
            return
        }

        Log.i(TAG, "停止视频流处理")
        isRunning.set(false)
        _isProcessing.value = false
        _currentFrame.value = null
        _frameRate.value = 0f
    }

    /**
     * 视频流处理主循环
     */
    private suspend fun processVideoStream() {
        lastFrameRateUpdate = System.currentTimeMillis()
        frameCount = 0

        try {
            while (isRunning.get()) {
                val startTime = System.currentTimeMillis()

                // 获取预览帧
                val frameData = cameraDevice?.getPreviewFrame()
                if (frameData != null) {
                    // 处理帧数据
                    processFrame(frameData)

                    // 更新帧率统计
                    updateFrameRate()
                } else {
                    // 如果获取帧失败，等待一段时间后重试
                    delay(FRAME_INTERVAL_MS)
                }

                // 控制帧率
                val processingTime = System.currentTimeMillis() - startTime
                val sleepTime = FRAME_INTERVAL_MS - processingTime
                if (sleepTime > 0) {
                    delay(sleepTime)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "视频流处理异常", e)
            _errorMessage.value = "视频流处理异常: ${e.message}"
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * 处理单帧数据
     */
    private suspend fun processFrame(frameData: ByteArray) {
        withContext(Dispatchers.Default) {
            try {
                // 将字节数组转换为Bitmap
                val bitmap = decodeFrameData(frameData)
                if (bitmap != null) {
                    // 更新当前帧
                    withContext(Dispatchers.Main) {
                        _currentFrame.value = bitmap
                    }
                } else {
                    Log.w(TAG, "帧数据解码失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理帧数据异常", e)
            }
        }
    }

    /**
     * 解码帧数据
     */
    private fun decodeFrameData(frameData: ByteArray): Bitmap? {
        return try {
            // 尝试直接解码为JPEG
            val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size)
            if (bitmap != null) {
                return bitmap
            }

            // 如果直接解码失败，可能是YUV格式，需要转换
            // 这里简化处理，实际应用中可能需要根据具体格式进行转换
            Log.w(TAG, "无法直接解码帧数据，可能需要格式转换")
            null
        } catch (e: Exception) {
            Log.e(TAG, "解码帧数据异常", e)
            null
        }
    }

    /**
     * 将YUV数据转换为JPEG
     */
    private fun convertYuvToJpeg(yuvData: ByteArray, width: Int, height: Int): ByteArray? {
        return try {
            val yuvImage = YuvImage(yuvData, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()
            val rect = Rect(0, 0, width, height)

            if (yuvImage.compressToJpeg(rect, 80, outputStream)) {
                outputStream.toByteArray()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "YUV转JPEG异常", e)
            null
        }
    }

    /**
     * 更新帧率统计
     */
    private fun updateFrameRate() {
        frameCount++
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastFrameRateUpdate

        if (timeDiff >= 1000) { // 每秒更新一次帧率
            val fps = (frameCount * 1000f) / timeDiff
            _frameRate.value = fps

            frameCount = 0
            lastFrameRateUpdate = currentTime

            Log.d(TAG, "当前帧率: ${String.format("%.1f", fps)} FPS")
        }
    }

    /**
     * 获取视频流统计信息
     */
    fun getStreamStats(): VideoStreamStats {
        return VideoStreamStats(
            isProcessing = _isProcessing.value,
            frameRate = _frameRate.value,
            hasError = _errorMessage.value != null
        )
    }

    /**
     * 清理错误信息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 释放资源
     */
    fun release() {
        stopProcessing()
        processingScope.cancel()
        _currentFrame.value?.recycle()
        _currentFrame.value = null
        cameraDevice = null
        Log.i(TAG, "视频流处理器已释放")
    }

    /**
     * 视频流统计信息
     */
    data class VideoStreamStats(
        val isProcessing: Boolean,
        val frameRate: Float,
        val hasError: Boolean
    )
}