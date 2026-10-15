#pragma once

#include <cstdint>
#include <vector>

namespace ipcam {
// Render one 8-band color pattern as tightly packed YUYV.
// Width must be positive and even; height must be positive.
std::vector<std::uint8_t> make_test_frame(int width, int height, std::uint64_t frame_index);
} // namespace ipcam
