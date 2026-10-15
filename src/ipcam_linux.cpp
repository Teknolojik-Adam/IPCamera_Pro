#include "ipcam_linux.h"

#include <cerrno>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <new>
#include <string>
#include <string_view>
#include <vector>
#include <sys/ioctl.h>
#include <unistd.h>

struct IpcamVideoDevice {
    int fd;
    size_t expected_frame_size;
};

static thread_local std::string g_last_error;

namespace {
namespace fs = std::filesystem;

bool starts_with_ipcam_label(const char* card) {
    constexpr std::string_view prefix = "IPCamera Pro";
    return card && std::string_view(card, strnlen(card, 32)).substr(0, prefix.size()) == prefix;
}

bool is_loopback_output(int fd, v4l2_capability* capability) {
    if (::ioctl(fd, VIDIOC_QUERYCAP, capability) < 0) return false;
    const auto capabilities = (capability->capabilities & V4L2_CAP_DEVICE_CAPS)
        ? capability->device_caps : capability->capabilities;
    if (!(capabilities & V4L2_CAP_VIDEO_OUTPUT)) return false;
    return std::string_view(reinterpret_cast<const char*>(capability->driver), 32)
               .find("v4l2 loopback") != std::string_view::npos &&
           starts_with_ipcam_label(reinterpret_cast<const char*>(capability->card));
}

std::vector<fs::path> video_nodes() {
    std::vector<fs::path> nodes;
    std::error_code ec;
    for (const auto& entry : fs::directory_iterator("/dev", ec)) {
        const auto name = entry.path().filename().string();
        if (name.rfind("video", 0) != 0 || name.size() <= 5 ||
            !std::all_of(name.begin() + 5, name.end(), [](unsigned char c) { return std::isdigit(c) != 0; }))
            continue;
        nodes.push_back(entry.path());
    }
    std::sort(nodes.begin(), nodes.end(), [](const fs::path& a, const fs::path& b) {
        return a.filename().string() < b.filename().string();
    });
    return nodes;
}
}

static void set_error(const std::string& where) {
    g_last_error = where + ": " + std::strerror(errno);
}

extern "C" const char* ipcam_last_error(void) {
    return g_last_error.c_str();
}

extern "C" int ipcam_find_virtual_device(char* path, size_t path_size) {
    g_last_error.clear();
    if (!path || path_size == 0) {
        g_last_error = "Output path buffer is empty";
        return -1;
    }
    for (const auto& node : video_nodes()) {
        const int fd = ::open(node.c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        v4l2_capability cap{};
        const bool match = is_loopback_output(fd, &cap);
        ::close(fd);
        if (!match) continue;
        const auto value = node.string();
        if (value.size() + 1 > path_size) {
            g_last_error = "Output path buffer is too small";
            return -1;
        }
        std::memcpy(path, value.c_str(), value.size() + 1);
        return 0;
    }
    g_last_error =
        "No IPCamera Pro v4l2loopback output device found; load v4l2loopback "
        "with card_label=IPCamera Pro first";
    return -1;
}

extern "C" IpcamVideoDevice* ipcam_device_open(const char* path, int width, int height, int fps) {
    g_last_error.clear();
    if (!path || !*path || width < 2 || height < 2 || width > 4096 || height > 4096 ||
        (width & 1) || fps < 1 || fps > 120) {
        g_last_error = "Invalid device path, size, or FPS (even width required)";
        return nullptr;
    }

    const int fd = ::open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        set_error("open device");
        return nullptr;
    }

    v4l2_capability cap{};
    if (::ioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) {
        set_error("VIDIOC_QUERYCAP");
        ::close(fd);
        return nullptr;
    }
    const auto capabilities = (cap.capabilities & V4L2_CAP_DEVICE_CAPS) ? cap.device_caps : cap.capabilities;
    if (!(capabilities & V4L2_CAP_VIDEO_OUTPUT)) {
        g_last_error = "Device is not a V4L2 video OUTPUT device (is v4l2loopback loaded?)";
        ::close(fd);
        return nullptr;
    }

    v4l2_format fmt{};
    fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    fmt.fmt.pix.width = static_cast<__u32>(width);
    fmt.fmt.pix.height = static_cast<__u32>(height);
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUYV;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;
    fmt.fmt.pix.bytesperline = static_cast<__u32>(width * 2);
    fmt.fmt.pix.sizeimage = static_cast<__u32>(width * height * 2);

    if (::ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        set_error("VIDIOC_S_FMT");
        ::close(fd);
        return nullptr;
    }
    if (fmt.fmt.pix.pixelformat != V4L2_PIX_FMT_YUYV ||
        fmt.fmt.pix.width != static_cast<__u32>(width) ||
        fmt.fmt.pix.height != static_cast<__u32>(height) ||
        fmt.fmt.pix.bytesperline != static_cast<__u32>(width * 2)) {
        g_last_error = "V4L2 driver rejected the requested tightly-packed YUYV format";
        ::close(fd);
        return nullptr;
    }

    v4l2_streamparm parm{};
    parm.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    parm.parm.output.timeperframe.numerator = 1;
    parm.parm.output.timeperframe.denominator = static_cast<__u32>(fps);
    (void)::ioctl(fd, VIDIOC_S_PARM, &parm); // Not all drivers allow setting FPS.

    auto* device = new (std::nothrow) IpcamVideoDevice{fd, static_cast<size_t>(width) * height * 2};
    if (!device) {
        g_last_error = "Out of memory while opening V4L2 device";
        ::close(fd);
    }
    return device;
}

extern "C" int ipcam_device_write(IpcamVideoDevice* device, const unsigned char* frame, size_t count) {
    g_last_error.clear();
    if (!device || !frame || count != device->expected_frame_size) {
        g_last_error = "Incorrect frame pointer or size";
        return -1;
    }
    size_t offset = 0;
    while (offset < count) {
        const auto written = ::write(device->fd, frame + offset, count - offset);
        if (written < 0 && errno == EINTR) continue;
        if (written < 0) {
            set_error("V4L2 write");
            return -1;
        }
        if (written == 0) {
            g_last_error = "V4L2 write returned zero bytes";
            return -1;
        }
        offset += static_cast<size_t>(written);
    }
    return 0;
}

extern "C" void ipcam_device_close(IpcamVideoDevice* device) {
    if (!device) return;
    ::close(device->fd);
    delete device;
}
