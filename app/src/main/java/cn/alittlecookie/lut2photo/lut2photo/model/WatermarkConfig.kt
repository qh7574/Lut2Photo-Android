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

    // 文字水印位置设置 (百分比形式，0-100)
    val textPositionX: Float = 50f, // 文字水印X位置百分比
    val textPositionY: Float = 90f, // 文字水印Y位置百分比

    // 图片水印位置设置 (百分比形式，0-100)
    val imagePositionX: Float = 50f, // 图片水印X位置百分比
    val imagePositionY: Float = 10f, // 图片水印Y位置百分比

    // 大小设置 (百分比形式，0-100)
    val textSize: Float = 10f, // 文字水印大小百分比
    val imageSize: Float = 10f, // 图片水印大小百分比

    // 透明度设置 (百分比形式，0-100)
    val textOpacity: Float = 80f, // 文字水印透明度百分比
    val imageOpacity: Float = 80f, // 图片水印透明度百分比

    // 文字水印设置
    val textContent: String = "拍摄参数：ISO {ISO} 光圈 f/{APERTURE} 快门 {SHUTTER}", // 支持变量替换
    val textColor: String = "#FFFFFF", // 16进制颜色
    val fontPath: String = "", // 字体文件路径
    val textAlignment: TextAlignment = TextAlignment.LEFT, // 文本对齐方式

    // 图片水印设置
    val imagePath: String = "", // 水印图片路径

    // 文字跟随模式设置
    val enableTextFollowMode: Boolean = false, // 是否启用文字跟随模式
    val textFollowDirection: TextFollowDirection = TextFollowDirection.BOTTOM, // 文字跟随方向

    // 文字和图片水印间距设置 (跟随模式下使用水印图片尺寸的百分比，0-500%，5%步进)
    val textImageSpacing: Float = 0f, // 文字水印与图片水印的距离百分比

    // 边框设置 - 四个方向独立控制
    val borderTopWidth: Float = 0f, // 上边框宽度百分比，0%-150%
    val borderBottomWidth: Float = 0f, // 下边框宽度百分比，0%-150%
    val borderLeftWidth: Float = 0f, // 左边框宽度百分比，0%-150%
    val borderRightWidth: Float = 0f, // 右边框宽度百分比，0%-150%
    val borderColor: String = "#000000", // 边框颜色
    val borderColorMode: BorderColorMode = BorderColorMode.MANUAL, // 边框颜色模式

    // 文字间距设置
    val letterSpacing: Float = 0f, // 字间距，使用背景图宽度的百分比（0.1%-100%）
    val lineSpacing: Float = 0f, // 行间距，使用图片高度的百分比

    // 向后兼容性字段（已弃用）
    @Deprecated("使用 textPositionX 和 imagePositionX")
    val positionX: Float = textPositionX, // 保持兼容性
    @Deprecated("使用 textPositionY 和 imagePositionY")
    val positionY: Float = textPositionY, // 保持兼容性
    @Deprecated("使用 textOpacity 和 imageOpacity")
    val opacity: Float = textOpacity // 保持兼容性
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

/**
 * 文本对齐方式枚举
 */
enum class TextAlignment {
    LEFT,    // 左对齐
    CENTER,  // 居中对齐
    RIGHT    // 右对齐
}

/**
 * 文字跟随方向枚举
 */
enum class TextFollowDirection {
    TOP,     // 文字在图片上方
    BOTTOM,  // 文字在图片下方
    LEFT,    // 文字在图片左侧
    RIGHT    // 文字在图片右侧
}

/**
 * 边框颜色模式枚举
 */
enum class BorderColorMode {
    MANUAL,    // 手动选择颜色
    PALETTE,   // 从图片自动提取颜色
    MATERIAL   // Material Design颜色
}