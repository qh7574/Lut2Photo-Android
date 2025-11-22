package cn.alittlecookie.lut2photo.lut2photo.core

import android.util.Log
import android.util.LruCache

/**
 * LUT 文件缓存
 * 使用 LRU 缓存策略，避免重复加载相同的 LUT 文件
 */
object LutCache {
    private const val TAG = "LutCache"
    private const val MAX_CACHE_SIZE = 5 // 最多缓存5个LUT文件
    
    // LUT 数据缓存
    private val lutDataCache = LruCache<String, LutData>(MAX_CACHE_SIZE)
    
    /**
     * LUT 数据结构
     */
    data class LutData(
        val lut: Array<Array<Array<FloatArray>>>,
        val size: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as LutData
            
            if (size != other.size) return false
            if (timestamp != other.timestamp) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = size
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
    
    /**
     * 获取缓存的 LUT 数据
     * @param path LUT 文件路径
     * @return LUT 数据，如果不存在则返回 null
     */
    fun get(path: String): LutData? {
        val data = lutDataCache.get(path)
        if (data != null) {
            Log.d(TAG, "LUT 缓存命中: $path (尺寸: ${data.size})")
        }
        return data
    }
    
    /**
     * 缓存 LUT 数据
     * @param path LUT 文件路径
     * @param lut LUT 数据数组
     * @param size LUT 尺寸
     */
    fun put(path: String, lut: Array<Array<Array<FloatArray>>>, size: Int) {
        val data = LutData(lut, size)
        lutDataCache.put(path, data)
        Log.d(TAG, "LUT 已缓存: $path (尺寸: $size, 缓存数: ${lutDataCache.size()})")
    }
    
    /**
     * 检查是否存在缓存
     * @param path LUT 文件路径
     * @return 是否存在缓存
     */
    fun contains(path: String): Boolean {
        return lutDataCache.get(path) != null
    }
    
    /**
     * 清除指定路径的缓存
     * @param path LUT 文件路径
     */
    fun remove(path: String) {
        lutDataCache.remove(path)
        Log.d(TAG, "LUT 缓存已移除: $path")
    }
    
    /**
     * 清除所有缓存
     */
    fun clear() {
        lutDataCache.evictAll()
        Log.d(TAG, "所有 LUT 缓存已清除")
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getStats(): String {
        return "LUT 缓存: ${lutDataCache.size()}/$MAX_CACHE_SIZE, " +
                "命中率: ${lutDataCache.hitCount()}/${lutDataCache.hitCount() + lutDataCache.missCount()}"
    }
}
