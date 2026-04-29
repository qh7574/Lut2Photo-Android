package cn.alittlecookie.lut2photo.lut2photo.adapter

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.alittlecookie.lut2photo.lut2photo.databinding.ItemLutBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LutAdapter(
    private val selectionCallback: SelectionCallback? = null
) : ListAdapter<LutItem, LutAdapter.LutViewHolder>(LutDiffCallback()) {

    // 多选状态
    var isSelectionMode = false
        private set
    private val selectedPositions = mutableSetOf<Int>()

    interface SelectionCallback {
        fun onSelectionModeChanged(enabled: Boolean)
        fun onSelectionCountChanged(count: Int)
        fun onItemClick(lutItem: LutItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LutViewHolder {
        val binding = ItemLutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LutViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LutViewHolder, position: Int) {
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

    // 获取选中的LUT
    fun getSelectedLuts(): List<LutItem> {
        return selectedPositions.mapNotNull { position ->
            if (position < itemCount) getItem(position) else null
        }
    }

    // 获取选中数量
    fun getSelectedCount(): Int = selectedPositions.size

    inner class LutViewHolder(private val binding: ItemLutBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(lutItem: LutItem, position: Int) {
            binding.apply {
                textLutName.text = lutItem.name
                textLutSize.text = Formatter.formatFileSize(root.context, lutItem.size)
                textLutDate.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(lutItem.lastModified))

                // 显示 VLT 上传状态
                if (lutItem.vltFileName != null && lutItem.uploadName != null) {
                    textVltStatus.text = "VLT：${lutItem.uploadName}"
                    textVltStatus.setTextColor(root.context.getColor(android.R.color.holo_green_dark))
                } else {
                    textVltStatus.text = "无 VLT"
                    textVltStatus.setTextColor(root.context.getColor(android.R.color.darker_gray))
                }

                // 选中状态指示器
                imageSelectionIndicator.visibility =
                    if (isSelectionMode && selectedPositions.contains(position)) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                // 点击事件
                root.setOnClickListener {
                    if (isSelectionMode) {
                        toggleSelection(position)
                    } else {
                        selectionCallback?.onItemClick(lutItem)
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
    }

    private class LutDiffCallback : DiffUtil.ItemCallback<LutItem>() {
        override fun areItemsTheSame(oldItem: LutItem, newItem: LutItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LutItem, newItem: LutItem): Boolean {
            return oldItem == newItem
        }
    }
}