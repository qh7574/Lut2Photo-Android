package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("lut2photo_prefs", Context.MODE_PRIVATE)
    
    // Dashboard设置
    var dashboardStrength: Float
        get() = prefs.getFloat("dashboard_strength", 60f)
        set(value) = prefs.edit().putFloat("dashboard_strength", value).apply()
    
    var dashboardQuality: Float
        get() = prefs.getFloat("dashboard_quality", 90f)
        set(value) = prefs.edit().putFloat("dashboard_quality", value).apply()
    
    var dashboardDitherType: String
        get() = prefs.getString("dashboard_dither_type", "none") ?: "none"
        set(value) = prefs.edit().putString("dashboard_dither_type", value).apply()
    
    var dashboardLutUri: String?
        get() = prefs.getString("dashboard_lut_uri", null)
        set(value) = prefs.edit().putString("dashboard_lut_uri", value).apply()
    
    // 添加缺少的dashboardOutputFolder属性
    var dashboardOutputFolder: String?
        get() = prefs.getString("dashboard_output_folder", null)
        set(value) = prefs.edit().putString("dashboard_output_folder", value).apply()
    
    // Home设置
    var homeStrength: Float
        get() = prefs.getFloat("home_strength", 60f)
        set(value) = prefs.edit().putFloat("home_strength", value).apply()
    
    var homeQuality: Float
        get() = prefs.getFloat("home_quality", 90f)
        set(value) = prefs.edit().putFloat("home_quality", value).apply()
    
    var homeDitherType: String
        get() = prefs.getString("home_dither_type", "none") ?: "none"
        set(value) = prefs.edit().putString("home_dither_type", value).apply()
    
    var homeLutUri: String?
        get() = prefs.getString("home_lut_uri", null)
        set(value) = prefs.edit().putString("home_lut_uri", value).apply()
    
    var homeInputFolder: String?
        get() = prefs.getString("home_input_folder", null)
        set(value) = prefs.edit().putString("home_input_folder", value).apply()
    
    var homeOutputFolder: String?
        get() = prefs.getString("home_output_folder", null)
        set(value) = prefs.edit().putString("home_output_folder", value).apply()
    
    var isMonitoring: Boolean
        get() = prefs.getBoolean("is_monitoring", false)
        set(value) = prefs.edit().putBoolean("is_monitoring", value).apply()
    
    // 添加Dashboard折叠状态保存
    var dashboardFileSettingsExpanded: Boolean
        get() = prefs.getBoolean("dashboard_file_settings_expanded", true)
        set(value) = prefs.edit().putBoolean("dashboard_file_settings_expanded", value).apply()
    
    var dashboardParamsExpanded: Boolean
        get() = prefs.getBoolean("dashboard_params_expanded", true)
        set(value) = prefs.edit().putBoolean("dashboard_params_expanded", value).apply()
    
    // 添加Home折叠状态保存
    var homeFileSettingsExpanded: Boolean
        get() = prefs.getBoolean("home_file_settings_expanded", true)
        set(value) = prefs.edit().putBoolean("home_file_settings_expanded", value).apply()
    
    var homeParamsExpanded: Boolean
        get() = prefs.getBoolean("home_params_expanded", true)
        set(value) = prefs.edit().putBoolean("home_params_expanded", value).apply()
}