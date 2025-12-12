package cn.alittlecookie.lut2photo.lut2photo.ui.dashboard

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import cn.alittlecookie.lut2photo.lut2photo.core.ThreadManager
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentDashboardBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.LutUtils
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import cn.alittlecookie.lut2photo.lut2photo.utils.WatermarkUtils
import cn.alittlecookie.lut2photo.lut2photo.utils.WrapContentGridLayoutManager
import cn.alittlecookie.lut2photo.ui.bottomsheet.WatermarkSettingsBottomSheet
import cn.alittlecookie.lut2photo.lut2photo.ui.bottomsheet.FilmGrainSettingsBottomSheet
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val dashboardViewModel: DashboardViewModel by viewModels()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var imageAdapter: ImageAdapter
    private lateinit var lutManager: LutManager
    private lateinit var threadManager: ThreadManager

    private var selectedLutItem: LutItem? = null
    private var selectedLut2Item: LutItem? = null  // 第二个LUT
    private var availableLuts: List<LutItem> = emptyList()

    // 防抖机制相关
    private val previewUpdateHandler = Handler(Looper.getMainLooper())
    private var previewUpdateRunnable: Runnable? = null
    private val PREVIEW_UPDATE_DELAY = 300L // 300ms延迟

    // Activity Result Launchers
    // 使用系统相册选择器（ACTION_PICK）
    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            
            // 处理多选结果
            data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uri ->
                        uris.add(uri)
                    }
                }
            } ?: data?.data?.let { uri ->
                // 处理单选结果
                uris.add(uri)
            }
            
            if (uris.isNotEmpty()) {
                // ACTION_PICK返回的URI通常不需要持久化权限
                // 但仍然尝试获取，失败也不影响使用
                uris.forEach { uri ->
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // ACTION_PICK的URI可能不支持持久化权限，这是正常的
                        Log.d("DashboardFragment", "URI不支持持久化权限（这是正常的）: $uri")
                    }
                }
                dashboardViewModel.addImages(uris)
            }
        }
    }
    
    // SAF备选方案（当系统相册不可用时使用）
    private val selectImagesSafLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.let { uriList ->
            if (uriList.isNotEmpty()) {
                // SAF需要持久化权限
                uriList.forEach { uri ->
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        Log.w("DashboardFragment", "无法获取持久化URI权限: $uri", e)
                    }
                }
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
        threadManager = ThreadManager(requireContext())

        setupViews()
        setupRecyclerView()
        setupLutSpinner()
        setupPreviewCard()
        observeViewModel()
        loadSavedSettings()
        restoreUIState()
    }

    private fun setupViews() {
        // 图片选择按钮 - 优先使用系统相册，不可用时使用SAF
        binding.buttonSelectImages.setOnClickListener {
            launchImagePicker()
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
            updatePreview()
        }

        // 在loadSavedSettings方法中
        binding.switchWatermark.isChecked =
            preferencesManager.dashboardWatermarkEnabled  // 加载分离的开关状态
        binding.buttonWatermarkSettings.isEnabled = preferencesManager.dashboardWatermarkEnabled
        // 水印设置按钮
        binding.buttonWatermarkSettings.setOnClickListener {
            val bottomSheet = WatermarkSettingsBottomSheet.newInstance(
                onConfigSaved = { config ->
                    // 设置保存后的回调
                    updatePreview()
                },
                forFolderMonitor = false,  // 标识为手动处理页面
                lut1Name = selectedLutItem?.name,
                lut2Name = selectedLut2Item?.name,
                lut1Strength = preferencesManager.dashboardStrength,
                lut2Strength = preferencesManager.dashboardLut2Strength
            )
            bottomSheet.show(parentFragmentManager, "WatermarkSettingsBottomSheet")
        }

        // 胶片颗粒开关监听器
        binding.switchGrain.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.dashboardGrainEnabled = isChecked
            binding.buttonGrainSettings.isEnabled = isChecked
            updatePreview()
        }

        // 加载颗粒开关状态
        binding.switchGrain.isChecked = preferencesManager.dashboardGrainEnabled
        binding.buttonGrainSettings.isEnabled = preferencesManager.dashboardGrainEnabled

        // 颗粒设置按钮
        binding.buttonGrainSettings.setOnClickListener {
            val bottomSheet = FilmGrainSettingsBottomSheet.newInstance(
                onConfigChanged = { config ->
                    // 设置保存后的回调
                    updatePreview()
                }
            )
            bottomSheet.show(parentFragmentManager, "FilmGrainSettingsBottomSheet")
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
                updatePreview()
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
                        preferencesManager.dashboardLutUri = it.filePath
                    }
                    updateProcessButtonState()
                    updatePreview()
                }

                // 设置第二个LUT下拉框
                binding.dropdownLut2.setAdapter(adapter)
                binding.dropdownLut2.setOnItemClickListener { _, _, position, _ ->
                    selectedLut2Item = if (position == 0) null else availableLuts[position - 1]
                    selectedLut2Item?.let {
                        preferencesManager.dashboardLut2Uri = it.filePath
                    } ?: run {
                        preferencesManager.dashboardLut2Uri = null
                    }
                    updatePreview()
                    Log.d(
                        "DashboardFragment",
                        "第二个LUT选择更新: ${selectedLut2Item?.name ?: "未选择"}"
                    )
                }

                // 恢复选中的主要LUT
                val savedLutUri = preferencesManager.dashboardLutUri
                if (!savedLutUri.isNullOrEmpty()) {
                    val savedLutIndex = availableLuts.indexOfFirst { it.filePath == savedLutUri }
                    if (savedLutIndex >= 0) {
                        binding.dropdownLut.setText(
                            lutNames[savedLutIndex + 1],
                            false
                        ) // +1 因为第一项是"未选择"
                        selectedLutItem = availableLuts[savedLutIndex]
                    }
                }

                // 恢复选中的第二个LUT
                val savedLut2Uri = preferencesManager.dashboardLut2Uri
                if (!savedLut2Uri.isNullOrEmpty()) {
                    val savedLut2Index = availableLuts.indexOfFirst { it.filePath == savedLut2Uri }
                    if (savedLut2Index >= 0) {
                        binding.dropdownLut2.setText(lutNames[savedLut2Index + 1], false)
                        selectedLut2Item = availableLuts[savedLut2Index]
                        Log.d("DashboardFragment", "恢复第二个LUT选择: ${selectedLut2Item?.name}")
                    }
                }
                
                // 修复：LUT恢复完成后，更新处理按钮状态
                updateProcessButtonState()
                Log.d("DashboardFragment", "LUT恢复完成，按钮状态已更新")
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
            updateProcessButtonState()
            updatePreview()
        }

        /*dashboardViewModel.processingStatus.observe(viewLifecycleOwner) { status ->
            updateProcessingStatus(status)
        }*/

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
        val dashboardStrength = preferencesManager.dashboardStrength
        val sliderStrengthValue = dashboardStrength * 100f
        binding.sliderStrength.value = sliderStrengthValue

        // 加载第二个LUT强度设置
        val dashboardLut2Strength = preferencesManager.dashboardLut2Strength
        val sliderLut2StrengthValue = dashboardLut2Strength * 100f
        binding.sliderLut2Strength.value = sliderLut2StrengthValue

        Log.d(
            "DashboardFragment",
            "加载设置详细信息:"
        )
        Log.d(
            "DashboardFragment",
            "- 主LUT强度: $dashboardStrength (滑块值: $sliderStrengthValue)"
        )
        Log.d(
            "DashboardFragment",
            "- 第二个LUT强度: $dashboardLut2Strength (滑块值: $sliderLut2StrengthValue)"
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

        // 加载颗粒设置
        binding.switchGrain.isChecked = preferencesManager.dashboardGrainEnabled
        binding.buttonGrainSettings.isEnabled = preferencesManager.dashboardGrainEnabled
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

    private fun setupPreviewCard() {
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_dashboard)
        val refreshButton =
            previewCardView?.findViewById<MaterialButton>(R.id.button_refresh_preview)
        val headerLayout = previewCardView?.findViewById<LinearLayout>(R.id.layout_preview_header)
        val contentLayout = previewCardView?.findViewById<FrameLayout>(R.id.layout_preview_content)
        val toggleButton = previewCardView?.findViewById<ImageView>(R.id.button_toggle_preview)

        refreshButton?.setOnClickListener {
            updatePreview()
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
        val isExpanded = preferencesManager.dashboardPreviewExpanded
        if (!isExpanded) {
            contentLayout?.visibility = View.GONE
            toggleButton?.rotation = 180f
        }

        // 初始显示占位符
        showPreviewPlaceholder("请选择图片")
    }

    private fun updatePreview() {
        // 获取第一张选中的图片
        val selectedImages = dashboardViewModel.selectedImages.value
        if (!selectedImages.isNullOrEmpty()) {
            showPreviewImageWithLut(selectedImages.first().uri)
        } else {
            showPreviewPlaceholder("请选择图片")
        }

        // 更新效果信息显示
        updatePreviewEffectsInfo()
    }

    private fun showPreviewImage(uri: Uri) {
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_dashboard)
        val imageView = previewCardView?.findViewById<ImageView>(R.id.image_preview)
        val placeholderText = previewCardView?.findViewById<TextView>(R.id.text_placeholder)

        imageView?.let { iv ->
            placeholderText?.visibility = View.GONE
            iv.visibility = View.VISIBLE

            // 使用Glide加载图片
            Glide.with(this)
                .load(uri)
                .into(iv)
        }
    }

    /**
     * 获取图片的原始尺寸（不加载完整图片）
     */
    private fun getImageDimensions(uri: Uri): Pair<Int, Int> {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
            }
            Pair(options.outWidth, options.outHeight)
        } catch (e: Exception) {
            Log.e("DashboardFragment", "获取图片尺寸失败", e)
            // 返回默认假设尺寸
            Pair(4000, 6000)
        }
    }
    
    private fun showPreviewImageWithLut(uri: Uri) {
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_dashboard)
        val imageView = previewCardView?.findViewById<ImageView>(R.id.image_preview)
        val placeholderText = previewCardView?.findViewById<TextView>(R.id.text_placeholder)

        imageView?.let { iv ->
            placeholderText?.visibility = View.GONE
            iv.visibility = View.VISIBLE

            // 如果没有选择LUT且没有开启水印，直接显示原图
            if (selectedLutItem == null && selectedLut2Item == null && !binding.switchWatermark.isChecked) {
                Glide.with(this)
                    .load(uri)
                    .into(iv)
                return
            }

            // 在后台线程获取原图尺寸
            // 使用 viewLifecycleOwner.lifecycleScope 确保 View 销毁时自动取消
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val (originalWidth, originalHeight) = getImageDimensions(uri)
                Log.d("DashboardFragment", "原图尺寸: ${originalWidth}x${originalHeight}")
                
                withContext(Dispatchers.Main) {
                    // 切换到主线程前再次检查 Fragment 和 binding 状态
                    if (!isAdded || _binding == null) {
                        Log.w("DashboardFragment", "showPreviewImageWithLut: Fragment已销毁，跳过Glide加载")
                        return@withContext
                    }
                    
                    // 使用Glide加载图片并应用LUT效果
                    // 创建唯一的缓存键，包含强度值以避免缓存问题
                    "${uri}_${preferencesManager.dashboardStrength}_${preferencesManager.dashboardLut2Strength}_${binding.switchWatermark.isChecked}"
                    Glide.with(this@DashboardFragment)
                        .asBitmap()
                        .load(uri)
                        .skipMemoryCache(true) // 跳过内存缓存
                        .diskCacheStrategy(DiskCacheStrategy.NONE) // 跳过磁盘缓存
                        .override(800, 600) // 限制预览图片大小以提高性能
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(
                                resource: Bitmap,
                        transition: Transition<in Bitmap>?
                    ) {
                        // 在后台线程应用LUT效果和水印效果
                        // 使用 viewLifecycleOwner.lifecycleScope 确保 View 销毁时自动取消
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            var processedBitmap = resource
                            var hasEffects = false

                            try {
                                // 使用ThreadManager统一处理流程（LUT + 颗粒）
                                val lutPath = selectedLutItem?.let { lutManager.getLutFilePath(it) }
                                val lut2Path = selectedLut2Item?.let { lutManager.getLutFilePath(it) }
                                val strength1 = preferencesManager.dashboardStrength
                                val strength2 = preferencesManager.dashboardLut2Strength

                                // 如果有任何LUT需要应用
                                if (!lutPath.isNullOrEmpty() || !lut2Path.isNullOrEmpty()) {
                                    Log.d("DashboardFragment", "开始使用ThreadManager处理预览")
                                    
                                    // 设置颗粒配置（如果启用）
                                    if (binding.switchGrain.isChecked) {
                                        val originalConfig = preferencesManager.getFilmGrainConfig()
                                        // 为预览缩放颗粒参数，使用真实的原图尺寸
                                        val previewConfig = originalConfig.scaleForPreview(
                                            previewWidth = processedBitmap.width,
                                            previewHeight = processedBitmap.height,
                                            originalWidth = originalWidth,  // 使用真实的原图尺寸
                                            originalHeight = originalHeight
                                        ).copy(isEnabled = true)
                                        threadManager.setFilmGrainConfig(previewConfig)
                                        Log.d("DashboardFragment", "预览颗粒配置已设置并缩放: 原图${originalWidth}x${originalHeight} -> 预览${processedBitmap.width}x${processedBitmap.height}, grainSize: ${originalConfig.grainSize} -> ${previewConfig.grainSize}")
                                    } else {
                                        threadManager.setFilmGrainConfig(null)
                                    }
                                    
                                    // 加载LUT到ThreadManager
                                    if (!lutPath.isNullOrEmpty()) {
                                        val lutFile = File(lutPath)
                                        if (lutFile.exists()) {
                                            threadManager.loadLut(lutFile.inputStream())
                                        }
                                    }
                                    
                                    // 加载第二个LUT（如果有）
                                    if (!lut2Path.isNullOrEmpty()) {
                                        val lut2File = File(lut2Path)
                                        if (lut2File.exists()) {
                                            threadManager.loadSecondLut(lut2File.inputStream())
                                        }
                                    }
                                    
                                    // 创建处理参数
                                    val params = ILutProcessor.ProcessingParams(
                                        strength = strength1,
                                        lut2Strength = strength2,
                                        quality = 95,
                                        ditherType = ILutProcessor.DitherType.NONE
                                    )
                                    
                                    // 使用ThreadManager处理（GPU会在着色器中处理LUT+颗粒）
                                    val lutAndGrainResult = suspendCancellableCoroutine<Bitmap?> { continuation ->
                                        threadManager.submitTask(
                                            bitmap = processedBitmap,
                                            params = params,
                                            onComplete = { result ->
                                                continuation.resume(result.getOrNull())
                                            }
                                        )
                                    }
                                    
                                    if (lutAndGrainResult != null) {
                                        processedBitmap = lutAndGrainResult
                                        hasEffects = true
                                        Log.d("DashboardFragment", "ThreadManager处理成功（LUT+颗粒）")
                                    } else {
                                        Log.w("DashboardFragment", "ThreadManager处理失败")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("DashboardFragment", "ThreadManager处理失败，回退到原方式", e)
                                
                                // 回退到原来的处理方式
                                try {
                                    val lutPath = selectedLutItem?.let { lutManager.getLutFilePath(it) }
                                    val lut2Path = selectedLut2Item?.let { lutManager.getLutFilePath(it) }
                                    val strength1 = preferencesManager.dashboardStrength
                                    val strength2 = preferencesManager.dashboardLut2Strength
                                    
                                    // GPU LUT处理
                                    if (!lutPath.isNullOrEmpty() || !lut2Path.isNullOrEmpty()) {
                                        val lutResult = LutUtils.applyDualLutGpu(
                                            processedBitmap,
                                            lutPath,
                                            strength1,
                                            lut2Path,
                                            strength2,
                                            requireContext()
                                        )
                                        if (lutResult != null) {
                                            processedBitmap = lutResult
                                            hasEffects = true
                                        }
                                    }
                                    Log.d("DashboardFragment", "回退处理完成")
                                } catch (fallbackException: Exception) {
                                    Log.e("DashboardFragment", "回退处理也失败", fallbackException)
                                }
                            }

                            try {
                                // 应用水印效果
                                if (binding.switchWatermark.isChecked) {
                                    val watermarkConfig = preferencesManager.getWatermarkConfig(forFolderMonitor = false)
                                    val watermarkResult = WatermarkUtils.addWatermark(
                                        processedBitmap,
                                        watermarkConfig,
                                        requireContext(),
                                        uri,
                                        selectedLutItem?.name,
                                        selectedLut2Item?.name,
                                        preferencesManager.dashboardStrength,
                                        preferencesManager.dashboardLut2Strength
                                    )
                                    if (watermarkResult != null) {
                                        processedBitmap = watermarkResult
                                        hasEffects = true
                                        Log.d("DashboardFragment", "水印效果应用成功")
                                    } else {
                                        Log.w("DashboardFragment", "水印效果应用失败")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("DashboardFragment", "应用水印效果失败", e)
                            }

                            // 颗粒效果已在ThreadManager中处理（GPU着色器或CPU回退）
                            // 无需单独处理颗粒

                            // 在主线程更新UI
                            withContext(Dispatchers.Main) {
                                // 切换到主线程后再次检查 Fragment 和 binding 状态
                                if (isAdded && !isDetached && _binding != null) { // 确保Fragment仍然附加且binding未销毁
                                    iv.setImageBitmap(processedBitmap)
                                    if (hasEffects) {
                                        Log.d("DashboardFragment", "预览效果应用完成")
                                    } else {
                                        Log.d(
                                            "DashboardFragment",
                                            "预览显示原图（无效果或效果应用失败）"
                                        )
                                    }
                                }
                            }
                        }
                    }

                            override fun onLoadCleared(placeholder: Drawable?) {
                                // 清理资源
                            }
                        })
                }
            }
        }
    }

    private fun showPreviewPlaceholder(message: String) {
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_dashboard)
        val imageView = previewCardView?.findViewById<ImageView>(R.id.image_preview)
        val placeholderText = previewCardView?.findViewById<TextView>(R.id.text_placeholder)

        imageView?.visibility = View.GONE
        placeholderText?.let {
            it.visibility = View.VISIBLE
            it.text = message
        }
    }

    private fun updatePreviewEffectsInfo() {
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_dashboard)
        val effectsInfoText = previewCardView?.findViewById<TextView>(R.id.text_effects_info)

        val effects = mutableListOf<String>()

        // 添加LUT信息
        selectedLutItem?.let { effects.add("LUT1: ${it.name}") }
        selectedLut2Item?.let { effects.add("LUT2: ${it.name}") }

        // 添加水印信息
        if (binding.switchWatermark.isChecked) {
            effects.add("水印")
        }

        // 添加颗粒信息
        if (binding.switchGrain.isChecked) {
            effects.add("颗粒")
        }

        // 添加抖动信息
        val ditherType = getDitherType()
        if (ditherType != ILutProcessor.DitherType.NONE) {
            effects.add("抖动: ${ditherType.name}")
        }

        effectsInfoText?.text = if (effects.isNotEmpty()) {
            effects.joinToString(" + ")
        } else {
            "无效果"
        }
    }

    private fun restoreUIState() {
        // 恢复UI状态，如折叠面板的展开/收起状态
        val isParamsExpanded = preferencesManager.dashboardParamsExpanded
        val isFileSettingsExpanded = preferencesManager.dashboardFileSettingsExpanded
        val isPreviewExpanded = preferencesManager.dashboardPreviewExpanded

        if (!isParamsExpanded) {
            binding.layoutParamsContent.visibility = View.GONE
            binding.buttonToggleParams.rotation = 180f
        }

        if (!isFileSettingsExpanded) {
            binding.layoutFileSettingsContent.visibility = View.GONE
            binding.buttonToggleFileSettings.rotation = 180f
        }

        // 恢复预览卡片的折叠状态
        if (!isPreviewExpanded) {
            val previewCardView = binding.root.findViewById<View>(R.id.preview_card_dashboard)
            val contentLayout =
                previewCardView?.findViewById<FrameLayout>(R.id.layout_preview_content)
            val toggleButton = previewCardView?.findViewById<ImageView>(R.id.button_toggle_preview)

            contentLayout?.visibility = View.GONE
            toggleButton?.rotation = 180f
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

    private fun togglePreviewSection(contentLayout: View, toggleButton: ImageView) {
        val isVisible = contentLayout.isVisible

        if (isVisible) {
            contentLayout.visibility = View.GONE
            toggleButton.animate().rotation(180f).setDuration(200).start()
        } else {
            contentLayout.visibility = View.VISIBLE
            toggleButton.animate().rotation(0f).setDuration(200).start()
        }

        // 保存预览卡片的折叠状态
        preferencesManager.dashboardPreviewExpanded = !isVisible
    }

    @SuppressLint("SetTextI18n")
    private fun setupSliders() {
        // 设置强度滑块
        binding.sliderStrength.addOnChangeListener { _, value, _ ->
            val strengthValue = (value / 100f)
            preferencesManager.dashboardStrength = strengthValue
            binding.textStrengthValue.text = "${value.toInt()}%"
            Log.d("DashboardFragment", "主LUT强度滑块变化: 滑块值=$value, 强度值=$strengthValue")
            schedulePreviewUpdate()
        }

        // 设置第二个LUT强度滑块
        binding.sliderLut2Strength.addOnChangeListener { _, value, _ ->
            val lut2StrengthValue = (value / 100f)
            preferencesManager.dashboardLut2Strength = lut2StrengthValue
            binding.textLut2StrengthValue.text = "${value.toInt()}%"
            Log.d(
                "DashboardFragment",
                "第二个LUT强度滑块变化: 滑块值=$value, 强度值=$lut2StrengthValue"
            )
            schedulePreviewUpdate()
        }

        // 设置质量滑块
        binding.sliderQuality.addOnChangeListener { _, value, _ ->
            preferencesManager.dashboardQuality = value
            binding.textQualityValue.text = "${value.toInt()}"
            schedulePreviewUpdate()
        }
    }

    /**
     * 使用防抖机制调度预览更新，避免频繁刷新
     */
    private fun schedulePreviewUpdate() {
        // 取消之前的更新任务
        previewUpdateRunnable?.let { previewUpdateHandler.removeCallbacks(it) }

        // 创建新的更新任务
        previewUpdateRunnable = Runnable {
            updatePreview()
        }

        // 延迟执行更新
        previewUpdateHandler.postDelayed(previewUpdateRunnable!!, PREVIEW_UPDATE_DELAY)
    }

    private fun updateImageCount(count: Int) {
        binding.textImageCount.text = getString(R.string.image_count_format, count)
    }

    /*private fun updateProcessingStatus(status: String) {
        binding.textProcessingStatus.text = status
    }*/
    
    /**
     * 统一更新处理按钮状态
     * 确保在图片列表或LUT选择变化时，按钮状态正确更新
     */
    private fun updateProcessButtonState() {
        val hasImages = dashboardViewModel.selectedImages.value?.isNotEmpty() == true
        val hasLut = selectedLutItem != null
        val shouldEnable = hasImages && hasLut
        
        binding.buttonStartProcessing.isEnabled = shouldEnable
        
        Log.d("DashboardFragment", "更新处理按钮状态: hasImages=$hasImages, hasLut=$hasLut, enabled=$shouldEnable")
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
        // 清理防抖任务
        previewUpdateRunnable?.let { previewUpdateHandler.removeCallbacks(it) }
        
        // 释放ThreadManager资源
        lifecycleScope.launch {
            threadManager.release()
        }
        
        _binding = null
    }
    
    /**
     * 启动图片选择器
     * 优先使用系统相册（ACTION_PICK），不可用时回退到SAF
     */
    private fun launchImagePicker() {
        try {
            // 尝试使用系统相册选择器
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                // 添加MIME类型过滤
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/jpg", "image/webp"))
            }
            
            // 检查是否有应用可以处理这个Intent
            val packageManager = requireContext().packageManager
            if (intent.resolveActivity(packageManager) != null) {
                Log.d("DashboardFragment", "使用系统相册选择器（ACTION_PICK）")
                pickImagesLauncher.launch(intent)
            } else {
                // 系统相册不可用，使用SAF
                Log.d("DashboardFragment", "系统相册不可用，使用SAF")
                useSafPicker()
            }
        } catch (e: Exception) {
            Log.e("DashboardFragment", "启动系统相册失败，使用SAF", e)
            useSafPicker()
        }
    }
    
    /**
     * 使用SAF选择图片（备选方案）
     */
    private fun useSafPicker() {
        try {
            selectImagesSafLauncher.launch(arrayOf("image/*"))
        } catch (e: Exception) {
            Log.e("DashboardFragment", "启动SAF失败", e)
            Toast.makeText(
                requireContext(),
                "无法打开图片选择器",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
