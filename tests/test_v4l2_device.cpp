#include "ipcam_linux.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <linux/videodev2.h>
#include <string>
#include <sys/ioctl.h>
#include <unistd.h>

namespace {
constexpr int kSkip = 77;

std::string find_device() {
    if (const char* explicit_device = std::getenv("IPCAM_V4L2_DEVICE")) return explicit_device;
    char path[256]{};
    return ipcam_find_virtual_device(path, sizeof(path)) == 0 ? path : std::string{};
}
}

int main() {
    const std::string path = find_device();
    if (path.empty()) {
        std::cout << "SKIP: no IPCamera Pro virtual device\n";
        return kSkip;
    }
    const int fd = ::open(path.c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        std::cerr << "FAIL: cannot open discovered device " << path << ": " << std::strerror(errno) << "\n";
        return 1;
    }
    v4l2_capability cap{};
    const auto caps_ok = ::ioctl(fd, VIDIOC_QUERYCAP, &cap) == 0;
    const auto caps = (cap.capabilities & V4L2_CAP_DEVICE_CAPS) ? cap.device_caps : cap.capabilities;
    const bool valid = caps_ok && (caps & V4L2_CAP_VIDEO_OUTPUT) &&
        std::string(reinterpret_cast<const char*>(cap.driver)).find("v4l2 loopback") != std::string::npos &&
        std::string(reinterpret_cast<const char*>(cap.card)).find("IPCamera Pro") == 0;
    ::close(fd);
    if (!valid) {
        std::cerr << "FAIL: discovered node is not an IPCamera Pro v4l2loopback OUTPUT device\n";
        return 1;
    }
    std::cout << "PASS: discovered " << path << " as IPCamera Pro / v4l2loopback OUTPUT\n";
    return 0;
}
