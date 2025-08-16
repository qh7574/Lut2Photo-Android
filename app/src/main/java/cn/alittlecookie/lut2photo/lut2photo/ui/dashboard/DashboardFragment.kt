package cn.alittlecookie.lut2photo.lut2photo.ui.dashboard

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.adapter.ImageAdapter
import cn.alittlecookie.lut2photo.lut2photo.adapter.LutAdapter
import cn.alittlecookie.lut2photo.lut2photo.core.LutProcessor
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentDashboardBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import cn.alittlecookie.lut2photo.lut2photo.utils.WrapContentGridLayoutManager
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val dashboardViewModel: DashboardViewModel by viewModels()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var imageAdapter: ImageAdapter
    private lateinit var lutAdapter: LutAdapter
    private lateinit var lutManager: LutManager
    private var selectedLutItem: LutItem? = null
    private var availableLuts: List<LutItem> = emptyList()

    // Activity Result Launchers
    private val selectImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris?.forEach { uri ->
            addImageToList(uri)
        }
    }

    private val selectOutputFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            preferencesManager.dashboardOutputFolder = it.toString()
            updateOutputFolderDisplay()
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
        binding.buttonStopProcessing?.setOnClickListener {
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
            toggleSection(binding.layoutParamsContent, binding.buttonToggleParams as ImageView)
        }

        // 设置文件设置切换
        binding.layoutFileSettingsHeader.setOnClickListener {
            toggleSection(
                binding.layoutFileSettingsContent,
                binding.buttonToggleFileSettings as ImageView
            )
        }

        // 设置滑块
        setupSliders()
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

    private fun addImageToList(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val previewBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            // 直接传递 Uri 给 ViewModel，让 ViewModel 处理 ImageItem 的创建
            dashboardViewModel.addImage(uri)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "添加图片失败: ${e.message}", Toast.LENGTH_SHORT)
                .show()
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
                binding.spinnerLut.adapter = adapter

                binding.spinnerLut.setOnItemSelectedListener(object :
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
                })

                // 恢复选中的LUT
                val savedLutUri = preferencesManager.dashboardLutUri
                if (!savedLutUri.isNullOrEmpty()) {
                    val savedLutIndex = availableLuts.indexOfFirst { it.filePath == savedLutUri }
                    if (savedLutIndex >= 0) {
                        binding.spinnerLut.setSelection(savedLutIndex + 1) // +1 因为第一项是"未选择"
                        selectedLutItem = availableLuts[savedLutIndex]
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

    private fun loadSavedSettings() {
        // 加载强度设置 - 修复：将0-1范围转换为0-100范围
        binding.sliderStrength.value = preferencesManager.dashboardStrength * 100f

        // 加载质量设置 - 修复类型问题
        binding.sliderQuality.value = preferencesManager.dashboardQuality

        // 加载抖动类型设置
        val ditherType = getDitherType()
        val buttonId = when (ditherType) {
            LutProcessor.DitherType.FLOYD_STEINBERG -> R.id.button_dither_floyd
            LutProcessor.DitherType.RANDOM -> R.id.button_dither_random
            LutProcessor.DitherType.NONE -> R.id.button_dither_none
        }
        binding.toggleGroupDither.check(buttonId)

        // 更新显示值
        binding.textStrengthValue?.text = "${(preferencesManager.dashboardStrength * 100).toInt()}%"
        binding.textQualityValue?.text = "${preferencesManager.dashboardQuality.toInt()}"

        // 加载输出文件夹
        updateOutputFolderDisplay()
    }

    private fun setupSliders() {
        // 强度滑块 - 修复：将0-100范围转换为0-1范围存储
        binding.sliderStrength.addOnChangeListener { _, value, _ ->
            preferencesManager.dashboardStrength = value / 100f
            binding.textStrengthValue?.text = "${value.toInt()}%"
        }

        // 质量滑块 - 修复类型问题
        binding.sliderQuality.addOnChangeListener { _, value, _ ->
            preferencesManager.dashboardQuality = value
            binding.textQualityValue?.text = "${value.toInt()}"
        }
    }

    private fun saveCurrentSettings() {
        // 修复：将slider值（0-100）转换为存储值（0-1）
        preferencesManager.dashboardStrength = binding.sliderStrength.value / 100f
        preferencesManager.dashboardQuality = binding.sliderQuality.value
        preferencesManager.dashboardDitherType = getDitherType().name
    }

    private fun updateOutputFolderDisplay() {
        val folderUri = preferencesManager.dashboardOutputFolder
        val displayName = if (folderUri.isNotEmpty()) {
            getDisplayNameFromUri(folderUri)
        } else {
            getString(R.string.no_folder_selected)
        }
        binding.textOutputFolder?.text = displayName
    }

    private fun restoreUIState() {
        // 恢复高级设置展开状态
        binding.layoutParamsContent.visibility = if (preferencesManager.dashboardParamsExpanded) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // 恢复文件设置展开状态
        binding.layoutFileSettingsContent.visibility =
            if (preferencesManager.dashboardFileSettingsExpanded) {
                View.VISIBLE
            } else {
                View.GONE
            }

        // 更新按钮图标
        updateToggleButtonIcon(
            binding.buttonToggleParams as ImageView,
            preferencesManager.dashboardParamsExpanded
        )
        updateToggleButtonIcon(
            binding.buttonToggleFileSettings as ImageView,
            preferencesManager.dashboardFileSettingsExpanded
        )
    }

    private fun toggleSection(layout: View, button: ImageView) {
        val isExpanded = layout.visibility == View.VISIBLE

        if (isExpanded) {
            layout.visibility = View.GONE
            when (layout.id) {
                R.id.layout_params_content -> preferencesManager.dashboardParamsExpanded = false
                R.id.layout_file_settings_content -> preferencesManager.dashboardFileSettingsExpanded =
                    false
            }
        } else {
            layout.visibility = View.VISIBLE
            when (layout.id) {
                R.id.layout_params_content -> preferencesManager.dashboardParamsExpanded = true
                R.id.layout_file_settings_content -> preferencesManager.dashboardFileSettingsExpanded =
                    true
            }
        }

        updateToggleButtonIcon(button, !isExpanded)
    }

    private fun updateToggleButtonIcon(button: ImageView, isExpanded: Boolean) {
        val iconRes = if (isExpanded) {
            R.drawable.ic_expand_less
        } else {
            R.drawable.ic_expand_more
        }
        button.setImageResource(iconRes)
    }

    private fun getFileNameFromUri(uri: Uri): String {
        return uri.lastPathSegment ?: "未知文件"
    }

    private fun getDisplayNameFromUri(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            uri.lastPathSegment?.replace(":", "/") ?: "未知文件夹"
        } catch (e: Exception) {
            "未知文件夹"
        }
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageAdapter { imageItem ->
            dashboardViewModel.removeImage(imageItem)
        }

        binding.recyclerViewImages.apply {
            layoutManager = WrapContentGridLayoutManager(requireContext(), 2)
            adapter = imageAdapter
        }
    }

    private fun updateImageCount(count: Int) {
        binding.textImageCount?.text = getString(R.string.selected_images_count, count)
    }

    private fun updateProcessingStatus(status: String) {
        binding.textProcessingStatus?.text = status
    }

    private fun updateProcessedCount(processed: Int, total: Int) {
        binding.textProcessedCount?.text = getString(R.string.processed_count, processed, total)
    }

    private fun getDitherType(): LutProcessor.DitherType {
        return when (preferencesManager.dashboardDitherType.lowercase()) {
            "floyd_steinberg" -> LutProcessor.DitherType.FLOYD_STEINBERG
            "random" -> LutProcessor.DitherType.RANDOM
            else -> LutProcessor.DitherType.NONE
        }
    }

    private fun startProcessing() {
        val images = dashboardViewModel.selectedImages.value ?: emptyList()
        val lutItem = selectedLutItem

        if (images.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.no_images_selected),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (lutItem == null) {
            Toast.makeText(
                requireContext(),
                getString(R.string.no_lut_selected),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val outputFolder = preferencesManager.dashboardOutputFolder
        if (outputFolder.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.no_output_folder_selected),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val params = LutProcessor.ProcessingParams(
            strength = (preferencesManager.dashboardStrength * 100).toInt(), // 修复：转换为0-100范围
            quality = preferencesManager.dashboardQuality.toInt(),
            ditherType = getDitherType()
        )

        dashboardViewModel.startProcessing(
            images = images,
            lutItem = lutItem,
            params = params,
            outputFolderUri = outputFolder
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