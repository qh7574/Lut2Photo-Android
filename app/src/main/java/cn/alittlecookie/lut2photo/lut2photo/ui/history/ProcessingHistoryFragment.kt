package cn.alittlecookie.lut2photo.lut2photo.ui.history

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.alittlecookie.lut2photo.lut2photo.adapter.ProcessingHistoryAdapter
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentProcessingHistoryBinding
import cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord
import cn.alittlecookie.lut2photo.lut2photo.ui.FullscreenImageActivity
import cn.alittlecookie.lut2photo.lut2photo.utils.ThumbnailManager
import java.io.File

class ProcessingHistoryFragment : Fragment(), ProcessingHistoryAdapter.SelectionCallback {

    private var _binding: FragmentProcessingHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var historyAdapter: ProcessingHistoryAdapter
    private lateinit var thumbnailManager: ThumbnailManager

    // 防抖处理：避免短时间内多次刷新
    private var lastRefreshTime = 0L
    private val refreshDebounceMs = 500L // 500ms 防抖间隔
    private var pendingRefresh: Runnable? = null

    private val processingUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "cn.alittlecookie.lut2photo.PROCESSING_UPDATE") {
                scheduleRefresh()
            }
        }
    }

    // 返回键处理
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (historyAdapter.isSelectionMode) {
                historyAdapter.exitSelectionMode()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProcessingHistoryBinding.inflate(inflater, container, false)

        thumbnailManager = ThumbnailManager(requireContext())

        setupRecyclerView()
        setupViews()
        setupSelectionToolbar()
        loadProcessingHistory()

        // 注册返回键回调
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        
        // 注册广播接收器
        val filter = IntentFilter("cn.alittlecookie.lut2photo.PROCESSING_UPDATE")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                processingUpdateReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            ContextCompat.registerReceiver(
                requireContext(),
                processingUpdateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }
        
        // 页面恢复时主动刷新一次,确保显示最新数据
        loadProcessingHistory()
    }

    override fun onPause() {
        super.onPause()
        
        // 注销广播接收器
        try {
            requireContext().unregisterReceiver(processingUpdateReceiver)
        } catch (e: IllegalArgumentException) {
            // 接收器未注册,忽略异常
            Log.d("ProcessingHistoryFragment", "广播接收器未注册或已注销")
        }
        
        // 取消待处理的刷新任务
        pendingRefresh?.let { binding.root.removeCallbacks(it) }
        pendingRefresh = null
    }

    private fun setupViews() {
        binding.buttonClearHistory.setOnClickListener {
            clearHistory()
        }
    }

    private fun setupRecyclerView() {
        historyAdapter = ProcessingHistoryAdapter(thumbnailManager, lifecycleScope, this)
        binding.recyclerViewHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }
    }

    private fun setupSelectionToolbar() {
        with(binding) {
            // 返回按钮 - 退出多选模式
            buttonExitSelection.setOnClickListener {
                historyAdapter.exitSelectionMode()
            }

            // 全选按钮
            buttonSelectAll.setOnClickListener {
                historyAdapter.selectAll()
            }

            // 取消选择按钮
            buttonDeselectAll.setOnClickListener {
                historyAdapter.deselectAll()
            }

            // 分享按钮
            buttonShare.setOnClickListener {
                shareSelectedImages()
            }
        }
    }

    // SelectionCallback 实现
    override fun onSelectionModeChanged(enabled: Boolean) {
        binding.selectionToolbar.visibility = if (enabled) View.VISIBLE else View.GONE
        backPressedCallback.isEnabled = enabled

        // 更新标题
        binding.textTitle.text = if (enabled) "选择图片" else "处理历史"
    }

    override fun onSelectionCountChanged(count: Int) {
        binding.buttonShare.text = if (count > 0) "分享($count)" else "分享"
        binding.buttonShare.isEnabled = count > 0
    }

    override fun onItemClick(record: ProcessingRecord) {
        // 使用 ZoomImage 打开图片预览
        openImagePreview(record.outputPath)
    }

    private fun openImagePreview(outputPath: String) {
        try {
            val intent = Intent(requireContext(), FullscreenImageActivity::class.java)
            intent.putExtra(FullscreenImageActivity.EXTRA_IMAGE_URI, outputPath)
            intent.putExtra(FullscreenImageActivity.EXTRA_IS_PROCESSED_IMAGE, true)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("ProcessingHistoryFragment", "打开图片预览失败", e)
            Toast.makeText(requireContext(), "无法打开图片预览", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareSelectedImages() {
        val selectedRecords = historyAdapter.getSelectedRecords()
        if (selectedRecords.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择要分享的图片", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uris = ArrayList<Uri>()

            for (record in selectedRecords) {
                val uri = getShareableUri(record.outputPath)
                if (uri != null) {
                    uris.add(uri)
                }
            }

            if (uris.isEmpty()) {
                Toast.makeText(requireContext(), "无法获取图片文件", Toast.LENGTH_SHORT).show()
                return
            }

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "分享图片"))

            // 分享后退出多选模式
            historyAdapter.exitSelectionMode()

        } catch (e: Exception) {
            Log.e("ProcessingHistoryFragment", "分享图片失败", e)
            Toast.makeText(requireContext(), "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getShareableUri(outputPath: String): Uri? {
        return try {
            when {
                outputPath.startsWith("content://") -> {
                    // 已经是 content URI，检查是否可访问
                    val uri = outputPath.toUri()
                    val docFile = DocumentFile.fromSingleUri(requireContext(), uri)
                    if (docFile?.exists() == true) uri else null
                }
                outputPath.startsWith("/") -> {
                    // 文件路径，转换为 FileProvider URI
                    val file = File(outputPath)
                    if (file.exists()) {
                        FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.fileprovider",
                            file
                        )
                    } else null
                }
                else -> {
                    // 尝试作为 URI 解析
                    outputPath.toUri()
                }
            }
        } catch (e: Exception) {
            Log.e("ProcessingHistoryFragment", "获取分享URI失败: $outputPath", e)
            null
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadProcessingHistory() {
        val prefs = requireContext().getSharedPreferences("processing_history", Context.MODE_PRIVATE)
        val records = prefs.getStringSet("records", emptySet()) ?: emptySet()

        val processingRecords = records.mapNotNull { recordString ->
            try {
                val parts = recordString.split("|")
                when {
                    parts.size >= 11 -> {
                        if (parts[4] == "SKIPPED") null
                        else ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = parts[5],
                            lut2FileName = parts[6],
                            strength = parts[7].toFloatOrNull() ?: 0f,
                            lut2Strength = parts[8].toFloatOrNull() ?: 0f,
                            quality = parts[9].toIntOrNull() ?: 0,
                            ditherType = parts[10]
                        )
                    }
                    parts.size >= 9 -> {
                        if (parts[4] == "SKIPPED") null
                        else ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = parts[5],
                            lut2FileName = "",
                            strength = parts[6].toFloatOrNull() ?: 0f,
                            lut2Strength = 0f,
                            quality = parts[7].toIntOrNull() ?: 0,
                            ditherType = parts[8]
                        )
                    }
                    parts.size >= 6 -> {
                        if (parts[4] == "SKIPPED") null
                        else ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = parts[5],
                            lut2FileName = "",
                            strength = 0f,
                            lut2Strength = 0f,
                            quality = 0,
                            ditherType = ""
                        )
                    }
                    parts.size >= 5 -> {
                        if (parts[4] == "SKIPPED") null
                        else ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = "未知",
                            lut2FileName = "",
                            strength = 0f,
                            lut2Strength = 0f,
                            quality = 0,
                            ditherType = ""
                        )
                    }
                    parts.size >= 4 -> {
                        ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = "处理完成",
                            lutFileName = "未知",
                            lut2FileName = "",
                            strength = 0f,
                            lut2Strength = 0f,
                            quality = 0,
                            ditherType = ""
                        )
                    }
                    else -> null
                }
            } catch (e: Exception) {
                Log.w("ProcessingHistoryFragment", "解析历史记录失败: $recordString", e)
                null
            }
        }.sortedByDescending { it.timestamp }

        historyAdapter.submitList(processingRecords)
        binding.textHistoryCount.text = "共 ${processingRecords.size} 条记录"
        
        Log.d("ProcessingHistoryFragment", "刷新历史记录: ${processingRecords.size} 条")
    }

    /**
     * 防抖刷新：避免短时间内多次刷新UI
     * 使用延迟执行机制,在500ms内的多次刷新请求会被合并为一次
     */
    private fun scheduleRefresh() {
        val currentTime = System.currentTimeMillis()
        
        // 如果距离上次刷新时间超过防抖间隔,立即刷新
        if (currentTime - lastRefreshTime >= refreshDebounceMs) {
            lastRefreshTime = currentTime
            loadProcessingHistory()
            return
        }
        
        // 否则取消之前的待处理任务,安排新的延迟刷新
        pendingRefresh?.let { binding.root.removeCallbacks(it) }
        
        val refreshTask = Runnable {
            lastRefreshTime = System.currentTimeMillis()
            loadProcessingHistory()
            pendingRefresh = null
        }
        
        pendingRefresh = refreshTask
        binding.root.postDelayed(refreshTask, refreshDebounceMs)
    }

    private fun clearHistory() {
        val prefs = requireContext().getSharedPreferences("processing_history", Context.MODE_PRIVATE)
        prefs.edit { remove("records") }

        thumbnailManager.clearAllCache()

        historyAdapter.submitList(emptyList())
        binding.textHistoryCount.text = "共 0 条记录"

        Toast.makeText(requireContext(), "历史记录和缓存已清空", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        
        // 清理待处理的刷新任务
        pendingRefresh?.let { binding.root.removeCallbacks(it) }
        pendingRefresh = null
        
        _binding = null
    }
}
