package cn.alittlecookie.lut2photo.lut2photo.ui.bottomsheet

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 胶片颗粒设置底部弹窗
 */
class FilmGrainSettingsBottomSheet : BottomSheetDialogFragment() {
    
    private lateinit var preferencesManager: PreferencesManager
    private var onConfigChanged: ((FilmGrainConfig) -> Unit)? = null
    
    // UI组件
    private lateinit var sliderGlobalStrength: Slider
    private lateinit var textGlobalStrength: TextView
    private lateinit var sliderGrainSize: Slider
    private lateinit var textGrainSize: TextView
    private lateinit var rangeSliderTonal: com.google.android.material.slider.RangeSlider
    private lateinit var textTonalRange: TextView
    private lateinit var sliderShadowRatio: Slider
    private lateinit var textShadowRatio: TextView
    private lateinit var sliderHighlightRatio: Slider
    private lateinit var textHighlightRatio: TextView
    private lateinit var sliderShadowSizeRatio: Slider
    private lateinit var textShadowSizeRatio: TextView
    private lateinit var sliderHighlightSizeRatio: Slider
    private lateinit var textHighlightSizeRatio: TextView
    private lateinit var sliderRedChannel: Slider
    private lateinit var textRedChannel: TextView
    private lateinit var sliderBlueChannel: Slider
    private lateinit var textBlueChannel: TextView
    private lateinit var sliderCorrelation: Slider
    private lateinit var textCorrelation: TextView
    private lateinit var sliderColorPreservation: Slider
    private lateinit var textColorPreservation: TextView
    private lateinit var buttonSave: MaterialButton
    private lateinit var buttonReset: MaterialButton
    private lateinit var buttonCancel: MaterialButton
    private lateinit var buttonImport: MaterialButton
    private lateinit var buttonExport: MaterialButton
    private lateinit var scrollViewSettings: ScrollView
    private lateinit var layoutHeader: View
    private lateinit var layoutAdvancedHeader: View
    private lateinit var layoutAdvancedContent: View
    private lateinit var buttonToggleAdvanced: ImageView
    
    // 保存初始配置，用于取消时恢复
    private var initialConfig: FilmGrainConfig? = null
    
    // Activity Result Launchers
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportConfigToUri(it) }
    }
    
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importConfigFromUri(it) }
    }
    
    companion object {
        private const val TAG = "FilmGrainSettings"
        
        fun newInstance(onConfigChanged: ((FilmGrainConfig) -> Unit)? = null): FilmGrainSettingsBottomSheet {
            return FilmGrainSettingsBottomSheet().apply {
                this.onConfigChanged = onConfigChanged
            }
        }
    }

    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottomsheet_film_grain_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        preferencesManager = PreferencesManager(requireContext())
        
        // 保存初始配置
        initialConfig = preferencesManager.getFilmGrainConfig()
        
        initViews(view)
        loadCurrentConfig()
        setupListeners()
        setupDragBehavior()
    }
    
    /**
     * 设置拖拽行为：全屏显示，只允许标题栏拖拽，禁用其他区域的滑动关闭
     */
    private fun setupDragBehavior() {
        dialog?.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as? BottomSheetDialog
            val bottomSheet = bottomSheetDialog?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                
                // 设置为全屏展开
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.peekHeight = 0
                
                // 禁用滑动关闭
                behavior.isDraggable = false
                
                // 设置全屏高度
                val layoutParams = it.layoutParams
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                it.layoutParams = layoutParams
                
                // 只允许标题栏拖拽
                layoutHeader.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            behavior.isDraggable = true
                            false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            behavior.isDraggable = false
                            false
                        }
                        else -> false
                    }
                }
                
                // 禁用ScrollView区域的拖拽
                scrollViewSettings.setOnTouchListener { v, event ->
                    behavior.isDraggable = false
                    false
                }
            }
        }
    }
    
    private fun initViews(view: View) {
        layoutHeader = view.findViewById(R.id.layout_header)
        scrollViewSettings = view.findViewById(R.id.scroll_view_settings)
        sliderGlobalStrength = view.findViewById(R.id.slider_global_strength)
        textGlobalStrength = view.findViewById(R.id.text_global_strength)
        sliderGrainSize = view.findViewById(R.id.slider_grain_size)
        textGrainSize = view.findViewById(R.id.text_grain_size)
        rangeSliderTonal = view.findViewById(R.id.range_slider_tonal)
        textTonalRange = view.findViewById(R.id.text_tonal_range)
        sliderShadowRatio = view.findViewById(R.id.slider_shadow_ratio)
        textShadowRatio = view.findViewById(R.id.text_shadow_ratio)
        sliderHighlightRatio = view.findViewById(R.id.slider_highlight_ratio)
        textHighlightRatio = view.findViewById(R.id.text_highlight_ratio)
        sliderShadowSizeRatio = view.findViewById(R.id.slider_shadow_size_ratio)
        textShadowSizeRatio = view.findViewById(R.id.text_shadow_size_ratio)
        sliderHighlightSizeRatio = view.findViewById(R.id.slider_highlight_size_ratio)
        textHighlightSizeRatio = view.findViewById(R.id.text_highlight_size_ratio)
        sliderRedChannel = view.findViewById(R.id.slider_red_channel)
        textRedChannel = view.findViewById(R.id.text_red_channel)
        sliderBlueChannel = view.findViewById(R.id.slider_blue_channel)
        textBlueChannel = view.findViewById(R.id.text_blue_channel)
        sliderCorrelation = view.findViewById(R.id.slider_correlation)
        textCorrelation = view.findViewById(R.id.text_correlation)
        sliderColorPreservation = view.findViewById(R.id.slider_color_preservation)
        textColorPreservation = view.findViewById(R.id.text_color_preservation)
        buttonSave = view.findViewById(R.id.button_save)
        buttonReset = view.findViewById(R.id.button_reset)
        buttonCancel = view.findViewById(R.id.button_cancel)
        buttonImport = view.findViewById(R.id.button_import)
        buttonExport = view.findViewById(R.id.button_export)
        layoutAdvancedHeader = view.findViewById(R.id.layout_advanced_header)
        layoutAdvancedContent = view.findViewById(R.id.layout_advanced_content)
        buttonToggleAdvanced = view.findViewById(R.id.button_toggle_advanced)
        
        // 恢复高级参数的折叠状态
        val isExpanded = preferencesManager.filmGrainAdvancedExpanded
        updateAdvancedSectionVisibility(isExpanded)
    }
    
    private fun loadCurrentConfig() {
        val config = preferencesManager.getFilmGrainConfig()
        
        sliderGlobalStrength.value = (config.globalStrength * 100).coerceIn(0f, 100f)
        textGlobalStrength.text = "${(config.globalStrength * 100).toInt()}%"
        
        sliderGrainSize.value = config.grainSize.coerceIn(0.5f, 6.0f)
        textGrainSize.text = String.format("%.1f", config.grainSize)
        
        // 设置影调范围
        rangeSliderTonal.values = listOf(
            config.shadowThreshold.toFloat().coerceIn(0f, 255f),
            config.highlightThreshold.toFloat().coerceIn(0f, 255f)
        )
        textTonalRange.text = "${config.shadowThreshold}-${config.highlightThreshold}"
        
        sliderShadowRatio.value = config.shadowGrainRatio.coerceIn(0.2f, 1.0f)
        textShadowRatio.text = String.format("%.2f", config.shadowGrainRatio)
        
        sliderHighlightRatio.value = config.highlightGrainRatio.coerceIn(0.1f, 0.8f)
        textHighlightRatio.text = String.format("%.2f", config.highlightGrainRatio)
        
        sliderShadowSizeRatio.value = config.shadowSizeRatio.coerceIn(1.0f, 2.0f)
        textShadowSizeRatio.text = String.format("%.1f", config.shadowSizeRatio)
        
        sliderHighlightSizeRatio.value = config.highlightSizeRatio.coerceIn(0.3f, 1.0f)
        textHighlightSizeRatio.text = String.format("%.2f", config.highlightSizeRatio)
        
        sliderRedChannel.value = config.redChannelRatio.coerceIn(0.5f, 1.5f)
        textRedChannel.text = String.format("%.2f", config.redChannelRatio)
        
        sliderBlueChannel.value = config.blueChannelRatio.coerceIn(0.8f, 1.5f)
        textBlueChannel.text = String.format("%.2f", config.blueChannelRatio)
        
        sliderCorrelation.value = config.channelCorrelation.coerceIn(0.8f, 0.95f)
        textCorrelation.text = String.format("%.2f", config.channelCorrelation)
        
        sliderColorPreservation.value = config.colorPreservation.coerceIn(0.9f, 1.0f)
        textColorPreservation.text = String.format("%.2f", config.colorPreservation)
    }

    
    private fun setupListeners() {
        sliderGlobalStrength.addOnChangeListener { _, value, _ ->
            textGlobalStrength.text = "${value.toInt()}%"
        }
        
        sliderGrainSize.addOnChangeListener { _, value, _ ->
            textGrainSize.text = String.format("%.1f", value)
        }
        
        // 影调范围滑块监听器
        rangeSliderTonal.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            if (values.size >= 2) {
                val shadowThreshold = values[0].toInt()
                val highlightThreshold = values[1].toInt()
                textTonalRange.text = "$shadowThreshold-$highlightThreshold"
            }
        }
        
        sliderShadowRatio.addOnChangeListener { _, value, _ ->
            textShadowRatio.text = String.format("%.2f", value)
        }
        
        sliderHighlightRatio.addOnChangeListener { _, value, _ ->
            textHighlightRatio.text = String.format("%.2f", value)
        }
        
        sliderShadowSizeRatio.addOnChangeListener { _, value, _ ->
            textShadowSizeRatio.text = String.format("%.1f", value)
        }
        
        sliderHighlightSizeRatio.addOnChangeListener { _, value, _ ->
            textHighlightSizeRatio.text = String.format("%.2f", value)
        }
        
        sliderRedChannel.addOnChangeListener { _, value, _ ->
            textRedChannel.text = String.format("%.2f", value)
        }
        
        sliderBlueChannel.addOnChangeListener { _, value, _ ->
            textBlueChannel.text = String.format("%.2f", value)
        }
        
        sliderCorrelation.addOnChangeListener { _, value, _ ->
            textCorrelation.text = String.format("%.2f", value)
        }
        
        sliderColorPreservation.addOnChangeListener { _, value, _ ->
            textColorPreservation.text = String.format("%.2f", value)
        }
        
        buttonSave.setOnClickListener {
            saveConfig()
            dismiss()
        }
        
        buttonReset.setOnClickListener {
            resetToDefault()
        }
        
        buttonCancel.setOnClickListener {
            // 恢复初始配置（不保存更改）
            initialConfig?.let {
                preferencesManager.saveFilmGrainConfig(it)
            }
            dismiss()
        }
        
        // 高级参数折叠/展开
        layoutAdvancedHeader.setOnClickListener {
            toggleAdvancedSection()
        }
        
        buttonImport.setOnClickListener {
            importConfig()
        }
        
        buttonExport.setOnClickListener {
            exportConfig()
        }
    }
    
    private fun saveConfig() {
        val tonalValues = rangeSliderTonal.values
        val config = FilmGrainConfig(
            isEnabled = true,
            globalStrength = sliderGlobalStrength.value / 100f,
            grainSize = sliderGrainSize.value,
            shadowThreshold = tonalValues[0].toInt(),
            highlightThreshold = tonalValues[1].toInt(),
            shadowGrainRatio = sliderShadowRatio.value,
            highlightGrainRatio = sliderHighlightRatio.value,
            shadowSizeRatio = sliderShadowSizeRatio.value,
            highlightSizeRatio = sliderHighlightSizeRatio.value,
            redChannelRatio = sliderRedChannel.value,
            blueChannelRatio = sliderBlueChannel.value,
            channelCorrelation = sliderCorrelation.value,
            colorPreservation = sliderColorPreservation.value
        )
        
        preferencesManager.saveFilmGrainConfig(config)
        onConfigChanged?.invoke(config)
        
        // 如果文件夹监控正在运行且颗粒已启用，发送广播通知服务更新配置
        if (preferencesManager.isMonitoring && preferencesManager.folderMonitorGrainEnabled) {
            val intent = android.content.Intent("cn.alittlecookie.lut2photo.GRAIN_CONFIG_CHANGED")
            intent.setPackage(requireContext().packageName)
            requireContext().sendBroadcast(intent)
            Log.d("FilmGrainSettings", "颗粒配置已保存，已发送广播通知文件夹监控服务")
        }
    }
    
    private fun resetToDefault() {
        val defaultConfig = FilmGrainConfig.default()
        
        sliderGlobalStrength.value = defaultConfig.globalStrength * 100
        sliderGrainSize.value = defaultConfig.grainSize
        rangeSliderTonal.values = listOf(
            defaultConfig.shadowThreshold.toFloat(),
            defaultConfig.highlightThreshold.toFloat()
        )
        sliderShadowRatio.value = defaultConfig.shadowGrainRatio
        sliderHighlightRatio.value = defaultConfig.highlightGrainRatio
        sliderShadowSizeRatio.value = defaultConfig.shadowSizeRatio
        sliderHighlightSizeRatio.value = defaultConfig.highlightSizeRatio
        sliderRedChannel.value = defaultConfig.redChannelRatio
        sliderBlueChannel.value = defaultConfig.blueChannelRatio
        sliderCorrelation.value = defaultConfig.channelCorrelation
        sliderColorPreservation.value = defaultConfig.colorPreservation
    }
    
    /**
     * 导出配置
     */
    private fun exportConfig() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Filmgrain_$timestamp.json"
            exportLauncher.launch(fileName)
        } catch (e: Exception) {
            Log.e(TAG, "启动导出失败", e)
            Toast.makeText(requireContext(), R.string.grain_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 导出配置到URI
     */
    private fun exportConfigToUri(uri: android.net.Uri) {
        try {
            val config = getCurrentConfig()
            val json = configToJson(config)
            
            requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(json.toString(2).toByteArray())
            }
            
            Toast.makeText(requireContext(), R.string.grain_export_success, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "配置导出成功: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "导出配置失败", e)
            Toast.makeText(requireContext(), R.string.grain_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 导入配置
     */
    private fun importConfig() {
        try {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        } catch (e: Exception) {
            Log.e(TAG, "启动导入失败", e)
            Toast.makeText(requireContext(), R.string.grain_import_failed, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 从URI导入配置
     */
    private fun importConfigFromUri(uri: android.net.Uri) {
        try {
            val jsonString = requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: throw Exception("无法读取文件")
            
            val json = JSONObject(jsonString)
            val config = jsonToConfig(json)
            
            // 应用导入的配置
            applyConfig(config)
            
            Toast.makeText(requireContext(), R.string.grain_import_success, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "配置导入成功: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "导入配置失败", e)
            Toast.makeText(requireContext(), R.string.invalid_grain_config, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 获取当前配置
     */
    private fun getCurrentConfig(): FilmGrainConfig {
        val tonalValues = rangeSliderTonal.values
        return FilmGrainConfig(
            isEnabled = true,
            globalStrength = sliderGlobalStrength.value / 100f,
            grainSize = sliderGrainSize.value,
            shadowThreshold = tonalValues[0].toInt(),
            highlightThreshold = tonalValues[1].toInt(),
            shadowGrainRatio = sliderShadowRatio.value,
            midtoneGrainRatio = 1.0f, // 固定值
            highlightGrainRatio = sliderHighlightRatio.value,
            shadowSizeRatio = sliderShadowSizeRatio.value,
            highlightSizeRatio = sliderHighlightSizeRatio.value,
            redChannelRatio = sliderRedChannel.value,
            greenChannelRatio = 1.0f, // 固定值
            blueChannelRatio = sliderBlueChannel.value,
            channelCorrelation = sliderCorrelation.value,
            colorPreservation = sliderColorPreservation.value
        )
    }
    
    /**
     * 应用配置到UI
     */
    private fun applyConfig(config: FilmGrainConfig) {
        sliderGlobalStrength.value = (config.globalStrength * 100).coerceIn(0f, 100f)
        sliderGrainSize.value = config.grainSize.coerceIn(0.5f, 6.0f)
        rangeSliderTonal.values = listOf(
            config.shadowThreshold.toFloat().coerceIn(0f, 255f),
            config.highlightThreshold.toFloat().coerceIn(0f, 255f)
        )
        sliderShadowRatio.value = config.shadowGrainRatio.coerceIn(0.2f, 1.0f)
        sliderHighlightRatio.value = config.highlightGrainRatio.coerceIn(0.1f, 0.8f)
        sliderShadowSizeRatio.value = config.shadowSizeRatio.coerceIn(1.0f, 2.0f)
        sliderHighlightSizeRatio.value = config.highlightSizeRatio.coerceIn(0.3f, 1.0f)
        sliderRedChannel.value = config.redChannelRatio.coerceIn(0.5f, 1.5f)
        sliderBlueChannel.value = config.blueChannelRatio.coerceIn(0.8f, 1.5f)
        sliderCorrelation.value = config.channelCorrelation.coerceIn(0.8f, 0.95f)
        sliderColorPreservation.value = config.colorPreservation.coerceIn(0.9f, 1.0f)
    }
    
    /**
     * 配置转JSON
     */
    private fun configToJson(config: FilmGrainConfig): JSONObject {
        return JSONObject().apply {
            put("version", 1)
            put("globalStrength", config.globalStrength)
            put("grainSize", config.grainSize)
            put("shadowThreshold", config.shadowThreshold)
            put("highlightThreshold", config.highlightThreshold)
            put("shadowGrainRatio", config.shadowGrainRatio)
            put("midtoneGrainRatio", config.midtoneGrainRatio)
            put("highlightGrainRatio", config.highlightGrainRatio)
            put("shadowSizeRatio", config.shadowSizeRatio)
            put("highlightSizeRatio", config.highlightSizeRatio)
            put("redChannelRatio", config.redChannelRatio)
            put("greenChannelRatio", config.greenChannelRatio)
            put("blueChannelRatio", config.blueChannelRatio)
            put("channelCorrelation", config.channelCorrelation)
            put("colorPreservation", config.colorPreservation)
        }
    }
    
    /**
     * JSON转配置
     */
    private fun jsonToConfig(json: JSONObject): FilmGrainConfig {
        return FilmGrainConfig(
            isEnabled = true,
            globalStrength = json.optDouble("globalStrength", 0.5).toFloat(),
            grainSize = json.optDouble("grainSize", 1.0).toFloat(),
            shadowThreshold = json.optInt("shadowThreshold", 85),
            highlightThreshold = json.optInt("highlightThreshold", 170),
            shadowGrainRatio = json.optDouble("shadowGrainRatio", 0.6).toFloat(),
            midtoneGrainRatio = json.optDouble("midtoneGrainRatio", 1.0).toFloat(),
            highlightGrainRatio = json.optDouble("highlightGrainRatio", 0.3).toFloat(),
            shadowSizeRatio = json.optDouble("shadowSizeRatio", 1.5).toFloat(),
            highlightSizeRatio = json.optDouble("highlightSizeRatio", 0.6).toFloat(),
            redChannelRatio = json.optDouble("redChannelRatio", 0.9).toFloat(),
            greenChannelRatio = json.optDouble("greenChannelRatio", 1.0).toFloat(),
            blueChannelRatio = json.optDouble("blueChannelRatio", 1.2).toFloat(),
            channelCorrelation = json.optDouble("channelCorrelation", 0.9).toFloat(),
            colorPreservation = json.optDouble("colorPreservation", 0.95).toFloat()
        )
    }
    
    /**
     * 切换高级参数区域的展开/折叠状态
     */
    private fun toggleAdvancedSection() {
        val isCurrentlyVisible = layoutAdvancedContent.visibility == View.VISIBLE
        val newVisibility = !isCurrentlyVisible
        updateAdvancedSectionVisibility(newVisibility)
        preferencesManager.filmGrainAdvancedExpanded = newVisibility
    }
    
    /**
     * 更新高级参数区域的可见性
     */
    private fun updateAdvancedSectionVisibility(isExpanded: Boolean) {
        layoutAdvancedContent.visibility = if (isExpanded) View.VISIBLE else View.GONE
        buttonToggleAdvanced.setImageResource(
            if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
    }
}
