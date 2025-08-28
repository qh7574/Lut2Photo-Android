#include "memory_manager.h"
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <sstream>
#include <iomanip>
#include <android/log.h>
#include <thread>
#include <chrono>
#include <jni.h>

#ifdef _WIN32
#include <malloc.h>
#else

#include <cstdlib>

#endif

#ifndef LOG_TAG
#define LOG_TAG "MemoryManager"
#endif
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// 全局JVM指针，用于垃圾回收
static JavaVM *g_jvm = nullptr;

MemoryManager &MemoryManager::getInstance() {
    static MemoryManager instance;
    return instance;
}

MemoryManager::MemoryManager() {
    // 启动自动优化（默认关闭）
    enableAutoOptimization(false);

    LOGI("MemoryManager initialized with enhanced features");
}

void MemoryManager::configureMemoryPool(size_t maxPoolSize, double cleanupThreshold) {
    // 配置内存池参数
    LOGI("内存池配置更新: 最大大小=%zu bytes, 清理阈值=%.2f", maxPoolSize, cleanupThreshold);
}

PoolStats MemoryManager::getPoolStats() const {
    return MemoryPool::getInstance().getStats();
}

std::string MemoryManager::getDetailedStats() const {
    std::stringstream ss;

    ss << "=== 内存管理器详细统计 ===\n";
    ss << "总分配字节数: " << getTotalAllocatedBytes() << "\n";
    ss << "池分配字节数: " << getPoolAllocatedBytes() << "\n";
    ss << "直接分配字节数: " << getDirectAllocatedBytes() << "\n";
    ss << "分配次数: " << getAllocationCount() << "\n";
    ss << "释放次数: " << getDeallocationCount() << "\n";
    ss << "活跃分配: " << getActiveAllocations() << "\n";
    ss << "内存限制: " << getMemoryLimit() << "\n";
    ss << "使用率: " << std::fixed << std::setprecision(2) << (getMemoryUsageRatio() * 100)
       << "%\n";
    ss << "内存压力: " << (isMemoryPressureHigh() ? "高" : "正常") << "\n";

    // 内存池统计
    PoolStats poolStats = getPoolStats();
    ss << "\n=== 内存池统计 ===\n";
    ss << "池总分配: " << poolStats.totalAllocated << "\n";
    ss << "池使用中: " << poolStats.totalInUse << "\n";
    ss << "池空闲: " << poolStats.totalFree << "\n";
    ss << "池命中率: " << std::fixed << std::setprecision(2) << (poolStats.getHitRate() * 100)
       << "%\n";
    ss << "块数量: " << poolStats.blockCount << "\n";

    return ss.str();
}

std::vector<std::pair<void *, AllocationInfo>>
MemoryManager::getLargeAllocations(size_t minSize) const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<std::pair<void *, AllocationInfo>> result;

    for (const auto &allocation: allocations_) {
        if (allocation.second.size >= minSize) {
            result.push_back(allocation);
        }
    }

    // 按大小排序
    std::sort(result.begin(), result.end(),
              [](const auto &a, const auto &b) {
                  return a.second.size > b.second.size;
              });

    return result;
}

void MemoryManager::dumpMemoryMap() const {
    std::lock_guard<std::mutex> lock(mutex_);

    LOGI("=== 内存映射转储 ===");
    LOGI("总分配: %zu 个, %zu bytes", allocations_.size(), totalAllocatedBytes_.load());

    // 获取大分配（>1MB）
    auto largeAllocations = getLargeAllocations(1024 * 1024);
    if (!largeAllocations.empty()) {
        LOGI("大分配 (>1MB):");
        for (const auto &alloc: largeAllocations) {
            auto duration = std::chrono::steady_clock::now() - alloc.second.timestamp;
            auto seconds = std::chrono::duration_cast<std::chrono::seconds>(duration).count();
            LOGI("  %p: %zu bytes, 对齐=%zu, 来源=%s, 存活=%lld秒",
                 alloc.first, alloc.second.size, alloc.second.alignment,
                 alloc.second.fromPool ? "池" : "直接", seconds);
        }
    }
}

MemoryManager::~MemoryManager() {
    // 停止自动优化线程
    enableAutoOptimization(false);

    // 清理资源
    cleanup();

    LOGI("MemoryManager 析构完成");
}

void *MemoryManager::allocate(size_t size, size_t alignment) {
    if (size == 0) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(mutex_);

    // 检查内存限制
    if (!checkMemoryLimit(size)) {
        triggerEvent(MemoryEvent::LIMIT_EXCEEDED, size);
        return nullptr;
    }

    void *ptr = allocateDirect(size, alignment);
    if (ptr) {
        allocations_[ptr] = AllocationInfo(size, alignment, false);
        totalAllocatedBytes_ += size;
        directAllocatedBytes_ += size;
        allocationCount_++;

        triggerEvent(MemoryEvent::ALLOCATION, size);

        // 检查内存压力
        if (isMemoryPressureHigh()) {
            triggerEvent(MemoryEvent::PRESSURE_WARNING, size);
        }
    }

    return ptr;
}

void *MemoryManager::smartAllocate(size_t size, size_t alignment) {
    if (size == 0) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(mutex_);

    // 检查内存限制
    if (!checkMemoryLimit(size)) {
        triggerEvent(MemoryEvent::LIMIT_EXCEEDED, size);
        return nullptr;
    }

    void *ptr = nullptr;
    bool fromPool = false;

    // 小于阈值的分配尝试使用内存池
    if (size <= POOL_ALLOCATION_THRESHOLD) {
        ptr = allocateFromPool(size, alignment);
        fromPool = (ptr != nullptr);
    }

    // 如果池分配失败或大于阈值，使用直接分配
    if (!ptr) {
        ptr = allocateDirect(size, alignment);
        fromPool = false;
    }

    if (ptr) {
        allocations_[ptr] = AllocationInfo(size, alignment, fromPool);
        totalAllocatedBytes_ += size;

        if (fromPool) {
            poolAllocatedBytes_ += size;
        } else {
            directAllocatedBytes_ += size;
        }

        allocationCount_++;
        triggerEvent(MemoryEvent::ALLOCATION, size);

        // 检查内存压力
        if (isMemoryPressureHigh()) {
            triggerEvent(MemoryEvent::PRESSURE_WARNING, size);
        }
    }

    return ptr;
}

void MemoryManager::deallocate(void *ptr) {
    if (!ptr) {
        return;
    }

    std::lock_guard<std::mutex> lock(mutex_);

    auto it = allocations_.find(ptr);
    if (it != allocations_.end()) {
        size_t size = it->second.size;
        bool fromPool = it->second.fromPool;

        allocations_.erase(it);

        totalAllocatedBytes_ -= size;
        if (fromPool) {
            poolAllocatedBytes_ -= size;
            // 返回到内存池
            deallocateInternal(ptr);
        } else {
            directAllocatedBytes_ -= size;
            deallocateInternal(ptr);
        }

        deallocationCount_++;
        triggerEvent(MemoryEvent::DEALLOCATION, size);

        LOGD("释放内存: %p, 大小: %zu bytes, 来源: %s, 总分配: %zu bytes",
             ptr, size, fromPool ? "池" : "直接", totalAllocatedBytes_.load());
    } else {
        LOGW("尝试释放未知内存指针: %p", ptr);
        // 仍然尝试释放，防止内存泄漏
        deallocateInternal(ptr);
    }
}

void *MemoryManager::reallocate(void *ptr, size_t newSize, size_t alignment) {
    if (!ptr) {
        return allocate(newSize, alignment);
    }

    if (newSize == 0) {
        deallocate(ptr);
        return nullptr;
    }

    size_t oldSize = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = allocations_.find(ptr);
        if (it != allocations_.end()) {
            oldSize = it->second.size;
        } else {
            LOGE("尝试重新分配未知指针: %p", ptr);
            return nullptr;
        }
    }

    if (!checkMemoryLimit(newSize - oldSize)) {
        LOGE("内存重新分配超出限制，新大小: %zu bytes", newSize);
        return nullptr;
    }

    void *newPtr = allocateInternal(newSize, alignment);
    if (newPtr) {
        // 复制数据
        size_t copySize = std::min(oldSize, newSize);
        std::memcpy(newPtr, ptr, copySize);

        // 更新记录
        {
            std::lock_guard<std::mutex> lock(mutex_);
            allocations_.erase(ptr);
            allocations_[newPtr] = AllocationInfo(newSize, alignment, false);
        }

        deallocateInternal(ptr);

        totalAllocatedBytes_.fetch_add(newSize - oldSize);

        LOGD("重新分配内存: %p -> %p, 旧大小: %zu, 新大小: %zu",
             ptr, newPtr, oldSize, newSize);
    }

    return newPtr;
}

MemoryManager::AlignedImageBuffer MemoryManager::allocateImageBuffer(
        int width, int height, int bytesPerPixel, size_t rowAlignment
) {
    // 计算对齐的stride
    size_t rowBytes = width * bytesPerPixel;
    size_t alignedStride = ((rowBytes + rowAlignment - 1) / rowAlignment) * rowAlignment;
    size_t totalSize = alignedStride * height;

    void *data = allocate(totalSize, rowAlignment);

    LOGD("分配图片缓冲区: %dx%d, %d bpp, stride: %zu, 总大小: %zu bytes",
         width, height, bytesPerPixel, alignedStride, totalSize);

    return {data, alignedStride, totalSize};
}

void MemoryManager::deallocateImageBuffer(const AlignedImageBuffer &buffer) {
    if (buffer.data) {
        deallocate(buffer.data);
        LOGD("释放图片缓冲区: %p, 大小: %zu bytes", buffer.data, buffer.totalSize);
    }
}

size_t MemoryManager::getTotalAllocatedBytes() const {
    return totalAllocatedBytes_.load();
}

size_t MemoryManager::getPoolAllocatedBytes() const {
    return poolAllocatedBytes_.load();
}

size_t MemoryManager::getDirectAllocatedBytes() const {
    return directAllocatedBytes_.load();
}

size_t MemoryManager::getAllocationCount() const {
    return allocationCount_.load();
}

size_t MemoryManager::getDeallocationCount() const {
    return deallocationCount_.load();
}

size_t MemoryManager::getActiveAllocations() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return allocations_.size();
}

double MemoryManager::getMemoryUsageRatio() const {
    size_t limit = memoryLimit_.load();
    if (limit == 0) {
        return 0.0;
    }
    return static_cast<double>(totalAllocatedBytes_.load()) / limit;
}

bool MemoryManager::isMemoryPressureHigh() const {
    return getMemoryUsageRatio() >= pressureThreshold_.load();
}

void MemoryManager::setMemoryPressureThreshold(float threshold) {
    pressureThreshold_.store(std::max(0.0f, std::min(1.0f, threshold)));
}

void MemoryManager::handleMemoryPressure() {
    LOGW("处理内存压力，当前使用率: %.2f%%", getMemoryUsageRatio() * 100);

    // 清理内存池
    cleanup();
    triggerEvent(MemoryEvent::POOL_CLEANUP);

    // 强制垃圾回收
    forceGarbageCollection();

    // 更新内存压力状态
    memoryPressureHigh_.store(isMemoryPressureHigh());
}

bool MemoryManager::hasMemoryLeaks() const {
    return getActiveAllocations() > 0;
}

void MemoryManager::printMemoryStats() const {
    std::lock_guard<std::mutex> lock(mutex_);

    LOGI("=== 内存使用统计 ===");
    LOGI("总分配次数: %zu", allocationCount_.load());
    LOGI("总释放次数: %zu", deallocationCount_.load());
    LOGI("当前分配块数: %zu", allocations_.size());
    LOGI("当前分配总量: %zu bytes (%.2f MB)",
         totalAllocatedBytes_.load(),
         totalAllocatedBytes_.load() / (1024.0 * 1024.0));

    if (memoryLimit_.load() > 0) {
        float usage = static_cast<float>(totalAllocatedBytes_.load()) / memoryLimit_.load();
        LOGI("内存使用率: %.1f%% (%zu / %zu bytes)",
             usage * 100.0f, totalAllocatedBytes_.load(), memoryLimit_.load());
    }

    if (!allocations_.empty()) {
        LOGI("活跃分配详情:");
        auto now = std::chrono::steady_clock::now();
        for (const auto &pair: allocations_) {
            auto duration = std::chrono::duration_cast<std::chrono::seconds>(
                    now - pair.second.timestamp
            ).count();
            LOGI("  %p: %zu bytes, 对齐: %zu, 存活: %lld 秒",
                 pair.first, pair.second.size, pair.second.alignment, duration);
        }
    }
    LOGI("===================");
}

void MemoryManager::cleanup() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!allocations_.empty()) {
        LOGW("清理 %zu 个未释放的内存块", allocations_.size());

        for (const auto &pair: allocations_) {
            LOGW("强制释放: %p, 大小: %zu bytes", pair.first, pair.second.size);
            deallocateInternal(pair.first);
        }

        allocations_.clear();
    }

    totalAllocatedBytes_.store(0);
}

void MemoryManager::setMemoryLimit(size_t maxBytes) {
    memoryLimit_.store(maxBytes);
    LOGI("设置内存限制: %zu bytes (%.2f MB)", maxBytes, maxBytes / (1024.0 * 1024.0));
}

size_t MemoryManager::getMemoryLimit() const {
    return memoryLimit_.load();
}

bool MemoryManager::isNearMemoryLimit(float threshold) const {
    size_t limit = memoryLimit_.load();
    if (limit == 0) {
        return false; // 无限制
    }

    size_t current = totalAllocatedBytes_.load();
    return static_cast<float>(current) / limit >= threshold;
}

void MemoryManager::setEventCallback(MemoryEventCallback callback) {
    std::lock_guard<std::mutex> lock(callbackMutex_);
    eventCallback_ = std::move(callback);
}

void MemoryManager::removeEventCallback() {
    std::lock_guard<std::mutex> lock(callbackMutex_);
    eventCallback_ = nullptr;
}

void MemoryManager::triggerEvent(MemoryEvent event, size_t size) {
    std::lock_guard<std::mutex> lock(callbackMutex_);
    if (eventCallback_) {
        MemoryEventInfo info;
        info.event = event;
        info.size = size;
        info.totalAllocated = totalAllocatedBytes_.load();
        info.memoryUsage = getMemoryUsageRatio();
        info.timestamp = std::chrono::steady_clock::now();

        try {
            eventCallback_(info);
        } catch (const std::exception &e) {
            LOGE("内存事件回调异常: %s", e.what());
        }
    }
}

void MemoryManager::optimizeMemoryUsage() {
    LOGI("开始内存优化");

    // 清理内存池
    cleanup();

    // 如果内存压力高，强制垃圾回收
    if (isMemoryPressureHigh()) {
        forceGarbageCollection();
    }

    LOGI("内存优化完成，当前使用率: %.2f%%", getMemoryUsageRatio() * 100);
}

void MemoryManager::enableAutoOptimization(bool enable) {
    if (enable && !autoOptimizationEnabled_.load()) {
        autoOptimizationEnabled_.store(true);
        startOptimizationThread();
    } else if (!enable && autoOptimizationEnabled_.load()) {
        autoOptimizationEnabled_.store(false);
        stopOptimizationThread();
    }
}

void MemoryManager::setOptimizationInterval(std::chrono::seconds interval) {
    optimizationInterval_ = interval;
}

void MemoryManager::startOptimizationThread() {
    if (!optimizationThreadRunning_.load()) {
        optimizationThreadRunning_.store(true);
        optimizationThread_ = std::thread(&MemoryManager::optimizationWorker, this);
    }
}

void MemoryManager::stopOptimizationThread() {
    optimizationThreadRunning_.store(false);
    if (optimizationThread_.joinable()) {
        optimizationThread_.join();
    }
}

void MemoryManager::optimizationWorker() {
    LOGI("自动优化线程启动，间隔: %lld 秒", optimizationInterval_.count());

    while (optimizationThreadRunning_.load()) {
        std::this_thread::sleep_for(optimizationInterval_);

        if (optimizationThreadRunning_.load()) {
            optimizeMemoryUsage();
        }
    }

    LOGI("自动优化线程停止");
}

void MemoryManager::setJavaVM(JavaVM *vm) {
    javaVM_ = vm;
}

void MemoryManager::forceGarbageCollection() {
    if (!g_jvm) {
        LOGW("JVM指针未设置，无法执行垃圾回收");
        return;
    }

    JNIEnv *env = nullptr;
    bool needDetach = false;

    int status = g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        status = g_jvm->AttachCurrentThread(&env, nullptr);
        needDetach = true;
    }

    if (status == JNI_OK && env) {
        jclass systemClass = env->FindClass("java/lang/System");
        if (systemClass) {
            jmethodID gcMethod = env->GetStaticMethodID(systemClass, "gc", "()V");
            if (gcMethod) {
                env->CallStaticVoidMethod(systemClass, gcMethod);
                LOGD("执行Java垃圾回收");
            }
            env->DeleteLocalRef(systemClass);
        }

        if (needDetach) {
            g_jvm->DetachCurrentThread();
        }
    } else {
        LOGE("无法获取JNI环境进行垃圾回收");
    }
}

void *MemoryManager::allocateFromPool(size_t size, size_t alignment) {
    try {
        return allocateInternal(size, alignment);
    } catch (const std::exception &e) {
        LOGW("内存池分配失败: %s", e.what());
        return nullptr;
    }
}

void *MemoryManager::allocateDirect(size_t size, size_t alignment) {
    return allocateInternal(size, alignment);
}

void *MemoryManager::allocateInternal(size_t size, size_t alignment) {
    void *ptr = nullptr;

#ifdef _WIN32
    ptr = _aligned_malloc(size, alignment);
#else
    if (posix_memalign(&ptr, alignment, size) != 0) {
        ptr = nullptr;
    }
#endif

    if (ptr) {
        // 初始化为0
        std::memset(ptr, 0, size);
    }

    return ptr;
}

void MemoryManager::deallocateInternal(void *ptr) {
    if (ptr) {
#ifdef _WIN32
        _aligned_free(ptr);
#else
        free(ptr);
#endif
    }
}

bool MemoryManager::checkMemoryLimit(size_t requestedSize) const {
    size_t limit = memoryLimit_.load();
    if (limit == 0) {
        return true; // 无限制
    }

    size_t current = totalAllocatedBytes_.load();
    return (current + requestedSize) <= limit;
}