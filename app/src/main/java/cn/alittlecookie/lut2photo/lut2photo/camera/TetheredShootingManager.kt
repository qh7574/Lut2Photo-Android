package cn.alittlecookie.lut2photo.lut2photo.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import cn.alittlecookie.lut2photo.lut2photo.lut.LutProcessor

/**
 * 联机拍摄管理器
 * 负责管理联机拍摄流程，包括照片传输、自动LUT处理等功能
 */
class TetheredShootingManager(
    private val context: Context,
    private val cameraDevice: ICameraDevice?,
    private val lutProcessor: LutProcessor
) {
    companion object {
        private const val TAG = "TetheredShootingManager"
        private const val PHOTO_DIRECTORY = "TetheredPhotos"
        private const val PROCESSED_DIRECTORY = "ProcessedPhotos"
    }

    // 联机拍摄状态
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    // 拍摄进度
    private val _shootingProgress = MutableStateFlow<ShootingProgress?>(null)
    val shootingProgress: StateFlow<ShootingProgress?> = _shootingProgress.asStateFlow()

    // 最近拍摄的照片
    private val _lastCapturedPhoto = MutableStateFlow<CapturedPhoto?>(null)
    val lastCapturedPhoto: StateFlow<CapturedPhoto?> = _lastCapturedPhoto.asStateFlow()

    // 错误信息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 自动LUT处理设置
    private var autoLutProcessing = true
    private var selectedLutFile: File? = null

    /**
     * 拍摄进度数据类
     */
    data class ShootingProgress(
        val stage: ShootingStage,
        val progress: Float, // 0.0 - 1.0
        val message: String
    )

    /**
     * 拍摄阶段枚举
     */
    enum class ShootingStage {
        CAPTURING,      // 拍摄中
        DOWNLOADING,    // 下载中
        PROCESSING,     // 处理中
        COMPLETED,      // 完成
        FAILED          // 失败
    }

    /**
     * 拍摄照片数据类
     */
    data class CapturedPhoto(
        val originalFile: File,
        val processedFile: File?,
        val thumbnail: Bitmap?,
        val captureTime: Date,
        val cameraSettings: ICameraDevice.CameraParameters?
    )

    /**
     * 启动联机拍摄模式
     */
    fun startTetheredShooting() {
        if (_isActive.value) {
            Log.w(TAG, "联机拍摄已经激活")
            return
        }

        scope.launch {
            try {
                _isActive.value = true
                _errorMessage.value = null
                Log.i(TAG, "联机拍摄模式已启动")
            } catch (e: Exception) {
                Log.e(TAG, "启动联机拍摄失败", e)
                _errorMessage.value = "启动联机拍摄失败: ${e.message}"
                _isActive.value = false
            }
        }
    }

    /**
     * 停止联机拍摄模式
     */
    fun stopTetheredShooting() {
        scope.launch {
            try {
                _isActive.value = false
                _shootingProgress.value = null
                Log.i(TAG, "联机拍摄模式已停止")
            } catch (e: Exception) {
                Log.e(TAG, "停止联机拍摄失败", e)
                _errorMessage.value = "停止联机拍摄失败: ${e.message}"
            }
        }
    }

    /**
     * 执行联机拍摄
     */
    fun capturePhoto() {
        if (!_isActive.value) {
            _errorMessage.value = "联机拍摄模式未激活"
            return
        }

        if (cameraDevice == null || !cameraDevice.isConnected()) {
            _errorMessage.value = "相机未连接"
            return
        }

        scope.launch {
            try {
                // 阶段1: 拍摄
                _shootingProgress.value = ShootingProgress(
                    ShootingStage.CAPTURING,
                    0.1f,
                    "正在拍摄..."
                )

                val result = cameraDevice.captureImage()
                if (!result.isSuccess) {
                    throw Exception(result.errorMessage ?: "拍摄失败")
                }

                // 阶段2: 下载照片
                _shootingProgress.value = ShootingProgress(
                    ShootingStage.DOWNLOADING,
                    0.3f,
                    "正在下载照片..."
                )

                val photoData = downloadLatestPhoto()
                if (photoData == null) {
                    throw Exception("下载照片失败")
                }

                // 阶段3: 保存原始照片
                _shootingProgress.value = ShootingProgress(
                    ShootingStage.DOWNLOADING,
                    0.6f,
                    "正在保存照片..."
                )

                val originalFile = saveOriginalPhoto(photoData)
                val thumbnail = createThumbnail(photoData)
                val cameraSettings = cameraDevice.getCurrentParameters()

                // 阶段4: LUT处理（如果启用）
                var processedFile: File? = null
                if (autoLutProcessing && selectedLutFile != null) {
                    _shootingProgress.value = ShootingProgress(
                        ShootingStage.PROCESSING,
                        0.8f,
                        "正在应用LUT处理..."
                    )

                    processedFile = applyLutProcessing(originalFile)
                }

                // 阶段5: 完成
                _shootingProgress.value = ShootingProgress(
                    ShootingStage.COMPLETED,
                    1.0f,
                    "拍摄完成"
                )

                val capturedPhoto = CapturedPhoto(
                    originalFile = originalFile,
                    processedFile = processedFile,
                    thumbnail = thumbnail,
                    captureTime = Date(),
                    cameraSettings = cameraSettings
                )

                _lastCapturedPhoto.value = capturedPhoto
                Log.i(TAG, "联机拍摄完成: ${originalFile.name}")

                // 延迟清除进度
                delay(2000)
                _shootingProgress.value = null

            } catch (e: Exception) {
                Log.e(TAG, "联机拍摄失败", e)
                _errorMessage.value = "联机拍摄失败: ${e.message}"
                _shootingProgress.value = ShootingProgress(
                    ShootingStage.FAILED,
                    0.0f,
                    "拍摄失败: ${e.message}"
                )

                // 延迟清除进度
                delay(3000)
                _shootingProgress.value = null
            }
        }
    }

    /**
     * 下载最新照片
     */
    private suspend fun downloadLatestPhoto(): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                // 这里应该调用相机设备的下载方法
                // 暂时返回null，实际实现需要根据libgphoto2的API
                cameraDevice?.downloadLatestFile()
            } catch (e: Exception) {
                Log.e(TAG, "下载照片失败", e)
                null
            }
        }
    }

    /**
     * 保存原始照片
     */
    private suspend fun saveOriginalPhoto(photoData: ByteArray): File {
        return withContext(Dispatchers.IO) {
            val photoDir = File(context.getExternalFilesDir(null), PHOTO_DIRECTORY)
            if (!photoDir.exists()) {
                photoDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "IMG_${timestamp}.jpg"
            val file = File(photoDir, fileName)

            FileOutputStream(file).use { output ->
                output.write(photoData)
            }

            Log.i(TAG, "原始照片已保存: ${file.absolutePath}")
            file
        }
    }

    /**
     * 创建缩略图
     */
    private suspend fun createThumbnail(photoData: ByteArray): Bitmap? {
        return withContext(Dispatchers.Default) {
            try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4 // 缩小到1/4
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(photoData, 0, photoData.size, options)
            } catch (e: Exception) {
                Log.e(TAG, "创建缩略图失败", e)
                null
            }
        }
    }

    /**
     * 应用LUT处理
     */
    private suspend fun applyLutProcessing(originalFile: File): File? {
        return withContext(Dispatchers.IO) {
            try {
                if (selectedLutFile == null) {
                    Log.w(TAG, "未选择LUT文件")
                    return@withContext null
                }

                val processedDir = File(context.getExternalFilesDir(null), PROCESSED_DIRECTORY)
                if (!processedDir.exists()) {
                    processedDir.mkdirs()
                }

                val processedFileName = "processed_${originalFile.nameWithoutExtension}.jpg"
                val processedFile = File(processedDir, processedFileName)

                // 使用LutProcessor处理照片
                val success = lutProcessor.processImage(
                    inputPath = originalFile.absolutePath,
                    outputPath = processedFile.absolutePath,
                    lutPath = selectedLutFile!!.absolutePath
                )

                if (success) {
                    Log.i(TAG, "LUT处理完成: ${processedFile.absolutePath}")
                    processedFile
                } else {
                    Log.e(TAG, "LUT处理失败")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "LUT处理异常", e)
                null
            }
        }
    }

    /**
     * 设置自动LUT处理
     */
    fun setAutoLutProcessing(enabled: Boolean, lutFile: File? = null) {
        autoLutProcessing = enabled
        selectedLutFile = lutFile
        Log.i(TAG, "自动LUT处理: $enabled, LUT文件: ${lutFile?.name}")
    }

    /**
     * 获取拍摄历史
     */
    fun getShootingHistory(): List<File> {
        val photoDir = File(context.getExternalFilesDir(null), PHOTO_DIRECTORY)
        return if (photoDir.exists()) {
            photoDir.listFiles()?.filter {
                it.isFile && it.extension.lowercase() in listOf(
                    "jpg",
                    "jpeg",
                    "raw",
                    "cr2",
                    "nef"
                )
            }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * 清理错误信息
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 释放资源
     */
    fun release() {
        scope.cancel()
        Log.i(TAG, "TetheredShootingManager已释放")
    }
}