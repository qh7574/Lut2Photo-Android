package cn.alittlecookie.lut2photo.ui.dialog

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import cn.alittlecookie.lut2photo.lut2photo.databinding.DialogWatermarkSettingsBinding
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import java.io.File
import java.io.FileOutputStream

class WatermarkSettingsDialog : DialogFragment() {

    private var _binding: DialogWatermarkSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferencesManager: PreferencesManager
    private var onConfigSaved: ((WatermarkConfig) -> Unit)? = null

    private lateinit var fontPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>

    private var selectedFontPath: String? = null
    private var selectedImagePath: String? = null

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
        binding.editTextColor.setText(config.textColor)
        selectedFontPath = config.fontPath
        updateFontPathDisplay()

        // 加载图片设置
        selectedImagePath = config.imagePath
        updateImagePathDisplay()
        binding.sliderTextImageSpacing.value = config.textImageSpacing

        // 加载边框设置
        binding.sliderBorderWidth.value = config.borderTopWidth // 使用上边框宽度作为默认值
        binding.editBorderColor.setText(config.borderColor)

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
                lineSpacing = 0f // 默认值
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}