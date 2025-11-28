package cn.alittlecookie.lut2photo.lut2photo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * USB 设备附加接收器
 */
class UsbDeviceReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UsbDeviceReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device != null) {
                    Log.i(TAG, "USB 设备已连接: ${device.deviceName}")
                    Log.i(TAG, "设备信息: VID=${device.vendorId}, PID=${device.productId}")
                    
                    // 检查是否是相机设备 (PTP/MTP)
                    if (isCameraDevice(device)) {
                        Log.i(TAG, "检测到相机设备")
                        // 可以在这里发送广播通知应用
                        val cameraIntent = Intent("cn.alittlecookie.lut2photo.CAMERA_ATTACHED")
                        context.sendBroadcast(cameraIntent)
                    }
                }
            }
        }
    }

    private fun isCameraDevice(device: UsbDevice): Boolean {
        // 检查设备类别
        // PTP: Class 6, Subclass 1, Protocol 1
        // MTP: Class 6, Subclass 1, Protocol 0
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == 6 && intf.interfaceSubclass == 1) {
                return true
            }
        }
        return false
    }
}
