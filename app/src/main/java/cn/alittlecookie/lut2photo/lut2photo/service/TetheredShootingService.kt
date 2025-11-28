package cn.alittlecookie.lut2photo.lut2photo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.alittlecookie.lut2photo.lut2photo.MainActivity
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.core.GPhoto2Manager
import cn.alittlecookie.lut2photo.lut2photo.model.CameraEvent
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import cn.alittlecookie.lut2photo.lut2photo.utils.UsbPermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 联机拍摄服务
 * 负责监听相机事件，自动下载照片到输入文件夹
 */
class TetheredShootingService : Service() {

    companion object {
        private const val TAG = "TetheredShootingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tethered_shooting_channel"
        
        // 广播 Action
        const val ACTION_CAMERA_CONNECTED = "cn.alittlecookie.lut2photo.CAMERA_CONNECTED"
        const val ACTION_CAMERA_DISCONNECTED = "cn.alittlecookie.lut2photo.CAMERA_DISCONNECTED"
        const val ACTION_PHOTO_DOWNLOADED = "cn.alittlecookie.lut2photo.PHOTO_DOWNLOADED"
        const val ACTION_PHOTO_ADDED = "cn.alittlecookie.lut2photo.PHOTO_ADDED"
        const val ACTION_CONNECTION_ERROR = "cn.alittlecookie.lut2photo.CONNECTION_ERROR"
        
        // Extra 键
        const val EXTRA_PHOTO_PATH = "photo_path"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }

    private val binder = LocalBinder()
    private val gphoto2Manager = GPhoto2Manager.getInstance()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var usbPermissionManager: UsbPermissionManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var eventMonitorJob: Job? = null
    
    @Volatile
    private var isMonitoring = false
    
    @Volatile
    private var downloadedCount = 0

    inner class LocalBinder : Binder() {
        fun getService(): TetheredShootingService = this@TetheredShootingService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建")
        preferencesManager = PreferencesManager(this)
        usbPermissionManager = UsbPermissionManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "服务启动")
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("正在连接相机..."))
        
        // 先检查 USB 权限，再初始化
        checkUsbPermissionAndConnect()
        
        return START_NOT_STICKY
    }
    
    /**
     * 检查 USB 权限并连接相机
     */
    private fun checkUsbPermissionAndConnect() {
        val device = usbPermissionManager.findPtpCamera()
        
        if (device == null) {
            Log.e(TAG, "未找到相机设备")
            sendBroadcast(Intent(ACTION_CONNECTION_ERROR).apply {
                putExtra(EXTRA_ERROR_MESSAGE, "未找到相机设备，请检查 USB 连接")
            })
            stopSelf()
            return
        }
        
        Log.i(TAG, "找到相机设备: ${device.deviceName}")
        
        if (usbPermissionManager.hasPermission(device)) {
            Log.i(TAG, "已有 USB 权限，直接连接")
            connectWithUsbDevice(device)
        } else {
            Log.i(TAG, "请求 USB 权限")
            updateNotification("请授权 USB 访问...")
            
            usbPermissionManager.requestPermission(device) { granted, dev ->
                if (granted && dev != null) {
                    Log.i(TAG, "USB 权限已授予")
                    connectWithUsbDevice(dev)
                } else {
                    Log.e(TAG, "USB 权限被拒绝")
                    sendBroadcast(Intent(ACTION_CONNECTION_ERROR).apply {
                        putExtra(EXTRA_ERROR_MESSAGE, "USB 权限被拒绝")
                    })
                    stopSelf()
                }
            }
        }
    }
    
    /**
     * 使用 USB 设备连接相机
     */
    private fun connectWithUsbDevice(device: UsbDevice) {
        // 打开 USB 设备
        val connection = usbPermissionManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "无法打开 USB 设备")
            sendBroadcast(Intent(ACTION_CONNECTION_ERROR).apply {
                putExtra(EXTRA_ERROR_MESSAGE, "无法打开 USB 设备")
            })
            stopSelf()
            return
        }
        
        val fd = connection.fileDescriptor
        Log.i(TAG, "USB 设备已打开, fd=$fd")
        
        // 在 IO 线程中初始化并连接相机
        serviceScope.launch {
            if (initializeAndConnect(fd)) {
                startEventMonitoring()
            } else {
                // 连接失败，发送广播
                mainHandler.post {
                    sendBroadcast(Intent(ACTION_CONNECTION_ERROR).apply {
                        putExtra(EXTRA_ERROR_MESSAGE, "相机连接失败")
                    })
                    stopSelf()
                }
            }
        }
    }

    private fun onStartCommandOld(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "服务启动")
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("正在连接相机..."))
        
        // 初始化并连接相机
        serviceScope.launch {
            if (initializeAndConnect()) {
                startEventMonitoring()
            } else {
                // 连接失败，发送广播
                sendBroadcast(Intent(ACTION_CONNECTION_ERROR).apply {
                    putExtra(EXTRA_ERROR_MESSAGE, "相机连接失败")
                })
                stopSelf()
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "服务销毁")
        
        // 先停止事件监听
        stopEventMonitoring()
        
        // 等待事件监听协程完全停止
        try {
            eventMonitorJob?.let { job ->
                if (job.isActive) {
                    Log.i(TAG, "等待事件监听协程停止...")
                    kotlinx.coroutines.runBlocking {
                        job.join()
                    }
                    Log.i(TAG, "事件监听协程已停止")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "等待协程停止时发生异常", e)
        }
        
        // 安全地断开连接和释放资源
        try {
            if (gphoto2Manager.isCameraConnected()) {
                gphoto2Manager.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "断开连接时发生异常", e)
        }
        
        try {
            gphoto2Manager.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "清理资源时发生异常", e)
        }
        
        try {
            usbPermissionManager.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放 USB 权限管理器时发生异常", e)
        }
        
        serviceScope.cancel()
    }

    /**
     * 初始化并连接相机
     * @param usbFd USB 文件描述符，-1 表示不使用
     */
    private fun initializeAndConnect(usbFd: Int = -1): Boolean {
        Log.i(TAG, "初始化 libgphoto2... (usbFd=$usbFd)")
        
        // 传递 Context 以便获取正确的库路径
        if (!gphoto2Manager.init(this)) {
            Log.e(TAG, "libgphoto2 初始化失败")
            return false
        }
        
        Log.i(TAG, "连接相机...")
        
        // 如果有 USB 文件描述符，使用它连接
        val connectResult = if (usbFd >= 0) {
            gphoto2Manager.connectWithFd(usbFd)
        } else {
            gphoto2Manager.connect()
        }
        
        if (!connectResult) {
            Log.e(TAG, "相机连接失败")
            return false
        }
        
        // 检测相机型号
        val cameraModel = gphoto2Manager.detectCamera()
        Log.i(TAG, "相机已连接: $cameraModel")
        
        // 更新通知
        mainHandler.post {
            updateNotification("相机已连接: $cameraModel，正在初始化...")
        }
        
        // 更新通知
        mainHandler.post {
            updateNotification("相机已连接: $cameraModel")
        }
        
        // 立即发送连接成功广播，让用户可以打开设置界面
        // Panasonic 相机的文件系统需要在收到 FOLDER_ADDED 事件后才完全可用
        // 但我们先让 UI 可以响应，用户可以尝试刷新或拍摄照片
        mainHandler.post {
            Log.i(TAG, "发送 ACTION_CAMERA_CONNECTED 广播")
            sendBroadcast(Intent(ACTION_CAMERA_CONNECTED).apply {
                setPackage(packageName)
            })
        }
        
        return true
    }

    /**
     * 开始事件监听
     */
    private fun startEventMonitoring() {
        if (isMonitoring) {
            Log.w(TAG, "事件监听已在运行")
            return
        }
        
        isMonitoring = true
        Log.i(TAG, "开始监听相机事件...")
        
        eventMonitorJob = serviceScope.launch {
            var consecutiveErrors = 0
            val maxRetries = 3
            
            while (isActive && isMonitoring) {
                try {
                    // 先检查相机是否仍然连接
                    if (!gphoto2Manager.isCameraConnected()) {
                        Log.e(TAG, "检测到相机已断开连接")
                        handleConnectionLost()
                        break
                    }
                    
                    val event = gphoto2Manager.waitForEvent(1000)
                    
                    when (event.type) {
                        CameraEvent.EVENT_ERROR -> {
                            // JNI 层返回的错误事件，通常是 USB 断开
                            Log.e(TAG, "收到错误事件: ${event.data}")
                            handleConnectionLost()
                            break
                        }
                        CameraEvent.EVENT_FILE_ADDED -> {
                            consecutiveErrors = 0 // 重置错误计数
                            Log.i(TAG, "检测到新照片: ${event.data}")
                            handleFileAdded(event.data)
                        }
                        CameraEvent.EVENT_FOLDER_ADDED -> {
                            consecutiveErrors = 0 // 重置错误计数
                            Log.i(TAG, "检测到文件夹添加: ${event.data}")
                            // Panasonic 相机在挂载存储卡时会触发此事件
                            // 此时文件系统才真正可用，发送连接成功广播
                            mainHandler.post {
                                Log.i(TAG, "存储卡已挂载，发送 ACTION_CAMERA_CONNECTED 广播")
                                updateNotification("相机已连接，存储卡已就绪")
                                sendBroadcast(Intent(ACTION_CAMERA_CONNECTED).apply {
                                    setPackage(packageName)
                                })
                            }
                        }
                        CameraEvent.EVENT_CAPTURE_COMPLETE -> {
                            consecutiveErrors = 0 // 重置错误计数
                            Log.i(TAG, "拍摄完成")
                        }
                        CameraEvent.EVENT_TIMEOUT -> {
                            consecutiveErrors = 0 // 超时是正常的，重置错误计数
                        }
                        CameraEvent.EVENT_UNKNOWN -> {
                            // GP_EVENT_UNKNOWN 表示相机内部状态更新，是正常的
                            consecutiveErrors = 0
                            Log.d(TAG, "其他事件: ${event.type}")
                        }
                        else -> {
                            Log.d(TAG, "其他事件: ${event.type}")
                        }
                    }
                } catch (e: Exception) {
                    val errorMessage = e.message ?: ""
                    
                    // 检查是否是 USB 设备断开错误
                    if (errorMessage.contains("Could not find the requested device", ignoreCase = true) ||
                        errorMessage.contains("USB device", ignoreCase = true) ||
                        errorMessage.contains("I/O error", ignoreCase = true)) {
                        Log.e(TAG, "检测到 USB 设备断开: $errorMessage")
                        handleConnectionLost()
                        break
                    }
                    
                    consecutiveErrors++
                    Log.e(TAG, "事件监听异常 (连续错误: $consecutiveErrors)", e)
                    
                    if (consecutiveErrors >= maxRetries) {
                        Log.e(TAG, "连续 $maxRetries 次事件监听异常，断开连接")
                        handleConnectionLost()
                        break
                    }
                    
                    // 等待一段时间再重试
                    kotlinx.coroutines.delay(500)
                }
            }
        }
    }
    
    /**
     * 处理连接丢失
     */
    private fun handleConnectionLost() {
        Log.i(TAG, "处理连接丢失，清理资源...")
        
        // 停止监听
        isMonitoring = false
        
        // 只断开连接，不调用 cleanup（会在 onDestroy 中调用）
        // 这样可以避免重复释放资源导致崩溃
        try {
            gphoto2Manager.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "断开连接时发生异常", e)
        }
        
        // 在主线程发送广播和停止服务
        mainHandler.post {
            // 发送断开连接广播
            sendBroadcast(Intent(ACTION_CAMERA_DISCONNECTED).apply {
                setPackage(packageName)
            })
            
            // 更新通知
            updateNotification("相机连接已断开")
            
            // 停止服务
            stopSelf()
        }
    }

    /**
     * 停止事件监听
     */
    private fun stopEventMonitoring() {
        isMonitoring = false
        eventMonitorJob?.cancel()
        eventMonitorJob = null
        Log.i(TAG, "事件监听已停止")
    }
    
    /**
     * 暂停事件监听（供外部调用，如 BottomSheet 打开时）
     */
    fun pauseEventMonitoring() {
        if (isMonitoring) {
            Log.i(TAG, "暂停事件监听")
            stopEventMonitoring()
        }
    }
    
    /**
     * 恢复事件监听（供外部调用，如 BottomSheet 关闭时）
     */
    fun resumeEventMonitoring() {
        if (!isMonitoring && gphoto2Manager.isCameraConnected()) {
            Log.i(TAG, "恢复事件监听")
            startEventMonitoring()
        }
    }

    /**
     * 处理文件添加事件
     */
    private fun handleFileAdded(photoPath: String) {
        if (photoPath.isEmpty()) {
            return
        }
        
        // 只处理 JPG/JPEG 文件，忽略 RAW 文件
        if (!isJpegFile(photoPath)) {
            Log.d(TAG, "跳过非 JPEG 文件: $photoPath")
            return
        }
        
        // 发送照片添加广播
        sendBroadcast(Intent(ACTION_PHOTO_ADDED).apply {
            putExtra(EXTRA_PHOTO_PATH, photoPath)
        })
        
        // 获取输入文件夹
        val inputFolderUri = preferencesManager.homeInputFolder
        if (inputFolderUri.isEmpty()) {
            Log.w(TAG, "输入文件夹未设置，跳过自动下载")
            return
        }
        
        // 下载照片
        serviceScope.launch {
            downloadPhoto(photoPath, inputFolderUri)
        }
    }
    
    /**
     * 检查文件是否为 JPEG 格式
     */
    private fun isJpegFile(filePath: String): Boolean {
        val extension = filePath.substringAfterLast('.', "").lowercase()
        return extension in listOf("jpg", "jpeg")
    }

    /**
     * 下载照片到输入文件夹
     */
    private fun downloadPhoto(photoPath: String, inputFolderUri: String) {
        try {
            // 提取文件名
            val fileName = photoPath.substringAfterLast('/')
            
            // 先下载到临时文件
            val tempFile = File(cacheDir, fileName)
            
            Log.i(TAG, "下载照片: $photoPath -> ${tempFile.absolutePath}")
            
            // 更新通知
            updateNotification("正在下载: $fileName")
            
            // 下载照片到临时文件
            val result = gphoto2Manager.downloadPhoto(photoPath, tempFile.absolutePath)
            
            if (result == GPhoto2Manager.GP_OK && tempFile.exists()) {
                // 使用 SAF API 复制到目标文件夹
                try {
                    val destFolderUri = android.net.Uri.parse(inputFolderUri)
                    val destFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, destFolderUri)
                    
                    if (destFolder != null && destFolder.canWrite()) {
                        val destFile = destFolder.createFile("image/jpeg", fileName)
                        if (destFile != null) {
                            contentResolver.openOutputStream(destFile.uri)?.use { outputStream ->
                                tempFile.inputStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            
                            downloadedCount++
                            Log.i(TAG, "照片下载成功: ${destFile.uri}")
                            
                            // 更新通知
                            updateNotification("已下载 $downloadedCount 张照片")
                            
                            // 发送下载完成广播
                            sendBroadcast(Intent(ACTION_PHOTO_DOWNLOADED).apply {
                                putExtra(EXTRA_PHOTO_PATH, destFile.uri.toString())
                            })
                        } else {
                            Log.e(TAG, "无法创建目标文件: $fileName")
                        }
                    } else {
                        Log.e(TAG, "无法写入目标文件夹")
                    }
                } finally {
                    // 删除临时文件
                    tempFile.delete()
                }
            } else {
                Log.e(TAG, "照片下载失败: ${gphoto2Manager.getErrorString(result)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载照片异常", e)
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "联机拍摄服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示相机连接状态和照片下载进度"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 创建通知
     */
    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("联机拍摄")
            .setContentText(content)
            .setSmallIcon(R.drawable.outline_photo_camera_24)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * 更新通知
     */
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 获取下载计数
     */
    fun getDownloadedCount(): Int = downloadedCount

    /**
     * 重置下载计数
     */
    fun resetDownloadedCount() {
        downloadedCount = 0
    }
}
