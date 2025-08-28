#ifndef MEMORY_POOL_H
#define MEMORY_POOL_H

#include <memory>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <cstddef>
#include <algorithm>
#include <functional>
#include <chrono>
#include <android/log.h>

#define POOL_TAG "MemoryPool"
#define POOL_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, POOL_TAG, __VA_ARGS__)
#define POOL_LOGI(...) __android_log_print(ANDROID_LOG_INFO, POOL_TAG, __VA_ARGS__)
#define POOL_LOGW(...) __android_log_print(ANDROID_LOG_WARN, POOL_TAG, __VA_ARGS__)
#define POOL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, POOL_TAG, __VA_ARGS__)

/**
 * 内存块结构
 */
struct MemoryBlock {
    void* ptr;
    size_t size;
    size_t alignment;
    bool inUse;
    std::chrono::steady_clock::time_point lastUsed;
    
    MemoryBlock(void* p, size_t s, size_t a) 
        : ptr(p), size(s), alignment(a), inUse(false), 
          lastUsed(std::chrono::steady_clock::now()) {}
};

/**
 * 内存池统计信息
 */
struct PoolStats {
    size_t totalAllocated = 0;
    size_t totalInUse = 0;
    size_t totalFree = 0;
    size_t blockCount = 0;
    size_t hitCount = 0;
    size_t missCount = 0;
    size_t reuseCount = 0;
    
    double getHitRate() const {
        size_t total = hitCount + missCount;
        return total > 0 ? static_cast<double>(hitCount) / total : 0.0;
    }
};

/**
 * 高性能内存池管理器
 * 专为大图片处理优化，支持不同尺寸的内存块复用
 */
class MemoryPool {
public:
    static MemoryPool& getInstance();
    
    // 内存分配和释放
    void* allocate(size_t size, size_t alignment = 32);
    void deallocate(void* ptr);
    
    // 预分配常用尺寸的内存块
    void preallocateCommonSizes();
    
    // 内存池管理
    void cleanup(bool force = false);
    void setMaxPoolSize(size_t maxSize);
    void setCleanupThreshold(double threshold);
    
    // 统计信息
    PoolStats getStats() const;
    void resetStats();
    
    // 内存压力检测
    bool isMemoryPressureHigh() const;
    void setMemoryPressureCallback(std::function<void(double)> callback);
    
    ~MemoryPool();
    
private:
    MemoryPool();
    MemoryPool(const MemoryPool&) = delete;
    MemoryPool& operator=(const MemoryPool&) = delete;
    
    // 内部方法
    MemoryBlock* findSuitableBlock(size_t size, size_t alignment);
    void* allocateNewBlock(size_t size, size_t alignment);
    void cleanupOldBlocks();
    size_t calculateBlockKey(size_t size, size_t alignment) const;
    
    // 成员变量
    mutable std::mutex mutex_;
    std::vector<std::unique_ptr<MemoryBlock>> blocks_;
    std::unordered_map<size_t, std::vector<MemoryBlock*>> sizeMap_;
    
    // 配置参数
    size_t maxPoolSize_ = 256 * 1024 * 1024; // 256MB默认最大池大小
    double cleanupThreshold_ = 0.8; // 80%使用率时触发清理
    std::chrono::minutes maxBlockAge_{10}; // 10分钟未使用的块会被清理
    
    // 统计信息
    mutable PoolStats stats_;
    
    // 内存压力回调
    std::function<void(double)> pressureCallback_;
    
    // 常用尺寸定义（基于常见图片分辨率）
    static const std::vector<size_t> COMMON_SIZES;
};

/**
 * RAII风格的内存池分配器
 */
template<typename T>
class PoolAllocator {
public:
    using value_type = T;
    
    PoolAllocator() = default;
    
    template<typename U>
    PoolAllocator(const PoolAllocator<U>&) {}
    
    T* allocate(size_t n) {
        size_t size = n * sizeof(T);
        void* ptr = MemoryPool::getInstance().allocate(size, alignof(T));
        if (!ptr) {
            throw std::bad_alloc();
        }
        return static_cast<T*>(ptr);
    }
    
    void deallocate(T* ptr, size_t) {
        MemoryPool::getInstance().deallocate(ptr);
    }
    
    template<typename U>
    bool operator==(const PoolAllocator<U>&) const { return true; }
    
    template<typename U>
    bool operator!=(const PoolAllocator<U>&) const { return false; }
};

/**
 * 智能内存管理器 - 自动管理内存生命周期
 */
class SmartBuffer {
public:
    SmartBuffer() = default;
    SmartBuffer(size_t size, size_t alignment = 32);
    SmartBuffer(SmartBuffer&& other) noexcept;
    SmartBuffer& operator=(SmartBuffer&& other) noexcept;
    
    ~SmartBuffer();
    
    // 禁止拷贝
    SmartBuffer(const SmartBuffer&) = delete;
    SmartBuffer& operator=(const SmartBuffer&) = delete;
    
    // 访问器
    void* data() const { return ptr_; }
    size_t size() const { return size_; }
    bool empty() const { return ptr_ == nullptr; }
    
    // 重新分配
    bool resize(size_t newSize, size_t alignment = 32);
    void reset();
    
    // 类型转换
    template<typename T>
    T* as() const { return static_cast<T*>(ptr_); }
    
private:
    void* ptr_ = nullptr;
    size_t size_ = 0;
    size_t alignment_ = 32;
};

#endif // MEMORY_POOL_H