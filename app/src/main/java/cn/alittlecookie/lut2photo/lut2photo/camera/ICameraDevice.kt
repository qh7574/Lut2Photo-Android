package cn.alittlecookie.lut2photo.lut2photo.camera

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

/**
 * 相机设备接口
 * 定义相机控制的核心功能
 */
interface ICameraDevice {

    /**
     * 相机连接状态
     */
    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /**
     * 相机参数
     */
    data class CameraParameters(
        val aperture: Float? = null,
        val shutterSpeed: String? = null,
        val iso: Int? = null,
        val whiteBalance: String? = null,
        val focusMode: String? = null,
        val imageFormat: String? = null,
        val imageQuality: String? = null
    )

    /**
     * 相机能力信息
     */
    data class CameraCapabilities(
        val supportedApertures: List<Float>,
        val supportedShutterSpeeds: List<String>,
        val supportedIsoValues: List<Int>,
        val supportedWhiteBalances: List<String>,
        val supportedFocusModes: List<String>,
        val supportedImageFormats: List<String>,
        val supportedImageQualities: List<String>,
        val hasLiveView: Boolean,
        val hasVideoRecording: Boolean
    )

    /**
     * 拍摄结果
     */
    data class CaptureResult(
        val isSuccess: Boolean,
        val imageData: ByteArray? = null,
        val filePath: String? = null,
        val errorMessage: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CaptureResult

            if (isSuccess != other.isSuccess) return false
            if (imageData != null) {
                if (other.imageData == null) return false
                if (!imageData.contentEquals(other.imageData)) return false
            } else if (other.imageData != null) return false
            if (filePath != other.filePath) return false
            if (errorMessage != other.errorMessage) return false

            return true
        }

        override fun hashCode(): Int {
            var result = isSuccess.hashCode()
            result = 31 * result + (imageData?.contentHashCode() ?: 0)
            result = 31 * result + (filePath?.hashCode() ?: 0)
            result = 31 * result + (errorMessage?.hashCode() ?: 0)
            return result
        }
    }

    // 连接管理
    suspend fun connect(): Boolean
    suspend fun disconnect()
    fun isConnected(): Boolean
    fun getConnectionStatus(): Flow<ConnectionStatus>

    // 设备信息
    suspend fun getDeviceInfo(): String?
    suspend fun getCameraCapabilities(): CameraCapabilities?

    // 参数控制
    suspend fun getCurrentParameters(): CameraParameters?
    suspend fun setParameters(parameters: CameraParameters): Boolean
    suspend fun setAperture(aperture: Float): Boolean
    suspend fun setShutterSpeed(shutterSpeed: String): Boolean
    suspend fun setIso(iso: Int): Boolean
    suspend fun setWhiteBalance(whiteBalance: String): Boolean
    suspend fun setFocusMode(focusMode: String): Boolean

    // 拍摄控制
    suspend fun captureImage(): CaptureResult
    suspend fun startVideoRecording(): Boolean
    suspend fun stopVideoRecording(): CaptureResult

    // 实时预览
    suspend fun startLiveView(): Boolean
    suspend fun stopLiveView()
    fun getLiveViewStream(): Flow<Bitmap?>

    // 自动对焦
    suspend fun autoFocus(): Boolean

    /**
     * 获取预览帧数据
     * @return 帧数据字节数组，通常为JPEG格式
     */
    suspend fun getPreviewFrame(): ByteArray?

    /**
     * 下载最新拍摄的文件
     * @return 文件数据的字节数组，如果下载失败则返回null
     */
    suspend fun downloadLatestFile(): ByteArray?

    /**
     * 下载指定的文件
     * @param fileName 文件名
     * @return 文件数据的字节数组，如果下载失败则返回null
     */
    suspend fun downloadFile(fileName: String): ByteArray?

    /**
     * 获取相机中的文件列表
     * @return 文件名列表
     */
    suspend fun getFileList(): List<String>

    /**
     * 删除相机中的文件
     * @param fileName 要删除的文件名
     * @return 删除是否成功
     */
    suspend fun deleteFile(fileName: String): Boolean

    // 资源释放
    fun release()
}