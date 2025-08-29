package cn.alittlecookie.lut2photo.lut2photo.camera

import android.util.Log

/**
 * libgphoto2 JNI接口
 * 提供与Native层libgphoto2库的通信
 */
class LibGPhoto2JNI {

    companion object {
        private const val TAG = "LibGPhoto2JNI"

        init {
            try {
                System.loadLibrary("gphoto2_jni")
                Log.i(TAG, "libgphoto2 JNI库加载成功")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "libgphoto2 JNI库加载失败", e)
            }
        }
    }

    /**
     * 相机信息结构
     */
    data class CameraInfo(
        val model: String,
        val port: String,
        val summary: String
    )

    /**
     * 配置项结构
     */
    data class ConfigItem(
        val name: String,
        val label: String,
        val type: String,
        val value: String,
        val choices: Array<String>?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ConfigItem

            if (name != other.name) return false
            if (label != other.label) return false
            if (type != other.type) return false
            if (value != other.value) return false
            if (choices != null) {
                if (other.choices == null) return false
                if (!choices.contentEquals(other.choices)) return false
            } else if (other.choices != null) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + label.hashCode()
            result = 31 * result + type.hashCode()
            result = 31 * result + value.hashCode()
            result = 31 * result + (choices?.contentHashCode() ?: 0)
            return result
        }
    }

    // ==================== 初始化和清理 ====================

    /**
     * 初始化libgphoto2
     * @return 是否成功
     */
    external fun initialize(): Boolean

    /**
     * 清理libgphoto2资源
     */
    external fun cleanup()

    // ==================== 相机检测和连接 ====================

    /**
     * 自动检测相机
     * @return 检测到的相机列表
     */
    external fun autoDetectCameras(): Array<CameraInfo>?

    /**
     * 连接到指定相机
     * @param model 相机型号
     * @param port 端口信息
     * @return 是否成功连接
     */
    external fun connectCamera(model: String, port: String): Boolean

    /**
     * 断开相机连接
     */
    external fun disconnectCamera()

    /**
     * 检查相机是否已连接
     * @return 是否已连接
     */
    external fun isCameraConnected(): Boolean

    // ==================== 相机信息 ====================

    /**
     * 获取相机摘要信息
     * @return 摘要信息
     */
    external fun getCameraSummary(): String?

    /**
     * 获取相机关于信息
     * @return 关于信息
     */
    external fun getCameraAbout(): String?

    // ==================== 配置管理 ====================

    /**
     * 获取所有配置项
     * @return 配置项列表
     */
    external fun getAllConfigs(): Array<ConfigItem>?

    /**
     * 获取指定配置项
     * @param configName 配置项名称
     * @return 配置项信息
     */
    external fun getConfig(configName: String): ConfigItem?

    /**
     * 设置配置项值
     * @param configName 配置项名称
     * @param value 新值
     * @return 是否成功
     */
    external fun setConfig(configName: String, value: String): Boolean

    // ==================== 拍摄控制 ====================

    /**
     * 拍摄照片
     * @return 照片数据，null表示失败
     */
    external fun captureImage(): ByteArray?

    /**
     * 拍摄照片并保存到相机
     * @return 是否成功
     */
    external fun captureImageToCamera(): Boolean

    /**
     * 触发自动对焦
     * @return 是否成功
     */
    external fun triggerAutoFocus(): Boolean

    // ==================== 实时预览 ====================

    /**
     * 开始实时预览
     * @return 是否成功
     */
    external fun startLiveView(): Boolean

    /**
     * 停止实时预览
     */
    external fun stopLiveView()

    /**
     * 获取预览帧
     * @return 预览帧数据，null表示无数据
     */
    external fun getPreviewFrame(): ByteArray?

    // ==================== 文件操作 ====================

    /**
     * 获取相机文件列表
     * @param folder 文件夹路径
     * @return 文件名列表
     */
    external fun listFiles(folder: String): Array<String>?

    /**
     * 获取文件列表（别名方法）
     * @param folder 文件夹路径
     * @return 文件名列表
     */
    external fun getFileList(folder: String): Array<String>?

    /**
     * 下载文件
     * @param folder 文件夹路径
     * @param filename 文件名
     * @return 文件数据，null表示失败
     */
    external fun downloadFile(folder: String, filename: String): ByteArray?

    /**
     * 删除文件
     * @param folder 文件夹路径
     * @param filename 文件名
     * @return 是否成功
     */
    external fun deleteFile(folder: String, filename: String): Boolean

    // ==================== 错误处理 ====================

    /**
     * 获取最后的错误信息
     * @return 错误信息
     */
    external fun getLastError(): String?

    /**
     * 清除错误状态
     */
    external fun clearError()

    // ==================== 高级功能 ====================

    /**
     * 设置USB设备文件描述符
     * 用于Android USB Host API集成
     * @param fd 文件描述符
     * @return 是否成功
     */
    external fun setUsbDeviceFd(fd: Int): Boolean

    /**
     * 获取支持的操作列表
     * @return 操作名称列表
     */
    external fun getSupportedOperations(): Array<String>?

    /**
     * 检查是否支持指定操作
     * @param operation 操作名称
     * @return 是否支持
     */
    external fun isOperationSupported(operation: String): Boolean

    /**
     * 等待相机事件
     * @param timeoutMs 超时时间（毫秒）
     * @return 事件类型，null表示超时或无事件
     */
    external fun waitForEvent(timeoutMs: Int): String?
}