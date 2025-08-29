// libgphoto2 stub implementation for compilation testing
#include "include/gphoto2/gphoto2.h"
#include "include/gphoto2/gphoto2-port.h"
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "LibGPhoto2Stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Port-related structures
struct _GPPortInfoList {
    int dummy;
};

struct _GPPort {
    int dummy;
};

// Port-related functions
int gp_port_info_list_new(GPPortInfoList **list) {
    LOGI("gp_port_info_list_new called");
    if (!list) return GP_ERROR_BAD_PARAMETERS;
    *list = (GPPortInfoList *) malloc(sizeof(GPPortInfoList));
    return GP_OK;
}

int gp_port_info_list_free(GPPortInfoList *list) {
    LOGI("gp_port_info_list_free called");
    if (list) {
        free(list);
    }
    return GP_OK;
}

int gp_port_info_list_load(GPPortInfoList *list) {
    LOGI("gp_port_info_list_load called");
    return GP_OK;
}

int gp_port_info_list_count(GPPortInfoList *list) {
    LOGI("gp_port_info_list_count called");
    return 1; // 模拟一个端口
}

int gp_port_info_list_lookup_path(GPPortInfoList *list, const char *path) {
    LOGI("gp_port_info_list_lookup_path called with path: %s", path ? path : "null");
    return 0; // 返回第一个索引
}

int gp_port_info_list_get_info(GPPortInfoList *list, int n, GPPortInfo *info) {
    LOGI("gp_port_info_list_get_info called with index: %d", n);
    if (!info) return GP_ERROR_BAD_PARAMETERS;
    return GP_OK;
}

int gp_camera_set_port_info(Camera *camera, GPPortInfo info) {
    LOGI("gp_camera_set_port_info called");
    if (!camera) return GP_ERROR_BAD_PARAMETERS;
    return GP_OK;
}

// 简单的结构体实现
struct _Camera {
    bool initialized;
    CameraAbilities abilities;
};

struct _GPContext {
    int dummy;
};

struct _CameraList {
    int count;
    char names[10][128];
    char values[10][128];
};

// CameraText and CameraFilePath are now defined in gphoto2.h

struct _CameraFile {
    char *data;
    unsigned long size;
};

struct _CameraWidget {
    int dummy;
};

struct _CameraAbilitiesList {
    int dummy;
};

// 错误信息映射
const char *gp_result_as_string(int result) {
    switch (result) {
        case GP_OK:
            return "No error";
        case GP_ERROR:
            return "Generic error";
        case GP_ERROR_BAD_PARAMETERS:
            return "Bad parameters";
        case GP_ERROR_NO_MEMORY:
            return "No memory";
        case GP_ERROR_LIBRARY:
            return "Library error";
        case GP_ERROR_UNKNOWN_PORT:
            return "Unknown port";
        case GP_ERROR_NOT_SUPPORTED:
            return "Not supported";
        case GP_ERROR_IO:
            return "I/O error";
        case GP_ERROR_TIMEOUT:
            return "Timeout";
        case GP_ERROR_CAMERA_BUSY:
            return "Camera busy";
        case GP_ERROR_FILE_NOT_FOUND:
            return "File not found";
        default:
            return "Unknown error";
    }
}

// 相机基本函数
int gp_camera_new(Camera **camera) {
    LOGI("gp_camera_new called");
    if (!camera) return GP_ERROR_BAD_PARAMETERS;

    *camera = (Camera *) malloc(sizeof(Camera));
    if (!*camera) return GP_ERROR_NO_MEMORY;

    (*camera)->initialized = false;
    memset(&(*camera)->abilities, 0, sizeof(CameraAbilities));
    strcpy((*camera)->abilities.model, "Stub Camera");
    (*camera)->abilities.operations = (CameraOperation) (GP_OPERATION_CAPTURE_IMAGE |
                                                         GP_OPERATION_CAPTURE_PREVIEW);

    return GP_OK;
}

int gp_camera_init(Camera *camera, GPContext *context) {
    LOGI("gp_camera_init called");
    if (!camera) return GP_ERROR_BAD_PARAMETERS;

    camera->initialized = true;
    return GP_OK;
}

int gp_camera_exit(Camera *camera, GPContext *context) {
    LOGI("gp_camera_exit called");
    if (!camera) return GP_ERROR_BAD_PARAMETERS;

    camera->initialized = false;
    return GP_OK;
}

int gp_camera_free(Camera *camera) {
    LOGI("gp_camera_free called");
    if (!camera) return GP_ERROR_BAD_PARAMETERS;

    free(camera);
    return GP_OK;
}

// 上下文函数
GPContext *gp_context_new(void) {
    LOGI("gp_context_new called");
    GPContext *context = (GPContext *) malloc(sizeof(GPContext));
    return context;
}

void gp_context_unref(GPContext *context) {
    LOGI("gp_context_unref called");
    if (context) {
        free(context);
    }
}

// 列表函数
int gp_list_new(CameraList **list) {
    LOGI("gp_list_new called");
    if (!list) return GP_ERROR_BAD_PARAMETERS;

    *list = (CameraList *) malloc(sizeof(CameraList));
    if (!*list) return GP_ERROR_NO_MEMORY;

    (*list)->count = 0;
    return GP_OK;
}

int gp_list_free(CameraList *list) {
    LOGI("gp_list_free called");
    if (!list) return GP_ERROR_BAD_PARAMETERS;

    free(list);
    return GP_OK;
}

int gp_list_unref(CameraList *list) {
    LOGI("gp_list_unref called");
    return gp_list_free(list);
}

int gp_camera_unref(Camera *camera) {
    LOGI("gp_camera_unref called");
    return gp_camera_free(camera);
}

int gp_list_count(CameraList *list) {
    if (!list) return 0;
    return list->count;
}

int gp_list_get_name(CameraList *list, int index, const char **name) {
    if (!list || index >= list->count || !name) return GP_ERROR_BAD_PARAMETERS;

    *name = list->names[index];
    return GP_OK;
}

int gp_list_get_value(CameraList *list, int index, const char **value) {
    if (!list || index >= list->count || !value) return GP_ERROR_BAD_PARAMETERS;

    *value = list->values[index];
    return GP_OK;
}

// 相机检测
int gp_camera_autodetect(CameraList *list, GPContext *context) {
    LOGI("gp_camera_autodetect called");
    if (!list) return GP_ERROR_BAD_PARAMETERS;

    // 模拟检测到一个相机
    list->count = 1;
    strcpy(list->names[0], "Stub Camera");
    strcpy(list->values[0], "usb:001,002");

    return GP_OK;
}

// 相机能力
int gp_camera_set_abilities(Camera *camera, CameraAbilities abilities) {
    LOGI("gp_camera_set_abilities called");
    if (!camera) return GP_ERROR_BAD_PARAMETERS;

    camera->abilities = abilities;
    return GP_OK;
}

int gp_camera_get_abilities(Camera *camera, CameraAbilities *abilities) {
    LOGI("gp_camera_get_abilities called");
    if (!camera || !abilities) return GP_ERROR_BAD_PARAMETERS;

    *abilities = camera->abilities;
    return GP_OK;
}

// Abilities list functions
int gp_abilities_list_new(CameraAbilitiesList **list) {
    LOGI("gp_abilities_list_new called");
    if (!list) return GP_ERROR_BAD_PARAMETERS;
    *list = (CameraAbilitiesList *) malloc(sizeof(CameraAbilitiesList));
    return GP_OK;
}

int gp_abilities_list_free(CameraAbilitiesList *list) {
    LOGI("gp_abilities_list_free called");
    if (list) {
        free(list);
    }
    return GP_OK;
}

int gp_abilities_list_load(CameraAbilitiesList *list, GPContext *context) {
    LOGI("gp_abilities_list_load called");
    return GP_OK;
}

int gp_abilities_list_lookup_model(CameraAbilitiesList *list, const char *model) {
    LOGI("gp_abilities_list_lookup_model called with model: %s", model ? model : "null");
    return 0; // 返回第一个索引
}

int
gp_abilities_list_get_abilities(CameraAbilitiesList *list, int index, CameraAbilities *abilities) {
    LOGI("gp_abilities_list_get_abilities called with index: %d", index);
    if (!abilities) return GP_ERROR_BAD_PARAMETERS;

    strcpy(abilities->model, "Test Camera");
    abilities->operations = (CameraOperation) (GP_OPERATION_CAPTURE_IMAGE |
                                               GP_OPERATION_CAPTURE_PREVIEW);
    return GP_OK;
}

// 文件操作
int gp_file_new(CameraFile **file) {
    LOGI("gp_file_new called");
    if (!file) return GP_ERROR_BAD_PARAMETERS;

    *file = (CameraFile *) malloc(sizeof(CameraFile));
    if (!*file) return GP_ERROR_NO_MEMORY;

    (*file)->data = nullptr;
    (*file)->size = 0;
    return GP_OK;
}

int gp_file_free(CameraFile *file) {
    return gp_file_unref(file);
}

int gp_file_unref(CameraFile *file) {
    LOGI("gp_file_unref called");
    if (!file) return GP_ERROR_BAD_PARAMETERS;

    if (file->data) {
        free(file->data);
    }
    free(file);
    return GP_OK;
}

int gp_file_get_data_and_size(CameraFile *file, const char **data, unsigned long *size) {
    LOGI("gp_file_get_data_and_size called");
    if (!file || !data || !size) return GP_ERROR_BAD_PARAMETERS;

    *data = file->data;
    *size = file->size;
    return GP_OK;
}

// 相机操作
int gp_camera_capture(Camera *camera, int type, CameraFilePath *path, GPContext *context) {
    LOGI("gp_camera_capture called with type: %d", type);

    if (!camera || !path || !context) {
        return GP_ERROR_BAD_PARAMETERS;
    }

    // 模拟捕获的文件路径
    strcpy(path->folder, "/store_00010001/DCIM/100CANON");
    strcpy(path->name, "IMG_0001.JPG");

    LOGI("模拟捕获完成: %s/%s", path->folder, path->name);
    return GP_OK;
}

int gp_camera_capture_preview(Camera *camera, CameraFile *file, GPContext *context) {
    LOGI("gp_camera_capture_preview called");
    if (!camera || !file) return GP_ERROR_BAD_PARAMETERS;

    if (!camera->initialized) return GP_ERROR_CAMERA_BUSY;

    // 模拟预览数据
    const char *dummy_preview = "DUMMY_PREVIEW_DATA";
    file->size = strlen(dummy_preview);
    file->data = (char *) malloc(file->size);
    memcpy(file->data, dummy_preview, file->size);

    return GP_OK;
}

// 文件管理
int gp_camera_file_get(Camera *camera, const char *folder, const char *file,
                       CameraFileType type, CameraFile *camera_file, GPContext *context) {
    LOGI("gp_camera_file_get called: %s/%s", folder, file);
    if (!camera || !folder || !file || !camera_file) return GP_ERROR_BAD_PARAMETERS;

    // 模拟文件下载
    const char *dummy_file = "DUMMY_FILE_CONTENT";
    camera_file->size = strlen(dummy_file);
    camera_file->data = (char *) malloc(camera_file->size);
    memcpy(camera_file->data, dummy_file, camera_file->size);

    return GP_OK;
}

int
gp_camera_file_delete(Camera *camera, const char *folder, const char *file, GPContext *context) {
    LOGI("gp_camera_file_delete called: %s/%s", folder, file);
    if (!camera || !folder || !file) return GP_ERROR_BAD_PARAMETERS;

    // 模拟文件删除
    return GP_OK;
}

// 配置管理
int gp_camera_get_config(Camera *camera, CameraWidget **window, GPContext *context) {
    LOGI("gp_camera_get_config called");
    if (!camera || !window) return GP_ERROR_BAD_PARAMETERS;

    *window = (CameraWidget *) malloc(sizeof(CameraWidget));
    return GP_OK;
}

int gp_camera_set_config(Camera *camera, CameraWidget *window, GPContext *context) {
    LOGI("gp_camera_set_config called");
    if (!camera || !window) return GP_ERROR_BAD_PARAMETERS;
    return GP_OK;
}

// Widget functions
int gp_widget_get_child_by_name(CameraWidget *widget, const char *name, CameraWidget **child) {
    LOGI("gp_widget_get_child_by_name called with name: %s", name ? name : "null");
    if (!widget || !child) return GP_ERROR_BAD_PARAMETERS;
    *child = (CameraWidget *) malloc(sizeof(CameraWidget));
    return GP_OK;
}

int gp_widget_set_value(CameraWidget *widget, const void *value) {
    LOGI("gp_widget_set_value called");
    if (!widget) return GP_ERROR_BAD_PARAMETERS;
    return GP_OK;
}

int gp_widget_unref(CameraWidget *widget) {
    LOGI("gp_widget_unref called");
    if (widget) {
        free(widget);
    }
    return GP_OK;
}

int gp_camera_get_summary(Camera *camera, CameraText *summary, GPContext *context) {
    LOGI("gp_camera_get_summary called");
    if (!camera || !summary) {
        return GP_ERROR_BAD_PARAMETERS;
    }
    strcpy(summary->text, "Stub Camera Summary");
    return GP_OK;
}

// 事件处理
int gp_camera_wait_for_event(Camera *camera, int timeout, CameraEventType *eventtype,
                             void **eventdata, GPContext *context) {
    LOGI("gp_camera_wait_for_event called with timeout: %d", timeout);
    if (!camera || !eventtype) return GP_ERROR_BAD_PARAMETERS;

    // 模拟超时事件
    *eventtype = GP_EVENT_TIMEOUT;
    if (eventdata) *eventdata = nullptr;

    return GP_OK;
}