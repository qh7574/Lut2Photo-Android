package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context

class PreferencesManager(context: Context) {
    private val sharedPreferences =
        context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)

    // Home Fragment 设置 (0-100范围)
    var homeStrength: Float
        get() = sharedPreferences.getFloat("home_strength", 60f)
        set(value) = sharedPreferences.edit().putFloat("home_strength", value.coerceIn(0f, 100f))
            .apply()

    var homeQuality: Float
        get() = sharedPreferences.getFloat("home_quality", 90f)
        set(value) = sharedPreferences.edit().putFloat("home_quality", value.coerceIn(50f, 100f))
            .apply()

    var homeDitherType: String
        get() = sharedPreferences.getString("home_dither_type", "none") ?: "none"
        set(value) = sharedPreferences.edit().putString("home_dither_type", value).apply()

    var homeLutUri: String?
        get() = sharedPreferences.getString("home_lut_uri", null)
        set(value) = sharedPreferences.edit().putString("home_lut_uri", value).apply()

    var homeInputFolder: String
        get() = sharedPreferences.getString("home_input_folder", null) ?: ""
        set(value) = sharedPreferences.edit().putString("home_input_folder", value).apply()

    var homeOutputFolder: String
        get() = sharedPreferences.getString("home_output_folder", null) ?: ""
        set(value) = sharedPreferences.edit().putString("home_output_folder", value).apply()

    // Dashboard Fragment 设置 - 修改为统一使用Float类型
    var dashboardStrength: Float
        get() = sharedPreferences.getFloat("dashboard_strength", 0.6f)
        set(value) = sharedPreferences.edit()
            .putFloat("dashboard_strength", value.coerceIn(0.0f, 1.0f)).apply()

    var dashboardQuality: Float
        get() {
            // 兼容性处理：如果之前存储为Int，转换为Float
            return try {
                sharedPreferences.getFloat("dashboard_quality", 95f)
            } catch (e: ClassCastException) {
                // 如果之前存储为Int，读取并转换为Float
                val intValue = sharedPreferences.getInt("dashboard_quality", 95)
                val floatValue = intValue.toFloat()
                // 重新存储为Float
                sharedPreferences.edit().putFloat("dashboard_quality", floatValue).apply()
                floatValue
            }
        }
        set(value) = sharedPreferences.edit()
            .putFloat("dashboard_quality", value.coerceIn(50f, 100f)).apply()

    var dashboardDitherType: String
        get() = sharedPreferences.getString("dashboard_dither_type", "none") ?: "none"
        set(value) = sharedPreferences.edit().putString("dashboard_dither_type", value).apply()

    var dashboardLutUri: String?
        get() = sharedPreferences.getString("dashboard_lut_uri", null)
        set(value) = sharedPreferences.edit().putString("dashboard_lut_uri", value).apply()

    var dashboardOutputFolder: String
        get() = sharedPreferences.getString("dashboard_output_folder", null) ?: ""
        set(value) = sharedPreferences.edit().putString("dashboard_output_folder", value).apply()

    var isMonitoring: Boolean
        get() = sharedPreferences.getBoolean("is_monitoring", false)
        set(value) = sharedPreferences.edit().putBoolean("is_monitoring", value).apply()

    // Dashboard折叠状态保存
    var dashboardFileSettingsExpanded: Boolean
        get() = sharedPreferences.getBoolean("dashboard_file_settings_expanded", true)
        set(value) = sharedPreferences.edit().putBoolean("dashboard_file_settings_expanded", value)
            .apply()

    var dashboardParamsExpanded: Boolean
        get() = sharedPreferences.getBoolean("dashboard_params_expanded", true)
        set(value) = sharedPreferences.edit().putBoolean("dashboard_params_expanded", value).apply()

    // Home折叠状态保存
    var dashboardAdvancedExpanded: Boolean
        get() = sharedPreferences.getBoolean("dashboard_advanced_expanded", false)
        set(value) = sharedPreferences.edit().putBoolean("dashboard_advanced_expanded", value)
            .apply()

    var homeFileSettingsExpanded: Boolean
        get() = sharedPreferences.getBoolean("home_file_settings_expanded", true)
        set(value) = sharedPreferences.edit().putBoolean("home_file_settings_expanded", value)
            .apply()

    var homeParamsExpanded: Boolean
        get() = sharedPreferences.getBoolean("home_params_expanded", true)
        set(value) = sharedPreferences.edit().putBoolean("home_params_expanded", value).apply()

    // Home高级设置折叠状态
    var homeAdvancedExpanded: Boolean
        get() = sharedPreferences.getBoolean("home_advanced_expanded", false)
        set(value) = sharedPreferences.edit().putBoolean("home_advanced_expanded", value).apply()
}