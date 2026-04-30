#include "vk_compute_pipeline.h"
#include "vk_context.h"
#include "vk_memory_pool.h"
#include <android/log.h>
#include <android/asset_manager.h>
#include <cstring>
#include <fstream>
#include <sstream>

#define LOG_TAG "VkComputePipeline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace vulkan {

VkComputePipeline::VkComputePipeline(VkContext* context, VkMemoryPool* memoryPool)
    : context_(context), memoryPool_(memoryPool), assetManager_(nullptr) {
    LOGI("VkComputePipeline created");
}

VkComputePipeline::~VkComputePipeline() {
    cleanup();
    LOGI("VkComputePipeline destroyed");
}

void VkComputePipeline::setAssetManager(AAssetManager* assetManager) {
    assetManager_ = assetManager;
    LOGI("Asset manager set: %p", assetManager);
}

std::vector<char> VkComputePipeline::loadSPIRVFromAssets(const std::string& assetPath) {
    if (!assetManager_) {
        LOGE("Asset manager not set");
        return {};
    }
    
    AAsset* asset = AAssetManager_open(assetManager_, assetPath.c_str(), AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open asset: %s", assetPath.c_str());
        return {};
    }
    
    size_t size = AAsset_getLength(asset);
    std::vector<char> buffer(size);
    
    int bytesRead = AAsset_read(asset, buffer.data(), size);
    AAsset_close(asset);
    
    if (bytesRead != size) {
        LOGE("Failed to read asset: %s (read %d of %zu bytes)", assetPath.c_str(), bytesRead, size);
        return {};
    }
    
    // 验证SPIR-V魔数
    if (size < 4 || *reinterpret_cast<uint32_t*>(buffer.data()) != 0x07230203) {
        LOGE("Invalid SPIR-V in asset: %s", assetPath.c_str());
        return {};
    }
    
    LOGI("Loaded SPIR-V from assets: %s (%zu bytes)", assetPath.c_str(), size);
    return buffer;
}

bool VkComputePipeline::initialize() {
    if (initialized_) {
        LOGW("VkComputePipeline already initialized");
        return true;
    }

    LOGI("Initializing VkComputePipeline...");

    // 创建着色器模块
    if (!createShaderModule()) {
        LOGE("Failed to create shader module");
        return false;
    }

    // 创建描述符集布局
    if (!createDescriptorSetLayout()) {
        LOGE("Failed to create descriptor set layout");
        cleanup();
        return false;
    }

    // 创建计算管线
    if (!createComputePipeline()) {
        LOGE("Failed to create compute pipeline");
        cleanup();
        return false;
    }

    // 创建描述符池
    if (!createDescriptorPool()) {
        LOGE("Failed to create descriptor pool");
        cleanup();
        return false;
    }

    // 创建Uniform缓冲区
    if (!createUniformBuffer()) {
        LOGE("Failed to create uniform buffer");
        cleanup();
        return false;
    }

    // 创建LUT纹理
    if (!createLutTexture(lutImage_, lutImageMemory_, lutImageView_, 32)) {
        LOGE("Failed to create LUT texture");
        cleanup();
        return false;
    }

    if (!createLutTexture(lut2Image_, lut2ImageMemory_, lut2ImageView_, 32)) {
        LOGE("Failed to create LUT2 texture");
        cleanup();
        return false;
    }

    // 创建采样器
    VkSamplerCreateInfo samplerInfo = {};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.anisotropyEnable = VK_FALSE;
    samplerInfo.maxAnisotropy = 1.0f;
    samplerInfo.borderColor = VK_BORDER_COLOR_INT_OPAQUE_BLACK;
    samplerInfo.unnormalizedCoordinates = VK_FALSE;
    samplerInfo.compareEnable = VK_FALSE;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;

    VkResult result = vkCreateSampler(context_->getDevice(), &samplerInfo, nullptr, &lutSampler_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create sampler: %d", result);
        cleanup();
        return false;
    }

    // 创建默认的1x1输入输出图像（避免在loadLut时描述符无效）
    if (!createInOutImages(1, 1)) {
        LOGE("Failed to create default input/output images");
        cleanup();
        return false;
    }

    // 创建命令缓冲区
    VkCommandBufferAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = context_->getCommandPool();
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;

    result = vkAllocateCommandBuffers(context_->getDevice(), &allocInfo, &commandBuffer_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate command buffer: %d", result);
        cleanup();
        return false;
    }

    // 创建栅栏
    VkFenceCreateInfo fenceInfo = {};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;

    result = vkCreateFence(context_->getDevice(), &fenceInfo, nullptr, &fence_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create fence: %d", result);
        cleanup();
        return false;
    }

    // 创建描述符集
    VkDescriptorSetAllocateInfo descriptorSetAllocInfo = {};
    descriptorSetAllocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    descriptorSetAllocInfo.descriptorPool = descriptorPool_;
    descriptorSetAllocInfo.descriptorSetCount = 1;
    descriptorSetAllocInfo.pSetLayouts = &descriptorSetLayout_;

    result = vkAllocateDescriptorSets(context_->getDevice(), &descriptorSetAllocInfo, &descriptorSet_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate descriptor set: %d", result);
        cleanup();
        return false;
    }

    initialized_ = true;
    LOGI("VkComputePipeline initialized successfully");
    return true;
}

void VkComputePipeline::cleanup() {
    if (!initialized_ && 
        pipelineLayout_ == VK_NULL_HANDLE &&
        pipeline_ == VK_NULL_HANDLE &&
        descriptorSetLayout_ == VK_NULL_HANDLE) {
        return;
    }

    VkDevice device = context_->getDevice();

    // 等待设备空闲
    if (device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device);
    }

    // 释放输入输出图像
    freeInOutImages();

    // 释放LUT纹理
    if (lutSampler_ != VK_NULL_HANDLE) {
        vkDestroySampler(device, lutSampler_, nullptr);
        lutSampler_ = VK_NULL_HANDLE;
    }

    if (lutImageView_ != VK_NULL_HANDLE) {
        vkDestroyImageView(device, lutImageView_, nullptr);
        lutImageView_ = VK_NULL_HANDLE;
    }

    if (lutImage_ != VK_NULL_HANDLE) {
        vkDestroyImage(device, lutImage_, nullptr);
        lutImage_ = VK_NULL_HANDLE;
    }

    if (lutImageMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device, lutImageMemory_, nullptr);
        lutImageMemory_ = VK_NULL_HANDLE;
    }

    if (lut2ImageView_ != VK_NULL_HANDLE) {
        vkDestroyImageView(device, lut2ImageView_, nullptr);
        lut2ImageView_ = VK_NULL_HANDLE;
    }

    if (lut2Image_ != VK_NULL_HANDLE) {
        vkDestroyImage(device, lut2Image_, nullptr);
        lut2Image_ = VK_NULL_HANDLE;
    }

    if (lut2ImageMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device, lut2ImageMemory_, nullptr);
        lut2ImageMemory_ = VK_NULL_HANDLE;
    }

    // 释放Uniform缓冲区
    if (uniformBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device, uniformBuffer_, nullptr);
        uniformBuffer_ = VK_NULL_HANDLE;
    }

    if (uniformBufferMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device, uniformBufferMemory_, nullptr);
        uniformBufferMemory_ = VK_NULL_HANDLE;
    }

    // 释放描述符池
    if (descriptorPool_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        descriptorPool_ = VK_NULL_HANDLE;
    }

    // 释放管线
    if (pipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device, pipeline_, nullptr);
        pipeline_ = VK_NULL_HANDLE;
    }

    if (pipelineLayout_ != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        pipelineLayout_ = VK_NULL_HANDLE;
    }

    if (descriptorSetLayout_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
        descriptorSetLayout_ = VK_NULL_HANDLE;
    }

    // 释放着色器模块
    if (computeShaderModule_ != VK_NULL_HANDLE) {
        vkDestroyShaderModule(device, computeShaderModule_, nullptr);
        computeShaderModule_ = VK_NULL_HANDLE;
    }

    // 释放命令缓冲区
    if (commandBuffer_ != VK_NULL_HANDLE) {
        vkFreeCommandBuffers(device, context_->getCommandPool(), 1, &commandBuffer_);
        commandBuffer_ = VK_NULL_HANDLE;
    }

    // 释放栅栏
    if (fence_ != VK_NULL_HANDLE) {
        vkDestroyFence(device, fence_, nullptr);
        fence_ = VK_NULL_HANDLE;
    }

    initialized_ = false;
    LOGI("VkComputePipeline cleaned up");
}

bool VkComputePipeline::createShaderModule() {
    LOGI("Creating shader module...");
    
    // 尝试从assets加载SPIR-V
    std::vector<char> spirvCode = loadSPIRVFromAssets("shaders/lut_processor.spv");
    
    if (spirvCode.empty()) {
        LOGW("Failed to load SPIR-V from assets, trying embedded shader...");
        return createShaderModuleFromEmbedded();
    }
    
    VkShaderModuleCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = spirvCode.size();
    createInfo.pCode = reinterpret_cast<const uint32_t*>(spirvCode.data());

    VkResult result = vkCreateShaderModule(
        context_->getDevice(), &createInfo, nullptr, &computeShaderModule_
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create shader module: %d", result);
        return false;
    }

    LOGI("Shader module created from SPIR-V (%zu bytes)", spirvCode.size());
    return true;
}

bool VkComputePipeline::createShaderModuleFromEmbedded() {
    LOGW("Using placeholder shader module - embedded SPIR-V not available");
    
    // 创建一个最小的有效SPIR-V模块（空的计算着色器）
    // 这是一个占位符，实际应用需要嵌入编译后的SPIR-V
    static const uint32_t minimalSPIRV[] = {
        0x07230203, // Magic number
        0x00010000, // Version 1.0
        0x00000000, // Generator
        0x00000004, // Bound
        0x00000000, // Schema
        // OpEntryPoint
        0x00150013, 0x00000001, 0x00000010, 0x00000000,
        // OpExecutionMode
        0x000F0021, 0x00000001, 0x00000013, 0x00000001,
        // OpName
        0x00060001, 0x00000001, 0x00000000,
        // OpTypeVoid
        0x00040013, 0x00000001,
        // OpTypeFunction
        0x00040014, 0x00000002, 0x00000001,
        // OpFunction
        0x00070036, 0x00000001, 0x00000002, 0x00000001,
        // OpLabel
        0x00020037, 0x00000003,
        // OpReturn
        0x00010038,
        // OpFunctionEnd
        0x00010039
    };
    
    VkShaderModuleCreateInfo createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = sizeof(minimalSPIRV);
    createInfo.pCode = minimalSPIRV;

    VkResult result = vkCreateShaderModule(
        context_->getDevice(), &createInfo, nullptr, &computeShaderModule_
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create shader module from embedded: %d", result);
        return false;
    }

    LOGI("Shader module created from embedded data (placeholder)");
    return true;
}

bool VkComputePipeline::createDescriptorSetLayout() {
    // 输入图像
    VkDescriptorSetLayoutBinding inputImageBinding = {};
    inputImageBinding.binding = 0;
    inputImageBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    inputImageBinding.descriptorCount = 1;
    inputImageBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // 输出图像
    VkDescriptorSetLayoutBinding outputImageBinding = {};
    outputImageBinding.binding = 1;
    outputImageBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    outputImageBinding.descriptorCount = 1;
    outputImageBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // LUT纹理
    VkDescriptorSetLayoutBinding lutTextureBinding = {};
    lutTextureBinding.binding = 2;
    lutTextureBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    lutTextureBinding.descriptorCount = 1;
    lutTextureBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // LUT2纹理
    VkDescriptorSetLayoutBinding lut2TextureBinding = {};
    lut2TextureBinding.binding = 3;
    lut2TextureBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    lut2TextureBinding.descriptorCount = 1;
    lut2TextureBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    // Uniform缓冲区
    VkDescriptorSetLayoutBinding uniformBinding = {};
    uniformBinding.binding = 4;
    uniformBinding.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    uniformBinding.descriptorCount = 1;
    uniformBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    std::vector<VkDescriptorSetLayoutBinding> bindings = {
        inputImageBinding,
        outputImageBinding,
        lutTextureBinding,
        lut2TextureBinding,
        uniformBinding
    };

    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    layoutInfo.pBindings = bindings.data();

    VkResult result = vkCreateDescriptorSetLayout(
        context_->getDevice(), &layoutInfo, nullptr, &descriptorSetLayout_
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create descriptor set layout: %d", result);
        return false;
    }

    LOGI("Descriptor set layout created");
    return true;
}

bool VkComputePipeline::createComputePipeline() {
    VkPipelineShaderStageCreateInfo shaderStageInfo = {};
    shaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStageInfo.module = computeShaderModule_;
    shaderStageInfo.pName = "main";

    VkPipelineLayoutCreateInfo pipelineLayoutInfo = {};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout_;

    VkResult result = vkCreatePipelineLayout(
        context_->getDevice(), &pipelineLayoutInfo, nullptr, &pipelineLayout_
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create pipeline layout: %d", result);
        return false;
    }

    VkComputePipelineCreateInfo pipelineInfo = {};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = shaderStageInfo;
    pipelineInfo.layout = pipelineLayout_;

    result = vkCreateComputePipelines(
        context_->getDevice(), VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline_
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create compute pipeline: %d", result);
        return false;
    }

    LOGI("Compute pipeline created");
    return true;
}

bool VkComputePipeline::createDescriptorPool() {
    VkDescriptorPoolSize poolSizes[] = {
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2},
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1}
    };

    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = poolSizes;

    VkResult result = vkCreateDescriptorPool(
        context_->getDevice(), &poolInfo, nullptr, &descriptorPool_
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create descriptor pool: %d", result);
        return false;
    }

    LOGI("Descriptor pool created");
    return true;
}

bool VkComputePipeline::createUniformBuffer() {
    VkDeviceSize bufferSize = sizeof(ProcessingParams);

    VkBufferCreateInfo bufferInfo = {};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = bufferSize;
    bufferInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkResult result = vkCreateBuffer(
        context_->getDevice(), &bufferInfo, nullptr, &uniformBuffer_
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create uniform buffer: %d", result);
        return false;
    }

    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(context_->getDevice(), uniformBuffer_, &memRequirements);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = context_->findMemoryType(
        memRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );

    result = vkAllocateMemory(
        context_->getDevice(), &allocInfo, nullptr, &uniformBufferMemory_
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate uniform buffer memory: %d", result);
        return false;
    }

    result = vkBindBufferMemory(
        context_->getDevice(), uniformBuffer_, uniformBufferMemory_, 0
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to bind uniform buffer memory: %d", result);
        return false;
    }

    LOGI("Uniform buffer created");
    return true;
}

bool VkComputePipeline::createLutTexture(VkImage& image, VkDeviceMemory& memory, 
                                          VkImageView& imageView, int lutSize) {
    // 创建3D图像用于LUT
    VkImageCreateInfo imageInfo = {};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_3D;
    imageInfo.extent.width = lutSize;
    imageInfo.extent.height = lutSize;
    imageInfo.extent.depth = lutSize;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = VK_FORMAT_R32G32B32A32_SFLOAT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkResult result = vkCreateImage(context_->getDevice(), &imageInfo, nullptr, &image);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create LUT image: %d", result);
        return false;
    }

    VkMemoryRequirements memRequirements;
    vkGetImageMemoryRequirements(context_->getDevice(), image, &memRequirements);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = context_->findMemoryType(
        memRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );

    result = vkAllocateMemory(context_->getDevice(), &allocInfo, nullptr, &memory);
    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate LUT image memory: %d", result);
        return false;
    }

    result = vkBindImageMemory(context_->getDevice(), image, memory, 0);
    if (result != VK_SUCCESS) {
        LOGE("Failed to bind LUT image memory: %d", result);
        return false;
    }

    // 创建图像视图
    VkImageViewCreateInfo viewInfo = {};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_3D;
    viewInfo.format = VK_FORMAT_R32G32B32A32_SFLOAT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;

    result = vkCreateImageView(context_->getDevice(), &viewInfo, nullptr, &imageView);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create LUT image view: %d", result);
        return false;
    }

    LOGI("LUT texture created: %dx%dx%d", lutSize, lutSize, lutSize);
    return true;
}

bool VkComputePipeline::createInOutImages(int width, int height) {
    // 释放旧的图像资源
    freeInOutImages();

    // 创建输入图像
    VkImageCreateInfo imageInfo = {};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.extent.width = width;
    imageInfo.extent.height = height;
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    // 创建输入图像
    VkResult result = vkCreateImage(context_->getDevice(), &imageInfo, nullptr, &inputImage_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create input image: %d", result);
        return false;
    }

    VkMemoryRequirements memRequirements;
    vkGetImageMemoryRequirements(context_->getDevice(), inputImage_, &memRequirements);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = context_->findMemoryType(
        memRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );

    result = vkAllocateMemory(context_->getDevice(), &allocInfo, nullptr, &inputImageMemory_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate input image memory: %d", result);
        return false;
    }

    result = vkBindImageMemory(context_->getDevice(), inputImage_, inputImageMemory_, 0);
    if (result != VK_SUCCESS) {
        LOGE("Failed to bind input image memory: %d", result);
        return false;
    }

    // 创建输入图像视图
    VkImageViewCreateInfo viewInfo = {};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = inputImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;

    result = vkCreateImageView(context_->getDevice(), &viewInfo, nullptr, &inputImageView_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create input image view: %d", result);
        return false;
    }

    // 创建输出图像
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    result = vkCreateImage(context_->getDevice(), &imageInfo, nullptr, &outputImage_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create output image: %d", result);
        return false;
    }

    vkGetImageMemoryRequirements(context_->getDevice(), outputImage_, &memRequirements);
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = context_->findMemoryType(
        memRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );

    result = vkAllocateMemory(context_->getDevice(), &allocInfo, nullptr, &outputImageMemory_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate output image memory: %d", result);
        return false;
    }

    result = vkBindImageMemory(context_->getDevice(), outputImage_, outputImageMemory_, 0);
    if (result != VK_SUCCESS) {
        LOGE("Failed to bind output image memory: %d", result);
        return false;
    }

    // 创建输出图像视图
    viewInfo.image = outputImage_;
    result = vkCreateImageView(context_->getDevice(), &viewInfo, nullptr, &outputImageView_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create output image view: %d", result);
        return false;
    }

    currentWidth_ = width;
    currentHeight_ = height;

    LOGI("Input/Output images created: %dx%d", width, height);
    return true;
}

void VkComputePipeline::updateDescriptorSet() {
    // 更新输入图像描述符
    VkDescriptorImageInfo inputImageInfo = {};
    inputImageInfo.imageView = inputImageView_;
    inputImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet inputWrite = {};
    inputWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    inputWrite.dstSet = descriptorSet_;
    inputWrite.dstBinding = 0;
    inputWrite.dstArrayElement = 0;
    inputWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    inputWrite.descriptorCount = 1;
    inputWrite.pImageInfo = &inputImageInfo;

    // 更新输出图像描述符
    VkDescriptorImageInfo outputImageInfo = {};
    outputImageInfo.imageView = outputImageView_;
    outputImageInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkWriteDescriptorSet outputWrite = {};
    outputWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    outputWrite.dstSet = descriptorSet_;
    outputWrite.dstBinding = 1;
    outputWrite.dstArrayElement = 0;
    outputWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    outputWrite.descriptorCount = 1;
    outputWrite.pImageInfo = &outputImageInfo;

    // 更新LUT纹理描述符
    VkDescriptorImageInfo lutImageInfo = {};
    lutImageInfo.imageView = lutImageView_;
    lutImageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    lutImageInfo.sampler = lutSampler_;

    VkWriteDescriptorSet lutWrite = {};
    lutWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    lutWrite.dstSet = descriptorSet_;
    lutWrite.dstBinding = 2;
    lutWrite.dstArrayElement = 0;
    lutWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    lutWrite.descriptorCount = 1;
    lutWrite.pImageInfo = &lutImageInfo;

    // 更新Uniform缓冲区描述符
    VkDescriptorBufferInfo bufferInfo = {};
    bufferInfo.buffer = uniformBuffer_;
    bufferInfo.offset = 0;
    bufferInfo.range = sizeof(ProcessingParams);

    VkWriteDescriptorSet uniformWrite = {};
    uniformWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    uniformWrite.dstSet = descriptorSet_;
    uniformWrite.dstBinding = 4;
    uniformWrite.dstArrayElement = 0;
    uniformWrite.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    uniformWrite.descriptorCount = 1;
    uniformWrite.pBufferInfo = &bufferInfo;

    std::vector<VkWriteDescriptorSet> writes = {
            inputWrite, outputWrite, lutWrite, uniformWrite
    };

    // 只有当LUT2有效时才更新LUT2描述符
    VkDescriptorImageInfo lut2ImageInfo = {};
    VkWriteDescriptorSet lut2Write = {};
    if (lut2ImageView_ != VK_NULL_HANDLE) {
        lut2ImageInfo.imageView = lut2ImageView_;
        lut2ImageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        lut2ImageInfo.sampler = lutSampler_;

        lut2Write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        lut2Write.dstSet = descriptorSet_;
        lut2Write.dstBinding = 3;
        lut2Write.dstArrayElement = 0;
        lut2Write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        lut2Write.descriptorCount = 1;
        lut2Write.pImageInfo = &lut2ImageInfo;

        writes.push_back(lut2Write);
    } else {
        // 使用LUT1作为LUT2的占位符（强度为0时不会实际使用）
        lut2ImageInfo.imageView = lutImageView_;
        lut2ImageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        lut2ImageInfo.sampler = lutSampler_;

        lut2Write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        lut2Write.dstSet = descriptorSet_;
        lut2Write.dstBinding = 3;
        lut2Write.dstArrayElement = 0;
        lut2Write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        lut2Write.descriptorCount = 1;
        lut2Write.pImageInfo = &lut2ImageInfo;

        writes.push_back(lut2Write);
    }

    vkUpdateDescriptorSets(
        context_->getDevice(),
        static_cast<uint32_t>(writes.size()),
        writes.data(),
        0,
        nullptr
    );
}

bool VkComputePipeline::loadLut(const float* lutData, int lutSize, bool isSecondLut) {
    if (!initialized_) {
        LOGE("Pipeline not initialized");
        return false;
    }

    LOGI("Loading LUT data: size=%d, isSecond=%d", lutSize, isSecondLut);

    // 创建暂存缓冲区
    VkDeviceSize bufferSize = lutSize * lutSize * lutSize * 4 * sizeof(float);
    
    VkBuffer stagingBuffer;
    VkDeviceMemory stagingBufferMemory;

    VkBufferCreateInfo bufferInfo = {};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = bufferSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkResult result = vkCreateBuffer(
        context_->getDevice(), &bufferInfo, nullptr, &stagingBuffer
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to create staging buffer: %d", result);
        return false;
    }

    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(context_->getDevice(), stagingBuffer, &memRequirements);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = context_->findMemoryType(
        memRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );

    result = vkAllocateMemory(
        context_->getDevice(), &allocInfo, nullptr, &stagingBufferMemory
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate staging buffer memory: %d", result);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    result = vkBindBufferMemory(
        context_->getDevice(), stagingBuffer, stagingBufferMemory, 0
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to bind staging buffer memory: %d", result);
        vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    // 复制LUT数据到暂存缓冲区
    void* mappedData;
    result = vkMapMemory(
        context_->getDevice(), stagingBufferMemory, 0, bufferSize, 0, &mappedData
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to map staging buffer memory: %d", result);
        vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    // 转换LUT数据格式（从RGB到RGBA）
    float* dstData = static_cast<float*>(mappedData);
    for (int i = 0; i < lutSize * lutSize * lutSize; i++) {
        dstData[i * 4 + 0] = lutData[i * 3 + 0];
        dstData[i * 4 + 1] = lutData[i * 3 + 1];
        dstData[i * 4 + 2] = lutData[i * 3 + 2];
        dstData[i * 4 + 3] = 1.0f;
    }

    vkUnmapMemory(context_->getDevice(), stagingBufferMemory);

    // 重新创建LUT纹理（如果尺寸变化）
    VkImage& targetImage = isSecondLut ? lut2Image_ : lutImage_;
    VkDeviceMemory& targetMemory = isSecondLut ? lut2ImageMemory_ : lutImageMemory_;
    VkImageView& targetView = isSecondLut ? lut2ImageView_ : lutImageView_;

    // 释放旧纹理
    if (targetView != VK_NULL_HANDLE) {
        vkDestroyImageView(context_->getDevice(), targetView, nullptr);
        targetView = VK_NULL_HANDLE;
    }
    if (targetImage != VK_NULL_HANDLE) {
        vkDestroyImage(context_->getDevice(), targetImage, nullptr);
        targetImage = VK_NULL_HANDLE;
    }
    if (targetMemory != VK_NULL_HANDLE) {
        vkFreeMemory(context_->getDevice(), targetMemory, nullptr);
        targetMemory = VK_NULL_HANDLE;
    }

    // 创建新纹理
    if (!createLutTexture(targetImage, targetMemory, targetView, lutSize)) {
        LOGE("Failed to create LUT texture");
        vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    // 记录命令缓冲区进行数据传输
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    vkBeginCommandBuffer(commandBuffer_, &beginInfo);

    // 转换图像布局为传输目标
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = targetImage;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;

    vkCmdPipelineBarrier(
        commandBuffer_,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );

    // 复制缓冲区到图像
    VkBufferImageCopy region = {};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.mipLevel = 0;
    region.imageSubresource.baseArrayLayer = 0;
    region.imageSubresource.layerCount = 1;
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {static_cast<uint32_t>(lutSize), 
                          static_cast<uint32_t>(lutSize), 
                          static_cast<uint32_t>(lutSize)};

    vkCmdCopyBufferToImage(
        commandBuffer_,
        stagingBuffer,
        targetImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &region
    );

    // 转换图像布局为着色器只读
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

    vkCmdPipelineBarrier(
        commandBuffer_,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );

    vkEndCommandBuffer(commandBuffer_);

    // 提交命令
    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkQueueSubmit(context_->getComputeQueue(), 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(context_->getComputeQueue());

    // 清理暂存缓冲区
    vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
    vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);

    // 更新LUT尺寸
    if (isSecondLut) {
        lut2Size_ = lutSize;
    } else {
        lutSize_ = lutSize;
    }

    // 更新描述符集
    updateDescriptorSet();

    LOGI("LUT data loaded successfully, size=%d, isSecond=%d", lutSize, isSecondLut);
    return true;
}

bool VkComputePipeline::processImage(
    int inputWidth,
    int inputHeight,
    const uint8_t* inputPixels,
    uint8_t* outputPixels,
    const ProcessingParams& params
) {
    if (!initialized_) {
        LOGE("Pipeline not initialized");
        return false;
    }

    // 检查是否需要重新创建图像
    if (inputWidth != currentWidth_ || inputHeight != currentHeight_) {
        if (!createInOutImages(inputWidth, inputHeight)) {
            LOGE("Failed to create input/output images");
            return false;
        }
    }

    // 每次处理都更新描述符集，确保绑定正确
    updateDescriptorSet();

    // 更新LUT尺寸到参数中
    ProcessingParams updatedParams = params;
    updatedParams.lutSize = static_cast<float>(lutSize_ > 0 ? lutSize_ : 32);
    updatedParams.lut2Size = static_cast<float>(lut2Size_ > 0 ? lut2Size_ : 32);

    // 更新Uniform缓冲区
    void* mappedData;
    VkResult result = vkMapMemory(
        context_->getDevice(), uniformBufferMemory_, 0, sizeof(ProcessingParams), 0, &mappedData
    );

    if (result != VK_SUCCESS) {
        LOGE("Failed to map uniform buffer: %d", result);
        return false;
    }

    memcpy(mappedData, &updatedParams, sizeof(ProcessingParams));
    vkUnmapMemory(context_->getDevice(), uniformBufferMemory_);

    // 创建暂存缓冲区用于数据传输
    VkDeviceSize imageSize = inputWidth * inputHeight * 4;
    
    VkBuffer stagingBuffer;
    VkDeviceMemory stagingBufferMemory;

    VkBufferCreateInfo bufferInfo = {};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = imageSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    result = vkCreateBuffer(context_->getDevice(), &bufferInfo, nullptr, &stagingBuffer);
    if (result != VK_SUCCESS) {
        LOGE("Failed to create staging buffer: %d", result);
        return false;
    }

    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(context_->getDevice(), stagingBuffer, &memRequirements);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = context_->findMemoryType(
        memRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );

    result = vkAllocateMemory(context_->getDevice(), &allocInfo, nullptr, &stagingBufferMemory);
    if (result != VK_SUCCESS) {
        LOGE("Failed to allocate staging buffer memory: %d", result);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    result = vkBindBufferMemory(context_->getDevice(), stagingBuffer, stagingBufferMemory, 0);
    if (result != VK_SUCCESS) {
        LOGE("Failed to bind staging buffer memory: %d", result);
        vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    // 上传输入图像数据
    void* data;
    result = vkMapMemory(context_->getDevice(), stagingBufferMemory, 0, imageSize, 0, &data);
    if (result != VK_SUCCESS) {
        LOGE("Failed to map staging buffer: %d", result);
        vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    memcpy(data, inputPixels, imageSize);
    vkUnmapMemory(context_->getDevice(), stagingBufferMemory);

    // 录制命令缓冲区
    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    vkResetCommandBuffer(commandBuffer_, 0);
    vkBeginCommandBuffer(commandBuffer_, &beginInfo);

    // 转换输入图像布局
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = inputImage_;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;

    vkCmdPipelineBarrier(
        commandBuffer_,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );

    // 复制缓冲区到输入图像
    VkBufferImageCopy region = {};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.mipLevel = 0;
    region.imageSubresource.baseArrayLayer = 0;
    region.imageSubresource.layerCount = 1;
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {static_cast<uint32_t>(inputWidth), 
                          static_cast<uint32_t>(inputHeight), 1};

    vkCmdCopyBufferToImage(
        commandBuffer_,
        stagingBuffer,
        inputImage_,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &region
    );

    // 转换输入图像为General布局
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

    vkCmdPipelineBarrier(
        commandBuffer_,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );

    // 转换输出图像为General布局
    barrier.image = outputImage_;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;

    vkCmdPipelineBarrier(
        commandBuffer_,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );

    // 绑定计算管线
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(
        commandBuffer_,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        pipelineLayout_,
        0,
        1,
        &descriptorSet_,
        0,
        nullptr
    );

    // 调度计算
    uint32_t groupX = (inputWidth + 15) / 16;
    uint32_t groupY = (inputHeight + 15) / 16;
    vkCmdDispatch(commandBuffer_, groupX, groupY, 1);

    // 转换输出图像为传输源
    barrier.image = outputImage_;
    barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;

    vkCmdPipelineBarrier(
        commandBuffer_,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );

    // 复制输出图像到暂存缓冲区
    vkCmdCopyImageToBuffer(
        commandBuffer_,
        outputImage_,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        stagingBuffer,
        1,
        &region
    );

    vkEndCommandBuffer(commandBuffer_);

    // 提交命令
    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;

    vkResetFences(context_->getDevice(), 1, &fence_);
    result = vkQueueSubmit(context_->getComputeQueue(), 1, &submitInfo, fence_);
    if (result != VK_SUCCESS) {
        LOGE("Failed to submit command buffer: %d", result);
        vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    // 等待完成
    result = vkWaitForFences(context_->getDevice(), 1, &fence_, VK_TRUE, UINT64_MAX);
    if (result != VK_SUCCESS) {
        LOGE("Failed to wait for fence: %d", result);
        vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    // 读取结果
    result = vkMapMemory(context_->getDevice(), stagingBufferMemory, 0, imageSize, 0, &data);
    if (result != VK_SUCCESS) {
        LOGE("Failed to map staging buffer for reading: %d", result);
        vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
        vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);
        return false;
    }

    memcpy(outputPixels, data, imageSize);
    
    // 调试：检查前几个像素
    if (inputWidth > 0 && inputHeight > 0) {
        const uint8_t* firstPixel = outputPixels;
        LOGD("Output pixel[0]: R=%d G=%d B=%d A=%d", 
             firstPixel[0], firstPixel[1], firstPixel[2], firstPixel[3]);
        
        // 检查是否全黑
        bool allBlack = true;
        for (int i = 0; i < std::min(100, (int)imageSize); i += 4) {
            if (outputPixels[i] != 0 || outputPixels[i+1] != 0 || 
                outputPixels[i+2] != 0) {
                allBlack = false;
                break;
            }
        }
        if (allBlack) {
            LOGW("WARNING: Output appears to be all black!");
            
            // 检查输入像素
            const uint8_t* inputFirst = inputPixels;
            LOGD("Input pixel[0]: R=%d G=%d B=%d A=%d", 
                 inputFirst[0], inputFirst[1], inputFirst[2], inputFirst[3]);
        }
    }
    
    vkUnmapMemory(context_->getDevice(), stagingBufferMemory);

    // 清理
    vkFreeMemory(context_->getDevice(), stagingBufferMemory, nullptr);
    vkDestroyBuffer(context_->getDevice(), stagingBuffer, nullptr);

    LOGD("Image processed successfully: %dx%d", inputWidth, inputHeight);
    return true;
}

void VkComputePipeline::freeInOutImages() {
    VkDevice device = context_->getDevice();

    if (inputImageView_ != VK_NULL_HANDLE) {
        vkDestroyImageView(device, inputImageView_, nullptr);
        inputImageView_ = VK_NULL_HANDLE;
    }
    if (inputImage_ != VK_NULL_HANDLE) {
        vkDestroyImage(device, inputImage_, nullptr);
        inputImage_ = VK_NULL_HANDLE;
    }
    if (inputImageMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device, inputImageMemory_, nullptr);
        inputImageMemory_ = VK_NULL_HANDLE;
    }

    if (outputImageView_ != VK_NULL_HANDLE) {
        vkDestroyImageView(device, outputImageView_, nullptr);
        outputImageView_ = VK_NULL_HANDLE;
    }
    if (outputImage_ != VK_NULL_HANDLE) {
        vkDestroyImage(device, outputImage_, nullptr);
        outputImage_ = VK_NULL_HANDLE;
    }
    if (outputImageMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device, outputImageMemory_, nullptr);
        outputImageMemory_ = VK_NULL_HANDLE;
    }

    currentWidth_ = 0;
    currentHeight_ = 0;
}

} // namespace vulkan
