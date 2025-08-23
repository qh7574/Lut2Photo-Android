package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import java.io.File

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

    // ========== 水印设置 ==========
    // Home Fragment 水印设置
    var homeWatermarkEnabled: Boolean
        get() = sharedPreferences.getBoolean("home_watermark_enabled", false)
        set(value) = sharedPreferences.edit { putBoolean("home_watermark_enabled", value) }

    var homeWatermarkType: String
        get() = sharedPreferences.getString("home_watermark_type", "text")
            ?: "text" // "text" 或 "image"
        set(value) = sharedPreferences.edit { putString("home_watermark_type", value) }

    var homeWatermarkText: String
        get() = sharedPreferences.getString("home_watermark_text", "Lut2Photo") ?: "Lut2Photo"
        set(value) = sharedPreferences.edit { putString("home_watermark_text", value) }

    var homeWatermarkImagePath: String?
        get() = sharedPreferences.getString("home_watermark_image_path", null)
        set(value) = sharedPreferences.edit { putString("home_watermark_image_path", value) }

    var homeWatermarkPositionX: Float
        get() = sharedPreferences.getFloat("home_watermark_position_x", 0.8f) // 0.0-1.0，相对于图片宽度
        set(value) = sharedPreferences.edit {
            putFloat(
                "home_watermark_position_x",
                value.coerceIn(0f, 1f)
            )
        }

    var homeWatermarkPositionY: Float
        get() = sharedPreferences.getFloat("home_watermark_position_y", 0.9f) // 0.0-1.0，相对于图片高度
        set(value) = sharedPreferences.edit {
            putFloat(
                "home_watermark_position_y",
                value.coerceIn(0f, 1f)
            )
        }

    var homeWatermarkSize: Float
        get() = sharedPreferences.getFloat("home_watermark_size", 0.1f) // 0.0-1.0，相对于背景图片高度的比例
        set(value) = sharedPreferences.edit {
            putFloat(
                "home_watermark_size",
                value.coerceIn(0.01f, 1f)
            )
        }

    // Dashboard Fragment 水印设置
    var dashboardWatermarkEnabled: Boolean
        get() = sharedPreferences.getBoolean("dashboard_watermark_enabled", false)
        set(value) = sharedPreferences.edit { putBoolean("dashboard_watermark_enabled", value) }

    var dashboardWatermarkType: String
        get() = sharedPreferences.getString("dashboard_watermark_type", "text") ?: "text"
        set(value) = sharedPreferences.edit { putString("dashboard_watermark_type", value) }

    var dashboardWatermarkText: String
        get() = sharedPreferences.getString("dashboard_watermark_text", "Lut2Photo") ?: "Lut2Photo"
        set(value) = sharedPreferences.edit { putString("dashboard_watermark_text", value) }

    var dashboardWatermarkImagePath: String?
        get() = sharedPreferences.getString("dashboard_watermark_image_path", null)
        set(value) = sharedPreferences.edit { putString("dashboard_watermark_image_path", value) }

    var dashboardWatermarkPositionX: Float
        get() = sharedPreferences.getFloat("dashboard_watermark_position_x", 0.8f)
        set(value) = sharedPreferences.edit {
            putFloat(
                "dashboard_watermark_position_x",
                value.coerceIn(0f, 1f)
            )
        }

    var dashboardWatermarkPositionY: Float
        get() = sharedPreferences.getFloat("dashboard_watermark_position_y", 0.9f)
        set(value) = sharedPreferences.edit {
            putFloat(
                "dashboard_watermark_position_y",
                value.coerceIn(0f, 1f)
            )
        }

    var dashboardWatermarkSize: Float
        get() = sharedPreferences.getFloat("dashboard_watermark_size", 0.1f)
        set(value) = sharedPreferences.edit {
            putFloat(
                "dashboard_watermark_size",
                value.coerceIn(0.01f, 1f)
            )
        }

    /**
     * 获取Dashboard的水印设置
     */
    fun getWatermarkPreferences(): WatermarkPreferences {
        return WatermarkPreferences(
            type = if (dashboardWatermarkType == "text") WatermarkPreferences.WatermarkType.TEXT else WatermarkPreferences.WatermarkType.IMAGE,
            text = dashboardWatermarkText,
            textColor = android.graphics.Color.WHITE,
            imageUri = dashboardWatermarkImagePath,
            positionX = dashboardWatermarkPositionX,
            positionY = dashboardWatermarkPositionY,
            size = dashboardWatermarkSize,
            opacity = dashboardWatermarkOpacity // 使用动态透明度
        )
    }

    /**
     * 获取Home的水印设置
     */
    fun getHomeWatermarkPreferences(): WatermarkPreferences {
        return WatermarkPreferences(
            type = if (homeWatermarkType == "text") WatermarkPreferences.WatermarkType.TEXT else WatermarkPreferences.WatermarkType.IMAGE,
            text = homeWatermarkText,
            textColor = android.graphics.Color.WHITE,
            imageUri = homeWatermarkImagePath,
            positionX = homeWatermarkPositionX,
            positionY = homeWatermarkPositionY,
            size = homeWatermarkSize,
            opacity = homeWatermarkOpacity // 使用动态透明度
        )
    }

    // 添加透明度设置
    var homeWatermarkOpacity: Float
        get() = sharedPreferences.getFloat("home_watermark_opacity", 0.8f)
        set(value) = sharedPreferences.edit().putFloat("home_watermark_opacity", value).apply()

    var dashboardWatermarkOpacity: Float
        get() = sharedPreferences.getFloat("dashboard_watermark_opacity", 0.8f)
        set(value) = sharedPreferences.edit().putFloat("dashboard_watermark_opacity", value).apply()

    /**
     * 验证水印图片URI是否仍然有效
     */
    fun validateWatermarkImageUri(context: Context, isHome: Boolean): Boolean {
        val imageUri = if (isHome) homeWatermarkImagePath else dashboardWatermarkImagePath
        if (imageUri.isNullOrEmpty()) return true // 没有设置图片水印，认为是有效的

        return try {
            val uri = Uri.parse(imageUri)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.available() > 0
            } ?: false
        } catch (e: Exception) {
            Log.e("PreferencesManager", "验证水印图片URI失败: $imageUri", e)
            false
        }
    }

    /**
     * 验证本地水印图片文件是否存在
     */
    fun validateLocalWatermarkImage(isHome: Boolean): Boolean {
        val imagePath = if (isHome) homeWatermarkImagePath else dashboardWatermarkImagePath
        if (imagePath.isNullOrEmpty()) return true

        return try {
            val file = File(imagePath)
            file.exists() && file.length() > 0
        } catch (e: Exception) {
            Log.e("PreferencesManager", "验证本地水印图片失败: $imagePath", e)
            false
        }
    }

}