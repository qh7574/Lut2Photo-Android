package cn.alittlecookie.lut2photo.lut2photo.adapter

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.alittlecookie.lut2photo.lut2photo.databinding.ItemProcessingRecordBinding
import cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord
import cn.alittlecookie.lut2photo.lut2photo.utils.ThumbnailManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProcessingHistoryAdapter(
    private val thumbnailManager: ThumbnailManager,
    private val lifecycleScope: LifecycleCoroutineScope
) : ListAdapter<ProcessingRecord, ProcessingHistoryAdapter.HistoryViewHolder>(HistoryDiffCallback()) {
    
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
                // 加载缩略图背景
                loadThumbnailBackground(record.outputPath)
                
                // 1. 只显示文件名，不显示完整路径
                textFileName.text = getFileNameFromPath(record.fileName)

                // 修改状态显示逻辑
                val displayStatus = when {
                    record.status.contains("成功") -> "手动处理"
                    record.status.contains("处理完成") -> "@string/realtime_monitoring"
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
                val lutInfo =
                    if (record.strength > 0f || record.quality > 0 || record.ditherType.isNotEmpty() || record.lut2FileName.isNotEmpty()) {
                    // 新格式：包含处理参数，格式为 "lut|强度|质量|抖动类型"
                    val lutName = record.lutFileName.ifEmpty { "未知" }
                        val lut2Name =
                            if (record.lut2FileName.isNotEmpty()) record.lut2FileName else null
                    
                    // 强度显示为百分比，修复格式化问题
                    val strengthText = "${(record.strength * 100).toInt()}%"
                        val lut2StrengthText =
                            if (record.lut2Strength > 0f) "${(record.lut2Strength * 100).toInt()}%" else null
                    val qualityText = record.quality.toString()
                    val ditherText = when (record.ditherType.lowercase()) {
                        "floyd" -> "Floyd"
                        "random" -> "Random"
                        "none", "" -> "None"
                        else -> record.ditherType
                    }

                        // 构建显示文本
                        buildString {
                            append("$lutName | $strengthText")
                            if (lut2Name != null && lut2StrengthText != null) {
                                append(" + $lut2Name | $lut2StrengthText") // 显示第二个LUT
                            }
                            append("\n$qualityText | $ditherText")
                        }
                } else {
                    // 旧格式：只显示LUT名称
                        val lutName = record.lutFileName.ifEmpty { "未知" }
                        val lut2Name =
                            if (record.lut2FileName.isNotEmpty()) record.lut2FileName else null
                        if (lut2Name != null) {
                            "LUT: $lutName + $lut2Name" // 旧格式也显示第二个LUT（如果有）
                        } else {
                            "LUT: $lutName"
                        }
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

        /**
         * 加载缩略图背景
         */
        private fun loadThumbnailBackground(outputPath: String) {
            with(binding) {
                // 初始化状态：隐藏背景图片和覆盖层
                imageThumbnailBackground.visibility = View.GONE
                overlayBackground.visibility = View.GONE
                imageThumbnailBackground.setImageBitmap(null)

                // 检查输出路径是否有效
                if (outputPath.isBlank()) {
                    android.util.Log.d("ProcessingHistoryAdapter", "输出路径为空，跳过缩略图加载")
                    return
                }

                android.util.Log.d("ProcessingHistoryAdapter", "开始加载缩略图: $outputPath")

                // 异步加载缩略图
                lifecycleScope.launch {
                    try {
                        val thumbnail = thumbnailManager.getThumbnail(outputPath)
                        if (thumbnail != null && !thumbnail.isRecycled) {
                            android.util.Log.d(
                                "ProcessingHistoryAdapter",
                                "缩略图加载成功: $outputPath, 尺寸: ${thumbnail.width}x${thumbnail.height}"
                            )
                            // 显示缩略图背景
                            imageThumbnailBackground.setImageBitmap(thumbnail)
                            imageThumbnailBackground.visibility = View.VISIBLE
                            overlayBackground.visibility = View.VISIBLE
                        } else {
                            android.util.Log.w(
                                "ProcessingHistoryAdapter",
                                "缩略图为null或已回收: $outputPath"
                            )
                            // 保持隐藏状态
                            imageThumbnailBackground.visibility = View.GONE
                            overlayBackground.visibility = View.GONE
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(
                            "ProcessingHistoryAdapter",
                            "加载缩略图失败: $outputPath",
                            e
                        )
                        // 加载失败，保持隐藏状态
                        imageThumbnailBackground.visibility = View.GONE
                        overlayBackground.visibility = View.GONE
                    }
                }
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