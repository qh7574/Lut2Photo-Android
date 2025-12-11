package cn.alittlecookie.lut2photo.lut2photo.filetracker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * 优化的冷启动扫描器
 * 
 * 特点：
 * 1. 根据URI类型自动选择最优扫描方式（NIO或DocumentFile）
 * 2. 流式处理，避免内存峰值
 * 3. 分批处理大目录
 * 4. 与PersistentStore配合计算增量
 */
class OptimizedColdBootScanner(
    private val context: Context,
    private val persistentStore: PersistentStore,
    private val config: FileTrackerConfig,
    private val onFileCountDetected: ((Int) -> Unit)? = null  // 文件数量检测回调
) {
    companion object {
        private const val TAG = "OptimizedColdBootScanner"
    }
    
    /**
     * 冷扫描结果
     */
    data class ColdScanResult(
        val existingFiles: List<FileRecord>,      // 存量文件（上次已知）
        val incrementalFiles: List<FileRecord>,   // 增量文件（新增）
        val removedFiles: Set<String>,            // 已删除文件
        val scanDurationMs: Long                  // 扫描耗时
    )
    
    /**
     * 执行冷启动扫描
     */
    suspend fun scan(): ColdScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "开始冷启动扫描: ${config.targetFolderUri}")
        
        try {
            val targetUri = Uri.parse(config.targetFolderUri)
            
            // 检查是否需要全量扫描（超过24小时未扫描）
            val needsFullRescan = persistentStore.needsFullRescan(config.fullRescanIntervalHours)
            if (needsFullRescan) {
                Log.d(TAG, "距离上次扫描超过${config.fullRescanIntervalHours}小时，执行全量扫描")
                persistentStore.clear()
            }
            
            // 根据URI类型选择最优扫描方式
            val scanResult = if (canUseDirectFileAccess(targetUri)) {
                Log.d(TAG, "使用NIO直接文件访问")
                scanWithNIO(targetUri)
            } else {
                Log.d(TAG, "使用DocumentFile API")
                scanWithOptimizedDocumentFile(targetUri)
            }
            
            // 获取已知文件集合
            val lastKnownFiles = persistentStore.getKnownFiles()
            val currentFiles = scanResult.map { it.fileName }.toSet()
            
            Log.d(TAG, "上次已知文件: ${lastKnownFiles.size}个, 当前扫描到: ${currentFiles.size}个")
            
            // 计算增量
            val incrementalFileNames = currentFiles - lastKnownFiles
            val removedFiles = lastKnownFiles - currentFiles
            
            val incrementalFiles = scanResult.filter { it.fileName in incrementalFileNames }
                .map { it.copy(isIncremental = true) }
            val existingFiles = scanResult.filter { it.fileName in lastKnownFiles }
            
            Log.d(TAG, "增量文件: ${incrementalFiles.size}个, 已删除: ${removedFiles.size}个")
            
            // 更新持久化存储
            val metadata = PersistentStore.StoreMetadata(
                lastExitMillis = System.currentTimeMillis(),
                lastScanCompleteMillis = System.currentTimeMillis(),
                fileCount = currentFiles.size
            )
            persistentStore.updateData(metadata, currentFiles)
            
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "冷启动扫描完成: 耗时${duration}ms, 总文件${currentFiles.size}, 增量${incrementalFiles.size}")
            
            ColdScanResult(
                existingFiles = existingFiles,
                incrementalFiles = incrementalFiles,
                removedFiles = removedFiles,
                scanDurationMs = duration
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "冷启动扫描失败", e)
            // 返回空结果，让调用方决定如何处理
            ColdScanResult(
                existingFiles = emptyList(),
                incrementalFiles = emptyList(),
                removedFiles = emptySet(),
                scanDurationMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * 检查是否可以使用直接文件访问（NIO）
     * 仅对应用内部目录或应用专有外部目录有效
     */
    private fun canUseDirectFileAccess(uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        
        val path = uri.path ?: return false
        val filesDir = context.filesDir.absolutePath
        val externalFilesDir = context.getExternalFilesDir(null)?.absolutePath ?: ""
        
        return path.startsWith(filesDir) || 
               (externalFilesDir.isNotEmpty() && path.startsWith(externalFilesDir))
    }
    
    /**
     * 使用Java NIO进行扫描（性能最佳）
     */
    private fun scanWithNIO(uri: Uri): List<FileRecord> {
        val pathString = uri.path ?: return emptyList()
        val path = Paths.get(pathString)
        
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            Log.w(TAG, "目录不存在或不是目录: $pathString")
            return emptyList()
        }
        
        return try {
            Files.newDirectoryStream(path).use { stream ->
                stream.asSequence()
                    .filter { Files.isRegularFile(it) }
                    .filter { isImageFile(it.fileName.toString()) }
                    .mapNotNull { filePath ->
                        try {
                            val attrs = Files.readAttributes(filePath, BasicFileAttributes::class.java)
                            FileRecord(
                                fileName = filePath.fileName.toString(),
                                uri = Uri.parse(filePath.toUri().toString()),
                                lastModified = attrs.lastModifiedTime().toMillis(),
                                fileSize = attrs.size(),
                                isIncremental = false
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "读取文件属性失败: ${filePath.fileName}", e)
                            null
                        }
                    }
                    .toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "NIO扫描失败", e)
            emptyList()
        }
    }
    
    /**
     * 使用优化的DocumentFile API进行扫描
     * 适用于SAF授权的外部目录
     * 优化：使用 sequence 延迟计算，减少内存占用
     */
    private fun scanWithOptimizedDocumentFile(uri: Uri): List<FileRecord> {
        val documentDir = DocumentFile.fromTreeUri(context, uri)
        
        if (documentDir == null || !documentDir.exists() || !documentDir.isDirectory) {
            Log.w(TAG, "DocumentFile目录不存在或不是目录: $uri")
            return emptyList()
        }
        
        return try {
            // 获取所有文件（这是不可避免的SAF调用）
            val allFiles = documentDir.listFiles()
            
            Log.d(TAG, "DocumentFile扫描: 共${allFiles.size}个条目")
            
            // 立即通知文件数量（在过滤之前）
            onFileCountDetected?.invoke(allFiles.size)
            
            // 优化：使用 sequence 延迟计算，减少内存占用
            // 先过滤再转换，避免不必要的对象创建
            allFiles.asSequence()
                .filter { it.isFile && isImageFile(it.name) }
                .mapNotNull { doc ->
                    try {
                        FileRecord.fromDocumentFile(doc, false)
                    } catch (e: Exception) {
                        Log.w(TAG, "读取文件失败: ${doc.name}", e)
                        null
                    }
                }
                .toList()
                
        } catch (e: Exception) {
            Log.e(TAG, "DocumentFile扫描失败", e)
            emptyList()
        }
    }
    
    /**
     * 检查文件是否为图片文件
     */
    private fun isImageFile(fileName: String?): Boolean {
        if (fileName == null) return false
        // 过滤临时文件
        if (fileName.startsWith(".") || fileName.endsWith(".tmp") || fileName.endsWith(".download")) {
            return false
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in config.allowedExtensions
    }
}
