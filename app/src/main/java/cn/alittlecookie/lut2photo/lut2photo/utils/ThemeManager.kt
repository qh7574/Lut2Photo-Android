package cn.alittlecookie.lut2photo.lut2photo.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
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
        prefs.edit().putInt(KEY_THEME_MODE, themeMode).apply()
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
    
    fun getThemeName(themeMode: Int): String {
        return when (themeMode) {
            THEME_LIGHT -> "浅色主题"
            THEME_DARK -> "深色主题"
            THEME_SYSTEM -> "跟随系统"
            THEME_DYNAMIC -> "动态颜色"
            else -> "未知"
        }
    }
    
    fun isDynamicColorSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DynamicColors.isDynamicColorAvailable()
    }
    
    /**
     * 应用动态颜色到整个应用
     */
    fun applyDynamicColors() {
        if (isDynamicColorSupported()) {
            DynamicColors.applyToActivitiesIfAvailable(context as? android.app.Application ?: return)
        }
    }
}