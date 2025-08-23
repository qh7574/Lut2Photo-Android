package cn.alittlecookie.lut2photo.lut2photo.ui.home

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
import cn.alittlecookie.lut2photo.lut2photo.core.LutProcessor
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentHomeBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.service.FolderMonitorService
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import cn.alittlecookie.lut2photo.ui.dialog.WatermarkSettingsDialog
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
            // 添加权限持久化
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                preferencesManager.homeInputFolder = it.toString()
                updateInputFolderDisplay()
            } catch (e: SecurityException) {
                Log.e("HomeFragment", "无法获取持久化URI权限", e)
                // 显示错误提示给用户
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
                preferencesManager.homeOutputFolder = it.toString()
                updateOutputFolderDisplay()
            } catch (e: SecurityException) {
                Log.e("HomeFragment", "无法获取持久化URI权限", e)
                // 显示错误提示给用户
            }
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
        loadSavedSettings()
        restoreUIState()

        // 修复：延迟观察ViewModel，确保LUT加载完成
        lifecycleScope.launch {
            // 等待LUT异步加载完成
            kotlinx.coroutines.delay(100)
            observeViewModel()

            // 只在Fragment首次创建时恢复状态，避免重复调用
            if (savedInstanceState == null) {
                homeViewModel.restoreMonitoringState()
            }
        }
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

        // 水印设置按钮
        binding.buttonWatermarkSettings.setOnClickListener {
            showWatermarkSettingsDialog()
        }

        // 添加水印开关监听器
        binding.switchWatermark.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.folderMonitorWatermarkEnabled = isChecked
            binding.buttonWatermarkSettings.isEnabled = isChecked
            Log.d("HomeFragment", "文件夹监控水印开关状态改变: $isChecked")
        }
    
        // 设置监控开关监听器 - 简化版本
        setupSwitchListener()

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

    private fun setupSwitchListener() {
        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            Log.d("HomeFragment", "开关状态改变: $isChecked")

            if (isChecked) {
                // 添加详细的调试信息
                val hasInputFolder = preferencesManager.homeInputFolder.isNotEmpty()
                val hasOutputFolder = preferencesManager.homeOutputFolder.isNotEmpty()
                val hasLutFile = selectedLutItem != null

                Log.d("HomeFragment", "检查监控启动条件:")
                Log.d(
                    "HomeFragment",
                    "  输入文件夹: $hasInputFolder (${preferencesManager.homeInputFolder})"
                )
                Log.d(
                    "HomeFragment",
                    "  输出文件夹: $hasOutputFolder (${preferencesManager.homeOutputFolder})"
                )
                Log.d(
                    "HomeFragment",
                    "  LUT文件: $hasLutFile (${selectedLutItem?.name ?: "未选择"})"
                )

                if (canStartMonitoring()) {
                    Log.d("HomeFragment", "条件满足，开始启动监控服务")
                    startMonitoring()
                } else {
                    Log.w("HomeFragment", "条件不满足，重置开关状态")
                    if (!hasLutFile && availableLuts.isNotEmpty()) {
                        showToast("LUT文件正在加载中，请稍后再试")
                    } else {
                        showToast("读取设置中。。。")
                    }

                    // 临时移除监听器，重置状态，然后恢复监听器
                    binding.switchMonitoring.setOnCheckedChangeListener(null)
                    binding.switchMonitoring.isChecked = false
                    setupSwitchListener()
                }
            } else {
                Log.d("HomeFragment", "停止监控服务")
                stopMonitoring()
            }

            // 修复：只有在成功操作后才保存开关状态
            if (isChecked && canStartMonitoring() || !isChecked) {
                preferencesManager.monitoringSwitchEnabled = binding.switchMonitoring.isChecked
                Log.d("HomeFragment", "开关状态已保存: ${binding.switchMonitoring.isChecked}")
            }
        }
    }

    private fun observeViewModel() {
        homeViewModel.statusText.observe(viewLifecycleOwner) { status ->
            binding.textMonitoringStatus.text = status
        }

        homeViewModel.isMonitoring.observe(viewLifecycleOwner) { isMonitoring ->
            // 只更新开关状态，不重新设置监听器
            binding.switchMonitoring.setOnCheckedChangeListener(null)
            binding.switchMonitoring.isChecked = isMonitoring
            setupSwitchListener() // 恢复监听器
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
                            Log.d(
                                "HomeFragment",
                                "LUT选择更新: ${selectedLutItem?.name ?: "未选择"}"
                            )
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
                        Log.d("HomeFragment", "恢复LUT选择: ${selectedLutItem?.name}")
                    }
                }

                Log.d("HomeFragment", "LUT加载完成，共${availableLuts.size}个文件")
            } catch (e: Exception) {
                Log.e("HomeFragment", "加载LUT文件失败", e)
                Toast.makeText(
                    requireContext(),
                    "加载LUT文件失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

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

        // 加载水印开关状态
        binding.switchWatermark.isChecked = preferencesManager.folderMonitorWatermarkEnabled
        binding.buttonWatermarkSettings.isEnabled = preferencesManager.folderMonitorWatermarkEnabled
    
        // 加载文件夹路径显示
        updateInputFolderDisplay()
        updateOutputFolderDisplay()

        // 修复：不要在启动时自动设置开关状态，让HomeViewModel来控制
        // 移除这行：binding.switchMonitoring.isChecked = savedSwitchState

        Log.d("HomeFragment", "设置加载完成，等待ViewModel状态检查")
    }

    private fun saveCurrentSettings() {
        // 保存滑块设置
        preferencesManager.homeStrength = binding.sliderStrength.value
        preferencesManager.homeQuality = binding.sliderQuality.value
        preferencesManager.homeDitherType = getDitherType().name

        // 保存开关状态
        preferencesManager.monitoringSwitchEnabled = binding.switchMonitoring.isChecked

        // 更新显示值
        binding.textStrengthValue.text = "${preferencesManager.homeStrength.toInt()}%"
        binding.textQualityValue.text = "${preferencesManager.homeQuality.toInt()}"

        // 确保文件夹显示是最新的
        updateInputFolderDisplay()
        updateOutputFolderDisplay()
    }

    @SuppressLint("SetTextI18n")
    private fun setupSliders() {
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

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun canStartMonitoring(): Boolean {
        val hasInputFolder = preferencesManager.homeInputFolder.isNotEmpty()
        val hasOutputFolder = preferencesManager.homeOutputFolder.isNotEmpty()
        val hasLutFile = selectedLutItem != null
        return hasInputFolder && hasOutputFolder && hasLutFile
    }

    private fun startMonitoring() {
        val intent = Intent(requireContext(), FolderMonitorService::class.java).apply {
            action = FolderMonitorService.ACTION_START_MONITORING
            putExtra(FolderMonitorService.EXTRA_INPUT_FOLDER, preferencesManager.homeInputFolder)
            putExtra(FolderMonitorService.EXTRA_OUTPUT_FOLDER, preferencesManager.homeOutputFolder)
            // 修复：使用LutManager获取完整路径
            putExtra(
                FolderMonitorService.EXTRA_LUT_FILE_PATH,
                selectedLutItem?.let { lutManager.getLutFilePath(it) } ?: "")
            // 修复：转换为Integer类型
            putExtra(FolderMonitorService.EXTRA_STRENGTH, preferencesManager.homeStrength.toInt())
            putExtra(FolderMonitorService.EXTRA_QUALITY, preferencesManager.homeQuality.toInt())
            putExtra(FolderMonitorService.EXTRA_DITHER, getDitherType().name)
        }
        requireContext().startForegroundService(intent)
        homeViewModel.setMonitoring(true)
    }

    private fun stopMonitoring() {
        val intent = Intent(requireContext(), FolderMonitorService::class.java).apply {
            action = FolderMonitorService.ACTION_STOP_MONITORING
        }
        requireContext().startService(intent)
        homeViewModel.setMonitoring(false)
    }

    private fun updateMonitoringButtonState() {
        // 这个方法在新的实现中不再需要，因为我们使用开关控件
        // 开关的状态会通过observeViewModel()中的逻辑自动更新
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showWatermarkSettingsDialog() {
        val dialog = WatermarkSettingsDialog.newInstance { config ->
            // 配置保存后的回调
            Log.d("HomeFragment", "水印配置已保存: $config")
        }
        dialog.show(parentFragmentManager, "WatermarkSettingsDialog")
    }
}