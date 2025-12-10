package cn.alittlecookie.lut2photo.lut2photo.filetracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 轻量级持久化存储
 * 使用简单的文本文件存储已知文件列表和元数据
 * 
 * 文件格式：
 * 第1行：lastExitMillis（上次退出时间戳）
 * 第2行：lastScanCompleteMillis（上次扫描完成时间戳）
 * 第3行：fileCount（文件数量）
 * 第4行起：每行一个文件名
 */
class PersistentStore(context: Context) {
    
    companion object {
        private const val TAG = "PersistentStore"
        private const val FILE_NAME = "file_tracker.txt"
    }
    
    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    
    /**
     * 存储元数据
     */
    data class StoreMetadata(
        val lastExitMillis: Long = 0L,
        val lastScanCompleteMillis: Long = 0L,
        val fileCount: Int = 0
    )
    
    /**
     * 获取元数据
     */
    suspend fun getMetadata(): StoreMetadata = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) {
                return@withContext StoreMetadata()
            }
            
            try {
                file.useLines { lines ->
                    val linesList = lines.take(3).toList()
                    StoreMetadata(
                        lastExitMillis = linesList.getOrNull(0)?.toLongOrNull() ?: 0L,
                        lastScanCompleteMillis = linesList.getOrNull(1)?.toLongOrNull() ?: 0L,
                        fileCount = linesList.getOrNull(2)?.toIntOrNull() ?: 0
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取元数据失败", e)
                StoreMetadata()
            }
        }
    }
    
    /**
     * 获取已知文件集合
     */
    suspend fun getKnownFiles(): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) {
                return@withContext emptySet()
            }
            
            try {
                file.useLines { lines ->
                    lines.drop(3) // 跳过前3行元数据
                        .filter { it.isNotBlank() }
                        .toSet()
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取已知文件失败", e)
                emptySet()
            }
        }
    }
    
    /**
     * 更新完整数据（元数据 + 文件列表）
     */
    suspend fun updateData(metadata: StoreMetadata, knownFiles: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                file.bufferedWriter().use { writer ->
                    writer.write("${metadata.lastExitMillis}\n")
                    writer.write("${metadata.lastScanCompleteMillis}\n")
                    writer.write("${knownFiles.size}\n")
                    knownFiles.forEach { fileName ->
                        writer.write("$fileName\n")
                    }
                }
                Log.d(TAG, "数据更新成功: ${knownFiles.size}个文件")
            } catch (e: Exception) {
                Log.e(TAG, "更新数据失败", e)
            }
        }
    }
    
    /**
     * 添加单个已知文件（增量更新）
     */
    suspend fun addKnownFile(fileName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val currentFiles = getKnownFilesInternal().toMutableSet()
                if (currentFiles.add(fileName)) {
                    val metadata = getMetadataInternal()
                    updateDataInternal(metadata.copy(fileCount = currentFiles.size), currentFiles)
                    Log.d(TAG, "添加已知文件: $fileName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "添加已知文件失败: $fileName", e)
            }
        }
    }
    
    /**
     * 移除单个已知文件
     */
    suspend fun removeKnownFile(fileName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val currentFiles = getKnownFilesInternal().toMutableSet()
                if (currentFiles.remove(fileName)) {
                    val metadata = getMetadataInternal()
                    updateDataInternal(metadata.copy(fileCount = currentFiles.size), currentFiles)
                    Log.d(TAG, "移除已知文件: $fileName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "移除已知文件失败: $fileName", e)
            }
        }
    }
    
    /**
     * 更新上次退出时间
     */
    suspend fun updateLastExitMillis(millis: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val metadata = getMetadataInternal()
                val knownFiles = getKnownFilesInternal()
                updateDataInternal(metadata.copy(lastExitMillis = millis), knownFiles)
                Log.d(TAG, "更新上次退出时间: $millis")
            } catch (e: Exception) {
                Log.e(TAG, "更新上次退出时间失败", e)
            }
        }
    }
    
    /**
     * 清空所有数据
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (file.exists()) {
                    file.delete()
                }
                Log.d(TAG, "数据已清空")
            } catch (e: Exception) {
                Log.e(TAG, "清空数据失败", e)
            }
        }
    }
    
    /**
     * 检查是否需要全量扫描
     * 如果距离上次扫描超过指定小时数，返回true
     */
    suspend fun needsFullRescan(intervalHours: Int): Boolean = withContext(Dispatchers.IO) {
        val metadata = getMetadata()
        val currentTime = System.currentTimeMillis()
        val intervalMs = intervalHours * 60 * 60 * 1000L
        
        val needsRescan = metadata.lastScanCompleteMillis == 0L ||
                (currentTime - metadata.lastScanCompleteMillis) > intervalMs
        
        if (needsRescan) {
            Log.d(TAG, "需要全量扫描: 上次扫描时间=${metadata.lastScanCompleteMillis}, 间隔=${intervalHours}小时")
        }
        
        needsRescan
    }
    
    // ========== 内部方法（不加锁，供已持有锁的方法调用）==========
    
    private fun getMetadataInternal(): StoreMetadata {
        if (!file.exists()) {
            return StoreMetadata()
        }
        
        return try {
            file.useLines { lines ->
                val linesList = lines.take(3).toList()
                StoreMetadata(
                    lastExitMillis = linesList.getOrNull(0)?.toLongOrNull() ?: 0L,
                    lastScanCompleteMillis = linesList.getOrNull(1)?.toLongOrNull() ?: 0L,
                    fileCount = linesList.getOrNull(2)?.toIntOrNull() ?: 0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取元数据失败(内部)", e)
            StoreMetadata()
        }
    }
    
    private fun getKnownFilesInternal(): Set<String> {
        if (!file.exists()) {
            return emptySet()
        }
        
        return try {
            file.useLines { lines ->
                lines.drop(3)
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取已知文件失败(内部)", e)
            emptySet()
        }
    }
    
    private fun updateDataInternal(metadata: StoreMetadata, knownFiles: Set<String>) {
        try {
            file.bufferedWriter().use { writer ->
                writer.write("${metadata.lastExitMillis}\n")
                writer.write("${metadata.lastScanCompleteMillis}\n")
                writer.write("${knownFiles.size}\n")
                knownFiles.forEach { fileName ->
                    writer.write("$fileName\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新数据失败(内部)", e)
        }
    }
}
