package cn.alittlecookie.lut2photo.lut2photo.ui.home

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.core.LutProcessor
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentHomeBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.service.FolderMonitorService
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.LutUtils
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import cn.alittlecookie.lut2photo.lut2photo.utils.WatermarkUtils
import cn.alittlecookie.lut2photo.ui.bottomsheet.WatermarkSettingsBottomSheet
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by viewModels()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var lutManager: LutManager
    private var selectedLutItem: LutItem? = null
    private var selectedLut2Item: LutItem? = null  // 第二个LUT
    private var availableLuts: List<LutItem> = emptyList()

    // 防抖机制相关
    private val previewUpdateHandler = Handler(Looper.getMainLooper())
    private var previewUpdateRunnable: Runnable? = null
    private val PREVIEW_UPDATE_DELAY = 300L // 300ms延迟


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
                updatePreviewFromInputFolder()
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
        setupPreviewCard()
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
            updatePreview()
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

                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    lutNames
                )

                // 设置主要LUT下拉框
                binding.dropdownLut.setAdapter(adapter)
                binding.dropdownLut.setOnItemClickListener { _, _, position, _ ->
                    selectedLutItem = if (position == 0) null else availableLuts[position - 1]
                    selectedLutItem?.let {
                        preferencesManager.homeLutUri = it.filePath
                    }
                    updateMonitoringButtonState()

                    // 发送LUT配置变化广播
                    sendLutConfigChangesBroadcast()

                    Log.d(
                        "HomeFragment",
                        "LUT选择更新: ${selectedLutItem?.name ?: "未选择"}"
                    )
                }

                // 设置第二个LUT下拉框
                binding.dropdownLut2.setAdapter(adapter)
                binding.dropdownLut2.setOnItemClickListener { _, _, position, _ ->
                    selectedLut2Item = if (position == 0) null else availableLuts[position - 1]
                    selectedLut2Item?.let {
                        preferencesManager.homeLut2Uri = it.filePath
                    } ?: run {
                        preferencesManager.homeLut2Uri = null
                    }

                    // 发送LUT配置变化广播
                    sendLutConfigChangesBroadcast()

                    Log.d(
                        "HomeFragment",
                        "第二个LUT选择更新: ${selectedLut2Item?.name ?: "未选择"}"
                    )
                }

                // 恢复选中的主要LUT
                val savedLutUri = preferencesManager.homeLutUri
                if (!savedLutUri.isNullOrEmpty()) {
                    val savedLutIndex = availableLuts.indexOfFirst { it.filePath == savedLutUri }
                    if (savedLutIndex >= 0) {
                        binding.dropdownLut.setText(lutNames[savedLutIndex + 1], false)
                        selectedLutItem = availableLuts[savedLutIndex]
                        Log.d("HomeFragment", "恢复LUT选择: ${selectedLutItem?.name}")
                    }
                } else {
                    binding.dropdownLut.setText(lutNames[0], false)
                }

                // 恢复选中的第二个LUT
                val savedLut2Uri = preferencesManager.homeLut2Uri
                if (!savedLut2Uri.isNullOrEmpty()) {
                    val savedLut2Index = availableLuts.indexOfFirst { it.filePath == savedLut2Uri }
                    if (savedLut2Index >= 0) {
                        binding.dropdownLut2.setText(lutNames[savedLut2Index + 1], false)
                        selectedLut2Item = availableLuts[savedLut2Index]
                        Log.d("HomeFragment", "恢复第二个LUT选择: ${selectedLut2Item?.name}")
                    }
                } else {
                    binding.dropdownLut2.setText(lutNames[0], false)
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

        // 加载第二个LUT强度设置
        binding.sliderLut2Strength.value = preferencesManager.homeLut2Strength

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
        preferencesManager.homeLut2Strength = binding.sliderLut2Strength.value
        preferencesManager.homeQuality = binding.sliderQuality.value
        preferencesManager.homeDitherType = getDitherType().name

        // 保存开关状态
        preferencesManager.monitoringSwitchEnabled = binding.switchMonitoring.isChecked

        // 更新显示值
        binding.textStrengthValue.text = "${preferencesManager.homeStrength.toInt()}%"
        binding.textLut2StrengthValue.text = "${preferencesManager.homeLut2Strength.toInt()}%"
        binding.textQualityValue.text = "${preferencesManager.homeQuality.toInt()}"

        // 确保文件夹显示是最新的
        updateInputFolderDisplay()
        updateOutputFolderDisplay()
    }

    @SuppressLint("SetTextI18n")
    private fun setupSliders() {
        binding.sliderStrength.addOnChangeListener { _, value, _ ->
            Log.d("HomeFragment", "滑块1变化: $value -> 保存前: ${preferencesManager.homeStrength}")
            preferencesManager.homeStrength = value
            Log.d("HomeFragment", "滑块1变化: $value -> 保存后: ${preferencesManager.homeStrength}")
            binding.textStrengthValue.text = "${value.toInt()}%"

            // 发送LUT配置变化广播
            sendLutConfigChangesBroadcast()
        }

        // 第二个LUT强度滑块
        binding.sliderLut2Strength.addOnChangeListener { _, value, _ ->
            Log.d(
                "HomeFragment",
                "滑块2变化: $value -> 保存前: ${preferencesManager.homeLut2Strength}"
            )
            preferencesManager.homeLut2Strength = value
            Log.d(
                "HomeFragment",
                "滑块2变化: $value -> 保存后: ${preferencesManager.homeLut2Strength}"
            )
            binding.textLut2StrengthValue.text = "${value.toInt()}%"

            // 发送LUT配置变化广播
            sendLutConfigChangesBroadcast()
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
            // 添加第二个LUT文件路径
            putExtra(
                FolderMonitorService.EXTRA_LUT2_FILE_PATH,
                selectedLut2Item?.let { lutManager.getLutFilePath(it) } ?: "")
            // 修复：转换为Integer类型
            putExtra(FolderMonitorService.EXTRA_STRENGTH, preferencesManager.homeStrength.toInt())
            putExtra(
                FolderMonitorService.EXTRA_LUT2_STRENGTH,
                preferencesManager.homeLut2Strength.toInt()
            )
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

    /**
     * 发送LUT配置变化广播，通知文件夹监控服务更新LUT配置
     */
    private fun sendLutConfigChangesBroadcast() {
        val intent = Intent("cn.alittlecookie.lut2photo.LUT_CONFIG_CHANGED")
        requireContext().sendBroadcast(intent)
        Log.d("HomeFragment", "发送LUT配置变化广播")

        // 使用防抖机制更新预览
        schedulePreviewUpdate()
    }

    /**
     * 使用防抖机制调度预览更新，避免频繁刷新
     */
    private fun schedulePreviewUpdate() {
        Log.d(
            "HomeFragment",
            "调度预览更新 - 当前强度1: ${preferencesManager.homeStrength}, 强度2: ${preferencesManager.homeLut2Strength}"
        )

        // 取消之前的更新任务
        previewUpdateRunnable?.let {
            previewUpdateHandler.removeCallbacks(it)
            Log.d("HomeFragment", "取消之前的预览更新任务")
        }

        // 创建新的更新任务
        previewUpdateRunnable = Runnable {
            Log.d("HomeFragment", "执行预览更新任务")
            updatePreview()
        }

        // 延迟执行更新
        previewUpdateHandler.postDelayed(previewUpdateRunnable!!, PREVIEW_UPDATE_DELAY)
        Log.d("HomeFragment", "预览更新任务已调度，延迟: ${PREVIEW_UPDATE_DELAY}ms")
    }

    private fun setupPreviewCard() {
        // 获取预览卡片的根视图
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_home)
        val refreshButton = previewCardView?.findViewById<View>(R.id.button_refresh_preview)
        val headerLayout = previewCardView?.findViewById<View>(R.id.layout_preview_header)
        val contentLayout = previewCardView?.findViewById<View>(R.id.layout_preview_content)
        val toggleButton = previewCardView?.findViewById<ImageView>(R.id.button_toggle_preview)

        // 设置刷新按钮点击事件
        refreshButton?.setOnClickListener {
            updatePreviewFromInputFolder()
        }

        // 设置折叠/展开功能
        headerLayout?.setOnClickListener {
            contentLayout?.let { content ->
                toggleButton?.let { toggle ->
                    togglePreviewSection(content, toggle)
                }
            }
        }

        // 恢复折叠状态
        val isExpanded = preferencesManager.homePreviewExpanded
        if (!isExpanded) {
            contentLayout?.visibility = View.GONE
            toggleButton?.rotation = 180f
        }

        // 初始化预览
        updatePreviewFromInputFolder()
    }

    private fun updatePreview() {
        // 从输入文件夹获取最新图片并更新预览
        updatePreviewFromInputFolder()

        // 更新效果信息显示
        updatePreviewEffectsInfo()
    }

    private fun togglePreviewSection(layout: View, button: ImageView) {
        val isExpanded = layout.isVisible

        if (isExpanded) {
            layout.visibility = View.GONE
            button.rotation = 180f
            preferencesManager.homePreviewExpanded = false
        } else {
            layout.visibility = View.VISIBLE
            button.rotation = 0f
            preferencesManager.homePreviewExpanded = true
        }
    }

    private fun updatePreviewEffectsInfo() {
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_home)
        val effectsInfoText = previewCardView?.findViewById<TextView>(R.id.text_effects_info)

        val effects = mutableListOf<String>()

        // 添加LUT信息
        selectedLutItem?.let { effects.add("LUT1: ${it.name}") }
        selectedLut2Item?.let { effects.add("LUT2: ${it.name}") }

        // 添加水印信息
        if (binding.switchWatermark.isChecked) {
            effects.add("水印")
        }

        // 添加抖动信息
        val ditherType = getDitherType()
        if (ditherType != LutProcessor.DitherType.NONE) {
            effects.add("抖动: ${ditherType.name}")
        }

        effectsInfoText?.text = if (effects.isNotEmpty()) {
            effects.joinToString(" + ")
        } else {
            "无效果"
        }
    }

    private fun updatePreviewFromInputFolder() {
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_home)
        val imageView = previewCardView?.findViewById<ImageView>(R.id.image_preview)
        val placeholderText = previewCardView?.findViewById<TextView>(R.id.text_placeholder)

        val inputFolderPath = preferencesManager.homeInputFolder
        if (inputFolderPath.isNullOrEmpty()) {
            showPreviewPlaceholder("请选择输入文件夹")
            return
        }

        try {
            val inputFolderUri = inputFolderPath.toUri()
            val inputFolder = DocumentFile.fromTreeUri(requireContext(), inputFolderUri)

            if (inputFolder == null || !inputFolder.exists() || !inputFolder.isDirectory) {
                showPreviewPlaceholder("输入文件夹不存在")
                return
            }

            // 获取文件夹中最新的图片文件
            val imageFiles = inputFolder.listFiles().filter { file ->
                file.isFile && file.name?.let { name ->
                    val extension = name.substringAfterLast('.', "").lowercase()
                    extension in listOf("jpg", "jpeg", "png", "bmp", "webp")
                } ?: false
            }.sortedByDescending { it.lastModified() }

            if (imageFiles.isEmpty()) {
                showPreviewPlaceholder("输入文件夹中没有图片")
                return
            }

            // 显示最新的图片并应用效果
            val latestImage = imageFiles.first()
            imageView?.let { iv ->
                // 隐藏占位图和占位文本
                val placeholderLayout = previewCardView?.findViewById<View>(R.id.layout_placeholder)
                placeholderLayout?.visibility = View.GONE
                placeholderText?.visibility = View.GONE
                iv.visibility = View.VISIBLE

                // 如果没有选择LUT且没有开启水印，直接显示原图
                if (selectedLutItem == null && selectedLut2Item == null && !binding.switchWatermark.isChecked) {
                    Glide.with(this)
                        .load(latestImage.uri)
                        .into(iv)
                    return
                }

                // 使用Glide加载图片并应用LUT和水印效果
                // 在加载前固定强度值，避免处理时值发生变化
                val currentStrength1 = preferencesManager.homeStrength
                val currentStrength2 = preferencesManager.homeLut2Strength
                val currentWatermarkEnabled = binding.switchWatermark.isChecked

                // 使用真正影响图像的参数作为缓存键
                val cacheKey =
                    "${latestImage.uri}_${selectedLutItem?.name}_${selectedLut2Item?.name}_${currentStrength1}_${currentStrength2}_${currentWatermarkEnabled}_${System.currentTimeMillis()}"

                Log.d("HomeFragment", "生成缓存键: $cacheKey")
                Log.d(
                    "HomeFragment",
                    "预览更新 - 强度1: $currentStrength1, 强度2: $currentStrength2, 水印: $currentWatermarkEnabled"
                )

                Glide.with(this)
                    .asBitmap()
                    .load(latestImage.uri)
                    .signature(ObjectKey(cacheKey)) // 使用包含时间戳的缓存键
                    .skipMemoryCache(true) // 跳过内存缓存
                    .diskCacheStrategy(DiskCacheStrategy.NONE) // 跳过磁盘缓存
                    .override(800, 600) // 限制预览图片大小以提高性能
                    .dontTransform() // 禁用所有变换
                    .format(DecodeFormat.PREFER_ARGB_8888) // 强制使用ARGB_8888格式
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(
                            resource: Bitmap,
                            transition: Transition<in Bitmap>?
                        ) {
                            // 在后台线程应用LUT效果和水印效果
                            lifecycleScope.launch(Dispatchers.IO) {
                                var processedBitmap = resource
                                var hasEffects = false

                                try {
                                    // 应用GPU双LUT效果
                                    val lutPath =
                                        selectedLutItem?.let { lutManager.getLutFilePath(it) }
                                    val lut2Path =
                                        selectedLut2Item?.let { lutManager.getLutFilePath(it) }

                                    if (!lutPath.isNullOrEmpty() || !lut2Path.isNullOrEmpty()) {
                                        // 使用固定的强度值，确保一致性
                                        // 将0-100范围的值转换为0-1范围
                                        val strength1 = currentStrength1 / 100f
                                        val strength2 = currentStrength2 / 100f

                                        Log.d("HomeFragment", "开始GPU双LUT处理")
                                        Log.d(
                                            "HomeFragment",
                                            "- LUT1: ${selectedLutItem?.name ?: "未选择"}, 强度: $strength1"
                                        )
                                        Log.d(
                                            "HomeFragment",
                                            "- LUT2: ${selectedLut2Item?.name ?: "未选择"}, 强度: $strength2"
                                        )
                                        Log.d(
                                            "HomeFragment",
                                            "- 原始图片尺寸: ${processedBitmap.width}x${processedBitmap.height}"
                                        )

                                        val lutResult = LutUtils.applyDualLutGpu(
                                            processedBitmap,
                                            lutPath,
                                            strength1,
                                            lut2Path,
                                            strength2,
                                            requireContext()
                                        )

                                        if (lutResult != null && lutResult != processedBitmap) {
                                            processedBitmap = lutResult
                                            hasEffects = true
                                            Log.d(
                                                "HomeFragment",
                                                "GPU双LUT效果应用成功，结果图片尺寸: ${lutResult.width}x${lutResult.height}"
                                            )
                                        } else {
                                            Log.w(
                                                "HomeFragment",
                                                "GPU双LUT效果应用失败或无变化，lutResult=${lutResult?.let { "非null" } ?: "null"}"
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("HomeFragment", "应用GPU双LUT效果失败，回退到CPU处理", e)

                                    // 回退到原来的CPU处理方式
                                    try {
                                        val lutPath =
                                            selectedLutItem?.let { lutManager.getLutFilePath(it) }
                                        if (!lutPath.isNullOrEmpty()) {
                                            val strength1 = preferencesManager.homeStrength / 100f
                                            val lutResult = LutUtils.applyLut(
                                                processedBitmap,
                                                lutPath,
                                                strength1
                                            )
                                            if (lutResult != null) {
                                                processedBitmap = lutResult
                                                hasEffects = true
                                                Log.d("HomeFragment", "LUT1 CPU回退处理成功")
                                            }
                                        }

                                        val lut2Path =
                                            selectedLut2Item?.let { lutManager.getLutFilePath(it) }
                                        if (!lut2Path.isNullOrEmpty()) {
                                            val strength2 =
                                                preferencesManager.homeLut2Strength / 100f
                                            val lut2Result = LutUtils.applyLut(
                                                processedBitmap,
                                                lut2Path,
                                                strength2
                                            )
                                            if (lut2Result != null) {
                                                processedBitmap = lut2Result
                                                hasEffects = true
                                                Log.d("HomeFragment", "LUT2 CPU回退处理成功")
                                            }
                                        }
                                        Log.d("HomeFragment", "CPU回退处理完成")
                                    } catch (fallbackException: Exception) {
                                        Log.e(
                                            "HomeFragment",
                                            "CPU回退处理也失败",
                                            fallbackException
                                        )
                                    }
                                }

                                try {
                                    // 应用水印效果
                                    if (currentWatermarkEnabled) {
                                        val watermarkConfig =
                                            preferencesManager.getWatermarkConfig()
                                        val watermarkResult = WatermarkUtils.addWatermark(
                                            processedBitmap,
                                            watermarkConfig,
                                            requireContext(),
                                            latestImage.uri,
                                            selectedLutItem?.name,
                                            selectedLut2Item?.name,
                                            currentStrength1,
                                            currentStrength2
                                        )
                                        if (watermarkResult != null) {
                                            processedBitmap = watermarkResult
                                            hasEffects = true
                                            Log.d("HomeFragment", "水印效果应用成功")
                                        } else {
                                            Log.w("HomeFragment", "水印效果应用失败")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("HomeFragment", "应用水印效果失败", e)
                                }

                                // 在主线程更新UI
                                withContext(Dispatchers.Main) {
                                    Log.d(
                                        "HomeFragment",
                                        "准备更新UI - isAdded: $isAdded, isDetached: $isDetached"
                                    )
                                    if (isAdded && !isDetached) { // 确保Fragment仍然附加
                                        Log.d("HomeFragment", "开始设置处理后的bitmap到ImageView")
                                        Log.d(
                                            "HomeFragment",
                                            "ImageView状态 - 可见性: ${iv.visibility}, 宽度: ${iv.width}, 高度: ${iv.height}"
                                        )

                                        // 获取当前ImageView中的bitmap进行对比
                                        val currentDrawable = iv.drawable
                                        Log.d(
                                            "HomeFragment",
                                            "当前ImageView drawable: ${currentDrawable?.javaClass?.simpleName ?: "null"}"
                                        )

                                        // 设置新的bitmap
                                        iv.setImageBitmap(processedBitmap)
                                        Log.d(
                                            "HomeFragment",
                                            "bitmap已设置到ImageView，尺寸: ${processedBitmap.width}x${processedBitmap.height}"
                                        )

                                        // 验证bitmap是否真的被设置
                                        val newDrawable = iv.drawable
                                        Log.d(
                                            "HomeFragment",
                                            "设置后ImageView drawable: ${newDrawable?.javaClass?.simpleName ?: "null"}"
                                        )
                                        Log.d(
                                            "HomeFragment",
                                            "drawable是否发生变化: ${currentDrawable != newDrawable}"
                                        )

                                        // 强制刷新ImageView
                                        iv.invalidate()
                                        iv.requestLayout()
                                        Log.d(
                                            "HomeFragment",
                                            "已调用invalidate()和requestLayout()强制刷新ImageView"
                                        )

                                        // 检查父容器状态
                                        val parentView = iv.parent as? View
                                        Log.d(
                                            "HomeFragment",
                                            "父容器状态 - 可见性: ${parentView?.visibility}, 类型: ${parentView?.javaClass?.simpleName}"
                                        )

                                        if (hasEffects) {
                                            Log.d("HomeFragment", "预览效果应用完成")
                                        } else {
                                            Log.d(
                                                "HomeFragment",
                                                "预览显示原图（无效果或效果应用失败）"
                                            )
                                        }
                                    } else {
                                        Log.w(
                                            "HomeFragment",
                                            "Fragment状态异常，跳过UI更新 - isAdded: $isAdded, isDetached: $isDetached"
                                        )
                                    }
                                }
                            }
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            // 清理资源
                        }
                    })
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "处理输入文件夹失败", e)
            showPreviewPlaceholder("无法访问输入文件夹")
        }
    }

    private fun showPreviewPlaceholder(message: String) {
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_home)
        val imageView = previewCardView?.findViewById<ImageView>(R.id.image_preview)
        val placeholderText = previewCardView?.findViewById<TextView>(R.id.text_placeholder)
        val placeholderLayout = previewCardView?.findViewById<View>(R.id.layout_placeholder)

        imageView?.visibility = View.GONE
        placeholderLayout?.visibility = View.VISIBLE
        placeholderText?.let {
            it.visibility = View.VISIBLE
            it.text = message
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 清理防抖任务
        previewUpdateRunnable?.let { previewUpdateHandler.removeCallbacks(it) }
        _binding = null
    }

    private fun showWatermarkSettingsDialog() {
        val bottomSheet = WatermarkSettingsBottomSheet.newInstance(
            onConfigSaved = { config ->
                // 配置保存后的回调
                updatePreview()
                Log.d("HomeFragment", "水印配置已保存: $config")
            },
            lut1Name = selectedLutItem?.name,
            lut2Name = selectedLut2Item?.name,
            lut1Strength = preferencesManager.homeStrength,
            lut2Strength = preferencesManager.homeLut2Strength
        )
        bottomSheet.show(parentFragmentManager, "WatermarkSettingsBottomSheet")
    }
}