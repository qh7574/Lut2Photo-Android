package cn.alittlecookie.lut2photo.lut2photo.filetracker

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 实时文件监听器
 * 
 * 使用FileObserver监听目录变化，实时检测新增文件
 * 
 * 特点：
 * 1. 仅监控目标目录本身，不递归子目录
 * 2. 快速过滤临时文件和非图片文件
 * 3. 内存中维护已知文件缓存，避免重复处理
 * 4. 文件完整性检查，确保文件写入完成后再处理
 */
class HotFileObserver(
    private val context: Context,
    private val persistentStore: PersistentStore,
    private val config: FileTrackerConfig
) {
    companion object {
        private const val TAG = "HotFileObserver"
    }
    
    private var fileObserver: FileObserver? = null
    private val knownFilesCache = ConcurrentHashMap<String, Boolean>()
    private var onNewFileCallback: ((FileRecord) -> Unit)? = null
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 用于SAF目录的轮询监控
    private var pollingJob: kotlinx.coroutines.Job? = null
    private var lastPollingFiles: Set<String> = emptySet()
    
    // ContentObserver用于SAF目录变化监听
    private var contentObserver: ContentObserver? = null
    private var pendingCheck = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * 启动监听
     * @param onNewFile 新文件回调
     */
    fun start(onNewFile: (FileRecord) -> Unit) {
        this.onNewFileCallback = onNewFile
        
        val targetUri = Uri.parse(config.targetFolderUri)
        
        // 根据URI类型选择监听方式
        if (targetUri.scheme == "file") {
            startFileObserver(targetUri)
        } else {
            // SAF目录使用轮询方式（FileObserver不支持content://）
            startPollingObserver(targetUri)
        }
    }
    
    /**
     * 停止监听
     */
    fun stop() {
        Log.d(TAG, "正在停止HotFileObserver...")
        
        // 停止FileObserver
        fileObserver?.stopWatching()
        fileObserver = null
        
        // 取消轮询协程
        pollingJob?.cancel()
        pollingJob = null
        
        // 注销ContentObserver
        contentObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                Log.w(TAG, "注销ContentObserver失败", e)
            }
        }
        contentObserver = null
        
        // 清理回调
        onNewFileCallback = null
        
        // 取消协程作用域
        observerScope.cancel()
        
        Log.d(TAG, "HotFileObserver已停止")
    }
    
    /**
     * 更新已知文件缓存
     */
    fun updateKnownFilesCache(files: Set<String>) {
        knownFilesCache.clear()
        files.forEach { fileName ->
            knownFilesCache[fileName] = true
        }
        lastPollingFiles = files
        Log.d(TAG, "已知文件缓存已更新: ${files.size}个文件")
    }
    
    /**
     * 添加文件到已知缓存
     */
    fun addToKnownCache(fileName: String) {
        knownFilesCache[fileName] = true
    }
    
    /**
     * 使用FileObserver监听（适用于file://协议）
     */
    private fun startFileObserver(targetUri: Uri) {
        val targetPath = targetUri.path
        if (targetPath == null) {
            Log.e(TAG, "无法获取目录路径: $targetUri")
            return
        }
        
        val targetDir = File(targetPath)
        if (!targetDir.exists() || !targetDir.isDirectory) {
            Log.e(TAG, "目录不存在或不是目录: $targetPath")
            return
        }
        
        // 初始化已知文件缓存
        observerScope.launch {
            val knownFiles = persistentStore.getKnownFiles()
            knownFiles.forEach { fileName ->
                knownFilesCache[fileName] = true
            }
            Log.d(TAG, "已知文件缓存初始化完成: ${knownFiles.size}个文件")
        }
        
        // 创建FileObserver
        // 监听事件：CREATE（创建）、MOVED_TO（移入）、CLOSE_WRITE（写入完成）
        fileObserver = object : FileObserver(targetPath, CREATE or MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                
                // 快速过滤
                if (!isImageFile(path)) return
                if (knownFilesCache.containsKey(path)) return
                
                Log.d(TAG, "检测到文件事件: event=$event, path=$path")
                
                // 异步处理新文件
                observerScope.launch {
                    handleNewFile(path, targetPath)
                }
            }
        }
        
        fileObserver?.startWatching()
        Log.d(TAG, "FileObserver已启动，监控路径: $targetPath")
    }
    
    /**
     * 使用ContentObserver + 轮询方式监听（适用于content://协议的SAF目录）
     * 优化策略：
     * 1. 使用ContentObserver监听目录变化事件
     * 2. 收到事件后延迟检查，避免频繁查询
     * 3. 使用ContentResolver直接查询，比DocumentFile.listFiles()快
     */
    private fun startPollingObserver(targetUri: Uri) {
        Log.d(TAG, "SAF目录使用ContentObserver + 优化轮询监控: $targetUri")
        
        // 获取children URI用于监听
        val childrenUri = try {
            val docId = DocumentsContract.getTreeDocumentId(targetUri)
            DocumentsContract.buildChildDocumentsUriUsingTree(targetUri, docId)
        } catch (e: Exception) {
            Log.e(TAG, "无法构建children URI", e)
            null
        }
        
        // 注册ContentObserver监听目录变化
        if (childrenUri != null) {
            contentObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    onChange(selfChange, null)
                }
                
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    Log.d(TAG, "ContentObserver检测到变化: $uri")
                    // 标记需要检查，避免重复触发
                    if (!pendingCheck) {
                        pendingCheck = true
                        observerScope.launch {
                            delay(500) // 延迟500ms，等待文件写入完成
                            pendingCheck = false
                            checkForNewFilesOptimized(targetUri, childrenUri)
                        }
                    }
                }
            }
            
            context.contentResolver.registerContentObserver(
                childrenUri,
                true,
                contentObserver!!
            )
            Log.d(TAG, "ContentObserver已注册: $childrenUri")
        }
        
        // 同时启动低频轮询作为兜底（每10秒）
        pollingJob = observerScope.launch {
            try {
                while (true) {
                    delay(10000) // 每10秒检查一次作为兜底
                    
                    if (childrenUri != null) {
                        checkForNewFilesOptimized(targetUri, childrenUri)
                    } else {
                        // 降级到原有方式
                        checkForNewFilesLegacy(targetUri)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程被正常取消，不记录错误日志
                Log.d(TAG, "轮询监控已停止")
                throw e // 重新抛出以确保协程正确取消
            } catch (e: Exception) {
                Log.e(TAG, "轮询监控出错", e)
            }
        }
    }
    
    /**
     * 优化的新文件检查（使用ContentResolver直接查询）
     */
    private suspend fun checkForNewFilesOptimized(treeUri: Uri, childrenUri: Uri) {
        try {
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            )
            
            val currentFiles = mutableMapOf<String, DocumentInfo>()
            
            context.contentResolver.query(
                childrenUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex)
                    val mimeType = cursor.getString(mimeIndex) ?: ""
                    val lastModified = cursor.getLong(modifiedIndex)
                    val size = cursor.getLong(sizeIndex)
                    
                    // 只处理配置中允许的图片文件（使用扩展名过滤，与冷扫描保持一致）
                    if (isImageFile(name)) {
                        currentFiles[name] = DocumentInfo(docId, name, lastModified, size)
                    }
                }
            }
            
            // 计算新增文件
            val newFileNames = currentFiles.keys - lastPollingFiles - knownFilesCache.keys
            
            if (newFileNames.isNotEmpty()) {
                Log.d(TAG, "检测到新文件: ${newFileNames.size}个")
                
                for (fileName in newFileNames) {
                    val docInfo = currentFiles[fileName] ?: continue
                    
                    // 构建文件URI
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docInfo.docId)
                    
                    // 等待文件写入完成
                    if (!waitForFileSizeStable(fileUri, docInfo.size)) {
                        Log.w(TAG, "文件写入未完成，跳过: $fileName")
                        continue
                    }
                    
                    // 创建FileRecord
                    val fileRecord = FileRecord(
                        fileName = fileName,
                        uri = fileUri,
                        lastModified = docInfo.lastModified,
                        fileSize = docInfo.size,
                        isIncremental = true
                    )
                    
                    // 添加到已知文件
                    knownFilesCache[fileName] = true
                    persistentStore.addKnownFile(fileName)
                    
                    // 回调业务层
                    onNewFileCallback?.invoke(fileRecord)
                    
                    Log.d(TAG, "新文件处理完成: $fileName")
                }
            }
            
            lastPollingFiles = currentFiles.keys
            
        } catch (e: Exception) {
            Log.e(TAG, "优化查询出错，降级到传统方式", e)
            checkForNewFilesLegacy(treeUri)
        }
    }
    
    /**
     * 传统的新文件检查（使用DocumentFile，较慢）
     */
    private suspend fun checkForNewFilesLegacy(targetUri: Uri) {
        val documentDir = DocumentFile.fromTreeUri(context, targetUri)
        if (documentDir == null || !documentDir.exists()) {
            Log.w(TAG, "SAF目录不存在: $targetUri")
            return
        }
        
        // 获取当前文件列表
        val allFiles = documentDir.listFiles()
        val currentFiles = allFiles
            .filter { it.isFile && isImageFile(it.name) }
            .mapNotNull { it.name }
            .toSet()
        
        // 计算新增文件
        val newFiles = currentFiles - lastPollingFiles - knownFilesCache.keys
        
        if (newFiles.isNotEmpty()) {
            Log.d(TAG, "轮询检测到新文件: ${newFiles.size}个")
            
            for (fileName in newFiles) {
                val docFile = allFiles.find { it.name == fileName }
                if (docFile != null) {
                    handleNewDocumentFile(docFile)
                }
            }
        }
        
        lastPollingFiles = currentFiles
    }
    
    /**
     * 等待文件大小稳定（通过ContentResolver查询）
     */
    private suspend fun waitForFileSizeStable(fileUri: Uri, initialSize: Long): Boolean {
        if (initialSize <= 0) {
            delay(config.fileCompleteCheckDelayMs)
        }
        
        repeat(config.fileCompleteCheckMaxRetries) {
            delay(config.fileCompleteCheckDelayMs)
            
            val currentSize = try {
                context.contentResolver.query(
                    fileUri,
                    arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getLong(0)
                    } else initialSize
                } ?: initialSize
            } catch (e: Exception) {
                initialSize
            }
            
            if (currentSize == initialSize && currentSize > 0) {
                return true
            }
        }
        return initialSize > 0
    }
    
    /**
     * 文档信息数据类
     */
    private data class DocumentInfo(
        val docId: String,
        val name: String,
        val lastModified: Long,
        val size: Long
    )
    
    /**
     * 处理新文件（file://协议）
     */
    private suspend fun handleNewFile(fileName: String, parentPath: String) {
        try {
            val file = File(parentPath, fileName)
            
            // 验证文件存在性
            if (!file.exists() || !file.isFile) {
                Log.w(TAG, "新文件不存在或不是文件: $fileName")
                return
            }
            
            // 等待文件写入完成
            if (!waitForFileComplete(file)) {
                Log.w(TAG, "文件写入未完成，跳过: $fileName")
                return
            }
            
            // 创建FileRecord
            val fileRecord = FileRecord(
                fileName = fileName,
                uri = file.toUri(),
                lastModified = file.lastModified(),
                fileSize = file.length(),
                isIncremental = true
            )
            
            // 添加到已知文件
            knownFilesCache[fileName] = true
            persistentStore.addKnownFile(fileName)
            
            // 回调业务层
            onNewFileCallback?.invoke(fileRecord)
            
            Log.d(TAG, "新文件处理完成: $fileName")
            
        } catch (e: Exception) {
            Log.e(TAG, "处理新文件失败: $fileName", e)
        }
    }
    
    /**
     * 处理新DocumentFile（content://协议）
     */
    private suspend fun handleNewDocumentFile(documentFile: DocumentFile) {
        try {
            val fileName = documentFile.name ?: return
            
            // 等待文件写入完成
            if (!waitForDocumentFileComplete(documentFile)) {
                Log.w(TAG, "文件写入未完成，跳过: $fileName")
                return
            }
            
            // 创建FileRecord
            val fileRecord = FileRecord.fromDocumentFile(documentFile, true)
            if (fileRecord == null) {
                Log.w(TAG, "无法创建FileRecord: $fileName")
                return
            }
            
            // 添加到已知文件
            knownFilesCache[fileName] = true
            persistentStore.addKnownFile(fileName)
            
            // 回调业务层
            onNewFileCallback?.invoke(fileRecord)
            
            Log.d(TAG, "新DocumentFile处理完成: $fileName")
            
        } catch (e: Exception) {
            Log.e(TAG, "处理新DocumentFile失败", e)
        }
    }
    
    /**
     * 等待文件写入完成（file://协议）
     */
    private suspend fun waitForFileComplete(file: File): Boolean {
        repeat(config.fileCompleteCheckMaxRetries) {
            val size1 = file.length()
            delay(config.fileCompleteCheckDelayMs)
            val size2 = file.length()
            
            if (size1 == size2 && size1 > 0) {
                return true
            }
        }
        return false
    }
    
    /**
     * 等待DocumentFile写入完成（content://协议）
     */
    private suspend fun waitForDocumentFileComplete(documentFile: DocumentFile): Boolean {
        repeat(config.fileCompleteCheckMaxRetries) {
            val size1 = documentFile.length()
            delay(config.fileCompleteCheckDelayMs)
            val size2 = documentFile.length()
            
            if (size1 == size2 && size1 > 0) {
                return true
            }
        }
        return false
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
