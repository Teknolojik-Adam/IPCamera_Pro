#include "ipcam_pattern.hpp"

#include <array>
#include <stdexcept>

namespace ipcam {
struct Yuv { std::uint8_t y, u, v; };

std::vector<std::uint8_t> make_test_frame(int width, int height, std::uint64_t frame_index) {
    if (width <= 0 || height <= 0 || width % 2 != 0 || width > 4096 || height > 4096)
        throw std::invalid_argument("Color-bar dimensions must have even width, 2..4096, and height 1..4096");
    constexpr std::array<Yuv, 8> bars{{
        {235,128,128}, // white
        {210, 16,146}, // yellow
        {170,166, 16}, // cyan
        {145, 54, 34}, // green
        {106,202,222}, // magenta
        { 81, 90,240}, // red
        { 41,240,110}, // blue
        { 16,128,128}  // black
    }};
    std::vector<std::uint8_t> output(static_cast<std::size_t>(width)*height*2);
    const int band_width = width / 8;
    const int sweep_x = static_cast<int>((frame_index * 7) % static_cast<std::uint64_t>(width));
    for (int y=0; y<height; ++y) {
        for (int x=0; x<width; x+=2) {
            int band = band_width ? (x * 8 / width) : (x % 8);
            if (band>7) band=7;
            auto c = bars[static_cast<std::size_t>(band)];
            // The moving stripe verifies that the stream is live, not a still image.
            if (y > height/3 && y < 2*height/3 && x >= sweep_x && x < sweep_x+14)
                c = {235, 128, 128};
            auto p = (static_cast<std::size_t>(y)*width+x)*2;
            output[p]   = c.y;
            output[p+1] = c.u;
            output[p+2] = c.y;
            output[p+3] = c.v;
        }
    }
    return output;
}
} // namespace ipcam
