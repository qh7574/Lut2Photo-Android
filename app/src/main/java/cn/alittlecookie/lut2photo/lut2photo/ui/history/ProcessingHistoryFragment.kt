package cn.alittlecookie.lut2photo.lut2photo.ui.history

import android.annotation.SuppressLint
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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.alittlecookie.lut2photo.lut2photo.adapter.ProcessingHistoryAdapter
import cn.alittlecookie.lut2photo.lut2photo.databinding.FragmentProcessingHistoryBinding
import cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord
import cn.alittlecookie.lut2photo.lut2photo.utils.ThumbnailManager

class ProcessingHistoryFragment : Fragment() {
    
    private var _binding: FragmentProcessingHistoryBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var historyAdapter: ProcessingHistoryAdapter
    private lateinit var thumbnailManager: ThumbnailManager
    
    private val processingUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "cn.alittlecookie.lut2photo.PROCESSING_UPDATE") {
                // 重新加载处理历史
                loadProcessingHistory()
            }
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProcessingHistoryBinding.inflate(inflater, container, false)

        // 初始化ThumbnailManager
        thumbnailManager = ThumbnailManager(requireContext())
        
        setupRecyclerView()
        setupViews()
        loadProcessingHistory()
        
        return binding.root
    }
    
    override fun onStart() {
        super.onStart()
        // 在onStart中注册广播接收器，确保Fragment可见时能接收更新
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
        
        // 重新加载历史记录
        loadProcessingHistory()
    }
    
    override fun onStop() {
        super.onStop()
        // 在onStop中注销广播接收器，而不是onPause
        try {
            requireContext().unregisterReceiver(processingUpdateReceiver)
        } catch (_: Exception) {
            // 忽略取消注册时的异常
        }
    }
    
    // 移除原来的onResume和onPause方法中的注册/注销逻辑
    override fun onPause() {
        super.onPause()
        // 取消注册广播接收器
        try {
            requireContext().unregisterReceiver(processingUpdateReceiver)
        } catch (_: Exception) {
            // 忽略取消注册时的异常
        }
    }
    
    private fun setupViews() {
        // 添加清空记录按钮的点击事件
        binding.buttonClearHistory.setOnClickListener {
            clearHistory()
        }
    }
    
    private fun setupRecyclerView() {
        historyAdapter = ProcessingHistoryAdapter(thumbnailManager, lifecycleScope)
        binding.recyclerViewHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
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
                        // 最新格式：timestamp|fileName|inputPath|outputPath|status|lutFileName|lut2FileName|strength|lut2Strength|quality|ditherType
                        ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = parts[5],
                            lut2FileName = parts[6], // 第二个LUT文件名
                            strength = parts[7].toFloatOrNull() ?: 0f,
                            lut2Strength = parts[8].toFloatOrNull() ?: 0f, // 第二个LUT强度
                            quality = parts[9].toIntOrNull() ?: 0,
                            ditherType = parts[10]
                        )
                    }
                    parts.size >= 9 -> {
                        // 旧格式（无第二个LUT）：timestamp|fileName|inputPath|outputPath|status|lutFileName|strength|quality|ditherType
                        ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = parts[5],
                            lut2FileName = "", // 旧格式无第二个LUT
                            strength = parts[6].toFloatOrNull() ?: 0f,
                            lut2Strength = 0f, // 旧格式无第二个LUT强度
                            quality = parts[7].toIntOrNull() ?: 0,
                            ditherType = parts[8]
                        )
                    }
                    parts.size >= 6 -> {
                        // 更旧格式：timestamp|fileName|inputPath|outputPath|status|lutFileName
                        ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = parts[5],
                            lut2FileName = "", // 旧格式无第二个LUT
                            strength = 0f,
                            lut2Strength = 0f, // 旧格式无第二个LUT强度
                            quality = 0,
                            ditherType = ""
                        )
                    }
                    parts.size >= 5 -> {
                        // 更旧格式：timestamp|fileName|inputPath|outputPath|status
                        ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = "未知",
                            lut2FileName = "", // 旧格式无第二个LUT
                            strength = 0f,
                            lut2Strength = 0f, // 旧格式无第二个LUT强度
                            quality = 0,
                            ditherType = ""
                        )
                    }
                    parts.size >= 4 -> {
                        // 最旧格式：timestamp|fileName|inputPath|outputPath
                        ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = "处理完成",
                            lutFileName = "未知",
                            lut2FileName = "", // 旧格式无第二个LUT
                            strength = 0f,
                            lut2Strength = 0f, // 旧格式无第二个LUT强度
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
    }
    
    private fun clearHistory() {
        val prefs = requireContext().getSharedPreferences("processing_history", Context.MODE_PRIVATE)
        prefs.edit { remove("records") }

        // 清空缩略图缓存
        thumbnailManager.clearAllCache()
        
        historyAdapter.submitList(emptyList())
        binding.textHistoryCount.text = "共 0 条记录"

        Toast.makeText(requireContext(), "历史记录和缓存已清空", Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}