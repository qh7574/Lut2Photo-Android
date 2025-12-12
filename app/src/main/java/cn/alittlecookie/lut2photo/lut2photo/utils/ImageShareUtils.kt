package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 图片共享工具类
 * 用于将 Bitmap 保存到临时文件并通过 FileProvider 共享
 */
object ImageShareUtils {
    
    /**
     * 将 Bitmap 保存到临时文件并返回 URI
     * @param context 上下文
     * @param bitmap 要保存的 Bitmap
     * @return FileProvider URI，失败返回 null
     */
    fun saveBitmapToTempFile(context: Context, bitmap: Bitmap): Uri? {
        return try {
            // 创建临时目录
            val cacheDir = File(context.cacheDir, "preview")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            // 创建临时文件
            val tempFile = File(cacheDir, "preview_${System.currentTimeMillis()}.jpg")
            
            // 保存 Bitmap
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            
            // 返回 FileProvider URI
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 清理旧的临时预览文件（保留最近的 5 个）
     * @param context 上下文
     */
    fun cleanOldTempFiles(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, "preview")
            if (cacheDir.exists()) {
                val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() }
                files?.drop(5)?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
