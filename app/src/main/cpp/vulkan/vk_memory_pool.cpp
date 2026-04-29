#include "vk_memory_pool.h"
#include "vk_context.h"
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "VkMemoryPool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace vulkan {

VkMemoryPool::VkMemoryPool(VkContext* context)
    : context_(context) {
    LOGI("VkMemoryPool created");
}

VkMemoryPool::~VkMemoryPool() {
    cleanup();
    LOGI("VkMemoryPool destroyed");
}

VkMemoryPool::BufferAllocation VkMemoryPool::allocateBuffer(
    VkDeviceSize size,
    VkBufferUsageFlags usage,
    VkMemoryPropertyFlags properties
) {
    std::lock_guard<std::mutex> lock(mutex_);

    BufferAllocation allocation = {};
    allocation.size = size;

    // 尝试从缓存获取
    VkBuffer cachedBuffer = getCachedBuffer(size, usage);
    if (cachedBuffer != VK_NULL_HANDLE) {
        allocation.buffer = cachedBuffer;
        LOGD("Reused cached buffer");
    } else {
        // 创建新缓冲区
        VkBufferCreateInfo bufferInfo = {};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = size;
        bufferInfo.usage = usage;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

        VkResult result = vkCreateBuffer(
            context_->getDevice(), &bufferInfo, nullptr, &allocation.buffer
        );

        if (result != VK_SUCCESS) {
            LOGE("Failed to create buffer: %d", result);
            return {};
        }
    }

    // 获取内存需求
    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(
        context_->getDevice(), allocation.buffer, &memRequirements
    );

    // 分配内存
    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = findMemoryType(
        memRequirements.memoryTypeBits, properties
    );

    VkResult result = vkAllocateMemory(
        context_->getDevice(), &allocInfo, nullptr, &allocation.memory
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate buffer memory: %d", result);
        vkDestroyBuffer(context_->getDevice(), allocation.buffer, nullptr);
        return {};
    }

    // 绑定内存
    result = vkBindBufferMemory(
        context_->getDevice(), allocation.buffer, allocation.memory, 0
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to bind buffer memory: %d", result);
        vkFreeMemory(context_->getDevice(), allocation.memory, nullptr);
        vkDestroyBuffer(context_->getDevice(), allocation.buffer, nullptr);
        return {};
    }

    // 映射内存（如果需要）
    if (properties & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) {
        result = vkMapMemory(
            context_->getDevice(), allocation.memory, 0, size, 0,
            &allocation.mappedData
        );

        if (result != VK_SUCCESS) {
            LOGW("Failed to map buffer memory: %d", result);
            allocation.mappedData = nullptr;
        }
    }

    // 更新统计
    stats_.totalAllocated += memRequirements.size;
    stats_.totalUsed += size;
    stats_.allocationCount++;

    LOGD("Allocated buffer: size=%zu, usage=%d", size, usage);
    return allocation;
}

void VkMemoryPool::freeBuffer(BufferAllocation& allocation) {
    if (allocation.buffer == VK_NULL_HANDLE) {
        return;
    }

    std::lock_guard<std::mutex> lock(mutex_);

    // 取消映射
    if (allocation.mappedData != nullptr) {
        vkUnmapMemory(context_->getDevice(), allocation.memory);
        allocation.mappedData = nullptr;
    }

    // 添加到缓存（如果大小合适）
    if (allocation.size <= 1024 * 1024) { // 缓存小于1MB的缓冲区
        cacheBuffer(allocation.buffer, allocation.memory, allocation.size);
    } else {
        // 直接释放
        vkDestroyBuffer(context_->getDevice(), allocation.buffer, nullptr);
        vkFreeMemory(context_->getDevice(), allocation.memory, nullptr);
    }

    // 更新统计
    stats_.totalUsed -= allocation.size;
    stats_.allocationCount--;

    allocation.buffer = VK_NULL_HANDLE;
    allocation.memory = VK_NULL_HANDLE;
    allocation.size = 0;

    LOGD("Freed buffer");
}

VkMemoryPool::ImageAllocation VkMemoryPool::allocateImage(
    VkExtent2D extent,
    VkFormat format,
    VkImageUsageFlags usage,
    VkMemoryPropertyFlags properties
) {
    std::lock_guard<std::mutex> lock(mutex_);

    ImageAllocation allocation = {};

    // 创建图像
    VkImageCreateInfo imageInfo = {};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.extent.width = extent.width;
    imageInfo.extent.height = extent.height;
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = format;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = usage;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkResult result = vkCreateImage(
        context_->getDevice(), &imageInfo, nullptr, &allocation.image
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create image: %d", result);
        return {};
    }

    // 获取内存需求
    VkMemoryRequirements memRequirements;
    vkGetImageMemoryRequirements(
        context_->getDevice(), allocation.image, &memRequirements
    );

    allocation.size = memRequirements.size;

    // 分配内存
    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = findMemoryType(
        memRequirements.memoryTypeBits, properties
    );

    result = vkAllocateMemory(
        context_->getDevice(), &allocInfo, nullptr, &allocation.memory
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate image memory: %d", result);
        vkDestroyImage(context_->getDevice(), allocation.image, nullptr);
        return {};
    }

    // 绑定内存
    result = vkBindImageMemory(
        context_->getDevice(), allocation.image, allocation.memory, 0
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to bind image memory: %d", result);
        vkFreeMemory(context_->getDevice(), allocation.memory, nullptr);
        vkDestroyImage(context_->getDevice(), allocation.image, nullptr);
        return {};
    }

    // 更新统计
    stats_.totalAllocated += memRequirements.size;
    stats_.totalUsed += memRequirements.size;
    stats_.allocationCount++;

    LOGD("Allocated image: %dx%d, format=%d, size=%zu",
         extent.width, extent.height, format, memRequirements.size);
    return allocation;
}

void VkMemoryPool::freeImage(ImageAllocation& allocation) {
    if (allocation.image == VK_NULL_HANDLE) {
        return;
    }

    std::lock_guard<std::mutex> lock(mutex_);

    vkDestroyImage(context_->getDevice(), allocation.image, nullptr);
    vkFreeMemory(context_->getDevice(), allocation.memory, nullptr);

    // 更新统计
    stats_.totalUsed -= allocation.size;
    stats_.allocationCount--;

    allocation.image = VK_NULL_HANDLE;
    allocation.memory = VK_NULL_HANDLE;
    allocation.size = 0;

    LOGD("Freed image");
}

VkImageView VkMemoryPool::createImageView(
    VkImage image,
    VkFormat format,
    VkImageAspectFlags aspectFlags
) {
    VkImageViewCreateInfo viewInfo = {};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange.aspectMask = aspectFlags;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;

    VkImageView imageView;
    VkResult result = vkCreateImageView(
        context_->getDevice(), &viewInfo, nullptr, &imageView
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create image view: %d", result);
        return VK_NULL_HANDLE;
    }

    return imageView;
}

void VkMemoryPool::cleanup() {
    std::lock_guard<std::mutex> lock(mutex_);

    // 清理缓存的缓冲区
    for (auto& cached : bufferCache_) {
        vkDestroyBuffer(context_->getDevice(), cached.buffer, nullptr);
        vkFreeMemory(context_->getDevice(), cached.memory, nullptr);
    }
    bufferCache_.clear();

    // 重置统计
    stats_ = {};

    LOGI("VkMemoryPool cleaned up");
}

VkMemoryPool::MemoryStats VkMemoryPool::getStats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return stats_;
}

uint32_t VkMemoryPool::findMemoryType(
    uint32_t typeFilter,
    VkMemoryPropertyFlags properties
) const {
    return context_->findMemoryType(typeFilter, properties);
}

VkDeviceMemory VkMemoryPool::allocateMemoryBlock(
    VkDeviceSize size,
    uint32_t memoryTypeIndex
) {
    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = size;
    allocInfo.memoryTypeIndex = memoryTypeIndex;

    VkDeviceMemory memory;
    VkResult result = vkAllocateMemory(
        context_->getDevice(), &allocInfo, nullptr, &memory
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate memory block: %d", result);
        return VK_NULL_HANDLE;
    }

    memoryBlocks_.push_back({memory, size, 0, nullptr});
    return memory;
}

VkBuffer VkMemoryPool::getCachedBuffer(VkDeviceSize size, VkBufferUsageFlags usage) {
    // 查找合适的缓存缓冲区
    for (auto it = bufferCache_.begin(); it != bufferCache_.end(); ++it) {
        if (it->size >= size && it->size <= size * 2) {
            VkBuffer buffer = it->buffer;
            bufferCache_.erase(it);
            return buffer;
        }
    }
    return VK_NULL_HANDLE;
}

void VkMemoryPool::cacheBuffer(
    VkBuffer buffer,
    VkDeviceMemory memory,
    VkDeviceSize size
) {
    // 限制缓存大小
    const size_t maxCacheSize = 16;
    if (bufferCache_.size() >= maxCacheSize) {
        // 移除最旧的缓存
        auto& oldest = bufferCache_.front();
        vkDestroyBuffer(context_->getDevice(), oldest.buffer, nullptr);
        vkFreeMemory(context_->getDevice(), oldest.memory, nullptr);
        bufferCache_.erase(bufferCache_.begin());
    }

    bufferCache_.push_back({buffer, memory, size, 0});
    LOGD("Cached buffer: size=%zu", size);
}

} // namespace vulkan
