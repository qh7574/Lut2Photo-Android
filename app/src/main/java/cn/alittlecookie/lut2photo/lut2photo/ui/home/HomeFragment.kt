package cn.alittlecookie.lut2photo.lut2photo.ui.home

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.LutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.ThreadManager
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentHomeBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.service.FolderMonitorService
import cn.alittlecookie.lut2photo.lut2photo.service.TetheredShootingService
import cn.alittlecookie.lut2photo.lut2photo.ui.bottomsheet.FilmGrainSettingsBottomSheet
import cn.alittlecookie.lut2photo.lut2photo.ui.bottomsheet.TetheredModeBottomSheet
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by viewModels()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var lutManager: LutManager
    private lateinit var threadManager: ThreadManager
    private var selectedLutItem: LutItem? = null
    private var selectedLut2Item: LutItem? = null  // 第二个LUT
    private var availableLuts: List<LutItem> = emptyList()

    // 防抖机制相关
    private val previewUpdateHandler = Handler(Looper.getMainLooper())
    private var previewUpdateRunnable: Runnable? = null
    private val PREVIEW_UPDATE_DELAY = 500L // 增加到500ms延迟，优化性能
    
    // 配置广播防抖机制
    private val configBroadcastHandler = Handler(Looper.getMainLooper())
    private var configBroadcastRunnable: Runnable? = null
    private val CONFIG_BROADCAST_DELAY = 500L // 500ms延迟，避免频繁重新加载LUT
    
    // 请求去重机制
    private var currentProcessingKey: String? = null
    private val processingLock = Any()

    // 联机模式广播接收器
    private val tetheredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("HomeFragment", "收到广播: ${intent?.action}")
            when (intent?.action) {
                TetheredShootingService.ACTION_CAMERA_CONNECTED -> {
                    Log.i("HomeFragment", "相机已连接，更新 UI")
                    updateTetheredStatus(true)
                }
                TetheredShootingService.ACTION_CAMERA_DISCONNECTED -> {
                    Log.i("HomeFragment", "相机已断开，但保持 Service 运行")
                    updateTetheredStatus(false)
                    // 不关闭开关，让 Service 继续运行
                    // 用户可以手动关闭开关来停止 Service
                }
                TetheredShootingService.ACTION_CONNECTION_ERROR -> {
                    val errorMessage = intent.getStringExtra(
                        TetheredShootingService.EXTRA_ERROR_MESSAGE
                    ) ?: getString(R.string.unknown)
                    Log.e("HomeFragment", "连接错误: $errorMessage")
                    showConnectionErrorDialog(errorMessage)
                    binding.switchTetheredMode.isChecked = false
                }
                TetheredShootingService.ACTION_PHOTO_DOWNLOADED -> {
                    val photoPath = intent.getStringExtra(
                        TetheredShootingService.EXTRA_PHOTO_PATH
                    )
                    if (photoPath != null) {
                        showToast(getString(R.string.photo_downloaded, photoPath.substringAfterLast('/')))
                        // 刷新预览
                        schedulePreviewUpdate()
                    }
                }
            }
        }
    }
    
    // 文件夹监控状态广播接收器
    private val monitoringStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("HomeFragment", "========== 广播接收器被调用 ==========")
            Log.d("HomeFragment", "Intent action: ${intent?.action}")
            Log.d("HomeFragment", "Intent extras: ${intent?.extras}")
            
            when (intent?.action) {
                FolderMonitorService.ACTION_MONITORING_STATUS_UPDATE -> {
                    val statusMessage = intent.getStringExtra(FolderMonitorService.EXTRA_STATUS_MESSAGE) ?: "未知状态"
                    val isMonitoring = intent.getBooleanExtra(FolderMonitorService.EXTRA_IS_MONITORING, false)
                    
                    Log.d("HomeFragment", "收到监控状态更新广播")
                    Log.d("HomeFragment", "  - 状态消息: $statusMessage")
                    Log.d("HomeFragment", "  - 是否监控中: $isMonitoring")
                    Log.d("HomeFragment", "  - Fragment状态: isAdded=$isAdded, isVisible=$isVisible, isResumed=$isResumed")
                    
                    // 更新UI
                    binding.textMonitoringStatus.text = "$statusMessage"
                    Log.d("HomeFragment", "  - UI已更新: ${binding.textMonitoringStatus.text}")
                    
                    // 根据监控状态控制"仅处理新增文件"开关
                    binding.switchProcessNewFilesOnly.isEnabled = !isMonitoring
                    Log.d("HomeFragment", "  - 开关状态已更新: enabled=${binding.switchProcessNewFilesOnly.isEnabled}")
                }
                "cn.alittlecookie.lut2photo.FILE_COUNT_WARNING" -> {
                    val fileCount = intent.getIntExtra("file_count", 0)
                    Log.w("HomeFragment", "收到文件数量警告: $fileCount 个文件")
                    
                    // 显示Toast提示（3秒）
                    Toast.makeText(
                        requireContext(),
                        "输入文件夹内文件数量过多（$fileCount 个），更改文件夹以提高处理性能",
                        Toast.LENGTH_LONG  // LENGTH_LONG 约3.5秒
                    ).show()
                }
                else -> {
                    Log.w("HomeFragment", "收到未知广播: ${intent?.action}")
                }
            }
        }
    }

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
        threadManager = ThreadManager(requireContext())
        
        setupViews()
        setupTetheredMode()
        setupLutSpinner()
        setupPreviewCard()
        loadSavedSettings()
        restoreUIState()
        registerTetheredReceiver()
        
        // 注册监控状态广播接收器（在onViewCreated中注册，确保及时接收广播）
        registerMonitoringStatusReceiver()

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
        // 帮助按钮
        binding.buttonHelpHome.setOnClickListener { view ->
            showHelpMenu(view)
        }
        
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
            // 修复：使用防抖机制，避免重复处理
            schedulePreviewUpdate()
            Log.d("HomeFragment", "文件夹监控水印开关状态改变: $isChecked")
            
            // 水印配置不需要发送广播，因为每次处理图片时都会实时读取配置
            // 水印是在图片处理的最后阶段添加的，不影响 LUT 处理流程
        }

        // 添加颗粒开关监听器
        binding.switchGrain.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.folderMonitorGrainEnabled = isChecked
            binding.buttonGrainSettings.isEnabled = isChecked
            schedulePreviewUpdate()
            Log.d("HomeFragment", "文件夹监控颗粒开关状态改变: $isChecked")
            
            // 如果监控正在运行，发送广播通知服务更新颗粒配置
            if (preferencesManager.isMonitoring) {
                val intent = Intent("cn.alittlecookie.lut2photo.GRAIN_CONFIG_CHANGED")
                intent.setPackage(requireContext().packageName)
                requireContext().sendBroadcast(intent)
                Log.d("HomeFragment", "已发送颗粒配置变化广播")
            }
        }

        // 颗粒设置按钮
        binding.buttonGrainSettings.setOnClickListener {
            val bottomSheet = FilmGrainSettingsBottomSheet.newInstance(
                onConfigChanged = { config ->
                    schedulePreviewUpdate()
                }
            )
            bottomSheet.show(parentFragmentManager, "FilmGrainSettingsBottomSheet")
        }

        // 添加"仅处理新增文件"开关监听器
        binding.switchProcessNewFilesOnly.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.processNewFilesOnly = isChecked
            Log.d("HomeFragment", "仅处理新增文件开关状态改变: $isChecked")
            
            // 如果关闭开关，清除所有已跳过的记录和缓存
            if (!isChecked) {
                Log.d("HomeFragment", "========== 开始清除所有缓存和记录 ==========")
                
                // 1. 清除处理历史记录（SharedPreferences）
                preferencesManager.clearSkippedRecords(requireContext())
                Log.d("HomeFragment", "✓ 已清除处理历史记录")
                
                // 2. 清除 FileTracker 的持久化存储
                clearFileTrackerCache()
                Log.d("HomeFragment", "✓ 已清除 FileTracker 缓存")
                
                // 3. 如果服务正在运行，通知服务清除内存缓存
                if (preferencesManager.isMonitoring) {
                    val intent = Intent(requireContext(), FolderMonitorService::class.java).apply {
                        action = "cn.alittlecookie.lut2photo.CLEAR_CACHES"
                    }
                    requireContext().startService(intent)
                    Log.d("HomeFragment", "✓ 已通知服务清除内存缓存")
                }
                
                // 4. 发送广播通知历史页面更新
                val updateIntent = Intent("cn.alittlecookie.lut2photo.PROCESSING_UPDATE")
                requireContext().sendBroadcast(updateIntent)
                
                Log.d("HomeFragment", "========== 缓存和记录清除完成 ==========")
                showToast("已清除所有缓存，现有文件将被重新处理")
            }
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
                    showToast("请设置输入和输出文件夹")

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
        // 注意：状态文本现在由广播接收器管理，不再从ViewModel观察
        // 移除了 homeViewModel.statusText.observe()，避免覆盖广播接收器设置的详细状态
        
        homeViewModel.isMonitoring.observe(viewLifecycleOwner) { isMonitoring ->
            // 修复：只在状态不一致时才同步，避免取消用户点击产生的动画
            // 如果开关状态已经是目标状态，说明是用户操作触发的ViewModel更新，无需再次设置
            if (binding.switchMonitoring.isChecked != isMonitoring) {
                // 状态不一致，需要同步（通常是程序自动恢复状态时）
                setSwitchStateWithoutAnimation(
                    binding.switchMonitoring, 
                    isMonitoring,
                    restoreListener = { setupSwitchListener() }
                )
                Log.d("HomeFragment", "ViewModel状态同步到UI: isMonitoring=$isMonitoring (状态已同步)")
            } else {
                Log.d("HomeFragment", "ViewModel状态与UI一致: isMonitoring=$isMonitoring (无需同步)")
            }
            
            // 更新"仅处理新增文件"开关状态
            binding.switchProcessNewFilesOnly.isEnabled = !isMonitoring
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
                
                // 发送LUT配置变化广播
                sendLutConfigChangesBroadcast()
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
                    } ?: run {
                        preferencesManager.homeLutUri = ""
                        // 修复：LUT1 变为 null 时，清除 ThreadManager 中已加载的主 LUT
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                threadManager.clearMainLut()
                                Log.d("HomeFragment", "已清除 ThreadManager 中的主 LUT")
                            } catch (e: Exception) {
                                Log.e("HomeFragment", "清除主 LUT 失败", e)
                            }
                        }
                    }
                    updateLutStrengthSliderState()  // 新增：更新滑块状态
                    updateMonitoringButtonState()

                    // 发送LUT配置变化广播
                    sendLutConfigChangesBroadcast()
                    
                    // 修复：触发预览更新，确保预览图正确显示
                    schedulePreviewUpdate()

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
                        preferencesManager.homeLut2Uri = ""
                        // 修复：LUT2 变为 null 时，清除 ThreadManager 中已加载的第二个 LUT
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                threadManager.clearSecondLut()
                                Log.d("HomeFragment", "已清除 ThreadManager 中的第二个 LUT")
                            } catch (e: Exception) {
                                Log.e("HomeFragment", "清除第二个 LUT 失败", e)
                            }
                        }
                    }
                    updateLut2StrengthSliderState()  // 新增：更新滑块状态

                    // 发送LUT配置变化广播
                    sendLutConfigChangesBroadcast()
                    
                    // 修复：触发预览更新，确保预览图正确显示
                    schedulePreviewUpdate()

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
                
                // 恢复LUT后，更新滑块状态（确保在主线程执行）
                withContext(Dispatchers.Main) {
                    updateLutStrengthSliderState()
                    updateLut2StrengthSliderState()
                    Log.d("HomeFragment", "滑块状态已更新: LUT1=${selectedLutItem != null}, LUT2=${selectedLut2Item != null}")
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
        // 注意：滑块的值在 setupSliders() 中设置，避免重复触发
        
        // 加载抖动类型设置
        val ditherType = getDitherType()
        val buttonId = when (ditherType) {
            LutProcessor.DitherType.FLOYD_STEINBERG -> R.id.button_dither_floyd
            LutProcessor.DitherType.RANDOM -> R.id.button_dither_random
            LutProcessor.DitherType.NONE -> R.id.button_dither_none
        }
        binding.toggleGroupDither.check(buttonId)

        // 加载水印开关状态（不播放动画）
        binding.switchWatermark.isChecked = preferencesManager.folderMonitorWatermarkEnabled
        binding.switchWatermark.jumpDrawablesToCurrentState()
        binding.buttonWatermarkSettings.isEnabled = preferencesManager.folderMonitorWatermarkEnabled

        // 加载颗粒开关状态（不播放动画）
        binding.switchGrain.isChecked = preferencesManager.folderMonitorGrainEnabled
        binding.switchGrain.jumpDrawablesToCurrentState()
        binding.buttonGrainSettings.isEnabled = preferencesManager.folderMonitorGrainEnabled

        // 加载"仅处理新增文件"开关状态（不播放动画）
        binding.switchProcessNewFilesOnly.isChecked = preferencesManager.processNewFilesOnly
        binding.switchProcessNewFilesOnly.jumpDrawablesToCurrentState()
    
        // 加载文件夹路径显示
        updateInputFolderDisplay()
        updateOutputFolderDisplay()

        // 预先设置监控开关状态（不播放动画），避免后续ViewModel同步时产生动画
        // 注意：这里只设置UI状态，不触发监听器，实际的服务启动由ViewModel控制
        binding.switchMonitoring.setOnCheckedChangeListener(null)
        binding.switchMonitoring.isChecked = preferencesManager.isMonitoring
        binding.switchMonitoring.jumpDrawablesToCurrentState()
        setupSwitchListener()

        Log.d("HomeFragment", "设置加载完成，监控开关预设为: ${preferencesManager.isMonitoring}")
        
        // 注意：滑块状态在 setupLutSpinner() 完成后初始化，因为需要等待 LUT 加载完成
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
        // 先设置初始值，避免触发监听器
        binding.sliderStrength.value = preferencesManager.homeStrength
        binding.sliderLut2Strength.value = preferencesManager.homeLut2Strength
        binding.sliderQuality.value = preferencesManager.homeQuality
        
        // 更新显示文本
        binding.textStrengthValue.text = "${preferencesManager.homeStrength.toInt()}%"
        binding.textLut2StrengthValue.text = "${preferencesManager.homeLut2Strength.toInt()}%"
        binding.textQualityValue.text = "${preferencesManager.homeQuality.toInt()}"
        
        // 然后才添加监听器，只响应用户操作
        binding.sliderStrength.addOnChangeListener { _, value, fromUser ->
            if (fromUser) { // 只响应用户操作，忽略程序设置
                Log.d("HomeFragment", "滑块1用户变化: $value")
                preferencesManager.homeStrength = value
                binding.textStrengthValue.text = "${value.toInt()}%"
                
                // 发送LUT配置变化广播
                sendLutConfigChangesBroadcast()
            }
        }

        // 第二个LUT强度滑块
        binding.sliderLut2Strength.addOnChangeListener { _, value, fromUser ->
            if (fromUser) { // 只响应用户操作
                Log.d("HomeFragment", "滑块2用户变化: $value")
                preferencesManager.homeLut2Strength = value
                binding.textLut2StrengthValue.text = "${value.toInt()}%"
                
                // 发送LUT配置变化广播
                sendLutConfigChangesBroadcast()
            }
        }

        // 质量滑块
        binding.sliderQuality.addOnChangeListener { _, value, fromUser ->
            if (fromUser) { // 只响应用户操作
                Log.d("HomeFragment", "质量滑块用户变化: $value")
                preferencesManager.homeQuality = value
                binding.textQualityValue.text = "${value.toInt()}"
                
                // 发送LUT配置变化广播
                sendLutConfigChangesBroadcast()
            }
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

    /**
     * 注册监控状态广播接收器
     */
    private fun registerMonitoringStatusReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(FolderMonitorService.ACTION_MONITORING_STATUS_UPDATE)
                addAction("cn.alittlecookie.lut2photo.FILE_COUNT_WARNING")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(monitoringStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(monitoringStatusReceiver, filter)
            }
            Log.d("HomeFragment", "========== 监控状态广播接收器已注册 ==========")
        } catch (e: Exception) {
            Log.e("HomeFragment", "注册监控状态广播接收器失败", e)
        }
    }
    
    /**
     * 注销监控状态广播接收器
     */
    private fun unregisterMonitoringStatusReceiver() {
        try {
            requireContext().unregisterReceiver(monitoringStatusReceiver)
            Log.d("HomeFragment", "========== 监控状态广播接收器已注销 ==========")
        } catch (e: Exception) {
            Log.w("HomeFragment", "注销监控状态广播接收器失败（可能未注册）", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 同步联机模式状态，避免触发重新连接
        syncTetheredModeState()
        
        // 检查服务是否真正在运行
        val isServiceActuallyRunning = isFolderMonitorServiceRunning()
        
        // 如果服务实际未运行，但PreferencesManager认为在运行，需要同步状态
        if (!isServiceActuallyRunning && preferencesManager.isMonitoring) {
            Log.w("HomeFragment", "检测到服务状态不一致：PreferencesManager认为在运行，但服务实际未运行，同步状态")
            preferencesManager.isMonitoring = false
            homeViewModel.setMonitoring(false)
        }
        
        // 根据当前监控状态更新UI（只更新开关状态，不覆盖状态文本）
        updateMonitoringStatusUI()
        
        // 如果服务未运行，设置初始状态文本并启用"仅处理新增文件"开关
        if (!isServiceActuallyRunning) {
            binding.textMonitoringStatus.text = "监控状态: 未启动"
            binding.switchProcessNewFilesOnly.isEnabled = true
        } else {
            // 服务正在运行，主动查询当前状态
            queryMonitoringStatus()
        }
    }
    
    /**
     * 主动查询监控服务的当前状态
     * 通过发送广播请求服务响应当前状态
     */
    private fun queryMonitoringStatus() {
        Log.d("HomeFragment", "主动查询监控服务状态")
        
        // 发送状态查询广播
        val queryIntent = Intent(requireContext(), FolderMonitorService::class.java).apply {
            action = FolderMonitorService.ACTION_QUERY_STATUS
        }
        
        try {
            requireContext().startService(queryIntent)
            Log.d("HomeFragment", "状态查询请求已发送")
            
            // 设置超时保护：如果500ms内没有收到响应，说明服务可能未正常运行
            // 使用 viewLifecycleOwner.lifecycleScope 确保在 View 销毁时自动取消
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                // 协程会在 Fragment View 销毁时自动取消，无需额外检查
                // 使用 _binding 安全访问，避免 NPE
                _binding?.let { binding ->
                    if (binding.textMonitoringStatus.text == "监控状态: 未启动" && 
                        preferencesManager.isMonitoring) {
                        Log.w("HomeFragment", "状态查询超时，服务可能未响应")
                        // 同步状态
                        preferencesManager.isMonitoring = false
                        homeViewModel.setMonitoring(false)
                        binding.switchProcessNewFilesOnly.isEnabled = true
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("HomeFragment", "发送状态查询请求失败", e)
        }
    }
    
    /**
     * 检查文件夹监控服务是否正在运行
     */
    private fun isFolderMonitorServiceRunning(): Boolean {
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (FolderMonitorService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
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
    
    /**
     * 设置开关状态但不播放动画
     * 用于状态恢复时避免不必要的动画效果
     * 
     * @param switch 要设置的开关
     * @param checked 目标状态
     * @param restoreListener 恢复监听器的函数（可选）
     */
    private fun setSwitchStateWithoutAnimation(
        switch: androidx.appcompat.widget.SwitchCompat, 
        checked: Boolean,
        restoreListener: (() -> Unit)? = null
    ) {
        // 只有在状态真正改变时才需要处理
        if (switch.isChecked == checked) {
            // 状态没有改变，无需操作
            return
        }
        
        // 临时移除监听器
        switch.setOnCheckedChangeListener(null)
        // 设置状态
        switch.isChecked = checked
        // 立即跳过动画
        switch.jumpDrawablesToCurrentState()
        // 恢复监听器（如果提供了特定的恢复函数则使用，否则使用默认的）
        if (restoreListener != null) {
            restoreListener()
        } else {
            setupSwitchListener()
        }
    }
    
    /**
     * 更新监控状态UI
     * 根据PreferencesManager中的监控状态更新UI显示和控件状态
     * 注意：不更新状态文本，状态文本完全由广播接收器管理
     */
    private fun updateMonitoringStatusUI() {
        val isMonitoring = preferencesManager.isMonitoring
        
        // 不修改状态文本，完全由广播接收器管理
        // 这样可以正确显示"正在扫描"、"正在标记"等详细状态
        
        // 根据监控状态控制"仅处理新增文件"开关
        binding.switchProcessNewFilesOnly.isEnabled = !isMonitoring
        
        Log.d("HomeFragment", "监控状态UI已更新: isMonitoring=$isMonitoring (状态文本由广播接收器管理)")
    }

    private fun canStartMonitoring(): Boolean {
        val hasInputFolder = preferencesManager.homeInputFolder.isNotEmpty()
        val hasOutputFolder = preferencesManager.homeOutputFolder.isNotEmpty()
        // 不再要求必须选择 LUT，可以只进行颗粒、水印等处理
        return hasInputFolder && hasOutputFolder
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
            putExtra(
                FolderMonitorService.EXTRA_PROCESS_NEW_FILES_ONLY,
                preferencesManager.processNewFilesOnly
            )
        }
        requireContext().startForegroundService(intent)
        homeViewModel.setMonitoring(true)
        
        // 更新开关状态（禁用"仅处理新增文件"开关）
        binding.switchProcessNewFilesOnly.isEnabled = false
        // 状态文本将由服务的广播更新
    }

    private fun stopMonitoring() {
        val intent = Intent(requireContext(), FolderMonitorService::class.java).apply {
            action = FolderMonitorService.ACTION_STOP_MONITORING
        }
        requireContext().startService(intent)
        homeViewModel.setMonitoring(false)
        
        // 更新UI状态
        updateMonitoringStatusUI()
        
        // 停止监控后，显式设置状态文本为"未启动"
        binding.textMonitoringStatus.text = "监控状态: 未启动"
    }

    private fun updateMonitoringButtonState() {
        // 这个方法在新的实现中不再需要，因为我们使用开关控件
        // 开关的状态会通过observeViewModel()中的逻辑自动更新
    }

    /**
     * 发送LUT配置变化广播，通知文件夹监控服务更新LUT配置
     * 使用防抖机制，避免频繁发送广播导致服务频繁重新加载LUT
     */
    private fun sendLutConfigChangesBroadcast() {
        // 取消之前的待发送广播
        configBroadcastRunnable?.let { configBroadcastHandler.removeCallbacks(it) }
        
        // 创建新的广播任务
        configBroadcastRunnable = Runnable {
            // 使用显式广播，明确指定接收者为FolderMonitorService
            val intent = Intent("cn.alittlecookie.lut2photo.LUT_CONFIG_CHANGED").apply {
                setPackage(requireContext().packageName)  // 限制在本应用内
            }
            requireContext().sendBroadcast(intent)
            Log.d("HomeFragment", "发送LUT配置变化广播（显式广播）")
        }
        
        // 延迟发送广播
        configBroadcastHandler.postDelayed(configBroadcastRunnable!!, CONFIG_BROADCAST_DELAY)
        Log.d("HomeFragment", "LUT配置变化广播已调度，延迟: ${CONFIG_BROADCAST_DELAY}ms")

        // 使用防抖机制更新预览
        schedulePreviewUpdate()
    }

    /**
     * 使用防抖机制调度预览更新，避免频繁刷新
     */
    private fun schedulePreviewUpdate() {
        // 生成请求键（不包含时间戳）
        val requestKey = generatePreviewRequestKey()
        
        // 修复：如果正在处理相同的请求，直接返回
        synchronized(processingLock) {
            if (currentProcessingKey == requestKey) {
                Log.d("HomeFragment", "相同请求正在处理中，跳过调度: $requestKey")
                return
            }
        }
        
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
            // 在执行前再次检查是否正在处理相同的请求
            synchronized(processingLock) {
                if (currentProcessingKey == requestKey) {
                    Log.d("HomeFragment", "相同请求正在处理中，跳过执行: $requestKey")
                    return@Runnable
                }
                currentProcessingKey = requestKey
            }
            
            try {
                Log.d("HomeFragment", "执行预览更新任务: $requestKey")
                updatePreview()
            } finally {
                synchronized(processingLock) {
                    if (currentProcessingKey == requestKey) {
                        currentProcessingKey = null
                        Log.d("HomeFragment", "预览更新完成，清除处理标记: $requestKey")
                    }
                }
            }
        }

        // 延迟执行更新
        previewUpdateHandler.postDelayed(previewUpdateRunnable!!, PREVIEW_UPDATE_DELAY)
        Log.d("HomeFragment", "预览更新任务已调度，延迟: ${PREVIEW_UPDATE_DELAY}ms, 请求键: $requestKey")
    }
    
    /**
     * 生成预览请求键（用于去重，不包含时间戳）
     */
    private fun generatePreviewRequestKey(): String {
        val inputFolder = preferencesManager.homeInputFolder
        val lut1 = selectedLutItem?.name ?: "none"
        val lut2 = selectedLut2Item?.name ?: "none"
        val strength1 = preferencesManager.homeStrength
        val strength2 = preferencesManager.homeLut2Strength
        val watermark = binding.switchWatermark.isChecked
        
        return "${inputFolder}_${lut1}_${lut2}_${strength1}_${strength2}_${watermark}"
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
        
        // 设置预览图点击事件 - 全屏查看
        val previewImageView = previewCardView?.findViewById<ImageView>(R.id.image_preview)
        previewImageView?.setOnClickListener {
            openFullscreenPreview()
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
        // 安全访问 binding，避免 NPE
        _binding?.let { binding ->
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

            // 添加颗粒信息
            if (binding.switchGrain.isChecked) {
                effects.add("颗粒")
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
        } ?: run {
            Log.w("HomeFragment", "updatePreviewEffectsInfo: binding为null，跳过效果信息更新")
        }
    }

    private fun updatePreviewFromInputFolder() {
        val inputFolderPath = preferencesManager.homeInputFolder
        if (inputFolderPath.isNullOrEmpty()) {
            showPreviewPlaceholder("请选择输入文件夹")
            return
        }

        // 异步获取最新图片文件，避免在主线程遍历大目录导致ANR
        // 使用 viewLifecycleOwner.lifecycleScope 确保 View 销毁时自动取消
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val latestImageUri = withContext(Dispatchers.IO) {
                    getLatestImageFileAsync(inputFolderPath)
                }
                
                // 切换到主线程前检查 Fragment 状态
                if (!isAdded || _binding == null) {
                    Log.w("HomeFragment", "Fragment已销毁，跳过预览更新")
                    return@launch
                }
                
                if (latestImageUri == null) {
                    showPreviewPlaceholder("输入文件夹中没有图片")
                    return@launch
                }
                
                // 在主线程显示预览
                displayPreviewImage(latestImageUri)
                
            } catch (e: Exception) {
                Log.e("HomeFragment", "获取预览图片失败", e)
                // 检查 Fragment 状态后再更新 UI
                if (isAdded && _binding != null) {
                    showPreviewPlaceholder("无法访问输入文件夹")
                }
            }
        }
    }
    
    /**
     * 异步获取最新图片文件（在IO线程执行）
     * 使用流式处理，只保留最新的一个文件，避免内存峰值
     */
    private suspend fun getLatestImageFileAsync(inputFolderPath: String): Uri? {
        val inputFolderUri = inputFolderPath.toUri()
        val inputFolder = DocumentFile.fromTreeUri(requireContext(), inputFolderUri)
            ?: return null
        
        if (!inputFolder.exists() || !inputFolder.isDirectory) {
            return null
        }
        
        // 流式遍历，只保留最新的一个文件
        var latestFile: DocumentFile? = null
        var latestModified = 0L
        
        inputFolder.listFiles().forEach { file ->
            if (file.isFile && isImageFile(file.name)) {
                val modified = file.lastModified()
                if (modified > latestModified) {
                    latestModified = modified
                    latestFile = file
                }
            }
        }
        
        return latestFile?.uri
    }
    
    /**
     * 检查文件是否为图片文件
     */
    private fun isImageFile(fileName: String?): Boolean {
        if (fileName == null) return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in listOf("jpg", "jpeg", "png", "bmp", "webp")
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
            Log.e("HomeFragment", "获取图片尺寸失败", e)
            // 返回默认假设尺寸
            Pair(4000, 6000)
        }
    }
    
    /**
     * 显示预览图片（在主线程执行）
     */
    private fun displayPreviewImage(imageUri: Uri) {
        // 安全访问 binding，避免 NPE
        _binding?.let { binding ->
            val previewCardView = binding.root.findViewById<View>(R.id.preview_card_home)
            val imageView = previewCardView?.findViewById<ImageView>(R.id.image_preview)
            val placeholderText = previewCardView?.findViewById<TextView>(R.id.text_placeholder)
            
            imageView?.let { iv ->
                // 隐藏占位图和占位文本
                val placeholderLayout = previewCardView.findViewById<View>(R.id.layout_placeholder)
                placeholderLayout?.visibility = View.GONE
                placeholderText?.visibility = View.GONE
                iv.visibility = View.VISIBLE

                // 如果没有选择LUT、没有开启水印、也没有开启颗粒，直接显示原图
                if (selectedLutItem == null && selectedLut2Item == null && 
                    !binding.switchWatermark.isChecked && !binding.switchGrain.isChecked) {
                    Glide.with(this)
                        .load(imageUri)
                        .into(iv)
                    return
                }

                // 使用Glide加载图片并应用LUT和水印效果
                // 在加载前固定强度值，避免处理时值发生变化
                val currentStrength1 = preferencesManager.homeStrength
                val currentStrength2 = preferencesManager.homeLut2Strength
                val currentWatermarkEnabled = binding.switchWatermark.isChecked

                // 使用真正影响图像的参数作为缓存键（移除时间戳以启用缓存）
                val cacheKey =
                    "${imageUri}_${selectedLutItem?.name}_${selectedLut2Item?.name}_${currentStrength1}_${currentStrength2}_${currentWatermarkEnabled}"

                Log.d("HomeFragment", "生成缓存键: $cacheKey")
                Log.d(
                    "HomeFragment",
                    "预览更新 - 强度1: $currentStrength1, 强度2: $currentStrength2, 水印: $currentWatermarkEnabled"
                )

                // 在后台线程获取原图尺寸
                // 使用 viewLifecycleOwner.lifecycleScope 确保 View 销毁时自动取消
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val (originalWidth, originalHeight) = getImageDimensions(imageUri)
                    Log.d("HomeFragment", "原图尺寸: ${originalWidth}x${originalHeight}")
                    
                    withContext(Dispatchers.Main) {
                        // 切换到主线程前再次检查 Fragment 和 binding 状态
                        if (!isAdded || _binding == null) {
                            Log.w("HomeFragment", "displayPreviewImage: Fragment已销毁，跳过Glide加载")
                            return@withContext
                        }
                        
                        Glide.with(this@HomeFragment)
                            .asBitmap()
                            .load(imageUri)
                            .signature(ObjectKey(cacheKey)) // 使用不含时间戳的缓存键，启用缓存
                            .skipMemoryCache(false) // 启用内存缓存
                            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC) // 启用磁盘缓存
                            .override(900, 600) // 限制预览图片大小以提高性能
                            // **修复：移除 dontTransform()，让 Glide 自动处理 EXIF 方向**
                            .format(DecodeFormat.PREFER_ARGB_8888) // 强制使用ARGB_8888格式
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

                                    // 如果有任何LUT需要应用，或者开启了颗粒效果
                                    if (!lutPath.isNullOrEmpty() || !lut2Path.isNullOrEmpty() || binding.switchGrain.isChecked) {
                                        // 使用固定的强度值，确保一致性
                                        val strength1 = currentStrength1 / 100f
                                        val strength2 = currentStrength2 / 100f

                                        Log.d("HomeFragment", "开始使用ThreadManager处理预览 (LUT=${!lutPath.isNullOrEmpty()}, LUT2=${!lut2Path.isNullOrEmpty()}, 颗粒=${binding.switchGrain.isChecked})")
                                        
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
                                            Log.d("HomeFragment", "预览颗粒配置已设置并缩放: 原图${originalWidth}x${originalHeight} -> 预览${processedBitmap.width}x${processedBitmap.height}, grainSize: ${originalConfig.grainSize} -> ${previewConfig.grainSize}")
                                        } else {
                                            threadManager.setFilmGrainConfig(null)
                                        }
                                        
                                        // 加载LUT到ThreadManager（如果有）
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
                                        
                                        // 使用ThreadManager处理（GPU会在着色器中处理LUT+颗粒，CPU会返回原图副本+单独处理颗粒）
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
                                            Log.d("HomeFragment", "ThreadManager处理成功（LUT+颗粒）")
                                        } else {
                                            Log.w("HomeFragment", "ThreadManager处理失败")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("HomeFragment", "ThreadManager处理失败，回退到原方式", e)
                                    
                                    // 回退到原来的处理方式
                                    try {
                                        val lutPath = selectedLutItem?.let { lutManager.getLutFilePath(it) }
                                        val lut2Path = selectedLut2Item?.let { lutManager.getLutFilePath(it) }
                                        val strength1 = currentStrength1 / 100f
                                        val strength2 = currentStrength2 / 100f
                                        
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
                                        Log.d("HomeFragment", "回退处理完成")
                                    } catch (fallbackException: Exception) {
                                        Log.e("HomeFragment", "回退处理也失败", fallbackException)
                                    }
                                }

                                try {
                                    // 应用水印效果
                                    if (currentWatermarkEnabled) {
                                        val watermarkConfig =
                                            preferencesManager.getWatermarkConfig(forFolderMonitor = true)
                                        val watermarkResult = WatermarkUtils.addWatermark(
                                            processedBitmap,
                                            watermarkConfig,
                                            requireContext(),
                                            imageUri,
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

                                // 颗粒效果已在ThreadManager中处理（GPU着色器或CPU回退）
                                // 无需单独处理颗粒

                                // 在主线程更新UI
                                withContext(Dispatchers.Main) {
                                    Log.d(
                                        "HomeFragment",
                                        "准备更新UI - isAdded: $isAdded, isDetached: $isDetached"
                                    )
                                    // 切换到主线程后再次检查 Fragment 和 binding 状态
                                    if (isAdded && !isDetached && _binding != null) { // 确保Fragment仍然附加且binding未销毁
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
                }
            }
        } ?: run {
            Log.w("HomeFragment", "displayPreviewImage: binding为null，跳过预览显示")
        }
    }

    private fun showPreviewPlaceholder(message: String) {
        // 安全访问 binding，避免 NPE
        _binding?.let { binding ->
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
        } ?: run {
            Log.w("HomeFragment", "showPreviewPlaceholder: binding为null，跳过占位符显示")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 清理防抖任务
        previewUpdateRunnable?.let { previewUpdateHandler.removeCallbacks(it) }
        configBroadcastRunnable?.let { configBroadcastHandler.removeCallbacks(it) }
        
        // 注销广播接收器
        unregisterTetheredReceiver()
        unregisterMonitoringStatusReceiver()
        
        // 释放ThreadManager资源
        lifecycleScope.launch {
            threadManager.release()
        }
        
        _binding = null
    }
    
    /**
     * 更新 LUT1 强度滑块状态
     */
    private fun updateLutStrengthSliderState() {
        val isEnabled = selectedLutItem != null
        binding.sliderStrength.isEnabled = isEnabled
        binding.textStrengthValue.alpha = if (isEnabled) 1.0f else 0.5f
        binding.sliderStrength.alpha = if (isEnabled) 1.0f else 0.5f
        Log.d("HomeFragment", "updateLutStrengthSliderState: isEnabled=$isEnabled, selectedLutItem=${selectedLutItem?.name}")
    }

    /**
     * 更新 LUT2 强度滑块状态
     */
    private fun updateLut2StrengthSliderState() {
        val isEnabled = selectedLut2Item != null
        binding.sliderLut2Strength.isEnabled = isEnabled
        binding.textLut2StrengthValue.alpha = if (isEnabled) 1.0f else 0.5f
        binding.sliderLut2Strength.alpha = if (isEnabled) 1.0f else 0.5f
        Log.d("HomeFragment", "updateLut2StrengthSliderState: isEnabled=$isEnabled, selectedLut2Item=${selectedLut2Item?.name}")
    }

    private fun showWatermarkSettingsDialog() {
        val bottomSheet = WatermarkSettingsBottomSheet.newInstance(
            onConfigSaved = { config ->
                // 配置保存后的回调
                updatePreview()
                Log.d("HomeFragment", "水印配置已保存: $config")
            },
            forFolderMonitor = true,  // 标识为文件夹监控页面
            lut1Name = selectedLutItem?.name,
            lut2Name = selectedLut2Item?.name,
            lut1Strength = preferencesManager.homeStrength,
            lut2Strength = preferencesManager.homeLut2Strength
        )
        bottomSheet.show(parentFragmentManager, "WatermarkSettingsBottomSheet")
    }

    // ==================== 联机模式功能 ====================

    private fun setupTetheredMode() {
        // 联机模式开关
        binding.switchTetheredMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startTetheredMode()
            } else {
                stopTetheredMode()
            }
        }

        // 设置按钮 - 打开联机模式 BottomSheet
        binding.buttonTetheredSettings.setOnClickListener {
            if (binding.switchTetheredMode.isChecked) {
                showTetheredBottomSheet()
            } else {
                showToast(getString(R.string.enable_tethered_mode_first))
            }
        }

        // 检查 Service 是否正在运行，同步开关状态
        syncTetheredModeState()
    }
    
    /**
     * 同步联机模式状态（不触发启动/停止操作，不播放动画）
     */
    private fun syncTetheredModeState() {
        val isServiceRunning = isTetheredServiceRunning()
        Log.d("HomeFragment", "同步联机模式状态: Service 运行中=$isServiceRunning")
        
        // 设置状态但不播放动画
        binding.switchTetheredMode.setOnCheckedChangeListener(null)
        binding.switchTetheredMode.isChecked = isServiceRunning
        binding.switchTetheredMode.jumpDrawablesToCurrentState()
        
        // 恢复监听器
        binding.switchTetheredMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startTetheredMode()
            } else {
                stopTetheredMode()
            }
        }
        
        // 更新状态显示
        if (isServiceRunning) {
            updateTetheredStatus(true)
        } else {
            updateTetheredStatus(false)
        }
    }
    
    /**
     * 检查联机拍摄服务是否正在运行
     */
    private fun isTetheredServiceRunning(): Boolean {
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (TetheredShootingService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun startTetheredMode() {
        Log.i("HomeFragment", "启动联机模式")
        
        // 检查输入文件夹是否已设置
        if (preferencesManager.homeInputFolder.isEmpty()) {
            showToast(getString(R.string.no_input_folder_selected))
            binding.switchTetheredMode.isChecked = false
            return
        }

        // 启动联机拍摄服务
        val intent = Intent(requireContext(), TetheredShootingService::class.java)
        requireContext().startService(intent)
        
        updateTetheredStatus(false, getString(R.string.camera_connecting))
    }

    private fun stopTetheredMode() {
        Log.i("HomeFragment", "停止联机模式")
        
        // 停止联机拍摄服务
        val intent = Intent(requireContext(), TetheredShootingService::class.java)
        requireContext().stopService(intent)
        
        updateTetheredStatus(false)
    }

    private fun showTetheredBottomSheet() {
        val bottomSheet = TetheredModeBottomSheet.newInstance()
        bottomSheet.show(parentFragmentManager, "TetheredModeBottomSheet")
    }

    private fun showConnectionErrorDialog(errorMessage: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.connection_error))
            .setMessage(getString(R.string.connection_error_message, errorMessage))
            .setPositiveButton(getString(R.string.retry)) { _, _ ->
                binding.switchTetheredMode.isChecked = true
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun updateTetheredStatus(isConnected: Boolean, customMessage: String? = null) {
        binding.textTetheredStatus.text = customMessage ?: if (isConnected) {
            getString(R.string.camera_connected)
        } else {
            getString(R.string.camera_disconnected)
        }
        
        // 只有在连接时才启用设置按钮
        binding.buttonTetheredSettings.isEnabled = isConnected
    }

    private fun registerTetheredReceiver() {
        val filter = IntentFilter().apply {
            addAction(TetheredShootingService.ACTION_CAMERA_CONNECTED)
            addAction(TetheredShootingService.ACTION_CAMERA_DISCONNECTED)
            addAction(TetheredShootingService.ACTION_CONNECTION_ERROR)
            addAction(TetheredShootingService.ACTION_PHOTO_DOWNLOADED)
        }
        requireContext().registerReceiver(tetheredReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterTetheredReceiver() {
        try {
            requireContext().unregisterReceiver(tetheredReceiver)
        } catch (e: Exception) {
            Log.e("HomeFragment", "注销广播接收器失败", e)
        }
    }

    /**
     * 显示帮助菜单
     */
    private fun showHelpMenu(view: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.menu_help, popup.menu)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_app_guide -> {
                    openHelpUrl("https://alittlecookie.cn/2025/12/04/Lut2Photo%E4%BD%BF%E7%94%A8%E8%AF%B4%E6%98%8E%E5%8F%8A%E4%B8%8B%E8%BD%BD%F0%9F%94%97/")
                    true
                }
                R.id.menu_watermark_help -> {
                    openHelpUrl("https://alittlecookie.cn/2025/12/12/Lut2Photo%E6%B0%B4%E5%8D%B0%E8%AE%BE%E7%BD%AE%E5%8F%82%E6%95%B0%E8%AF%A6%E8%A7%A3/")
                    true
                }
                R.id.menu_grain_help -> {
                    openHelpUrl("https://alittlecookie.cn/2025/12/12/Lut2Photo%E8%83%B6%E7%89%87%E9%A2%97%E7%B2%92%E5%8F%82%E6%95%B0%E8%AF%A6%E8%A7%A3/")
                    true
                }
                else -> false
            }
        }
        
        popup.show()
    }

    /**
     * 在浏览器中打开帮助链接
     */
    private fun openHelpUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("HomeFragment", "打开帮助链接失败: $url", e)
            Toast.makeText(requireContext(), "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 打开全屏预览图片
     */
    private fun openFullscreenPreview() {
        val previewCardView = binding.root.findViewById<View>(R.id.preview_card_home)
        val imageView = previewCardView?.findViewById<ImageView>(R.id.image_preview)
        val bitmap = (imageView?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (bitmap != null) {
            // 保存到临时文件
            val uri = cn.alittlecookie.lut2photo.lut2photo.utils.ImageShareUtils.saveBitmapToTempFile(requireContext(), bitmap)
            if (uri != null) {
                // 启动全屏 Activity
                val intent = Intent(requireContext(), cn.alittlecookie.lut2photo.lut2photo.ui.FullscreenImageActivity::class.java).apply {
                    putExtra(cn.alittlecookie.lut2photo.lut2photo.ui.FullscreenImageActivity.EXTRA_IMAGE_URI, uri.toString())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                
                // 清理旧文件
                cn.alittlecookie.lut2photo.lut2photo.utils.ImageShareUtils.cleanOldTempFiles(requireContext())
            } else {
                Toast.makeText(requireContext(), "无法保存预览图", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "请先生成预览图", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 清除 FileTracker 的持久化缓存
     * 删除 FileTracker 的 SharedPreferences 文件
     */
    private fun clearFileTrackerCache() {
        try {
            // FileTracker 使用的 SharedPreferences 名称是 "file_tracker_${folderUri的hash}"
            // 我们需要清除所有 file_tracker_ 开头的 SharedPreferences
            val prefsDir = File(requireContext().applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                prefsDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("file_tracker_") && file.name.endsWith(".xml")) {
                        val deleted = file.delete()
                        Log.d("HomeFragment", "删除 FileTracker 缓存文件: ${file.name}, 结果: $deleted")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "清除 FileTracker 缓存失败", e)
        }
    }
}