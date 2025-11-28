package cn.alittlecookie.lut2photo.lut2photo.adapter

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.core.GPhoto2Manager
import cn.alittlecookie.lut2photo.lut2photo.model.PhotoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 相机照片适配器
 */
class CameraPhotoAdapter(
    private val gphoto2Manager: GPhoto2Manager,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<PhotoInfo, CameraPhotoAdapter.PhotoViewHolder>(PhotoDiffCallback()) {

    private val selectedPhotos = mutableSetOf<String>()

    inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageThumbnail: ImageView = itemView.findViewById(R.id.image_thumbnail)
        val checkboxSelected: CheckBox = itemView.findViewById(R.id.checkbox_selected)
        val textPhotoName: TextView = itemView.findViewById(R.id.text_photo_name)

        init {
            // 点击整个项目切换选中状态
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val photo = getItem(position)
                    toggleSelection(photo.path)
                    checkboxSelected.isChecked = isSelected(photo.path)
                }
            }

            // 点击复选框也切换选中状态
            checkboxSelected.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val photo = getItem(position)
                    toggleSelection(photo.path)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_camera_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = getItem(position)

        // 设置照片名称
        holder.textPhotoName.text = photo.name

        // 设置选中状态
        holder.checkboxSelected.isChecked = isSelected(photo.path)

        // 异步加载缩略图
        loadThumbnail(holder, photo)
    }

    private fun loadThumbnail(holder: PhotoViewHolder, photo: PhotoInfo) {
        // 显示占位图
        holder.imageThumbnail.setImageResource(R.drawable.outline_photo_24)

        // 在后台线程加载缩略图
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val thumbnailData = gphoto2Manager.getThumbnail(photo.path)

                if (thumbnailData != null && thumbnailData.isNotEmpty()) {
                    val bitmap = BitmapFactory.decodeByteArray(
                        thumbnailData, 0, thumbnailData.size
                    )

                    withContext(Dispatchers.Main) {
                        holder.imageThumbnail.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun toggleSelection(photoPath: String) {
        if (selectedPhotos.contains(photoPath)) {
            selectedPhotos.remove(photoPath)
        } else {
            selectedPhotos.add(photoPath)
        }
        onSelectionChanged(selectedPhotos.size)
    }

    private fun isSelected(photoPath: String): Boolean {
        return selectedPhotos.contains(photoPath)
    }

    fun getSelectedPhotos(): List<String> {
        return selectedPhotos.toList()
    }

    fun clearSelection() {
        selectedPhotos.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAll() {
        currentList.forEach { photo ->
            selectedPhotos.add(photo.path)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPhotos.size)
    }

    class PhotoDiffCallback : DiffUtil.ItemCallback<PhotoInfo>() {
        override fun areItemsTheSame(oldItem: PhotoInfo, newItem: PhotoInfo): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: PhotoInfo, newItem: PhotoInfo): Boolean {
            return oldItem == newItem
        }
    }
}
