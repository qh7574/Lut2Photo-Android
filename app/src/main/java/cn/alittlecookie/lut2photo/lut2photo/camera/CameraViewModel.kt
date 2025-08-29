package cn.alittlecookie.lut2photo.lut2photo.camera

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 相机控制ViewModel，负责UI层与相机服务的交互
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
    }

    private var cameraService: CameraService? = null
    private var isServiceBound = false

    // UI状态
    private val _isServiceConnected = MutableStateFlow(false)
    private val _serviceState = MutableStateFlow(CameraService.STATE_IDLE)
    private val _connectedCamera = MutableStateFlow<ICameraDevice?>(null)
    private val _availableCameras =
        MutableStateFlow<List<UsbCameraManager.UsbCameraInfo>>(emptyList())
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _liveViewStream = MutableStateFlow<Bitmap?>(null)

    // 视频流处理状态
    private val _isVideoStreamProcessing = MutableStateFlow(false)
    private val _currentVideoFrame = MutableStateFlow<Bitmap?>(null)
    private val _videoFrameRate = MutableStateFlow(0f)
    private val _videoStreamError = MutableStateFlow<String?>(null)
    private val _cameraCapabilities = MutableStateFlow<ICameraDevice.CameraCapabilities?>(null)
    private val _currentParameters = MutableStateFlow<ICameraDevice.CameraParameters?>(null)
    private val _isLiveViewActive = MutableStateFlow(false)
    private val _isRecording = MutableStateFlow(false)

    // 公开的状态流
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()
    val serviceState: StateFlow<Int> = _serviceState.asStateFlow()
    val connectedCamera: StateFlow<ICameraDevice?> = _connectedCamera.asStateFlow()
    val availableCameras: StateFlow<List<UsbCameraManager.UsbCameraInfo>> =
        _availableCameras.asStateFlow()
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    val liveViewStream: StateFlow<Bitmap?> = _liveViewStream.asStateFlow()

    // 视频流相关状态
    val isVideoStreamProcessing: StateFlow<Boolean> = _isVideoStreamProcessing.asStateFlow()
    val currentVideoFrame: StateFlow<Bitmap?> = _currentVideoFrame.asStateFlow()
    val videoFrameRate: StateFlow<Float> = _videoFrameRate.asStateFlow()
    val videoStreamError: StateFlow<String?> = _videoStreamError.asStateFlow()

    // 联机拍摄相关状态
    val isTetheredShootingActive: StateFlow<Boolean>
        get() = cameraService?.isTetheredShootingActive ?: MutableStateFlow(false)

    val tetheredShootingProgress: StateFlow<TetheredShootingManager.ShootingProgress?>
        get() = cameraService?.tetheredShootingProgress ?: MutableStateFlow(null)

    val lastCapturedPhoto: StateFlow<TetheredShootingManager.CapturedPhoto?>
        get() = cameraService?.lastCapturedPhoto ?: MutableStateFlow(null)

    val tetheredShootingError: StateFlow<String?>
        get() = cameraService?.tetheredShootingError ?: MutableStateFlow(null)

    // ==================== 视频效果相关状态 ====================

    // LUT效果状态
    private val _lutEnabled = MutableStateFlow(false)
    val lutEnabled: StateFlow<Boolean> = _lutEnabled.asStateFlow()

    private val _lutIntensity = MutableStateFlow(1.0f)
    val lutIntensity: StateFlow<Float> = _lutIntensity.asStateFlow()

    private val _currentLutFile = MutableStateFlow<java.io.File?>(null)
    val currentLutFile: StateFlow<java.io.File?> = _currentLutFile.asStateFlow()

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

    // 可用LUT文件列表
    private val _availableLuts = MutableStateFlow<List<java.io.File>>(emptyList())
    val availableLuts: StateFlow<List<java.io.File>> = _availableLuts.asStateFlow()

    // 视频效果错误信息
    private val _videoEffectsError = MutableStateFlow<String?>(null)
    val videoEffectsError: StateFlow<String?> = _videoEffectsError.asStateFlow()

    val cameraCapabilities: StateFlow<ICameraDevice.CameraCapabilities?> =
        _cameraCapabilities.asStateFlow()
    val currentParameters: StateFlow<ICameraDevice.CameraParameters?> =
        _currentParameters.asStateFlow()
    val isLiveViewActive: StateFlow<Boolean> = _isLiveViewActive.asStateFlow()
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // 服务连接回调
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "相机服务已连接")
            val binder = service as CameraService.CameraServiceBinder
            cameraService = binder.getService()
            isServiceBound = true
            _isServiceConnected.value = true

            // 开始监听服务状态
            observeServiceStates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "相机服务已断开")
            cameraService = null
            isServiceBound = false
            _isServiceConnected.value = false
        }
    }

    init {
        // 启动并绑定相机服务
        startAndBindCameraService()
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel清理")

        // 停止实时预览
        if (_isLiveViewActive.value) {
            stopLiveView()
        }

        // 解绑服务
        unbindCameraService()
    }

    private fun startAndBindCameraService() {
        val context = getApplication<Application>()
        val intent = Intent(context, CameraService::class.java)

        // 启动服务
        context.startService(intent)

        // 绑定服务
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindCameraService() {
        if (isServiceBound) {
            val context = getApplication<Application>()
            context.unbindService(serviceConnection)
            isServiceBound = false
            _isServiceConnected.value = false
        }
    }

    private fun observeServiceStates() {
        cameraService?.let { service ->
            viewModelScope.launch {
                // 监听服务状态
                service.serviceState.collect { state ->
                    _serviceState.value = state
                }
            }

            viewModelScope.launch {
                // 监听连接的相机
                service.connectedCamera.collect { camera ->
                    _connectedCamera.value = camera

                    // 如果相机连接成功，获取相机能力和参数
                    if (camera != null) {
                        loadCameraCapabilities()
                        loadCurrentParameters()
                        observeLiveViewStream(camera)
                    } else {
                        _cameraCapabilities.value = null
                        _currentParameters.value = null
                        _liveViewStream.value = null
                        _isLiveViewActive.value = false
                    }
                }
            }

            viewModelScope.launch {
                // 监听可用相机列表
                service.availableCameras.collect { cameras ->
                    _availableCameras.value = cameras
                }
            }

            viewModelScope.launch {
                // 监听错误消息
                service.errorMessage.collect { error ->
                    _errorMessage.value = error
                }
            }
        }
    }

    private fun observeLiveViewStream(camera: ICameraDevice) {
        viewModelScope.launch {
            camera.getLiveViewStream().collect { bitmap ->
                _liveViewStream.value = bitmap
                _isLiveViewActive.value = bitmap != null
            }
        }
    }

    private fun loadCameraCapabilities() {
        viewModelScope.launch {
            try {
                val capabilities = cameraService?.getCameraCapabilities()
                _cameraCapabilities.value = capabilities
            } catch (e: Exception) {
                Log.e(TAG, "加载相机能力失败", e)
            }
        }
    }

    private fun loadCurrentParameters() {
        viewModelScope.launch {
            try {
                val parameters = cameraService?.getCurrentCameraParameters()
                _currentParameters.value = parameters
            } catch (e: Exception) {
                Log.e(TAG, "加载相机参数失败", e)
            }
        }
    }

    /**
     * 刷新可用相机列表
     */
    fun refreshAvailableCameras() {
        cameraService?.refreshAvailableCameras()
    }

    /**
     * 连接到指定相机
     */
    fun connectToCamera(cameraInfo: UsbCameraManager.UsbCameraInfo) {
        cameraService?.connectToCamera(cameraInfo)
    }

    /**
     * 断开当前相机连接
     */
    fun disconnectCamera() {
        cameraService?.disconnectCamera()
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        cameraService?.clearError()
    }

    /**
     * 设置光圈值
     */
    fun setAperture(aperture: Float) {
        viewModelScope.launch {
            try {
                val camera = _connectedCamera.value
                if (camera != null) {
                    val success = camera.setAperture(aperture)
                    if (success) {
                        loadCurrentParameters() // 重新加载参数
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置光圈失败", e)
                _errorMessage.value = "设置光圈失败: ${e.message}"
            }
        }
    }

    /**
     * 设置快门速度
     */
    fun setShutterSpeed(shutterSpeed: String) {
        viewModelScope.launch {
            try {
                val camera = _connectedCamera.value
                if (camera != null) {
                    val success = camera.setShutterSpeed(shutterSpeed)
                    if (success) {
                        loadCurrentParameters()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置快门速度失败", e)
                _errorMessage.value = "设置快门速度失败: ${e.message}"
            }
        }
    }

    /**
     * 设置ISO值
     */
    fun setIso(iso: Int) {
        viewModelScope.launch {
            try {
                val camera = _connectedCamera.value
                if (camera != null) {
                    val success = camera.setIso(iso)
                    if (success) {
                        loadCurrentParameters()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置ISO失败", e)
                _errorMessage.value = "设置ISO失败: ${e.message}"
            }
        }
    }

    /**
     * 设置白平衡
     */
    fun setWhiteBalance(whiteBalance: String) {
        viewModelScope.launch {
            try {
                val camera = _connectedCamera.value
                if (camera != null) {
                    val success = camera.setWhiteBalance(whiteBalance)
                    if (success) {
                        loadCurrentParameters()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置白平衡失败", e)
                _errorMessage.value = "设置白平衡失败: ${e.message}"
            }
        }
    }

    /**
     * 设置对焦模式
     */
    fun setFocusMode(focusMode: String) {
        viewModelScope.launch {
            try {
                val camera = _connectedCamera.value
                if (camera != null) {
                    val success = camera.setFocusMode(focusMode)
                    if (success) {
                        loadCurrentParameters()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置对焦模式失败", e)
                _errorMessage.value = "设置对焦模式失败: ${e.message}"
            }
        }
    }

    /**
     * 拍摄照片
     */
    fun captureImage() {
        viewModelScope.launch {
            try {
                val result = cameraService?.captureImage()
                if (result?.isSuccess == true) {
                    Log.i(TAG, "照片拍摄成功")
                    // 这里可以触发照片保存和处理逻辑
                } else {
                    _errorMessage.value = result?.errorMessage ?: "拍摄失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "拍摄照片失败", e)
                _errorMessage.value = "拍摄失败: ${e.message}"
            }
        }
    }

    /**
     * 开始录制视频
     */
    fun startVideoRecording() {
        viewModelScope.launch {
            try {
                val success = cameraService?.startVideoRecording() ?: false
                if (success) {
                    _isRecording.value = true
                    Log.i(TAG, "视频录制开始")
                } else {
                    _errorMessage.value = "无法开始录制"
                }
            } catch (e: Exception) {
                Log.e(TAG, "开始录制失败", e)
                _errorMessage.value = "开始录制失败: ${e.message}"
            }
        }
    }

    /**
     * 停止录制视频
     */
    fun stopVideoRecording() {
        viewModelScope.launch {
            try {
                val result = cameraService?.stopVideoRecording()
                _isRecording.value = false

                if (result?.isSuccess == true) {
                    Log.i(TAG, "视频录制完成")
                    // 这里可以触发视频保存和处理逻辑
                } else {
                    _errorMessage.value = result?.errorMessage ?: "录制停止失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "停止录制失败", e)
                _errorMessage.value = "停止录制失败: ${e.message}"
            }
        }
    }

    /**
     * 开始实时预览
     */
    fun startLiveView() {
        viewModelScope.launch {
            try {
                val success = cameraService?.startLiveView() ?: false
                if (!success) {
                    _errorMessage.value = "无法开始实时预览"
                } else {
                    Log.i(TAG, "实时预览和视频流处理已启动")
                    _isVideoStreamProcessing.value = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "开始实时预览失败", e)
                _errorMessage.value = "开始实时预览失败: ${e.message}"
            }
        }
    }

    /**
     * 停止实时预览
     */
    fun stopLiveView() {
        viewModelScope.launch {
            try {
                cameraService?.stopLiveView()
                _isVideoStreamProcessing.value = false
                Log.i(TAG, "实时预览和视频流处理已停止")
            } catch (e: Exception) {
                Log.e(TAG, "停止实时预览失败", e)
            }
        }
    }

    /**
     * 获取视频流统计信息
     */
    fun getVideoStreamStats(): Map<String, Any> {
        return try {
            mapOf(
                "isProcessing" to _isVideoStreamProcessing.value,
                "frameRate" to _videoFrameRate.value,
                "hasError" to (_videoStreamError.value != null)
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取视频流统计信息异常", e)
            emptyMap()
        }
    }

    /**
     * 清理视频流错误
     */
    fun clearVideoStreamError() {
        if (_videoStreamError.value != null) {
            Log.i(TAG, "清理视频流错误")
            _videoStreamError.value = null
            stopLiveView()
            viewModelScope.launch {
                kotlinx.coroutines.delay(1000)
                startLiveView()
            }
        }
    }

    /**
     * 启动联机拍摄模式
     */
    fun startTetheredShooting() {
        viewModelScope.launch {
            try {
                cameraService?.startTetheredShooting()
                Log.i(TAG, "联机拍摄模式已启动")
            } catch (e: Exception) {
                _errorMessage.value = "启动联机拍摄失败: ${e.message}"
                Log.e(TAG, "启动联机拍摄失败", e)
            }
        }
    }

    /**
     * 停止联机拍摄模式
     */
    fun stopTetheredShooting() {
        viewModelScope.launch {
            try {
                cameraService?.stopTetheredShooting()
                Log.i(TAG, "联机拍摄模式已停止")
            } catch (e: Exception) {
                _errorMessage.value = "停止联机拍摄失败: ${e.message}"
                Log.e(TAG, "停止联机拍摄失败", e)
            }
        }
    }

    /**
     * 执行联机拍摄
     */
    fun capturePhotoTethered() {
        viewModelScope.launch {
            try {
                if (!isTetheredShootingActive.value) {
                    _errorMessage.value = "联机拍摄模式未激活"
                    return@launch
                }

                cameraService?.capturePhotoTethered()
                Log.i(TAG, "联机拍摄已触发")
            } catch (e: Exception) {
                _errorMessage.value = "联机拍摄失败: ${e.message}"
                Log.e(TAG, "联机拍摄失败", e)
            }
        }
    }

    /**
     * 设置自动LUT处理
     */
    fun setAutoLutProcessing(enabled: Boolean, lutFile: java.io.File? = null) {
        viewModelScope.launch {
            try {
                cameraService?.setAutoLutProcessing(enabled, lutFile)
                Log.i(TAG, "自动LUT处理设置: $enabled, 文件: ${lutFile?.name}")
            } catch (e: Exception) {
                _errorMessage.value = "设置自动LUT处理失败: ${e.message}"
                Log.e(TAG, "设置自动LUT处理失败", e)
            }
        }
    }

    /**
     * 获取拍摄历史
     */
    fun getShootingHistory(): List<java.io.File> {
        return try {
            cameraService?.getShootingHistory() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "获取拍摄历史失败", e)
            emptyList()
        }
    }

    /**
     * 清理联机拍摄错误
     */
    fun clearTetheredShootingError() {
        cameraService?.clearTetheredShootingError()
    }

    /**
     * 触发自动对焦
     */
    fun autoFocus() {
        viewModelScope.launch {
            try {
                val success = cameraService?.autoFocus() ?: false
                if (!success) {
                    _errorMessage.value = "自动对焦失败"
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动对焦失败", e)
                _errorMessage.value = "自动对焦失败: ${e.message}"
            }
        }
    }

    /**
     * 检查是否有相机连接
     */
    fun isCameraConnected(): Boolean {
        return cameraService?.isCameraConnected() ?: false
    }

    /**
     * 获取相机设备信息
     */
    suspend fun getCameraDeviceInfo(): String? {
        return cameraService?.getCameraDeviceInfo()
    }

    // ==================== 视频效果相关方法 ====================

    /**
     * 启用/禁用LUT效果
     */
    fun setLutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                _lutEnabled.value = enabled
                cameraService?.setLutEffect(enabled, _lutIntensity.value)
                Log.i(TAG, "LUT效果设置: $enabled")
            } catch (e: Exception) {
                Log.e(TAG, "设置LUT效果失败", e)
                _videoEffectsError.value = "设置LUT效果失败: ${e.message}"
            }
        }
    }

    /**
     * 设置LUT强度
     */
    fun setLutIntensity(intensity: Float) {
        viewModelScope.launch {
            try {
                val clampedIntensity = intensity.coerceIn(0f, 1f)
                _lutIntensity.value = clampedIntensity
                cameraService?.setLutEffect(_lutEnabled.value, clampedIntensity)
                Log.i(TAG, "LUT强度设置: $clampedIntensity")
            } catch (e: Exception) {
                Log.e(TAG, "设置LUT强度失败", e)
                _videoEffectsError.value = "设置LUT强度失败: ${e.message}"
            }
        }
    }

    /**
     * 加载LUT文件
     */
    fun loadLutFile(lutFile: java.io.File) {
        viewModelScope.launch {
            try {
                if (!lutFile.exists()) {
                    _videoEffectsError.value = "LUT文件不存在: ${lutFile.name}"
                    return@launch
                }

                cameraService?.loadLutFile(lutFile) { success ->
                    if (success) {
                        _currentLutFile.value = lutFile
                        Log.i(TAG, "LUT文件加载成功: ${lutFile.name}")
                    } else {
                        _videoEffectsError.value = "LUT文件加载失败: ${lutFile.name}"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载LUT文件失败", e)
                _videoEffectsError.value = "加载LUT文件失败: ${e.message}"
            }
        }
    }

    /**
     * 启用/禁用峰值显示
     */
    fun setPeakingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                _peakingEnabled.value = enabled
                cameraService?.setPeakingEffect(enabled, _peakingThreshold.value)
                Log.i(TAG, "峰值显示设置: $enabled")
            } catch (e: Exception) {
                Log.e(TAG, "设置峰值显示失败", e)
                _videoEffectsError.value = "设置峰值显示失败: ${e.message}"
            }
        }
    }

    /**
     * 设置峰值显示阈值
     */
    fun setPeakingThreshold(threshold: Float) {
        viewModelScope.launch {
            try {
                val clampedThreshold = threshold.coerceIn(0f, 1f)
                _peakingThreshold.value = clampedThreshold
                cameraService?.setPeakingEffect(_peakingEnabled.value, clampedThreshold)
                Log.i(TAG, "峰值显示阈值设置: $clampedThreshold")
            } catch (e: Exception) {
                Log.e(TAG, "设置峰值显示阈值失败", e)
                _videoEffectsError.value = "设置峰值显示阈值失败: ${e.message}"
            }
        }
    }

    /**
     * 启用/禁用波形图
     */
    fun setWaveformEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                _waveformEnabled.value = enabled
                cameraService?.setWaveformDisplay(enabled, _waveformHeight.value)
                Log.i(TAG, "波形图设置: $enabled")
            } catch (e: Exception) {
                Log.e(TAG, "设置波形图失败", e)
                _videoEffectsError.value = "设置波形图失败: ${e.message}"
            }
        }
    }

    /**
     * 设置波形图高度
     */
    fun setWaveformHeight(height: Float) {
        viewModelScope.launch {
            try {
                val clampedHeight = height.coerceIn(0.1f, 1f)
                _waveformHeight.value = clampedHeight
                cameraService?.setWaveformDisplay(_waveformEnabled.value, clampedHeight)
                Log.i(TAG, "波形图高度设置: $clampedHeight")
            } catch (e: Exception) {
                Log.e(TAG, "设置波形图高度失败", e)
                _videoEffectsError.value = "设置波形图高度失败: ${e.message}"
            }
        }
    }

    /**
     * 刷新可用LUT文件列表
     */
    fun refreshAvailableLuts() {
        viewModelScope.launch {
            try {
                cameraService?.refreshAvailableLuts()
                // 从VideoEffectsManager获取可用LUT列表
                val effectsManager = cameraService?.getVideoEffectsManager()
                effectsManager?.availableLuts?.value?.let { luts ->
                    _availableLuts.value = luts
                    Log.i(TAG, "可用LUT文件数量: ${luts.size}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "刷新LUT文件列表失败", e)
                _videoEffectsError.value = "刷新LUT文件列表失败: ${e.message}"
            }
        }
    }

    /**
     * 清除视频效果错误
     */
    fun clearVideoEffectsError() {
        _videoEffectsError.value = null
    }

    /**
     * 重置所有视频效果设置
     */
    fun resetVideoEffects() {
        viewModelScope.launch {
            try {
                _lutEnabled.value = false
                _lutIntensity.value = 1.0f
                _currentLutFile.value = null
                _peakingEnabled.value = false
                _peakingThreshold.value = 0.3f
                _waveformEnabled.value = false
                _waveformHeight.value = 0.3f

                cameraService?.resetAllVideoEffects()
                Log.i(TAG, "视频效果已重置")
            } catch (e: Exception) {
                Log.e(TAG, "重置视频效果失败", e)
                _videoEffectsError.value = "重置视频效果失败: ${e.message}"
            }
        }
    }

    /**
     * 获取当前视频效果状态
     */
    fun getVideoEffectsStatus(): Map<String, Any> {
        return mapOf(
            "lutEnabled" to _lutEnabled.value,
            "lutIntensity" to _lutIntensity.value,
            "currentLutFile" to (_currentLutFile.value?.name ?: "无"),
            "peakingEnabled" to _peakingEnabled.value,
            "peakingThreshold" to _peakingThreshold.value,
            "waveformEnabled" to _waveformEnabled.value,
            "waveformHeight" to _waveformHeight.value,
            "availableLutsCount" to _availableLuts.value.size
        )
    }
}