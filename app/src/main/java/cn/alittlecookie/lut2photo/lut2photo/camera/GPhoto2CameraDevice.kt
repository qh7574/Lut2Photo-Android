package cn.alittlecookie.lut2photo.lut2photo.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * 基于libgphoto2的相机设备实现
 */
class GPhoto2CameraDevice(
    private val context: Context,
    private val usbDevice: UsbDevice
) : ICameraDevice {

    companion object {
        private const val TAG = "GPhoto2CameraDevice"

        // 常用配置项名称
        private const val CONFIG_APERTURE = "aperture"
        private const val CONFIG_SHUTTERSPEED = "shutterspeed"
        private const val CONFIG_ISO = "iso"
        private const val CONFIG_WHITEBALANCE = "whitebalance"
        private const val CONFIG_FOCUSMODE = "focusmode"
        private const val CONFIG_IMAGEFORMAT = "imageformat"
        private const val CONFIG_IMAGEQUALITY = "imagequality"
    }

    private val libGPhoto2 = LibGPhoto2JNI()
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectionStatus = MutableStateFlow(ICameraDevice.ConnectionStatus.DISCONNECTED)
    private val _liveViewStream = MutableStateFlow<Bitmap?>(null)

    private var usbConnection: UsbDeviceConnection? = null
    private var isLiveViewActive = false
    private var deviceInfo: String? = null
    private var capabilities: ICameraDevice.CameraCapabilities? = null

    override fun getConnectionStatus(): Flow<ICameraDevice.ConnectionStatus> =
        _connectionStatus.asStateFlow()

    override fun getLiveViewStream(): Flow<Bitmap?> = _liveViewStream.asStateFlow()

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "开始连接相机设备: ${usbDevice.deviceName}")
            _connectionStatus.value = ICameraDevice.ConnectionStatus.CONNECTING

            // 1. 检查USB权限
            if (!usbManager.hasPermission(usbDevice)) {
                Log.e(TAG, "没有USB设备权限")
                _connectionStatus.value = ICameraDevice.ConnectionStatus.ERROR
                return@withContext false
            }

            // 2. 打开USB连接
            usbConnection = usbManager.openDevice(usbDevice)
            if (usbConnection == null) {
                Log.e(TAG, "无法打开USB设备连接")
                _connectionStatus.value = ICameraDevice.ConnectionStatus.ERROR
                return@withContext false
            }

            // 3. 初始化libgphoto2
            if (!libGPhoto2.initialize()) {
                Log.e(TAG, "libgphoto2初始化失败")
                _connectionStatus.value = ICameraDevice.ConnectionStatus.ERROR
                return@withContext false
            }

            // 4. 设置USB文件描述符
            val fd = usbConnection!!.fileDescriptor
            if (!libGPhoto2.setUsbDeviceFd(fd)) {
                Log.e(TAG, "设置USB文件描述符失败")
                _connectionStatus.value = ICameraDevice.ConnectionStatus.ERROR
                return@withContext false
            }

            // 5. 自动检测相机
            val cameras = libGPhoto2.autoDetectCameras()
            if (cameras.isNullOrEmpty()) {
                Log.e(TAG, "未检测到相机")
                _connectionStatus.value = ICameraDevice.ConnectionStatus.ERROR
                return@withContext false
            }

            // 6. 连接到第一个检测到的相机
            val camera = cameras[0]
            if (!libGPhoto2.connectCamera(camera.model, camera.port)) {
                Log.e(TAG, "连接相机失败: ${camera.model}")
                _connectionStatus.value = ICameraDevice.ConnectionStatus.ERROR
                return@withContext false
            }

            // 7. 获取设备信息和能力
            deviceInfo = libGPhoto2.getCameraSummary()
            capabilities = loadCameraCapabilities()

            _connectionStatus.value = ICameraDevice.ConnectionStatus.CONNECTED
            Log.i(TAG, "相机连接成功: ${camera.model}")

            true
        } catch (e: Exception) {
            Log.e(TAG, "连接相机时发生异常", e)
            _connectionStatus.value = ICameraDevice.ConnectionStatus.ERROR
            false
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "断开相机连接")

            // 停止实时预览
            if (isLiveViewActive) {
                stopLiveView()
            }

            // 断开libgphoto2连接
            libGPhoto2.disconnectCamera()
            libGPhoto2.cleanup()

            // 关闭USB连接
            usbConnection?.close()
            usbConnection = null

            _connectionStatus.value = ICameraDevice.ConnectionStatus.DISCONNECTED
            Log.i(TAG, "相机连接已断开")
        } catch (e: Exception) {
            Log.e(TAG, "断开相机连接时发生异常", e)
        }
    }

    override fun isConnected(): Boolean {
        return _connectionStatus.value == ICameraDevice.ConnectionStatus.CONNECTED &&
                libGPhoto2.isCameraConnected()
    }

    override suspend fun getDeviceInfo(): String? = withContext(Dispatchers.IO) {
        deviceInfo ?: libGPhoto2.getCameraSummary()
    }

    override suspend fun getCameraCapabilities(): ICameraDevice.CameraCapabilities? =
        withContext(Dispatchers.IO) {
            capabilities ?: loadCameraCapabilities()
        }

    private suspend fun loadCameraCapabilities(): ICameraDevice.CameraCapabilities? =
        withContext(Dispatchers.IO) {
            try {
                val configs = libGPhoto2.getAllConfigs() ?: return@withContext null

                val supportedApertures = mutableListOf<Float>()
                val supportedShutterSpeeds = mutableListOf<String>()
                val supportedIsoValues = mutableListOf<Int>()
                val supportedWhiteBalances = mutableListOf<String>()
                val supportedFocusModes = mutableListOf<String>()
                val supportedImageFormats = mutableListOf<String>()
                val supportedImageQualities = mutableListOf<String>()

                for (config in configs) {
                    when (config.name.lowercase()) {
                        CONFIG_APERTURE -> {
                            config.choices?.forEach { choice ->
                                try {
                                    supportedApertures.add(choice.toFloat())
                                } catch (e: NumberFormatException) {
                                    // 忽略无法解析的值
                                }
                            }
                        }

                        CONFIG_SHUTTERSPEED -> {
                            config.choices?.let { supportedShutterSpeeds.addAll(it) }
                        }

                        CONFIG_ISO -> {
                            config.choices?.forEach { choice ->
                                try {
                                    supportedIsoValues.add(choice.toInt())
                                } catch (e: NumberFormatException) {
                                    // 忽略无法解析的值
                                }
                            }
                        }

                        CONFIG_WHITEBALANCE -> {
                            config.choices?.let { supportedWhiteBalances.addAll(it) }
                        }

                        CONFIG_FOCUSMODE -> {
                            config.choices?.let { supportedFocusModes.addAll(it) }
                        }

                        CONFIG_IMAGEFORMAT -> {
                            config.choices?.let { supportedImageFormats.addAll(it) }
                        }

                        CONFIG_IMAGEQUALITY -> {
                            config.choices?.let { supportedImageQualities.addAll(it) }
                        }
                    }
                }

                // 检查支持的操作
                val operations = libGPhoto2.getSupportedOperations() ?: emptyArray()
                val hasLiveView = operations.contains("preview") || operations.contains("liveview")
                val hasVideoRecording = operations.contains("movie") || operations.contains("video")

                ICameraDevice.CameraCapabilities(
                    supportedApertures = supportedApertures,
                    supportedShutterSpeeds = supportedShutterSpeeds,
                    supportedIsoValues = supportedIsoValues,
                    supportedWhiteBalances = supportedWhiteBalances,
                    supportedFocusModes = supportedFocusModes,
                    supportedImageFormats = supportedImageFormats,
                    supportedImageQualities = supportedImageQualities,
                    hasLiveView = hasLiveView,
                    hasVideoRecording = hasVideoRecording
                )
            } catch (e: Exception) {
                Log.e(TAG, "加载相机能力失败", e)
                null
            }
        }

    override suspend fun getCurrentParameters(): ICameraDevice.CameraParameters? =
        withContext(Dispatchers.IO) {
            try {
                val aperture = libGPhoto2.getConfig(CONFIG_APERTURE)?.value?.toFloatOrNull()
                val shutterSpeed = libGPhoto2.getConfig(CONFIG_SHUTTERSPEED)?.value
                val iso = libGPhoto2.getConfig(CONFIG_ISO)?.value?.toIntOrNull()
                val whiteBalance = libGPhoto2.getConfig(CONFIG_WHITEBALANCE)?.value
                val focusMode = libGPhoto2.getConfig(CONFIG_FOCUSMODE)?.value
                val imageFormat = libGPhoto2.getConfig(CONFIG_IMAGEFORMAT)?.value
                val imageQuality = libGPhoto2.getConfig(CONFIG_IMAGEQUALITY)?.value

                ICameraDevice.CameraParameters(
                    aperture = aperture,
                    shutterSpeed = shutterSpeed,
                    iso = iso,
                    whiteBalance = whiteBalance,
                    focusMode = focusMode,
                    imageFormat = imageFormat,
                    imageQuality = imageQuality
                )
            } catch (e: Exception) {
                Log.e(TAG, "获取相机参数失败", e)
                null
            }
        }

    override suspend fun setParameters(parameters: ICameraDevice.CameraParameters): Boolean =
        withContext(Dispatchers.IO) {
            try {
                var success = true

                parameters.aperture?.let {
                    success = success && libGPhoto2.setConfig(CONFIG_APERTURE, it.toString())
                }

                parameters.shutterSpeed?.let {
                    success = success && libGPhoto2.setConfig(CONFIG_SHUTTERSPEED, it)
                }

                parameters.iso?.let {
                    success = success && libGPhoto2.setConfig(CONFIG_ISO, it.toString())
                }

                parameters.whiteBalance?.let {
                    success = success && libGPhoto2.setConfig(CONFIG_WHITEBALANCE, it)
                }

                parameters.focusMode?.let {
                    success = success && libGPhoto2.setConfig(CONFIG_FOCUSMODE, it)
                }

                parameters.imageFormat?.let {
                    success = success && libGPhoto2.setConfig(CONFIG_IMAGEFORMAT, it)
                }

                parameters.imageQuality?.let {
                    success = success && libGPhoto2.setConfig(CONFIG_IMAGEQUALITY, it)
                }

                if (success) {
                    Log.d(TAG, "相机参数设置成功")
                } else {
                    Log.w(TAG, "部分相机参数设置失败")
                }

                success
            } catch (e: Exception) {
                Log.e(TAG, "设置相机参数失败", e)
                false
            }
        }

    override suspend fun setAperture(aperture: Float): Boolean = withContext(Dispatchers.IO) {
        libGPhoto2.setConfig(CONFIG_APERTURE, aperture.toString())
    }

    override suspend fun setShutterSpeed(shutterSpeed: String): Boolean =
        withContext(Dispatchers.IO) {
            libGPhoto2.setConfig(CONFIG_SHUTTERSPEED, shutterSpeed)
        }

    override suspend fun setIso(iso: Int): Boolean = withContext(Dispatchers.IO) {
        libGPhoto2.setConfig(CONFIG_ISO, iso.toString())
    }

    override suspend fun setWhiteBalance(whiteBalance: String): Boolean =
        withContext(Dispatchers.IO) {
            libGPhoto2.setConfig(CONFIG_WHITEBALANCE, whiteBalance)
        }

    override suspend fun setFocusMode(focusMode: String): Boolean = withContext(Dispatchers.IO) {
        libGPhoto2.setConfig(CONFIG_FOCUSMODE, focusMode)
    }

    override suspend fun captureImage(): ICameraDevice.CaptureResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始拍摄照片")

            val imageData = libGPhoto2.captureImage()
            if (imageData != null) {
                Log.i(TAG, "照片拍摄成功，大小: ${imageData.size} bytes")
                ICameraDevice.CaptureResult(
                    isSuccess = true,
                    imageData = imageData
                )
            } else {
                val error = libGPhoto2.getLastError() ?: "未知错误"
                Log.e(TAG, "照片拍摄失败: $error")
                ICameraDevice.CaptureResult(
                    isSuccess = false,
                    errorMessage = error
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "拍摄照片时发生异常", e)
            ICameraDevice.CaptureResult(
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }

    override suspend fun startVideoRecording(): Boolean = withContext(Dispatchers.IO) {
        // libgphoto2的视频录制支持有限，这里返回false
        // 实际实现需要根据具体相机型号和libgphoto2版本来确定
        Log.w(TAG, "视频录制功能暂未实现")
        false
    }

    override suspend fun stopVideoRecording(): ICameraDevice.CaptureResult =
        withContext(Dispatchers.IO) {
            Log.w(TAG, "视频录制功能暂未实现")
            ICameraDevice.CaptureResult(
                isSuccess = false,
                errorMessage = "视频录制功能暂未实现"
            )
        }

    override suspend fun startLiveView(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isLiveViewActive) {
                Log.w(TAG, "实时预览已经在运行")
                return@withContext true
            }

            Log.d(TAG, "开始实时预览")

            if (libGPhoto2.startLiveView()) {
                isLiveViewActive = true

                // 启动预览帧获取循环
                startPreviewLoop()

                Log.i(TAG, "实时预览启动成功")
                true
            } else {
                val error = libGPhoto2.getLastError() ?: "未知错误"
                Log.e(TAG, "实时预览启动失败: $error")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动实时预览时发生异常", e)
            false
        }
    }

    override suspend fun stopLiveView() = withContext(Dispatchers.IO) {
        try {
            if (!isLiveViewActive) {
                return@withContext
            }

            Log.d(TAG, "停止实时预览")

            isLiveViewActive = false
            libGPhoto2.stopLiveView()
            _liveViewStream.value = null

            Log.i(TAG, "实时预览已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止实时预览时发生异常", e)
        }
    }

    private suspend fun startPreviewLoop() = withContext(Dispatchers.IO) {
        while (isLiveViewActive && isConnected()) {
            try {
                val frameData = libGPhoto2.getPreviewFrame()
                if (frameData != null) {
                    val bitmap = BitmapFactory.decodeStream(ByteArrayInputStream(frameData))
                    if (bitmap != null) {
                        _liveViewStream.value = bitmap
                    }
                }

                // 控制帧率，避免过度消耗资源
                kotlinx.coroutines.delay(33) // 约30fps
            } catch (e: Exception) {
                Log.e(TAG, "获取预览帧时发生异常", e)
                kotlinx.coroutines.delay(100) // 出错时延长等待时间
            }
        }
    }

    override suspend fun autoFocus(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "触发自动对焦")
            val result = libGPhoto2.triggerAutoFocus()
            if (result) {
                Log.i(TAG, "自动对焦成功")
            } else {
                Log.w(TAG, "自动对焦失败")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "自动对焦时发生异常", e)
            false
        }
    }

    override suspend fun getPreviewFrame(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) {
                Log.e(TAG, "相机未连接，无法获取预览帧")
                return@withContext null
            }

            if (!isLiveViewActive) {
                Log.w(TAG, "实时预览未启动，无法获取预览帧")
                return@withContext null
            }

            // 获取预览帧数据
            val frameData = libGPhoto2.getPreviewFrame()

            if (frameData != null && frameData.isNotEmpty()) {
                Log.d(TAG, "获取预览帧成功，大小: ${frameData.size} 字节")
            } else {
                Log.w(TAG, "获取预览帧失败或数据为空")
            }

            frameData
        } catch (e: Exception) {
            Log.e(TAG, "获取预览帧异常", e)
            null
        }
    }

    override suspend fun downloadLatestFile(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) {
                Log.w(TAG, "相机未连接")
                return@withContext null
            }

            // 获取文件列表并下载最新的文件
            val fileList = libGPhoto2.getFileList("/") ?: emptyArray()
            if (fileList.isEmpty()) {
                Log.w(TAG, "相机中没有文件")
                return@withContext null
            }

            // 假设最后一个文件是最新的
            val latestFile = fileList.last()
            Log.i(TAG, "下载最新文件: $latestFile")

            libGPhoto2.downloadFile("/", latestFile)
        } catch (e: Exception) {
            Log.e(TAG, "下载最新文件失败", e)
            null
        }
    }

    override suspend fun downloadFile(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) {
                Log.w(TAG, "相机未连接")
                return@withContext null
            }

            Log.i(TAG, "下载文件: $fileName")
            libGPhoto2.downloadFile("/", fileName)
        } catch (e: Exception) {
            Log.e(TAG, "下载文件失败: $fileName", e)
            null
        }
    }

    override suspend fun getFileList(): List<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) {
                Log.w(TAG, "相机未连接")
                return@withContext emptyList()
            }

            val fileList = libGPhoto2.getFileList("/") ?: emptyArray()
            Log.i(TAG, "获取到 ${fileList.size} 个文件")
            fileList.toList()
        } catch (e: Exception) {
            Log.e(TAG, "获取文件列表失败", e)
            emptyList()
        }
    }

    override suspend fun deleteFile(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConnected()) {
                Log.w(TAG, "相机未连接")
                return@withContext false
            }

            Log.i(TAG, "删除文件: $fileName")
            val success = libGPhoto2.deleteFile("/", fileName)
            if (success) {
                Log.i(TAG, "文件删除成功: $fileName")
            } else {
                Log.w(TAG, "文件删除失败: $fileName")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "删除文件异常: $fileName", e)
            false
        }
    }

    override fun release() {
        try {
            Log.d(TAG, "释放相机设备资源")

            // 停止实时预览
            if (isLiveViewActive) {
                kotlinx.coroutines.runBlocking {
                    stopLiveView()
                }
            }

            // 断开连接
            kotlinx.coroutines.runBlocking {
                disconnect()
            }

            Log.d(TAG, "相机设备资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放相机设备资源时发生异常", e)
        }
    }
}