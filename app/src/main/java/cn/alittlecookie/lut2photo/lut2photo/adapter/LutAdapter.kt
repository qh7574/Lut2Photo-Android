package cn.alittlecookie.lut2photo.lut2photo.adapter

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.alittlecookie.lut2photo.lut2photo.databinding.ItemLutBinding
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import java.text.SimpleDateFormat
import java.util.*

class LutAdapter(
    private val onItemClick: (LutItem) -> Unit,
    private val onDeleteClick: (LutItem) -> Unit
) : ListAdapter<LutItem, LutAdapter.LutViewHolder>(LutDiffCallback()) {
    
    // 移除单选相关的属性
    // private var selectedLutId: String? = null
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LutViewHolder {
        val binding = ItemLutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LutViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: LutViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    // 移除单选相关的方法
    // fun setSelectedLut(lutItem: LutItem) {
    //     selectedLutId = lutItem.id
    //     notifyDataSetChanged()
    // }
    // fun getSelectedLut(): LutItem? {
    //     return currentList.find { it.id == selectedLutId }
    // }
    
    fun getSelectedLuts(): List<LutItem> {
        val selected = currentList.filter { it.isSelected }
        android.util.Log.d("LutAdapter", "获取选中的 LUT: ${selected.size} 个")
        selected.forEach { lut ->
            android.util.Log.d("LutAdapter", "  - ${lut.name}, vltFileName=${lut.vltFileName}")
        }
        return selected
    }
    
    fun toggleSelection(lutItem: LutItem) {
        val oldState = lutItem.isSelected
        lutItem.isSelected = !lutItem.isSelected
        android.util.Log.d("LutAdapter", "切换选择状态: ${lutItem.name}, $oldState -> ${lutItem.isSelected}")
        android.util.Log.d("LutAdapter", "  - vltFileName: ${lutItem.vltFileName}")
        android.util.Log.d("LutAdapter", "  - uploadName: ${lutItem.uploadName}")
        notifyDataSetChanged()
    }
    
    inner class LutViewHolder(private val binding: ItemLutBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(lutItem: LutItem) {
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
                
                // 先移除旧的监听器，避免重复触发
                checkboxSelect.setOnCheckedChangeListener(null)
                // 设置复选框状态
                checkboxSelect.isChecked = lutItem.isSelected
                
                // 点击整个项目切换选择状态
                root.setOnClickListener {
                    onItemClick(lutItem)
                }
                
                // 点击复选框也切换选择状态
                checkboxSelect.setOnCheckedChangeListener { _, isChecked ->
                    if (lutItem.isSelected != isChecked) {
                        onItemClick(lutItem)
                    }
                }
                
                buttonDelete.setOnClickListener {
                    onDeleteClick(lutItem)
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