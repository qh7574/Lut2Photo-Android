package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import androidx.core.content.edit

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
}