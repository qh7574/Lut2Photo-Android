package cn.alittlecookie.lut2photo.ui.bottomsheet

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import cn.alittlecookie.lut2photo.lut2photo.databinding.BottomsheetWatermarkSettingsBinding
import cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment
import cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import cn.alittlecookie.lut2photo.lut2photo.utils.WatermarkConfigManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jfenn.colorpickerdialog.dialogs.ColorPickerDialog
import me.jfenn.colorpickerdialog.views.picker.ImagePickerView
import java.io.File
import java.io.FileOutputStream

class WatermarkSettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetWatermarkSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var watermarkConfigManager: WatermarkConfigManager
    private var onConfigSaved: ((WatermarkConfig) -> Unit)? = null
    private var forFolderMonitor: Boolean = false  // 标识是否为文件夹监控页面调用

    private lateinit var fontPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var backgroundImagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var paletteImagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportLauncher: ActivityResultLauncher<Intent>
    private lateinit var importLauncher: ActivityResultLauncher<Intent>

    private var selectedFontPath: String? = null
    private var selectedImagePath: String? = null
    private var selectedBackgroundImagePath: String? = null
    private var paletteColors: List<Int> = emptyList()
    private var isManualColorMode = true

    // LUT相关参数
    private var lut1Name: String? = null
    private var lut2Name: String? = null
    private var lut1Strength: Float? = null
    private var lut2Strength: Float? = null
    
    // 预览图片URI
    private var previewImageUri: Uri? = null

    companion object {
        fun newInstance(
            onConfigSaved: (WatermarkConfig) -> Unit,
            forFolderMonitor: Boolean = false,  // 新增参数，标识调用来源
            lut1Name: String? = null,
            lut2Name: String? = null,
            lut1Strength: Float? = null,
            lut2Strength: Float? = null,
            previewImageUri: Uri? = null  // 新增参数，预览图片URI
        ): WatermarkSettingsBottomSheet {
            return WatermarkSettingsBottomSheet().apply {
                this.onConfigSaved = onConfigSaved
                this.forFolderMonitor = forFolderMonitor
                this.lut1Name = lut1Name
                this.lut2Name = lut2Name
                this.lut1Strength = lut1Strength
                this.lut2Strength = lut2Strength
                this.previewImageUri = previewImageUri
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(requireContext())
        watermarkConfigManager = WatermarkConfigManager(requireContext())

        // 初始化文件选择器
        fontPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleFontSelection(uri)
                }
            }
        }

        imagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleImageSelection(uri)
                }
            }
        }

        backgroundImagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleBackgroundImageSelection(uri)
                }
            }
        }

        paletteImagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handlePaletteImageSelection(uri)
                }
            }
        }

        // 导出配置启动器
        exportLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleExportConfig(uri)
                }
            }
        }

        // 导入配置启动器
        importLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleImportConfig(uri)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetWatermarkSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()

        // 设置BottomSheet的行为
        val dialog = dialog
        if (dialog != null) {
            // 同步导航栏颜色
            val window = dialog.window
            if (window != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val context = requireContext()
                val typedValue = android.util.TypedValue()
                val theme = context.theme

                // 获取当前主题的颜色
                if (theme.resolveAttribute(
                        com.google.android.material.R.attr.colorSurfaceVariant,
                        typedValue,
                        true
                    )
                ) {
                    window.navigationBarColor = typedValue.data
                }

                // 设置导航栏按钮颜色
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (theme.resolveAttribute(
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            typedValue,
                            true
                        )
                    ) {
                        val isLightColor = isLightColor(typedValue.data)
                        window.decorView.systemUiVisibility = if (isLightColor) {
                            window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        } else {
                            window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                        }
                    }
                }
            }

            val bottomSheet =
                dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                // 设置为展开状态
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                // 允许拖拽，但只在顶部区域生效
                behavior.isDraggable = true
                // 设置峰值高度为最大高度，使其全屏显示
                behavior.peekHeight = resources.displayMetrics.heightPixels
                // 禁用自适应内容高度，启用半展开模式
                behavior.isFitToContents = false
                behavior.skipCollapsed = true
                // 设置拖拽阈值，只有当拖拽距离超过一定阈值才会关闭
                behavior.halfExpandedRatio = 0.9f

                // 设置拖拽回调，更精确地控制拖拽行为
                behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    private var lastSlideOffset = 1.0f
                    private var isDraggingDown = false
                    
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        // 只允许展开状态，不允许其他状态
                        if (newState == BottomSheetBehavior.STATE_COLLAPSED ||
                            newState == BottomSheetBehavior.STATE_HIDDEN
                        ) {
                            dismiss()
                        } else if (newState == BottomSheetBehavior.STATE_DRAGGING) {
                            // 开始拖拽时重置状态
                            isDraggingDown = false
                        } else if (newState == BottomSheetBehavior.STATE_SETTLING) {
                            // 停止拖拽时重置状态
                            isDraggingDown = false
                        }
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        // 修复：只有在持续向下拖拽且偏移量小于阈值时才关闭
                        // 检测是否在向下拖拽
                        if (slideOffset < lastSlideOffset) {
                            isDraggingDown = true
                        } else if (slideOffset > lastSlideOffset) {
                            // 如果开始向上拖拽，重置标志
                            isDraggingDown = false
                        }
                        
                        // 只有在持续向下拖拽且偏移量小于0.3时才关闭
                        // 0.3是一个更保守的阈值，避免误触
                        if (isDraggingDown && slideOffset < 0.3f) {
                            dismiss()
                        }
                        
                        lastSlideOffset = slideOffset
                    }
                })
            }
        }
    }



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
        loadSavedSettings()
        loadPreviewImage()
    }
    
    /**
     * 加载预览图片
     * 根据来源自动加载合适的预览图片
     */
    private fun loadPreviewImage() {
        lifecycleScope.launch {
            try {
                val uri = when {
                    // 如果传入了预览图片URI，直接使用
                    previewImageUri != null -> previewImageUri
                    
                    // 如果是从文件夹监控进入，读取输入文件夹中的第一张图片
                    forFolderMonitor -> {
                        val inputFolder = preferencesManager.homeInputFolder
                        if (inputFolder.isNotEmpty()) {
                            getFirstImageFromFolder(inputFolder)
                        } else null
                    }
                    
                    // 否则不加载（显示占位图）
                    else -> null
                }
                
                // 如果有URI，加载图片
                uri?.let { imageUri ->
                    val memoryOptimizer = cn.alittlecookie.lut2photo.lut2photo.utils.MemoryOptimizer(requireContext())
                    val bitmap = memoryOptimizer.loadOptimizedBitmap(imageUri)
                    
                    if (bitmap != null) {
                        binding.watermarkPreview.setBackgroundImage(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e("WatermarkSettings", "加载预览图片失败", e)
                // 失败时不显示错误，保持占位图
            }
        }
    }
    
    /**
     * 从文件夹中获取第一张图片的URI
     */
    private suspend fun getFirstImageFromFolder(folderPath: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val folder = File(folderPath)
            if (!folder.exists() || !folder.isDirectory) return@withContext null
            
            val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "heic", "heif")
            val firstImage = folder.listFiles()?.firstOrNull { file ->
                file.isFile && imageExtensions.any { ext ->
                    file.name.lowercase().endsWith(".$ext")
                }
            }
            
            firstImage?.let { Uri.fromFile(it) }
        } catch (e: Exception) {
            Log.e("WatermarkSettings", "读取文件夹图片失败", e)
            null
        }
    }

    private fun setupViews() {
        // 为ScrollView设置触摸监听器，阻止拖拽事件传递给BottomSheet
        binding.scrollviewContent.setOnTouchListener { view, event ->
            // 告诉父视图不要拦截这个触摸事件
            view.parent.requestDisallowInterceptTouchEvent(true)
            false // 不消耗事件，让ScrollView正常处理滚动
        }

        // 为整个内容区域设置触摸监听器，阻止拖拽传递
        binding.scrollviewContent.viewTreeObserver.addOnGlobalLayoutListener {
            val contentLayout = binding.scrollviewContent.getChildAt(0)
            contentLayout?.setOnTouchListener { view, event ->
                // 当触摸内容区域时，禁止拖拽事件传递
                view.parent.parent.requestDisallowInterceptTouchEvent(true)

                // 对于所有子视图，也设置相同的触摸处理
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        child.setOnTouchListener { childView, _ ->
                            childView.parent.parent.parent.requestDisallowInterceptTouchEvent(true)
                            false
                        }
                    }
                }
                false
            }
        }

        // 设置水印类型 Segmented Button 监听器
        binding.toggleGroupWatermarkType.addOnButtonCheckedListener { group, checkedId, isChecked ->
            when (checkedId) {
                binding.buttonTextWatermark.id -> {
                    binding.cardTextSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
                }

                binding.buttonImageWatermark.id -> {
                    binding.cardImageSettings.visibility =
                        if (isChecked) View.VISIBLE else View.GONE
                }
            }
        }

        // 设置文本对齐方式 Segmented Button 监听器
        binding.toggleGroupTextAlignment.addOnButtonCheckedListener { group, checkedId, isChecked ->
            // 单选模式，不需要额外处理
        }

        // 设置文字跟随模式开关监听器
        binding.switchTextFollowMode.setOnCheckedChangeListener { _, isChecked ->
            updateTextFollowModeVisibility(isChecked)
        }

        // 设置文字跟随方向 Segmented Button 监听器
        binding.toggleGroupTextFollowDirection.addOnButtonCheckedListener { group, checkedId, isChecked ->
            // 单选模式，不需要额外处理
        }

        // 文字水印位置设置滑块监听器
        binding.sliderTextPositionX.addOnChangeListener { _, value, _ ->
            binding.textTextPositionXValue.text = "${value.toInt()}%"
        }

        // 为滑块设置触摸监听器，防止拖拽时关闭bottomsheet
        binding.sliderTextPositionX.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.sliderTextPositionY.addOnChangeListener { _, value, _ ->
            binding.textTextPositionYValue.text = "${value.toInt()}%"
        }

        binding.sliderTextPositionY.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        // 文字水印透明度设置滑块监听器
        binding.sliderTextOpacity.addOnChangeListener { _, value, _ ->
            binding.textTextOpacityValue.text = "${value.toInt()}%"
        }

        binding.sliderTextOpacity.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        // 图片水印位置设置滑块监听器
        binding.sliderImagePositionX.addOnChangeListener { _, value, _ ->
            binding.textImagePositionXValue.text = "${value.toInt()}%"
        }

        binding.sliderImagePositionX.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.sliderImagePositionY.addOnChangeListener { _, value, _ ->
            binding.textImagePositionYValue.text = "${value.toInt()}%"
        }

        binding.sliderImagePositionY.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        // 图片水印透明度设置滑块监听器
        binding.sliderImageOpacity.addOnChangeListener { _, value, _ ->
            binding.textImageOpacityValue.text = "${value.toInt()}%"
        }

        binding.sliderImageOpacity.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.sliderTextSize.addOnChangeListener { _, value, _ ->
            binding.textTextSizeValue.text = "${String.format("%.1f", value)}%"
        }

        binding.sliderTextSize.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.sliderImageSize.addOnChangeListener { _, value, _ ->
            binding.textImageSizeValue.text = "${value.toInt()}%"
        }

        binding.sliderImageSize.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        // 四个方向的边框滑块监听器
        binding.sliderBorderTopWidth.addOnChangeListener { _, value, _ ->
            binding.textBorderTopWidthValue.text = "${value.toInt()}%"
        }

        binding.sliderBorderTopWidth.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.sliderBorderBottomWidth.addOnChangeListener { _, value, _ ->
            binding.textBorderBottomWidthValue.text = "${value.toInt()}%"
        }

        binding.sliderBorderBottomWidth.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.sliderBorderLeftWidth.addOnChangeListener { _, value, _ ->
            binding.textBorderLeftWidthValue.text = "${value.toInt()}%"
        }

        binding.sliderBorderLeftWidth.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.sliderBorderRightWidth.addOnChangeListener { _, value, _ ->
            binding.textBorderRightWidthValue.text = "${value.toInt()}%"
        }

        binding.sliderBorderRightWidth.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        // 新增字间距和行间距滑块监听器
        binding.sliderLetterSpacing.addOnChangeListener { _, value, _ ->
            binding.textLetterSpacingValue.text = "${value.toInt()}%"
        }

        binding.sliderLetterSpacing.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        binding.sliderLineSpacing.addOnChangeListener { _, value, _ ->
            binding.textLineSpacingValue.text = "${value.toInt()}%"
        }

        binding.sliderLineSpacing.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        // 添加图片文字间距滑块监听器
        binding.sliderTextImageSpacing.addOnChangeListener { _, value, _ ->
            binding.textTextImageSpacingValue.text = "${value.toInt()}%"
        }

        binding.sliderTextImageSpacing.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        // 设置按钮监听器
        binding.buttonSelectFont.setOnClickListener {
            selectFontFile()
        }

        binding.buttonResetFont.setOnClickListener {
            resetToDefaultFont()
        }

        binding.buttonSelectImage.setOnClickListener {
            selectImageFile()
        }

        // 设置颜色选择按钮监听器
        binding.buttonTextColor.setOnClickListener {
            showTextColorPicker()
        }

        binding.buttonBorderColor.setOnClickListener {
            showBorderColorPicker()
        }

        // 设置边框颜色模式切换监听器
        binding.toggleBorderColorMode.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.buttonManualColor.id -> {
                        isManualColorMode = true
                        binding.layoutManualColor.visibility = View.VISIBLE
                        binding.layoutAutoColor.visibility = View.GONE
                        binding.layoutMaterialColor.visibility = View.GONE
                    }

                    binding.buttonAutoColor.id -> {
                        isManualColorMode = false
                        binding.layoutManualColor.visibility = View.GONE
                        binding.layoutAutoColor.visibility = View.VISIBLE
                        binding.layoutMaterialColor.visibility = View.GONE
                    }

                    binding.buttonMaterialColor.id -> {
                        isManualColorMode = false
                        binding.layoutManualColor.visibility = View.GONE
                        binding.layoutAutoColor.visibility = View.GONE
                        binding.layoutMaterialColor.visibility = View.VISIBLE
                    }
                }
            }
        }

        // 移除Palette图片选择按钮监听器，改为通过预览图点击选择

        // 设置Palette颜色按钮监听器
        setupPaletteColorButtons()

        // 设置Material颜色按钮监听器
        setupMaterialColorButtons()

        binding.buttonExportConfig.setOnClickListener {
            exportWatermarkConfig()
        }

        binding.buttonImportConfig.setOnClickListener {
            importWatermarkConfig()
        }

        binding.buttonResetDefault.setOnClickListener {
            resetToDefaultSettings()
        }

        binding.buttonCancel.setOnClickListener {
            dismiss()
        }

        binding.buttonConfirm.setOnClickListener {
            saveSettings()
        }

        // 设置预览视图的监听器
        setupPreviewView()
        
        // 为所有交互控件设置触摸事件处理，防止意外关闭BottomSheet
        setupTouchEventHandling()
    }
    
    /**
     * 为所有交互控件设置触摸事件处理，防止在操作时意外关闭BottomSheet
     */
    private fun setupTouchEventHandling() {
        // 为所有按钮设置触摸事件处理
        val buttons = listOf(
            binding.buttonTextWatermark,
            binding.buttonImageWatermark,
            binding.buttonTextAlignLeft,
            binding.buttonTextAlignCenter,
            binding.buttonTextAlignRight,
            binding.buttonFollowTop,
            binding.buttonFollowBottom,
            binding.buttonFollowLeft,
            binding.buttonFollowRight,
            binding.buttonManualColor,
            binding.buttonAutoColor,
            binding.buttonMaterialColor,
            binding.buttonSelectFont,
            binding.buttonResetFont,
            binding.buttonSelectImage,
            binding.buttonTextColor,
            binding.buttonBorderColor,
            binding.buttonExportConfig,
            binding.buttonImportConfig,
            binding.buttonResetDefault,
            binding.buttonCancel,
            binding.buttonConfirm
        )
        
        buttons.forEach { button ->
            button.setOnTouchListener { view, event ->
                // 阻止触摸事件传递给BottomSheet
                view.parent.requestDisallowInterceptTouchEvent(true)
                false // 不消耗事件，让按钮正常处理点击
            }
        }
        
        // 为所有toggle group设置触摸事件处理
        val toggleGroups = listOf(
            binding.toggleGroupWatermarkType,
            binding.toggleGroupTextAlignment,
            binding.toggleGroupTextFollowDirection,
            binding.toggleBorderColorMode
        )
        
        toggleGroups.forEach { toggleGroup ->
            toggleGroup.setOnTouchListener { view, event ->
                view.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        
        // 为开关设置触摸事件处理
        binding.switchTextFollowMode.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        
        // 为输入框设置触摸事件处理
        binding.editTextContent.setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        
        // 为水印预览区域设置触摸事件处理，防止下滑时意外关闭BottomSheet
        binding.watermarkPreview.setOnTouchListener { view, event ->
            // 阻止触摸事件传递给BottomSheet
            view.parent.requestDisallowInterceptTouchEvent(true)
            false // 不消耗事件，让预览区域正常处理点击和其他交互
        }
        
        // 为水印预览区域的所有子视图递归设置触摸事件处理
        // 使用post确保在视图树构建完成后再设置
        binding.watermarkPreview.post {
            setupTouchEventForViewGroup(binding.watermarkPreview)
        }
    }
    
    /**
     * 递归为ViewGroup及其所有子视图设置触摸事件处理
     */
    private fun setupTouchEventForViewGroup(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            
            // 为子视图设置触摸事件处理
            child.setOnTouchListener { view, event ->
                // 阻止触摸事件传递给BottomSheet
                view.parent.requestDisallowInterceptTouchEvent(true)
                false // 不消耗事件，让子视图正常处理交互
            }
            
            // 如果子视图也是ViewGroup，递归处理
            if (child is ViewGroup) {
                setupTouchEventForViewGroup(child)
            }
        }
    }

    private fun loadSavedSettings() {
        // 根据调用来源加载对应的配置
        val config = preferencesManager.getWatermarkConfig(forFolderMonitor = forFolderMonitor)

        // 设置水印类型 Segmented Button
        val checkedButtons = mutableListOf<Int>()
        if (config.enableTextWatermark) {
            checkedButtons.add(binding.buttonTextWatermark.id)
        }
        if (config.enableImageWatermark) {
            checkedButtons.add(binding.buttonImageWatermark.id)
        }

        // 设置按钮状态
        binding.toggleGroupWatermarkType.clearOnButtonCheckedListeners()
        // 修复：先清除所有选中状态，避免XML默认值干扰
        binding.toggleGroupWatermarkType.clearChecked()
        // 然后设置正确的选中状态
        checkedButtons.forEach { buttonId ->
            binding.toggleGroupWatermarkType.check(buttonId)
        }
        // 重新添加监听器
        binding.toggleGroupWatermarkType.addOnButtonCheckedListener { group, checkedId, isChecked ->
            when (checkedId) {
                binding.buttonTextWatermark.id -> {
                    binding.cardTextSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
                }

                binding.buttonImageWatermark.id -> {
                    binding.cardImageSettings.visibility =
                        if (isChecked) View.VISIBLE else View.GONE
                }
            }
        }

        // 设置文本对齐方式
        val alignmentButtonId = when (config.textAlignment) {
            TextAlignment.LEFT -> binding.buttonTextAlignLeft.id
            TextAlignment.CENTER -> binding.buttonTextAlignCenter.id
            TextAlignment.RIGHT -> binding.buttonTextAlignRight.id
        }
        binding.toggleGroupTextAlignment.check(alignmentButtonId)

        // 设置文字水印位置和透明度
        binding.sliderTextPositionX.value = config.textPositionX
        binding.sliderTextPositionY.value = config.textPositionY
        binding.sliderTextOpacity.value = config.textOpacity

        // 设置图片水印位置和透明度
        binding.sliderImagePositionX.value = config.imagePositionX
        binding.sliderImagePositionY.value = config.imagePositionY
        binding.sliderImageOpacity.value = config.imageOpacity

        binding.sliderTextSize.value = config.textSize
        binding.sliderImageSize.value = config.imageSize

        // 加载文字设置
        binding.editTextContent.setText(config.textContent)
        updateTextColorButton(config.textColor)
        selectedFontPath = config.fontPath
        updateFontPathDisplay()

        // 加载图片设置
        selectedImagePath = config.imagePath
        updateImagePathDisplay()

        // 加载边框设置
        binding.sliderBorderTopWidth.value = config.borderTopWidth
        binding.sliderBorderBottomWidth.value = config.borderBottomWidth
        binding.sliderBorderLeftWidth.value = config.borderLeftWidth
        binding.sliderBorderRightWidth.value = config.borderRightWidth
        updateBorderColorButton(config.borderColor)

        // 加载新增的字间距和行间距设置
        binding.sliderLetterSpacing.value = config.letterSpacing.coerceIn(-100f, 400f)
        binding.sliderLineSpacing.value = config.lineSpacing.coerceIn(-100f, 400f)

        // 加载文字跟随模式设置
        binding.switchTextFollowMode.isChecked = config.enableTextFollowMode

        // 设置文字跟随方向
        val followDirectionButtonId = when (config.textFollowDirection) {
            TextFollowDirection.TOP -> binding.buttonFollowTop.id
            TextFollowDirection.BOTTOM -> binding.buttonFollowBottom.id
            TextFollowDirection.LEFT -> binding.buttonFollowLeft.id
            TextFollowDirection.RIGHT -> binding.buttonFollowRight.id
        }
        binding.toggleGroupTextFollowDirection.check(followDirectionButtonId)

        // 设置图片文字间距
        binding.sliderTextImageSpacing.value = config.textImageSpacing.coerceIn(-500f, 500f)

        // 更新UI显示状态
        updateTextFollowModeVisibility(config.enableTextFollowMode)

        // 初始化边框颜色模式
        val borderColorModeButtonId = when (config.borderColorMode) {
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL -> binding.buttonManualColor.id
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE -> binding.buttonAutoColor.id
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MATERIAL -> binding.buttonMaterialColor.id
        }
        binding.toggleBorderColorMode.check(borderColorModeButtonId)

        // 根据边框颜色模式设置UI可见性
        when (config.borderColorMode) {
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL -> {
                isManualColorMode = true
                binding.layoutManualColor.visibility = View.VISIBLE
                binding.layoutAutoColor.visibility = View.GONE
                binding.layoutMaterialColor.visibility = View.GONE
            }

            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE -> {
                isManualColorMode = false
                binding.layoutManualColor.visibility = View.GONE
                binding.layoutAutoColor.visibility = View.VISIBLE
                binding.layoutMaterialColor.visibility = View.GONE
            }

            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MATERIAL -> {
                isManualColorMode = false
                binding.layoutManualColor.visibility = View.GONE
                binding.layoutAutoColor.visibility = View.GONE
                binding.layoutMaterialColor.visibility = View.VISIBLE
            }
        }

        // 更新卡片可见性
        binding.cardTextSettings.visibility =
            if (config.enableTextWatermark) View.VISIBLE else View.GONE
        binding.cardImageSettings.visibility =
            if (config.enableImageWatermark) View.VISIBLE else View.GONE
    }

    private fun selectFontFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf")
            )
        }
        fontPickerLauncher.launch(Intent.createChooser(intent, "选择字体文件"))
    }

    private fun resetToDefaultFont() {
        try {
            // 删除内部文件夹中的临时字体文件
            val fontDir = File(requireContext().filesDir, "fonts")
            if (fontDir.exists()) {
                fontDir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.endsWith(".ttf") || file.name.endsWith(".otf"))) {
                        file.delete()
                    }
                }
                // 如果文件夹为空，删除文件夹
                if (fontDir.listFiles()?.isEmpty() == true) {
                    fontDir.delete()
                }
            }

            // 重置选中的字体路径
            selectedFontPath = null
            updateFontPathDisplay()

            Toast.makeText(context, "已恢复默认字体", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "恢复默认字体失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectImageFile() {
        // 使用系统相册选择器（ACTION_PICK）
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        imagePickerLauncher.launch(intent)
    }

    private fun selectBackgroundImageFile() {
        // 使用系统相册选择器（ACTION_PICK）
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        backgroundImagePickerLauncher.launch(intent)
    }

    private fun exportWatermarkConfig() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "watermark_config_${System.currentTimeMillis()}.zip")
        }
        exportLauncher.launch(intent)
    }

    private fun importWatermarkConfig() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/zip"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        importLauncher.launch(Intent.createChooser(intent, "选择配置文件"))
    }

    private fun handleFontSelection(uri: Uri) {
        try {
            val fileName = getFileName(uri) ?: "font_${System.currentTimeMillis()}.ttf"
            val fontDir = File(requireContext().filesDir, "fonts")
            if (!fontDir.exists()) fontDir.mkdirs()

            val fontFile = File(fontDir, fileName)
            copyUriToFile(uri, fontFile)

            selectedFontPath = fontFile.absolutePath
            updateFontPathDisplay()

            Toast.makeText(context, "字体文件已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "保存字体文件失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleImageSelection(uri: Uri) {
        try {
            val fileName = getFileName(uri) ?: "watermark_${System.currentTimeMillis()}.png"
            val imageDir = File(requireContext().filesDir, "watermarks")
            if (!imageDir.exists()) imageDir.mkdirs()

            val imageFile = File(imageDir, fileName)
            copyUriToFile(uri, imageFile)

            selectedImagePath = imageFile.absolutePath
            updateImagePathDisplay()

            Toast.makeText(context, "水印图片已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "保存水印图片失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleBackgroundImageSelection(uri: Uri) {
        // 使用协程处理图片加载，避免阻塞UI线程
        lifecycleScope.launch {
            try {
                // 直接使用内存优化器加载图片
                val memoryOptimizer =
                    cn.alittlecookie.lut2photo.lut2photo.utils.MemoryOptimizer(requireContext())
                val bitmap = memoryOptimizer.loadOptimizedBitmap(uri)

                if (bitmap != null) {
                    // 保存背景图片路径
                    selectedBackgroundImagePath = uri.toString()

                    // 更新预览视图的背景图片
                    binding.watermarkPreview.setBackgroundImage(bitmap)

                    // 如果当前是自动取色模式，自动提取颜色并显示调色板
                    val borderColorMode = getBorderColorMode()
                    if (borderColorMode == cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE) {
                        extractColorsFromBitmap(bitmap)
                    }

                    Toast.makeText(context, "预览背景图片已设置", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "设置背景图片失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 为预览加载大图片的缩略版本
     */
    private suspend fun loadLargeImageForPreview(uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)

                // 获取图片尺寸
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return@withContext null
                }

                // 为预览计算合适的采样率（限制在1024x1024以内）
                val maxPreviewSize = 1024
                val sampleSize = calculateSampleSize(
                    options.outWidth,
                    options.outHeight,
                    maxPreviewSize,
                    maxPreviewSize
                )

                // 加载缩略图用于预览
                val decodingStream = requireContext().contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(
                    decodingStream,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
                decodingStream?.close()

                bitmap
            } catch (e: Exception) {
                Log.e("WatermarkSettings", "加载大图片预览失败", e)
                null
            }
        }
    }

    /**
     * 计算合适的采样率
     */
    private fun calculateSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun handleExportConfig(uri: Uri) {
        try {
            val currentConfig = getCurrentConfigFromUI()
            watermarkConfigManager.exportConfig(currentConfig, uri)
            Toast.makeText(context, "配置导出成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "导出配置失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleImportConfig(uri: Uri) {
        try {
            val importedConfig = watermarkConfigManager.importConfig(uri)
            applyImportedConfig(importedConfig)
            Toast.makeText(context, "配置导入成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "导入配置失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getCurrentConfigFromUI(): WatermarkConfig {
        // 获取水印类型状态
        val enableTextWatermark =
            binding.toggleGroupWatermarkType.checkedButtonIds.contains(binding.buttonTextWatermark.id)
        val enableImageWatermark =
            binding.toggleGroupWatermarkType.checkedButtonIds.contains(binding.buttonImageWatermark.id)

        // 获取文本对齐方式
        val textAlignment = when (binding.toggleGroupTextAlignment.checkedButtonId) {
            binding.buttonTextAlignLeft.id -> TextAlignment.LEFT
            binding.buttonTextAlignCenter.id -> TextAlignment.CENTER
            binding.buttonTextAlignRight.id -> TextAlignment.RIGHT
            else -> TextAlignment.LEFT
        }

        // 获取文字跟随模式设置
        val enableTextFollowMode = binding.switchTextFollowMode.isChecked
        val textFollowDirection = when (binding.toggleGroupTextFollowDirection.checkedButtonId) {
            binding.buttonFollowTop.id -> TextFollowDirection.TOP
            binding.buttonFollowBottom.id -> TextFollowDirection.BOTTOM
            binding.buttonFollowLeft.id -> TextFollowDirection.LEFT
            binding.buttonFollowRight.id -> TextFollowDirection.RIGHT
            else -> TextFollowDirection.BOTTOM
        }

        // 修复：根据调用来源获取正确的开关状态
        val isEnabled = if (forFolderMonitor) {
            preferencesManager.folderMonitorWatermarkEnabled
        } else {
            preferencesManager.dashboardWatermarkEnabled
        }

        return WatermarkConfig(
            isEnabled = isEnabled,
            enableTextWatermark = enableTextWatermark,
            enableImageWatermark = enableImageWatermark,
            // 新的分离位置和透明度设置
            textPositionX = binding.sliderTextPositionX.value,
            textPositionY = binding.sliderTextPositionY.value,
            imagePositionX = binding.sliderImagePositionX.value,
            imagePositionY = binding.sliderImagePositionY.value,
            textSize = binding.sliderTextSize.value,
            imageSize = binding.sliderImageSize.value,
            textOpacity = binding.sliderTextOpacity.value,
            imageOpacity = binding.sliderImageOpacity.value,
            textContent = binding.editTextContent.text.toString(),
            textColor = getTextColorFromButton(),
            fontPath = selectedFontPath ?: "",
            textAlignment = textAlignment,
            imagePath = selectedImagePath ?: "",
            enableTextFollowMode = enableTextFollowMode,
            textFollowDirection = textFollowDirection,
            textImageSpacing = if (enableTextFollowMode) binding.sliderTextImageSpacing.value else 0f,
            borderTopWidth = binding.sliderBorderTopWidth.value,
            borderBottomWidth = binding.sliderBorderBottomWidth.value,
            borderLeftWidth = binding.sliderBorderLeftWidth.value,
            borderRightWidth = binding.sliderBorderRightWidth.value,
            borderColor = getBorderColorFromButton(),
            letterSpacing = binding.sliderLetterSpacing.value,
            lineSpacing = binding.sliderLineSpacing.value,
            borderColorMode = getBorderColorMode()
        )
    }

    private fun applyImportedConfig(config: WatermarkConfig) {
        // 设置水印类型 Segmented Button
        val checkedButtons = mutableListOf<Int>()
        if (config.enableTextWatermark) {
            checkedButtons.add(binding.buttonTextWatermark.id)
        }
        if (config.enableImageWatermark) {
            checkedButtons.add(binding.buttonImageWatermark.id)
        }

        // 清除并设置按钮状态
        binding.toggleGroupWatermarkType.clearOnButtonCheckedListeners()
        binding.toggleGroupWatermarkType.clearChecked()
        checkedButtons.forEach { buttonId ->
            binding.toggleGroupWatermarkType.check(buttonId)
        }
        // 重新添加监听器
        binding.toggleGroupWatermarkType.addOnButtonCheckedListener { group, checkedId, isChecked ->
            when (checkedId) {
                binding.buttonTextWatermark.id -> {
                    binding.cardTextSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
                }

                binding.buttonImageWatermark.id -> {
                    binding.cardImageSettings.visibility =
                        if (isChecked) View.VISIBLE else View.GONE
                }
            }
        }

        // 设置文本对齐方式
        val alignmentButtonId = when (config.textAlignment) {
            TextAlignment.LEFT -> binding.buttonTextAlignLeft.id
            TextAlignment.CENTER -> binding.buttonTextAlignCenter.id
            TextAlignment.RIGHT -> binding.buttonTextAlignRight.id
        }
        binding.toggleGroupTextAlignment.check(alignmentButtonId)

        // 设置分离的位置和透明度
        binding.sliderTextPositionX.value = config.textPositionX
        binding.sliderTextPositionY.value = config.textPositionY
        binding.sliderImagePositionX.value = config.imagePositionX
        binding.sliderImagePositionY.value = config.imagePositionY
        binding.sliderTextOpacity.value = config.textOpacity
        binding.sliderImageOpacity.value = config.imageOpacity

        binding.sliderTextSize.value = config.textSize.coerceAtLeast(0.1f)
        binding.sliderImageSize.value = config.imageSize

        binding.editTextContent.setText(config.textContent)
        updateTextColorButton(config.textColor)

        selectedImagePath = config.imagePath
        updateImagePathDisplay()
        selectedFontPath = config.fontPath
        updateFontPathDisplay()

        // 移除文字图片间距设置，不再使用
        // binding.sliderTextImageSpacing.value = config.textImageSpacing

        binding.sliderBorderTopWidth.value = config.borderTopWidth
        binding.sliderBorderBottomWidth.value = config.borderBottomWidth
        binding.sliderBorderLeftWidth.value = config.borderLeftWidth
        binding.sliderBorderRightWidth.value = config.borderRightWidth
        updateBorderColorButton(config.borderColor)

        // 应用新增的字间距和行间距设置
        binding.sliderLetterSpacing.value = config.letterSpacing.coerceIn(-100f, 400f)
        binding.sliderLineSpacing.value = config.lineSpacing.coerceIn(-100f, 400f)

        // 应用文字跟随模式设置
        binding.switchTextFollowMode.isChecked = config.enableTextFollowMode

        // 设置文字跟随方向
        val followDirectionButtonId = when (config.textFollowDirection) {
            TextFollowDirection.TOP -> binding.buttonFollowTop.id
            TextFollowDirection.BOTTOM -> binding.buttonFollowBottom.id
            TextFollowDirection.LEFT -> binding.buttonFollowLeft.id
            TextFollowDirection.RIGHT -> binding.buttonFollowRight.id
        }
        binding.toggleGroupTextFollowDirection.check(followDirectionButtonId)

        // 设置图片文字间距
        binding.sliderTextImageSpacing.value = config.textImageSpacing.coerceIn(-500f, 500f)

        // 更新UI显示状态
        updateTextFollowModeVisibility(config.enableTextFollowMode)

        // 应用边框颜色模式设置
        val borderColorModeButtonId = when (config.borderColorMode) {
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL -> binding.buttonManualColor.id
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE -> binding.buttonAutoColor.id
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MATERIAL -> binding.buttonMaterialColor.id
        }
        binding.toggleBorderColorMode.check(borderColorModeButtonId)

        // 根据边框颜色模式设置UI可见性
        when (config.borderColorMode) {
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL -> {
                isManualColorMode = true
                binding.layoutManualColor.visibility = View.VISIBLE
                binding.layoutAutoColor.visibility = View.GONE
                binding.layoutMaterialColor.visibility = View.GONE
            }

            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE -> {
                isManualColorMode = false
                binding.layoutManualColor.visibility = View.GONE
                binding.layoutAutoColor.visibility = View.VISIBLE
                binding.layoutMaterialColor.visibility = View.GONE
            }

            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MATERIAL -> {
                isManualColorMode = false
                binding.layoutManualColor.visibility = View.GONE
                binding.layoutAutoColor.visibility = View.GONE
                binding.layoutMaterialColor.visibility = View.VISIBLE
            }
        }

        // 更新卡片可见性
        binding.cardTextSettings.visibility =
            if (config.enableTextWatermark) View.VISIBLE else View.GONE
        binding.cardImageSettings.visibility =
            if (config.enableImageWatermark) View.VISIBLE else View.GONE
    }

    private fun getFileName(uri: Uri): String? {
        return requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
    }

    private fun copyUriToFile(uri: Uri, targetFile: File) {
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun updateFontPathDisplay() {
        binding.textFontPath.text = selectedFontPath?.let { path ->
            File(path).name
        } ?: "未选择字体文件，使用默认字体"
    }

    private fun updateImagePathDisplay() {
        binding.textImagePath.text = selectedImagePath?.let { path ->
            File(path).name
        } ?: "未选择水印图片"
    }

    // 颜色选择器相关方法
    private var currentTextColor = "#FFFFFF"
    private var currentBorderColor = "#000000"

    private fun showTextColorPicker() {
        val isDarkMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val currentColor = try {
            Color.parseColor(currentTextColor)
        } catch (e: Exception) {
            Color.WHITE
        }

        val dialog = ColorPickerDialog()
            .withColor(currentColor)
            .withAlphaEnabled(false)
            .withPresets(
                Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
                Color.CYAN, Color.MAGENTA, Color.BLACK, Color.WHITE,
                Color.GRAY, Color.DKGRAY
            )
            .withPicker(ImagePickerView::class.java)
            .withListener { _, color ->
                currentTextColor = String.format("#%06X", 0xFFFFFF and color)
                updateTextColorButton(currentTextColor)
                updatePreview()
            }

        // 应用自定义主题
        val themeResId = if (isDarkMode) {
            cn.alittlecookie.lut2photo.lut2photo.R.style.Theme_ColorPickerDialog_Dark
        } else {
            cn.alittlecookie.lut2photo.lut2photo.R.style.Theme_ColorPickerDialog_Light
        }
        dialog.setStyle(STYLE_NORMAL, themeResId)
        dialog.show(parentFragmentManager, "textColorPicker")
    }

    private fun showBorderColorPicker() {
        val isDarkMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val currentColor = try {
            Color.parseColor(currentBorderColor)
        } catch (e: Exception) {
            Color.BLACK
        }

        val dialog = ColorPickerDialog()
            .withColor(currentColor)
            .withAlphaEnabled(false)
            .withPresets(
                Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
                Color.CYAN, Color.MAGENTA, Color.BLACK, Color.WHITE,
                Color.GRAY, Color.DKGRAY
            )
            .withPicker(ImagePickerView::class.java)
            .withListener { _, color ->
                currentBorderColor = String.format("#%06X", 0xFFFFFF and color)
                updateBorderColorButton(currentBorderColor)
                updatePreview()
            }

        // 应用自定义主题
        val themeResId = if (isDarkMode) {
            cn.alittlecookie.lut2photo.lut2photo.R.style.Theme_ColorPickerDialog_Dark
        } else {
            cn.alittlecookie.lut2photo.lut2photo.R.style.Theme_ColorPickerDialog_Light
        }
        dialog.setStyle(STYLE_NORMAL, themeResId)
        dialog.show(parentFragmentManager, "borderColorPicker")
    }

    private fun updateTextColorButton(colorHex: String) {
        currentTextColor = colorHex
        binding.buttonTextColor.text = colorHex
        try {
            val color = Color.parseColor(colorHex)
            binding.buttonTextColor.setBackgroundColor(color)
            // 根据背景颜色调整文字颜色
            val textColor = if (isLightColor(color)) Color.BLACK else Color.WHITE
            binding.buttonTextColor.setTextColor(textColor)
        } catch (e: Exception) {
            // 如果颜色解析失败，使用默认样式
            binding.buttonTextColor.setBackgroundColor(Color.WHITE)
            binding.buttonTextColor.setTextColor(Color.BLACK)
        }
    }

    private fun updateBorderColorButton(colorHex: String) {
        currentBorderColor = colorHex
        binding.buttonBorderColor.text = colorHex
        try {
            val color = Color.parseColor(colorHex)
            binding.buttonBorderColor.setBackgroundColor(color)
            // 根据背景颜色调整文字颜色
            val textColor = if (isLightColor(color)) Color.BLACK else Color.WHITE
            binding.buttonBorderColor.setTextColor(textColor)
        } catch (e: Exception) {
            // 如果颜色解析失败，使用默认样式
            binding.buttonBorderColor.setBackgroundColor(Color.BLACK)
            binding.buttonBorderColor.setTextColor(Color.WHITE)
        }
    }

    private fun getTextColorFromButton(): String {
        return currentTextColor
    }

    private fun getBorderColorFromButton(): String {
        return currentBorderColor
    }

    private fun getBorderColorMode(): cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode {
        return when (binding.toggleBorderColorMode.checkedButtonId) {
            binding.buttonManualColor.id -> cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL
            binding.buttonAutoColor.id -> cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE
            binding.buttonMaterialColor.id -> cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MATERIAL
            else -> cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val brightness = (red * 299 + green * 587 + blue * 114) / 1000
        return brightness > 128
    }

    /**
     * 更新文字跟随模式相关UI的显示/隐藏状态
     */
    private fun updateTextFollowModeVisibility(isFollowMode: Boolean) {
        if (isFollowMode) {
            // 跟随模式下：隐藏文字位置滑块，显示跟随方向和间距设置
            binding.textPositionXLabel.visibility = View.GONE
            binding.layoutTextPositionX.visibility = View.GONE
            binding.textPositionYLabel.visibility = View.GONE
            binding.layoutTextPositionY.visibility = View.GONE

            binding.textFollowDirectionLabel.visibility = View.VISIBLE
            binding.toggleGroupTextFollowDirection.visibility = View.VISIBLE
            binding.textImageSpacingLabel.visibility = View.VISIBLE
            binding.layoutTextImageSpacing.visibility = View.VISIBLE
        } else {
            // 非跟随模式下：显示文字位置滑块，隐藏跟随方向和间距设置
            binding.textPositionXLabel.visibility = View.VISIBLE
            binding.layoutTextPositionX.visibility = View.VISIBLE
            binding.textPositionYLabel.visibility = View.VISIBLE
            binding.layoutTextPositionY.visibility = View.VISIBLE

            binding.textFollowDirectionLabel.visibility = View.GONE
            binding.toggleGroupTextFollowDirection.visibility = View.GONE
            binding.textImageSpacingLabel.visibility = View.GONE
            binding.layoutTextImageSpacing.visibility = View.GONE
        }

        // 更新预览
        updatePreview()
    }

    /**
     * 设置预览视图
     */
    private fun setupPreviewView() {
        // 设置预览视图的折叠监听器
        binding.watermarkPreview.onToggleListener = { isCollapsed ->
            // 可以在这里处理折叠状态变化
            // 例如，调整ScrollView的高度等
        }

        // 设置配置提供者回调
        binding.watermarkPreview.configProvider = {
            try {
                getCurrentConfigFromUI()
            } catch (e: Exception) {
                null
            }
        }

        // 设置预览点击监听器 - 点击预览区域选择背景图片
        binding.watermarkPreview.onPreviewClickListener = {
            // 点击预览区域总是触发选择背景图片
            selectBackgroundImageFile()
        }

        // 为所有设置控件添加预览更新监听器
        setupPreviewUpdateListeners()

        // 初始化预览
        updatePreview()

        // 强制初始化预览显示
        binding.watermarkPreview.forceInitialPreview()
    }

    /**
     * 为所有设置控件添加预览更新监听器
     */
    private fun setupPreviewUpdateListeners() {
        // 水印类型变化
        binding.toggleGroupWatermarkType.addOnButtonCheckedListener { _, _, _ ->
            updatePreview()
        }

        // 文字设置变化
        binding.editTextContent.setOnTextChangedListener { updatePreview() }

        // 滑块变化
        listOf(
            binding.sliderTextPositionX,
            binding.sliderTextPositionY,
            binding.sliderImagePositionX,
            binding.sliderImagePositionY,
            binding.sliderTextSize,
            binding.sliderImageSize,
            binding.sliderTextOpacity,
            binding.sliderImageOpacity,
            binding.sliderTextImageSpacing,
            binding.sliderLetterSpacing,
            binding.sliderLineSpacing,
            binding.sliderBorderTopWidth,
            binding.sliderBorderBottomWidth,
            binding.sliderBorderLeftWidth,
            binding.sliderBorderRightWidth
        ).forEach { slider ->
            slider.addOnChangeListener { _, _, _ -> updatePreview() }
        }

        // 文字跟随模式变化
        binding.switchTextFollowMode.setOnCheckedChangeListener { _, isChecked ->
            updateTextFollowModeVisibility(isChecked)
        }
        binding.toggleGroupTextFollowDirection.addOnButtonCheckedListener { _, _, _ -> updatePreview() }

        // 文本对齐方式变化
        binding.toggleGroupTextAlignment.addOnButtonCheckedListener { _, _, _ -> updatePreview() }
    }

    /**
     * 更新预览
     */
    private fun updatePreview() {
        try {
            val config = getCurrentConfigFromUI()
            binding.watermarkPreview.updatePreview(
                config,
                lut1Name,
                lut2Name,
                lut1Strength,
                lut2Strength
            )
        } catch (e: Exception) {
            // 忽略错误，防止崩溃
        }
    }

    /**
     * EditText的文本变化监听器
     */
    private fun android.widget.EditText.setOnTextChangedListener(callback: () -> Unit) {
        this.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                callback()
            }
        })
    }

    /**
     * 恢复默认设置
     * 将所有水印设置恢复到默认值，并清除水印图片和字体
     */
    private fun resetToDefaultSettings() {
        try {
            // 创建默认配置
            val defaultConfig = WatermarkConfig()
            
            // 清除选中的图片和字体
            selectedImagePath = null
            selectedFontPath = null
            
            // 应用默认配置到UI
            applyConfigToUI(defaultConfig)
            
            // 更新预览
            updatePreview()
            
            Toast.makeText(context, "已恢复默认设置", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "恢复默认设置失败: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("WatermarkSettings", "恢复默认设置失败", e)
        }
    }
    
    /**
     * 将配置应用到UI
     */
    private fun applyConfigToUI(config: WatermarkConfig) {
        // 设置水印类型 Segmented Button
        val checkedButtons = mutableListOf<Int>()
        if (config.enableTextWatermark) {
            checkedButtons.add(binding.buttonTextWatermark.id)
        }
        if (config.enableImageWatermark) {
            checkedButtons.add(binding.buttonImageWatermark.id)
        }

        // 清除并设置按钮状态
        binding.toggleGroupWatermarkType.clearOnButtonCheckedListeners()
        binding.toggleGroupWatermarkType.clearChecked()
        checkedButtons.forEach { buttonId ->
            binding.toggleGroupWatermarkType.check(buttonId)
        }
        // 重新添加监听器
        binding.toggleGroupWatermarkType.addOnButtonCheckedListener { group, checkedId, isChecked ->
            when (checkedId) {
                binding.buttonTextWatermark.id -> {
                    binding.cardTextSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
                }
                binding.buttonImageWatermark.id -> {
                    binding.cardImageSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
                }
            }
        }

        // 设置文本对齐方式
        val alignmentButtonId = when (config.textAlignment) {
            TextAlignment.LEFT -> binding.buttonTextAlignLeft.id
            TextAlignment.CENTER -> binding.buttonTextAlignCenter.id
            TextAlignment.RIGHT -> binding.buttonTextAlignRight.id
        }
        binding.toggleGroupTextAlignment.check(alignmentButtonId)

        // 设置文字跟随模式
        binding.switchTextFollowMode.isChecked = config.enableTextFollowMode
        updateTextFollowModeVisibility(config.enableTextFollowMode)
        
        // 设置文字跟随方向
        val followDirectionButtonId = when (config.textFollowDirection) {
            TextFollowDirection.TOP -> binding.buttonFollowTop.id
            TextFollowDirection.BOTTOM -> binding.buttonFollowBottom.id
            TextFollowDirection.LEFT -> binding.buttonFollowLeft.id
            TextFollowDirection.RIGHT -> binding.buttonFollowRight.id
        }
        binding.toggleGroupTextFollowDirection.check(followDirectionButtonId)

        // 设置位置和透明度
        binding.sliderTextPositionX.value = config.textPositionX
        binding.sliderTextPositionY.value = config.textPositionY
        binding.sliderImagePositionX.value = config.imagePositionX
        binding.sliderImagePositionY.value = config.imagePositionY
        binding.sliderTextOpacity.value = config.textOpacity
        binding.sliderImageOpacity.value = config.imageOpacity

        // 设置大小
        binding.sliderTextSize.value = config.textSize.coerceAtLeast(0.1f)
        binding.sliderImageSize.value = config.imageSize

        // 设置文字内容和颜色
        binding.editTextContent.setText(config.textContent)
        updateTextColorButton(config.textColor)

        // 设置图片和字体路径
        selectedImagePath = config.imagePath.ifEmpty { null }
        selectedFontPath = config.fontPath.ifEmpty { null }
        updateImagePathDisplay()
        updateFontPathDisplay()

        // 设置图片文字间距
        binding.sliderTextImageSpacing.value = config.textImageSpacing

        // 设置边框
        binding.sliderBorderTopWidth.value = config.borderTopWidth
        binding.sliderBorderBottomWidth.value = config.borderBottomWidth
        binding.sliderBorderLeftWidth.value = config.borderLeftWidth
        binding.sliderBorderRightWidth.value = config.borderRightWidth
        updateBorderColorButton(config.borderColor)

        // 设置字间距和行间距
        binding.sliderLetterSpacing.value = config.letterSpacing
        binding.sliderLineSpacing.value = config.lineSpacing

        // 设置边框颜色模式
        val borderColorModeButtonId = when (config.borderColorMode) {
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL -> binding.buttonManualColor.id
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE -> binding.buttonAutoColor.id
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MATERIAL -> binding.buttonMaterialColor.id
        }
        binding.toggleBorderColorMode.check(borderColorModeButtonId)
    }

    private fun saveSettings() {
        try {
            // 验证颜色格式
            val textColor = getTextColorFromButton()
            val borderColor = getBorderColorFromButton()

            if (!isValidColor(textColor)) {
                Toast.makeText(
                    context,
                    "文字颜色格式不正确，请使用16进制格式如 #FFFFFF",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            if (!isValidColor(borderColor)) {
                Toast.makeText(
                    context,
                    "边框颜色格式不正确，请使用16进制格式如 #000000",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val config = getCurrentConfigFromUI()
            // 保存配置时指定是否为文件夹监控页面
            preferencesManager.saveWatermarkConfig(config, forFolderMonitor)
            onConfigSaved?.invoke(config)

            // 发送广播通知文件夹监控服务更新配置
            // 使用显式广播，明确指定接收者为FolderMonitorService
            val intent = Intent("cn.alittlecookie.lut2photo.LUT_CONFIG_CHANGED").apply {
                setPackage(requireContext().packageName)  // 限制在本应用内
            }
            requireContext().sendBroadcast(intent)
            Log.d("WatermarkSettings", "发送配置变化广播（显式广播），forFolderMonitor=$forFolderMonitor")

            Toast.makeText(context, "水印设置已保存", Toast.LENGTH_SHORT).show()
            dismiss()

        } catch (e: Exception) {
            Toast.makeText(context, "保存设置失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun isValidColor(colorString: String): Boolean {
        return try {
            Color.parseColor(colorString)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun selectPaletteImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        paletteImagePickerLauncher.launch(Intent.createChooser(intent, "选择图片提取颜色"))
    }

    /**
     * 从已选择的背景图片中提取颜色
     */
    private fun extractColorsFromBackgroundImage() {
        selectedBackgroundImagePath?.let { uriString ->
            try {
                println("开始从背景图片提取颜色，URI: $uriString")
                val uri = Uri.parse(uriString)
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    println("成功加载背景图片，尺寸: ${bitmap.width}x${bitmap.height}")
                    extractColorsFromBitmap(bitmap)
                } else {
                    println("无法解码背景图片")
                    Toast.makeText(context, "无法读取背景图片", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                println("提取颜色异常: ${e.message}")
                Toast.makeText(context, "提取颜色失败: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        } ?: run {
            println("selectedBackgroundImagePath 为空")
            Toast.makeText(context, "请先选择背景图片", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePaletteImageSelection(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap != null) {
                extractColorsFromBitmap(bitmap)
            } else {
                Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "处理图片失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun extractColorsFromBitmap(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            // 确保在主线程中更新UI
            requireActivity().runOnUiThread {
                if (palette != null) {
                    val colors = mutableListOf<Int>()

                    // 提取各种颜色
                    palette.vibrantSwatch?.rgb?.let { colors.add(it) }
                    palette.lightVibrantSwatch?.rgb?.let { colors.add(it) }
                    palette.darkVibrantSwatch?.rgb?.let { colors.add(it) }
                    palette.mutedSwatch?.rgb?.let { colors.add(it) }
                    palette.lightMutedSwatch?.rgb?.let { colors.add(it) }
                    palette.darkMutedSwatch?.rgb?.let { colors.add(it) }

                    // 如果颜色不足6个，从主要颜色中补充
                    if (colors.size < 6) {
                        palette.swatches.forEach { swatch ->
                            if (colors.size < 6 && !colors.contains(swatch.rgb)) {
                                colors.add(swatch.rgb)
                            }
                        }
                    }

                    // 如果仍然没有颜色，添加一些默认颜色
                    if (colors.isEmpty()) {
                        colors.addAll(
                            listOf(
                                Color.BLACK, Color.WHITE, Color.RED,
                                Color.GREEN, Color.BLUE, Color.YELLOW
                            )
                        )
                    }

                    paletteColors = colors.take(6)
                    println("提取到 ${paletteColors.size} 个颜色: $paletteColors")
                    updatePaletteColorButtons()

                    // 显示颜色选择网格
                    binding.gridPaletteColors.visibility = View.VISIBLE
                    println("调色板网格可见性已设置为 VISIBLE")

                    Toast.makeText(
                        context,
                        "已提取 ${paletteColors.size} 个颜色",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(context, "无法从图片中提取颜色", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupPaletteColorButtons() {
        val colorButtons = listOf(
            binding.buttonPaletteColor1,
            binding.buttonPaletteColor2,
            binding.buttonPaletteColor3,
            binding.buttonPaletteColor4,
            binding.buttonPaletteColor5,
            binding.buttonPaletteColor6
        )

        colorButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                if (index < paletteColors.size) {
                    val color = paletteColors[index]
                    val colorHex = String.format("#%06X", 0xFFFFFF and color)
                    updateBorderColorButton(colorHex)
                    updatePreview()
                }
            }
        }
    }

    private fun setupMaterialColorButtons() {
        // Material Design 调色板颜色映射
        val materialColors = mapOf(
            binding.buttonMaterialRed to "#F44336",
            binding.buttonMaterialPink to "#E91E63",
            binding.buttonMaterialPurple to "#9C27B0",
            binding.buttonMaterialDeepPurple to "#673AB7",
            binding.buttonMaterialIndigo to "#3F51B5",
            binding.buttonMaterialBlue to "#2196F3",
            binding.buttonMaterialLightBlue to "#03A9F4",
            binding.buttonMaterialCyan to "#00BCD4",
            binding.buttonMaterialTeal to "#009688",
            binding.buttonMaterialGreen to "#4CAF50",
            binding.buttonMaterialOrange to "#FF9800",
            binding.buttonMaterialBrown to "#795548"
        )

        materialColors.forEach { (button, colorHex) ->
            button.setOnClickListener {
                updateBorderColorButton(colorHex)
                updatePreview()
            }
        }
    }

    private fun updatePaletteColorButtons() {
        val colorButtons = listOf(
            binding.buttonPaletteColor1,
            binding.buttonPaletteColor2,
            binding.buttonPaletteColor3,
            binding.buttonPaletteColor4,
            binding.buttonPaletteColor5,
            binding.buttonPaletteColor6
        )

        val labels = listOf("主色", "鲜艳", "柔和", "深色", "浅色", "侘寂")

        println("更新调色板按钮，颜色数量: ${paletteColors.size}")

        colorButtons.forEachIndexed { index, button ->
            if (index < paletteColors.size) {
                val color = paletteColors[index]
                button.setBackgroundColor(color)
                button.visibility = View.VISIBLE
                button.text = labels[index]

                // 根据颜色亮度设置文字颜色
                val textColor = if (isLightColor(color)) Color.BLACK else Color.WHITE
                button.setTextColor(textColor)

                println("按钮 ${index + 1} 设置颜色: ${String.format("#%06X", 0xFFFFFF and color)}")
            } else {
                button.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}