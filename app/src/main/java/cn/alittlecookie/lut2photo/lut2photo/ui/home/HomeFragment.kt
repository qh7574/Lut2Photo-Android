package cn.alittlecookie.lut2photo.lut2photo.ui.home

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
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
import cn.alittlecookie.lut2photo.lut2photo.core.LutProcessor
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentHomeBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.service.FolderMonitorService
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by viewModels()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var lutManager: LutManager
    private var selectedLutItem: LutItem? = null
    private var availableLuts: List<LutItem> = emptyList()

    // Activity Result Launchers
    private val selectInputFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            preferencesManager.homeInputFolder = it.toString()
            updateInputFolderDisplay()
        }
    }

    private val selectOutputFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            preferencesManager.homeOutputFolder = it.toString()
            updateOutputFolderDisplay()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        preferencesManager = PreferencesManager(requireContext())
        lutManager = LutManager(requireContext())
        
        setupViews()
        setupLutSpinner()
        observeViewModel()
        loadSavedSettings()
        restoreUIState()
    }

    private fun setupViews() {
        // 输入文件夹选择按钮
        binding.buttonSelectInputFolder.setOnClickListener {
            selectInputFolderLauncher.launch(null)
        }

        // 输出文件夹选择按钮
        binding.buttonSelectOutputFolder.setOnClickListener {
            selectOutputFolderLauncher.launch(null)
        }

        // 开始监控按钮
        binding.buttonStartMonitoring.setOnClickListener {
            startMonitoring()
        }

        // 停止监控按钮
        binding.buttonStopMonitoring.setOnClickListener {
            stopMonitoring()
        }

        // 参数设置折叠/展开
        binding.layoutParamsHeader.setOnClickListener {
            toggleSection(binding.layoutParamsContent, binding.buttonToggleParams)
        }

        // 文件设置折叠/展开
        binding.layoutFileSettingsHeader.setOnClickListener {
            toggleSection(binding.layoutFileSettingsContent, binding.buttonToggleFileSettings)
        }

        // 设置滑块
        setupSliders()

        // 设置抖动按钮
        setupDitherButtons()
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
                preferencesManager.homeDitherType = ditherType.name
            }
        }
    }

    private fun setupDitherButtons() {
        setupDitherToggleGroup()
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

                binding.spinnerLut.onItemSelectedListener =
                    object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {
                            selectedLutItem =
                                if (position == 0) null else availableLuts[position - 1]
                            selectedLutItem?.let {
                                preferencesManager.homeLutUri = it.filePath
                            }
                            updateMonitoringButtonState()
                    }

                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                            selectedLutItem = null
                            updateMonitoringButtonState()
                    }
                }

                // 恢复选中的LUT
                val savedLutUri = preferencesManager.homeLutUri
                if (!savedLutUri.isNullOrEmpty()) {
                    val savedLutIndex = availableLuts.indexOfFirst { it.filePath == savedLutUri }
                    if (savedLutIndex >= 0) {
                        binding.spinnerLut.setSelection(savedLutIndex + 1)
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
        homeViewModel.statusText.observe(viewLifecycleOwner) { status ->
            binding.textMonitoringStatus.text = status
            updateMonitoringButtonState()
        }

        homeViewModel.isMonitoring.observe(viewLifecycleOwner) { isMonitoring ->
            binding.buttonStartMonitoring.isEnabled = !isMonitoring && canStartMonitoring()
            binding.buttonStopMonitoring.isEnabled = isMonitoring
        }
    }

    private fun updateMonitoringButtonState() {
        val isMonitoring = homeViewModel.isMonitoring.value == true
        binding.buttonStartMonitoring.isEnabled = !isMonitoring && canStartMonitoring()
        binding.buttonStopMonitoring.isEnabled = isMonitoring
    }

    private fun canStartMonitoring(): Boolean {
        return preferencesManager.homeInputFolder.isNotEmpty() &&
                preferencesManager.homeOutputFolder.isNotEmpty() &&
                selectedLutItem != null
    }

    private fun startMonitoring() {
        if (!canStartMonitoring()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.monitoring_requirements_not_met),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // 添加空值检查
        val lutItem = selectedLutItem
        if (lutItem == null) {
            Toast.makeText(requireContext(), "请先选择LUT文件", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(requireContext(), FolderMonitorService::class.java).apply {
            action = FolderMonitorService.ACTION_START_MONITORING
            putExtra(FolderMonitorService.EXTRA_INPUT_FOLDER, preferencesManager.homeInputFolder)
            putExtra(FolderMonitorService.EXTRA_OUTPUT_FOLDER, preferencesManager.homeOutputFolder)
            putExtra(FolderMonitorService.EXTRA_LUT_FILE_PATH, lutManager.getLutFilePath(lutItem))
            // 修复强度参数传递 - 直接传递原始值，不要乘以100
            putExtra(FolderMonitorService.EXTRA_STRENGTH, preferencesManager.homeStrength.toInt())
            putExtra(FolderMonitorService.EXTRA_QUALITY, preferencesManager.homeQuality.toInt())
            putExtra(FolderMonitorService.EXTRA_DITHER, getDitherType().name)
        }
        requireContext().startForegroundService(intent)
        homeViewModel.setMonitoring(true)
    }

    private fun stopMonitoring() {
        val intent = Intent(requireContext(), FolderMonitorService::class.java)
        intent.action = FolderMonitorService.ACTION_STOP_MONITORING
        requireContext().startService(intent)
        homeViewModel.setMonitoring(false)
    }

    @SuppressLint("SetTextI18n")
    private fun loadSavedSettings() {
        // 加载强度设置
        binding.sliderStrength.value = preferencesManager.homeStrength

        // 加载质量设置
        binding.sliderQuality.value = preferencesManager.homeQuality

        // 加载抖动类型设置
        val ditherType = getDitherType()
        val buttonId = when (ditherType) {
            LutProcessor.DitherType.FLOYD_STEINBERG -> R.id.button_dither_floyd
            LutProcessor.DitherType.RANDOM -> R.id.button_dither_random
            LutProcessor.DitherType.NONE -> R.id.button_dither_none
        }
        binding.toggleGroupDither.check(buttonId)

        // 更新显示值 - 修复强度显示问题
        binding.textStrengthValue.text = "${preferencesManager.homeStrength.toInt()}%"
        binding.textQualityValue.text = "${preferencesManager.homeQuality.toInt()}"

        // 加载文件夹
        updateInputFolderDisplay()
        updateOutputFolderDisplay()
    }

    @SuppressLint("SetTextI18n")
    private fun setupSliders() {
        // 强度滑块 - 修复显示问题
        binding.sliderStrength.addOnChangeListener { _, value, _ ->
            preferencesManager.homeStrength = value
            binding.textStrengthValue.text = "${value.toInt()}%"
        }

        // 质量滑块
        binding.sliderQuality.addOnChangeListener { _, value, _ ->
            preferencesManager.homeQuality = value
            binding.textQualityValue.text = "${value.toInt()}"
        }
    }

    private fun updateInputFolderDisplay() {
        val folderUri = preferencesManager.homeInputFolder
        val displayName = if (folderUri.isNotEmpty()) {
            getDisplayNameFromUri(folderUri)
        } else {
            getString(R.string.no_folder_selected)
        }
        binding.textInputFolder.text = displayName
    }

    private fun updateOutputFolderDisplay() {
        val folderUri = preferencesManager.homeOutputFolder
        val displayName = if (folderUri.isNotEmpty()) {
            getDisplayNameFromUri(folderUri)
        } else {
            getString(R.string.no_folder_selected)
        }
        binding.textOutputFolder.text = displayName
    }

    private fun restoreUIState() {
        // 恢复参数设置展开状态
        val paramsExpanded = preferencesManager.homeParamsExpanded
        if (paramsExpanded) {
            binding.layoutParamsContent.visibility = View.VISIBLE
            updateToggleButtonIcon(binding.buttonToggleParams, true)
        } else {
            binding.layoutParamsContent.visibility = View.GONE
            updateToggleButtonIcon(binding.buttonToggleParams, false)
        }

        // 恢复文件设置展开状态
        val fileSettingsExpanded = preferencesManager.homeFileSettingsExpanded
        if (fileSettingsExpanded) {
            binding.layoutFileSettingsContent.visibility = View.VISIBLE
            updateToggleButtonIcon(binding.buttonToggleFileSettings, true)
        } else {
            binding.layoutFileSettingsContent.visibility = View.GONE
            updateToggleButtonIcon(binding.buttonToggleFileSettings, false)
        }
    }

    private fun toggleSection(layout: View, button: ImageView) {
        val isExpanded = layout.isVisible

        if (isExpanded) {
            layout.visibility = View.GONE
            when (layout.id) {
                R.id.layout_params_content -> preferencesManager.homeParamsExpanded = false
                R.id.layout_file_settings_content -> preferencesManager.homeFileSettingsExpanded =
                    false
            }
        } else {
            layout.visibility = View.VISIBLE
            when (layout.id) {
                R.id.layout_params_content -> preferencesManager.homeParamsExpanded = true
                R.id.layout_file_settings_content -> preferencesManager.homeFileSettingsExpanded =
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

    private fun getDisplayNameFromUri(uriString: String): String {
        return try {
            val uri = uriString.toUri()
            uri.lastPathSegment?.replace(":", "/") ?: "未知文件夹"
        } catch (_: Exception) {
            "未知文件夹"
        }
    }

    private fun getDitherType(): LutProcessor.DitherType {
        return when (preferencesManager.homeDitherType.lowercase()) {
            "floyd_steinberg" -> LutProcessor.DitherType.FLOYD_STEINBERG
            "random" -> LutProcessor.DitherType.RANDOM
            else -> LutProcessor.DitherType.NONE
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentSettings()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentSettings()
    }

    private fun saveCurrentSettings() {
        preferencesManager.homeStrength = binding.sliderStrength.value
        preferencesManager.homeQuality = binding.sliderQuality.value
        preferencesManager.homeDitherType = getDitherType().name
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}