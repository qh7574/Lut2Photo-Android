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
        return currentList.filter { it.isSelected }
    }
    
    fun toggleSelection(lutItem: LutItem) {
        lutItem.isSelected = !lutItem.isSelected
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
                
                // 只保留复选框逻辑
                checkboxSelect.isChecked = lutItem.isSelected
                
                root.setOnClickListener {
                    onItemClick(lutItem)
                }
                
                checkboxSelect.setOnCheckedChangeListener { _, isChecked ->
                    lutItem.isSelected = isChecked
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