#!/bin/bash

echo "========================================"
echo "  SPIR-V Shader Compiler"
echo "========================================"
echo ""

# 设置路径
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="${SCRIPT_DIR}/../../../.."
OUTPUT_DIR="${PROJECT_DIR}/assets/shaders"

# 查找glslc
GLSLC=""

# 检查ANDROID_HOME或ANDROID_SDK
if [ -z "$ANDROID_HOME" ]; then
    if [ -z "$ANDROID_SDK" ]; then
        # 默认路径
        if [ "$(uname)" == "Darwin" ]; then
            # macOS
            ANDROID_HOME="$HOME/Library/Android/sdk"
        else
            # Linux
            ANDROID_HOME="$HOME/Android/Sdk"
        fi
    else
        ANDROID_HOME="$ANDROID_SDK"
    fi
fi

# 尝试多个可能的路径
for path in \
    "${ANDROID_HOME}/cmake/3.22.1/bin/glslc" \
    "${ANDROID_HOME}/cmake/3.21.1/bin/glslc" \
    "${ANDROID_HOME}/cmake/3.20.0/bin/glslc" \
    "${ANDROID_HOME}/ndk-bundle/shader-tools/linux-x86_64/glslc" \
    "${ANDROID_HOME}/ndk-bundle/shader-tools/darwin-x86_64/glslc" \
    "$(which glslc 2>/dev/null)"; do
    if [ -x "$path" ]; then
        GLSLC="$path"
        break
    fi
done

if [ -z "$GLSLC" ]; then
    echo "ERROR: glslc not found!"
    echo ""
    echo "Please install CMake via Android SDK Manager or set ANDROID_HOME."
    echo ""
    exit 1
fi

echo "Found glslc: ${GLSLC}"
echo ""

# 创建输出目录
mkdir -p "${OUTPUT_DIR}"
echo "Output directory: ${OUTPUT_DIR}"
echo ""

# 编译着色器
COMPILE_COUNT=0
ERROR_COUNT=0

compile_shader() {
    local input_file="$1"
    local output_file="$2"
    local shader_name=$(basename "$input_file")
    
    echo "Compiling: ${shader_name}"
    
    if "${GLSLC}" --target-env=vulkan1.0 -O "$input_file" -o "$output_file"; then
        echo "  SUCCESS: $(basename "$output_file")"
        ((COMPILE_COUNT++))
    else
        echo "  FAILED: ${shader_name}"
        ((ERROR_COUNT++))
    fi
    echo ""
}

# 编译计算着色器 (.comp)
for f in "${SCRIPT_DIR}"/*.comp; do
    if [ -f "$f" ]; then
        filename=$(basename "$f" .comp)
        compile_shader "$f" "${OUTPUT_DIR}/${filename}.spv"
    fi
done

# 编译顶点着色器 (.vert)
for f in "${SCRIPT_DIR}"/*.vert; do
    if [ -f "$f" ]; then
        filename=$(basename "$f" .vert)
        compile_shader "$f" "${OUTPUT_DIR}/${filename}_vert.spv"
    fi
done

# 编译片段着色器 (.frag)
for f in "${SCRIPT_DIR}"/*.frag; do
    if [ -f "$f" ]; then
        filename=$(basename "$f" .frag)
        compile_shader "$f" "${OUTPUT_DIR}/${filename}_frag.spv"
    fi
done

# 总结
echo "========================================"
echo "  Compilation Summary"
echo "========================================"
echo ""
echo "  Compiled: ${COMPILE_COUNT}"
echo "  Failed:   ${ERROR_COUNT}"
echo ""

if [ $ERROR_COUNT -gt 0 ]; then
    echo "WARNING: Some shaders failed to compile!"
    echo ""
    exit 1
else
    echo "All shaders compiled successfully!"
    echo ""
    exit 0
fi
