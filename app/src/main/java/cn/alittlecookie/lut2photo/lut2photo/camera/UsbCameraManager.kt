package cn.alittlecookie.lut2photo.lut2photo.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * USB相机管理器
 * 负责USB设备的检测、权限管理和连接
 */
class UsbCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbCameraManager"
        private const val ACTION_USB_PERMISSION = "cn.alittlecookie.lut2photo.USB_PERMISSION"

        // 支持的相机厂商ID (可扩展)
        private val SUPPORTED_VENDOR_IDS = setOf(
            0x04A9, // Canon
            0x054C, // Sony
            0x04B0, // Nikon
            0x05AC, // Apple
            0x040A, // Kodak
            0x04DA, // Panasonic
            0x04DD, // Sharp
            0x0553, // STMicroelectronics
            0x0BB4, // HTC
            0x22B8, // Motorola
            0x18D1  // Google
        )
    }

    /**
     * USB设备信息
     */
    data class UsbCameraInfo(
        val device: UsbDevice,
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val manufacturerName: String?,
        val productName: String?,
        val serialNumber: String?,
        val hasPermission: Boolean
    )

    /**
     * 连接状态
     */
    enum class ConnectionState {
        DISCONNECTED,
        PERMISSION_REQUESTING,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _connectedDevices = MutableStateFlow<List<UsbCameraInfo>>(emptyList())
    private val _availableDevices = MutableStateFlow<List<UsbCameraInfo>>(emptyList())

    val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()
    val connectedDevices: Flow<List<UsbCameraInfo>> = _connectedDevices.asStateFlow()
    val availableDevices: Flow<List<UsbCameraInfo>> = _availableDevices.asStateFlow()

    private var currentDevice: UsbDevice? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    // USB权限广播接收器
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                        Log.d(TAG, "USB权限结果: device=${device?.deviceName}, granted=$granted")

                        if (granted && device != null) {
                            Log.i(TAG, "USB权限已授予: ${device.deviceName}")
                            _connectionState.value = ConnectionState.CONNECTING
                        } else {
                            Log.w(TAG, "USB权限被拒绝")
                            _connectionState.value = ConnectionState.ERROR
                        }

                        permissionCallback?.invoke(granted)
                        permissionCallback = null
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        Log.i(TAG, "USB设备连接: ${it.deviceName}")
                        refreshAvailableDevices()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        Log.i(TAG, "USB设备断开: ${it.deviceName}")
                        if (currentDevice == it) {
                            currentDevice = null
                            _connectionState.value = ConnectionState.DISCONNECTED
                        }
                        refreshAvailableDevices()
                    }
                }
            }
        }
    }

    init {
        registerUsbReceiver()
        refreshAvailableDevices()
    }

    /**
     * 注册USB广播接收器
     */
    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Log.d(TAG, "USB广播接收器已注册")
    }

    /**
     * 刷新可用设备列表
     */
    fun refreshAvailableDevices() {
        val deviceList = usbManager.deviceList
        val cameraDevices = mutableListOf<UsbCameraInfo>()

        Log.d(TAG, "检测到 ${deviceList.size} 个USB设备")

        for ((_, device) in deviceList) {
            Log.d(
                TAG,
                "设备: ${device.deviceName}, VID: 0x${device.vendorId.toString(16)}, PID: 0x${
                    device.productId.toString(16)
                }"
            )

            // 检查是否为支持的相机设备
            if (isSupportedCameraDevice(device)) {
                val hasPermission = usbManager.hasPermission(device)
                val cameraInfo = UsbCameraInfo(
                    device = device,
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    manufacturerName = device.manufacturerName,
                    productName = device.productName,
                    serialNumber = device.serialNumber,
                    hasPermission = hasPermission
                )
                cameraDevices.add(cameraInfo)

                Log.i(
                    TAG,
                    "发现相机设备: ${cameraInfo.productName ?: cameraInfo.deviceName}, 权限: $hasPermission"
                )
            }
        }

        _availableDevices.value = cameraDevices
        Log.d(TAG, "可用相机设备数量: ${cameraDevices.size}")
    }

    /**
     * 检查是否为支持的相机设备
     */
    private fun isSupportedCameraDevice(device: UsbDevice): Boolean {
        // 检查厂商ID
        if (device.vendorId in SUPPORTED_VENDOR_IDS) {
            return true
        }

        // 检查设备类别 (可能是PTP设备)
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            // PTP设备通常使用类别6 (Still Image)
            if (usbInterface.interfaceClass == 6) {
                return true
            }
        }

        return false
    }

    /**
     * 请求USB设备权限
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "设备已有权限: ${device.deviceName}")
            callback(true)
            return
        }

        Log.d(TAG, "请求USB权限: ${device.deviceName}")
        _connectionState.value = ConnectionState.PERMISSION_REQUESTING
        permissionCallback = callback

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * 连接到指定设备
     */
    fun connectToDevice(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "设备无权限，先请求权限")
            requestPermission(device) { granted ->
                if (granted) {
                    connectToDevice(device, callback)
                } else {
                    callback(false)
                }
            }
            return
        }

        try {
            Log.i(TAG, "开始连接设备: ${device.deviceName}")
            _connectionState.value = ConnectionState.CONNECTING

            val connection = usbManager.openDevice(device)
            if (connection != null) {
                currentDevice = device
                _connectionState.value = ConnectionState.CONNECTED

                // 更新连接设备列表
                val connectedInfo = _availableDevices.value.find { it.device == device }
                if (connectedInfo != null) {
                    _connectedDevices.value = listOf(connectedInfo.copy(hasPermission = true))
                }

                Log.i(TAG, "设备连接成功: ${device.deviceName}")
                callback(true)
            } else {
                Log.e(TAG, "无法打开设备连接: ${device.deviceName}")
                _connectionState.value = ConnectionState.ERROR
                callback(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "连接设备失败: ${device.deviceName}", e)
            _connectionState.value = ConnectionState.ERROR
            callback(false)
        }
    }

    /**
     * 断开当前设备
     */
    fun disconnect() {
        currentDevice?.let { device ->
            try {
                Log.i(TAG, "断开设备: ${device.deviceName}")
                // 这里应该关闭USB连接，但UsbDeviceConnection需要在具体实现中管理
                currentDevice = null
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedDevices.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "断开设备失败", e)
            }
        }
    }

    /**
     * 获取当前连接的设备
     */
    fun getCurrentDevice(): UsbDevice? = currentDevice

    /**
     * 释放资源
     */
    fun release() {
        try {
            context.unregisterReceiver(usbReceiver)
            Log.d(TAG, "USB广播接收器已注销")
        } catch (e: Exception) {
            Log.e(TAG, "注销USB广播接收器失败", e)
        }

        disconnect()
        permissionCallback = null
    }
}