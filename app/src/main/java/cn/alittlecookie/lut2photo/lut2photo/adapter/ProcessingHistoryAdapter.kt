package cn.alittlecookie.lut2photo.lut2photo.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.alittlecookie.lut2photo.lut2photo.databinding.ItemProcessingRecordBinding
import cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord
import cn.alittlecookie.lut2photo.lut2photo.utils.ThumbnailManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProcessingHistoryAdapter(
    private val thumbnailManager: ThumbnailManager,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val selectionCallback: SelectionCallback? = null
) : ListAdapter<ProcessingRecord, ProcessingHistoryAdapter.HistoryViewHolder>(HistoryDiffCallback()) {

    // 多选状态
    var isSelectionMode = false
        private set
    private val selectedPositions = mutableSetOf<Int>()

    interface SelectionCallback {
        fun onSelectionModeChanged(enabled: Boolean)
        fun onSelectionCountChanged(count: Int)
        fun onItemClick(record: ProcessingRecord)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemProcessingRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    // 进入多选模式
    fun enterSelectionMode(initialPosition: Int) {
        if (!isSelectionMode) {
            isSelectionMode = true
            selectedPositions.clear()
            selectedPositions.add(initialPosition)
            notifyDataSetChanged()
            selectionCallback?.onSelectionModeChanged(true)
            selectionCallback?.onSelectionCountChanged(1)
        }
    }

    // 退出多选模式
    fun exitSelectionMode() {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedPositions.clear()
            notifyDataSetChanged()
            selectionCallback?.onSelectionModeChanged(false)
        }
    }

    // 切换选中状态
    fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
        selectionCallback?.onSelectionCountChanged(selectedPositions.size)
    }

    // 全选
    fun selectAll() {
        selectedPositions.clear()
        for (i in 0 until itemCount) {
            selectedPositions.add(i)
        }
        notifyDataSetChanged()
        selectionCallback?.onSelectionCountChanged(selectedPositions.size)
    }

    // 取消全选
    fun deselectAll() {
        selectedPositions.clear()
        notifyDataSetChanged()
        selectionCallback?.onSelectionCountChanged(0)
    }

    // 获取选中的记录
    fun getSelectedRecords(): List<ProcessingRecord> {
        return selectedPositions.mapNotNull { position ->
            if (position < itemCount) getItem(position) else null
        }
    }

    // 获取选中数量
    fun getSelectedCount(): Int = selectedPositions.size

    inner class HistoryViewHolder(private val binding: ItemProcessingRecordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: ProcessingRecord, position: Int) {
            with(binding) {
                // 加载缩略图背景
                loadThumbnailBackground(record.outputPath)

                // 1. 只显示文件名，不显示完整路径
                textFileName.text = getFileNameFromPath(record.fileName)

                // 修改状态显示逻辑
                val displayStatus = when {
                    record.status.contains("成功") -> "手动处理"
                    record.status.contains("处理完成") -> "实时处理"
                    record.status.contains("失败") -> record.status
                    else -> record.status
                }
                textStatus.text = displayStatus

                // 统一设置状态颜色
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
                val lutInfo = buildLutInfo(record)
                val inputDisplayPath = getSimplifiedPath(record.inputPath)
                val outputDisplayPath = getSimplifiedPath(record.outputPath)
                textPaths.text = "$lutInfo\n输入: $inputDisplayPath\n输出: $outputDisplayPath"

                // 选中状态指示器
                imageSelectionIndicator.visibility = if (isSelectionMode && selectedPositions.contains(position)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

                // 点击事件
                root.setOnClickListener {
                    if (isSelectionMode) {
                        toggleSelection(position)
                    } else {
                        selectionCallback?.onItemClick(record)
                    }
                }

                // 长按事件 - 进入多选模式
                root.setOnLongClickListener {
                    if (!isSelectionMode) {
                        enterSelectionMode(position)
                    }
                    true
                }
            }
        }

        private fun buildLutInfo(record: ProcessingRecord): String {
            return if (record.strength > 0f || record.quality > 0 || record.ditherType.isNotEmpty() || record.lut2FileName.isNotEmpty()) {
                val lutName = record.lutFileName.ifEmpty { "未知" }
                val lut2Name = if (record.lut2FileName.isNotEmpty()) record.lut2FileName else null
                val strengthText = "${(record.strength * 100).toInt()}%"
                val lut2StrengthText = if (record.lut2Strength > 0f) "${(record.lut2Strength * 100).toInt()}%" else null
                val qualityText = record.quality.toString()
                val ditherText = when (record.ditherType.lowercase()) {
                    "floyd" -> "Floyd"
                    "random" -> "Random"
                    "none", "" -> "None"
                    else -> record.ditherType
                }
                buildString {
                    append("$lutName | $strengthText")
                    if (lut2Name != null && lut2StrengthText != null) {
                        append(" + $lut2Name | $lut2StrengthText")
                    }
                    append("\n$qualityText | $ditherText")
                }
            } else {
                val lutName = record.lutFileName.ifEmpty { "未知" }
                val lut2Name = if (record.lut2FileName.isNotEmpty()) record.lut2FileName else null
                if (lut2Name != null) {
                    "LUT: $lutName + $lut2Name"
                } else {
                    "LUT: $lutName"
                }
            }
        }

        private fun getFileNameFromPath(path: String): String {
            return try {
                when {
                    path.startsWith("content://") -> {
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
                        path.substringAfterLast("/").substringAfterLast("\\")
                    }
                    else -> path
                }
            } catch (_: Exception) {
                path
            }
        }

        private fun getSimplifiedPath(path: String): String {
            return try {
                when {
                    path.startsWith("content://") -> {
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
                    path.startsWith("/") -> path.substringAfterLast("/")
                    else -> path.substringAfterLast("/").substringAfterLast("\\")
                }
            } catch (_: Exception) {
                path.substringAfterLast("/").substringAfterLast("\\")
            }
        }

        private fun loadThumbnailBackground(outputPath: String) {
            with(binding) {
                imageThumbnailBackground.visibility = View.GONE
                overlayBackground.visibility = View.GONE
                imageThumbnailBackground.setImageBitmap(null)

                if (outputPath.isBlank()) return

                lifecycleScope.launch {
                    try {
                        val thumbnail = thumbnailManager.getThumbnail(outputPath)
                        if (thumbnail != null && !thumbnail.isRecycled) {
                            imageThumbnailBackground.setImageBitmap(thumbnail)
                            imageThumbnailBackground.visibility = View.VISIBLE
                            overlayBackground.visibility = View.VISIBLE
                        }
                    } catch (_: Exception) {
                        imageThumbnailBackground.visibility = View.GONE
                        overlayBackground.visibility = View.GONE
                    }
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
