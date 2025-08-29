package cn.alittlecookie.lut2photo.lut2photo.camera

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 视频效果管理器
 * 统一管理LUT、峰值显示、波形图等视频处理效果
 */
class VideoEffectsManager(private val context: Context) {

    companion object {
        private const val TAG = "VideoEffectsManager"
    }

    // LUT效果状态
    private val _lutEnabled = MutableStateFlow(false)
    val lutEnabled: StateFlow<Boolean> = _lutEnabled.asStateFlow()

    private val _lutIntensity = MutableStateFlow(1.0f)
    val lutIntensity: StateFlow<Float> = _lutIntensity.asStateFlow()

    private val _currentLutFile = MutableStateFlow<File?>(null)
    val currentLutFile: StateFlow<File?> = _currentLutFile.asStateFlow()

    // 峰值显示状态
    private val _peakingEnabled = MutableStateFlow(false)
    val peakingEnabled: StateFlow<Boolean> = _peakingEnabled.asStateFlow()

    private val _peakingThreshold = MutableStateFlow(0.3f)
    val peakingThreshold: StateFlow<Float> = _peakingThreshold.asStateFlow()

    // 波形图状态
    private val _waveformEnabled = MutableStateFlow(false)
    val waveformEnabled: StateFlow<Boolean> = _waveformEnabled.asStateFlow()

    private val _waveformHeight = MutableStateFlow(0.3f)
    val waveformHeight: StateFlow<Float> = _waveformHeight.asStateFlow()

    // 错误状态
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 可用的LUT文件列表
    private val _availableLuts = MutableStateFlow<List<File>>(emptyList())
    val availableLuts: StateFlow<List<File>> = _availableLuts.asStateFlow()

    // 当前绑定的VideoSurfaceView
    private var videoSurfaceView: VideoSurfaceView? = null

    init {
        // 扫描可用的LUT文件
        scanAvailableLuts()
        Log.i(TAG, "VideoEffectsManager initialized")
    }

    /**
     * 绑定VideoSurfaceView
     */
    fun bindVideoSurfaceView(surfaceView: VideoSurfaceView) {
        videoSurfaceView = surfaceView

        // 应用当前设置
        applyCurrentSettings()

        Log.i(TAG, "VideoSurfaceView bound")
    }

    /**
     * 解绑VideoSurfaceView
     */
    fun unbindVideoSurfaceView() {
        videoSurfaceView = null
        Log.i(TAG, "VideoSurfaceView unbound")
    }

    /**
     * 加载LUT文件
     */
    fun loadLut(lutFile: File, callback: (Boolean) -> Unit = {}) {
        if (!lutFile.exists()) {
            _errorMessage.value = "LUT文件不存在: ${lutFile.name}"
            callback(false)
            return
        }

        videoSurfaceView?.loadLut(lutFile) { success ->
            if (success) {
                _currentLutFile.value = lutFile
                _errorMessage.value = null
                Log.i(TAG, "LUT loaded: ${lutFile.name}")
            } else {
                _errorMessage.value = "加载LUT文件失败: ${lutFile.name}"
                Log.e(TAG, "Failed to load LUT: ${lutFile.name}")
            }
            callback(success)
        } ?: run {
            _errorMessage.value = "VideoSurfaceView未绑定"
            callback(false)
        }
    }

    /**
     * 设置LUT效果
     */
    fun setLutEffect(enabled: Boolean, intensity: Float = _lutIntensity.value) {
        val clampedIntensity = intensity.coerceIn(0.0f, 1.0f)

        _lutEnabled.value = enabled
        _lutIntensity.value = clampedIntensity

        videoSurfaceView?.setLutEffect(enabled, clampedIntensity)

        Log.i(TAG, "LUT effect: enabled=$enabled, intensity=$clampedIntensity")
    }

    /**
     * 设置峰值显示
     */
    fun setPeakingEffect(enabled: Boolean, threshold: Float = _peakingThreshold.value) {
        val clampedThreshold = threshold.coerceIn(0.0f, 1.0f)

        _peakingEnabled.value = enabled
        _peakingThreshold.value = clampedThreshold

        videoSurfaceView?.setPeakingEffect(enabled, clampedThreshold)

        Log.i(TAG, "Peaking effect: enabled=$enabled, threshold=$clampedThreshold")
    }

    /**
     * 设置波形图显示
     */
    fun setWaveformDisplay(enabled: Boolean, height: Float = _waveformHeight.value) {
        val clampedHeight = height.coerceIn(0.1f, 1.0f)

        _waveformEnabled.value = enabled
        _waveformHeight.value = clampedHeight

        videoSurfaceView?.setWaveformDisplay(enabled, clampedHeight)

        Log.i(TAG, "Waveform display: enabled=$enabled, height=$clampedHeight")
    }

    /**
     * 切换LUT效果
     */
    fun toggleLutEffect() {
        setLutEffect(!_lutEnabled.value)
    }

    /**
     * 切换峰值显示
     */
    fun togglePeakingEffect() {
        setPeakingEffect(!_peakingEnabled.value)
    }

    /**
     * 切换波形图显示
     */
    fun toggleWaveformDisplay() {
        setWaveformDisplay(!_waveformEnabled.value)
    }

    /**
     * 重置所有效果
     */
    fun resetAllEffects() {
        setLutEffect(false, 1.0f)
        setPeakingEffect(false, 0.3f)
        setWaveformDisplay(false, 0.3f)

        _currentLutFile.value = null
        _errorMessage.value = null

        Log.i(TAG, "All effects reset")
    }

    /**
     * 应用当前设置到VideoSurfaceView
     */
    private fun applyCurrentSettings() {
        videoSurfaceView?.let { surfaceView ->
            // 应用LUT设置
            _currentLutFile.value?.let { lutFile ->
                surfaceView.loadLut(lutFile) { success ->
                    if (success) {
                        surfaceView.setLutEffect(_lutEnabled.value, _lutIntensity.value)
                    }
                }
            } ?: run {
                surfaceView.setLutEffect(_lutEnabled.value, _lutIntensity.value)
            }

            // 应用峰值显示设置
            surfaceView.setPeakingEffect(_peakingEnabled.value, _peakingThreshold.value)

            // 应用波形图设置
            surfaceView.setWaveformDisplay(_waveformEnabled.value, _waveformHeight.value)
        }
    }

    /**
     * 扫描可用的LUT文件
     */
    private fun scanAvailableLuts() {
        try {
            val lutDirectory = File(context.filesDir, "luts")
            if (!lutDirectory.exists()) {
                lutDirectory.mkdirs()
            }

            val lutFiles = lutDirectory.listFiles { file ->
                file.isFile && (file.extension.lowercase() == "png" ||
                        file.extension.lowercase() == "jpg" ||
                        file.extension.lowercase() == "jpeg")
            }?.toList() ?: emptyList()

            _availableLuts.value = lutFiles

            Log.i(TAG, "Found ${lutFiles.size} LUT files")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan LUT files", e)
            _errorMessage.value = "扫描LUT文件失败: ${e.message}"
        }
    }

    /**
     * 刷新可用的LUT文件列表
     */
    fun refreshAvailableLuts() {
        scanAvailableLuts()
    }

    /**
     * 获取效果统计信息
     */
    fun getEffectsStats(): Map<String, Any> {
        return mapOf(
            "lutEnabled" to _lutEnabled.value,
            "lutIntensity" to _lutIntensity.value,
            "currentLutFile" to (_currentLutFile.value?.name ?: "None"),
            "peakingEnabled" to _peakingEnabled.value,
            "peakingThreshold" to _peakingThreshold.value,
            "waveformEnabled" to _waveformEnabled.value,
            "waveformHeight" to _waveformHeight.value,
            "availableLutsCount" to _availableLuts.value.size
        )
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 释放资源
     */
    fun release() {
        unbindVideoSurfaceView()
        resetAllEffects()
        Log.i(TAG, "VideoEffectsManager released")
    }
}