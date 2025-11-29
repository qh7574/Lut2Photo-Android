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
    
    // VLT 文件管理器
    private val vltFileManager: VltFileManager by lazy {
        VltFileManager(context)
    }
    
    suspend fun getAllLuts(): List<LutItem> = withContext(Dispatchers.IO) {
        android.util.Log.d("LutManager", "开始扫描 LUT 文件，目录: ${lutDirectory.absolutePath}")
        
        val cubeFiles = lutDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(".cube", ignoreCase = true)
        }
        
        android.util.Log.d("LutManager", "找到 ${cubeFiles?.size ?: 0} 个 CUBE 文件")
        
        cubeFiles?.map { file ->
            android.util.Log.d("LutManager", "处理 CUBE 文件: ${file.name}")
            
            // 检查是否有对应的 VLT 文件
            val vltFileName = vltFileManager.getVltName(file.name)
            android.util.Log.d("LutManager", "  - 对应的 VLT 文件: $vltFileName")
            
            val uploadName = if (vltFileName != null) {
                // 生成上传文件名（不含扩展名）
                val name = VltNameGenerator.generateUploadName(vltFileName.substringBeforeLast("."))
                android.util.Log.d("LutManager", "  - 生成的上传文件名: $name")
                name
            } else {
                android.util.Log.d("LutManager", "  - 没有对应的 VLT 文件")
                null
            }
            
            LutItem(
                id = file.name,
                name = file.nameWithoutExtension,
                filePath = file.name, // 只存储文件名，不存储完整路径
                size = file.length(),
                lastModified = file.lastModified(),
                vltFileName = vltFileName,
                uploadName = uploadName
            )
        }?.sortedBy { it.name } ?: emptyList()
    }
    
    suspend fun importLut(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val originalFileName = getFileName(uri) ?: "imported_${System.currentTimeMillis()}.cube"
            android.util.Log.d("LutManager", "开始导入LUT文件: $originalFileName")
            
            // 第一步：检测LUT信息
            val lutInfo = context.contentResolver.openInputStream(uri)?.use { input ->
                LutConverter.detectLutInfo(input, originalFileName)
            }
            
            if (lutInfo == null) {
                android.util.Log.e("LutManager", "无法检测LUT文件信息")
                return@withContext false
            }
            
            android.util.Log.d("LutManager", "检测到LUT: 尺寸=${lutInfo.size}, 格式=${lutInfo.format}")
            
            // 第二步：根据尺寸决定是否需要转换
            val needConversion = lutInfo.size != 33
            
            if (needConversion) {
                android.util.Log.d("LutManager", "需要转换LUT从 ${lutInfo.size}位 到 33位")
                
                // 解析源LUT数据
                val (sourceLut, sourceSize) = context.contentResolver.openInputStream(uri)?.use { input ->
                    LutConverter.parseLutData(input)
                } ?: run {
                    android.util.Log.e("LutManager", "无法解析LUT数据")
                    return@withContext false
                }
                
                // 转换为33位Cube格式
                val convertedContent = LutConverter.convertTo33Cube(
                    sourceLut, 
                    sourceSize, 
                    lutInfo.title ?: originalFileName.substringBeforeLast(".")
                )
                
                // 生成目标文件名（统一为.cube格式）
                // 如果是.vlt格式，去除-vlt标识（按需求文档）
                val baseFileName = originalFileName.substringBeforeLast(".")
                val isVltFormat = lutInfo.format == LutConverter.LutFormat.VLT
                
                // 目标 CUBE 文件名（不含 -vlt 后缀）
                val targetFileName = "${baseFileName}.cube"
                val targetFile = File(lutDirectory, targetFileName)
                
                // 如果文件已存在，添加时间戳
                val finalFile = if (targetFile.exists()) {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    File(lutDirectory, "${baseFileName}-${timestamp}.cube")
                } else {
                    targetFile
                }
                
                // 写入转换后的文件
                val success = LutConverter.writeCubeFile(convertedContent, finalFile)
                
                if (success) {
                    android.util.Log.d("LutManager", "LUT转换并导入成功: ${finalFile.name}")
                    
                    // 如果是 VLT 格式，保存原始 VLT 文件并建立映射
                    if (isVltFormat) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val vltFile = vltFileManager.saveVltFileFromStream(input, originalFileName)
                            if (vltFile != null) {
                                // 建立 CUBE 和 VLT 的映射关系
                                vltFileManager.addMapping(finalFile.name, originalFileName)
                                android.util.Log.d("LutManager", "VLT文件已保存并建立映射: ${finalFile.name} -> $originalFileName")
                            }
                        }
                    }
                    
                    validateLutFile(finalFile)
                } else {
                    android.util.Log.e("LutManager", "LUT转换失败")
                    false
                }
                
            } else {
                // 33位LUT，直接复制
                android.util.Log.d("LutManager", "33位LUT，直接导入")
                
                val isVltFormat = lutInfo.format == LutConverter.LutFormat.VLT
                val baseFileName = originalFileName.substringBeforeLast(".")
                
                // 目标文件名（如果是 VLT 格式，去除 .vlt 扩展名，改为 .cube）
                val targetFileName = if (isVltFormat) {
                    "${baseFileName}.cube"
                } else {
                    originalFileName
                }
                val targetFile = File(lutDirectory, targetFileName)
                
                // 如果文件已存在，添加时间戳
                val finalFile = if (targetFile.exists()) {
                    val nameWithoutExt = targetFile.nameWithoutExtension
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val extension = targetFile.extension
                    File(lutDirectory, "${nameWithoutExt}_${timestamp}.${extension}")
                } else {
                    targetFile
                }
                
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(finalFile).use { output ->
                        input.copyTo(output)
                    }
                }
                
                android.util.Log.d("LutManager", "LUT直接导入成功: ${finalFile.name}")
                
                // 如果是 VLT 格式，保存原始 VLT 文件并建立映射
                if (isVltFormat) {
                    // 重新打开流保存 VLT 文件
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val vltFile = vltFileManager.saveVltFileFromStream(input, originalFileName)
                        if (vltFile != null) {
                            // 建立 CUBE 和 VLT 的映射关系
                            vltFileManager.addMapping(finalFile.name, originalFileName)
                            android.util.Log.d("LutManager", "VLT文件已保存并建立映射: ${finalFile.name} -> $originalFileName")
                        }
                    }
                }
                
                // 验证LUT文件格式
                validateLutFile(finalFile)
            }
        } catch (e: Exception) {
            android.util.Log.e("LutManager", "导入LUT失败", e)
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
            val isValid = file.readLines().any { line ->
                line.trim().startsWith("LUT_3D_SIZE")
            }
            android.util.Log.d("LutManager", "LUT文件验证: ${file.name}, 结果: $isValid")
            isValid
        } catch (e: Exception) {
            android.util.Log.e("LutManager", "LUT文件验证失败: ${file.name}", e)
            false
        }
    }
}