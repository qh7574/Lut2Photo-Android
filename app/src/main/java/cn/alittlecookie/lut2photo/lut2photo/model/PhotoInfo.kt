package cn.alittlecookie.lut2photo.lut2photo.model

/**
 * 相机照片信息
 * @param path 照片在相机中的路径
 * @param name 照片文件名
 * @param size 照片大小（字节）
 * @param timestamp 照片时间戳
 */
data class PhotoInfo(
    val path: String,
    val name: String,
    val size: Long,
    val timestamp: Long
)
