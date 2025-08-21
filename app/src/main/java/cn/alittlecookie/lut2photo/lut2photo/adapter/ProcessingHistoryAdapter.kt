package cn.alittlecookie.lut2photo.lut2photo.adapter

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.alittlecookie.lut2photo.lut2photo.databinding.ItemProcessingRecordBinding
import cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProcessingHistoryAdapter : ListAdapter<ProcessingRecord, ProcessingHistoryAdapter.HistoryViewHolder>(HistoryDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemProcessingRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class HistoryViewHolder(private val binding: ItemProcessingRecordBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(record: ProcessingRecord) {
            with(binding) {
                // 1. 只显示文件名，不显示完整路径
                textFileName.text = getFileNameFromPath(record.fileName)

                // 修改状态显示逻辑
                val displayStatus = when {
                    record.status.contains("成功") -> "手动处理"
                    record.status.contains("处理完成") -> "文件夹监控"
                    record.status.contains("失败") -> record.status // 保持原有的失败状态显示
                    else -> record.status
                }
                textStatus.text = displayStatus

                // 统一设置状态颜色为"成功"的样式（绿色）
                when {
                    displayStatus == "手动处理" || displayStatus == "文件夹监控" -> {
                        textStatus.setTextColor(ContextCompat.getColor(root.context, android.R.color.holo_green_dark))
                    }
                    record.status.contains("失败") -> {
                        textStatus.setTextColor(ContextCompat.getColor(root.context, android.R.color.holo_red_dark))
                    }
                    else -> {
                        textStatus.setTextColor(ContextCompat.getColor(root.context, android.R.color.black))
                    }
                }
                
                textTimestamp.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(record.timestamp))
                
                // 2. 修改显示格式为 "lut|强度|质量|抖动类型"
                val lutInfo = if (record.strength > 0f || record.quality > 0 || record.ditherType.isNotEmpty()) {
                    // 新格式：包含处理参数，格式为 "lut|强度|质量|抖动类型"
                    val lutName = record.lutFileName.ifEmpty { "未知" }
                    // 强度显示为百分比，修复格式化问题
                    val strengthText = "${(record.strength * 100).toInt()}%"
                    val qualityText = record.quality.toString()
                    val ditherText = when (record.ditherType.lowercase()) {
                        "floyd" -> "Floyd"
                        "random" -> "Random"
                        "none", "" -> "None"
                        else -> record.ditherType
                    }
                    "$lutName | $strengthText | $qualityText | $ditherText"
                } else {
                    // 旧格式：只显示LUT名称
                    "LUT: ${record.lutFileName.ifEmpty { "未知" }}"
                }
                
                // 简化路径显示
                val inputDisplayPath = getSimplifiedPath(record.inputPath)
                val outputDisplayPath = getSimplifiedPath(record.outputPath)
                
                textPaths.text = "$lutInfo\n输入: $inputDisplayPath\n输出: $outputDisplayPath"
                
                // 添加点击跳转到系统相册功能
                root.setOnClickListener {
                    openImageInGallery(record.outputPath)
                }
            }
        }
        
        // 新增：从路径或URI中提取文件名的方法
        private fun getFileNameFromPath(path: String): String {
            return try {
                when {
                    path.startsWith("content://") -> {
                        // 对于content URI，尝试解码并获取文件名
                        val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
                        when {
                            decodedPath.contains("/document/") -> {
                                val documentId = decodedPath.substringAfterLast("/document/")
                                if (documentId.contains(":")) {
                                    documentId.substringAfterLast(":")
                                } else {
                                    documentId
                                }
                            }
                            decodedPath.contains("primary:") -> {
                                decodedPath.substringAfterLast("primary:")
                            }
                            else -> decodedPath.substringAfterLast("/").substringAfterLast("\\")
                        }
                    }
                    path.contains("/") || path.contains("\\") -> {
                        // 如果包含路径分隔符，提取文件名
                        path.substringAfterLast("/").substringAfterLast("\\")
                    }
                    else -> {
                        // 如果已经是文件名，直接返回
                        path
                    }
                }
            } catch (_: Exception) {
                // 如果解析失败，返回原始字符串
                path
            }
        }

        private fun getSimplifiedPath(path: String): String {
            return try {
                when {
                    path.startsWith("content://") -> {
                        // 对于content URI，尝试解码并获取文件名
                        val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
                        when {
                            decodedPath.contains("/document/") -> {
                                // DocumentFile URI格式
                                val documentId = decodedPath.substringAfterLast("/document/")
                                if (documentId.contains(":")) {
                                    documentId.substringAfterLast(":")
                                } else {
                                    documentId
                                }
                            }
                            decodedPath.contains("primary:") -> {
                                decodedPath.substringAfterLast("primary:")
                            }
                            else -> decodedPath.substringAfterLast("/").substringAfterLast("\\")
                        }
                    }
                    path.startsWith("/") -> {
                        // 绝对路径，只显示文件名
                        path.substringAfterLast("/")
                    }
                    else -> path.substringAfterLast("/").substringAfterLast("\\")
                }
            } catch (_: Exception) {
                // 如果解码失败，返回文件名
                path.substringAfterLast("/").substringAfterLast("\\")
            }
        }
        
        private fun openImageInGallery(outputPath: String) {
            try {
                val context = binding.root.context
                
                // 尝试不同的方式打开图片
                val intent = when {
                    // 如果是content URI
                    outputPath.startsWith("content://") -> {
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(outputPath.toUri(), "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    // 如果是文件路径
                    outputPath.startsWith("/") -> {
                        val file = File(outputPath)
                        if (file.exists()) {
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.fromFile(file), "image/*")
                            }
                        } else {
                            null
                        }
                    }
                    // 其他情况，尝试作为URI解析
                    else -> {
                        try {
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(outputPath.toUri(), "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                
                if (intent != null) {
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // 如果无法打开特定图片，尝试打开相册应用
                        val galleryIntent = Intent(Intent.ACTION_VIEW).apply {
                            type = "image/*"
                        }
                        context.startActivity(galleryIntent)
                    }
                } else {
                    // 如果都失败了，打开相册应用
                    val galleryIntent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                    }
                    context.startActivity(galleryIntent)
                }

            } catch (_: Exception) {
                // 最后的备选方案
                try {
                    val galleryIntent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                    }
                    binding.root.context.startActivity(galleryIntent)
                } catch (_: Exception) {
                    // 忽略错误
                }
            }
        }
    }

    private class HistoryDiffCallback : DiffUtil.ItemCallback<ProcessingRecord>() {
        override fun areItemsTheSame(oldItem: ProcessingRecord, newItem: ProcessingRecord): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.fileName == newItem.fileName
        }
        
        override fun areContentsTheSame(oldItem: ProcessingRecord, newItem: ProcessingRecord): Boolean {
            return oldItem == newItem
        }
    }
}