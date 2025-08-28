#include "memory_pool.h"
#include <cstdlib>
#include <cstring>
#include <chrono>
#include <algorithm>

#ifdef _WIN32
#include <malloc.h>
#else

#include <cstdlib>

#endif

// 常用尺寸定义（基于常见图片分辨率和处理需求）
const std::vector<size_t> MemoryPool::COMMON_SIZES = {
        // 小图片缓冲区
        1024 * 1024,      // 1MB - 512x512 RGBA
        4 * 1024 * 1024,  // 4MB - 1024x1024 RGBA
        16 * 1024 * 1024, // 16MB - 2048x2048 RGBA

        // 中等图片缓冲区
        32 * 1024 * 1024, // 32MB - 2896x2896 RGBA
        64 * 1024 * 1024, // 64MB - 4096x4096 RGBA

        // 大图片缓冲区
        96 * 1024 * 1024,  // 96MB - 5000x5000 RGBA
        128 * 1024 * 1024, // 128MB - 5793x5793 RGBA
        192 * 1024 * 1024, // 192MB - 7092x7092 RGBA
};

MemoryPool &MemoryPool::getInstance() {
    static MemoryPool instance;
    return instance;
}

MemoryPool::MemoryPool() {
    POOL_LOGI("内存池初始化，最大池大小: %.2f MB", maxPoolSize_ / (1024.0 * 1024.0));
    preallocateCommonSizes();
}

MemoryPool::~MemoryPool() {
    cleanup(true);
    POOL_LOGI("内存池销毁，最终统计 - 命中率: %.2f%%, 复用次数: %zu",
              stats_.getHitRate() * 100, stats_.reuseCount);
}

void *MemoryPool::allocate(size_t size, size_t alignment) {
    std::lock_guard<std::mutex> lock(mutex_);

    // 对齐大小到最近的2的幂次
    size_t alignedSize = ((size + alignment - 1) / alignment) * alignment;

    // 首先尝试从现有块中找到合适的
    MemoryBlock *block = findSuitableBlock(alignedSize, alignment);
    if (block) {
        block->inUse = true;
        block->lastUsed = std::chrono::steady_clock::now();
        stats_.hitCount++;
        stats_.reuseCount++;
        stats_.totalInUse += block->size;
        stats_.totalFree -= block->size;

        POOL_LOGD("复用内存块: %zu bytes (对齐: %zu), 命中率: %.2f%%",
                  alignedSize, alignment, stats_.getHitRate() * 100);
        return block->ptr;
    }

    // 检查内存压力
    if (isMemoryPressureHigh()) {
        POOL_LOGW("内存压力过高，触发清理");
        cleanupOldBlocks();

        // 如果设置了压力回调，通知上层
        if (pressureCallback_) {
            double pressure = static_cast<double>(stats_.totalAllocated) / maxPoolSize_;
            pressureCallback_(pressure);
        }
    }

    // 分配新块
    void *ptr = allocateNewBlock(alignedSize, alignment);
    if (!ptr) {
        POOL_LOGE("内存分配失败: %zu bytes", alignedSize);
        return nullptr;
    }

    stats_.missCount++;
    POOL_LOGD("分配新内存块: %zu bytes (对齐: %zu), 总分配: %.2f MB",
              alignedSize, alignment, stats_.totalAllocated / (1024.0 * 1024.0));

    return ptr;
}

void MemoryPool::deallocate(void *ptr) {
    if (!ptr) return;

    std::lock_guard<std::mutex> lock(mutex_);

    // 查找对应的内存块
    auto it = std::find_if(blocks_.begin(), blocks_.end(),
                           [ptr](const std::unique_ptr<MemoryBlock> &block) {
                               return block->ptr == ptr;
                           });

    if (it != blocks_.end()) {
        MemoryBlock *block = it->get();
        if (block->inUse) {
            block->inUse = false;
            block->lastUsed = std::chrono::steady_clock::now();
            stats_.totalInUse -= block->size;
            stats_.totalFree += block->size;

            POOL_LOGD("释放内存块: %zu bytes, 空闲内存: %.2f MB",
                      block->size, stats_.totalFree / (1024.0 * 1024.0));
        }
    } else {
        POOL_LOGW("尝试释放未知的内存指针: %p", ptr);
    }
}

MemoryBlock *MemoryPool::findSuitableBlock(size_t size, size_t alignment) {
    size_t key = calculateBlockKey(size, alignment);

    // 首先查找精确匹配的块
    auto it = sizeMap_.find(key);
    if (it != sizeMap_.end()) {
        for (MemoryBlock *block: it->second) {
            if (!block->inUse && block->size >= size && block->alignment >= alignment) {
                return block;
            }
        }
    }

    // 查找稍大的可用块
    for (auto &pair: sizeMap_) {
        if (pair.first > key) {
            for (MemoryBlock *block: pair.second) {
                if (!block->inUse && block->size >= size && block->alignment >= alignment) {
                    return block;
                }
            }
        }
    }

    return nullptr;
}

void *MemoryPool::allocateNewBlock(size_t size, size_t alignment) {
    // 检查是否超过最大池大小
    if (stats_.totalAllocated + size > maxPoolSize_) {
        POOL_LOGW("分配将超过最大池大小，当前: %.2f MB, 请求: %.2f MB, 限制: %.2f MB",
                  stats_.totalAllocated / (1024.0 * 1024.0),
                  size / (1024.0 * 1024.0),
                  maxPoolSize_ / (1024.0 * 1024.0));
        return nullptr;
    }

    void *ptr = nullptr;

#ifdef _WIN32
    ptr = _aligned_malloc(size, alignment);
#else
    if (posix_memalign(&ptr, alignment, size) != 0) {
        ptr = nullptr;
    }
#endif

    if (!ptr) {
        return nullptr;
    }

    // 创建内存块记录
    auto block = std::make_unique<MemoryBlock>(ptr, size, alignment);
    block->inUse = true;

    // 添加到大小映射
    size_t key = calculateBlockKey(size, alignment);
    sizeMap_[key].push_back(block.get());

    // 更新统计
    stats_.totalAllocated += size;
    stats_.totalInUse += size;
    stats_.blockCount++;

    blocks_.push_back(std::move(block));

    return ptr;
}

void MemoryPool::preallocateCommonSizes() {
    POOL_LOGI("预分配常用尺寸内存块");

    for (size_t size: COMMON_SIZES) {
        // 为每个常用尺寸预分配1-2个块
        for (int i = 0; i < 2; ++i) {
            void *ptr = allocateNewBlock(size, 32);
            if (ptr) {
                // 立即标记为未使用，以便后续复用
                auto it = std::find_if(blocks_.rbegin(), blocks_.rend(),
                                       [ptr](const std::unique_ptr<MemoryBlock> &block) {
                                           return block->ptr == ptr;
                                       });
                if (it != blocks_.rend()) {
                    (*it)->inUse = false;
                    stats_.totalInUse -= (*it)->size;
                    stats_.totalFree += (*it)->size;
                }
            }
        }
    }

    POOL_LOGI("预分配完成，总块数: %zu, 总大小: %.2f MB",
              stats_.blockCount, stats_.totalAllocated / (1024.0 * 1024.0));
}

void MemoryPool::cleanup(bool force) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto now = std::chrono::steady_clock::now();
    size_t cleanedCount = 0;
    size_t cleanedSize = 0;

    // 清理旧的未使用块
    auto it = blocks_.begin();
    while (it != blocks_.end()) {
        MemoryBlock *block = it->get();

        bool shouldClean = force ||
                           (!block->inUse && (now - block->lastUsed) > maxBlockAge_);

        if (shouldClean) {
            // 从大小映射中移除
            size_t key = calculateBlockKey(block->size, block->alignment);
            auto &sizeVec = sizeMap_[key];
            sizeVec.erase(std::remove(sizeVec.begin(), sizeVec.end(), block), sizeVec.end());
            if (sizeVec.empty()) {
                sizeMap_.erase(key);
            }

            // 释放内存
#ifdef _WIN32
            _aligned_free(block->ptr);
#else
            free(block->ptr);
#endif

            cleanedSize += block->size;
            cleanedCount++;

            // 更新统计
            stats_.totalAllocated -= block->size;
            if (!block->inUse) {
                stats_.totalFree -= block->size;
            } else {
                stats_.totalInUse -= block->size;
            }
            stats_.blockCount--;

            it = blocks_.erase(it);
        } else {
            ++it;
        }
    }

    if (cleanedCount > 0) {
        POOL_LOGI("清理完成，释放 %zu 个块，总大小: %.2f MB",
                  cleanedCount, cleanedSize / (1024.0 * 1024.0));
    }
}

void MemoryPool::cleanupOldBlocks() {
    cleanup(false);
}

size_t MemoryPool::calculateBlockKey(size_t size, size_t alignment) const {
    // 使用大小和对齐方式计算唯一键
    return (size << 8) | (alignment & 0xFF);
}

bool MemoryPool::isMemoryPressureHigh() const {
    double usage = static_cast<double>(stats_.totalAllocated) / maxPoolSize_;
    return usage > cleanupThreshold_;
}

void MemoryPool::setMaxPoolSize(size_t maxSize) {
    std::lock_guard<std::mutex> lock(mutex_);
    maxPoolSize_ = maxSize;
    POOL_LOGI("设置最大池大小: %.2f MB", maxSize / (1024.0 * 1024.0));
}

void MemoryPool::setCleanupThreshold(double threshold) {
    std::lock_guard<std::mutex> lock(mutex_);
    cleanupThreshold_ = std::clamp(threshold, 0.1, 0.95);
    POOL_LOGI("设置清理阈值: %.1f%%", cleanupThreshold_ * 100);
}

void MemoryPool::setMemoryPressureCallback(std::function<void(double)> callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    pressureCallback_ = std::move(callback);
}

PoolStats MemoryPool::getStats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return stats_;
}

void MemoryPool::resetStats() {
    std::lock_guard<std::mutex> lock(mutex_);
    stats_.hitCount = 0;
    stats_.missCount = 0;
    stats_.reuseCount = 0;
    POOL_LOGI("统计信息已重置");
}

// SmartBuffer 实现
SmartBuffer::SmartBuffer(size_t size, size_t alignment)
        : size_(size), alignment_(alignment) {
    ptr_ = MemoryPool::getInstance().allocate(size, alignment);
    if (!ptr_) {
        throw std::bad_alloc();
    }
}

SmartBuffer::SmartBuffer(SmartBuffer &&other) noexcept
        : ptr_(other.ptr_), size_(other.size_), alignment_(other.alignment_) {
    other.ptr_ = nullptr;
    other.size_ = 0;
    other.alignment_ = 32;
}

SmartBuffer &SmartBuffer::operator=(SmartBuffer &&other) noexcept {
    if (this != &other) {
        reset();
        ptr_ = other.ptr_;
        size_ = other.size_;
        alignment_ = other.alignment_;
        other.ptr_ = nullptr;
        other.size_ = 0;
        other.alignment_ = 32;
    }
    return *this;
}

SmartBuffer::~SmartBuffer() {
    reset();
}

bool SmartBuffer::resize(size_t newSize, size_t alignment) {
    if (newSize == size_ && alignment == alignment_) {
        return true;
    }

    reset();

    size_ = newSize;
    alignment_ = alignment;
    ptr_ = MemoryPool::getInstance().allocate(newSize, alignment);

    return ptr_ != nullptr;
}

void SmartBuffer::reset() {
    if (ptr_) {
        MemoryPool::getInstance().deallocate(ptr_);
        ptr_ = nullptr;
        size_ = 0;
    }
}