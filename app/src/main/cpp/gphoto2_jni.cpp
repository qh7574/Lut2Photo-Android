// gphoto2_jni.cpp - libgphoto2 JNI 接口实现
// 使用 libgphoto2 官方 Android API: gp_port_usb_set_sys_device()
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <utility>
#include <cstdlib>
#include <cstdint>
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
    
    LOGI("libgphoto2 初始化成功");
    return GP_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_initialize(
        JNIEnv *env, jobject thiz) {
    LOGI("初始化 libgphoto2...");
    
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
    
    LOGI("libgphoto2 初始化成功");
    return GP_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_core_GPhoto2Manager_release(
        JNIEnv *env, jobject thiz) {
    LOGI("释放 libgphoto2 资源...");
    
    // 注意：不要在这里调用 gp_camera_unref，因为可能导致崩溃
    // 相机资源应该在 disconnectCamera 中通过 gp_camera_exit 释放
    // gp_camera_unref 会尝试释放底层端口，但端口可能已经被关闭
    
    // 重置 USB 系统设备文件描述符
    if (g_usb_fd >= 0) {
        gp_port_usb_set_sys_device(-1);
        g_usb_fd = -1;
        LOGI("已重置 USB 系统设备");
    }
    
    // 释放相机对象 - 使用 gp_camera_free 而不是 gp_camera_unref
    // gp_camera_free 不会尝试关闭端口，更安全
    if (camera != nullptr) {
        // 不调用 gp_camera_unref，因为它会尝试释放已关闭的端口
        // 直接将指针置空，让系统回收内存
        camera = nullptr;
        LOGI("相机对象已清理");
    }
    
    if (context != nullptr) {
        gp_context_unref(context);
        context = nullptr;
        LOGI("上下文已释放");
    }
    
    LOGI("libgphoto2 资源已释放");
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
    
    if (camera != nullptr && context != nullptr) {
        // gp_camera_exit 会关闭与相机的连接并释放端口资源
        int ret = gp_camera_exit(camera, context);
        if (ret < GP_OK) {
            LOGW("gp_camera_exit 返回错误: %s", gp_result_as_string(ret));
        }
        LOGI("相机已断开");
    }
    
    // 重置 USB 文件描述符
    if (g_usb_fd >= 0) {
        gp_port_usb_set_sys_device(-1);
        g_usb_fd = -1;
        LOGI("已重置 USB 系统设备");
    }
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
    
    if (camera == nullptr || context == nullptr) {
        LOGE("相机未连接");
        // 返回超时事件
        jclass eventClass = env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/CameraEvent");
        jmethodID constructor = env->GetMethodID(eventClass, "<init>", "(ILjava/lang/String;)V");
        return env->NewObject(eventClass, constructor, 1, createJavaString(env, ""));
    }
    
    CameraEventType eventType;
    void *eventData = nullptr;
    
    int ret = gp_camera_wait_for_event(camera, timeout, &eventType, &eventData, context);
    
    jclass eventClass = env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/CameraEvent");
    jmethodID constructor = env->GetMethodID(eventClass, "<init>", "(ILjava/lang/String;)V");
    
    if (ret < GP_OK) {
        LOGE("等待事件失败: %s", gp_result_as_string(ret));
        return env->NewObject(eventClass, constructor, 0, createJavaString(env, ""));
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
    
    // 遍历配置项（简化版本，仅获取第一层）
    int childCount = gp_widget_count_children(rootConfig);
    
    std::vector<jobject> configItems;
    jclass configItemClass = env->FindClass("cn/alittlecookie/lut2photo/lut2photo/model/ConfigItem");
    jmethodID constructor = env->GetMethodID(configItemClass, "<init>", 
        "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;[Ljava/lang/String;FFF)V");
    
    for (int i = 0; i < childCount; i++) {
        CameraWidget *child;
        ret = gp_widget_get_child(rootConfig, i, &child);
        if (ret < GP_OK) continue;
        
        const char *name, *label;
        CameraWidgetType type;
        
        gp_widget_get_name(child, &name);
        gp_widget_get_label(child, &label);
        gp_widget_get_type(child, &type);
        
        // 获取当前值
        char *value = nullptr;
        gp_widget_get_value(child, &value);
        
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
        
        // 创建 ConfigItem 对象
        jstring jname = createJavaString(env, name);
        jstring jlabel = createJavaString(env, label);
        jstring jvalue = createJavaString(env, value ? value : "");
        
        jobject configItem = env->NewObject(configItemClass, constructor,
            jname, jlabel, javaType, jvalue, nullptr, 0.0f, 0.0f, 0.0f);
        
        configItems.push_back(configItem);
        
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(jlabel);
        env->DeleteLocalRef(jvalue);
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
    
    CameraWidget *widget;
    int ret = gp_camera_get_single_config(camera, name.c_str(), &widget, context);
    
    if (ret < GP_OK) {
        LOGE("获取配置失败: %s", gp_result_as_string(ret));
        return ret;
    }
    
    // 设置值
    ret = gp_widget_set_value(widget, value.c_str());
    
    if (ret < GP_OK) {
        LOGE("设置配置值失败: %s", gp_result_as_string(ret));
        gp_widget_free(widget);
        return ret;
    }
    
    // 应用配置
    ret = gp_camera_set_single_config(camera, name.c_str(), widget, context);
    
    if (ret < GP_OK) {
        LOGE("应用配置失败: %s", gp_result_as_string(ret));
        gp_widget_free(widget);
        return ret;
    }
    
    gp_widget_free(widget);
    
    LOGI("配置设置成功");
    
    return GP_OK;
}
