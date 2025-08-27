package cn.alittlecookie.lut2photo.ui.bottomsheet

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import cn.alittlecookie.lut2photo.lut2photo.databinding.BottomsheetWatermarkSettingsBinding
import cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment
import cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import cn.alittlecookie.lut2photo.lut2photo.utils.WatermarkConfigManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File
import java.io.FileOutputStream

class WatermarkSettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetWatermarkSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var watermarkConfigManager: WatermarkConfigManager
    private var onConfigSaved: ((WatermarkConfig) -> Unit)? = null

    private lateinit var fontPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportLauncher: ActivityResultLauncher<Intent>
    private lateinit var importLauncher: ActivityResultLauncher<Intent>

    private var selectedFontPath: String? = null
    private var selectedImagePath: String? = null

    companion object {
        fun newInstance(onConfigSaved: (WatermarkConfig) -> Unit): WatermarkSettingsBottomSheet {
            return WatermarkSettingsBottomSheet().apply {
                this.onConfigSaved = onConfigSaved
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
                            window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        } else {
                            window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
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
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        // 只允许展开状态，不允许其他状态
                        if (newState == BottomSheetBehavior.STATE_COLLAPSED ||
                            newState == BottomSheetBehavior.STATE_HIDDEN
                        ) {
                            dismiss()
                        }
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        // 当滑动偏移小于0.5时关闭对话框
                        if (slideOffset < 0.5f) {
                            dismiss()
                        }
                    }
                })
            }
        }
    }

    // 判断颜色是否为浅色
    private fun isLightColor(color: Int): Boolean {
        val red = android.graphics.Color.red(color)
        val green = android.graphics.Color.green(color)
        val blue = android.graphics.Color.blue(color)
        val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255
        return luminance > 0.5
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
        loadSavedSettings()
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

        binding.sliderTextPositionY.addOnChangeListener { _, value, _ ->
            binding.textTextPositionYValue.text = "${value.toInt()}%"
        }

        // 文字水印透明度设置滑块监听器
        binding.sliderTextOpacity.addOnChangeListener { _, value, _ ->
            binding.textTextOpacityValue.text = "${value.toInt()}%"
        }

        // 图片水印位置设置滑块监听器
        binding.sliderImagePositionX.addOnChangeListener { _, value, _ ->
            binding.textImagePositionXValue.text = "${value.toInt()}%"
        }

        binding.sliderImagePositionY.addOnChangeListener { _, value, _ ->
            binding.textImagePositionYValue.text = "${value.toInt()}%"
        }

        // 图片水印透明度设置滑块监听器
        binding.sliderImageOpacity.addOnChangeListener { _, value, _ ->
            binding.textImageOpacityValue.text = "${value.toInt()}%"
        }

        binding.sliderTextSize.addOnChangeListener { _, value, _ ->
            binding.textTextSizeValue.text = "${String.format("%.1f", value)}%"
        }

        binding.sliderImageSize.addOnChangeListener { _, value, _ ->
            binding.textImageSizeValue.text = "${value.toInt()}%"
        }

        // 四个方向的边框滑块监听器
        binding.sliderBorderTopWidth.addOnChangeListener { _, value, _ ->
            binding.textBorderTopWidthValue.text = "${value.toInt()}%"
        }

        binding.sliderBorderBottomWidth.addOnChangeListener { _, value, _ ->
            binding.textBorderBottomWidthValue.text = "${value.toInt()}%"
        }

        binding.sliderBorderLeftWidth.addOnChangeListener { _, value, _ ->
            binding.textBorderLeftWidthValue.text = "${value.toInt()}%"
        }

        binding.sliderBorderRightWidth.addOnChangeListener { _, value, _ ->
            binding.textBorderRightWidthValue.text = "${value.toInt()}%"
        }

        // 新增字间距和行间距滑块监听器
        binding.sliderLetterSpacing.addOnChangeListener { _, value, _ ->
            binding.textLetterSpacingValue.text = "${String.format("%.1f", value)}%"
        }

        binding.sliderLineSpacing.addOnChangeListener { _, value, _ ->
            binding.textLineSpacingValue.text = "${value.toInt()}%"
        }

        // 添加图片文字间距滑块监听器
        binding.sliderTextImageSpacing.addOnChangeListener { _, value, _ ->
            binding.textTextImageSpacingValue.text = "${value.toInt()}%"
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

        binding.buttonExportConfig.setOnClickListener {
            exportWatermarkConfig()
        }

        binding.buttonImportConfig.setOnClickListener {
            importWatermarkConfig()
        }

        binding.buttonCancel.setOnClickListener {
            dismiss()
        }

        binding.buttonConfirm.setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSavedSettings() {
        val config = preferencesManager.getWatermarkConfig()

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
        binding.editTextColor.setText(config.textColor)
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
        binding.editBorderColor.setText(config.borderColor)

        // 加载新增的字间距和行间距设置
        binding.sliderLetterSpacing.value = config.letterSpacing.coerceAtLeast(0.1f)
        binding.sliderLineSpacing.value = config.lineSpacing

        // 加载文字跟随模式设置
        binding.switchTextFollowMode.isChecked = config.enableTextFollowMode

        // 设置文字跟随方向
        val followDirectionButtonId = when (config.textFollowDirection) {
            cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection.TOP -> binding.buttonFollowTop.id
            cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection.BOTTOM -> binding.buttonFollowBottom.id
            cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection.LEFT -> binding.buttonFollowLeft.id
            cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection.RIGHT -> binding.buttonFollowRight.id
        }
        binding.toggleGroupTextFollowDirection.check(followDirectionButtonId)

        // 设置图片文字间距
        binding.sliderTextImageSpacing.value = config.textImageSpacing

        // 更新UI显示状态
        updateTextFollowModeVisibility(config.enableTextFollowMode)

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
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        imagePickerLauncher.launch(Intent.createChooser(intent, "选择水印图片"))
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

        return WatermarkConfig(
            isEnabled = preferencesManager.watermarkEnabled,
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
            textColor = binding.editTextColor.text.toString(),
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
            borderColor = binding.editBorderColor.text.toString(),
            letterSpacing = binding.sliderLetterSpacing.value,
            lineSpacing = binding.sliderLineSpacing.value
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
        binding.editTextColor.setText(config.textColor)

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
        binding.editBorderColor.setText(config.borderColor)

        // 应用新增的字间距和行间距设置
        binding.sliderLetterSpacing.value = config.letterSpacing.coerceAtLeast(0.1f)
        binding.sliderLineSpacing.value = config.lineSpacing

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
        binding.sliderTextImageSpacing.value = config.textImageSpacing

        // 更新UI显示状态
        updateTextFollowModeVisibility(config.enableTextFollowMode)

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
    }

    private fun saveSettings() {
        try {
            // 验证颜色格式
            val textColor = binding.editTextColor.text.toString().trim()
            val borderColor = binding.editBorderColor.text.toString().trim()

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
            preferencesManager.saveWatermarkConfig(config)
            onConfigSaved?.invoke(config)

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}