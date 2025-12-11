package cn.alittlecookie.lut2photo.lut2photo.ui.bottomsheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager

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
    
    companion object {
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
        
        initViews(view)
        loadCurrentConfig()
        setupListeners()
    }
    
    private fun initViews(view: View) {
        sliderGlobalStrength = view.findViewById(R.id.slider_global_strength)
        textGlobalStrength = view.findViewById(R.id.text_global_strength)
        sliderGrainSize = view.findViewById(R.id.slider_grain_size)
        textGrainSize = view.findViewById(R.id.text_grain_size)
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
    }
    
    private fun loadCurrentConfig() {
        val config = preferencesManager.getFilmGrainConfig()
        
        sliderGlobalStrength.value = (config.globalStrength * 100).coerceIn(0f, 100f)
        textGlobalStrength.text = "${(config.globalStrength * 100).toInt()}%"
        
        sliderGrainSize.value = config.grainSize.coerceIn(0.5f, 3.0f)
        textGrainSize.text = String.format("%.1f", config.grainSize)
        
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
    }
    
    private fun saveConfig() {
        val config = FilmGrainConfig(
            isEnabled = true,
            globalStrength = sliderGlobalStrength.value / 100f,
            grainSize = sliderGrainSize.value,
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
    }
    
    private fun resetToDefault() {
        val defaultConfig = FilmGrainConfig.default()
        
        sliderGlobalStrength.value = defaultConfig.globalStrength * 100
        sliderGrainSize.value = defaultConfig.grainSize
        sliderShadowRatio.value = defaultConfig.shadowGrainRatio
        sliderHighlightRatio.value = defaultConfig.highlightGrainRatio
        sliderShadowSizeRatio.value = defaultConfig.shadowSizeRatio
        sliderHighlightSizeRatio.value = defaultConfig.highlightSizeRatio
        sliderRedChannel.value = defaultConfig.redChannelRatio
        sliderBlueChannel.value = defaultConfig.blueChannelRatio
        sliderCorrelation.value = defaultConfig.channelCorrelation
        sliderColorPreservation.value = defaultConfig.colorPreservation
    }
}
