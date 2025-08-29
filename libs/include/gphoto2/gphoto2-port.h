#ifndef GPHOTO2_PORT_H
#define GPHOTO2_PORT_H

#ifdef __cplusplus
extern "C" {
#endif

// 端口类型定义
typedef struct _GPPort GPPort;
typedef struct _GPPortInfo GPPortInfo;
typedef struct _GPPortInfoList GPPortInfoList;

// 端口类型枚举
typedef enum {
    GP_PORT_NONE = 0,
    GP_PORT_SERIAL = 1 << 0,
    GP_PORT_USB = 1 << 2,
    GP_PORT_DISK = 1 << 3,
    GP_PORT_PTPIP = 1 << 4,
    GP_PORT_USB_DISK_DIRECT = 1 << 5,
    GP_PORT_USB_SCSI = 1 << 6
} GPPortType;

// 端口设置结构
typedef struct {
    char path[128];
    GPPortType type;
    union {
        struct {
            int speed;
            int bits;
            int parity;
            int stopbits;
        } serial;
        struct {
            int inep;
            int outep;
            int intep;
            int config;
            int interface;
            int altsetting;
        } usb;
    } settings;
} GPPortSettings;

// 基本函数声明
int gp_port_new(GPPort **port);
int gp_port_free(GPPort *port);
int gp_port_set_info(GPPort *port, GPPortInfo info);
int gp_port_get_info(GPPort *port, GPPortInfo *info);

int gp_port_open(GPPort *port);
int gp_port_close(GPPort *port);

int gp_port_read(GPPort *port, char *data, int size);
int gp_port_write(GPPort *port, const char *data, int size);

int gp_port_get_settings(GPPort *port, GPPortSettings *settings);
int gp_port_set_settings(GPPort *port, GPPortSettings settings);

int gp_port_info_list_new(GPPortInfoList **list);
int gp_port_info_list_free(GPPortInfoList *list);
int gp_port_info_list_load(GPPortInfoList *list);
int gp_port_info_list_count(GPPortInfoList *list);
int gp_port_info_list_get_info(GPPortInfoList *list, int n, GPPortInfo *info);
int gp_port_info_list_lookup_path(GPPortInfoList *list, const char *path);
int gp_port_info_get_name(GPPortInfo info, char **name);
int gp_port_info_get_path(GPPortInfo info, char **path);
int gp_port_info_get_type(GPPortInfo info, GPPortType *type);

int gp_port_set_timeout(GPPort *port, int timeout);
int gp_port_get_timeout(GPPort *port, int *timeout);

#ifdef __cplusplus
}
#endif

#endif // GPHOTO2_PORT_H