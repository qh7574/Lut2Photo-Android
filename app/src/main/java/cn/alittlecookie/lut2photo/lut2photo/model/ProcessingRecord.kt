package cn.alittlecookie.lut2photo.lut2photo.model

data class ProcessingRecord(
    val timestamp: Long,
    val fileName: String,
    val inputPath: String,
    val outputPath: String,
    val status: String,
    val lutFileName: String = "", // LUT文件名
    val strength: Float = 0f, // 强度参数
    val quality: Int = 0, // 质量参数
    val ditherType: String = "" // 抖动类型
)