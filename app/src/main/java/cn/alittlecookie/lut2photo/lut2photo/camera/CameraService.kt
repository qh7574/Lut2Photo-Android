package cn.alittlecookie.lut2photo.lut2photo.camera

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import cn.alittlecookie.lut2photo.lut2photo.camera.TetheredShootingManager
import cn.alittlecookie.lut2photo.lut2photo.lut.LutProcessor
import java.io.File

/**
 * 相机服务，负责管理相机设备的生命周期和状态
 */
class CameraService : Service() {

    companion object {
        private const val TAG = "CameraService"

        // 服务状态
        const val STATE_IDLE = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        const val STATE_DISCONNECTING = 3
        const val STATE_ERROR = 4
    }

    private val binder = CameraServiceBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var usbCameraManager: UsbCameraManager? = null
    private var currentCameraDevice: ICameraDevice? = null

    // 视频流处理器
    private val videoStreamProcessor = VideoStreamProcessor()

    // 联机拍摄管理器
    private var tetheredShootingManager: TetheredShootingManager? = null

    // LUT处理器
    private var lutProcessor: LutProcessor? = null

    // 视频效果管理器
    private lateinit var videoEffectsManager: VideoEffectsManager

    private val _serviceState = MutableStateFlow(STATE_IDLE)
    private val _connectedCamera = MutableStateFlow<ICameraDevice?>(null)
    private val _availableCameras =
        MutableStateFlow<List<UsbCameraManager.UsbCameraInfo>>(emptyList())
    private val _errorMessage = MutableStateFlow<String?>(null)

    // 公开的状态流
    val serviceState: StateFlow<Int> = _serviceState.asStateFlow()
    val connectedCamera: StateFlow<ICameraDevice?> = _connectedCamera.asStateFlow()
    val availableCameras: StateFlow<List<UsbCameraManager.UsbCameraInfo>> =
        _availableCameras.asStateFlow()
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 视频流相关状态
    val isVideoStreamProcessing: StateFlow<Boolean> = videoStreamProcessor.isProcessing
    val currentVideoFrame: StateFlow<Bitmap?> = videoStreamProcessor.currentFrame
    val videoFrameRate: StateFlow<Float> = videoStreamProcessor.frameRate
    val videoStreamError: StateFlow<String?> = videoStreamProcessor.errorMessage

    // 联机拍摄相关状态
    val isTetheredShootingActive: StateFlow<Boolean>
        get() = tetheredShootingManager?.isActive ?: MutableStateFlow(false)

    val tetheredShootingProgress: StateFlow<TetheredShootingManager.ShootingProgress?>
        get() = tetheredShootingManager?.shootingProgress ?: MutableStateFlow(null)

    val lastCapturedPhoto: StateFlow<TetheredShootingManager.CapturedPhoto?>
        get() = tetheredShootingManager?.lastCapturedPhoto ?: MutableStateFlow(null)

    val tetheredShootingError: StateFlow<String?>
        get() = tetheredShootingManager?.errorMessage ?: MutableStateFlow(null)

    inner class CameraServiceBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "相机服务创建")

        // 初始化USB相机管理器
        usbCameraManager = UsbCameraManager(this)

        // 初始化LUT处理器
        lutProcessor = LutProcessor()

        // 初始化视频效果管理器
        videoEffectsManager = VideoEffectsManager(this)

        // 监听USB设备变化
        observeUsbDeviceChanges()

        // 刷新可用相机列表
        refreshAvailableCameras()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "相机服务销毁")

        // 断开当前相机连接
        serviceScope.launch {
            disconnectCamera()
        }

        // 释放资源
        videoStreamProcessor.release()
        tetheredShootingManager?.release()
        tetheredShootingManager = null
        lutProcessor = null

        // 释放视频效果管理器
        videoEffectsManager.release()

        usbCameraManager = null
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun observeUsbDeviceChanges() {
        usbCameraManager?.let { manager ->
            serviceScope.launch {
                manager.connectionState.collect { state ->
                    when (state) {
                        UsbCameraManager.ConnectionState.CONNECTED -> {
                            Log.d(TAG, "USB设备已连接")
                            refreshAvailableCameras()
                        }

                        UsbCameraManager.ConnectionState.DISCONNECTED -> {
                            Log.d(TAG, "USB设备已断开")
                            // 如果当前连接的相机被断开，则断开连接
                            if (currentCameraDevice != null) {
                                disconnectCamera()
                            }
                            refreshAvailableCameras()
                        }

                        UsbCameraManager.ConnectionState.PERMISSION_REQUESTING -> {
                            Log.d(TAG, "正在请求USB权限")
                        }

                        UsbCameraManager.ConnectionState.ERROR -> {
                            Log.w(TAG, "USB连接错误")
                            _serviceState.value = STATE_ERROR
                            _errorMessage.value = "USB连接错误"
                        }

                        else -> {
                            // 其他状态
                        }
                    }
                }
            }
        }
    }

    /**
     * 刷新可用相机列表
     */
    fun refreshAvailableCameras() {
        usbCameraManager?.refreshAvailableDevices()
        serviceScope.launch {
            usbCameraManager?.availableDevices?.collect { cameras ->
                _availableCameras.value = cameras
                Log.d(TAG, "刷新相机列表，发现 ${cameras.size} 台相机")
            }
        }
    }

    /**
     * 连接到指定的相机设备
     */
    fun connectToCamera(cameraInfo: UsbCameraManager.UsbCameraInfo) {
        serviceScope.launch {
            try {
                Log.i(TAG, "开始连接相机: ${cameraInfo.deviceName}")
                _serviceState.value = STATE_CONNECTING
                _errorMessage.value = null

                // 如果已有连接的相机，先断开
                if (currentCameraDevice != null) {
                    disconnectCamera()
                }

                // 请求USB权限
                usbCameraManager?.requestPermission(cameraInfo.device) { hasPermission ->
                    if (!hasPermission) {
                        _serviceState.value = STATE_ERROR
                        _errorMessage.value = "无法获取USB设备权限"
                        return@requestPermission
                    }

                    serviceScope.launch {
                        // 创建相机设备实例
                        val cameraDevice =
                            GPhoto2CameraDevice(this@CameraService, cameraInfo.device)

                        // 尝试连接
                        val connected = cameraDevice.connect()
                        if (connected) {
                            currentCameraDevice = cameraDevice
                            _connectedCamera.value = cameraDevice
                            _serviceState.value = STATE_CONNECTED

                            // 设置视频流处理器的相机设备
                            videoStreamProcessor.setCameraDevice(cameraDevice)

                            // 初始化联机拍摄管理器
                            lutProcessor?.let { processor ->
                                tetheredShootingManager = TetheredShootingManager(
                                    context = this@CameraService,
                                    cameraDevice = cameraDevice,
                                    lutProcessor = processor
                                )
                            }

                            // 监听相机连接状态
                            observeCameraConnectionStatus(cameraDevice)

                            Log.i(TAG, "相机连接成功: ${cameraInfo.deviceName}")
                        } else {
                            _serviceState.value = STATE_ERROR
                            _errorMessage.value = "相机连接失败"
                            cameraDevice.release()
                            Log.e(TAG, "相机连接失败: ${cameraInfo.deviceName}")
                        }
                    }
                }
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "连接相机时发生异常", e)
                _serviceState.value = STATE_ERROR
                _errorMessage.value = "连接相机异常: ${e.message}"
            }
        }
    }

    /**
     * 断开当前相机连接
     */
    fun disconnectCamera() {
        serviceScope.launch {
            try {
                if (currentCameraDevice == null) {
                    return@launch
                }

                Log.i(TAG, "断开相机连接")
                _serviceState.value = STATE_DISCONNECTING

                // 停止视频流处理
                videoStreamProcessor.stopProcessing()
                videoStreamProcessor.setCameraDevice(null)

                // 停止联机拍摄并释放资源
                tetheredShootingManager?.stopTetheredShooting()
                tetheredShootingManager?.release()
                tetheredShootingManager = null

                currentCameraDevice?.disconnect()
                currentCameraDevice?.release()
                currentCameraDevice = null

                _connectedCamera.value = null
                _serviceState.value = STATE_IDLE

                Log.i(TAG, "相机连接已断开")
            } catch (e: Exception) {
                Log.e(TAG, "断开相机连接时发生异常", e)
                _serviceState.value = STATE_ERROR
                _errorMessage.value = "断开连接异常: ${e.message}"
            }
        }
    }

    private fun observeCameraConnectionStatus(cameraDevice: ICameraDevice) {
        serviceScope.launch {
            cameraDevice.getConnectionStatus().collect { status ->
                when (status) {
                    ICameraDevice.ConnectionStatus.DISCONNECTED -> {
                        if (currentCameraDevice == cameraDevice) {
                            Log.w(TAG, "相机连接意外断开")
                            currentCameraDevice = null
                            _connectedCamera.value = null
                            _serviceState.value = STATE_IDLE
                        }
                    }

                    ICameraDevice.ConnectionStatus.ERROR -> {
                        if (currentCameraDevice == cameraDevice) {
                            Log.e(TAG, "相机连接出现错误")
                            _serviceState.value = STATE_ERROR
                            _errorMessage.value = "相机连接错误"
                        }
                    }

                    else -> {
                        // 其他状态已在连接过程中处理
                    }
                }
            }
        }
    }

    /**
     * 获取当前连接的相机设备
     */
    fun getCurrentCamera(): ICameraDevice? {
        return currentCameraDevice
    }

    /**
     * 检查是否有相机连接
     */
    fun isCameraConnected(): Boolean {
        return currentCameraDevice?.isConnected() == true
    }

    /**
     * 清除错误消息
     */
    fun clearError() {
        _errorMessage.value = null
        if (_serviceState.value == STATE_ERROR) {
            _serviceState.value = STATE_IDLE
        }
    }

    /**
     * 获取相机设备信息
     */
    suspend fun getCameraDeviceInfo(): String? {
        return currentCameraDevice?.getDeviceInfo()
    }

    /**
     * 获取相机能力
     */
    suspend fun getCameraCapabilities(): ICameraDevice.CameraCapabilities? {
        return currentCameraDevice?.getCameraCapabilities()
    }

    /**
     * 获取当前相机参数
     */
    suspend fun getCurrentCameraParameters(): ICameraDevice.CameraParameters? {
        return currentCameraDevice?.getCurrentParameters()
    }

    /**
     * 设置相机参数
     */
    suspend fun setCameraParameters(parameters: ICameraDevice.CameraParameters): Boolean {
        return currentCameraDevice?.setParameters(parameters) ?: false
    }

    /**
     * 拍摄照片
     */
    suspend fun captureImage(): ICameraDevice.CaptureResult? {
        return currentCameraDevice?.captureImage()
    }

    /**
     * 开始录制视频
     */
    suspend fun startVideoRecording(): Boolean {
        return currentCameraDevice?.startVideoRecording() ?: false
    }

    /**
     * 停止录制视频
     */
    suspend fun stopVideoRecording(): ICameraDevice.CaptureResult? {
        return currentCameraDevice?.stopVideoRecording()
    }

    /**
     * 开始实时预览
     */
    suspend fun startLiveView(): Boolean {
        val success = currentCameraDevice?.startLiveView() ?: false
        if (success) {
            // 启动视频流处理
            videoStreamProcessor.startProcessing()
        }
        return success
    }

    /**
     * 停止实时预览
     */
    suspend fun stopLiveView() {
        // 停止视频流处理
        videoStreamProcessor.stopProcessing()
        currentCameraDevice?.stopLiveView()
    }

    /**
     * 触发自动对焦
     */
    suspend fun autoFocus(): Boolean {
        return currentCameraDevice?.autoFocus() ?: false
    }

    /**
     * 启动联机拍摄模式
     */
    fun startTetheredShooting() {
        tetheredShootingManager?.startTetheredShooting()
    }

    /**
     * 停止联机拍摄模式
     */
    fun stopTetheredShooting() {
        tetheredShootingManager?.stopTetheredShooting()
    }

    /**
     * 执行联机拍摄
     */
    fun capturePhotoTethered() {
        tetheredShootingManager?.capturePhoto()
    }

    /**
     * 设置自动LUT处理
     * @param enabled 是否启用自动LUT处理
     * @param lutFile LUT文件，如果为null则使用默认LUT
     */
    fun setAutoLutProcessing(enabled: Boolean, lutFile: java.io.File? = null) {
        tetheredShootingManager?.setAutoLutProcessing(enabled, lutFile)
    }

    /**
     * 获取拍摄历史
     */
    fun getShootingHistory(): List<java.io.File> {
        return tetheredShootingManager?.getShootingHistory() ?: emptyList()
    }

    /**
     * 清除联机拍摄错误
     */
    fun clearTetheredShootingError() {
        tetheredShootingManager?.clearError()
    }

    // ==================== 视频效果相关方法 ====================

    /**
     * 绑定VideoSurfaceView到视频效果管理器
     */
    fun bindVideoSurfaceView(surfaceView: VideoSurfaceView) {
        videoEffectsManager.bindVideoSurfaceView(surfaceView)
    }

    /**
     * 解绑VideoSurfaceView
     */
    fun unbindVideoSurfaceView() {
        videoEffectsManager.unbindVideoSurfaceView()
    }

    /**
     * 加载LUT文件
     */
    fun loadLutFile(lutFile: File, callback: (Boolean) -> Unit) {
        videoEffectsManager.loadLut(lutFile, callback)
    }

    /**
     * 设置LUT效果
     */
    fun setLutEffect(enabled: Boolean, intensity: Float = 1.0f) {
        videoEffectsManager.setLutEffect(enabled, intensity)
    }

    /**
     * 设置峰值显示
     */
    fun setPeakingEffect(enabled: Boolean, threshold: Float = 0.3f) {
        videoEffectsManager.setPeakingEffect(enabled, threshold)
    }

    /**
     * 设置波形图显示
     */
    fun setWaveformDisplay(enabled: Boolean, height: Float = 0.3f) {
        videoEffectsManager.setWaveformDisplay(enabled, height)
    }

    /**
     * 切换LUT效果
     */
    fun toggleLutEffect() {
        videoEffectsManager.toggleLutEffect()
    }

    /**
     * 切换峰值显示
     */
    fun togglePeakingEffect() {
        videoEffectsManager.togglePeakingEffect()
    }

    /**
     * 切换波形图显示
     */
    fun toggleWaveformDisplay() {
        videoEffectsManager.toggleWaveformDisplay()
    }

    /**
     * 重置所有视频效果
     */
    fun resetAllVideoEffects() {
        videoEffectsManager.resetAllEffects()
    }

    /**
     * 获取视频效果状态
     */
    fun getVideoEffectsStats(): Map<String, Any> {
        return videoEffectsManager.getEffectsStats()
    }

    /**
     * 刷新可用的LUT文件列表
     */
    fun refreshAvailableLuts() {
        videoEffectsManager.refreshAvailableLuts()
    }

    /**
     * 获取视频效果管理器
     */
    fun getVideoEffectsManager(): VideoEffectsManager {
        return videoEffectsManager
    }
}