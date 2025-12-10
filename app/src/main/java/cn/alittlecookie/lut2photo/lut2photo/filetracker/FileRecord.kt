package cn.alittlecookie.lut2photo.lut2photo.filetracker

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * 文件记录数据类
 * 用于表示监控目录中的图片文件信息
 */
data class FileRecord(
    val fileName: String,           // 文件名（不含路径）
    val uri: Uri,                   // 完整URI
    val lastModified: Long,         // 最后修改时间
    val fileSize: Long,             // 文件大小
    val isIncremental: Boolean      // 是否为增量文件（冷启动后新增）
) {
    companion object {
        /**
         * 从DocumentFile创建FileRecord
         */
        fun fromDocumentFile(documentFile: DocumentFile, isIncremental: Boolean = false): FileRecord? {
            val name = documentFile.name ?: return null
            return FileRecord(
                fileName = name,
                uri = documentFile.uri,
                lastModified = documentFile.lastModified(),
                fileSize = documentFile.length(),
                isIncremental = isIncremental
            )
        }
    }
}
