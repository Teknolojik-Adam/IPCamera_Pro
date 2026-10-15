#include "ipcam_linux.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <linux/videodev2.h>
#include <poll.h>
#include <string>
#include <sys/ioctl.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr int kSkip = 77; // CTest reports this as Not Run, not Passed.

std::string selected_device() {
    if (const char* explicit_device = std::getenv("IPCAM_V4L2_DEVICE"))
        return explicit_device;
    char path[256]{};
    if (ipcam_find_virtual_device(path, sizeof(path)) == 0) return path;
    return {};
}

bool read_frame(int fd, std::vector<unsigned char>& frame) {
    std::size_t offset = 0;
    while (offset < frame.size()) {
        pollfd pfd{fd, POLLIN | POLLERR | POLLHUP, 0};
        const int ready = ::poll(&pfd, 1, 1500);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) return false;
        const ssize_t got = ::read(fd, frame.data() + offset, frame.size() - offset);
        if (got < 0 && errno == EINTR) continue;
        if (got < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (got <= 0) return false;
        offset += static_cast<std::size_t>(got);
    }
    return true;
}
}

int main() {
    const std::string path = selected_device();
    if (path.empty()) {
        std::cout << "SKIP: no IPCamera Pro v4l2loopback device is available\n";
        return kSkip;
    }

    constexpr int width = 320;
    constexpr int height = 240;
    const std::size_t frame_size = static_cast<std::size_t>(width) * height * 2;
    IpcamVideoDevice* output = ipcam_device_open(path.c_str(), width, height, 30);
    if (!output) {
        std::cerr << "FAIL: output open: " << ipcam_last_error() << "\n";
        return 1;
    }

    const int capture = ::open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (capture < 0) {
        std::cerr << "FAIL: capture open " << path << ": " << std::strerror(errno) << "\n";
        ipcam_device_close(output);
        return 1;
    }
    v4l2_capability cap{};
    if (::ioctl(capture, VIDIOC_QUERYCAP, &cap) < 0 ||
        !((cap.capabilities & V4L2_CAP_DEVICE_CAPS ? cap.device_caps : cap.capabilities) & V4L2_CAP_VIDEO_CAPTURE)) {
        std::cerr << "FAIL: loopback did not expose a capture capability\n";
        ::close(capture);
        ipcam_device_close(output);
        return 1;
    }
    v4l2_format capture_format{};
    capture_format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (::ioctl(capture, VIDIOC_G_FMT, &capture_format) < 0 ||
        capture_format.fmt.pix.pixelformat != V4L2_PIX_FMT_YUYV ||
        capture_format.fmt.pix.width != static_cast<__u32>(width) ||
        capture_format.fmt.pix.height != static_cast<__u32>(height) ||
        capture_format.fmt.pix.bytesperline != static_cast<__u32>(width * 2)) {
        std::cerr << "FAIL: capture format is not tightly packed YUYV "
                  << width << "x" << height << "\n";
        ::close(capture);
        ipcam_device_close(output);
        return 1;
    }

    std::vector<unsigned char> frame(frame_size, 0);
    std::vector<unsigned char> received(frame_size);
    for (int index = 0; index < 3; ++index) {
        for (std::size_t i = 0; i < frame.size(); i += 2) {
            frame[i] = static_cast<unsigned char>(16 + index * 32);
            frame[i + 1] = static_cast<unsigned char>(128 + (i / 2) % 32);
        }
        if (ipcam_device_write(output, frame.data(), frame.size()) != 0) {
            std::cerr << "FAIL: frame write: " << ipcam_last_error() << "\n";
            ::close(capture);
            ipcam_device_close(output);
            return 1;
        }
        if (!read_frame(capture, received)) {
            std::cerr << "FAIL: capture did not return frame " << index + 1 << "\n";
            ::close(capture);
            ipcam_device_close(output);
            return 1;
        }
        if (received != frame) {
            std::cerr << "FAIL: captured frame " << index + 1 << " differs from sent frame\n";
            ::close(capture);
            ipcam_device_close(output);
            return 1;
        }
    }

    ::close(capture);
    ipcam_device_close(output);
    std::cout << "PASS: wrote and read 3 YUYV frames through " << path << "\n";
    return 0;
}
