package cn.alittlecookie.lut2photo.lut2photo.model

import android.graphics.Bitmap
import android.net.Uri

data class ImageItem(
    val uri: Uri,
    val previewBitmap: Bitmap // 用于预览的小图
)