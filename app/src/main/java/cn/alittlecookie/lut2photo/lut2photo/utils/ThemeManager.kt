package cn.alittlecookie.lut2photo.lut2photo.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import cn.alittlecookie.lut2photo.lut2photo.R
import com.google.android.material.color.DynamicColors

class ThemeManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "theme_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_SYSTEM = 2
        const val THEME_DYNAMIC = 3

    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun getCurrentTheme(): Int {
        return prefs.getInt(KEY_THEME_MODE, if (isDynamicColorSupported()) THEME_DYNAMIC else THEME_SYSTEM)
    }
    
    fun setTheme(themeMode: Int) {
        prefs.edit { putInt(KEY_THEME_MODE, themeMode) }
        applyTheme(themeMode)
    }
    
    fun applyTheme(themeMode: Int = getCurrentTheme()) {
        when (themeMode) {
            THEME_LIGHT -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setAppTheme(R.style.Theme_Lut2Photo_Light)
            }
            THEME_DARK -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                setAppTheme(R.style.Theme_Lut2Photo_Dark)
            }
            THEME_SYSTEM -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                setAppTheme(R.style.Theme_Lut2Photo)
            }
            THEME_DYNAMIC -> {
                if (isDynamicColorSupported()) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    setAppTheme(R.style.Theme_Lut2Photo)
                    // 应用动态颜色
                    if (context is Activity) {
                        DynamicColors.applyToActivityIfAvailable(context)
                    }
                } else {
                    // 降级到系统主题
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    setAppTheme(R.style.Theme_Lut2Photo)
                }
            }
        }
    }
    
    private fun setAppTheme(themeResId: Int) {
        if (context is Activity) {
            context.setTheme(themeResId)
        }
    }

    fun getThemeName(theme: Int): String {
        return when (theme) {
            THEME_LIGHT -> context.getString(R.string.theme_light)
            THEME_DARK -> context.getString(R.string.theme_dark)
            THEME_SYSTEM -> context.getString(R.string.theme_follow_system)
            THEME_DYNAMIC -> context.getString(R.string.theme_dynamic_color)
            else -> context.getString(R.string.theme_system)
        }
    }
    
    fun isDynamicColorSupported(): Boolean {
        return DynamicColors.isDynamicColorAvailable()
    }

}