#include "ipcam_linux.h"

#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

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
    const std::vector<unsigned char> frame(320u * 240u * 2u, 128);
    for (int cycle = 0; cycle < 2; ++cycle) {
        IpcamVideoDevice* device = ipcam_device_open(path.c_str(), 320, 240, 30);
        if (!device) {
            std::cerr << "FAIL: open cycle " << cycle + 1 << ": " << ipcam_last_error() << "\n";
            return 1;
        }
        if (ipcam_device_write(device, frame.data(), frame.size()) != 0) {
            std::cerr << "FAIL: write cycle " << cycle + 1 << ": " << ipcam_last_error() << "\n";
            ipcam_device_close(device);
            return 1;
        }
        ipcam_device_close(device);
    }
    std::cout << "PASS: virtual camera opened, wrote, closed and reopened cleanly\n";
    return 0;
}
