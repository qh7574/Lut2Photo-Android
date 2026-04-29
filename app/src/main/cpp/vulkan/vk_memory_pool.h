#ifndef VK_MEMORY_POOL_H
#define VK_MEMORY_POOL_H

#include <vulkan/vulkan.h>
#include <vector>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace vulkan {

class VkContext;

/**
 * Vulkan内存池管理类
 * 提供高效的内存分配和复用机制
 */
class VkMemoryPool {
public:
    /**
     * 构造函数
     * @param context Vulkan上下文
     */
    explicit VkMemoryPool(VkContext* context);
    ~VkMemoryPool();

    // 禁止拷贝
    VkMemoryPool(const VkMemoryPool&) = delete;
    VkMemoryPool& operator=(const VkMemoryPool&) = delete;

    /**
     * 分配缓冲区内存
     * @param size 缓冲区大小
     * @param usage 缓冲区用途
     * @param properties 内存属性
     * @return 缓冲区和设备内存
     */
    struct BufferAllocation {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize size = 0;
        void* mappedData = nullptr;
    };

    BufferAllocation allocateBuffer(
        VkDeviceSize size,
        VkBufferUsageFlags usage,
        VkMemoryPropertyFlags properties
    );

    /**
     * 释放缓冲区
     */
    void freeBuffer(BufferAllocation& allocation);

    /**
     * 分配图像内存
     */
    struct ImageAllocation {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize size = 0;
    };

    ImageAllocation allocateImage(
        VkExtent2D extent,
        VkFormat format,
        VkImageUsageFlags usage,
        VkMemoryPropertyFlags properties
    );

    /**
     * 释放图像
     */
    void freeImage(ImageAllocation& allocation);

    /**
     * 创建图像视图
     */
    VkImageView createImageView(
        VkImage image,
        VkFormat format,
        VkImageAspectFlags aspectFlags
    );

    /**
     * 清理所有缓存的资源
     */
    void cleanup();

    /**
     * 获取内存使用统计
     */
    struct MemoryStats {
        VkDeviceSize totalAllocated = 0;
        VkDeviceSize totalUsed = 0;
        uint32_t allocationCount = 0;
    };

    MemoryStats getStats() const;

private:
    VkContext* context_;
    mutable std::mutex mutex_;

    // 内存分配记录
    struct MemoryBlock {
        VkDeviceMemory memory;
        VkDeviceSize size;
        VkDeviceSize used;
        void* mappedData;
    };

    std::vector<MemoryBlock> memoryBlocks_;

    // 缓冲区缓存
    struct CachedBuffer {
        VkBuffer buffer;
        VkDeviceMemory memory;
        VkDeviceSize size;
        uint64_t lastUsedFrame;
    };

    std::vector<CachedBuffer> bufferCache_;

    // 统计信息
    MemoryStats stats_;

    /**
     * 查找合适的内存类型
     */
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const;

    /**
     * 查找或分配内存块
     */
    VkDeviceMemory allocateMemoryBlock(VkDeviceSize size, uint32_t memoryTypeIndex);

    /**
     * 从缓存中获取缓冲区
     */
    VkBuffer getCachedBuffer(VkDeviceSize size, VkBufferUsageFlags usage);

    /**
     * 将缓冲区添加到缓存
     */
    void cacheBuffer(VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize size);
};

} // namespace vulkan

#endif // VK_MEMORY_POOL_H
