#include "vk_context.h"
#include <android/log.h>
#include <set>
#include <stdexcept>

#define LOG_TAG "VkContext"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// 调试回调函数
static VKAPI_ATTR VkBool32 VKAPI_CALL debugCallbackFunc(
    VkDebugUtilsMessageSeverityFlagBitsEXT severity,
    VkDebugUtilsMessageTypeFlagsEXT type,
    const VkDebugUtilsMessengerCallbackDataEXT* pCallbackData,
    void* pUserData
) {
    if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) {
        LOGE("Validation Error: %s", pCallbackData->pMessage);
    } else if (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) {
        LOGW("Validation Warning: %s", pCallbackData->pMessage);
    }
    return VK_FALSE;
}

#ifdef NDEBUG
const bool enableValidationLayers = false;
#else
const bool enableValidationLayers = true;
#endif

const std::vector<const char*> validationLayers = {
    "VK_LAYER_KHRONOS_validation"
};

const std::vector<const char*> deviceExtensions = {
    VK_KHR_SWAPCHAIN_EXTENSION_NAME
};

namespace vulkan {

VkContext::VkContext() = default;

VkContext::~VkContext() {
    cleanup();
}

bool VkContext::initialize() {
    if (initialized_) {
        LOGW("VkContext already initialized");
        return true;
    }

    LOGI("Initializing Vulkan context...");

    // 检查Vulkan支持
    if (!isVulkanSupported()) {
        LOGE("Vulkan is not supported on this device");
        return false;
    }

    // 创建实例
    if (!createInstance()) {
        LOGE("Failed to create Vulkan instance");
        return false;
    }

    // 设置调试回调
    if (enableValidationLayers) {
        if (!setupDebugCallback()) {
            LOGW("Failed to setup debug callback, continuing without validation");
        }
    }

    // 选择物理设备
    if (!selectPhysicalDevice()) {
        LOGE("Failed to select physical device");
        cleanup();
        return false;
    }

    // 创建逻辑设备
    if (!createLogicalDevice()) {
        LOGE("Failed to create logical device");
        cleanup();
        return false;
    }

    // 创建命令池
    if (!createCommandPool()) {
        LOGE("Failed to create command pool");
        cleanup();
        return false;
    }

    initialized_ = true;
    LOGI("Vulkan context initialized successfully");
    LOGI("  Device: %s", deviceProperties_.deviceName);
    LOGI("  API Version: %d.%d.%d",
         VK_VERSION_MAJOR(deviceProperties_.apiVersion),
         VK_VERSION_MINOR(deviceProperties_.apiVersion),
         VK_VERSION_PATCH(deviceProperties_.apiVersion));
    LOGI("  Max Image Dimension 2D: %d", deviceProperties_.limits.maxImageDimension2D);

    return true;
}

void VkContext::cleanup() {
    if (!initialized_) {
        return;
    }

    LOGI("Cleaning up Vulkan context...");

    if (commandPool_ != VK_NULL_HANDLE) {
        vkDestroyCommandPool(device_, commandPool_, nullptr);
        commandPool_ = VK_NULL_HANDLE;
    }

    if (device_ != VK_NULL_HANDLE) {
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }

    if (debugMessenger_ != VK_NULL_HANDLE) {
        auto func = (PFN_vkDestroyDebugUtilsMessengerEXT)
            vkGetInstanceProcAddr(instance_, "vkDestroyDebugUtilsMessengerEXT");
        if (func != nullptr) {
            func(instance_, debugMessenger_, nullptr);
        }
        debugMessenger_ = VK_NULL_HANDLE;
    }

    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }

    initialized_ = false;
    LOGI("Vulkan context cleaned up");
}

bool VkContext::createInstance() {
    if (enableValidationLayers && !checkValidationLayerSupport()) {
        LOGW("Validation layers requested but not available, disabling");
    }

    VkApplicationInfo appInfo = {};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Lut2Photo";
    appInfo.applicationVersion = VK_MAKE_VERSION(3, 3, 0);
    appInfo.pEngineName = "Lut2Photo Engine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    auto extensions = getRequiredInstanceExtensions();
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();

    if (enableValidationLayers && checkValidationLayerSupport()) {
        createInfo.enabledLayerCount = static_cast<uint32_t>(validationLayers.size());
        createInfo.ppEnabledLayerNames = validationLayers.data();
    } else {
        createInfo.enabledLayerCount = 0;
    }

    VkResult result = vkCreateInstance(&createInfo, nullptr, &instance_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create Vulkan instance: %d", result);
        return false;
    }

    LOGI("Vulkan instance created successfully");
    return true;
}

bool VkContext::setupDebugCallback() {
    auto func = (PFN_vkCreateDebugUtilsMessengerEXT)
        vkGetInstanceProcAddr(instance_, "vkCreateDebugUtilsMessengerEXT");
    if (func == nullptr) {
        LOGW("vkCreateDebugUtilsMessengerEXT not found");
        return false;
    }

    VkDebugUtilsMessengerCreateInfoEXT createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
    createInfo.messageSeverity =
        VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
        VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
    createInfo.messageType =
        VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
        VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
        VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    createInfo.pfnUserCallback = debugCallbackFunc;

    VkResult result = func(instance_, &createInfo, nullptr, &debugMessenger_);
    if (result != VK_SUCCESS) {
        LOGW("Failed to create debug messenger: %d", result);
        return false;
    }

    LOGI("Debug callback setup successfully");
    return true;
}

bool VkContext::selectPhysicalDevice() {
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr);

    if (deviceCount == 0) {
        LOGE("No Vulkan-capable devices found");
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data());

    // 选择第一个合适的设备
    for (const auto& device : devices) {
        VkPhysicalDeviceProperties deviceProperties;
        vkGetPhysicalDeviceProperties(device, &deviceProperties);

        LOGI("Checking device: %s", deviceProperties.deviceName);

        // 检查队列族
        if (!findQueueFamilies(device)) {
            continue;
        }

        // 检查设备扩展
        if (!checkDeviceExtensionSupport(device)) {
            continue;
        }

        // 获取设备属性和内存属性
        vkGetPhysicalDeviceProperties(device, &deviceProperties_);
        vkGetPhysicalDeviceMemoryProperties(device, &memoryProperties_);

        physicalDevice_ = device;
        LOGI("Selected physical device: %s", deviceProperties_.deviceName);
        return true;
    }

    LOGE("No suitable physical device found");
    return false;
}

bool VkContext::createLogicalDevice() {
    std::vector<VkDeviceQueueCreateInfo> queueCreateInfos;
    std::set<uint32_t> uniqueQueueFamilies = {computeQueueFamily_, transferQueueFamily_};

    float queuePriority = 1.0f;
    for (uint32_t queueFamily : uniqueQueueFamilies) {
        VkDeviceQueueCreateInfo queueCreateInfo = {};
        queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueCreateInfo.queueFamilyIndex = queueFamily;
        queueCreateInfo.queueCount = 1;
        queueCreateInfo.pQueuePriorities = &queuePriority;
        queueCreateInfos.push_back(queueCreateInfo);
    }

    VkPhysicalDeviceFeatures deviceFeatures = {};
    // 启用3D纹理支持（用于LUT）
    deviceFeatures.shaderImageGatherExtended = VK_TRUE;

    VkDeviceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.queueCreateInfoCount = static_cast<uint32_t>(queueCreateInfos.size());
    createInfo.pQueueCreateInfos = queueCreateInfos.data();
    createInfo.pEnabledFeatures = &deviceFeatures;

    auto extensions = getRequiredDeviceExtensions();
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();

    if (enableValidationLayers) {
        createInfo.enabledLayerCount = static_cast<uint32_t>(validationLayers.size());
        createInfo.ppEnabledLayerNames = validationLayers.data();
    } else {
        createInfo.enabledLayerCount = 0;
    }

    VkResult result = vkCreateDevice(physicalDevice_, &createInfo, nullptr, &device_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create logical device: %d", result);
        return false;
    }

    // 获取队列
    vkGetDeviceQueue(device_, computeQueueFamily_, 0, &computeQueue_);
    vkGetDeviceQueue(device_, transferQueueFamily_, 0, &transferQueue_);

    LOGI("Logical device created successfully");
    return true;
}

bool VkContext::createCommandPool() {
    VkCommandPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = computeQueueFamily_;

    VkResult result = vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create command pool: %d", result);
        return false;
    }

    LOGI("Command pool created successfully");
    return true;
}

bool VkContext::findQueueFamilies(VkPhysicalDevice device) {
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, nullptr);

    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, queueFamilies.data());

    bool foundCompute = false;
    bool foundTransfer = false;

    for (uint32_t i = 0; i < queueFamilyCount; i++) {
        // 查找计算队列
        if (!foundCompute && (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
            computeQueueFamily_ = i;
            foundCompute = true;
        }

        // 查找传输队列（优先选择只支持传输的队列）
        if (!foundTransfer && (queueFamilies[i].queueFlags & VK_QUEUE_TRANSFER_BIT)) {
            if (!(queueFamilies[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                transferQueueFamily_ = i;
                foundTransfer = true;
            }
        }
    }

    // 如果没有找到专用传输队列，使用计算队列
    if (!foundTransfer && foundCompute) {
        transferQueueFamily_ = computeQueueFamily_;
        foundTransfer = true;
    }

    return foundCompute && foundTransfer;
}

bool VkContext::checkDeviceExtensionSupport(VkPhysicalDevice device) {
    uint32_t extensionCount;
    vkEnumerateDeviceExtensionProperties(device, nullptr, &extensionCount, nullptr);

    std::vector<VkExtensionProperties> availableExtensions(extensionCount);
    vkEnumerateDeviceExtensionProperties(device, nullptr, &extensionCount, availableExtensions.data());

    std::set<std::string> requiredExtensions(deviceExtensions.begin(), deviceExtensions.end());

    for (const auto& extension : availableExtensions) {
        requiredExtensions.erase(extension.extensionName);
    }

    return requiredExtensions.empty();
}

bool VkContext::checkValidationLayerSupport() {
    uint32_t layerCount;
    vkEnumerateInstanceLayerProperties(&layerCount, nullptr);

    std::vector<VkLayerProperties> availableLayers(layerCount);
    vkEnumerateInstanceLayerProperties(&layerCount, availableLayers.data());

    for (const char* layerName : validationLayers) {
        bool layerFound = false;

        for (const auto& layerProperties : availableLayers) {
            if (strcmp(layerName, layerProperties.layerName) == 0) {
                layerFound = true;
                break;
            }
        }

        if (!layerFound) {
            return false;
        }
    }

    return true;
}

std::vector<const char*> VkContext::getRequiredInstanceExtensions() {
    std::vector<const char*> extensions;

    if (enableValidationLayers) {
        extensions.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    }

    return extensions;
}

std::vector<const char*> VkContext::getRequiredDeviceExtensions() {
    return deviceExtensions;
}

uint32_t VkContext::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) const {
    for (uint32_t i = 0; i < memoryProperties_.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) &&
            (memoryProperties_.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }

    LOGE("Failed to find suitable memory type");
    return UINT32_MAX;
}

bool VkContext::isVulkanSupported() {
    // 尝试创建临时实例来检查Vulkan支持
    VkApplicationInfo appInfo = {};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "VulkanCheck";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "No Engine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance testInstance;
    VkResult result = vkCreateInstance(&createInfo, nullptr, &testInstance);

    if (result == VK_SUCCESS) {
        vkDestroyInstance(testInstance, nullptr);
        return true;
    }

    return false;
}

uint32_t VkContext::getMaxImageDimension2D() const {
    return deviceProperties_.limits.maxImageDimension2D;
}

} // namespace vulkan
