#ifndef GPHOTO2_H
#define GPHOTO2_H

#ifdef __cplusplus
extern "C" {
#endif

// 基本类型定义
typedef struct _Camera Camera;
typedef struct _GPContext GPContext;
typedef struct _CameraList CameraList;
typedef struct _CameraFile CameraFile;
typedef struct _CameraWidget CameraWidget;
typedef struct _CameraAbilities CameraAbilities;
typedef struct _CameraText CameraText;
typedef struct _CameraFilePath CameraFilePath;
typedef struct _GPPortInfo GPPortInfo;

// 错误代码
#define GP_OK                    0
#define GP_ERROR                -1
#define GP_ERROR_BAD_PARAMETERS -2
#define GP_ERROR_NO_MEMORY      -3
#define GP_ERROR_LIBRARY        -4
#define GP_ERROR_UNKNOWN_PORT   -5
#define GP_ERROR_NOT_SUPPORTED  -6
#define GP_ERROR_IO             -7
#define GP_ERROR_FIXED_LIMIT_EXCEEDED -8
#define GP_ERROR_TIMEOUT        -9
#define GP_ERROR_IO_SUPPORTED_SERIAL -10
#define GP_ERROR_IO_SUPPORTED_USB -11
#define GP_ERROR_UNKNOWN_MODEL  -12
#define GP_ERROR_OUT_OF_SPACE   -13
#define GP_ERROR_CANCEL         -14
#define GP_ERROR_CAMERA_BUSY    -15
#define GP_ERROR_PATH_NOT_ABSOLUTE -16
#define GP_ERROR_CORRUPTED_DATA -17
#define GP_ERROR_FILE_EXISTS    -18
#define GP_ERROR_MODEL_NOT_FOUND -19
#define GP_ERROR_DIRECTORY_NOT_FOUND -20
#define GP_ERROR_FILE_NOT_FOUND -21
#define GP_ERROR_DIRECTORY_EXISTS -22
#define GP_ERROR_CAMERA_ERROR   -23
#define GP_ERROR_OS_FAILURE     -24
#define GP_ERROR_NO_SPACE       -25

// 文件类型
typedef enum {
    GP_FILE_TYPE_PREVIEW,
    GP_FILE_TYPE_NORMAL,
    GP_FILE_TYPE_RAW,
    GP_FILE_TYPE_AUDIO,
    GP_FILE_TYPE_EXIF,
    GP_FILE_TYPE_METADATA
} CameraFileType;

// 事件类型
typedef enum {
    GP_EVENT_UNKNOWN,
    GP_EVENT_TIMEOUT,
    GP_EVENT_FILE_ADDED,
    GP_EVENT_FOLDER_ADDED,
    GP_EVENT_CAPTURE_COMPLETE
} CameraEventType;

// 相机操作能力
typedef enum {
    GP_OPERATION_NONE = 0,
    GP_OPERATION_CAPTURE_IMAGE = 1 << 0,
    GP_OPERATION_CAPTURE_VIDEO = 1 << 1,
    GP_OPERATION_CAPTURE_AUDIO = 1 << 2,
    GP_OPERATION_CAPTURE_PREVIEW = 1 << 3,
    GP_OPERATION_CONFIG = 1 << 4
} CameraOperation;

// 捕获类型常量
#define GP_CAPTURE_IMAGE GP_OPERATION_CAPTURE_IMAGE

// 结构体定义
struct _CameraText {
    char text[256];
};

struct _CameraFilePath {
    char name[128];
    char folder[1024];
};

struct _GPPortInfo {
    char name[128];
    char path[128];
    int type;
};

// 相机能力结构
struct _CameraAbilities {
    char model[128];
    int status;
    int port;
    int speed[64];
    CameraOperation operations;
    int file_operations;
    int folder_operations;
    int usb_vendor;
    int usb_product;
    int usb_class;
    int usb_subclass;
    int usb_protocol;
    char library[1024];
    char id[1024];
};

// 基本函数声明
int gp_camera_new(Camera **camera);
int gp_camera_init(Camera *camera, GPContext *context);
int gp_camera_exit(Camera *camera, GPContext *context);
int gp_camera_free(Camera *camera);

GPContext *gp_context_new(void);
void gp_context_unref(GPContext *context);

int gp_list_new(CameraList **list);
int gp_list_free(CameraList *list);
int gp_list_unref(CameraList *list);
int gp_camera_unref(Camera *camera);
int gp_list_count(CameraList *list);
int gp_list_get_name(CameraList *list, int index, const char **name);
int gp_list_get_value(CameraList *list, int index, const char **value);

int gp_camera_autodetect(CameraList *list, GPContext *context);
int gp_camera_set_abilities(Camera *camera, CameraAbilities abilities);
int gp_camera_get_abilities(Camera *camera, CameraAbilities *abilities);
int gp_camera_set_port_info(Camera *camera, GPPortInfo info);

// Abilities list functions
typedef struct _CameraAbilitiesList CameraAbilitiesList;
int gp_abilities_list_new(CameraAbilitiesList **list);
int gp_abilities_list_free(CameraAbilitiesList *list);
int gp_abilities_list_load(CameraAbilitiesList *list, GPContext *context);
int gp_abilities_list_lookup_model(CameraAbilitiesList *list, const char *model);
int
gp_abilities_list_get_abilities(CameraAbilitiesList *list, int index, CameraAbilities *abilities);

int gp_camera_get_summary(Camera *camera, CameraText *summary, GPContext *context);
int gp_camera_capture(Camera *camera, int type, CameraFilePath *path, GPContext *context);
int gp_camera_capture_preview(Camera *camera, CameraFile *file, GPContext *context);

int gp_file_new(CameraFile **file);
int gp_file_free(CameraFile *file);
int gp_file_unref(CameraFile *file);
int gp_file_get_data_and_size(CameraFile *file, const char **data, unsigned long *size);

int gp_camera_file_get(Camera *camera, const char *folder, const char *file,
                       CameraFileType type, CameraFile *camera_file, GPContext *context);
int gp_camera_file_delete(Camera *camera, const char *folder, const char *file, GPContext *context);

int gp_camera_get_config(Camera *camera, CameraWidget **window, GPContext *context);
int gp_camera_set_config(Camera *camera, CameraWidget *window, GPContext *context);

// Widget functions
int gp_widget_get_child_by_name(CameraWidget *widget, const char *name, CameraWidget **child);
int gp_widget_set_value(CameraWidget *widget, const void *value);
int gp_widget_unref(CameraWidget *widget);

int gp_camera_wait_for_event(Camera *camera, int timeout, CameraEventType *eventtype,
                             void **eventdata, GPContext *context);

const char *gp_result_as_string(int result);

#ifdef __cplusplus
}
#endif

#endif // GPHOTO2_H