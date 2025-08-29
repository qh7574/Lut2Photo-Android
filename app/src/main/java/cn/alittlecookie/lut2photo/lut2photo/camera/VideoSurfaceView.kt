package cn.alittlecookie.lut2photo.lut2photo.camera

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import java.io.File

/**
 * 自定义GLSurfaceView用于视频渲染
 * 支持OpenGL ES 3.0视频处理效果
 */
class VideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    companion object {
        private const val TAG = "VideoSurfaceView"
    }

    private val videoRenderer: VideoRenderer
    private var isRendererSet = false

    init {
        // 设置OpenGL ES 3.0上下文
        setEGLContextClientVersion(3)

        // 创建渲染器
        videoRenderer = VideoRenderer(context)
        setRenderer(videoRenderer)

        // 设置渲染模式为按需渲染
        renderMode = RENDERMODE_WHEN_DIRTY

        isRendererSet = true

        Log.i(TAG, "VideoSurfaceView initialized")
    }

    /**
     * 设置要渲染的帧
     */
    fun setFrame(frame: Bitmap) {
        if (!isRendererSet) {
            Log.w(TAG, "Renderer not set, ignoring frame")
            return
        }

        queueEvent {
            videoRenderer.setFrame(frame)
            requestRender()
        }
    }

    /**
     * 加载LUT文件
     */
    fun loadLut(lutFile: File, callback: (Boolean) -> Unit) {
        if (!isRendererSet) {
            Log.w(TAG, "Renderer not set, cannot load LUT")
            callback(false)
            return
        }

        queueEvent {
            val success = videoRenderer.loadLut(lutFile)
            post { callback(success) }
        }
    }

    /**
     * 设置LUT效果
     */
    fun setLutEffect(enabled: Boolean, intensity: Float = 1.0f) {
        if (!isRendererSet) {
            Log.w(TAG, "Renderer not set, cannot set LUT effect")
            return
        }

        queueEvent {
            videoRenderer.setLutEffect(enabled, intensity)
            requestRender()
        }
    }

    /**
     * 设置峰值显示效果
     */
    fun setPeakingEffect(enabled: Boolean, threshold: Float = 0.3f) {
        if (!isRendererSet) {
            Log.w(TAG, "Renderer not set, cannot set peaking effect")
            return
        }

        queueEvent {
            videoRenderer.setPeakingEffect(enabled, threshold)
            requestRender()
        }
    }

    /**
     * 设置波形图显示
     */
    fun setWaveformDisplay(enabled: Boolean, height: Float = 0.3f) {
        if (!isRendererSet) {
            Log.w(TAG, "Renderer not set, cannot set waveform display")
            return
        }

        queueEvent {
            videoRenderer.setWaveformDisplay(enabled, height)
            requestRender()
        }
    }

    /**
     * 获取当前LUT效果状态
     */
    fun isLutEnabled(): Boolean {
        // 这里需要从渲染器获取状态，暂时返回false
        return false
    }

    /**
     * 获取当前峰值显示状态
     */
    fun isPeakingEnabled(): Boolean {
        // 这里需要从渲染器获取状态，暂时返回false
        return false
    }

    /**
     * 获取当前波形图显示状态
     */
    fun isWaveformEnabled(): Boolean {
        // 这里需要从渲染器获取状态，暂时返回false
        return false
    }

    /**
     * 释放资源
     */
    fun release() {
        if (isRendererSet) {
            queueEvent {
                videoRenderer.release()
            }
            isRendererSet = false
        }
        Log.i(TAG, "VideoSurfaceView released")
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}