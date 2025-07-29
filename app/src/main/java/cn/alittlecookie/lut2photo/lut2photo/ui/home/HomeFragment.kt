package cn.alittlecookie.lut2photo.lut2photo.ui.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.adapter.ProcessingHistoryAdapter
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentHomeBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.service.FolderMonitorService
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var lutManager: LutManager
    private lateinit var lutSpinnerAdapter: ArrayAdapter<LutItem>
    private var selectedLutItem: LutItem? = null
    private lateinit var historyAdapter: ProcessingHistoryAdapter
    
    private val lutFileLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadLutFile(uri)
            }
        }
    }
    
    private val inputFolderLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val displayName = getDisplayNameFromUri(uri) ?: "输入文件夹已选择"
                binding.textInputFolder.text = displayName
                preferencesManager.homeInputFolder = uri.toString()
            }
        }
    }
    
    private val outputFolderLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val displayName = getDisplayNameFromUri(uri) ?: "输出文件夹已选择"
                binding.textOutputFolder.text = displayName
                preferencesManager.homeOutputFolder = uri.toString()
            }
        }
    }

    // 添加状态保存的键
    companion object {
        private const val KEY_FILE_SETTINGS_EXPANDED = "file_settings_expanded"
        private const val KEY_PARAMS_EXPANDED = "params_expanded"
        private const val KEY_SCROLL_POSITION = "scroll_position"
    }
    
    // 添加状态变量
    private var isFileSettingsExpanded = true
    private var isParamsExpanded = true
    private var scrollPosition = 0
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        
        preferencesManager = PreferencesManager(requireContext())
        lutManager = LutManager(requireContext())
        
        // 优先从PreferencesManager恢复状态，然后从savedInstanceState
        isFileSettingsExpanded = savedInstanceState?.getBoolean(KEY_FILE_SETTINGS_EXPANDED) 
            ?: preferencesManager.homeFileSettingsExpanded
        isParamsExpanded = savedInstanceState?.getBoolean(KEY_PARAMS_EXPANDED) 
            ?: preferencesManager.homeParamsExpanded
        scrollPosition = savedInstanceState?.getInt(KEY_SCROLL_POSITION, 0) ?: 0
        
        setupViews()
        setupRecyclerView()
        loadSavedSettings()
        observeViewModel()
        
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 恢复UI状态
        restoreUIState()
        
        // 延迟恢复滚动位置，确保视图完全布局完成
        binding.root.post {
            if (scrollPosition > 0) {
                (binding.root as ScrollView).scrollTo(0, scrollPosition)
            }
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        // 检查 binding 是否可用
        _binding?.let { binding ->
            // 保存展开状态
            outState.putBoolean(KEY_FILE_SETTINGS_EXPANDED, isFileSettingsExpanded)
            outState.putBoolean(KEY_PARAMS_EXPANDED, isParamsExpanded)
            
            // 保存滚动位置
            outState.putInt(KEY_SCROLL_POSITION, (binding.root as ScrollView).scrollY)
            
            // 保存当前设置到PreferencesManager
            saveCurrentSettings()
        }
    }
    
    private fun saveCurrentSettings() {
        // 检查 binding 是否可用
        _binding?.let { binding ->
            // 保存当前的所有设置
            preferencesManager.homeStrength = binding.sliderStrength.value
            preferencesManager.homeQuality = binding.sliderQuality.value
            preferencesManager.homeDitherType = getDitherType()
            preferencesManager.homeLutUri = selectedLutItem?.filePath
            
            // 保存折叠状态到PreferencesManager
            preferencesManager.homeFileSettingsExpanded = isFileSettingsExpanded
            preferencesManager.homeParamsExpanded = isParamsExpanded
            
            // 保存完整的文件夹路径
            val inputFolderText = binding.textInputFolder.text.toString()
            val outputFolderText = binding.textOutputFolder.text.toString()
            
            if (inputFolderText != "未设置文件夹" && inputFolderText.isNotEmpty()) {
                preferencesManager.homeInputFolder = preferencesManager.homeInputFolder ?: ""
            }
            if (outputFolderText != "未设置文件夹" && outputFolderText.isNotEmpty()) {
                preferencesManager.homeOutputFolder = preferencesManager.homeOutputFolder ?: ""
            }
        }
    }
    
    private fun loadSavedSettings() {
        binding.sliderStrength.value = preferencesManager.homeStrength
        binding.sliderQuality.value = preferencesManager.homeQuality
        
        when (preferencesManager.homeDitherType) {
            "floyd" -> binding.radioFloyd.isChecked = true
            "random" -> binding.radioRandom.isChecked = true
            else -> binding.radioNone.isChecked = true
        }
        
        // 从PreferencesManager恢复折叠状态
        isFileSettingsExpanded = preferencesManager.homeFileSettingsExpanded
        isParamsExpanded = preferencesManager.homeParamsExpanded
        
        // 修复LUT选择恢复逻辑 - 使用自定义适配器
        lifecycleScope.launch {
            try {
                val lutItems = lutManager.getAllLuts()
                
                // 使用自定义适配器以只显示文件名
                lutSpinnerAdapter = object : ArrayAdapter<LutItem>(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    lutItems
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getView(position, convertView, parent)
                        (view as TextView).text = getItem(position)?.name ?: ""
                        return view
                    }
                    
                    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getDropDownView(position, convertView, parent)
                        (view as TextView).text = getItem(position)?.name ?: ""
                        return view
                    }
                }
                
                lutSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerLut.adapter = lutSpinnerAdapter
                
                // 恢复保存的LUT选择
                preferencesManager.homeLutUri?.let { savedPath ->
                    val savedLutIndex = lutItems.indexOfFirst { it.filePath == savedPath }
                    if (savedLutIndex >= 0) {
                        binding.spinnerLut.setSelection(savedLutIndex)
                        selectedLutItem = lutItems[savedLutIndex]
                        android.util.Log.d("HomeFragment", "恢复LUT选择: ${selectedLutItem?.name}")
                    } else {
                        android.util.Log.w("HomeFragment", "保存的LUT路径未找到: $savedPath")
                        // 清除无效的保存路径
                        preferencesManager.homeLutUri = null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "加载LUT设置失败: ${e.message}", e)
            }
        }
        
        // 改进文件夹路径显示逻辑
        preferencesManager.homeInputFolder?.let { folder ->
            if (folder.isNotEmpty()) {
                try {
                    val uri = Uri.parse(folder)
                    val displayName = getDisplayNameFromUri(uri) ?: "输入文件夹已选择"
                    binding.textInputFolder.text = displayName
                } catch (e: Exception) {
                    binding.textInputFolder.text = "输入文件夹已选择"
                }
            }
        }
        
        preferencesManager.homeOutputFolder?.let { folder ->
            if (folder.isNotEmpty()) {
                try {
                    val uri = Uri.parse(folder)
                    val displayName = getDisplayNameFromUri(uri) ?: "输出文件夹已选择"
                    binding.textOutputFolder.text = displayName
                } catch (e: Exception) {
                    binding.textOutputFolder.text = "输出文件夹已选择"
                }
            }
        }
    }
    
    private fun getDisplayNameFromUri(uri: Uri): String? {
        return try {
            // 获取更友好的显示名称，使用 / 分割
            val segments = uri.pathSegments
            if (segments.isNotEmpty()) {
                // 获取最后几个路径段作为显示名称，统一使用 / 分割
                val lastSegments = segments.takeLast(2)
                if (lastSegments.size >= 2) {
                    ".../${lastSegments.joinToString("/")}"
                } else {
                    lastSegments.joinToString("/")
                }
            } else {
                uri.lastPathSegment?.replace("\\", "/")
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun restoreUIState() {
        // 恢复卡片展开状态
        binding.layoutFileSettingsContent.visibility = if (isFileSettingsExpanded) View.VISIBLE else View.GONE
        binding.layoutParamsContent.visibility = if (isParamsExpanded) View.VISIBLE else View.GONE
        
        // 更新按钮图标
        updateToggleButtonIcon(binding.buttonToggleFileSettings, isFileSettingsExpanded)
        updateToggleButtonIcon(binding.buttonToggleParams, isParamsExpanded)
    }
    
    private fun updateToggleButtonIcon(button: View, isExpanded: Boolean) {
        // 根据展开状态更新按钮图标或文本
        // 这里可以根据你的UI设计来实现
    }
    
    private fun toggleSection(content: View, button: View) {
        val isExpanding = content.visibility == View.GONE
        
        if (isExpanding) {
            content.visibility = View.VISIBLE
        } else {
            content.visibility = View.GONE
        }
        
        // 更新状态变量
        when (content.id) {
            R.id.layout_file_settings_content -> isFileSettingsExpanded = isExpanding
            R.id.layout_params_content -> isParamsExpanded = isExpanding
        }
        
        updateToggleButtonIcon(button, isExpanding)
    }
    
    override fun onPause() {
        super.onPause()
        // 页面失去焦点时保存设置，但只在 binding 可用时
        if (_binding != null) {
            saveCurrentSettings()
        }
    }
    
    override fun onStop() {
        super.onStop()
        // 页面停止时也保存设置，但只在 binding 可用时
        if (_binding != null) {
            saveCurrentSettings()
        }
    }

    private fun setupViews() {
        binding.apply {
            // Setup LUT spinner
            lifecycleScope.launch {
                setupLutSpinner()
            }
            
            // Setup input folder selection
            buttonSelectInputFolder.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                inputFolderLauncher.launch(intent)
            }
            
            // Setup output folder selection
            buttonSelectOutputFolder.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                outputFolderLauncher.launch(intent)
            }
            
            // Setup monitoring controls
            buttonStartMonitoring.setOnClickListener {
                startMonitoring()
            }
            
            buttonStopMonitoring.setOnClickListener {
                stopMonitoring()
            }
            
            // Setup collapsible sections
            buttonToggleFileSettings.setOnClickListener {
                toggleSection(layoutFileSettingsContent, buttonToggleFileSettings)
            }
            
            buttonToggleParams.setOnClickListener {
                toggleSection(layoutParamsContent, buttonToggleParams)
            }
        }
    }
    
    private suspend fun setupLutSpinner() {
        val lutItems = lutManager.getAllLuts()
        
        // 创建自定义适配器以只显示文件名
        lutSpinnerAdapter = object : ArrayAdapter<LutItem>(
            requireContext(),
            android.R.layout.simple_spinner_item,
            lutItems
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).text = getItem(position)?.name ?: ""
                return view
            }
            
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as TextView).text = getItem(position)?.name ?: ""
                return view
            }
        }
        
        lutSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        binding.spinnerLut.adapter = lutSpinnerAdapter
        binding.spinnerLut.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLutItem = lutItems[position]
                preferencesManager.homeLutUri = selectedLutItem?.filePath
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedLutItem = null
            }
        }
    }
    
    private fun setupRecyclerView() {
        historyAdapter = ProcessingHistoryAdapter()
        // Note: RecyclerView for history is not in current layout
        // This method can be removed or the RecyclerView can be added to layout if needed
    }

    private fun startMonitoring() {
        val inputFolder = preferencesManager.homeInputFolder
        val outputFolder = preferencesManager.homeOutputFolder
        
        if (inputFolder.isNullOrEmpty() || outputFolder.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "请选择输入和输出文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 检查URI权限
        val inputUri = Uri.parse(inputFolder)
        val outputUri = Uri.parse(outputFolder)
        
        if (!hasUriPermission(inputUri) || !hasUriPermission(outputUri)) {
            Toast.makeText(requireContext(), "文件夹访问权限已失效，请重新选择文件夹", Toast.LENGTH_LONG).show()
            // 清空已保存的文件夹路径
            preferencesManager.homeInputFolder = null
            preferencesManager.homeOutputFolder = null
            binding.textInputFolder.text = "未设置文件夹"
            binding.textOutputFolder.text = "未设置文件夹"
            return
        }
        
        if (selectedLutItem == null) {
            Toast.makeText(requireContext(), "请选择LUT文件", Toast.LENGTH_SHORT).show()
            return
        }
        
        val strength = binding.sliderStrength.value.toInt()
        val quality = binding.sliderQuality.value.toInt()
        val ditherType = getDitherType()
        
        // 保存设置
        preferencesManager.homeStrength = binding.sliderStrength.value
        preferencesManager.homeQuality = binding.sliderQuality.value
        preferencesManager.homeDitherType = ditherType
        preferencesManager.homeLutUri = selectedLutItem!!.filePath
        
        // 获取LUT文件的完整路径
        val lutFilePath = lutManager.getLutFilePath(selectedLutItem!!)
        
        // 启动监控服务
        val intent = Intent(requireContext(), FolderMonitorService::class.java).apply {
            action = FolderMonitorService.ACTION_START_MONITORING
            putExtra(FolderMonitorService.EXTRA_INPUT_FOLDER, inputFolder)
            putExtra(FolderMonitorService.EXTRA_OUTPUT_FOLDER, outputFolder)
            putExtra(FolderMonitorService.EXTRA_LUT_FILE_PATH, lutFilePath)
            putExtra(FolderMonitorService.EXTRA_STRENGTH, strength)
            putExtra(FolderMonitorService.EXTRA_QUALITY, quality)
            putExtra(FolderMonitorService.EXTRA_DITHER, ditherType)
        }
        
        requireContext().startForegroundService(intent)
        homeViewModel.setMonitoring(true)
        
        Toast.makeText(requireContext(), "开始监控文件夹", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoring() {
        // 停止监控服务
        val intent = Intent(requireContext(), FolderMonitorService::class.java).apply {
            action = FolderMonitorService.ACTION_STOP_MONITORING
        }
        requireContext().startService(intent)
        
        homeViewModel.stopMonitoring()
        Toast.makeText(requireContext(), "已停止监控文件夹", Toast.LENGTH_SHORT).show()
    }

    private fun observeViewModel() {
        homeViewModel.processingHistory.observe(viewLifecycleOwner) { history ->
            historyAdapter.submitList(history)
        }
        
        homeViewModel.isMonitoring.observe(viewLifecycleOwner) { isMonitoring ->
            binding.buttonStartMonitoring.isEnabled = !isMonitoring
            binding.buttonStopMonitoring.isEnabled = isMonitoring
        }
        
        homeViewModel.statusText.observe(viewLifecycleOwner) { status ->
            binding.textMonitoringStatus.text = "监控状态: $status"
        }
        
        // **新增：观察处理计数**
        homeViewModel.processedCount.observe(viewLifecycleOwner) { count ->
            // 可以在UI中显示处理计数，或者更新其他相关UI元素
            Log.d("HomeFragment", "当前已处理图片数量: $count")
        }
    }

    override fun onResume() {
        super.onResume()
        // 恢复监控状态
        homeViewModel.restoreMonitoringState()
    }

    private fun loadLutFile(uri: Uri) {
        lifecycleScope.launch {
            val success = lutManager.importLut(uri)
            if (success) {
                Toast.makeText(requireContext(), "LUT文件导入成功", Toast.LENGTH_SHORT).show()
                setupLutSpinner() // 重新加载LUT列表
            } else {
                Toast.makeText(requireContext(), "LUT文件导入失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getDitherType(): String {
        return when (binding.radioGroupDither.checkedRadioButtonId) {
            R.id.radio_floyd -> "floyd"
            R.id.radio_random -> "random"
            else -> "none"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    private fun hasUriPermission(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (e: Exception) {
            Log.w("HomeFragment", "检查URI权限失败: ${e.message}")
            false
        }
    }
}
