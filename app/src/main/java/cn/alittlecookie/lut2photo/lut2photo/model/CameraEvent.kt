package cn.alittlecookie.lut2photo.lut2photo.model

/**
 * 相机事件
 * @param type 事件类型
 * @param data 事件数据（如照片路径）
 */
data class CameraEvent(
    val type: Int,
    val data: String
) {
    companion object {
        const val EVENT_ERROR = -1  // 错误事件（如 USB 断开）
        const val EVENT_UNKNOWN = 0
        const val EVENT_TIMEOUT = 1
        const val EVENT_FILE_ADDED = 2
        const val EVENT_FOLDER_ADDED = 3
        const val EVENT_CAPTURE_COMPLETE = 4
    }
}
