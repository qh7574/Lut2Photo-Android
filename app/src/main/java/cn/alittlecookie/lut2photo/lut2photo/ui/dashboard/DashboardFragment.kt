package cn.alittlecookie.lut2photo.lut2photo.ui.dashboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.adapter.ImageAdapter
import cn.alittlecookie.lut2photo.lut2photo.adapter.ProcessingHistoryAdapter
import cn.alittlecookie.lut2photo.lut2photo.core.LutProcessor
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentDashboardBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var dashboardViewModel: DashboardViewModel
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var lutManager: LutManager
    private lateinit var imageAdapter: ImageAdapter
    private lateinit var historyAdapter: ProcessingHistoryAdapter
    
    private lateinit var lutSpinnerAdapter: ArrayAdapter<LutItem>
    private var selectedLutItem: LutItem? = null
    
    // 添加状态保存的键
    companion object {
        private const val KEY_FILE_SETTINGS_EXPANDED = "file_settings_expanded"
        private const val KEY_PARAMS_EXPANDED = "params_expanded"
        private const val KEY_SCROLL_POSITION = "scroll_position"
        private const val KEY_SELECTED_IMAGES = "selected_images"
    }
    
    // 添加状态变量
    private var isFileSettingsExpanded = true
    private var isParamsExpanded = true
    private var scrollPosition = 0
    
    private val lutFileLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadLutFile(uri)
            }
        }
    }
    
    private val outputFolderLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                binding.textOutputFolder.text = uri.toString()
                preferencesManager.dashboardOutputFolder = uri.toString()
            }
        }
    }
    
    // 添加图片选择器
    private val imageSelectionLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                if (data.clipData != null) {
                    // 多选
                    val clipData = data.clipData!!
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        addImageToList(uri)
                    }
                } else if (data.data != null) {
                    // 单选
                    addImageToList(data.data!!)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        
        dashboardViewModel = ViewModelProvider(this)[DashboardViewModel::class.java]
        preferencesManager = PreferencesManager(requireContext())
        lutManager = LutManager(requireContext())
        
        // 优先从PreferencesManager恢复状态，然后从savedInstanceState
        isFileSettingsExpanded = savedInstanceState?.getBoolean(KEY_FILE_SETTINGS_EXPANDED) 
            ?: preferencesManager.dashboardFileSettingsExpanded
        isParamsExpanded = savedInstanceState?.getBoolean(KEY_PARAMS_EXPANDED) 
            ?: preferencesManager.dashboardParamsExpanded
        scrollPosition = savedInstanceState?.getInt(KEY_SCROLL_POSITION, 0) ?: 0
        
        // 修复图片重复问题：只在savedInstanceState存在且ViewModel为空时才恢复
        savedInstanceState?.let {
            val savedImages = it.getStringArrayList(KEY_SELECTED_IMAGES)
            if (!savedImages.isNullOrEmpty() && (dashboardViewModel.selectedImages.value?.isEmpty() != false)) {
                // 先清空现有状态，再添加保存的图片
                dashboardViewModel.clearImages()
                savedImages.forEach { uriString ->
                    dashboardViewModel.addImage(Uri.parse(uriString))
                }
            }
        }
        
        // 执行LUT文件迁移
        lifecycleScope.launch {
            lutManager.migrateLegacyLuts()
            setupViews()
            setupRecyclerView()
            observeViewModel()
            loadSavedSettings()
        }
        
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
            
            // 保存选中的图片列表
            val selectedImages = dashboardViewModel.selectedImages.value ?: emptyList()
            val imageUris = ArrayList(selectedImages.map { it.uri.toString() })
            outState.putStringArrayList(KEY_SELECTED_IMAGES, imageUris)
            
            // 保存当前设置到PreferencesManager
            saveCurrentSettings()
        }
    }
    
    private fun saveCurrentSettings() {
        // 检查 binding 是否可用
        _binding?.let { binding ->
            // 保存当前的所有设置
            preferencesManager.dashboardStrength = binding.sliderStrength.value
            preferencesManager.dashboardQuality = binding.sliderQuality.value
            preferencesManager.dashboardDitherType = getDitherType()
            preferencesManager.dashboardLutUri = selectedLutItem?.filePath
            
            // 保存折叠状态到PreferencesManager
            preferencesManager.dashboardFileSettingsExpanded = isFileSettingsExpanded
            preferencesManager.dashboardParamsExpanded = isParamsExpanded
        }
    }

    override fun onPause() {
        super.onPause()
        // 页面失去焦点时保存设置
        if (_binding != null) {
            saveCurrentSettings()
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
        // 例如：如果按钮有图标，可以旋转箭头图标
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

    private fun setupViews() {
        binding.apply {
            // 设置LUT选择
            lifecycleScope.launch {
                setupLutSpinner()
            }
            
            // Setup output folder selection
            buttonSelectOutputFolder.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                outputFolderLauncher.launch(intent)
            }
            
            // Setup image selection
            buttonSelectImages.setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                imageSelectionLauncher.launch(intent)
            }
            
            buttonClearImages.setOnClickListener {
                dashboardViewModel.clearImages()
            }
            
            buttonStartProcessing.setOnClickListener {
                startProcessing()
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
                preferencesManager.dashboardLutUri = selectedLutItem?.filePath
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedLutItem = null
            }
        }
    }

    private fun setupRecyclerView() {
        // Setup images RecyclerView
        imageAdapter = ImageAdapter { imageItem ->
            // 修复：正确处理删除图片
            dashboardViewModel.removeImage(imageItem)
        }
        
        binding.recyclerViewImages.apply {
            // 使用 GridLayoutManager 来支持多列显示，并设置为自动调整高度
            layoutManager = WrapContentGridLayoutManager(requireContext(), 2) // 2列网格
            adapter = imageAdapter
            
            // 禁用嵌套滚动，让父 ScrollView 处理滚动
            isNestedScrollingEnabled = false
        }
        
        // Setup history RecyclerView if needed
        historyAdapter = ProcessingHistoryAdapter()
    }

    private fun startProcessing() {
        val selectedImages = dashboardViewModel.selectedImages.value ?: emptyList()
        if (selectedImages.isEmpty()) {
            Toast.makeText(requireContext(), "请选择要处理的图片", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (selectedLutItem == null) {
            Toast.makeText(requireContext(), "请选择LUT文件", Toast.LENGTH_SHORT).show()
            return
        }
        
        val strength = binding.sliderStrength.value.toInt()
        val quality = binding.sliderQuality.value.toInt()
        val ditherType = getDitherType()
        
        // Save settings
        preferencesManager.dashboardStrength = binding.sliderStrength.value
        preferencesManager.dashboardQuality = binding.sliderQuality.value
        preferencesManager.dashboardDitherType = ditherType
        
        // Create processing params
        val params = LutProcessor.ProcessingParams(
            strength = strength,
            quality = quality,
            ditherType = when (ditherType) {
                "floyd" -> LutProcessor.DitherType.FLOYD_STEINBERG
                "random" -> LutProcessor.DitherType.RANDOM
                else -> LutProcessor.DitherType.NONE
            }
        )
        
        // Start processing with output folder
        dashboardViewModel.startProcessing(
            selectedImages,
            selectedLutItem!!,
            params,
            preferencesManager.dashboardOutputFolder // 传递输出文件夹
        )
    }

    private fun observeViewModel() {
        dashboardViewModel.selectedImages.observe(viewLifecycleOwner) { images ->
            imageAdapter.submitList(images)
            binding.textImageCount.text = "已选择 ${images.size} 张图片"
        }
        
        dashboardViewModel.processingStatus.observe(viewLifecycleOwner) { status ->
            // 可以使用Toast显示状态或更新其他UI元素
        }
        
        dashboardViewModel.isProcessing.observe(viewLifecycleOwner) { isProcessing ->
            binding.progressBar.visibility = if (isProcessing) View.VISIBLE else View.GONE
            binding.buttonStartProcessing.isEnabled = !isProcessing
            binding.buttonStartProcessing.text = if (isProcessing) "正在处理" else "开始处理"
        }
        
        // 添加处理完成监听
        dashboardViewModel.processingCompleted.observe(viewLifecycleOwner) { result ->
            result?.let {
                // 显示Toast通知
                val toastMessage = if (it.isSuccess) {
                    "处理完成！成功处理 ${it.processedCount} 张图片"
                } else {
                    "处理失败：${it.message}"
                }
                Toast.makeText(requireContext(), toastMessage, Toast.LENGTH_LONG).show()
                
                // 如果处理成功且是单文件处理，自动清空选择的图片
                if (it.isSuccess && it.totalCount == 1) {
                    dashboardViewModel.clearImages()
                    Toast.makeText(requireContext(), "已自动清空选择的图片", Toast.LENGTH_SHORT).show()
                }
                
                // 重置状态以避免重复触发
                dashboardViewModel.resetProcessingCompleted()
            }
        }
    }
    
    // 在loadSavedSettings方法中修复LUT选择恢复逻辑
    private fun loadSavedSettings() {
        binding.sliderStrength.value = preferencesManager.dashboardStrength
        binding.sliderQuality.value = preferencesManager.dashboardQuality
        
        when (preferencesManager.dashboardDitherType) {
            "floyd" -> binding.radioFloyd.isChecked = true
            "random" -> binding.radioRandom.isChecked = true
            else -> binding.radioNone.isChecked = true
        }
        
        // 从PreferencesManager恢复折叠状态
        isFileSettingsExpanded = preferencesManager.dashboardFileSettingsExpanded
        isParamsExpanded = preferencesManager.dashboardParamsExpanded
        
        // 修复LUT选择恢复逻辑
        lifecycleScope.launch {
            try {
                val lutItems = lutManager.getAllLuts()
                
                // 使用自定义适配器以只显示文件名（与setupLutSpinner保持一致）
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
                preferencesManager.dashboardLutUri?.let { savedPath ->
                    val savedLutIndex = lutItems.indexOfFirst { it.filePath == savedPath }
                    if (savedLutIndex >= 0) {
                        binding.spinnerLut.setSelection(savedLutIndex)
                        selectedLutItem = lutItems[savedLutIndex]
                        android.util.Log.d("DashboardFragment", "恢复LUT选择: ${selectedLutItem?.name}")
                    } else {
                        android.util.Log.w("DashboardFragment", "保存的LUT路径未找到: $savedPath")
                        // 清除无效的保存路径
                        preferencesManager.dashboardLutUri = null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DashboardFragment", "加载LUT设置失败: ${e.message}", e)
            }
        }
        
        preferencesManager.dashboardOutputFolder?.let { folder ->
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
    
    // 添加路径显示方法
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
    
    // 修改输出文件夹选择的回调
    private val selectOutputFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val displayName = getDisplayNameFromUri(uri) ?: "输出文件夹已选择"
                binding.textOutputFolder.text = displayName
                preferencesManager.dashboardOutputFolder = uri.toString()
            }
        }
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

    private fun addImageToList(uri: Uri) {
        dashboardViewModel.addImage(uri)
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
}

// 自定义 GridLayoutManager 支持 wrap_content 高度
class WrapContentGridLayoutManager(
    context: android.content.Context,
    spanCount: Int
) : GridLayoutManager(context, spanCount) {
    
    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            // 处理可能的索引越界异常
        }
    }
    
    override fun supportsPredictiveItemAnimations(): Boolean {
        return false
    }
}