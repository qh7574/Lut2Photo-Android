# Vulkan Shaders

本目录包含Vulkan计算着色器的源代码和编译工具。

## 目录结构

```
shaders/
├── README.md                    # 本文件
├── lut_processor.comp           # LUT处理计算着色器（GLSL源码）
├── compile_shaders.bat          # Windows编译脚本
├── compile_shaders.sh           # Linux/macOS编译脚本
└── validate_shaders.bat         # SPIR-V验证脚本
```

## 前置条件

### 安装Android SDK CMake

1. 打开Android Studio
2. 进入 Tools -> SDK Manager
3. 选择 SDK Tools 标签
4. 勾选 CMake
5. 点击 Apply 安装

### 或安装Vulkan SDK

1. 下载 [LunarG Vulkan SDK](https://vulkan.lunarg.com/sdk/home)
2. 安装到默认位置
3. 设置 VULKAN_SDK 环境变量

## 编译着色器

### Windows

```batch
# 双击运行
compile_shaders.bat

# 或在命令行
cd app\src\main\cpp\vulkan\shaders
compile_shaders.bat
```

### Linux/macOS

```bash
# 添加执行权限
chmod +x compile_shaders.sh

# 运行
./compile_shaders.sh
```

### 手动编译

```bash
# 使用glslc
glslc --target-env=vulkan1.0 -O lut_processor.comp -o ../../assets/shaders/lut_processor.spv

# 使用glslangValidator
glslangValidator --target-env vulkan1.0 -V lut_processor.comp -o ../../assets/shaders/lut_processor.spv
```

## 验证着色器

### 验证SPIR-V文件

```batch
# Windows
validate_shaders.bat

# 或手动验证
spirv-val --target-env vulkan1.0 lut_processor.spv
```

### 反汇编查看

```bash
spirv-dis lut_processor.spv -o lut_processor.dis
```

## 输出位置

编译后的SPIR-V文件位于：
```
app/src/main/assets/shaders/lut_processor.spv
```

## 着色器说明

### lut_processor.comp

LUT处理计算着色器，实现以下功能：
- 双LUT应用（支持混合）
- Floyd-Steinberg抖动
- 随机抖动
- 胶片颗粒效果
- 亮度分区控制

**工作组大小：** 16x16x1

**绑定描述：**
- binding 0: 输入图像 (readonly image2D)
- binding 1: 输出图像 (image2D)
- binding 2: LUT纹理 (sampler3D)
- binding 3: LUT2纹理 (sampler3D)
- binding 4: 参数Uniform

## 常见问题

### Q: 找不到glslc

A: 确保Android SDK的CMake已安装，或手动设置ANDROID_HOME环境变量。

### Q: 编译错误

A: 检查GLSL语法，确保使用 `#version 450`。

### Q: 运行时着色器加载失败

A: 确保SPIR-V文件已正确打包到APK的assets目录。

## 参考资料

- [GLSL规范](https://www.khronos.org/opengl/wiki/OpenGL_Shading_Language)
- [Vulkan着色器指南](https://vulkan-tutorial.com/Drawing_a_triangle/Graphics_pipeline_basics/Shaders)
- [SPIR-V规范](https://www.khronos.org/registry/SPIR-V/)
