package cn.alittlecookie.lut2photo.lut2photo.model

data class LutItem(
    val id: String,
    val name: String,
    val filePath: String,
    val size: Long,
    val lastModified: Long,
    var isSelected: Boolean = false
)