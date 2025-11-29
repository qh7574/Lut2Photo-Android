// gphoto2_jni.cpp - libgphoto2 JNI 接口实现
// 使用 libgphoto2 官方 Android API: gp_port_usb_set_sys_device()
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <utility>
#include <cstdlib>
#include <cstdint>
#include <mutex>
#include <algorithm>
#include <gphoto2/gphoto2.h>
#include <gphoto2/gphoto2-port-info-list.h>

#define LOG_TAG "GPhoto2JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全局变量
static Camera *camera = nullptr;
static GPContext *context = nullptr;
static int g_usb_fd = -1;  // Android USB 文件描述符
static std::mutex camera_mutex;  // 保护 camera 和 context 的互斥锁

// ==================== 辅助函数 ====================

// 创建 Java 字符串
jstring createJavaString(JNIEnv *env, const char *str) {
    if (str == nullptr) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(str);
}

// 获取 C 字符串
std::string getStdString(JNIEnv *env, jstring jstr) {
    if (jstr == nullptr) {
        return "";
    }
    const char *str = env->GetStringUTFChars(jstr, nullptr);
    std::string result(str);
    env->ReleaseStringUTFChars(jstr, str);
    return result;
}

// 存储库路径
static std::string g_camlibs_path;
static std::string g_iolibs_path;

// ==================== 初始化和释放 ====================

extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_initializeWithPaths(
        JNIEnv *env, jobject thiz, jstring camlibsPath, jstring iolibsPath) {
    LOGI("初始化 libgphoto2（带路径）...");
    
    // 使用互斥锁保护
    std::lock_guard<std::mutex> lock(camera_mutex);
    
    // 如果已有旧的对象，先彻底清理
    if (camera != nullptr) {
        LOGW("检测到旧的 camera 对象，先彻底清理");
        // 尝试释放 camera 对象
        // 注意：如果之前已经调用过 gp_camera_exit，这里可能会失败
        // 但我们仍然需要调用 gp_camera_unref 来释放引用计数
        gp_camera_unref(camera);
        camera = nullptr;
        LOGI("旧 camera 对象已释放");
    }
    if (context != nullptr) {
        LOGW("检测到旧的 context 对象，先清理");
        gp_context_unref(context);
        context = nullptr;
        LOGI("旧 context 对象已释放");
    }
    
    // 重置 USB 文件描述符
    if (g_usb_fd >= 0) {
        LOGI("重置旧的 USB 文件描述符: %d", g_usb_fd);
        gp_port_usb_set_sys_device(-1);
        g_usb_fd = -1;
    }
    
    // 保存库路径
    g_camlibs_path = getStdString(env, camlibsPath);
    g_iolibs_path = getStdString(env, iolibsPath);
    
    LOGI("camlibs 路径: %s", g_camlibs_path.c_str());
    LOGI("iolibs 路径: %s", g_iolibs_path.c_str());
    
    // 设置环境变量让 libgphoto2 找到驱动
    setenv("CAMLIBS", g_camlibs_path.c_str(), 1);
    setenv("IOLIBS", g_iolibs_path.c_str(), 1);
    
    // 创建上下文
    context = gp_context_new();
    if (context == nullptr) {
        LOGE("创建 GPContext 失败");
        return GP_ERROR;
    }
    
    // 创建相机对象
    int ret = gp_camera_new(&camera);
    if (ret < GP_OK) {
        LOGE("创建 Camera 对象失败: %s", gp_result_as_string(ret));
        gp_context_unref(context);
        context = nullptr;
        return ret;
    }
    
    LOGI("libgphoto2 初始化成功（全新实例）");
    return GP_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_initialize(
        JNIEnv *env, jobject thiz) {
    LOGI("初始化 libgphoto2...");
    
    // 使用互斥锁保护
    std::lock_guard<std::mutex> lock(camera_mutex);
    
    // 如果已有旧的对象，先彻底清理
    if (camera != nullptr) {
        LOGW("检测到旧的 camera 对象，先彻底清理");
        gp_camera_unref(camera);
        camera = nullptr;
        LOGI("旧 camera 对象已释放");
    }
    if (context != nullptr) {
        LOGW("检测到旧的 context 对象，先清理");
        gp_context_unref(context);
        context = nullptr;
        LOGI("旧 context 对象已释放");
    }
    
    // 重置 USB 文件描述符
    if (g_usb_fd >= 0) {
        LOGI("重置旧的 USB 文件描述符: %d", g_usb_fd);
        gp_port_usb_set_sys_device(-1);
        g_usb_fd = -1;
    }
    
    // 创建上下文
    context = gp_context_new();
    if (context == nullptr) {
        LOGE("创建 GPContext 失败");
        return GP_ERROR;
    }
    
    // 创建相机对象
    int ret = gp_camera_new(&camera);
    if (ret < GP_OK) {
        LOGE("创建 Camera 对象失败: %s", gp_result_as_string(ret));
        gp_context_unref(context);
        context = nullptr;
        return ret;
    }
    
    LOGI("libgphoto2 初始化成功（全新实例）");
    return GP_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_release(
        JNIEnv *env, jobject thiz) {
    LOGI("释放 libgphoto2 资源...");
    
    // 使用互斥锁保护 camera 访问
    std::lock_guard<std::mutex> lock(camera_mutex);
    
    // 释放 camera 对象
    // 注意：如果连接失败，camera 对象可能处于不一致状态
    // 需要先尝试 exit，如果失败则直接清空指针
    if (camera != nullptr) {
        LOGI("释放 camera 对象...");
        
        // 先尝试 exit（如果还没有 exit）
        // 这会清理端口资源，但可能失败（如果 USB 已断开）
        if (context != nullptr) {
            int ret = gp_camera_exit(camera, context);
            if (ret < GP_OK) {
                LOGW("gp_camera_exit 失败: %s (可能 USB 已断开，继续清理)", gp_result_as_string(ret));
            }
        }
        
        // 尝试 unref，如果失败则直接清空指针
        // 使用 try-catch 保护，防止崩溃
        try {
            gp_camera_unref(camera);
            LOGI("camera 对象已释放");
        } catch (...) {
            LOGE("gp_camera_unref 异常，直接清空指针");
        }
        
        camera = nullptr;
    }
    
    // 释放上下文
    if (context != nullptr) {
        gp_context_unref(context);
        context = nullptr;
        LOGI("上下文已释放");
    }
    
    // 重置 USB 系统设备文件描述符
    if (g_usb_fd >= 0) {
        gp_port_usb_set_sys_device(-1);
        g_usb_fd = -1;
        LOGI("已重置 USB 系统设备");
    }
    
    LOGI("libgphoto2 资源已彻底释放");
}

// ==================== 相机连接 ====================

extern "C" JNIEXPORT jstring JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_detectCamera(
        JNIEnv *env, jobject thiz) {
    LOGI("检测相机...");
    
    if (camera == nullptr || context == nullptr) {
        LOGE("libgphoto2 未初始化");
        return createJavaString(env, "");
    }
    
    // 自动检测相机
    int ret = gp_camera_init(camera, context);
    if (ret < GP_OK) {
        LOGE("相机检测失败: %s", gp_result_as_string(ret));
        return createJavaString(env, "");
    }
    
    // 获取相机能力
    CameraAbilities abilities;
    ret = gp_camera_get_abilities(camera, &abilities);
    if (ret < GP_OK) {
        LOGE("获取相机能力失败: %s", gp_result_as_string(ret));
        gp_camera_exit(camera, context);
        return createJavaString(env, "");
    }
    
    LOGI("检测到相机: %s", abilities.model);
    
    // 打印相机能力信息
    LOGI("相机能力:");
    LOGI("  file_operations: 0x%x", abilities.file_operations);
    LOGI("  folder_operations: 0x%x", abilities.folder_operations);
    LOGI("  operations: 0x%x", abilities.operations);
    
    // 检查是否支持文件列表
    if (abilities.file_operations & GP_FILE_OPERATION_DELETE) {
        LOGI("  支持: 删除文件");
    }
    if (abilities.folder_operations & GP_FOLDER_OPERATION_PUT_FILE) {
        LOGI("  支持: 上传文件");
    }
    if (abilities.folder_operations & GP_FOLDER_OPERATION_MAKE_DIR) {
        LOGI("  支持: 创建目录");
    }
    if (abilities.folder_operations & GP_FOLDER_OPERATION_REMOVE_DIR) {
        LOGI("  支持: 删除目录");
    }
    if (abilities.operations & GP_OPERATION_CAPTURE_IMAGE) {
        LOGI("  支持: 拍摄图像");
    }
    if (abilities.operations & GP_OPERATION_CAPTURE_VIDEO) {
        LOGI("  支持: 拍摄视频");
    }
    if (abilities.operations & GP_OPERATION_CONFIG) {
        LOGI("  支持: 配置");
    }
    
    // 注意：不要在这里调用 gp_camera_exit，因为相机已经在 connectCamera 中初始化
    // 调用 exit 会清空文件系统缓存，导致后续无法列出文件
    // gp_camera_exit(camera, context);
    
    return createJavaString(env, abilities.model);
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_connectCamera(
        JNIEnv *env, jobject thiz) {
    LOGI("连接相机...");
    
    if (camera == nullptr || context == nullptr) {
        LOGE("libgphoto2 未初始化");
        return GP_ERROR;
    }
    
    // 初始化相机连接
    int ret = gp_camera_init(camera, context);
    if (ret < GP_OK) {
        LOGE("相机连接失败: %s", gp_result_as_string(ret));
        return ret;
    }
    
    // 获取相机信息
    CameraAbilities abilities;
    ret = gp_camera_get_abilities(camera, &abilities);
    if (ret >= GP_OK) {
        LOGI("相机连接成功: %s", abilities.model);
    }
    
    return GP_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_connectCameraWithFd(
        JNIEnv *env, jobject thiz, jint fd) {
    LOGI("使用 USB fd=%d 连接相机 (Android 模式)...", fd);
    
    if (camera == nullptr || context == nullptr) {
        LOGE("libgphoto2 未初始化");
        return GP_ERROR;
    }
    
    // 保存文件描述符
    g_usb_fd = fd;
    
    // ========== 使用 libgphoto2 官方 Android API ==========
    // gp_port_usb_set_sys_device() 是 libgphoto2 提供的官方 Android 支持
    // 它必须在 gp_camera_init() 之前调用
    // libgphoto2 内部会自动处理 libusb_wrap_sys_device() 等操作
    
    LOGI("设置 USB 系统设备文件描述符: %d", fd);
    int ret = gp_port_usb_set_sys_device(fd);
    if (ret < GP_OK) {
        LOGE("gp_port_usb_set_sys_device 失败: %s", gp_result_as_string(ret));
        return ret;
    }
    LOGI("gp_port_usb_set_sys_device 成功");
    
    // 验证设置
    int current_fd = gp_port_usb_get_sys_device();
    LOGI("当前 USB 系统设备 fd: %d", current_fd);
    
    // 尝试列出可用的端口（调试用）
    GPPortInfoList *port_info_list = nullptr;
    ret = gp_port_info_list_new(&port_info_list);
    if (ret >= GP_OK) {
        ret = gp_port_info_list_load(port_info_list);
        LOGI("加载端口列表: %s (count=%d)", gp_result_as_string(ret), 
             ret >= GP_OK ? gp_port_info_list_count(port_info_list) : 0);
        
        // 列出所有端口
        int count = gp_port_info_list_count(port_info_list);
        for (int i = 0; i < count; i++) {
            GPPortInfo info;
            if (gp_port_info_list_get_info(port_info_list, i, &info) >= GP_OK) {
                char *name = nullptr;
                char *path = nullptr;
                gp_port_info_get_name(info, &name);
                gp_port_info_get_path(info, &path);
                LOGI("端口 %d: name=%s, path=%s", i, name ? name : "null", path ? path : "null");
            }
        }
        
        gp_port_info_list_free(port_info_list);
    }
    
    // 初始化相机连接
    // libgphoto2 会自动使用我们设置的文件描述符
    ret = gp_camera_init(camera, context);
    if (ret < GP_OK) {
        LOGE("相机连接失败: %s", gp_result_as_string(ret));
        // 清理：重置文件描述符
        gp_port_usb_set_sys_device(-1);
        return ret;
    }
    
    // 获取相机信息
    CameraAbilities abilities;
    ret = gp_camera_get_abilities(camera, &abilities);
    if (ret >= GP_OK) {
        LOGI("相机连接成功: %s", abilities.model);
    }
    
    // 注意：不在这里主动初始化文件系统，因为可能会阻塞很长时间
    // Panasonic 相机的文件系统需要通过拍照事件或手动刷新来激活
    // 这是已知的相机行为，在 BottomSheet 中已经提供了友好提示和刷新功能
    
    return GP_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_disconnectCamera(
        JNIEnv *env, jobject thiz) {
    LOGI("断开相机连接...");
    
    // 使用互斥锁保护 camera 访问
    std::lock_guard<std::mutex> lock(camera_mutex);
    
    if (camera != nullptr && context != nullptr) {
        // gp_camera_exit 会关闭与相机的连接并释放端口资源
        // 但不会释放 camera 对象本身
        int ret = gp_camera_exit(camera, context);
        if (ret < GP_OK) {
            LOGW("gp_camera_exit 返回错误: %s (可能 USB 已断开)", gp_result_as_string(ret));
        }
        LOGI("相机连接已关闭");
        
        // 释放 camera 对象，为下次重新连接做准备
        // 这样下次 init 时会创建全新的 camera 对象
        gp_camera_unref(camera);
        camera = nullptr;
        LOGI("camera 对象已释放");
    }
    
    // 释放 context，为下次重新连接做准备
    if (context != nullptr) {
        gp_context_unref(context);
        context = nullptr;
        LOGI("context 对象已释放");
    }
    
    // 重置 USB 文件描述符
    if (g_usb_fd >= 0) {
        gp_port_usb_set_sys_device(-1);
        g_usb_fd = -1;
        LOGI("已重置 USB 系统设备");
    }
    
    LOGI("相机断开完成，资源已清理");
}

// ==================== 照片操作 ====================

// 递归列出文件夹中的所有照片
static void listFilesRecursive(const char *folder, std::vector<std::pair<std::string, std::string>> &files) {
    if (camera == nullptr || context == nullptr) {
        return;
    }
    
    CameraList *fileList;
    CameraList *folderList;
    int ret;
    
    // 列出当前文件夹中的文件
    ret = gp_list_new(&fileList);
    if (ret >= GP_OK) {
        ret = gp_camera_folder_list_files(camera, folder, fileList, context);
        if (ret >= GP_OK) {
            int fileCount = gp_list_count(fileList);
            for (int i = 0; i < fileCount; i++) {
                const char *name;
                gp_list_get_name(fileList, i, &name);
                files.push_back(std::make_pair(std::string(folder), std::string(name)));
            }
        }
        gp_list_free(fileList);
    }
    
    // 列出子文件夹并递归
    ret = gp_list_new(&folderList);
    if (ret >= GP_OK) {
        ret = gp_camera_folder_list_folders(camera, folder, folderList, context);
        if (ret >= GP_OK) {
            int folderCount = gp_list_count(folderList);
            for (int i = 0; i < folderCount; i++) {
                const char *name;
                gp_list_get_name(folderList, i, &name);
                
                // 构建子文件夹路径
                std::string subFolder = std::string(folder);
                if (subFolder != "/") {
                    subFolder += "/";
                }
                subFolder += name;
                
                // 递归列出子文件夹
                listFilesRecursive(subFolder.c_str(), files);
            }
        }
        gp_list_free(folderList);
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeListPhotos(
        JNIEnv *env, jobject thiz) {
    LOGI("获取照片列表...");
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return env->NewObjectArray(0, 
            env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/PhotoInfo"), 
            nullptr);
    }
    
    // 递归获取所有照片
    // 注意：Panasonic 相机需要在连接后等待约 3 秒让存储卡挂载
    std::vector<std::pair<std::string, std::string>> files;
    
    // 从根目录递归扫描所有文件
    listFilesRecursive("/", files);
    
    int count = files.size();
    LOGI("总共找到 %d 张照片", count);
    
    // 创建 Java 对象数组
    jclass photoInfoClass = env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/PhotoInfo");
    jmethodID constructor = env->GetMethodID(photoInfoClass, "<init>", 
        "(Ljava/lang/String;Ljava/lang/String;JJ)V");
    
    jobjectArray result = env->NewObjectArray(count, photoInfoClass, nullptr);
    
    for (int i = 0; i < count; i++) {
        const std::string &folder = files[i].first;
        const std::string &name = files[i].second;
        
        // 获取文件信息
        CameraFileInfo info;
        int ret = gp_camera_file_get_info(camera, folder.c_str(), name.c_str(), &info, context);
        
        long size = 0;
        long timestamp = 0;
        
        if (ret >= GP_OK) {
            if (info.file.fields & GP_FILE_INFO_SIZE) {
                size = info.file.size;
            }
            if (info.file.fields & GP_FILE_INFO_MTIME) {
                timestamp = info.file.mtime;
            }
        }
        
        // 创建 PhotoInfo 对象
        // path 应该是完整路径（folder + "/" + name）
        std::string fullPath = folder;
        if (fullPath != "/" && !fullPath.empty()) {
            fullPath += "/";
        }
        fullPath += name;
        
        jstring jpath = createJavaString(env, fullPath.c_str());
        jstring jname = createJavaString(env, name.c_str());
        
        jobject photoInfo = env->NewObject(photoInfoClass, constructor,
            jpath, jname, (jlong)size, (jlong)timestamp);
        
        env->SetObjectArrayElement(result, i, photoInfo);
        
        env->DeleteLocalRef(jpath);
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(photoInfo);
    }
    
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeGetThumbnail(
        JNIEnv *env, jobject thiz, jstring photoPath) {
    std::string path = getStdString(env, photoPath);
    LOGI("获取缩略图: %s", path.c_str());
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return nullptr;
    }
    
    // 分离文件夹和文件名
    size_t pos = path.find_last_of('/');
    std::string folder = (pos != std::string::npos) ? path.substr(0, pos) : "/";
    std::string name = (pos != std::string::npos) ? path.substr(pos + 1) : path;
    
    if (folder.empty()) folder = "/";
    
    // 创建文件对象
    CameraFile *file;
    int ret = gp_file_new(&file);
    if (ret < GP_OK) {
        LOGE("创建文件对象失败: %s", gp_result_as_string(ret));
        return nullptr;
    }
    
    // 获取缩略图
    ret = gp_camera_file_get(camera, folder.c_str(), name.c_str(), 
        GP_FILE_TYPE_PREVIEW, file, context);
    
    if (ret < GP_OK) {
        LOGE("获取缩略图失败: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        return nullptr;
    }
    
    // 获取数据
    const char *data;
    unsigned long size;
    ret = gp_file_get_data_and_size(file, &data, &size);
    
    if (ret < GP_OK) {
        LOGE("获取缩略图数据失败: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        return nullptr;
    }
    
    // 创建 Java 字节数组
    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, (const jbyte*)data);
    
    gp_file_unref(file);
    
    LOGI("缩略图获取成功，大小: %lu 字节", size);
    
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeDownloadPhoto(
        JNIEnv *env, jobject thiz, jstring photoPath, jstring destPath) {
    std::string path = getStdString(env, photoPath);
    std::string dest = getStdString(env, destPath);
    
    LOGI("下载照片: %s -> %s", path.c_str(), dest.c_str());
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return GP_ERROR;
    }
    
    // 分离文件夹和文件名
    size_t pos = path.find_last_of('/');
    std::string folder = (pos != std::string::npos) ? path.substr(0, pos) : "/";
    std::string name = (pos != std::string::npos) ? path.substr(pos + 1) : path;
    
    if (folder.empty()) folder = "/";
    
    // 创建文件对象
    CameraFile *file;
    int ret = gp_file_new(&file);
    if (ret < GP_OK) {
        LOGE("创建文件对象失败: %s", gp_result_as_string(ret));
        return ret;
    }
    
    // 下载照片
    ret = gp_camera_file_get(camera, folder.c_str(), name.c_str(), 
        GP_FILE_TYPE_NORMAL, file, context);
    
    if (ret < GP_OK) {
        LOGE("下载照片失败: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        return ret;
    }
    
    // 保存到文件
    ret = gp_file_save(file, dest.c_str());
    
    if (ret < GP_OK) {
        LOGE("保存照片失败: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        return ret;
    }
    
    gp_file_unref(file);
    
    LOGI("照片下载成功");
    
    return GP_OK;
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeGetFileSize(
        JNIEnv *env, jobject thiz, jstring photoPath) {
    std::string path = getStdString(env, photoPath);
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return -1;
    }
    
    // 分离文件夹和文件名
    size_t pos = path.find_last_of('/');
    std::string folder = (pos != std::string::npos) ? path.substr(0, pos) : "/";
    std::string name = (pos != std::string::npos) ? path.substr(pos + 1) : path;
    
    if (folder.empty()) folder = "/";
    
    // 获取文件信息
    CameraFileInfo info;
    int ret = gp_camera_file_get_info(camera, folder.c_str(), name.c_str(), &info, context);
    
    if (ret < GP_OK) {
        LOGE("获取文件信息失败: %s", gp_result_as_string(ret));
        return -1;
    }
    
    if (info.file.fields & GP_FILE_INFO_SIZE) {
        return (jlong)info.file.size;
    }
    
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeDownloadPhotoChunk(
        JNIEnv *env, jobject thiz, jstring photoPath, jstring destPath, jlong offset, jint chunkSize) {
    std::string path = getStdString(env, photoPath);
    std::string dest = getStdString(env, destPath);
    
    LOGI("流式下载照片块: %s, offset=%lld, size=%d", path.c_str(), (long long)offset, chunkSize);
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return GP_ERROR;
    }
    
    // 分离文件夹和文件名
    size_t pos = path.find_last_of('/');
    std::string folder = (pos != std::string::npos) ? path.substr(0, pos) : "/";
    std::string name = (pos != std::string::npos) ? path.substr(pos + 1) : path;
    
    if (folder.empty()) folder = "/";
    
    // 分配缓冲区
    std::vector<char> buffer(chunkSize);
    uint64_t readSize = chunkSize;
    
    // 使用 gp_camera_file_read 进行流式读取
    // 这个函数支持从指定偏移量读取指定大小的数据，不需要将整个文件加载到内存
    int ret = gp_camera_file_read(camera, folder.c_str(), name.c_str(),
                                   GP_FILE_TYPE_NORMAL,
                                   (uint64_t)offset, buffer.data(), &readSize,
                                   context);
    
    if (ret < GP_OK) {
        LOGE("流式读取照片失败: %s", gp_result_as_string(ret));
        return ret;
    }
    
    LOGI("从相机读取了 %llu 字节", (unsigned long long)readSize);
    
    // 打开文件追加写入
    FILE *fp = fopen(dest.c_str(), offset == 0 ? "wb" : "ab");
    if (fp == nullptr) {
        LOGE("打开目标文件失败: %s", dest.c_str());
        return GP_ERROR;
    }
    
    // 写入数据块
    size_t written = fwrite(buffer.data(), 1, readSize, fp);
    fclose(fp);
    
    if (written != readSize) {
        LOGE("写入数据失败: written=%zu, expected=%llu", written, (unsigned long long)readSize);
        return GP_ERROR;
    }
    
    LOGI("照片块写入成功: %zu 字节 (offset=%lld)", written, (long long)offset);
    
    return GP_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeDeletePhoto(
        JNIEnv *env, jobject thiz, jstring photoPath) {
    std::string path = getStdString(env, photoPath);
    LOGI("删除照片: %s", path.c_str());
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return GP_ERROR;
    }
    
    // 分离文件夹和文件名
    size_t pos = path.find_last_of('/');
    std::string folder = (pos != std::string::npos) ? path.substr(0, pos) : "/";
    std::string name = (pos != std::string::npos) ? path.substr(pos + 1) : path;
    
    if (folder.empty()) folder = "/";
    
    // 删除文件
    int ret = gp_camera_file_delete(camera, folder.c_str(), name.c_str(), context);
    
    if (ret < GP_OK) {
        LOGE("删除照片失败: %s", gp_result_as_string(ret));
        return ret;
    }
    
    LOGI("照片删除成功");
    
    return GP_OK;
}

// ==================== 事件监听 ====================

extern "C" JNIEXPORT jobject JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeWaitForEvent(
        JNIEnv *env, jobject thiz, jint timeout) {
    
    jclass eventClass = env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/CameraEvent");
    jmethodID constructor = env->GetMethodID(eventClass, "<init>", "(ILjava/lang/String;)V");
    
    // 使用互斥锁保护 camera 访问
    std::lock_guard<std::mutex> lock(camera_mutex);
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        // 返回超时事件
        return env->NewObject(eventClass, constructor, 1, createJavaString(env, ""));
    }
    
    CameraEventType eventType;
    void *eventData = nullptr;
    
    int ret = gp_camera_wait_for_event(camera, timeout, &eventType, &eventData, context);
    
    if (ret < GP_OK) {
        const char* errorStr = gp_result_as_string(ret);
        LOGE("等待事件失败: %s", errorStr);
        // 返回错误事件（type=-1），并将错误信息作为 data 传递
        return env->NewObject(eventClass, constructor, -1, createJavaString(env, errorStr));
    }
    
    int javaEventType = 0;
    std::string eventDataStr = "";
    
    switch (eventType) {
        case GP_EVENT_TIMEOUT:
            javaEventType = 1;
            break;
        case GP_EVENT_FILE_ADDED:
            javaEventType = 2;
            if (eventData != nullptr) {
                CameraFilePath *path = (CameraFilePath*)eventData;
                eventDataStr = std::string(path->folder) + "/" + std::string(path->name);
                LOGI("文件添加事件: %s", eventDataStr.c_str());
            }
            break;
        case GP_EVENT_FOLDER_ADDED:
            javaEventType = 3;
            if (eventData != nullptr) {
                CameraFilePath *path = (CameraFilePath*)eventData;
                eventDataStr = std::string(path->folder);
                LOGI("文件夹添加事件: %s", eventDataStr.c_str());
            }
            break;
        case GP_EVENT_CAPTURE_COMPLETE:
            javaEventType = 4;
            LOGI("拍摄完成事件");
            break;
        default:
            javaEventType = 0;
            break;
    }
    
    return env->NewObject(eventClass, constructor, javaEventType, 
        createJavaString(env, eventDataStr.c_str()));
}

// ==================== 相机配置 ====================

extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeListConfig(
        JNIEnv *env, jobject thiz) {
    LOGI("获取配置列表...");
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return env->NewObjectArray(0, 
            env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/ConfigItem"), 
            nullptr);
    }
    
    // 获取配置根节点
    CameraWidget *rootConfig;
    int ret = gp_camera_get_config(camera, &rootConfig, context);
    if (ret < GP_OK) {
        LOGE("获取配置失败: %s", gp_result_as_string(ret));
        return env->NewObjectArray(0, 
            env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/ConfigItem"), 
            nullptr);
    }
    
    // 遍历配置项（需要递归遍历所有层级）
    int childCount = gp_widget_count_children(rootConfig);
    LOGI("根配置节点有 %d 个子节点", childCount);
    
    std::vector<jobject> configItems;
    jclass configItemClass = env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/ConfigItem");
    jmethodID constructor = env->GetMethodID(configItemClass, "<init>", 
        "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;FFF)V");
    
    for (int i = 0; i < childCount; i++) {
        CameraWidget *child;
        ret = gp_widget_get_child(rootConfig, i, &child);
        if (ret < GP_OK) continue;
        
        const char *name = nullptr;
        const char *label = nullptr;
        CameraWidgetType type;
        
        gp_widget_get_name(child, &name);
        gp_widget_get_label(child, &label);
        gp_widget_get_type(child, &type);
        
        // 确保 name 和 label 不为空
        if (name == nullptr) name = "";
        if (label == nullptr) label = "";
        
        LOGI("配置项 %d: name=%s, label=%s, type=%d", i, name, label, type);
        
        // 如果是 SECTION 或 WINDOW 类型，需要递归遍历子节点
        if (type == GP_WIDGET_SECTION || type == GP_WIDGET_WINDOW) {
            int subChildCount = gp_widget_count_children(child);
            LOGI("  这是一个容器，有 %d 个子节点", subChildCount);
            
            // 递归遍历子节点
            for (int j = 0; j < subChildCount; j++) {
                CameraWidget *subChild;
                ret = gp_widget_get_child(child, j, &subChild);
                if (ret < GP_OK) continue;
                
                const char *subName = nullptr;
                const char *subLabel = nullptr;
                CameraWidgetType subType;
                
                gp_widget_get_name(subChild, &subName);
                gp_widget_get_label(subChild, &subLabel);
                gp_widget_get_type(subChild, &subType);
                
                // 确保 name 和 label 不为空
                if (subName == nullptr) subName = "";
                if (subLabel == nullptr) subLabel = "";
                
                // 复制字符串，避免指针失效
                std::string subNameStr(subName);
                std::string subLabelStr(subLabel);
                
                LOGI("  子配置项 %d: name=%s, label=%s, type=%d", j, subNameStr.c_str(), subLabelStr.c_str(), subType);
                
                // 检查 subChild 是否有效
                if (subChild == nullptr) {
                    LOGE("  subChild 为空，跳过");
                    continue;
                }
                
                // 跳过容器类型
                if (subType == GP_WIDGET_SECTION || subType == GP_WIDGET_WINDOW) {
                    LOGI("  跳过容器类型");
                    continue;
                }
                
                // 转换类型
                int javaSubType = 0;
                switch (subType) {
                    case GP_WIDGET_TEXT: javaSubType = 0; break;
                    case GP_WIDGET_RANGE: javaSubType = 1; break;
                    case GP_WIDGET_TOGGLE: javaSubType = 2; break;
                    case GP_WIDGET_RADIO: javaSubType = 3; break;
                    case GP_WIDGET_MENU: javaSubType = 4; break;
                    case GP_WIDGET_BUTTON: javaSubType = 5; break;
                    case GP_WIDGET_DATE: javaSubType = 6; break;
                    default: continue;
                }
                
                // 获取当前值（根据类型使用不同的方式）
                std::string subValueStr = "";
                if (subType == GP_WIDGET_TEXT || subType == GP_WIDGET_RADIO || subType == GP_WIDGET_MENU) {
                    const char *textValue = nullptr;
                    int valueRet = gp_widget_get_value(subChild, &textValue);
                    if (valueRet >= GP_OK && textValue != nullptr) {
                        subValueStr = textValue;
                    }
                } else if (subType == GP_WIDGET_TOGGLE) {
                    int toggleValue = 0;
                    int valueRet = gp_widget_get_value(subChild, &toggleValue);
                    if (valueRet >= GP_OK) {
                        subValueStr = std::to_string(toggleValue);
                    }
                } else if (subType == GP_WIDGET_RANGE) {
                    float rangeValue = 0.0f;
                    int valueRet = gp_widget_get_value(subChild, &rangeValue);
                    if (valueRet >= GP_OK) {
                        subValueStr = std::to_string(rangeValue);
                    }
                } else if (subType == GP_WIDGET_DATE) {
                    int dateValue = 0;
                    int valueRet = gp_widget_get_value(subChild, &dateValue);
                    if (valueRet >= GP_OK) {
                        subValueStr = std::to_string(dateValue);
                    }
                }
                LOGI("    值: %s", subValueStr.c_str());
                
                // 获取选项列表（对于 RADIO 和 MENU 类型）
                jobjectArray choices = nullptr;
                if (subType == GP_WIDGET_RADIO || subType == GP_WIDGET_MENU) {
                    LOGI("    准备获取 %s 的选项列表", subNameStr.c_str());
                    int choiceCount = gp_widget_count_choices(subChild);
                    LOGI("    配置项 %s 有 %d 个选项", subNameStr.c_str(), choiceCount);
                    if (choiceCount > 0) {
                        jclass stringClass = env->FindClass("java/lang/String");
                        if (stringClass == nullptr) {
                            LOGE("    无法找到 String 类");
                            continue;
                        }
                        choices = env->NewObjectArray(choiceCount, stringClass, nullptr);
                        if (choices == nullptr) {
                            LOGE("    无法创建数组");
                            continue;
                        }
                        for (int k = 0; k < choiceCount; k++) {
                            const char *choice = nullptr;
                            int choiceRet = gp_widget_get_choice(subChild, k, &choice);
                            if (choiceRet >= GP_OK && choice != nullptr) {
                                jstring jchoice = createJavaString(env, choice);
                                if (jchoice != nullptr) {
                                    env->SetObjectArrayElement(choices, k, jchoice);
                                    env->DeleteLocalRef(jchoice);
                                }
                            } else {
                                LOGW("    选项 %d 获取失败或为空", k);
                                jstring jempty = createJavaString(env, "");
                                if (jempty != nullptr) {
                                    env->SetObjectArrayElement(choices, k, jempty);
                                    env->DeleteLocalRef(jempty);
                                }
                            }
                        }
                    }
                }
                
                // 创建 ConfigItem 对象
                jstring jsubName = createJavaString(env, subNameStr.c_str());
                jstring jsubLabel = createJavaString(env, subLabelStr.c_str());
                jstring jsubValue = createJavaString(env, subValueStr.c_str());
                
                jobject configItem = env->NewObject(configItemClass, constructor,
                    jsubName, jsubLabel, javaSubType, jsubValue, choices, 0.0f, 0.0f, 0.0f);
                
                configItems.push_back(configItem);
                
                env->DeleteLocalRef(jsubName);
                env->DeleteLocalRef(jsubLabel);
                env->DeleteLocalRef(jsubValue);
                if (choices) env->DeleteLocalRef(choices);
            }
            continue;
        }
        
        // 转换类型
        int javaType = 0;
        switch (type) {
            case GP_WIDGET_TEXT: javaType = 0; break;
            case GP_WIDGET_RANGE: javaType = 1; break;
            case GP_WIDGET_TOGGLE: javaType = 2; break;
            case GP_WIDGET_RADIO: javaType = 3; break;
            case GP_WIDGET_MENU: javaType = 4; break;
            case GP_WIDGET_BUTTON: javaType = 5; break;
            case GP_WIDGET_DATE: javaType = 6; break;
            default: continue;
        }
        
        // 获取当前值（根据类型使用不同的方式）
        std::string valueStr = "";
        if (type == GP_WIDGET_TEXT || type == GP_WIDGET_RADIO || type == GP_WIDGET_MENU) {
            const char *textValue = nullptr;
            int valueRet = gp_widget_get_value(child, &textValue);
            if (valueRet >= GP_OK && textValue != nullptr) {
                valueStr = textValue;
            }
        } else if (type == GP_WIDGET_TOGGLE) {
            int toggleValue = 0;
            int valueRet = gp_widget_get_value(child, &toggleValue);
            if (valueRet >= GP_OK) {
                valueStr = std::to_string(toggleValue);
            }
        } else if (type == GP_WIDGET_RANGE) {
            float rangeValue = 0.0f;
            int valueRet = gp_widget_get_value(child, &rangeValue);
            if (valueRet >= GP_OK) {
                valueStr = std::to_string(rangeValue);
            }
        } else if (type == GP_WIDGET_DATE) {
            int dateValue = 0;
            int valueRet = gp_widget_get_value(child, &dateValue);
            if (valueRet >= GP_OK) {
                valueStr = std::to_string(dateValue);
            }
        }
        
        // 获取选项列表（对于 RADIO 和 MENU 类型）
        jobjectArray choices = nullptr;
        if (type == GP_WIDGET_RADIO || type == GP_WIDGET_MENU) {
            int choiceCount = gp_widget_count_choices(child);
            if (choiceCount > 0) {
                jclass stringClass = env->FindClass("java/lang/String");
                choices = env->NewObjectArray(choiceCount, stringClass, nullptr);
                for (int k = 0; k < choiceCount; k++) {
                    const char *choice = nullptr;
                    int ret = gp_widget_get_choice(child, k, &choice);
                    if (ret >= GP_OK && choice != nullptr) {
                        env->SetObjectArrayElement(choices, k, createJavaString(env, choice));
                    } else {
                        env->SetObjectArrayElement(choices, k, createJavaString(env, ""));
                    }
                }
            }
        }
        
        // 创建 ConfigItem 对象
        jstring jname = createJavaString(env, name);
        jstring jlabel = createJavaString(env, label);
        jstring jvalue = createJavaString(env, valueStr.c_str());
        
        jobject configItem = env->NewObject(configItemClass, constructor,
            jname, jlabel, javaType, jvalue, choices, 0.0f, 0.0f, 0.0f);
        
        configItems.push_back(configItem);
        
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(jlabel);
        env->DeleteLocalRef(jvalue);
        if (choices) env->DeleteLocalRef(choices);
    }
    
    gp_widget_free(rootConfig);
    
    // 创建数组
    jobjectArray result = env->NewObjectArray(configItems.size(), configItemClass, nullptr);
    for (size_t i = 0; i < configItems.size(); i++) {
        env->SetObjectArrayElement(result, i, configItems[i]);
        env->DeleteLocalRef(configItems[i]);
    }
    
    LOGI("获取到 %zu 个配置项", configItems.size());
    
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeGetConfig(
        JNIEnv *env, jobject thiz, jstring configName) {
    std::string name = getStdString(env, configName);
    LOGI("获取配置: %s", name.c_str());
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return nullptr;
    }
    
    CameraWidget *widget;
    int ret = gp_camera_get_single_config(camera, name.c_str(), &widget, context);
    
    if (ret < GP_OK) {
        LOGE("获取配置失败: %s", gp_result_as_string(ret));
        return nullptr;
    }
    
    const char *label;
    CameraWidgetType type;
    char *value = nullptr;
    
    gp_widget_get_label(widget, &label);
    gp_widget_get_type(widget, &type);
    gp_widget_get_value(widget, &value);
    
    // 转换类型
    int javaType = 0;
    switch (type) {
        case GP_WIDGET_TEXT: javaType = 0; break;
        case GP_WIDGET_RANGE: javaType = 1; break;
        case GP_WIDGET_TOGGLE: javaType = 2; break;
        case GP_WIDGET_RADIO: javaType = 3; break;
        case GP_WIDGET_MENU: javaType = 4; break;
        case GP_WIDGET_BUTTON: javaType = 5; break;
        case GP_WIDGET_DATE: javaType = 6; break;
        default: break;
    }
    
    // 创建 ConfigItem 对象
    jclass configItemClass = env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/ConfigItem");
    jmethodID constructor = env->GetMethodID(configItemClass, "<init>", 
        "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;FFF)V");
    
    jstring jname = createJavaString(env, name.c_str());
    jstring jlabel = createJavaString(env, label);
    jstring jvalue = createJavaString(env, value ? value : "");
    
    jobject result = env->NewObject(configItemClass, constructor,
        jname, jlabel, javaType, jvalue, nullptr, 0.0f, 0.0f, 0.0f);
    
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jlabel);
    env->DeleteLocalRef(jvalue);
    
    gp_widget_free(widget);
    
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeSetConfig(
        JNIEnv *env, jobject thiz, jstring configName, jstring configValue) {
    std::string name = getStdString(env, configName);
    std::string value = getStdString(env, configValue);
    
    LOGI("设置配置: %s = %s", name.c_str(), value.c_str());
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return GP_ERROR;
    }
    
    // 黑名单：某些 Panasonic 相机参数会导致崩溃
    static const std::vector<std::string> blacklist = {
        "capturetarget",
        "capture-target",
        "d1a8"  // Panasonic Capture Target 的 PTP 属性 ID
    };
    
    std::string nameLower = name;
    std::transform(nameLower.begin(), nameLower.end(), nameLower.begin(), ::tolower);
    
    for (const auto& blocked : blacklist) {
        if (nameLower.find(blocked) != std::string::npos) {
            LOGW("参数 %s 在黑名单中，跳过设置", name.c_str());
            return GP_ERROR_NOT_SUPPORTED;
        }
    }
    
    CameraWidget *widget = nullptr;
    int ret = gp_camera_get_single_config(camera, name.c_str(), &widget, context);
    
    if (ret < GP_OK) {
        LOGE("获取配置失败: %s", gp_result_as_string(ret));
        return ret;
    }
    
    // 设置值
    ret = gp_widget_set_value(widget, value.c_str());
    
    if (ret < GP_OK) {
        LOGE("设置配置值失败: %s", gp_result_as_string(ret));
        if (widget) gp_widget_free(widget);
        return ret;
    }
    
    // 应用配置
    ret = gp_camera_set_single_config(camera, name.c_str(), widget, context);
    
    if (ret < GP_OK) {
        LOGE("应用配置失败: %s", gp_result_as_string(ret));
        if (widget) gp_widget_free(widget);
        return ret;
    }
    
    if (widget) gp_widget_free(widget);
    
    LOGI("配置设置成功");
    
    return GP_OK;
}

// ==================== 文件上传 ====================
// 注意：经测试，松下相机不支持通过 PTP 协议上传 VLT 文件
// 以下代码保留供参考，但不再使用

/*
extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeUploadFile(
        JNIEnv *env, jobject thiz, jstring localPath, jstring remotePath) {
    std::string local = getStdString(env, localPath);
    std::string remote = getStdString(env, remotePath);
    
    LOGI("上传文件: %s -> %s", local.c_str(), remote.c_str());
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return GP_ERROR;
    }
    
    // 分离远程路径的文件夹和文件名
    // remotePath 格式："/xxxxxx.vlt" 或 "/DCIM/xxxxxx.vlt"
    size_t pos = remote.find_last_of('/');
    std::string folder = (pos != std::string::npos) ? remote.substr(0, pos) : "/";
    std::string name = (pos != std::string::npos) ? remote.substr(pos + 1) : remote;
    
    if (folder.empty()) folder = "/";
    
    LOGI("目标文件夹: %s, 文件名: %s", folder.c_str(), name.c_str());
    
    // 检查相机能力
    CameraAbilities abilities;
    int ret = gp_camera_get_abilities(camera, &abilities);
    if (ret >= GP_OK) {
        LOGI("相机能力检查:");
        if (abilities.folder_operations & GP_FOLDER_OPERATION_PUT_FILE) {
            LOGI("  ✓ 支持上传文件");
        } else {
            LOGE("  ✗ 不支持上传文件");
            return GP_ERROR_NOT_SUPPORTED;
        }
    }
    
    // 注意：文件夹存在性检查已在 Kotlin 层完成（通过 getFirstAvailableFolder）
    // 这里直接尝试上传
    
    // 创建 CameraFile 对象并从本地文件加载
    CameraFile *file;
    ret = gp_file_new(&file);
    if (ret < GP_OK) {
        LOGE("创建文件对象失败: %s", gp_result_as_string(ret));
        return ret;
    }
    
    // 从本地文件加载数据
    ret = gp_file_open(file, local.c_str());
    if (ret < GP_OK) {
        LOGE("打开本地文件失败: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        return ret;
    }
    
    // 设置文件名
    ret = gp_file_set_name(file, name.c_str());
    if (ret < GP_OK) {
        LOGE("设置文件名失败: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        return ret;
    }
    
    // 上传文件到相机
    ret = gp_camera_folder_put_file(camera, folder.c_str(), name.c_str(), 
                                     GP_FILE_TYPE_NORMAL, file, context);
    
    if (ret < GP_OK) {
        LOGE("上传文件失败: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        return ret;
    }
    
    gp_file_unref(file);
    
    LOGI("文件上传成功");
    
    return GP_OK;
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_nativeGetFirstAvailableFolder(
        JNIEnv *env, jobject thiz) {
    LOGI("获取第一个可用文件夹...");
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        return nullptr;
    }
    
    CameraList *folderList;
    int ret = gp_list_new(&folderList);
    if (ret < GP_OK) {
        LOGE("创建文件夹列表失败: %s", gp_result_as_string(ret));
        return nullptr;
    }
    
    // 列出根目录下的所有文件夹
    ret = gp_camera_folder_list_folders(camera, "/", folderList, context);
    if (ret < GP_OK) {
        LOGE("列出文件夹失败: %s", gp_result_as_string(ret));
        gp_list_free(folderList);
        return nullptr;
    }
    
    int count = gp_list_count(folderList);
    LOGI("根目录下有 %d 个文件夹", count);
    
    if (count == 0) {
        LOGE("根目录下没有文件夹");
        gp_list_free(folderList);
        return nullptr;
    }
    
    // 优先查找 DCIM 文件夹
    std::string targetFolder;
    bool foundDCIM = false;
    
    for (int i = 0; i < count; i++) {
        const char *name;
        gp_list_get_name(folderList, i, &name);
        LOGI("  - 文件夹 %d: %s", i, name);
        
        // 检查是否是 DCIM 或包含 DCIM 的文件夹
        std::string folderName(name);
        if (folderName.find("DCIM") != std::string::npos || 
            folderName.find("dcim") != std::string::npos) {
            targetFolder = std::string("/") + name;
            foundDCIM = true;
            LOGI("找到 DCIM 文件夹: %s", targetFolder.c_str());
            break;
        }
    }
    
    // 如果找到 DCIM，尝试查找其子文件夹
    if (foundDCIM) {
        CameraList *subFolderList;
        ret = gp_list_new(&subFolderList);
        if (ret >= GP_OK) {
            ret = gp_camera_folder_list_folders(camera, targetFolder.c_str(), subFolderList, context);
            if (ret >= GP_OK) {
                int subCount = gp_list_count(subFolderList);
                LOGI("DCIM 文件夹中有 %d 个子文件夹", subCount);
                
                if (subCount > 0) {
                    // 使用第一个子文件夹
                    const char *subName;
                    ret = gp_list_get_name(subFolderList, 0, &subName);
                    if (ret >= GP_OK && subName != nullptr) {
                        targetFolder = targetFolder + "/" + subName;
                        LOGI("使用 DCIM 子文件夹: %s", targetFolder.c_str());
                    }
                }
            }
            gp_list_free(subFolderList);
        }
        
        gp_list_free(folderList);
        return createJavaString(env, targetFolder.c_str());
    }
    
    // 如果没有找到 DCIM，检查第一个文件夹的子文件夹
    const char *firstFolder = nullptr;
    ret = gp_list_get_name(folderList, 0, &firstFolder);
    if (ret < GP_OK || firstFolder == nullptr) {
        LOGE("获取第一个文件夹失败: %s", gp_result_as_string(ret));
        gp_list_free(folderList);
        return nullptr;
    }
    
    std::string firstFolderPath = std::string("/") + firstFolder;
    LOGI("检查第一个文件夹的子文件夹: %s", firstFolderPath.c_str());
    
    // 列出第一个文件夹的子文件夹
    CameraList *subFolderList;
    ret = gp_list_new(&subFolderList);
    if (ret >= GP_OK) {
        ret = gp_camera_folder_list_folders(camera, firstFolderPath.c_str(), subFolderList, context);
        if (ret >= GP_OK) {
            int subCount = gp_list_count(subFolderList);
            LOGI("%s 中有 %d 个子文件夹", firstFolderPath.c_str(), subCount);
            
            // 在子文件夹中查找 DCIM
            for (int i = 0; i < subCount; i++) {
                const char *subName;
                gp_list_get_name(subFolderList, i, &subName);
                LOGI("  - 子文件夹 %d: %s", i, subName);
                
                std::string subFolderName(subName);
                if (subFolderName.find("DCIM") != std::string::npos || 
                    subFolderName.find("dcim") != std::string::npos) {
                    std::string dcimPath = firstFolderPath + "/" + subName;
                    LOGI("在子文件夹中找到 DCIM: %s", dcimPath.c_str());
                    
                    // 列出 DCIM 的子文件夹
                    CameraList *dcimSubList;
                    ret = gp_list_new(&dcimSubList);
                    if (ret >= GP_OK) {
                        ret = gp_camera_folder_list_folders(camera, dcimPath.c_str(), dcimSubList, context);
                        if (ret >= GP_OK) {
                            int dcimSubCount = gp_list_count(dcimSubList);
                            LOGI("DCIM 中有 %d 个子文件夹", dcimSubCount);
                            
                            if (dcimSubCount > 0) {
                                const char *dcimSubName;
                                ret = gp_list_get_name(dcimSubList, 0, &dcimSubName);
                                if (ret >= GP_OK && dcimSubName != nullptr) {
                                    std::string finalPath = dcimPath + "/" + dcimSubName;
                                    LOGI("使用 DCIM 子文件夹: %s", finalPath.c_str());
                                    gp_list_free(dcimSubList);
                                    gp_list_free(subFolderList);
                                    gp_list_free(folderList);
                                    return createJavaString(env, finalPath.c_str());
                                }
                            } else {
                                // DCIM 没有子文件夹，直接使用 DCIM
                                LOGI("使用 DCIM 文件夹: %s", dcimPath.c_str());
                                gp_list_free(dcimSubList);
                                gp_list_free(subFolderList);
                                gp_list_free(folderList);
                                return createJavaString(env, dcimPath.c_str());
                            }
                        }
                        gp_list_free(dcimSubList);
                    }
                    
                    gp_list_free(subFolderList);
                    gp_list_free(folderList);
                    return createJavaString(env, dcimPath.c_str());
                }
            }
            
            // 如果没有找到 DCIM，使用第一个子文件夹
            if (subCount > 0) {
                const char *firstSubName;
                ret = gp_list_get_name(subFolderList, 0, &firstSubName);
                if (ret >= GP_OK && firstSubName != nullptr) {
                    std::string subPath = firstFolderPath + "/" + firstSubName;
                    LOGI("使用第一个子文件夹: %s", subPath.c_str());
                    gp_list_free(subFolderList);
                    gp_list_free(folderList);
                    return createJavaString(env, subPath.c_str());
                }
            }
        }
        gp_list_free(subFolderList);
    }
    
    // 如果都失败了，使用第一个文件夹本身
    LOGI("使用第一个文件夹: %s", firstFolderPath.c_str());
    gp_list_free(folderList);
    return createJavaString(env, firstFolderPath.c_str());
}

*/
