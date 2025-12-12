package cn.alittlecookie.lut2photo.ui.dialog

import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.palette.graphics.Palette
import cn.alittlecookie.lut2photo.lut2photo.databinding.DialogWatermarkSettingsBinding
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import me.jfenn.colorpickerdialog.dialogs.ColorPickerDialog
import me.jfenn.colorpickerdialog.views.picker.ImagePickerView
import java.io.File
import java.io.FileOutputStream

class WatermarkSettingsDialog : DialogFragment() {

    private var _binding: DialogWatermarkSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferencesManager: PreferencesManager
    private var onConfigSaved: ((WatermarkConfig) -> Unit)? = null

    private lateinit var fontPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var paletteImagePickerLauncher: ActivityResultLauncher<Intent>

    private var selectedFontPath: String? = null
    private var selectedImagePath: String? = null
    private var paletteColors: List<Int> = emptyList()
    private var isManualColorMode = true

    companion object {
        fun newInstance(onConfigSaved: (WatermarkConfig) -> Unit): WatermarkSettingsDialog {
            return WatermarkSettingsDialog().apply {
                this.onConfigSaved = onConfigSaved
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferencesManager = PreferencesManager(requireContext())

        // 初始化文件选择器
        fontPickerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleFontSelection(uri)
                }
            }
        }

        imagePickerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleImageSelection(uri)
                }
            }
        }

        paletteImagePickerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    result.data?.data?.let { uri ->
                        handlePaletteImageSelection(uri)
                    }
                }
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogWatermarkSettingsBinding.inflate(LayoutInflater.from(context))

        setupViews()
        loadSavedSettings()

        // 使用兼容的主题创建对话框
        val themedContext = ContextThemeWrapper(
            requireContext(),
            androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog
        )
        return AlertDialog.Builder(themedContext)
            .setView(binding.root)
            .create()
    }

    private fun setupViews() {
        // 设置滑块监听器
        binding.sliderPositionX.addOnChangeListener { _, value, _ ->
            binding.textPositionXValue.text = "${value.toInt()}%"
        }

        binding.sliderPositionY.addOnChangeListener { _, value, _ ->
            binding.textPositionYValue.text = "${value.toInt()}%"
        }

        // 添加文字大小滑块
        binding.sliderTextSize.addOnChangeListener { _, value, _ ->
            binding.textTextSizeValue.text = "${value.toInt()}%"
        }

        // 添加图片大小滑块
        binding.sliderImageSize.addOnChangeListener { _, value, _ ->
            binding.textImageSizeValue.text = "${value.toInt()}%"
        }

        binding.sliderOpacity.addOnChangeListener { _, value, _ ->
            binding.textOpacityValue.text = "${value.toInt()}%"
        }

        binding.sliderTextImageSpacing.addOnChangeListener { _, value, _ ->
            binding.textSpacingValue.text = "${value.toInt()}%"
        }

        binding.sliderBorderWidth.addOnChangeListener { _, value, _ ->
            binding.textBorderWidthValue.text = "${value.toInt()}%"
        }

        // 设置复选框监听器
        binding.checkboxTextWatermark.setOnCheckedChangeListener { _, isChecked ->
            binding.cardTextSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.checkboxImageWatermark.setOnCheckedChangeListener { _, isChecked ->
            binding.cardImageSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // 设置按钮监听器
        binding.buttonSelectFont.setOnClickListener {
            selectFontFile()
        }

        binding.buttonSelectImage.setOnClickListener {
            selectImageFile()
        }

        binding.buttonCancel.setOnClickListener {
            dismiss()
        }

        binding.buttonConfirm.setOnClickListener {
            saveSettings()
        }

        // 设置颜色选择按钮监听器
        binding.buttonTextColor.setOnClickListener {
            showTextColorPicker()
        }

        binding.buttonBorderColor.setOnClickListener {
            showBorderColorPicker()
        }

        // 设置边框颜色模式切换监听器
        binding.toggleBorderColorMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.buttonManualColor.id -> {
                        isManualColorMode = true
                        binding.layoutManualColor.visibility = View.VISIBLE
                        binding.layoutAutoColor.visibility = View.GONE
                    }

                    binding.buttonAutoColor.id -> {
                        isManualColorMode = false
                        binding.layoutManualColor.visibility = View.GONE
                        binding.layoutAutoColor.visibility = View.VISIBLE
                    }
                }
            }
        }

        // 设置Palette图片选择按钮监听器
        binding.buttonSelectImageForPalette.setOnClickListener {
            selectPaletteImage()
        }

        // 设置Palette颜色按钮监听器
        setupPaletteColorButtons()
    }

    private fun setupSliders() {
        // 文字大小滑块
        binding.sliderTextSize.addOnChangeListener { _, value, _ ->
            binding.textTextSizeValue.text = "${value.toInt()}%"
        }

        // 图片大小滑块
        binding.sliderImageSize.addOnChangeListener { _, value, _ ->
            binding.textImageSizeValue.text = "${value.toInt()}%"
        }
    }

    private fun loadSavedSettings() {
        val config = preferencesManager.getWatermarkConfig()

        binding.checkboxTextWatermark.isChecked = config.enableTextWatermark
        binding.checkboxImageWatermark.isChecked = config.enableImageWatermark

        binding.sliderPositionX.value = config.positionX
        binding.sliderPositionY.value = config.positionY
        binding.sliderTextSize.value = config.textSize
        binding.sliderImageSize.value = config.imageSize
        binding.sliderOpacity.value = config.opacity

        // 加载文字设置
        binding.editTextContent.setText(config.textContent)
        updateTextColorButton(config.textColor)
        selectedFontPath = config.fontPath
        updateFontPathDisplay()

        // 加载图片设置
        selectedImagePath = config.imagePath
        updateImagePathDisplay()
        binding.sliderTextImageSpacing.value = config.textImageSpacing

        // 加载边框设置
        binding.sliderBorderWidth.value = config.borderTopWidth // 使用上边框宽度作为默认值
        updateBorderColorButton(config.borderColor)

        // 初始化边框颜色模式
        val borderColorModeButtonId = when (config.borderColorMode) {
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL -> binding.buttonManualColor.id
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE -> binding.buttonAutoColor.id
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MATERIAL -> binding.buttonManualColor.id // Material模式暂时使用手动模式
        }
        binding.toggleBorderColorMode.check(borderColorModeButtonId)

        // 根据边框颜色模式设置UI可见性
        when (config.borderColorMode) {
            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL -> {
                isManualColorMode = true
                binding.layoutManualColor.visibility = View.VISIBLE
                binding.layoutAutoColor.visibility = View.GONE
            }

            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE -> {
                isManualColorMode = false
                binding.layoutManualColor.visibility = View.GONE
                binding.layoutAutoColor.visibility = View.VISIBLE
            }

            cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MATERIAL -> {
                // Material模式暂时使用手动模式的UI
                isManualColorMode = true
                binding.layoutManualColor.visibility = View.VISIBLE
                binding.layoutAutoColor.visibility = View.GONE
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

    private fun selectImageFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        imagePickerLauncher.launch(Intent.createChooser(intent, "选择水印图片"))
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
        } ?: "未选择字体文件"
    }

    private fun updateImagePathDisplay() {
        binding.textImagePath.text = selectedImagePath?.let { path ->
            File(path).name
        } ?: "未选择水印图片"
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

            val config = WatermarkConfig(
                isEnabled = preferencesManager.watermarkEnabled, // 修复：使用当前的水印开关状态，而不是强制设为true
                enableTextWatermark = binding.checkboxTextWatermark.isChecked,
                enableImageWatermark = binding.checkboxImageWatermark.isChecked,
                positionX = binding.sliderPositionX.value,
                positionY = binding.sliderPositionY.value,
                textSize = binding.sliderTextSize.value,
                imageSize = binding.sliderImageSize.value,
                opacity = binding.sliderOpacity.value,
                textContent = binding.editTextContent.text.toString(),
                textColor = textColor ?: "#FFFFFF",
                fontPath = selectedFontPath ?: "",
                imagePath = selectedImagePath ?: "",
                textImageSpacing = binding.sliderTextImageSpacing.value,
                // 使用单一边框宽度设置所有边框
                borderTopWidth = binding.sliderBorderWidth.value,
                borderBottomWidth = binding.sliderBorderWidth.value,
                borderLeftWidth = binding.sliderBorderWidth.value,
                borderRightWidth = binding.sliderBorderWidth.value,
                borderColor = borderColor ?: "#000000",
                letterSpacing = 0f, // 默认值
                lineSpacing = 0f, // 默认值
                borderColorMode = getBorderColorMode()
            )

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

    // 颜色选择器相关方法
    private fun showTextColorPicker() {
        val isDarkMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val currentColor = getTextColorFromButton()

        val dialog = ColorPickerDialog()
            .withColor(Color.parseColor(currentColor))
            .withAlphaEnabled(false)
            .withPresets(
                Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
                Color.CYAN, Color.MAGENTA, Color.BLACK, Color.WHITE,
                Color.GRAY, Color.DKGRAY
            )
            .withPicker(ImagePickerView::class.java)
            .withListener { _, color ->
                val hexColor = String.format("#%06X", 0xFFFFFF and color)
                updateTextColorButton(hexColor)
            }

        // 应用自定义主题
        val themeResId = if (isDarkMode) {
            cn.alittlecookie.lut2photo.lut2photo.R.style.Theme_ColorPickerDialog_Dark
        } else {
            cn.alittlecookie.lut2photo.lut2photo.R.style.Theme_ColorPickerDialog_Light
        }
        dialog.setStyle(STYLE_NORMAL, themeResId)
        dialog.show(childFragmentManager, "textColorPicker")
    }

    private fun showBorderColorPicker() {
        val isDarkMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val currentColor = getBorderColorFromButton()

        val dialog = ColorPickerDialog()
            .withColor(Color.parseColor(currentColor))
            .withAlphaEnabled(false)
            .withPresets(
                Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
                Color.CYAN, Color.MAGENTA, Color.BLACK, Color.WHITE,
                Color.GRAY, Color.DKGRAY
            )
            .withPicker(ImagePickerView::class.java)
            .withListener { _, color ->
                val hexColor = String.format("#%06X", 0xFFFFFF and color)
                updateBorderColorButton(hexColor)
            }

        // 应用自定义主题
        val themeResId = if (isDarkMode) {
            cn.alittlecookie.lut2photo.lut2photo.R.style.Theme_ColorPickerDialog_Dark
        } else {
            cn.alittlecookie.lut2photo.lut2photo.R.style.Theme_ColorPickerDialog_Light
        }
        dialog.setStyle(STYLE_NORMAL, themeResId)
        dialog.show(childFragmentManager, "borderColorPicker")
    }

    private fun updateTextColorButton(colorHex: String) {
        try {
            val color = Color.parseColor(colorHex)
            binding.buttonTextColor.setBackgroundColor(color)
            binding.buttonTextColor.text = colorHex
            binding.buttonTextColor.setTextColor(if (isLightColor(color)) Color.BLACK else Color.WHITE)
            binding.buttonTextColor.tag = colorHex
        } catch (e: Exception) {
            binding.buttonTextColor.setBackgroundColor(Color.WHITE)
            binding.buttonTextColor.text = "#FFFFFF"
            binding.buttonTextColor.setTextColor(Color.BLACK)
            binding.buttonTextColor.tag = "#FFFFFF"
        }
    }

    private fun updateBorderColorButton(colorHex: String) {
        try {
            val color = Color.parseColor(colorHex)
            binding.buttonBorderColor.setBackgroundColor(color)
            binding.buttonBorderColor.text = colorHex
            binding.buttonBorderColor.setTextColor(if (isLightColor(color)) Color.BLACK else Color.WHITE)
            binding.buttonBorderColor.tag = colorHex
        } catch (e: Exception) {
            binding.buttonBorderColor.setBackgroundColor(Color.BLACK)
            binding.buttonBorderColor.text = "#000000"
            binding.buttonBorderColor.setTextColor(Color.WHITE)
            binding.buttonBorderColor.tag = "#000000"
        }
    }

    private fun getTextColorFromButton(): String {
        return binding.buttonTextColor.tag as? String ?: "#FFFFFF"
    }

    private fun getBorderColorFromButton(): String {
        return binding.buttonBorderColor.tag as? String ?: "#000000"
    }

    private fun getBorderColorMode(): cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode {
        return when (binding.toggleBorderColorMode.checkedButtonId) {
            binding.buttonManualColor.id -> cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL
            binding.buttonAutoColor.id -> cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.PALETTE
            else -> cn.alittlecookie.lut2photo.lut2photo.model.BorderColorMode.MANUAL
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val brightness = (red * 0.299 + green * 0.587 + blue * 0.114) / 255
        return brightness > 0.5
    }

    // Palette相关方法
    private fun selectPaletteImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        paletteImagePickerLauncher.launch(intent)
    }

    private fun handlePaletteImageSelection(uri: Uri) {
        try {
            val bitmap = MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
            extractColorsFromBitmap(bitmap)
        } catch (e: Exception) {
            Toast.makeText(context, "无法加载图片: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractColorsFromBitmap(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            palette?.let {
                val colors = mutableListOf<Int>()

                // 提取六种不同类型的颜色
                it.dominantSwatch?.rgb?.let { color -> colors.add(color) }
                it.vibrantSwatch?.rgb?.let { color -> colors.add(color) }
                it.mutedSwatch?.rgb?.let { color -> colors.add(color) }
                it.darkVibrantSwatch?.rgb?.let { color -> colors.add(color) }
                it.lightVibrantSwatch?.rgb?.let { color -> colors.add(color) }
                it.darkMutedSwatch?.rgb?.let { color -> colors.add(color) }

                // 如果颜色不足6个，用默认颜色补充
                while (colors.size < 6) {
                    colors.add(Color.BLACK)
                }

                paletteColors = colors.take(6)
                updatePaletteColorButtons()
                binding.gridPaletteColors.visibility = View.VISIBLE
            }
        }
    }

    private fun setupPaletteColorButtons() {
        val buttons = listOf(
            binding.buttonPaletteColor1,
            binding.buttonPaletteColor2,
            binding.buttonPaletteColor3,
            binding.buttonPaletteColor4,
            binding.buttonPaletteColor5,
            binding.buttonPaletteColor6
        )

        buttons.forEachIndexed { index, button ->
            button.setOnClickListener {
                if (index < paletteColors.size) {
                    val color = paletteColors[index]
                    val hexColor = String.format("#%06X", 0xFFFFFF and color)
                    updateBorderColorButton(hexColor)
                }
            }
        }
    }

    private fun updatePaletteColorButtons() {
        val buttons = listOf(
            binding.buttonPaletteColor1,
            binding.buttonPaletteColor2,
            binding.buttonPaletteColor3,
            binding.buttonPaletteColor4,
            binding.buttonPaletteColor5,
            binding.buttonPaletteColor6
        )

        val labels = listOf("主色", "鲜艳", "柔和", "深色", "浅色", "侘寂")

        buttons.forEachIndexed { index, button ->
            if (index < paletteColors.size) {
                val color = paletteColors[index]
                button.setBackgroundColor(color)
                button.text = labels[index]
                button.setTextColor(if (isLightColor(color)) Color.BLACK else Color.WHITE)
                button.visibility = View.VISIBLE
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