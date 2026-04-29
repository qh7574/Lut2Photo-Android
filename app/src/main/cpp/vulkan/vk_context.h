#ifndef VK_CONTEXT_H
#define VK_CONTEXT_H

#include <vulkan/vulkan.h>
#include <vector>
#include <string>
#include <memory>

namespace vulkan {

/**
 * Vulkan上下文管理类
 * 负责Vulkan实例、设备、队列的初始化和管理
 */
class VkContext {
public:
    VkContext();
    ~VkContext();

    // 禁止拷贝
    VkContext(const VkContext&) = delete;
    VkContext& operator=(const VkContext&) = delete;

    /**
     * 初始化Vulkan上下文
     * @return 是否初始化成功
     */
    bool initialize();

    /**
     * 清理资源
     */
    void cleanup();

    /**
     * 检查是否已初始化
     */
    bool isInitialized() const { return initialized_; }

    // Getters
    VkInstance getInstance() const { return instance_; }
    VkPhysicalDevice getPhysicalDevice() const { return physicalDevice_; }
    VkDevice getDevice() const { return device_; }
    VkQueue getComputeQueue() const { return computeQueue_; }
    VkQueue getTransferQueue() const { return transferQueue_; }
    VkCommandPool getCommandPool() const { return commandPool_; }
    uint32_t getComputeQueueFamily() const { return computeQueueFamily_; }
    uint32_t getTransferQueueFamily() const { return transferQueueFamily_; }

    /**
     * 获取设备属性
     */
    const VkPhysicalDeviceProperties& getDeviceProperties() const { return deviceProperties_; }
    const VkPhysicalDeviceMemoryProperties& getMemoryProperties() const { return memoryProperties_; }

    /**
     * 查找合适的内存类型
     * @param typeFilter 内存类型过滤器
     * @param properties 内存属性要求
     * @return 内存类型索引
     */
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const;

    /**
     * 检查设备是否支持Vulkan
     */
    static bool isVulkanSupported();

    /**
     * 获取最大纹理尺寸
     */
    uint32_t getMaxImageDimension2D() const;

private:
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue computeQueue_ = VK_NULL_HANDLE;
    VkQueue transferQueue_ = VK_NULL_HANDLE;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;

    uint32_t computeQueueFamily_ = 0;
    uint32_t transferQueueFamily_ = 0;

    VkPhysicalDeviceProperties deviceProperties_;
    VkPhysicalDeviceMemoryProperties memoryProperties_;

    bool initialized_ = false;

    // 调试回调
    VkDebugUtilsMessengerEXT debugMessenger_ = VK_NULL_HANDLE;

    /**
     * 创建Vulkan实例
     */
    bool createInstance();

    /**
     * 设置调试回调
     */
    bool setupDebugCallback();

    /**
     * 选择物理设备
     */
    bool selectPhysicalDevice();

    /**
     * 创建逻辑设备
     */
    bool createLogicalDevice();

    /**
     * 创建命令池
     */
    bool createCommandPool();

    /**
     * 查找队列族
     */
    bool findQueueFamilies(VkPhysicalDevice device);

    /**
     * 检查设备扩展支持
     */
    bool checkDeviceExtensionSupport(VkPhysicalDevice device);

    /**
     * 验证层支持检查
     */
    bool checkValidationLayerSupport();

    /**
     * 获取所需的实例扩展
     */
    std::vector<const char*> getRequiredInstanceExtensions();

    /**
     * 获取所需的设备扩展
     */
    std::vector<const char*> getRequiredDeviceExtensions();
};

} // namespace vulkan

#endif // VK_CONTEXT_H
