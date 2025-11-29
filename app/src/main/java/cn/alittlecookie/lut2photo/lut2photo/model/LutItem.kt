package cn.alittlecookie.lut2photo.lut2photo.model

data class LutItem(
    val id: String,
    val name: String,
    val filePath: String,
    val size: Long,
    val lastModified: Long,
    var isSelected: Boolean = false,
    val vltFileName: String? = null,  // 对应的 VLT 文件名
    val uploadName: String? = null     // 上传时使用的文件名（6位字符）
)