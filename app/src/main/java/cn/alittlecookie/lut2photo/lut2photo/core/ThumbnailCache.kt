package cn.alittlecookie.lut2photo.lut2photo.core

import android.graphics.Bitmap

/**
 * 缩略图缓存管理器（单例）
 * 用于在 BottomSheet 关闭后保留缓存
 */
object ThumbnailCache {
    // 缩略图缓存：路径 -> Bitmap
    private val cache = mutableMapOf<String, Bitmap>()
    
    // 正在加载的缩略图路径集合
    private val loading = mutableSetOf<String>()
    
    /**
     * 获取缓存的缩略图
     */
    fun get(path: String): Bitmap? {
        synchronized(cache) {
            return cache[path]
        }
    }
    
    /**
     * 缓存缩略图
     */
    fun put(path: String, bitmap: Bitmap) {
        synchronized(cache) {
            cache[path] = bitmap
        }
    }
    
    /**
     * 检查是否正在加载
     */
    fun isLoading(path: String): Boolean {
        synchronized(loading) {
            return loading.contains(path)
        }
    }
    
    /**
     * 标记为正在加载
     */
    fun markLoading(path: String): Boolean {
        synchronized(loading) {
            if (loading.contains(path)) {
                return false // 已经在加载中
            }
            loading.add(path)
            return true // 成功标记
        }
    }
    
    /**
     * 移除加载标记
     */
    fun unmarkLoading(path: String) {
        synchronized(loading) {
            loading.remove(path)
        }
    }
    
    /**
     * 清除所有缓存（断开连接时调用）
     */
    fun clear() {
        synchronized(cache) {
            // 回收所有 Bitmap
            cache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            cache.clear()
        }
        synchronized(loading) {
            loading.clear()
        }
    }
    
    /**
     * 获取缓存大小
     */
    fun size(): Int {
        synchronized(cache) {
            return cache.size
        }
    }
}
