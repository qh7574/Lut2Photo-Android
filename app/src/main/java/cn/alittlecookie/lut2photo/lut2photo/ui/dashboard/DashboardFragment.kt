package cn.alittlecookie.lut2photo.lut2photo.ui.dashboard

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.adapter.ImageAdapter
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.LutProcessor
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentDashboardBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import cn.alittlecookie.lut2photo.lut2photo.utils.WrapContentGridLayoutManager
import cn.alittlecookie.lut2photo.ui.bottomsheet.WatermarkSettingsBottomSheet
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val dashboardViewModel: DashboardViewModel by viewModels()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var imageAdapter: ImageAdapter
    private lateinit var lutManager: LutManager
    private var selectedLutItem: LutItem? = null
    private var selectedLut2Item: LutItem? = null  // 第二个LUT
    private var availableLuts: List<LutItem> = emptyList()

    // Activity Result Launchers
    private val selectImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.let { uriList ->
            if (uriList.isNotEmpty()) {
                // 使用批量添加方法而不是逐个添加
                dashboardViewModel.addImages(uriList)
            }
        }
    }

    private val selectOutputFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // 添加权限持久化
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                preferencesManager.dashboardOutputFolder = it.toString()
                updateOutputFolderDisplay()
            } catch (e: SecurityException) {
                Log.e("DashboardFragment", "无法获取持久化URI权限", e)
                // 显示错误提示给用户
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        preferencesManager = PreferencesManager(requireContext())
        lutManager = LutManager(requireContext())

        setupViews()
        setupRecyclerView()
        setupLutSpinner()
        observeViewModel()
        loadSavedSettings()
        restoreUIState()
    }

    private fun setupViews() {
        // 图片选择按钮
        binding.buttonSelectImages.setOnClickListener {
            selectImagesLauncher.launch("image/*")
        }

        // 输出文件夹选择按钮
        binding.buttonSelectOutputFolder.setOnClickListener {
            selectOutputFolderLauncher.launch(null)
        }

        // 开始处理按钮
        binding.buttonStartProcessing.setOnClickListener {
            startProcessing()
        }

        // 停止处理按钮
        binding.buttonStopProcessing.setOnClickListener {
            stopProcessing()
        }

        // 清空图片按钮
        binding.buttonClearImages.setOnClickListener {
            clearImages()
        }

        // 设置抖动类型切换组
        setupDitherToggleGroup()

        // 设置高级设置切换
        binding.layoutParamsHeader.setOnClickListener {
            toggleSection(binding.layoutParamsContent, binding.buttonToggleParams)
        }

        // 设置文件设置切换
        binding.layoutFileSettingsHeader.setOnClickListener {
            toggleSection(
                binding.layoutFileSettingsContent,
                binding.buttonToggleFileSettings
            )
        }

        // 设置滑块
        setupSliders()

        // 水印开关监听器
        binding.switchWatermark.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.dashboardWatermarkEnabled = isChecked  // 使用分离的开关
            binding.buttonWatermarkSettings.isEnabled = isChecked
        }

        // 在loadSavedSettings方法中
        binding.switchWatermark.isChecked =
            preferencesManager.dashboardWatermarkEnabled  // 加载分离的开关状态
        binding.buttonWatermarkSettings.isEnabled = preferencesManager.dashboardWatermarkEnabled
        // 水印设置按钮
        binding.buttonWatermarkSettings.setOnClickListener {
            val bottomSheet = WatermarkSettingsBottomSheet.newInstance { config ->
                // 设置保存后的回调
            }
            bottomSheet.show(parentFragmentManager, "WatermarkSettingsBottomSheet")
        }
    }

    private fun setupDitherToggleGroup() {
        binding.toggleGroupDither.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val ditherType = when (checkedId) {
                    R.id.button_dither_none -> LutProcessor.DitherType.NONE
                    R.id.button_dither_floyd -> LutProcessor.DitherType.FLOYD_STEINBERG
                    R.id.button_dither_random -> LutProcessor.DitherType.RANDOM
                    else -> LutProcessor.DitherType.NONE
                }
                preferencesManager.dashboardDitherType = ditherType.name
            }
        }
    }

    private fun setupLutSpinner() {
        lifecycleScope.launch {
            try {
                availableLuts = lutManager.getAllLuts()
                val lutNames = mutableListOf<String>()
                lutNames.add(getString(R.string.no_lut_selected))
                lutNames.addAll(availableLuts.map { it.name })

                val adapter =
                    ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, lutNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

                // 设置主要LUT下拉框
                binding.spinnerLut.adapter = adapter
                binding.spinnerLut.onItemSelectedListener = object :
                    android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        selectedLutItem = if (position == 0) null else availableLuts[position - 1]
                        selectedLutItem?.let {
                            preferencesManager.dashboardLutUri = it.filePath
                        }
                        val hasImages =
                            dashboardViewModel.selectedImages.value?.isNotEmpty() == true
                        binding.buttonStartProcessing.isEnabled =
                            hasImages && selectedLutItem != null
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                        selectedLutItem = null
                        binding.buttonStartProcessing.isEnabled = false
                    }
                }

                // 设置第二个LUT下拉框
                binding.spinnerLut2.adapter = adapter
                binding.spinnerLut2.onItemSelectedListener = object :
                    android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        selectedLut2Item = if (position == 0) null else availableLuts[position - 1]
                        selectedLut2Item?.let {
                            preferencesManager.dashboardLut2Uri = it.filePath
                        } ?: run {
                            preferencesManager.dashboardLut2Uri = null
                        }

                        Log.d(
                            "DashboardFragment",
                            "第二个LUT选择更新: ${selectedLut2Item?.name ?: "未选择"}"
                        )
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                        selectedLut2Item = null
                    }
                }

                // 恢复选中的主要LUT
                val savedLutUri = preferencesManager.dashboardLutUri
                if (!savedLutUri.isNullOrEmpty()) {
                    val savedLutIndex = availableLuts.indexOfFirst { it.filePath == savedLutUri }
                    if (savedLutIndex >= 0) {
                        binding.spinnerLut.setSelection(savedLutIndex + 1) // +1 因为第一项是"未选择"
                        selectedLutItem = availableLuts[savedLutIndex]
                    }
                }

                // 恢复选中的第二个LUT
                val savedLut2Uri = preferencesManager.dashboardLut2Uri
                if (!savedLut2Uri.isNullOrEmpty()) {
                    val savedLut2Index = availableLuts.indexOfFirst { it.filePath == savedLut2Uri }
                    if (savedLut2Index >= 0) {
                        binding.spinnerLut2.setSelection(savedLut2Index + 1)
                        selectedLut2Item = availableLuts[savedLut2Index]
                        Log.d("DashboardFragment", "恢复第二个LUT选择: ${selectedLut2Item?.name}")
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "加载LUT文件失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun observeViewModel() {
        dashboardViewModel.selectedImages.observe(viewLifecycleOwner) { images ->
            imageAdapter.submitList(images)
            updateImageCount(images.size)
            binding.buttonStartProcessing.isEnabled = images.isNotEmpty() && selectedLutItem != null
        }

        dashboardViewModel.processingStatus.observe(viewLifecycleOwner) { status ->
            updateProcessingStatus(status)
        }

        dashboardViewModel.processedCount.observe(viewLifecycleOwner) { processed ->
            val total = dashboardViewModel.totalCount.value ?: 0
            updateProcessedCount(processed, total)
        }

        // 添加处理完成监听器
        dashboardViewModel.processingCompleted.observe(viewLifecycleOwner) { result ->
            result?.let {
                if (it.isSuccess && it.processedCount > 0) {
                    // 处理成功完成，自动清空已选择的图片
                    dashboardViewModel.clearImages()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.processing_completed_and_cleared, it.processedCount),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                // 重置处理完成状态
                dashboardViewModel.resetProcessingCompleted()
            }
        }
    }

    private fun stopProcessing() {
        dashboardViewModel.stopProcessing()
    }

    private fun clearImages() {
        dashboardViewModel.clearImages()
    }

    private fun getDitherType(): ILutProcessor.DitherType {
        val savedDitherType = preferencesManager.dashboardDitherType
        return try {
            ILutProcessor.DitherType.valueOf(savedDitherType.uppercase())
        } catch (_: Exception) {
            ILutProcessor.DitherType.NONE
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadSavedSettings() {
        // 加载强度设置
        binding.sliderStrength.value = preferencesManager.dashboardStrength * 100f

        // 加载第二个LUT强度设置
        val lut2Strength = preferencesManager.dashboardLut2Strength * 100f
        binding.sliderLut2Strength.value = lut2Strength

        Log.d(
            "DashboardFragment",
            "加载设置 - 主LUT强度: ${preferencesManager.dashboardStrength}, 第二个LUT强度: ${preferencesManager.dashboardLut2Strength} (滑块值: $lut2Strength)"
        )

        // 加载质量设置
        binding.sliderQuality.value = preferencesManager.dashboardQuality

        // 加载抖动类型设置 - 修复枚举类型比较
        val ditherType = getDitherType()
        val buttonId = when (ditherType) {
            ILutProcessor.DitherType.FLOYD_STEINBERG -> R.id.button_dither_floyd
            ILutProcessor.DitherType.RANDOM -> R.id.button_dither_random
            ILutProcessor.DitherType.NONE -> R.id.button_dither_none
        }
        binding.toggleGroupDither.check(buttonId)

        // 更新显示值
        binding.textStrengthValue.text = "${(preferencesManager.dashboardStrength * 100).toInt()}%"
        binding.textLut2StrengthValue.text =
            "${(preferencesManager.dashboardLut2Strength * 100).toInt()}%"
        binding.textQualityValue.text = "${preferencesManager.dashboardQuality.toInt()}"

        // 加载输出文件夹
        updateOutputFolderDisplay()

        // 修复：加载水印设置 - 使用分离的开关
        binding.switchWatermark.isChecked = preferencesManager.dashboardWatermarkEnabled
        binding.buttonWatermarkSettings.isEnabled = preferencesManager.dashboardWatermarkEnabled
    }

    private fun updateOutputFolderDisplay() {
        val outputFolder = preferencesManager.dashboardOutputFolder
        binding.textOutputFolder.text = if (outputFolder.isNotEmpty()) {
            getDisplayNameFromUri(outputFolder)
        } else {
            getString(R.string.folder_not_set)
        }
    }

    private fun getDisplayNameFromUri(uriString: String): String {
        return try {
            val uri = uriString.toUri()
            uri.lastPathSegment?.replace(":", "/") ?: "未知文件夹"
        } catch (_: Exception) {
            "未知文件夹"
        }
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageAdapter { imageItem ->
            dashboardViewModel.removeImage(imageItem)
        }
        binding.recyclerViewImages.apply {
            adapter = imageAdapter
            layoutManager = WrapContentGridLayoutManager(requireContext(), 3)
        }
    }

    private fun restoreUIState() {
        // 恢复UI状态，如折叠面板的展开/收起状态
        val isParamsExpanded = preferencesManager.dashboardParamsExpanded
        val isFileSettingsExpanded = preferencesManager.dashboardFileSettingsExpanded

        if (!isParamsExpanded) {
            binding.layoutParamsContent.visibility = View.GONE
            binding.buttonToggleParams.rotation = 180f
        }

        if (!isFileSettingsExpanded) {
            binding.layoutFileSettingsContent.visibility = View.GONE
            binding.buttonToggleFileSettings.rotation = 180f
        }
    }

    private fun toggleSection(contentLayout: View, toggleButton: ImageView) {
        val isVisible = contentLayout.isVisible

        if (isVisible) {
            contentLayout.visibility = View.GONE
            toggleButton.animate().rotation(180f).setDuration(200).start()
        } else {
            contentLayout.visibility = View.VISIBLE
            toggleButton.animate().rotation(0f).setDuration(200).start()
        }

        // 保存状态
        when (contentLayout.id) {
            R.id.layout_params_content -> {
                preferencesManager.dashboardParamsExpanded = !isVisible
            }

            R.id.layout_file_settings_content -> {
                preferencesManager.dashboardFileSettingsExpanded = !isVisible
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupSliders() {
        // 设置强度滑块
        binding.sliderStrength.addOnChangeListener { _, value, _ ->
            val strengthValue = (value / 100f)
            preferencesManager.dashboardStrength = strengthValue
            binding.textStrengthValue.text = "${value.toInt()}%"
        }

        // 设置第二个LUT强度滑块
        binding.sliderLut2Strength.addOnChangeListener { _, value, _ ->
            val lut2StrengthValue = (value / 100f)
            preferencesManager.dashboardLut2Strength = lut2StrengthValue
            binding.textLut2StrengthValue.text = "${value.toInt()}%"
            Log.d("DashboardFragment", "第二个LUT强度设置为: $lut2StrengthValue (滑块值: $value)")
        }

        // 设置质量滑块
        binding.sliderQuality.addOnChangeListener { _, value, _ ->
            preferencesManager.dashboardQuality = value
            binding.textQualityValue.text = "${value.toInt()}"
        }
    }

    private fun updateImageCount(count: Int) {
        binding.textImageCount.text = getString(R.string.image_count_format, count)
    }

    private fun updateProcessingStatus(status: String) {
        binding.textProcessingStatus.text = status
    }

    private fun updateProcessedCount(processed: Int, total: Int) {
        binding.textProcessedCount.text =
            getString(R.string.processed_count_format, processed, total)

        // 更新进度条
        if (total > 0) {
            val progress = (processed * 100) / total
            binding.progressBar.progress = progress
            binding.progressBar.visibility = if (processed < total) View.VISIBLE else View.GONE
        }
    }

    private fun saveCurrentSettings() {
        // 当前设置已经通过滑块监听器实时保存，这里可以做额外的保存操作
        preferencesManager.apply {
            // 保存当前选中的LUT
            selectedLutItem?.let {
                dashboardLutUri = it.filePath
            }
        }
    }

    private fun startProcessing() {
        val selectedLut = selectedLutItem ?: return
        val outputFolderUri = preferencesManager.dashboardOutputFolder

        if (outputFolderUri.isEmpty()) {
            Toast.makeText(requireContext(), "请选择输出文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("DashboardFragment", "开始处理，主要LUT: ${selectedLut.name}")
        selectedLut2Item?.let {
            Log.d("DashboardFragment", "第二个LUT: ${it.name}, 路径: ${it.filePath}")
        } ?: Log.d("DashboardFragment", "未选择第二个LUT")

        // 添加更详细的调试信息
        Log.d("DashboardFragment", "selectedLut2Item是否为null: ${selectedLut2Item == null}")
        Log.d("DashboardFragment", "保存的第二个LUT URI: ${preferencesManager.dashboardLut2Uri}")
        Log.d("DashboardFragment", "第二个LUT强度滑块值: ${binding.sliderLut2Strength.value}")
        Log.d(
            "DashboardFragment",
            "PreferencesManager中的LUT2强度: ${preferencesManager.dashboardLut2Strength}"
        )

        // 修复参数创建
        val params = ILutProcessor.ProcessingParams(
            strength = preferencesManager.dashboardStrength,
            lut2Strength = preferencesManager.dashboardLut2Strength,
            quality = preferencesManager.dashboardQuality.toInt(),
            ditherType = getDitherType()
        )

        Log.d(
            "DashboardFragment",
            "处理参数: 强度=${params.strength}, LUT2强度=${params.lut2Strength}, 质量=${params.quality}, 抖动=${params.ditherType}"
        )

        // 修复方法调用参数顺序
        val images = dashboardViewModel.selectedImages.value ?: emptyList()
        dashboardViewModel.startProcessing(
            images,
            selectedLut,
            selectedLut2Item,
            params,
            outputFolderUri
        )
    }

    override fun onPause() {
        super.onPause()
        saveCurrentSettings()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentSettings()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
