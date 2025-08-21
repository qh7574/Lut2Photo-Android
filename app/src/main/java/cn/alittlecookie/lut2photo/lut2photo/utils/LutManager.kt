package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LutManager(private val context: Context) {
    
    // 修改存储位置到 /android_data/
    private val lutDirectory: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "android_data/luts")
        android.util.Log.d("LutManager", "LUT目录路径: ${dir.absolutePath}")
        android.util.Log.d("LutManager", "外部文件目录: ${context.getExternalFilesDir(null)?.absolutePath}")
        
        if (!dir.exists()) {
            val created = dir.mkdirs()
            android.util.Log.d("LutManager", "创建LUT目录结果: $created")
            android.util.Log.d("LutManager", "目录是否存在: ${dir.exists()}")
            android.util.Log.d("LutManager", "目录是否可写: ${dir.canWrite()}")
        } else {
            android.util.Log.d("LutManager", "LUT目录已存在")
        }
        
        // 列出目录中的所有文件
        dir.listFiles()?.let { files ->
            android.util.Log.d("LutManager", "目录中的文件数量: ${files.size}")
            files.forEach { file ->
                android.util.Log.d("LutManager", "文件: ${file.name}, 大小: ${file.length()}")
            }
        } ?: android.util.Log.d("LutManager", "无法列出目录文件")
        
        dir
    }
    
    suspend fun getAllLuts(): List<LutItem> = withContext(Dispatchers.IO) {
        lutDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(".cube", ignoreCase = true)
        }?.map { file ->
            LutItem(
                id = file.name,
                name = file.nameWithoutExtension,
                filePath = file.name, // 只存储文件名，不存储完整路径
                size = file.length(),
                lastModified = file.lastModified()
            )
        }?.sortedBy { it.name } ?: emptyList()
    }
    
    suspend fun importLut(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri) ?: "imported_${System.currentTimeMillis()}.cube"
            val targetFile = File(lutDirectory, fileName)
            
            // 如果文件已存在，添加时间戳
            val finalFile = if (targetFile.exists()) {
                val nameWithoutExt = targetFile.nameWithoutExtension
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                File(lutDirectory, "${nameWithoutExt}_${timestamp}.cube")
            } else {
                targetFile
            }
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(finalFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // 验证LUT文件格式
            validateLutFile(finalFile)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun exportLuts(lutItems: List<LutItem>, targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetDir = DocumentFile.fromTreeUri(context, targetUri) ?: return@withContext false
            
            lutItems.forEach { lutItem ->
                // 修改：使用lutDirectory + fileName构建完整路径
                val sourceFile = File(lutDirectory, lutItem.filePath)
                if (sourceFile.exists()) {
                    val targetFile = targetDir.createFile("application/octet-stream", sourceFile.name)
                    targetFile?.let { docFile ->
                        context.contentResolver.openOutputStream(docFile.uri)?.use { output ->
                            FileInputStream(sourceFile).use { input ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    suspend fun deleteLut(lutItem: LutItem): Boolean = withContext(Dispatchers.IO) {
        try {
            // 修改：使用lutDirectory + fileName构建完整路径
            val file = File(lutDirectory, lutItem.filePath)
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 新增：获取LUT文件的完整路径
    fun getLutFilePath(lutItem: LutItem): String {
        val fullPath = File(lutDirectory, lutItem.filePath).absolutePath
        android.util.Log.d("LutManager", "获取LUT文件路径: ${lutItem.filePath} -> $fullPath")
        android.util.Log.d("LutManager", "文件是否存在: ${File(fullPath).exists()}")
        return fullPath
    }

    private fun getFileName(uri: Uri): String? {
        return DocumentFile.fromSingleUri(context, uri)?.name
    }
    
    private fun validateLutFile(file: File): Boolean {
        return try {
            file.readLines().any { line ->
                line.trim().startsWith("LUT_3D_SIZE")
            }
        } catch (_: Exception) {
            false
        }
    }
}