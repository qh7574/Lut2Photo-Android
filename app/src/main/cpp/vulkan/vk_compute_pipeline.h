#ifndef VK_COMPUTE_PIPELINE_H
#define VK_COMPUTE_PIPELINE_H

#include <vulkan/vulkan.h>
#include <vector>
#include <string>
#include <memory>

// 前向声明
struct AAssetManager;

namespace vulkan {

class VkContext;
class VkMemoryPool;

/**
 * Vulkan计算管线管理类
 * 负责计算着色器的编译、管线创建和调度
 */
class VkComputePipeline {
public:
    /**
     * 处理参数结构
     */
    struct ProcessingParams {
        float lutStrength = 1.0f;
        float lut2Strength = 0.0f;
        float lutSize = 32.0f;
        float lut2Size = 32.0f;
        int ditherType = 0;
        int grainEnabled = 0;
        float grainStrength = 0.0f;
        float grainSize = 1.0f;
        float grainSeed = 0.0f;
        float shadowThreshold = 0.25f;
        float highlightThreshold = 0.75f;
        float shadowGrainRatio = 1.5f;
        float midtoneGrainRatio = 1.0f;
        float highlightGrainRatio = 0.8f;
        float shadowSizeRatio = 1.2f;
        float highlightSizeRatio = 0.8f;
        float redChannelRatio = 1.0f;
        float greenChannelRatio = 1.0f;
        float blueChannelRatio = 1.0f;
        float channelCorrelation = 0.5f;
        float colorPreservation = 0.8f;
    };

    /**
     * 构造函数
     * @param context Vulkan上下文
     * @param memoryPool 内存池
     */
    VkComputePipeline(VkContext* context, VkMemoryPool* memoryPool);
    ~VkComputePipeline();

    // 禁止拷贝
    VkComputePipeline(const VkComputePipeline&) = delete;
    VkComputePipeline& operator=(const VkComputePipeline&) = delete;

    /**
     * 设置Asset管理器（用于从assets加载着色器）
     * @param assetManager Android Asset管理器
     */
    void setAssetManager(AAssetManager* assetManager);

    /**
     * 初始化计算管线
     * @return 是否初始化成功
     */
    bool initialize();

    /**
     * 清理资源
     */
    void cleanup();

    /**
     * 加载LUT数据到GPU
     * @param lutData LUT数据（浮点数组）
     * @param lutSize LUT尺寸
     * @param isSecondLut 是否为第二个LUT
     * @return 是否加载成功
     */
    bool loadLut(const float* lutData, int lutSize, bool isSecondLut = false);

    /**
     * 处理图像
     * @param inputWidth 输入图像宽度
     * @param inputHeight 输入图像高度
     * @param inputPixels 输入像素数据
     * @param outputPixels 输出像素数据
     * @param params 处理参数
     * @return 是否处理成功
     */
    bool processImage(
        int inputWidth,
        int inputHeight,
        const uint8_t* inputPixels,
        uint8_t* outputPixels,
        const ProcessingParams& params
    );

    /**
     * 检查是否已初始化
     */
    bool isInitialized() const { return initialized_; }

private:
    VkContext* context_;
    VkMemoryPool* memoryPool_;
    AAssetManager* assetManager_ = nullptr;

    // 管线组件
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;

    // 着色器模块
    VkShaderModule computeShaderModule_ = VK_NULL_HANDLE;

    // 资源
    VkBuffer uniformBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory uniformBufferMemory_ = VK_NULL_HANDLE;

    // LUT纹理
    VkImage lutImage_ = VK_NULL_HANDLE;
    VkDeviceMemory lutImageMemory_ = VK_NULL_HANDLE;
    VkImageView lutImageView_ = VK_NULL_HANDLE;
    VkSampler lutSampler_ = VK_NULL_HANDLE;

    VkImage lut2Image_ = VK_NULL_HANDLE;
    VkDeviceMemory lut2ImageMemory_ = VK_NULL_HANDLE;
    VkImageView lut2ImageView_ = VK_NULL_HANDLE;

    // 输入输出图像
    VkImage inputImage_ = VK_NULL_HANDLE;
    VkDeviceMemory inputImageMemory_ = VK_NULL_HANDLE;
    VkImageView inputImageView_ = VK_NULL_HANDLE;

    VkImage outputImage_ = VK_NULL_HANDLE;
    VkDeviceMemory outputImageMemory_ = VK_NULL_HANDLE;
    VkImageView outputImageView_ = VK_NULL_HANDLE;

    // 命令缓冲区
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;

    // 同步
    VkFence fence_ = VK_NULL_HANDLE;

    // 状态
    bool initialized_ = false;
    int currentWidth_ = 0;
    int currentHeight_ = 0;
    int lutSize_ = 0;
    int lut2Size_ = 0;

    /**
     * 创建着色器模块
     */
    bool createShaderModule();

    /**
     * 从assets加载SPIR-V着色器
     * @param assetPath 资源路径
     * @return SPIR-V数据
     */
    std::vector<char> loadSPIRVFromAssets(const std::string& assetPath);

    /**
     * 从嵌入数据创建着色器模块
     */
    bool createShaderModuleFromEmbedded();

    /**
     * 创建描述符集布局
     */
    bool createDescriptorSetLayout();

    /**
     * 创建计算管线
     */
    bool createComputePipeline();

    /**
     * 创建描述符池和描述符集
     */
    bool createDescriptorPool();

    /**
     * 创建Uniform缓冲区
     */
    bool createUniformBuffer();

    /**
     * 创建LUT纹理
     */
    bool createLutTexture(VkImage& image, VkDeviceMemory& memory, 
                          VkImageView& imageView, int lutSize);

    /**
     * 创建输入输出图像
     */
    bool createInOutImages(int width, int height);

    /**
     * 更新描述符集
     */
    void updateDescriptorSet();

    /**
     * 录制命令缓冲区
     */
    bool recordCommandBuffer(const ProcessingParams& params);

    /**
     * 执行计算
     */
    bool submitAndWait();

    /**
     * 创建图像内存屏障
     */
    VkImageMemoryBarrier createImageMemoryBarrier(
        VkImage image,
        VkAccessFlags srcAccessMask,
        VkAccessFlags dstAccessMask,
        VkImageLayout oldLayout,
        VkImageLayout newLayout
    );

    /**
     * 释放输入输出图像资源
     */
    void freeInOutImages();
};

} // namespace vulkan

#endif // VK_COMPUTE_PIPELINE_H
