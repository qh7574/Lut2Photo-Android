#!/bin/bash
# libgphoto2 交叉编译脚本 for Android ARM64
# 运行环境: WSL Ubuntu 22.04.5 LTS

set -e

# 配置变量
NDK_PATH="${NDK_PATH:-/mnt/d/AndroidStudioSDK/ndk/android-ndk-r29-linux}"
API_LEVEL=31
ABI="arm64-v8a"
TOOLCHAIN="${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64"
TARGET="aarch64-linux-android"
BUILD_DIR="$(pwd)/build_native"
PREFIX="${BUILD_DIR}/install/${ABI}"

# 版本配置
LIBTOOL_VERSION="2.4.7"
LIBUSB_VERSION="1.0.27"  # 升级到支持 Android 的版本
LIBGPHOTO2_VERSION="2.5.33"  # 升级到支持 Android USB 的版本

# 颜色输出
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查NDK路径
if [ ! -d "$NDK_PATH" ]; then
    log_error "NDK路径不存在: $NDK_PATH"
    log_info "请设置正确的NDK_PATH环境变量"
    exit 1
fi

# 创建构建目录
mkdir -p "${BUILD_DIR}/src"
mkdir -p "${PREFIX}"

# 设置编译环境变量
export ANDROID_NDK_ROOT="${NDK_PATH}"
export AR="${TOOLCHAIN}/bin/llvm-ar"
export CC="${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang"
export CXX="${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang++"
export LD="${TOOLCHAIN}/bin/ld"
export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
export STRIP="${TOOLCHAIN}/bin/llvm-strip"
export NM="${TOOLCHAIN}/bin/llvm-nm"

export CFLAGS="-fPIC -ffunction-sections -funwind-tables -fstack-protector-strong -no-canonical-prefixes -DANDROID -DNDEBUG -O2"
export CPPFLAGS="-I${TOOLCHAIN}/sysroot/usr/include -I${TOOLCHAIN}/sysroot/usr/include/${TARGET}"
export LDFLAGS="-L${TOOLCHAIN}/sysroot/usr/lib/${TARGET}/${API_LEVEL} -L${PREFIX}/lib -llog"
export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig"

# 编译 libtool (提供libltdl)
build_libtool() {
    log_info "开始编译 libtool ${LIBTOOL_VERSION}..."
    
    cd "${BUILD_DIR}/src"
    
    if [ ! -f "libtool-${LIBTOOL_VERSION}.tar.gz" ]; then
        log_info "下载 libtool..."
        wget "https://ftp.gnu.org/gnu/libtool/libtool-${LIBTOOL_VERSION}.tar.gz"
    fi
    
    if [ ! -d "libtool-${LIBTOOL_VERSION}" ]; then
        tar -xzf "libtool-${LIBTOOL_VERSION}.tar.gz"
    fi
    
    cd "libtool-${LIBTOOL_VERSION}"
    
    log_info "配置 libtool..."
    make clean || true
    ./configure \
        --host=${TARGET} \
        --prefix="${PREFIX}" \
        --enable-shared \
        --disable-static \
        --enable-ltdl-install \
        --disable-symbol-versioning
    
    log_info "编译 libtool..."
    make -j$(nproc)
    make install
    
    log_success "libtool 编译完成"
    cd ../..
}

# 编译 libusb
build_libusb() {
    log_info "开始编译 libusb ${LIBUSB_VERSION}..."
    
    cd "${BUILD_DIR}/src"
    
    if [ ! -f "libusb-${LIBUSB_VERSION}.tar.bz2" ]; then
        log_info "下载 libusb..."
        wget "https://github.com/libusb/libusb/releases/download/v${LIBUSB_VERSION}/libusb-${LIBUSB_VERSION}.tar.bz2"
    fi
    
    tar -xjf "libusb-${LIBUSB_VERSION}.tar.bz2"
    cd "libusb-${LIBUSB_VERSION}"
    
    log_info "配置 libusb..."
    ./configure \
        --host=${TARGET} \
        --prefix="${PREFIX}" \
        --enable-shared \
        --disable-static \
        --disable-udev \
        --enable-system-log
    
    log_info "编译 libusb..."
    make -j$(nproc)
    make install
    
    log_success "libusb 编译完成"
}

# 编译 libgphoto2
build_libgphoto2() {
    log_info "开始编译 libgphoto2 ${LIBGPHOTO2_VERSION}..."
    
    cd "${BUILD_DIR}/src"
    
    if [ ! -f "libgphoto2-${LIBGPHOTO2_VERSION}.tar.bz2" ]; then
        log_info "下载 libgphoto2..."
        wget "https://github.com/gphoto/libgphoto2/releases/download/${LIBGPHOTO2_VERSION}/libgphoto2-${LIBGPHOTO2_VERSION}.tar.bz2"
    fi
    
    tar -xjf "libgphoto2-${LIBGPHOTO2_VERSION}.tar.bz2"
    cd "libgphoto2-${LIBGPHOTO2_VERSION}"
    
    log_info "配置 libgphoto2..."
    make clean || true
    
    export LIBUSB_CFLAGS="-I${PREFIX}/include/libusb-1.0"
    export LIBUSB_LIBS="-L${PREFIX}/lib -lusb-1.0"
    export LTDLINCL="-I${PREFIX}/include"
    export LIBLTDL="-L${PREFIX}/lib -lltdl"
    
    ./configure \
        --host=${TARGET} \
        --prefix="${PREFIX}" \
        --enable-shared \
        --disable-static \
        --with-android-usb=yes \
        --with-libgphoto2-port="${PREFIX}" \
        --with-libusb="${PREFIX}" \
        --with-ltdl-lib="${PREFIX}/lib" \
        --with-ltdl-include="${PREFIX}/include" \
        --without-libxml-2.0 \
        --without-gdlib \
        --without-libjpeg \
        --disable-nls \
        --disable-rpath \
        --disable-versioned-symbols \
        --disable-symbol-versioning \
        --disable-version-script \
        --with-camlibs=standard
    
    log_info "检查 Android USB 支持是否启用..."
    if grep -q "HAVE_ANDROID_USB" config.h 2>/dev/null; then
        log_success "Android USB 支持已启用"
    else
        log_error "警告：Android USB 支持未启用！"
    fi
    
    log_info "编译 libgphoto2..."
    make -j$(nproc)
    make install
    
    log_success "libgphoto2 编译完成"
}

# 复制库文件到jniLibs
copy_to_jniLibs() {
    log_info "复制库文件到 jniLibs..."
    
    JNILIBS_DIR="app/src/main/jniLibs/${ABI}"
    mkdir -p "${JNILIBS_DIR}"
    
    # 复制主动态库文件
    cp "${PREFIX}/lib/libusb-1.0.so"* "${JNILIBS_DIR}/" 2>/dev/null || true
    cp "${PREFIX}/lib/libgphoto2.so"* "${JNILIBS_DIR}/" 2>/dev/null || true
    cp "${PREFIX}/lib/libgphoto2_port.so"* "${JNILIBS_DIR}/" 2>/dev/null || true
    cp "${PREFIX}/lib/libltdl.so"* "${JNILIBS_DIR}/" 2>/dev/null || true
    
    # 复制 camlibs 驱动模块（关键！）
    log_info "复制相机驱动模块..."
    CAMLIBS_SRC="${PREFIX}/lib/libgphoto2/${LIBGPHOTO2_VERSION}"
    CAMLIBS_DST="${JNILIBS_DIR}/libgphoto2/${LIBGPHOTO2_VERSION}"
    if [ -d "${CAMLIBS_SRC}" ]; then
        mkdir -p "${CAMLIBS_DST}"
        cp "${CAMLIBS_SRC}"/*.so "${CAMLIBS_DST}/" 2>/dev/null || true
        log_success "复制了 $(ls ${CAMLIBS_DST}/*.so 2>/dev/null | wc -l) 个相机驱动"
    fi
    
    # 复制 iolibs 端口驱动模块（关键！）
    log_info "复制端口驱动模块..."
    IOLIBS_VERSION=$(ls "${PREFIX}/lib/libgphoto2_port/" | head -n1)
    IOLIBS_SRC="${PREFIX}/lib/libgphoto2_port/${IOLIBS_VERSION}"
    IOLIBS_DST="${JNILIBS_DIR}/libgphoto2_port/${IOLIBS_VERSION}"
    if [ -d "${IOLIBS_SRC}" ]; then
        mkdir -p "${IOLIBS_DST}"
        cp "${IOLIBS_SRC}"/*.so "${IOLIBS_DST}/" 2>/dev/null || true
        log_success "复制了 $(ls ${IOLIBS_DST}/*.so 2>/dev/null | wc -l) 个端口驱动"
    fi
    
    # 去除调试符号
    for so_file in $(find "${JNILIBS_DIR}" -name "*.so"); do
        if [ -f "$so_file" ]; then
            ${STRIP} "$so_file" || true
        fi
    done
    
    # 复制头文件供JNI使用
    mkdir -p "app/src/main/cpp/include/gphoto2"
    cp -r "${PREFIX}/include/gphoto2/"* "app/src/main/cpp/include/gphoto2/" 2>/dev/null || true
    
    log_success "库文件复制完成"
    log_info "库文件位置: ${JNILIBS_DIR}"
    
    # 显示生成的文件
    log_info "生成的主库文件:"
    ls -lh "${JNILIBS_DIR}"/*.so* 2>/dev/null || true
    log_info "相机驱动模块:"
    ls "${CAMLIBS_DST}"/*.so 2>/dev/null | wc -l
    log_info "端口驱动模块:"
    ls "${IOLIBS_DST}"/*.so 2>/dev/null | wc -l
}

# 主流程
main() {
    log_info "开始构建 libgphoto2 for Android ${ABI}"
    log_info "NDK: ${NDK_PATH}"
    log_info "API Level: ${API_LEVEL}"
    log_info "Target: ${TARGET}"
    
    build_libtool
    build_libusb
    build_libgphoto2
    copy_to_jniLibs
    
    log_success "所有编译任务完成!"
    log_info "可以开始Android项目构建了"
}

# 执行主流程
main
