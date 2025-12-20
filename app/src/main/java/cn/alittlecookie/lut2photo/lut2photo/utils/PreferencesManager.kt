package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig

class PreferencesManager(context: Context) {
    private val sharedPreferences =
        context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)

    // Home Fragment 设置 (0-100范围)
    var homeStrength: Float
        get() = sharedPreferences.getFloat("home_strength", 100f)
        set(value) = sharedPreferences.edit {
            putFloat("home_strength", value.coerceIn(0f, 100f))
        }

    var homeQuality: Float
        get() = sharedPreferences.getFloat("home_quality", 97f)
        set(value) = sharedPreferences.edit {
            putFloat("home_quality", value.coerceIn(50f, 100f))
        }

    var homeDitherType: String
        get() = sharedPreferences.getString("home_dither_type", "random") ?: "random"
        set(value) = sharedPreferences.edit { putString("home_dither_type", value) }

    var homeLutUri: String?
        get() = sharedPreferences.getString("home_lut_uri", null)
        set(value) = sharedPreferences.edit { putString("home_lut_uri", value) }

    // 第二个LUT设置
    var homeLut2Uri: String?
        get() = sharedPreferences.getString("home_lut2_uri", null)
        set(value) = sharedPreferences.edit { putString("home_lut2_uri", value) }

    var homeLut2Strength: Float
        get() = sharedPreferences.getFloat("home_lut2_strength", 100f)
        set(value) = sharedPreferences.edit {
            putFloat("home_lut2_strength", value.coerceIn(0f, 100f))
        }

    var homeInputFolder: String
        get() = sharedPreferences.getString("home_input_folder", null) ?: ""
        set(value) = sharedPreferences.edit { putString("home_input_folder", value) }

    var homeOutputFolder: String
        get() = sharedPreferences.getString("home_output_folder", null) ?: ""
        set(value) = sharedPreferences.edit { putString("home_output_folder", value) }

    // Dashboard Fragment 设置 - 修改为统一使用Float类型
    var dashboardStrength: Float
        get() = sharedPreferences.getFloat("dashboard_strength", 1.0f)
        set(value) = sharedPreferences.edit {
            putFloat("dashboard_strength", value.coerceIn(0.0f, 1.0f))
        }

    var dashboardQuality: Float
        get() {
            // 兼容性处理：如果之前存储为Int，转换为Float
            return try {
                sharedPreferences.getFloat("dashboard_quality", 97f)
            } catch (_: ClassCastException) {
                // 如果之前存储为Int，读取并转换为Float
                val intValue = sharedPreferences.getInt("dashboard_quality", 97)
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
        get() = sharedPreferences.getString("dashboard_dither_type", "random") ?: "random"
        set(value) = sharedPreferences.edit { putString("dashboard_dither_type", value) }

    var dashboardLutUri: String?
        get() = sharedPreferences.getString("dashboard_lut_uri", null)
        set(value) = sharedPreferences.edit { putString("dashboard_lut_uri", value) }

    // Dashboard第二个LUT设置
    var dashboardLut2Uri: String?
        get() = sharedPreferences.getString("dashboard_lut2_uri", null)
        set(value) = sharedPreferences.edit { putString("dashboard_lut2_uri", value) }

    var dashboardLut2Strength: Float
        get() = sharedPreferences.getFloat("dashboard_lut2_strength", 1.0f)
        set(value) = sharedPreferences.edit {
            putFloat("dashboard_lut2_strength", value.coerceIn(0.0f, 1.0f))
            Log.d(
                "PreferencesManager",
                "设置Dashboard LUT2强度: $value，保存值: ${value.coerceIn(0.0f, 1.0f)}"
            )
        }

    var dashboardOutputFolder: String
        get() = sharedPreferences.getString("dashboard_output_folder", null) ?: ""
        set(value) = sharedPreferences.edit { putString("dashboard_output_folder", value) }

    var isMonitoring: Boolean
        get() {
            val value = sharedPreferences.getBoolean("is_monitoring", false)
            Log.d("PreferencesManager", "读取 isMonitoring: $value")
            return value
        }
        set(value) {
            Log.d("PreferencesManager", "设置 isMonitoring: $value")
            sharedPreferences.edit { putBoolean("is_monitoring", value) }
        }

    // Dashboard折叠状态保存
    var dashboardParamsExpanded: Boolean
        get() = sharedPreferences.getBoolean("dashboard_params_expanded", true)
        set(value) = sharedPreferences.edit { putBoolean("dashboard_params_expanded", value) }

    var dashboardPreviewExpanded: Boolean
        get() = sharedPreferences.getBoolean("dashboard_preview_expanded", true)
        set(value) = sharedPreferences.edit { putBoolean("dashboard_preview_expanded", value) }


    var homeFileSettingsExpanded: Boolean
        get() = sharedPreferences.getBoolean("home_file_settings_expanded", true)
        set(value) = sharedPreferences.edit {
            putBoolean("home_file_settings_expanded", value)
        }

    var homeParamsExpanded: Boolean
        get() = sharedPreferences.getBoolean("home_params_expanded", true)
        set(value) = sharedPreferences.edit { putBoolean("home_params_expanded", value) }

    var homePreviewExpanded: Boolean
        get() = sharedPreferences.getBoolean("home_preview_expanded", true)
        set(value) = sharedPreferences.edit { putBoolean("home_preview_expanded", value) }

    var watermarkPreviewExpanded: Boolean
        get() = sharedPreferences.getBoolean("watermark_preview_expanded", true)
        set(value) = sharedPreferences.edit { putBoolean("watermark_preview_expanded", value) }

    var homeTetheredExpanded: Boolean
        get() = sharedPreferences.getBoolean("home_tethered_expanded", true)
        set(value) = sharedPreferences.edit { putBoolean("home_tethered_expanded", value) }

    // 胶片颗粒设置折叠状态
    var filmGrainAdvancedExpanded: Boolean
        get() = sharedPreferences.getBoolean("film_grain_advanced_expanded", false)
        set(value) = sharedPreferences.edit { putBoolean("film_grain_advanced_expanded", value) }

    // 处理器选择设置
    var processorType: String
        get() = sharedPreferences.getString("processor_type", "auto") ?: "auto"
        set(value) = sharedPreferences.edit { putString("processor_type", value) }

    // 保持原始分辨率设置
    var keepOriginalResolution: Boolean
        get() = sharedPreferences.getBoolean("keep_original_resolution", false)
        set(value) = sharedPreferences.edit { putBoolean("keep_original_resolution", value) }

    // 监控开关状态 - 修复：默认值改为false，防止重启后自动开启
    var monitoringSwitchEnabled: Boolean
        get() {
            val value = sharedPreferences.getBoolean("monitoring_switch_enabled", false)
            Log.d("PreferencesManager", "读取 monitoringSwitchEnabled: $value")
            return value
        }
        set(value) {
            Log.d("PreferencesManager", "设置 monitoringSwitchEnabled: $value")
            sharedPreferences.edit { putBoolean("monitoring_switch_enabled", value) }
        }

    // 仅处理新增文件的开关状态
    var processNewFilesOnly: Boolean
        get() = sharedPreferences.getBoolean("process_new_files_only", false)
        set(value) = sharedPreferences.edit { putBoolean("process_new_files_only", value) }

    // Logcat 捕获开关状态
    var logcatCaptureEnabled: Boolean
        get() = sharedPreferences.getBoolean("logcat_capture_enabled", false)
        set(value) = sharedPreferences.edit { putBoolean("logcat_capture_enabled", value) }

    /**
     * 清除所有状态为"SKIPPED"的已跳过记录，保留正常处理过的记录
     */
    fun clearSkippedRecords(context: Context) {
        val prefs = context.getSharedPreferences("processing_history", Context.MODE_PRIVATE)
        val existingRecords = prefs.getStringSet("records", emptySet())?.toMutableSet() 
            ?: mutableSetOf()
        
        // 过滤掉状态为"SKIPPED"的记录
        val filteredRecords = existingRecords.filter { recordStr ->
            try {
                val parts = recordStr.split("|")
                // 保留状态不是"SKIPPED"的记录
                parts.size >= 5 && parts[4] != "SKIPPED"
            } catch (_: Exception) {
                true // 如果解析失败，保留该记录
            }
        }.toSet()
        
        // 保存过滤后的记录
        prefs.edit { putStringSet("records", filteredRecords) }
        
        Log.d("PreferencesManager", "已清除 ${existingRecords.size - filteredRecords.size} 条已跳过记录")
    }

    // 水印配置设置
    fun saveWatermarkConfig(config: WatermarkConfig, forFolderMonitor: Boolean? = null) {
        sharedPreferences.edit {
            putBoolean("watermark_enabled", config.isEnabled)
            
            // 如果指定了forFolderMonitor参数，则同时更新对应页面的开关状态
            when (forFolderMonitor) {
                true -> putBoolean("folder_monitor_watermark_enabled", config.isEnabled)
                false -> putBoolean("dashboard_watermark_enabled", config.isEnabled)
                null -> {
                    // 如果没有指定，则不更新页面特定的开关（保持向后兼容）
                }
            }
            
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

            // 新增文字跟随模式配置
            putBoolean("watermark_enable_text_follow_mode", config.enableTextFollowMode)
            putString("watermark_text_follow_direction", config.textFollowDirection.name)

            // 新增边框颜色模式配置
            putString("watermark_border_color_mode", config.borderColorMode.name)
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
            sharedPreferences.getString("watermark_text_alignment", "CENTER") ?: "CENTER"
        val textAlignment = try {
            cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.valueOf(textAlignmentName)
        } catch (e: IllegalArgumentException) {
            cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.CENTER
        }

        // 读取文字跟随模式配置
        val textFollowDirectionName =
            sharedPreferences.getString("watermark_text_follow_direction", "BOTTOM") ?: "BOTTOM"
        val textFollowDirection = try {
            cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection.valueOf(
                textFollowDirectionName
            )
        } catch (e: IllegalArgumentException) {
            cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection.BOTTOM
        }

        // 读取边框颜色模式配置
        val borderColorModeName =
            sharedPreferences.getString("watermark_border_color_mode", "MANUAL") ?: "MANUAL"
        val borderColorMode = try {
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.valueOf(
                borderColorModeName
            )
        } catch (e: IllegalArgumentException) {
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL
        }
            
        return WatermarkConfig(
            isEnabled = isEnabled,
            enableTextWatermark = sharedPreferences.getBoolean("watermark_enable_text", true),
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
                
            textSize = sharedPreferences.getFloat("watermark_text_size", 35f),
            imageSize = sharedPreferences.getFloat("watermark_image_size", 10f),
            textContent = sharedPreferences.getString(
                "watermark_text_content",
                "拍摄参数：ISO {ISO} 光圈 f/{APERTURE} 快门 {SHUTTER}"
            ) ?: "拍摄参数：ISO {ISO} 光圈 f/{APERTURE} 快门 {SHUTTER}",
            textColor = sharedPreferences.getString("watermark_text_color", "#FFFFFF") ?: "#FFFFFF",
            fontPath = sharedPreferences.getString("watermark_font_path", "") ?: "",
            textAlignment = textAlignment,
            imagePath = sharedPreferences.getString("watermark_image_path", "") ?: "",
            enableTextFollowMode = sharedPreferences.getBoolean(
                "watermark_enable_text_follow_mode",
                false
            ),
            textFollowDirection = textFollowDirection,
            textImageSpacing = sharedPreferences.getFloat("watermark_text_image_spacing", 100f),
            borderTopWidth = sharedPreferences.getFloat("watermark_border_top_width", 0f),
            borderBottomWidth = sharedPreferences.getFloat("watermark_border_bottom_width", 0f),
            borderLeftWidth = sharedPreferences.getFloat("watermark_border_left_width", 0f),
            borderRightWidth = sharedPreferences.getFloat("watermark_border_right_width", 0f),
            borderColor = sharedPreferences.getString("watermark_border_color", "#000000")
                ?: "#000000",
            letterSpacing = sharedPreferences.getFloat("watermark_letter_spacing", 0f),
            lineSpacing = sharedPreferences.getFloat("watermark_line_spacing", 0f),
            borderColorMode = borderColorMode
        )
    }
    
    // ========== 胶片颗粒配置 ==========
    
    /**
     * 保存胶片颗粒配置
     */
    fun saveFilmGrainConfig(config: cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig) {
        sharedPreferences.edit {
            putBoolean("grain_enabled", config.isEnabled)
            putFloat("grain_global_strength", config.globalStrength)
            putFloat("grain_size", config.grainSize)
            putFloat("grain_sharpness", config.grainSharpness)
            putFloat("grain_anisotropy", config.anisotropy)
            putFloat("grain_direction_variation", config.directionVariation)
            putInt("grain_shadow_threshold", config.shadowThreshold)
            putInt("grain_highlight_threshold", config.highlightThreshold)
            putFloat("grain_shadow_ratio", config.shadowGrainRatio)
            putFloat("grain_midtone_ratio", config.midtoneGrainRatio)
            putFloat("grain_highlight_ratio", config.highlightGrainRatio)
            putFloat("grain_shadow_size_ratio", config.shadowSizeRatio)
            putFloat("grain_midtone_size_ratio", config.midtoneSizeRatio)
            putFloat("grain_highlight_size_ratio", config.highlightSizeRatio)
            putFloat("grain_red_channel_ratio", config.redChannelRatio)
            putFloat("grain_green_channel_ratio", config.greenChannelRatio)
            putFloat("grain_blue_channel_ratio", config.blueChannelRatio)
            putFloat("grain_channel_correlation", config.channelCorrelation)
            putFloat("grain_color_preservation", config.colorPreservation)
        }
        Log.d("PreferencesManager", "保存胶片颗粒配置: enabled=${config.isEnabled}, strength=${config.globalStrength}")
    }
    
    /**
     * 获取胶片颗粒配置
     */
    fun getFilmGrainConfig(): cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig {
        return cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig(
            isEnabled = sharedPreferences.getBoolean("grain_enabled", false),
            globalStrength = sharedPreferences.getFloat("grain_global_strength", 0.5f),
            grainSize = sharedPreferences.getFloat("grain_size", 1.0f),
            grainSharpness = sharedPreferences.getFloat("grain_sharpness", 0.7f),
            anisotropy = sharedPreferences.getFloat("grain_anisotropy", 0.3f),
            directionVariation = sharedPreferences.getFloat("grain_direction_variation", 15f),
            shadowThreshold = sharedPreferences.getInt("grain_shadow_threshold", 60),
            highlightThreshold = sharedPreferences.getInt("grain_highlight_threshold", 180),
            shadowGrainRatio = sharedPreferences.getFloat("grain_shadow_ratio", 0.6f),
            midtoneGrainRatio = sharedPreferences.getFloat("grain_midtone_ratio", 1.0f),
            highlightGrainRatio = sharedPreferences.getFloat("grain_highlight_ratio", 0.3f),
            shadowSizeRatio = sharedPreferences.getFloat("grain_shadow_size_ratio", 1.5f),
            midtoneSizeRatio = sharedPreferences.getFloat("grain_midtone_size_ratio", 1.0f),
            highlightSizeRatio = sharedPreferences.getFloat("grain_highlight_size_ratio", 0.6f),
            redChannelRatio = sharedPreferences.getFloat("grain_red_channel_ratio", 0.9f),
            greenChannelRatio = sharedPreferences.getFloat("grain_green_channel_ratio", 1.0f),
            blueChannelRatio = sharedPreferences.getFloat("grain_blue_channel_ratio", 1.2f),
            channelCorrelation = sharedPreferences.getFloat("grain_channel_correlation", 0.9f),
            colorPreservation = sharedPreferences.getFloat("grain_color_preservation", 0.95f)
        )
    }
    
    // 胶片颗粒开关状态（手动处理页面）
    var dashboardGrainEnabled: Boolean
        get() = sharedPreferences.getBoolean("dashboard_grain_enabled", false)
        set(value) = sharedPreferences.edit { putBoolean("dashboard_grain_enabled", value) }
    
    // 胶片颗粒开关状态（文件夹监控）
    var folderMonitorGrainEnabled: Boolean
        get() = sharedPreferences.getBoolean("folder_monitor_grain_enabled", false)
        set(value) = sharedPreferences.edit { putBoolean("folder_monitor_grain_enabled", value) }
}