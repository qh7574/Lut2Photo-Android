package cn.alittlecookie.lut2photo.lut2photo.ui.bottomsheet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.adapter.CameraPhotoAdapter
import cn.alittlecookie.lut2photo.lut2photo.core.GPhoto2Manager
import cn.alittlecookie.lut2photo.lut2photo.databinding.BottomsheetTetheredModeBinding
import cn.alittlecookie.lut2photo.lut2photo.model.ConfigItem
import cn.alittlecookie.lut2photo.lut2photo.service.TetheredShootingService
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 联机模式 BottomSheet
 * 
 * 注意：所有相机 I/O 操作都通过 GPhoto2Manager 的同步锁保护，
 * 可以与 Service 的事件监听并发执行而不会冲突。
 */
class TetheredModeBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "TetheredModeBottomSheet"

        fun newInstance(): TetheredModeBottomSheet {
            return TetheredModeBottomSheet()
        }
    }

    private var _binding: BottomsheetTetheredModeBinding? = null
    private val binding get() = _binding!!
    
    // 检查 binding 是否可用
    private val isBindingAvailable: Boolean get() = _binding != null

    private val gphoto2Manager = GPhoto2Manager.getInstance()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var photoAdapter: CameraPhotoAdapter

    private var configItems = listOf<ConfigItem>()

    // 广播接收器
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TetheredShootingService.ACTION_PHOTO_ADDED -> {
                    // 照片添加，刷新列表
                    loadPhotos()
                }
                TetheredShootingService.ACTION_CAMERA_DISCONNECTED -> {
                    // 相机断开
                    updateConnectionStatus(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenBottomSheetDialog)
    }

    override fun onStart() {
        super.onStart()
        // 确保 BottomSheet 展开到全屏
        dialog?.let { dialog ->
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                
                // 设置全屏高度
                sheet.layoutParams?.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                sheet.requestLayout()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetTetheredModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated 被调用")

        try {
            preferencesManager = PreferencesManager(requireContext())

            setupViews()
            registerBroadcastReceiver()
            
            // 显示加载状态
            binding.progressLoading.visibility = View.VISIBLE
            binding.recyclerViewPhotos.visibility = View.GONE
            binding.textConnectionStatus.text = "正在加载..."
            
            // 尝试初始加载
            Log.d(TAG, "开始初始加载照片和配置...")
            tryInitialLoad()
        } catch (e: Exception) {
            Log.e(TAG, "onViewCreated 异常", e)
            Toast.makeText(requireContext(), "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 尝试初始加载照片和配置
     * 成功则刷新 UI，失败则显示等待状态
     */
    private fun tryInitialLoad() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d(TAG, "尝试获取照片列表...")
                val allPhotos = withContext(Dispatchers.IO) {
                    gphoto2Manager.listPhotos()
                }
                
                val jpegPhotos = allPhotos.filter { isJpegFile(it.name) }
                Log.d(TAG, "初始加载: 获取到 ${jpegPhotos.size} 张 JPEG 照片")
                
                // 检查 binding 是否仍然可用
                if (!isBindingAvailable) return@launch
                
                if (jpegPhotos.isNotEmpty()) {
                    // 成功获取到照片，刷新 UI
                    binding.progressLoading.visibility = View.GONE
                    binding.layoutEmptyState.visibility = View.GONE
                    binding.recyclerViewPhotos.visibility = View.VISIBLE
                    photoAdapter.submitList(jpegPhotos)
                    binding.textConnectionStatus.text = "相机已连接 (${jpegPhotos.size} 张照片)"
                    
                    // 同时加载配置
                    loadCameraSettings()
                } else {
                    // 没有照片，显示等待状态
                    showWaitingState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "初始加载失败", e)
                // 检查 binding 是否仍然可用
                if (!isBindingAvailable) return@launch
                // 加载失败，显示等待状态
                showWaitingState()
            }
        }
    }
    
    /**
     * 显示等待新文件事件的状态
     */
    private fun showWaitingState() {
        if (!isBindingAvailable) return
        
        binding.progressLoading.visibility = View.GONE
        binding.recyclerViewPhotos.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.VISIBLE
        binding.textEmptyMessage.text = "相机文件系统正在初始化"
        binding.textEmptyHint.visibility = View.VISIBLE
        binding.textEmptyHint.text = "请拍摄一张照片或点击右上角刷新按钮"
        binding.textConnectionStatus.text = "相机已连接，等待文件系统就绪..."
        
        // 尝试加载配置（可能也会失败）
        loadCameraSettings()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterBroadcastReceiver()
        _binding = null
    }

    private fun setupViews() {
        // 返回按钮
        binding.buttonBack.setOnClickListener {
            dismiss()
        }

        // 导入按钮
        binding.buttonImport.setOnClickListener {
            importSelectedPhotos()
        }

        // 更多选项按钮
        binding.buttonMore.setOnClickListener {
            showMoreOptionsMenu()
        }

        // 设置照片适配器
        photoAdapter = CameraPhotoAdapter(gphoto2Manager) { selectedCount ->
            updateImportButton(selectedCount)
        }

        binding.recyclerViewPhotos.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = photoAdapter
        }

        // 更新连接状态
        updateConnectionStatus(gphoto2Manager.isCameraConnected())
    }

    private fun loadPhotos() {
        if (!isBindingAvailable) return
        
        Log.d(TAG, "loadPhotos 开始")
        binding.progressLoading.visibility = View.VISIBLE
        binding.layoutEmptyState.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d(TAG, "开始获取照片列表...")
                val allPhotos = withContext(Dispatchers.IO) {
                    gphoto2Manager.listPhotos()
                }
                
                Log.d(TAG, "获取到 ${allPhotos.size} 张照片")
                
                // 检查 binding 是否仍然可用
                if (!isBindingAvailable) return@launch
                
                // 只显示 JPG/JPEG 文件，过滤掉 RAW 文件
                val jpegPhotos = allPhotos.filter { photo ->
                    isJpegFile(photo.name)
                }

                Log.d(TAG, "过滤后 ${jpegPhotos.size} 张 JPEG 照片")

                if (jpegPhotos.isEmpty()) {
                    binding.layoutEmptyState.visibility = View.VISIBLE
                    binding.recyclerViewPhotos.visibility = View.GONE
                    // 显示提示信息
                    binding.textEmptyHint.visibility = View.VISIBLE
                } else {
                    binding.layoutEmptyState.visibility = View.GONE
                    binding.recyclerViewPhotos.visibility = View.VISIBLE
                    photoAdapter.submitList(jpegPhotos)
                }
                
                // 更新连接状态文本
                binding.textConnectionStatus.text = if (jpegPhotos.isEmpty()) {
                    "相机已连接，请拍摄照片"
                } else {
                    "相机已连接 (${jpegPhotos.size} 张照片)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载照片失败", e)
                if (!isBindingAvailable) return@launch
                binding.textConnectionStatus.text = "加载失败"
                context?.let {
                    Toast.makeText(it, "加载照片失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (isBindingAvailable) {
                    binding.progressLoading.visibility = View.GONE
                }
            }
        }
    }
    
    /**
     * 检查文件是否为 JPEG 格式
     */
    private fun isJpegFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in listOf("jpg", "jpeg")
    }

    private fun loadCameraSettings() {
        Log.d(TAG, "loadCameraSettings 开始")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d(TAG, "开始获取相机配置...")
                val settings = withContext(Dispatchers.IO) {
                    gphoto2Manager.listConfig()
                }

                Log.d(TAG, "获取到 ${settings.size} 个配置项")
                
                // 检查 binding 是否仍然可用
                if (!isBindingAvailable) return@launch
                
                configItems = settings.toList()

                if (configItems.isEmpty()) {
                    binding.textSettingsEmpty.visibility = View.VISIBLE
                } else {
                    binding.textSettingsEmpty.visibility = View.GONE
                    displayCameraSettings(configItems)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载相机设置失败", e)
                if (isBindingAvailable) {
                    binding.textSettingsEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun displayCameraSettings(settings: List<ConfigItem>) {
        if (!isBindingAvailable) return
        
        binding.chipGroupSettings.removeAllViews()

        val ctx = context ?: return
        settings.forEach { config ->
            val chip = Chip(ctx).apply {
                text = "${config.label}: ${config.currentValue}"
                isClickable = true
                isCheckable = false

                setOnClickListener {
                    showConfigDialog(config)
                }
            }

            binding.chipGroupSettings.addView(chip)
        }
    }

    private fun showConfigDialog(config: ConfigItem) {
        when (config.type) {
            ConfigItem.TYPE_RADIO, ConfigItem.TYPE_MENU -> {
                // 单选类型
                showRadioConfigDialog(config)
            }
            ConfigItem.TYPE_TEXT -> {
                // 文本类型
                showTextConfigDialog(config)
            }
            ConfigItem.TYPE_TOGGLE -> {
                // 开关类型
                toggleConfig(config)
            }
            else -> {
                Toast.makeText(requireContext(), "不支持的配置类型", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRadioConfigDialog(config: ConfigItem) {
        val choices = config.choices ?: return

        // 使用 Android 原生 AlertDialog.Builder 避免主题问题
        android.app.AlertDialog.Builder(requireActivity())
            .setTitle(config.label)
            .setItems(choices) { _, which ->
                val selectedValue = choices[which]
                setConfig(config.name, selectedValue)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTextConfigDialog(config: ConfigItem) {
        val ctx = requireActivity()
        val input = android.widget.EditText(ctx).apply {
            setText(config.currentValue)
        }

        // 使用 Android 原生 AlertDialog.Builder 避免主题问题
        android.app.AlertDialog.Builder(ctx)
            .setTitle(config.label)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val value = input.text.toString()
                setConfig(config.name, value)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleConfig(config: ConfigItem) {
        val newValue = if (config.currentValue == "1" || config.currentValue.equals("true", true)) {
            "0"
        } else {
            "1"
        }
        setConfig(config.name, newValue)
    }

    private fun setConfig(name: String, value: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    gphoto2Manager.setConfig(name, value)
                }

                context?.let { ctx ->
                    if (result == GPhoto2Manager.GP_OK) {
                        Toast.makeText(ctx, "设置成功", Toast.LENGTH_SHORT).show()
                        // 刷新设置显示
                        loadCameraSettings()
                    } else {
                        Toast.makeText(
                            ctx,
                            "设置失败: ${gphoto2Manager.getErrorString(result)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置配置失败", e)
                context?.let { ctx ->
                    Toast.makeText(ctx, "设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun importSelectedPhotos() {
        val selectedPhotos = photoAdapter.getSelectedPhotos()
        if (selectedPhotos.isEmpty()) {
            Toast.makeText(requireContext(), "请选择要导入的照片", Toast.LENGTH_SHORT).show()
            return
        }

        val inputFolderUri = preferencesManager.homeInputFolder
        if (inputFolderUri.isEmpty()) {
            Toast.makeText(requireContext(), "输入文件夹未设置", Toast.LENGTH_SHORT).show()
            return
        }

        // 禁用导入按钮，显示进度
        binding.buttonImport.isEnabled = false
        var importedCount = 0
        val totalCount = selectedPhotos.size

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = requireContext()
                val contentResolver = context.contentResolver
                val destFolderUri = android.net.Uri.parse(inputFolderUri)
                val destFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, destFolderUri)
                
                if (destFolder == null || !destFolder.canWrite()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "无法写入目标文件夹", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                selectedPhotos.forEach { photoPath ->
                    withContext(Dispatchers.IO) {
                        val fileName = photoPath.substringAfterLast('/')
                        
                        // 先下载到临时文件
                        val tempFile = File(context.cacheDir, fileName)
                        val result = gphoto2Manager.downloadPhoto(photoPath, tempFile.absolutePath)

                        if (result == GPhoto2Manager.GP_OK && tempFile.exists()) {
                            // 复制到目标文件夹
                            try {
                                val destFile = destFolder.createFile("image/jpeg", fileName)
                                if (destFile != null) {
                                    contentResolver.openOutputStream(destFile.uri)?.use { outputStream ->
                                        tempFile.inputStream().use { inputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                    importedCount++
                                    Log.d(TAG, "照片已导入: $fileName -> ${destFile.uri}")
                                }
                            } finally {
                                // 删除临时文件
                                tempFile.delete()
                            }
                        }
                    }

                    // 更新进度
                    withContext(Dispatchers.Main) {
                        if (isBindingAvailable) {
                            binding.buttonImport.text = "导入中 $importedCount/$totalCount"
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    context?.let { ctx ->
                        Toast.makeText(
                            ctx,
                            "成功导入 $importedCount 张照片",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    // 清除选择
                    if (::photoAdapter.isInitialized) {
                        photoAdapter.clearSelection()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "导入照片失败", e)
                withContext(Dispatchers.Main) {
                    context?.let { ctx ->
                        Toast.makeText(ctx, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (isBindingAvailable) {
                        binding.buttonImport.isEnabled = true
                        binding.buttonImport.text = "导入"
                    }
                }
            }
        }
    }

    private fun showMoreOptionsMenu() {
        val popupMenu = androidx.appcompat.widget.PopupMenu(requireContext(), binding.buttonMore)
        popupMenu.menu.add(0, 1, 0, "刷新")
        popupMenu.menu.add(0, 2, 1, "全选")
        popupMenu.menu.add(0, 3, 2, "取消选择")
        
        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    refreshAll()
                    true
                }
                2 -> {
                    photoAdapter.selectAll()
                    true
                }
                3 -> {
                    photoAdapter.clearSelection()
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }
    
    /**
     * 刷新照片列表和相机配置
     */
    private fun refreshAll() {
        if (!isBindingAvailable) return
        
        Log.d(TAG, "手动刷新照片列表和配置")
        
        // 显示加载状态
        binding.progressLoading.visibility = View.VISIBLE
        binding.textConnectionStatus.text = "正在刷新..."
        
        // 重新加载照片和配置
        loadPhotos()
        loadCameraSettings()
    }

    private fun updateConnectionStatus(isConnected: Boolean) {
        if (!isBindingAvailable) return
        
        binding.textConnectionStatus.text = if (isConnected) {
            "相机已连接"
        } else {
            "相机未连接"
        }
    }

    private fun updateImportButton(selectedCount: Int) {
        if (!isBindingAvailable) return
        
        binding.buttonImport.isEnabled = selectedCount > 0
        binding.buttonImport.text = if (selectedCount > 0) {
            "导入 ($selectedCount)"
        } else {
            "导入"
        }
    }

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(TetheredShootingService.ACTION_PHOTO_ADDED)
            addAction(TetheredShootingService.ACTION_CAMERA_DISCONNECTED)
        }
        requireContext().registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterBroadcastReceiver() {
        try {
            requireContext().unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败", e)
        }
    }
}
