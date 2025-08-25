package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import androidx.core.content.edit
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig

class PreferencesManager(context: Context) {
    private val sharedPreferences =
        context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)

    // Home Fragment 设置 (0-100范围)
    var homeStrength: Float
        get() = sharedPreferences.getFloat("home_strength", 60f)
        set(value) = sharedPreferences.edit {
            putFloat("home_strength", value.coerceIn(0f, 100f))
        }

    var homeQuality: Float
        get() = sharedPreferences.getFloat("home_quality", 90f)
        set(value) = sharedPreferences.edit {
            putFloat("home_quality", value.coerceIn(50f, 100f))
        }

    var homeDitherType: String
        get() = sharedPreferences.getString("home_dither_type", "none") ?: "none"
        set(value) = sharedPreferences.edit { putString("home_dither_type", value) }

    var homeLutUri: String?
        get() = sharedPreferences.getString("home_lut_uri", null)
        set(value) = sharedPreferences.edit { putString("home_lut_uri", value) }

    var homeInputFolder: String
        get() = sharedPreferences.getString("home_input_folder", null) ?: ""
        set(value) = sharedPreferences.edit { putString("home_input_folder", value) }

    var homeOutputFolder: String
        get() = sharedPreferences.getString("home_output_folder", null) ?: ""
        set(value) = sharedPreferences.edit { putString("home_output_folder", value) }

    // Dashboard Fragment 设置 - 修改为统一使用Float类型
    var dashboardStrength: Float
        get() = sharedPreferences.getFloat("dashboard_strength", 0.6f)
        set(value) = sharedPreferences.edit {
            putFloat("dashboard_strength", value.coerceIn(0.0f, 1.0f))
        }

    var dashboardQuality: Float
        get() {
            // 兼容性处理：如果之前存储为Int，转换为Float
            return try {
                sharedPreferences.getFloat("dashboard_quality", 95f)
            } catch (_: ClassCastException) {
                // 如果之前存储为Int，读取并转换为Float
                val intValue = sharedPreferences.getInt("dashboard_quality", 95)
                val floatValue = intValue.toFloat()
                // 重新存储为Float
                sharedPreferences.edit { putFloat("dashboard_quality", floatValue) }
                floatValue
            }
        }
        set(value) = sharedPreferences.edit {
            putFloat("dashboard_quality", value.coerceIn(50f, 100f))
        }

    var dashboardDitherType: String
        get() = sharedPreferences.getString("dashboard_dither_type", "none") ?: "none"
        set(value) = sharedPreferences.edit { putString("dashboard_dither_type", value) }

    var dashboardLutUri: String?
        get() = sharedPreferences.getString("dashboard_lut_uri", null)
        set(value) = sharedPreferences.edit { putString("dashboard_lut_uri", value) }

    var dashboardOutputFolder: String
        get() = sharedPreferences.getString("dashboard_output_folder", null) ?: ""
        set(value) = sharedPreferences.edit { putString("dashboard_output_folder", value) }

    var isMonitoring: Boolean
        get() = sharedPreferences.getBoolean("is_monitoring", false)
        set(value) = sharedPreferences.edit { putBoolean("is_monitoring", value) }

    // Dashboard折叠状态保存
    var dashboardFileSettingsExpanded: Boolean
        get() = sharedPreferences.getBoolean("dashboard_file_settings_expanded", true)
        set(value) = sharedPreferences.edit {
            putBoolean("dashboard_file_settings_expanded", value)
        }

    var dashboardParamsExpanded: Boolean
        get() = sharedPreferences.getBoolean("dashboard_params_expanded", true)
        set(value) = sharedPreferences.edit { putBoolean("dashboard_params_expanded", value) }


    var homeFileSettingsExpanded: Boolean
        get() = sharedPreferences.getBoolean("home_file_settings_expanded", true)
        set(value) = sharedPreferences.edit {
            putBoolean("home_file_settings_expanded", value)
        }

    var homeParamsExpanded: Boolean
        get() = sharedPreferences.getBoolean("home_params_expanded", true)
        set(value) = sharedPreferences.edit { putBoolean("home_params_expanded", value) }

    // 处理器选择设置
    var processorType: String
        get() = sharedPreferences.getString("processor_type", "auto") ?: "auto"
        set(value) = sharedPreferences.edit { putString("processor_type", value) }

    // 监控开关状态 - 修复：默认值改为false，防止重启后自动开启
    var monitoringSwitchEnabled: Boolean
        get() = sharedPreferences.getBoolean("monitoring_switch_enabled", false)
        set(value) = sharedPreferences.edit { putBoolean("monitoring_switch_enabled", value) }

    // 水印配置设置
    fun saveWatermarkConfig(config: WatermarkConfig) {
        sharedPreferences.edit {
            putBoolean("watermark_enabled", config.isEnabled)
            putBoolean("watermark_enable_text", config.enableTextWatermark)
            putBoolean("watermark_enable_image", config.enableImageWatermark)
            
            // 新的分离位置参数
            putFloat("watermark_text_position_x", config.textPositionX)
            putFloat("watermark_text_position_y", config.textPositionY)
            putFloat("watermark_image_position_x", config.imagePositionX)
            putFloat("watermark_image_position_y", config.imagePositionY)

            // 新的分离透明度参数
            putFloat("watermark_text_opacity", config.textOpacity)
            putFloat("watermark_image_opacity", config.imageOpacity)

            // 向后兼容参数（使用textPosition和textOpacity作为默认值）
            putFloat("watermark_position_x", config.textPositionX)
            putFloat("watermark_position_y", config.textPositionY)
            putFloat("watermark_opacity", config.textOpacity)
            
            putFloat("watermark_text_size", config.textSize)
            putFloat("watermark_image_size", config.imageSize)
            putString("watermark_text_content", config.textContent)
            putString("watermark_text_color", config.textColor)
            putString("watermark_font_path", config.fontPath)
            putString("watermark_text_alignment", config.textAlignment.name)
            putString("watermark_image_path", config.imagePath)
            putFloat("watermark_text_image_spacing", config.textImageSpacing)
            putFloat("watermark_border_top_width", config.borderTopWidth)
            putFloat("watermark_border_bottom_width", config.borderBottomWidth)
            putFloat("watermark_border_left_width", config.borderLeftWidth)
            putFloat("watermark_border_right_width", config.borderRightWidth)
            putString("watermark_border_color", config.borderColor)
            putFloat("watermark_letter_spacing", config.letterSpacing)
            putFloat("watermark_line_spacing", config.lineSpacing)
        }
    }

    // 手动处理页面的水印开关状态
    var dashboardWatermarkEnabled: Boolean
        get() = sharedPreferences.getBoolean("dashboard_watermark_enabled", false)
        set(value) = sharedPreferences.edit { putBoolean("dashboard_watermark_enabled", value) }

    // 文件夹监控的水印开关状态
    var folderMonitorWatermarkEnabled: Boolean
        get() = sharedPreferences.getBoolean("folder_monitor_watermark_enabled", false)
        set(value) = sharedPreferences.edit {
            putBoolean(
                "folder_monitor_watermark_enabled",
                value
            )
        }

    // 保持原有的watermarkEnabled作为向后兼容，但现在它只影响水印配置的保存
    var watermarkEnabled: Boolean
        get() = sharedPreferences.getBoolean("watermark_enabled", false)
        set(value) = sharedPreferences.edit { putBoolean("watermark_enabled", value) }

    // 修改水印配置的获取方法，支持指定来源
    fun getWatermarkConfig(forFolderMonitor: Boolean = false): WatermarkConfig {
        val isEnabled =
            if (forFolderMonitor) folderMonitorWatermarkEnabled else dashboardWatermarkEnabled

        // 读取文本对齐方式
        val textAlignmentName =
            sharedPreferences.getString("watermark_text_alignment", "LEFT") ?: "LEFT"
        val textAlignment = try {
            cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.valueOf(textAlignmentName)
        } catch (e: IllegalArgumentException) {
            cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT
        }
            
        return WatermarkConfig(
            isEnabled = isEnabled,
            enableTextWatermark = sharedPreferences.getBoolean("watermark_enable_text", false),
            enableImageWatermark = sharedPreferences.getBoolean("watermark_enable_image", false),

            // 新的分离位置参数，如果不存在则使用旧参数作为默认值
            textPositionX = sharedPreferences.getFloat(
                "watermark_text_position_x",
                sharedPreferences.getFloat("watermark_position_x", 50f)
            ),
            textPositionY = sharedPreferences.getFloat(
                "watermark_text_position_y",
                sharedPreferences.getFloat("watermark_position_y", 90f)
            ),
            imagePositionX = sharedPreferences.getFloat(
                "watermark_image_position_x",
                sharedPreferences.getFloat("watermark_position_x", 50f)
            ),
            imagePositionY = sharedPreferences.getFloat(
                "watermark_image_position_y",
                sharedPreferences.getFloat("watermark_position_y", 10f)
            ),

            // 新的分离透明度参数，如果不存在则使用旧参数作为默认值
            textOpacity = sharedPreferences.getFloat(
                "watermark_text_opacity",
                sharedPreferences.getFloat("watermark_opacity", 80f)
            ),
            imageOpacity = sharedPreferences.getFloat(
                "watermark_image_opacity",
                sharedPreferences.getFloat("watermark_opacity", 80f)
            ),
                
            textSize = sharedPreferences.getFloat("watermark_text_size", 10f),
            imageSize = sharedPreferences.getFloat("watermark_image_size", 10f),
            textContent = sharedPreferences.getString(
                "watermark_text_content",
                "拍摄参数：ISO {ISO} 光圈 f/{APERTURE} 快门 {SHUTTER}"
            ) ?: "拍摄参数：ISO {ISO} 光圈 f/{APERTURE} 快门 {SHUTTER}",
            textColor = sharedPreferences.getString("watermark_text_color", "#FFFFFF") ?: "#FFFFFF",
            fontPath = sharedPreferences.getString("watermark_font_path", "") ?: "",
            textAlignment = textAlignment,
            imagePath = sharedPreferences.getString("watermark_image_path", "") ?: "",
            textImageSpacing = sharedPreferences.getFloat("watermark_text_image_spacing", 5f),
            borderTopWidth = sharedPreferences.getFloat("watermark_border_top_width", 0f),
            borderBottomWidth = sharedPreferences.getFloat("watermark_border_bottom_width", 0f),
            borderLeftWidth = sharedPreferences.getFloat("watermark_border_left_width", 0f),
            borderRightWidth = sharedPreferences.getFloat("watermark_border_right_width", 0f),
            borderColor = sharedPreferences.getString("watermark_border_color", "#000000")
                ?: "#000000",
            letterSpacing = sharedPreferences.getFloat("watermark_letter_spacing", 0f),
            lineSpacing = sharedPreferences.getFloat("watermark_line_spacing", 0f)
        )
    }
}