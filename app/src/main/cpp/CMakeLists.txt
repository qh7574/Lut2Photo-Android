# CMakeLists.txt for Native LUT Processor
cmake_minimum_required(VERSION 3.22.1)

# 项目名称
project("native_lut_processor")

# 设置C++标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 编译选项
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -O3 -ffast-math -ferror-limit=0")

# 添加NEON支持（ARM架构）
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon -mfloat-abi=softfp")
endif ()

# 包含目录
include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/core
        ${CMAKE_CURRENT_SOURCE_DIR}/utils
        ${CMAKE_CURRENT_SOURCE_DIR}/tests
        ${CMAKE_CURRENT_SOURCE_DIR}
)

# 核心源文件列表
set(CORE_SOURCES
        jni/native_lut_processor.cpp
        core/image_processor.cpp
        core/lut_processor.cpp
        utils/simd_utils.cpp
        utils/bitmap_utils.cpp
)

# 增强功能源文件
set(ENHANCED_SOURCES
        utils/memory_manager.cpp
        utils/memory_pool.cpp
        utils/exception_handler.cpp
        interfaces/media_processor_interface.cpp
        core/streaming_processor.cpp
        lut_image_processor.cpp
)

# 测试源文件（可选编译）
set(TEST_SOURCES
        tests/performance_test.cpp
        tests/test_runner.cpp
)

# 合并所有源文件
set(SOURCES ${CORE_SOURCES} ${ENHANCED_SOURCES})

# 如果启用测试，添加测试源文件
option(ENABLE_PERFORMANCE_TESTS "Enable performance testing" OFF)
if (ENABLE_PERFORMANCE_TESTS)
    set(SOURCES ${SOURCES} ${TEST_SOURCES})
    message(STATUS "Performance tests enabled")
endif ()

# 创建共享库
add_library(
        native_lut_processor
        SHARED
        ${SOURCES}
)

# 设置库的别名
add_library(lut2photo::native_lut_processor ALIAS native_lut_processor)

# 查找并链接系统库
find_library(
        log-lib
        log
)

find_library(
        jnigraphics-lib
        jnigraphics
)

find_library(
        android-lib
        android
)

# 查找OpenMP（可选）
find_package(OpenMP)
if (OpenMP_CXX_FOUND)
    message(STATUS "OpenMP found, enabling parallel processing")
    target_link_libraries(native_lut_processor OpenMP::OpenMP_CXX)
    target_compile_definitions(native_lut_processor PRIVATE USE_OPENMP)
else ()
    message(STATUS "OpenMP not found, using single-threaded processing")
endif ()

# 链接库
target_link_libraries(
        native_lut_processor
        ${log-lib}
        ${jnigraphics-lib}
        ${android-lib}
)

# 编译定义
target_compile_definitions(
        native_lut_processor
        PRIVATE
        ANDROID_NDK
        USE_NEON_SIMD
        LOG_TAG="NativeLutProcessor"
        ENHANCED_MEMORY_MANAGEMENT
        EXCEPTION_HANDLING_ENABLED
)

# 如果启用测试，添加测试相关定义
if (ENABLE_PERFORMANCE_TESTS)
    target_compile_definitions(
            native_lut_processor
            PRIVATE
            PERFORMANCE_TESTS_ENABLED
    )
endif ()

# 调试模式下的额外定义
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(
            native_lut_processor
            PRIVATE
            DEBUG_MEMORY_TRACKING
            VERBOSE_LOGGING
    )
endif ()

# 设置输出目录
set_target_properties(
        native_lut_processor
        PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
)