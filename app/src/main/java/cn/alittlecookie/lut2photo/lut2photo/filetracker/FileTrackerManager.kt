package cn.alittlecookie.lut2photo.lut2photo.filetracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FileTracker统一管理器
 * 
 * 对外提供统一的文件追踪接口，内部协调PersistentStore、ColdBootScanner和HotFileObserver
 * 
 * 使用方式：
 * 1. 创建实例：val manager = FileTrackerManager(context)
 * 2. 启动追踪：manager.start(config)
 * 3. 等待冷扫描：manager.awaitColdScanComplete()
 * 4. 获取存量文件：manager.consumeExisting()
 * 5. 监听增量文件：manager.consumeIncremental().collect { ... }
 * 6. 停止追踪：manager.stop()
 */
class FileTrackerManager(
    private val context: Context,
    private val onFileCountDetected: ((Int) -> Unit)? = null  // 文件数量检测回调
) {
    
    companion object {
        private const val TAG = "FileTrackerManager"
    }
    
    private val persistentStore = PersistentStore(context)
    private var coldBootScanner: OptimizedColdBootScanner? = null
    private var hotFileObserver: HotFileObserver? = null
    
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 状态管理
    private val _coldScanComplete = MutableStateFlow(false)
    val coldScanComplete: StateFlow<Boolean> = _coldScanComplete
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    
    // 增量文件Channel
    private var incrementalChannel: Channel<FileRecord>? = null
    
    // 存量文件缓存
    private var existingFilesCache: List<FileRecord> = emptyList()
    
    // 当前配置
    private var currentConfig: FileTrackerConfig? = null
    
    /**
     * 消费增量文件流
     * 返回一个Flow，每当有新文件时会发射FileRecord
     */
    fun consumeIncremental(): Flow<FileRecord> {
        return incrementalChannel?.receiveAsFlow() 
            ?: throw IllegalStateException("FileTrackerManager未启动")
    }
    
    /**
     * 获取存量文件列表
     * 需要在awaitColdScanComplete()之后调用
     */
    suspend fun consumeExisting(): List<FileRecord> {
        return existingFilesCache
    }
    
    /**
     * 等待冷扫描完成
     */
    suspend fun awaitColdScanComplete() {
        coldScanComplete.first { it }
    }
    
    /**
     * 启动文件追踪
     * @param config 配置
     */
    suspend fun start(config: FileTrackerConfig) = withContext(Dispatchers.IO) {
        if (_isRunning.value) {
            Log.w(TAG, "FileTrackerManager已在运行中")
            return@withContext
        }
        
        Log.d(TAG, "FileTrackerManager启动: ${config.targetFolderUri}")
        
        currentConfig = config
        _isRunning.value = true
        _coldScanComplete.value = false
        
        // 初始化Channel
        incrementalChannel = Channel(Channel.BUFFERED)
        
        // 初始化组件，传递文件数量检测回调
        coldBootScanner = OptimizedColdBootScanner(context, persistentStore, config, onFileCountDetected)
        hotFileObserver = HotFileObserver(context, persistentStore, config)
        
        try {
            // 执行冷启动扫描
            val scanResult = coldBootScanner!!.scan()
            existingFilesCache = scanResult.existingFiles
            
            Log.d(TAG, "冷扫描完成: 存量${scanResult.existingFiles.size}, 增量${scanResult.incrementalFiles.size}")
            
            // 将增量文件发送到Channel
            scanResult.incrementalFiles.forEach { fileRecord ->
                incrementalChannel?.trySend(fileRecord)
            }
            
            // 启动热监听
            hotFileObserver!!.start { fileRecord ->
                incrementalChannel?.trySend(fileRecord)
            }
            
            // 更新热监听的已知文件缓存
            val allKnownFiles = (scanResult.existingFiles + scanResult.incrementalFiles)
                .map { it.fileName }.toSet()
            hotFileObserver!!.updateKnownFilesCache(allKnownFiles)
            
            // 标记冷扫描完成
            _coldScanComplete.value = true
            
            Log.d(TAG, "FileTrackerManager启动完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "FileTrackerManager启动失败", e)
            _coldScanComplete.value = true // 即使失败也标记完成，避免调用方永久等待
            throw e
        }
    }
    
    /**
     * 停止文件追踪
     */
    fun stop() {
        Log.d(TAG, "FileTrackerManager停止")
        
        hotFileObserver?.stop()
        hotFileObserver = null
        
        coldBootScanner = null
        
        incrementalChannel?.close()
        incrementalChannel = null
        
        _coldScanComplete.value = false
        _isRunning.value = false
        existingFilesCache = emptyList()
        currentConfig = null
        
        // 更新退出时间
        managerScope.launch {
            persistentStore.updateLastExitMillis(System.currentTimeMillis())
        }
        
        Log.d(TAG, "FileTrackerManager已停止")
    }
    
    /**
     * 获取最新文件（用于预览）
     * 返回最后修改时间最新的文件
     */
    suspend fun getLatestFile(): FileRecord? = withContext(Dispatchers.IO) {
        existingFilesCache.maxByOrNull { it.lastModified }
    }
    
    /**
     * 检查文件是否已知
     */
    fun isFileKnown(fileName: String): Boolean {
        return existingFilesCache.any { it.fileName == fileName }
    }
    
    /**
     * 标记文件为已处理
     */
    suspend fun markFileAsProcessed(fileName: String) {
        persistentStore.addKnownFile(fileName)
        hotFileObserver?.addToKnownCache(fileName)
    }
    
    /**
     * 获取存量文件数量
     */
    fun getExistingFilesCount(): Int {
        return existingFilesCache.size
    }
    
    /**
     * 清空持久化数据
     * 下次启动时会重新全量扫描
     */
    suspend fun clearPersistentData() {
        persistentStore.clear()
        Log.d(TAG, "持久化数据已清空")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stop()
        managerScope.cancel()
    }
}
