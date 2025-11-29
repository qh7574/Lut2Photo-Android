package cn.alittlecookie.lut2photo.lut2photo.core

import android.content.Context
import android.util.Log
import cn.alittlecookie.lut2photo.lut2photo.model.CameraEvent
import cn.alittlecookie.lut2photo.lut2photo.model.ConfigItem
import cn.alittlecookie.lut2photo.lut2photo.model.PhotoInfo
import java.io.File
import java.io.FileOutputStream

/**
 * libgphoto2 JNI 包装类
 * 提供相机连接、照片操作、事件监听、参数配置等功能
 */
class GPhoto2Manager private constructor() {

    companion object {
        private const val TAG = "GPhoto2Manager"
        
        @Volatile
        private var instance: GPhoto2Manager? = null

        fun getInstance(): GPhoto2Manager {
            return instance ?: synchronized(this) {
                instance ?: GPhoto2Manager().also { instance = it }
            }
        }

        init {
            try {
                // 按依赖顺序加载库
                // 1. 先加载基础依赖库
                System.loadLibrary("ltdl")
                Log.d(TAG, "libltdl 加载成功")
                
                System.loadLibrary("usb-1.0")
                Log.d(TAG, "libusb-1.0 加载成功")
                
                // 2. 加载 gphoto2 核心库
                System.loadLibrary("gphoto2_port")
                Log.d(TAG, "libgphoto2_port 加载成功")
                
                System.loadLibrary("gphoto2")
                Log.d(TAG, "libgphoto2 加载成功")
                
                // 3. 最后加载 JNI 库
                System.loadLibrary("gphoto2_jni")
                Log.i(TAG, "所有 gphoto2 库加载成功")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "gphoto2 库加载失败", e)
            }
        }

        // 错误码常量
        const val GP_OK = 0
        const val GP_ERROR = -1
        const val GP_ERROR_BAD_PARAMETERS = -2
        const val GP_ERROR_NO_MEMORY = -3
        const val GP_ERROR_LIBRARY = -4
        const val GP_ERROR_UNKNOWN_PORT = -5
        const val GP_ERROR_NOT_SUPPORTED = -6
        const val GP_ERROR_IO = -7
        const val GP_ERROR_FIXED_LIMIT_EXCEEDED = -8
        const val GP_ERROR_TIMEOUT = -10
        const val GP_ERROR_IO_SUPPORTED_SERIAL = -20
        const val GP_ERROR_IO_SUPPORTED_USB = -21
        const val GP_ERROR_UNKNOWN_MODEL = -22
        const val GP_ERROR_OUT_OF_SPACE = -23
        const val GP_ERROR_CAMERA_BUSY = -24
        const val GP_ERROR_PATH_NOT_ABSOLUTE = -25
        const val GP_ERROR_CANCEL = -26
        const val GP_ERROR_CAMERA_ERROR = -27
        const val GP_ERROR_OS_FAILURE = -28
        const val GP_ERROR_NO_SPACE = -29
    }

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isConnected = false
    
    // I/O 操作锁，确保所有相机操作互斥执行
    // 使用 ReentrantLock 支持 tryLock，避免 waitForEvent 长时间阻塞其他操作
    private val ioLock = java.util.concurrent.locks.ReentrantLock()
    
    // 配置设置队列
    private data class ConfigRequest(
        val name: String,
        val value: String,
        val callback: (Int) -> Unit
    )
    
    private val configQueue = java.util.concurrent.LinkedBlockingQueue<ConfigRequest>()
    private val configQueueExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    
    init {
        // 启动配置队列处理线程
        configQueueExecutor.execute {
            while (true) {
                try {
                    val request = configQueue.take() // 阻塞等待队列中的请求
                    Log.d(TAG, "从队列中取出配置请求: ${request.name} = ${request.value}")
                    
                    val result = setConfigSync(request.name, request.value)
                    
                    // 回调结果
                    request.callback(result)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "配置队列线程被中断")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "处理配置请求异常", e)
                }
            }
        }
    }

    // ==================== 初始化和释放 ====================

    /**
     * 初始化 libgphoto2
     * @return 错误码，0 表示成功
     */
    external fun initialize(): Int

    /**
     * 初始化 libgphoto2（带库路径）
     * @param camlibsPath 相机驱动路径
     * @param iolibsPath 端口驱动路径
     * @return 错误码，0 表示成功
     */
    external fun initializeWithPaths(camlibsPath: String, iolibsPath: String): Int

    /**
     * 释放 libgphoto2 资源
     */
    external fun release()

    /**
     * 初始化（带状态管理）
     * @param context Android Context，用于获取库路径
     * @param forceReinit 是否强制重新初始化（用于重新连接场景）
     */
    fun init(context: Context? = null, forceReinit: Boolean = false): Boolean {
        if (isInitialized && !forceReinit) {
            Log.w(TAG, "GPhoto2Manager 已经初始化，跳过")
            return true
        }
        
        // 如果强制重新初始化，先清理旧状态
        if (forceReinit && isInitialized) {
            Log.i(TAG, "强制重新初始化，先清理旧状态")
            isInitialized = false
            isConnected = false
        }

        val result = if (context != null) {
            // 从 assets 复制驱动文件到私有目录
            val camlibsPath = copyAssetsToPrivateDir(context, "gphoto2/camlibs", "camlibs")
            val iolibsPath = copyAssetsToPrivateDir(context, "gphoto2/iolibs", "iolibs")
            
            Log.i(TAG, "Camlibs path: $camlibsPath")
            Log.i(TAG, "Iolibs path: $iolibsPath")
            
            if (camlibsPath != null && iolibsPath != null) {
                initializeWithPaths(camlibsPath, iolibsPath)
            } else {
                Log.e(TAG, "复制驱动文件失败")
                GP_ERROR
            }
        } else {
            initialize()
        }
        
        if (result == GP_OK) {
            isInitialized = true
            Log.i(TAG, "GPhoto2Manager 初始化成功")
            return true
        } else {
            Log.e(TAG, "GPhoto2Manager 初始化失败: ${getErrorString(result)}")
            return false
        }
    }

    /**
     * 释放资源（带状态管理）
     */
    fun cleanup() {
        if (!isInitialized) {
            Log.d(TAG, "GPhoto2Manager 未初始化，跳过 cleanup")
            return
        }

        Log.i(TAG, "开始清理 GPhoto2Manager 资源...")
        
        // disconnectCamera 现在会释放所有资源（camera、context、usb fd）
        // 所以不需要再调用 release()
        if (isConnected) {
            disconnectCamera()
        } else {
            // 如果没有连接，仍然需要调用 release 来清理可能存在的资源
            release()
        }

        isInitialized = false
        isConnected = false
        Log.i(TAG, "GPhoto2Manager 已释放")
    }

    // ==================== 相机连接 ====================

    /**
     * 检测相机
     * @return 相机型号，如果未检测到返回空字符串
     */
    external fun detectCamera(): String

    /**
     * 连接相机
     * @return 错误码，0 表示成功
     */
    external fun connectCamera(): Int

    /**
     * 使用 USB 文件描述符连接相机
     * @param fd USB 文件描述符
     * @return 错误码，0 表示成功
     */
    external fun connectCameraWithFd(fd: Int): Int

    /**
     * 断开相机连接
     */
    external fun disconnectCamera()

    /**
     * 连接相机（带状态管理）
     */
    fun connect(): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "GPhoto2Manager 未初始化")
            return false
        }

        if (isConnected) {
            Log.w(TAG, "相机已经连接")
            return true
        }

        val result = connectCamera()
        if (result == GP_OK) {
            isConnected = true
            Log.i(TAG, "相机连接成功")
            return true
        } else {
            Log.e(TAG, "相机连接失败: ${getErrorString(result)}")
            return false
        }
    }

    /**
     * 使用 USB 文件描述符连接相机（带状态管理）
     */
    fun connectWithFd(fd: Int): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "GPhoto2Manager 未初始化")
            return false
        }

        if (isConnected) {
            Log.w(TAG, "相机已经连接")
            return true
        }

        Log.i(TAG, "使用 USB fd=$fd 连接相机")
        val result = connectCameraWithFd(fd)
        if (result == GP_OK) {
            isConnected = true
            Log.i(TAG, "相机连接成功 (fd=$fd)")
            return true
        } else {
            Log.e(TAG, "相机连接失败: ${getErrorString(result)}")
            return false
        }
    }

    /**
     * 断开相机（带状态管理）
     * 注意：disconnectCamera 会释放所有资源，包括 camera 和 context
     * 因此断开后需要重新调用 init() 才能再次连接
     */
    fun disconnect() {
        if (!isConnected) {
            Log.d(TAG, "相机未连接，跳过 disconnect")
            return
        }

        Log.i(TAG, "断开相机连接...")
        disconnectCamera()
        isConnected = false
        isInitialized = false  // disconnectCamera 会释放所有资源，需要重新初始化
        Log.i(TAG, "相机已断开，需要重新初始化才能再次连接")
    }

    /**
     * 检查相机是否已连接
     */
    fun isCameraConnected(): Boolean {
        return isConnected
    }

    // ==================== 照片操作 (Native) ====================

    private external fun nativeListPhotos(): Array<PhotoInfo>
    private external fun nativeGetThumbnail(photoPath: String): ByteArray?
    private external fun nativeDownloadPhoto(photoPath: String, destPath: String): Int
    private external fun nativeGetFileSize(photoPath: String): Long
    private external fun nativeDownloadPhotoChunk(photoPath: String, destPath: String, offset: Long, chunkSize: Int): Int
    private external fun nativeDeletePhoto(photoPath: String): Int

    // ==================== 事件监听 (Native) ====================

    private external fun nativeWaitForEvent(timeout: Int): CameraEvent

    // ==================== 相机配置 (Native) ====================

    private external fun nativeListConfig(): Array<ConfigItem>
    private external fun nativeGetConfig(name: String): ConfigItem?
    private external fun nativeSetConfig(name: String, value: String): Int

    // ==================== 照片操作 (带锁) ====================

    /**
     * 获取相机中的照片列表（线程安全）
     * @return 照片信息数组
     */
    fun listPhotos(): Array<PhotoInfo> {
        ioLock.lock()
        try {
            return nativeListPhotos()
        } finally {
            ioLock.unlock()
        }
    }

    /**
     * 获取照片缩略图（线程安全）
     * @param photoPath 照片路径
     * @return 缩略图数据（JPEG 格式）
     */
    fun getThumbnail(photoPath: String): ByteArray? {
        ioLock.lock()
        try {
            return nativeGetThumbnail(photoPath)
        } finally {
            ioLock.unlock()
        }
    }

    /**
     * 获取文件大小（线程安全）
     * @param photoPath 照片路径
     * @return 文件大小（字节），失败返回 -1
     */
    fun getFileSize(photoPath: String): Long {
        ioLock.lock()
        try {
            return nativeGetFileSize(photoPath)
        } finally {
            ioLock.unlock()
        }
    }

    /**
     * 下载照片（线程安全）
     * @param photoPath 照片在相机中的路径
     * @param destPath 目标保存路径
     * @return 错误码，0 表示成功
     */
    fun downloadPhoto(photoPath: String, destPath: String): Int {
        ioLock.lock()
        try {
            return nativeDownloadPhoto(photoPath, destPath)
        } finally {
            ioLock.unlock()
        }
    }
    
    /**
     * 分块下载照片（线程安全）
     * @param photoPath 照片在相机中的路径
     * @param destPath 目标保存路径
     * @param offset 起始偏移量
     * @param chunkSize 块大小
     * @return 错误码，0 表示成功
     */
    fun downloadPhotoChunk(photoPath: String, destPath: String, offset: Long, chunkSize: Int): Int {
        ioLock.lock()
        try {
            return nativeDownloadPhotoChunk(photoPath, destPath, offset, chunkSize)
        } finally {
            ioLock.unlock()
        }
    }
    
    /**
     * 分块下载照片（带进度回调）
     * @param photoPath 照片在相机中的路径
     * @param destPath 目标保存路径
     * @param chunkSize 块大小（默认 1MB）
     * @param progressCallback 进度回调 (已下载字节数, 总字节数)
     * @return 错误码，0 表示成功
     */
    fun downloadPhotoWithProgress(
        photoPath: String, 
        destPath: String, 
        chunkSize: Int = 1024 * 1024,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): Int {
        // 先获取文件大小
        val fileSize = getFileSize(photoPath)
        if (fileSize <= 0) {
            Log.e(TAG, "无法获取文件大小")
            return GP_ERROR
        }
        
        Log.i(TAG, "开始分块下载: $photoPath, 大小: $fileSize 字节")
        
        var offset = 0L
        while (offset < fileSize) {
            val currentChunkSize = minOf(chunkSize.toLong(), fileSize - offset).toInt()
            
            val ret = downloadPhotoChunk(photoPath, destPath, offset, currentChunkSize)
            if (ret != GP_OK) {
                Log.e(TAG, "下载块失败: offset=$offset, size=$currentChunkSize")
                return ret
            }
            
            offset += currentChunkSize
            progressCallback?.invoke(offset, fileSize)
            
            Log.d(TAG, "下载进度: $offset / $fileSize (${offset * 100 / fileSize}%)")
        }
        
        Log.i(TAG, "分块下载完成")
        return GP_OK
    }

    /**
     * 删除照片（线程安全）
     * @param photoPath 照片路径
     * @return 错误码，0 表示成功
     */
    fun deletePhoto(photoPath: String): Int {
        ioLock.lock()
        try {
            return nativeDeletePhoto(photoPath)
        } finally {
            ioLock.unlock()
        }
    }

    // ==================== 事件监听 (带锁) ====================

    /**
     * 等待相机事件（线程安全）
     * 使用 tryLock 避免长时间阻塞其他操作
     * @param timeout 超时时间（毫秒）
     * @return 相机事件
     */
    fun waitForEvent(timeout: Int): CameraEvent {
        // 尝试获取锁，最多等待 100ms
        val acquired = ioLock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!acquired) {
            // 获取锁失败，返回超时事件，让其他操作先执行
            return CameraEvent(CameraEvent.EVENT_TIMEOUT, "")
        }
        try {
            return nativeWaitForEvent(timeout)
        } finally {
            ioLock.unlock()
        }
    }

    // ==================== 相机配置 (带锁) ====================

    /**
     * 获取所有可配置项（线程安全）
     * @return 配置项数组
     */
    fun listConfig(): Array<ConfigItem> {
        ioLock.lock()
        try {
            return nativeListConfig()
        } finally {
            ioLock.unlock()
        }
    }

    /**
     * 获取指定配置项（线程安全）
     * @param name 配置项名称
     * @return 配置项，如果不存在返回 null
     */
    fun getConfig(name: String): ConfigItem? {
        ioLock.lock()
        try {
            return nativeGetConfig(name)
        } finally {
            ioLock.unlock()
        }
    }

    /**
     * 设置配置项（同步版本，线程安全）
     * @param name 配置项名称
     * @param value 配置值
     * @return 错误码，0 表示成功
     */
    private fun setConfigSync(name: String, value: String): Int {
        ioLock.lock()
        try {
            return nativeSetConfig(name, value)
        } finally {
            ioLock.unlock()
        }
    }
    
    /**
     * 设置配置项（异步队列版本）
     * 将请求放入队列，避免多个请求同时竞争锁
     * @param name 配置项名称
     * @param value 配置值
     * @param callback 完成回调，参数为错误码
     */
    fun setConfigAsync(name: String, value: String, callback: (Int) -> Unit) {
        Log.d(TAG, "将配置请求加入队列: $name = $value")
        configQueue.offer(ConfigRequest(name, value, callback))
    }
    
    /**
     * 设置配置项（兼容旧版本的同步接口）
     * @param name 配置项名称
     * @param value 配置值
     * @return 错误码，0 表示成功
     */
    fun setConfig(name: String, value: String): Int {
        return setConfigSync(name, value)
    }

    // ==================== 工具方法 ====================

    /**
     * 将错误码转换为可读字符串
     */
    fun getErrorString(errorCode: Int): String {
        return when (errorCode) {
            GP_OK -> "成功"
            GP_ERROR -> "一般错误"
            GP_ERROR_BAD_PARAMETERS -> "参数错误"
            GP_ERROR_NO_MEMORY -> "内存不足"
            GP_ERROR_LIBRARY -> "库错误"
            GP_ERROR_UNKNOWN_PORT -> "未知端口"
            GP_ERROR_NOT_SUPPORTED -> "不支持"
            GP_ERROR_IO -> "I/O 错误"
            GP_ERROR_FIXED_LIMIT_EXCEEDED -> "超出固定限制"
            GP_ERROR_TIMEOUT -> "超时"
            GP_ERROR_IO_SUPPORTED_SERIAL -> "不支持串口"
            GP_ERROR_IO_SUPPORTED_USB -> "不支持 USB"
            GP_ERROR_UNKNOWN_MODEL -> "未知相机型号"
            GP_ERROR_OUT_OF_SPACE -> "空间不足"
            GP_ERROR_CAMERA_BUSY -> "相机忙"
            GP_ERROR_PATH_NOT_ABSOLUTE -> "路径不是绝对路径"
            GP_ERROR_CANCEL -> "已取消"
            GP_ERROR_CAMERA_ERROR -> "相机错误"
            GP_ERROR_OS_FAILURE -> "操作系统错误"
            GP_ERROR_NO_SPACE -> "没有空间"
            else -> "未知错误 ($errorCode)"
        }
    }

    /**
     * 从 assets 复制文件到私有目录
     * @param context Android Context
     * @param assetPath assets 中的路径
     * @param targetDirName 目标目录名称
     * @return 目标目录的绝对路径，失败返回 null
     */
    private fun copyAssetsToPrivateDir(context: Context, assetPath: String, targetDirName: String): String? {
        try {
            val targetDir = File(context.filesDir, "gphoto2/$targetDirName")
            
            // 如果目录已存在且有文件，直接返回
            if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
                Log.d(TAG, "驱动目录已存在: ${targetDir.absolutePath}")
                return targetDir.absolutePath
            }
            
            // 创建目标目录
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            // 列出 assets 中的文件
            val assetManager = context.assets
            val files = assetManager.list(assetPath) ?: return null
            
            Log.d(TAG, "复制 $assetPath 中的 ${files.size} 个文件到 ${targetDir.absolutePath}")
            
            for (fileName in files) {
                val inputStream = assetManager.open("$assetPath/$fileName")
                val outputFile = File(targetDir, fileName)
                
                FileOutputStream(outputFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                inputStream.close()
                
                // 设置可执行权限
                outputFile.setExecutable(true, false)
                
                Log.d(TAG, "已复制: $fileName")
            }
            
            return targetDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "复制 assets 失败: ${e.message}", e)
            return null
        }
    }
}
