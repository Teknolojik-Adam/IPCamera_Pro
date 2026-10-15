#include "ipcam_pattern.hpp"
#include <cassert>
#include <stdexcept>
#include <iostream>

int main() {
    const auto first = ipcam::make_test_frame(320, 180, 0);
    const auto second = ipcam::make_test_frame(320, 180, 9);
    assert(first.size() == 320u * 180u * 2u);
    assert(second.size() == first.size());
    assert(first != second && "Pattern must animate across frames");
    // YUYV = Y0 U Y1 V, first pair of first bar is bright neutral white.
    assert(first[0] == 235 && first[1] == 128 && first[2] == 235 && first[3] == 128);
    bool bad_failed = false;
    try { (void)ipcam::make_test_frame(321, 180, 0); }
    catch (const std::invalid_argument&) { bad_failed = true; }
    assert(bad_failed);
    std::cout << "native YUYV frames: sizes, animation, format and errors OK\n";
}
