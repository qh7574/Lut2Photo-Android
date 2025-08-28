#ifndef MEMORY_MANAGER_H
#define MEMORY_MANAGER_H

#include <cstddef>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <memory>
#include <chrono>
#include <jni.h>
#include <functional>
#include <vector>
#include <thread>
#include "memory_pool.h"

// 分配信息结构
struct AllocationInfo {
    size_t size;
    size_t alignment;
    std::chrono::steady_clock::time_point timestamp;
    bool fromPool; // 是否来自内存池

    AllocationInfo() : size(0), alignment(0), timestamp(std::chrono::steady_clock::now()),
                       fromPool(false) {}

    AllocationInfo(size_t s, size_t a, bool pool = false)
            : size(s), alignment(a), timestamp(std::chrono::steady_clock::now()), fromPool(pool) {}
};

// 内存监控事件类型
enum class MemoryEvent {
    ALLOCATION,
    DEALLOCATION,
    PRESSURE_WARNING,
    LIMIT_EXCEEDED,
    POOL_CLEANUP
};

// 内存监控回调
struct MemoryEventInfo {
    MemoryEvent event;
    size_t size;
    size_t totalAllocated;
    double memoryUsage; // 0.0-1.0
    std::chrono::steady_clock::time_point timestamp;
};

typedef std::function<void(const MemoryEventInfo &)> MemoryEventCallback;

/**
 * 增强的内存管理器 - 单例模式
 * 集成内存池，提供高级内存分配、监控和优化功能
 */
class MemoryManager {
public:
    /**
     * 获取单例实例
     */
    static MemoryManager &getInstance();

    /**
     * 分配内存
     * @param size 要分配的字节数
     * @param alignment 内存对齐字节数（默认32字节）
     * @return 分配的内存指针，失败返回nullptr
     */
    void *allocate(size_t size, size_t alignment = 32);

    /**
     * 智能分配（自动选择池或直接分配）
     * @param size 要分配的字节数
     * @param alignment 内存对齐字节数
     * @return 分配的内存指针，失败返回nullptr
     */
    void *smartAllocate(size_t size, size_t alignment = 32);

    /**
     * 释放内存
     * @param ptr 要释放的内存指针
     */
    void deallocate(void *ptr);

    /**
     * 重新分配内存
     * @param ptr 原内存指针
     * @param newSize 新的大小
     * @param alignment 内存对齐字节数
     * @return 新的内存指针
     */
    void *reallocate(void *ptr, size_t newSize, size_t alignment = 32);

    /**
     * 分配对齐的内存块（用于图片数据）
     * @param width 图片宽度
     * @param height 图片高度
     * @param bytesPerPixel 每像素字节数
     * @param rowAlignment 行对齐字节数（默认32字节）
     * @return 分配的内存指针和实际stride
     */
    struct AlignedImageBuffer {
        void *data;
        size_t stride;
        size_t totalSize;
    };

    AlignedImageBuffer
    allocateImageBuffer(int width, int height, int bytesPerPixel, size_t rowAlignment = 32);

    /**
     * 释放图片缓冲区
     * @param buffer 要释放的缓冲区
     */
    void deallocateImageBuffer(const AlignedImageBuffer &buffer);

    /**
     * 获取当前已分配的内存总量
     * @return 字节数
     */
    size_t getTotalAllocatedBytes() const;

    /**
     * 获取分配次数
     * @return 分配次数
     */
    size_t getAllocationCount() const;

    /**
     * 获取释放次数
     * @return 释放次数
     */
    size_t getDeallocationCount() const;

    /**
     * 获取当前活跃的分配块数量
     * @return 活跃分配数
     */
    size_t getActiveAllocations() const;

    /**
     * 获取内存池分配的字节数
     * @return 字节数
     */
    size_t getPoolAllocatedBytes() const;

    /**
     * 获取直接分配的字节数
     * @return 字节数
     */
    size_t getDirectAllocatedBytes() const;

    /**
     * 检查内存泄漏
     * @return 是否有内存泄漏
     */
    bool hasMemoryLeaks() const;

    /**
     * 打印内存使用统计
     */
    void printMemoryStats() const;

    /**
     * 清理所有分配的内存（调试用）
     */
    void cleanup();

    /**
     * 设置内存使用限制
     * @param maxBytes 最大字节数（0表示无限制）
     */
    void setMemoryLimit(size_t maxBytes);

    /**
     * 获取内存使用限制
     * @return 最大字节数
     */
    size_t getMemoryLimit() const;

    /**
     * 检查是否接近内存限制
     * @param threshold 阈值（0.0-1.0）
     * @return 是否接近限制
     */
    bool isNearMemoryLimit(float threshold = 0.9f) const;

    /**
     * 获取内存使用比率
     * @return 使用比率（0.0-1.0）
     */
    double getMemoryUsageRatio() const;

    /**
     * 设置内存压力阈值
     * @param threshold 阈值（0.0-1.0）
     */
    void setMemoryPressureThreshold(float threshold);

    /**
     * 检查内存压力是否过高
     * @return 是否高压力
     */
    bool isMemoryPressureHigh() const;

    /**
     * 处理内存压力
     */
    void handleMemoryPressure();

    /**
     * 设置事件回调
     * @param callback 回调函数
     */
    void setEventCallback(MemoryEventCallback callback);

    /**
     * 移除事件回调
     */
    void removeEventCallback();

    /**
     * 优化内存使用
     */
    void optimizeMemoryUsage();

    /**
     * 启用自动优化
     * @param enable 是否启用
     */
    void enableAutoOptimization(bool enable);

    /**
     * 设置优化间隔
     * @param interval 间隔时间
     */
    void setOptimizationInterval(std::chrono::seconds interval);

    /**
     * 设置JavaVM指针
     * @param vm JavaVM指针
     */
    void setJavaVM(JavaVM *vm);

    /**
     * 强制垃圾回收（通知Java层）
     */
    void forceGarbageCollection();

    /**
     * 配置内存池
     * @param maxPoolSize 最大池大小
     * @param cleanupThreshold 清理阈值
     */
    void configureMemoryPool(size_t maxPoolSize, double cleanupThreshold);

    /**
     * 获取内存池统计
     * @return 池统计信息
     */
    PoolStats getPoolStats() const;

    /**
     * 获取详细统计信息
     * @return 统计字符串
     */
    std::string getDetailedStats() const;

    /**
     * 获取大分配列表
     * @param minSize 最小大小
     * @return 大分配列表
     */
    std::vector<std::pair<void *, AllocationInfo>> getLargeAllocations(size_t minSize) const;

    /**
     * 转储内存映射
     */
    void dumpMemoryMap() const;

private:
    MemoryManager();

    ~MemoryManager();

    // 禁止拷贝和赋值
    MemoryManager(const MemoryManager &) = delete;

    MemoryManager &operator=(const MemoryManager &) = delete;

    // 内部方法
    void *allocateInternal(size_t size, size_t alignment);

    void *allocateFromPool(size_t size, size_t alignment);

    void *allocateDirect(size_t size, size_t alignment);

    void deallocateInternal(void *ptr);

    bool checkMemoryLimit(size_t requestedSize) const;

    void triggerEvent(MemoryEvent event, size_t size = 0);

    void startOptimizationThread();

    void stopOptimizationThread();

    void optimizationWorker();

    // 成员变量
    mutable std::mutex mutex_;
    std::unordered_map<void *, AllocationInfo> allocations_;
    std::atomic<size_t> totalAllocatedBytes_{0};
    std::atomic<size_t> poolAllocatedBytes_{0};
    std::atomic<size_t> directAllocatedBytes_{0};
    std::atomic<size_t> allocationCount_{0};
    std::atomic<size_t> deallocationCount_{0};
    std::atomic<size_t> memoryLimit_{0}; // 0表示无限制

    // 内存压力管理
    std::atomic<float> pressureThreshold_{0.8f};
    std::atomic<bool> memoryPressureHigh_{false};

    // 事件回调
    MemoryEventCallback eventCallback_;
    mutable std::mutex callbackMutex_;

    // 自动优化
    std::atomic<bool> autoOptimizationEnabled_{false};
    std::atomic<bool> optimizationThreadRunning_{false};
    std::chrono::seconds optimizationInterval_{30}; // 30秒
    std::thread optimizationThread_;

    // JVM指针用于垃圾回收
    JavaVM *javaVM_ = nullptr;

    // 配置参数
    static constexpr size_t POOL_ALLOCATION_THRESHOLD = 4 * 1024 * 1024; // 4MB以下使用池
    static constexpr size_t LARGE_ALLOCATION_THRESHOLD = 64 * 1024 * 1024; // 64MB以上为大分配
};

/**
 * 增强的RAII风格内存管理器
 * 支持自动优化和智能分配策略
 */
template<typename T>
class ManagedBuffer {
public:
    ManagedBuffer() = default;

    explicit ManagedBuffer(size_t count, size_t alignment = alignof(T),
                           bool useSmartAllocation = true) {
        allocate(count, alignment, useSmartAllocation);
    }

    ~ManagedBuffer() {
        deallocate();
    }

    // 移动构造和赋值
    ManagedBuffer(ManagedBuffer &&other) noexcept
            : ptr_(other.ptr_), size_(other.size_), count_(other.count_),
              useSmartAllocation_(other.useSmartAllocation_) {
        other.ptr_ = nullptr;
        other.size_ = 0;
        other.count_ = 0;
    }

    ManagedBuffer &operator=(ManagedBuffer &&other) noexcept {
        if (this != &other) {
            deallocate();
            ptr_ = other.ptr_;
            size_ = other.size_;
            count_ = other.count_;
            useSmartAllocation_ = other.useSmartAllocation_;
            other.ptr_ = nullptr;
            other.size_ = 0;
            other.count_ = 0;
        }
        return *this;
    }

    // 禁止拷贝
    ManagedBuffer(const ManagedBuffer &) = delete;

    ManagedBuffer &operator=(const ManagedBuffer &) = delete;

    // 分配内存
    bool allocate(size_t count, size_t alignment = alignof(T), bool useSmartAllocation = true) {
        deallocate();

        size_ = count * sizeof(T);
        count_ = count;
        useSmartAllocation_ = useSmartAllocation;

        if (useSmartAllocation) {
            ptr_ = static_cast<T *>(MemoryManager::getInstance().smartAllocate(size_, alignment));
        } else {
            ptr_ = static_cast<T *>(MemoryManager::getInstance().allocate(size_, alignment));
        }

        return ptr_ != nullptr;
    }

    // 重新分配
    bool resize(size_t newCount, size_t alignment = alignof(T)) {
        if (newCount == count_) {
            return true;
        }

        size_t newSize = newCount * sizeof(T);
        T *newPtr = static_cast<T *>(MemoryManager::getInstance().reallocate(ptr_, newSize,
                                                                             alignment));

        if (newPtr) {
            ptr_ = newPtr;
            size_ = newSize;
            count_ = newCount;
            return true;
        }

        return false;
    }

    // 释放内存
    void deallocate() {
        if (ptr_) {
            MemoryManager::getInstance().deallocate(ptr_);
            ptr_ = nullptr;
            size_ = 0;
            count_ = 0;
        }
    }

    // 访问器
    T *get() const { return ptr_; }

    T *data() const { return ptr_; }

    size_t size() const { return size_; }

    size_t count() const { return count_; }

    bool empty() const { return ptr_ == nullptr; }

    // 操作符重载
    T &operator[](size_t index) { return ptr_[index]; }

    const T &operator[](size_t index) const { return ptr_[index]; }

    T *operator->() { return ptr_; }

    const T *operator->() const { return ptr_; }

    operator bool() const { return ptr_ != nullptr; }

private:
    T *ptr_ = nullptr;
    size_t size_ = 0;
    size_t count_ = 0;
    bool useSmartAllocation_ = true;
};

#endif // MEMORY_MANAGER_H