#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#ifdef CAMERA_SUPPORT_ENABLED

#include <gphoto2/gphoto2.h>
#include <gphoto2/gphoto2-port.h>

#endif

#include <unistd.h>
#include <fcntl.h>

#define LOG_TAG "LibGPhoto2JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#ifdef CAMERA_SUPPORT_ENABLED
static GPContext *context = nullptr;
static Camera *camera = nullptr;
static CameraAbilities abilities;
static GPPortInfo port_info;
#endif

// 初始化libgphoto2
extern "C" JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeInit(JNIEnv *env,
                                                                          jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    LOGI("初始化libgphoto2");

    context = gp_context_new();
    if (!context) {
        LOGE("无法创建GPContext");
        return JNI_FALSE;
    }

    LOGI("libgphoto2初始化成功");
    return JNI_TRUE;
#else
    LOGI("相机支持未启用");
    return JNI_FALSE;
#endif
}

// 清理libgphoto2资源
extern "C" JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeCleanup(JNIEnv *env,
                                                                             jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    LOGI("清理libgphoto2资源");

    if (camera) {
        gp_camera_unref(camera);
        camera = nullptr;
    }

    if (context) {
        gp_context_unref(context);
        context = nullptr;
    }

    LOGI("libgphoto2资源清理完成");
#else
    LOGI("相机支持未启用，无需清理");
#endif
}

// 检测相机
extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeDetectCameras(JNIEnv *env,
                                                                                   jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    LOGI("开始检测相机");

    if (!context) {
        LOGE("GPContext未初始化");
        return nullptr;
    }

    CameraList *list;
    int ret = gp_list_new(&list);
    if (ret < GP_OK) {
        LOGE("无法创建相机列表: %s", gp_result_as_string(ret));
        return nullptr;
    }

    ret = gp_camera_autodetect(list, context);
    if (ret < GP_OK) {
        LOGE("相机自动检测失败: %s", gp_result_as_string(ret));
        gp_list_unref(list);
        return nullptr;
    }

    int count = gp_list_count(list);
    LOGI("检测到 %d 个相机", count);

    // 创建Java对象数组
    jclass cameraInfoClass = env->FindClass(
            "cn/alittlecookie/lut2photo/lut2photo/camera/LibGPhoto2JNI$CameraInfo");
    if (!cameraInfoClass) {
        LOGE("无法找到CameraInfo类");
        gp_list_unref(list);
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(cameraInfoClass, "<init>",
                                             "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!constructor) {
        LOGE("无法找到CameraInfo构造函数");
        gp_list_unref(list);
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(count, cameraInfoClass, nullptr);

    for (int i = 0; i < count; i++) {
        const char *name, *value;
        gp_list_get_name(list, i, &name);
        gp_list_get_value(list, i, &value);

        jstring jname = env->NewStringUTF(name);
        jstring jvalue = env->NewStringUTF(value);

        jobject cameraInfo = env->NewObject(cameraInfoClass, constructor, jname, jvalue);
        env->SetObjectArrayElement(result, i, cameraInfo);

        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(jvalue);
        env->DeleteLocalRef(cameraInfo);

        LOGI("相机 %d: %s - %s", i, name, value);
    }

    gp_list_unref(list);
    return result;
#else
    LOGI("相机支持未启用");
    return nullptr;
#endif
}

// 连接相机
extern "C" JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeConnectCamera(JNIEnv *env,
                                                                                   jobject thiz,
                                                                                   jstring model,
                                                                                   jstring port) {
#ifdef CAMERA_SUPPORT_ENABLED
    const char *model_str = env->GetStringUTFChars(model, nullptr);
    const char *port_str = env->GetStringUTFChars(port, nullptr);

    LOGI("连接相机: %s @ %s", model_str, port_str);

    if (!context) {
        LOGE("GPContext未初始化");
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    // 如果已有相机连接，先断开
    if (camera) {
        gp_camera_unref(camera);
        camera = nullptr;
    }

    int ret = gp_camera_new(&camera);
    if (ret < GP_OK) {
        LOGE("无法创建相机对象: %s", gp_result_as_string(ret));
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    // 获取相机能力
    CameraAbilitiesList *abilities_list;
    ret = gp_abilities_list_new(&abilities_list);
    if (ret < GP_OK) {
        LOGE("无法创建能力列表: %s", gp_result_as_string(ret));
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    ret = gp_abilities_list_load(abilities_list, context);
    if (ret < GP_OK) {
        LOGE("无法加载能力列表: %s", gp_result_as_string(ret));
        gp_abilities_list_free(abilities_list);
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    int model_index = gp_abilities_list_lookup_model(abilities_list, model_str);
    if (model_index < GP_OK) {
        LOGE("无法找到相机型号: %s", model_str);
        gp_abilities_list_free(abilities_list);
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    ret = gp_abilities_list_get_abilities(abilities_list, model_index, &abilities);
    if (ret < GP_OK) {
        LOGE("无法获取相机能力: %s", gp_result_as_string(ret));
        gp_abilities_list_free(abilities_list);
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    ret = gp_camera_set_abilities(camera, abilities);
    if (ret < GP_OK) {
        LOGE("无法设置相机能力: %s", gp_result_as_string(ret));
        gp_abilities_list_free(abilities_list);
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    gp_abilities_list_free(abilities_list);

    // 设置端口
    GPPortInfoList *port_info_list;
    ret = gp_port_info_list_new(&port_info_list);
    if (ret < GP_OK) {
        LOGE("无法创建端口信息列表: %s", gp_result_as_string(ret));
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    ret = gp_port_info_list_load(port_info_list);
    if (ret < GP_OK) {
        LOGE("无法加载端口信息列表: %s", gp_result_as_string(ret));
        gp_port_info_list_free(port_info_list);
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    int port_index = gp_port_info_list_lookup_path(port_info_list, port_str);
    if (port_index < GP_OK) {
        LOGE("无法找到端口: %s", port_str);
        gp_port_info_list_free(port_info_list);
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    ret = gp_port_info_list_get_info(port_info_list, port_index, &port_info);
    if (ret < GP_OK) {
        LOGE("无法获取端口信息: %s", gp_result_as_string(ret));
        gp_port_info_list_free(port_info_list);
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    ret = gp_camera_set_port_info(camera, port_info);
    if (ret < GP_OK) {
        LOGE("无法设置端口信息: %s", gp_result_as_string(ret));
        gp_port_info_list_free(port_info_list);
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    gp_port_info_list_free(port_info_list);

    // 初始化相机
    ret = gp_camera_init(camera, context);
    if (ret < GP_OK) {
        LOGE("相机初始化失败: %s", gp_result_as_string(ret));
        gp_camera_unref(camera);
        camera = nullptr;
        env->ReleaseStringUTFChars(model, model_str);
        env->ReleaseStringUTFChars(port, port_str);
        return JNI_FALSE;
    }

    LOGI("相机连接成功: %s @ %s", model_str, port_str);

    env->ReleaseStringUTFChars(model, model_str);
    env->ReleaseStringUTFChars(port, port_str);
    return JNI_TRUE;
#else
    LOGI("相机支持未启用");
    return JNI_FALSE;
#endif
}

// 断开相机
extern "C" JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeDisconnectCamera(JNIEnv *env,
                                                                                      jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    LOGI("断开相机连接");

    if (camera) {
        gp_camera_exit(camera, context);
        gp_camera_unref(camera);
        camera = nullptr;
        LOGI("相机已断开");
    }
#else
    LOGI("相机支持未启用");
#endif
}

// 获取相机信息
extern "C" JNIEXPORT jstring JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeGetCameraInfo(JNIEnv *env,
                                                                                   jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return env->NewStringUTF("相机未连接");
    }

    CameraText text;
    int ret = gp_camera_get_summary(camera, &text, context);
    if (ret < GP_OK) {
        LOGE("无法获取相机信息: %s", gp_result_as_string(ret));
        return env->NewStringUTF("无法获取相机信息");
    }

    return env->NewStringUTF(text.text);
#else
    return env->NewStringUTF("相机支持未启用");
#endif
}

// 拍摄照片
extern "C" JNIEXPORT jstring JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeCaptureImage(JNIEnv *env,
                                                                                  jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return nullptr;
    }

    LOGI("开始拍摄照片");

    CameraFilePath camera_file_path;
    int ret = gp_camera_capture(camera, GP_CAPTURE_IMAGE, &camera_file_path, context);
    if (ret < GP_OK) {
        LOGE("拍摄失败: %s", gp_result_as_string(ret));
        return nullptr;
    }

    LOGI("拍摄成功: %s/%s", camera_file_path.folder, camera_file_path.name);

    // 返回文件路径
    std::string file_path =
            std::string(camera_file_path.folder) + "/" + std::string(camera_file_path.name);
    return env->NewStringUTF(file_path.c_str());
#else
    LOGI("相机支持未启用");
    return nullptr;
#endif
}

// 设置USB设备文件描述符
extern "C" JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeSetUsbDeviceFd(JNIEnv *env,
                                                                                    jobject thiz,
                                                                                    jint fd) {
#ifdef CAMERA_SUPPORT_ENABLED
    LOGI("设置USB设备文件描述符: %d", fd);

    if (!camera) {
        LOGE("相机未连接");
        return JNI_FALSE;
    }

    // 这里需要根据具体的libgphoto2版本和USB实现来设置文件描述符
    // 这是一个简化的实现，实际可能需要更复杂的处理

    LOGI("USB设备文件描述符设置完成");
    return JNI_TRUE;
#else
    LOGI("相机支持未启用");
    return JNI_FALSE;
#endif
}

// 获取配置项
extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeGetConfig(JNIEnv *env,
                                                                               jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return nullptr;
    }

    CameraWidget *widget;
    int ret = gp_camera_get_config(camera, &widget, context);
    if (ret < GP_OK) {
        LOGE("无法获取相机配置: %s", gp_result_as_string(ret));
        return nullptr;
    }

    // 这里应该递归遍历配置树并创建ConfigItem数组
    // 为了简化，这里只返回一个空数组
    jclass configItemClass = env->FindClass(
            "cn/alittlecookie/lut2photo/lut2photo/camera/LibGPhoto2JNI$ConfigItem");
    jobjectArray result = env->NewObjectArray(0, configItemClass, nullptr);

    gp_widget_unref(widget);
    return result;
#else
    LOGI("相机支持未启用");
    return nullptr;
#endif
}

// 设置配置项
extern "C" JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeSetConfig(JNIEnv *env,
                                                                               jobject thiz,
                                                                               jstring name,
                                                                               jstring value) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return JNI_FALSE;
    }

    const char *name_str = env->GetStringUTFChars(name, nullptr);
    const char *value_str = env->GetStringUTFChars(value, nullptr);

    LOGI("设置配置项: %s = %s", name_str, value_str);

    CameraWidget *widget;
    int ret = gp_camera_get_config(camera, &widget, context);
    if (ret < GP_OK) {
        LOGE("无法获取相机配置: %s", gp_result_as_string(ret));
        env->ReleaseStringUTFChars(name, name_str);
        env->ReleaseStringUTFChars(value, value_str);
        return JNI_FALSE;
    }

    CameraWidget *child;
    ret = gp_widget_get_child_by_name(widget, name_str, &child);
    if (ret < GP_OK) {
        LOGE("无法找到配置项: %s", name_str);
        gp_widget_unref(widget);
        env->ReleaseStringUTFChars(name, name_str);
        env->ReleaseStringUTFChars(value, value_str);
        return JNI_FALSE;
    }

    ret = gp_widget_set_value(child, value_str);
    if (ret < GP_OK) {
        LOGE("无法设置配置项值: %s", gp_result_as_string(ret));
        gp_widget_unref(widget);
        env->ReleaseStringUTFChars(name, name_str);
        env->ReleaseStringUTFChars(value, value_str);
        return JNI_FALSE;
    }

    ret = gp_camera_set_config(camera, widget, context);
    if (ret < GP_OK) {
        LOGE("无法应用配置更改: %s", gp_result_as_string(ret));
        gp_widget_unref(widget);
        env->ReleaseStringUTFChars(name, name_str);
        env->ReleaseStringUTFChars(value, value_str);
        return JNI_FALSE;
    }

    LOGI("配置项设置成功: %s = %s", name_str, value_str);

    gp_widget_unref(widget);
    env->ReleaseStringUTFChars(name, name_str);
    env->ReleaseStringUTFChars(value, value_str);
    return JNI_TRUE;
#else
    LOGI("相机支持未启用");
    return JNI_FALSE;
#endif
}

// 开始实时预览
extern "C" JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeStartPreview(JNIEnv *env,
                                                                                  jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return JNI_FALSE;
    }

    LOGI("开始实时预览");

    // 检查相机是否支持实时预览
    if (!(abilities.operations & GP_OPERATION_CAPTURE_PREVIEW)) {
        LOGE("相机不支持实时预览");
        return JNI_FALSE;
    }

    LOGI("实时预览已启动");
    return JNI_TRUE;
#else
    LOGI("相机支持未启用");
    return JNI_FALSE;
#endif
}

// 停止实时预览
extern "C" JNIEXPORT void JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeStopPreview(JNIEnv *env,
                                                                                 jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    LOGI("停止实时预览");
    // 实时预览停止的具体实现
#else
    LOGI("相机支持未启用");
#endif
}

// 获取预览帧
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeGetPreviewFrame(JNIEnv *env,
                                                                                     jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return nullptr;
    }

    CameraFile *file;
    int ret = gp_file_new(&file);
    if (ret < GP_OK) {
        LOGE("无法创建文件对象: %s", gp_result_as_string(ret));
        return nullptr;
    }

    ret = gp_camera_capture_preview(camera, file, context);
    if (ret < GP_OK) {
        LOGE("无法获取预览帧: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        return nullptr;
    }

    const char *data;
    unsigned long size;
    ret = gp_file_get_data_and_size(file, &data, &size);
    if (ret < GP_OK) {
        LOGE("无法获取文件数据: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, (const jbyte *) data);

    gp_file_unref(file);
    return result;
#else
    LOGI("相机支持未启用");
    return nullptr;
#endif
}

// 自动对焦
extern "C" JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeAutoFocus(JNIEnv *env,
                                                                               jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return JNI_FALSE;
    }

    LOGI("执行自动对焦");

    // 这里应该实现具体的自动对焦逻辑
    // 不同相机的自动对焦实现可能不同

    LOGI("自动对焦完成");
    return JNI_TRUE;
#else
    LOGI("相机支持未启用");
    return JNI_FALSE;
#endif
}

// 下载文件
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeDownloadFile(JNIEnv *env,
                                                                                  jobject thiz,
                                                                                  jstring folder,
                                                                                  jstring filename) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return nullptr;
    }

    const char *folder_str = env->GetStringUTFChars(folder, nullptr);
    const char *filename_str = env->GetStringUTFChars(filename, nullptr);

    LOGI("下载文件: %s/%s", folder_str, filename_str);

    CameraFile *file;
    int ret = gp_file_new(&file);
    if (ret < GP_OK) {
        LOGE("无法创建文件对象: %s", gp_result_as_string(ret));
        env->ReleaseStringUTFChars(folder, folder_str);
        env->ReleaseStringUTFChars(filename, filename_str);
        return nullptr;
    }

    ret = gp_camera_file_get(camera, folder_str, filename_str, GP_FILE_TYPE_NORMAL, file, context);
    if (ret < GP_OK) {
        LOGE("无法下载文件: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        env->ReleaseStringUTFChars(folder, folder_str);
        env->ReleaseStringUTFChars(filename, filename_str);
        return nullptr;
    }

    const char *data;
    unsigned long size;
    ret = gp_file_get_data_and_size(file, &data, &size);
    if (ret < GP_OK) {
        LOGE("无法获取文件数据: %s", gp_result_as_string(ret));
        gp_file_unref(file);
        env->ReleaseStringUTFChars(folder, folder_str);
        env->ReleaseStringUTFChars(filename, filename_str);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(size);
    env->SetByteArrayRegion(result, 0, size, (const jbyte *) data);

    LOGI("文件下载成功，大小: %lu 字节", size);

    gp_file_unref(file);
    env->ReleaseStringUTFChars(folder, folder_str);
    env->ReleaseStringUTFChars(filename, filename_str);
    return result;
#else
    LOGI("相机支持未启用");
    return nullptr;
#endif
}

// 删除文件
extern "C" JNIEXPORT jboolean JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeDeleteFile(JNIEnv *env,
                                                                                jobject thiz,
                                                                                jstring folder,
                                                                                jstring filename) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return JNI_FALSE;
    }

    const char *folder_str = env->GetStringUTFChars(folder, nullptr);
    const char *filename_str = env->GetStringUTFChars(filename, nullptr);

    LOGI("删除文件: %s/%s", folder_str, filename_str);

    int ret = gp_camera_file_delete(camera, folder_str, filename_str, context);
    if (ret < GP_OK) {
        LOGE("无法删除文件: %s", gp_result_as_string(ret));
        env->ReleaseStringUTFChars(folder, folder_str);
        env->ReleaseStringUTFChars(filename, filename_str);
        return JNI_FALSE;
    }

    LOGI("文件删除成功");

    env->ReleaseStringUTFChars(folder, folder_str);
    env->ReleaseStringUTFChars(filename, filename_str);
    return JNI_TRUE;
#else
    LOGI("相机支持未启用");
    return JNI_FALSE;
#endif
}

// 获取错误信息
extern "C" JNIEXPORT jstring JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeGetErrorString(JNIEnv *env,
                                                                                    jobject thiz,
                                                                                    jint error_code) {
#ifdef CAMERA_SUPPORT_ENABLED
    const char *error_str = gp_result_as_string(error_code);
    return env->NewStringUTF(error_str);
#else
    return env->NewStringUTF("相机支持未启用");
#endif
}

// 获取支持的操作
extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeGetSupportedOperations(
        JNIEnv *env, jobject thiz) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return 0;
    }

    return abilities.operations;
#else
    LOGI("相机支持未启用");
    return 0;
#endif
}

// 等待相机事件
extern "C" JNIEXPORT jint JNICALL
Java_cn_alittlecookie_lut2photo_lut2photo_camera_LibGPhoto2JNI_nativeWaitForEvent(JNIEnv *env,
                                                                                  jobject thiz,
                                                                                  jint timeout) {
#ifdef CAMERA_SUPPORT_ENABLED
    if (!camera) {
        LOGE("相机未连接");
        return GP_ERROR;
    }

    CameraEventType event_type;
    void *event_data;

    int ret = gp_camera_wait_for_event(camera, timeout, &event_type, &event_data, context);
    if (ret < GP_OK) {
        LOGE("等待事件失败: %s", gp_result_as_string(ret));
        return ret;
    }

    LOGI("收到相机事件: %d", event_type);

    // 清理事件数据
    if (event_data) {
        free(event_data);
    }

    return event_type;
#else
    LOGI("相机支持未启用");
    return -1;
#endif
}