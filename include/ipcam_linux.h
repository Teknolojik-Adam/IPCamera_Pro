#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Own Linux V4L2 frame output library. Caller owns returned handle. */
typedef struct IpcamVideoDevice IpcamVideoDevice;

/* Find the IPCamera Pro v4l2loopback node without assuming a video number. */
int ipcam_find_virtual_device(char* path, size_t path_size);

/* Open virtual V4L2 device and negotiate uncompressed YUYV (YUY2). */
IpcamVideoDevice* ipcam_device_open(const char* device, int width, int height, int fps);

/* Send exactly one tightly packed YUYV frame. Returns 0 on success, -1 on error. */
int ipcam_device_write(IpcamVideoDevice* device, const unsigned char* frame, size_t byte_count);

/* Close and free device (NULL is permitted). */
void ipcam_device_close(IpcamVideoDevice* device);

/* Description of the most recent error on the calling thread. */
const char* ipcam_last_error(void);

#ifdef __cplusplus
}
#endif
