package cn.alittlecookie.lut2photo.lut2photo.model

import android.graphics.Color

/**
 * 水印配置数据类
 */
data class WatermarkConfig(
    // 基础设置
    val isEnabled: Boolean = false,
    val enableTextWatermark: Boolean = false,
    val enableImageWatermark: Boolean = false,

    // 位置设置 (百分比形式，0-100)
    val positionX: Float = 50f, // 水印中心点X位置百分比
    val positionY: Float = 90f, // 水印中心点Y位置百分比

    // 大小和透明度设置 (百分比形式，0-100)
    val textSize: Float = 10f, // 文字水印大小百分比
    val imageSize: Float = 10f, // 图片水印大小百分比
    val opacity: Float = 80f, // 透明度百分比

    // 文字水印设置
    val textContent: String = "拍摄参数：ISO {ISO} 光圈 f/{APERTURE} 快门 {SHUTTER}", // 支持变量替换
    val textColor: String = "#FFFFFF", // 16进制颜色
    val fontPath: String = "", // 字体文件路径

    // 图片水印设置
    val imagePath: String = "", // 水印图片路径

    // 文字和图片水印间距设置
    val textImageSpacing: Float = 5f, // 文字水印与图片水印的距离百分比

    // 边框设置
    val borderWidth: Float = 0f, // 边框宽度百分比，100%表示边框宽度与背景图短边一致
    val borderColor: String = "#000000" // 边框颜色
) {
    /**
     * 获取文字颜色的Color值
     */
    fun getTextColorInt(): Int {
        return try {
            Color.parseColor(textColor)
        } catch (_: Exception) {
            Color.WHITE
        }
    }

    /**
     * 获取边框颜色的Color值
     */
    fun getBorderColorInt(): Int {
        return try {
            Color.parseColor(borderColor)
        } catch (_: Exception) {
            Color.BLACK
        }
    }
}

/**
 * EXIF变量定义
 */
object ExifVariables {
    const val ISO = "{ISO}"
    const val APERTURE = "{APERTURE}"
    const val SHUTTER = "{SHUTTER}"
    const val CAMERA_MODEL = "{CAMERA_MODEL}"
    const val LENS_MODEL = "{LENS_MODEL}"
    const val DATE = "{DATE}"
    const val TIME = "{TIME}"
    const val FOCAL_LENGTH = "{FOCAL_LENGTH}"
    const val EXPOSURE_COMPENSATION = "{EXPOSURE_COMPENSATION}"
    const val WHITE_BALANCE = "{WHITE_BALANCE}"
    const val FLASH = "{FLASH}"

}