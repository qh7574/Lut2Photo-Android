package cn.alittlecookie.lut2photo.lut2photo.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * USB 权限管理器
 * 负责请求 USB 设备权限并获取文件描述符
 */
class UsbPermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbPermissionManager"
        private const val ACTION_USB_PERMISSION = "cn.alittlecookie.lut2photo.USB_PERMISSION"
        
        // PTP 相机的 USB 类代码
        private const val USB_CLASS_PTP = 6  // Still Image Capture
        private const val USB_SUBCLASS_PTP = 1
        private const val USB_PROTOCOL_PTP = 1
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var permissionCallback: ((Boolean, UsbDevice?) -> Unit)? = null
    private var currentDevice: UsbDevice? = null
    private var currentConnection: UsbDeviceConnection? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "收到广播: ${intent?.action}")
            if (intent?.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    
                    Log.i(TAG, "USB 权限结果: granted=$granted, device=${device?.deviceName}")
                    
                    if (permissionCallback != null) {
                        permissionCallback?.invoke(granted, device)
                        permissionCallback = null
                    } else {
                        Log.w(TAG, "权限回调为空，可能已被处理或未设置")
                    }
                }
            }
        }
    }

    init {
        // 注册广播接收器
        // 注意：USB 权限广播是由系统发送的，需要使用 RECEIVER_EXPORTED
        // 但由于我们使用自定义 action，实际上是 PendingIntent 回调，所以用 NOT_EXPORTED
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        Log.d(TAG, "USB 权限广播接收器已注册")
    }

    /**
     * 查找 PTP 相机设备
     */
    fun findPtpCamera(): UsbDevice? {
        val deviceList = usbManager.deviceList
        Log.d(TAG, "发现 ${deviceList.size} 个 USB 设备")
        
        for ((name, device) in deviceList) {
            Log.d(TAG, "设备: $name, VID=${device.vendorId}, PID=${device.productId}, Class=${device.deviceClass}")
            
            // 检查设备类或接口类是否为 PTP
            if (isPtpDevice(device)) {
                Log.i(TAG, "找到 PTP 相机: $name")
                return device
            }
        }
        
        // 如果没有找到 PTP 设备，返回第一个非 Hub 设备（可能是相机）
        for ((name, device) in deviceList) {
            if (device.deviceClass != 9) { // 9 = USB Hub
                Log.i(TAG, "使用设备: $name (可能是相机)")
                return device
            }
        }
        
        Log.w(TAG, "未找到 PTP 相机")
        return null
    }

    /**
     * 检查设备是否为 PTP 设备
     */
    private fun isPtpDevice(device: UsbDevice): Boolean {
        // 检查设备类
        if (device.deviceClass == USB_CLASS_PTP) {
            return true
        }
        
        // 检查接口类
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == USB_CLASS_PTP) {
                return true
            }
        }
        
        return false
    }

    /**
     * 检查是否有 USB 权限
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * 请求 USB 权限
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean, UsbDevice?) -> Unit) {
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "已有 USB 权限")
            callback(true, device)
            return
        }
        
        Log.i(TAG, "请求 USB 权限: ${device.deviceName}")
        permissionCallback = callback
        
        // 创建 PendingIntent，使用 FLAG_UPDATE_CURRENT 确保回调能正确触发
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        Log.d(TAG, "发送 USB 权限请求...")
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * 打开 USB 设备连接
     */
    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        if (!usbManager.hasPermission(device)) {
            Log.e(TAG, "没有 USB 权限")
            return null
        }
        
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "无法打开 USB 设备")
            return null
        }
        
        currentDevice = device
        currentConnection = connection
        
        Log.i(TAG, "USB 设备已打开, fd=${connection.fileDescriptor}")
        return connection
    }

    /**
     * 获取当前连接的文件描述符
     */
    fun getFileDescriptor(): Int {
        return currentConnection?.fileDescriptor ?: -1
    }

    /**
     * 关闭 USB 连接
     */
    fun closeDevice() {
        currentConnection?.close()
        currentConnection = null
        currentDevice = null
        Log.i(TAG, "USB 设备已关闭")
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败", e)
        }
        closeDevice()
    }
}
