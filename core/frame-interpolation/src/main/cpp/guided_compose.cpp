#include "guided_compose.h"

#include <algorithm>
#include <cmath>
#include <thread>
#include <vector>

namespace {
struct Tap {
    int i0;
    int i1;
    float f;
};

inline void sample_rgb(const uint8_t* base, size_t stride, int w, int h, float sx, float sy, float* rgb) {
    sx = std::min(std::max(sx, 0.f), static_cast<float>(w - 1));
    sy = std::min(std::max(sy, 0.f), static_cast<float>(h - 1));
    const int x0 = static_cast<int>(sx);
    const int y0 = static_cast<int>(sy);
    const int x1 = std::min(x0 + 1, w - 1);
    const int y1 = std::min(y0 + 1, h - 1);
    const float fx = sx - x0;
    const float fy = sy - y0;
    const uint8_t* r0 = base + y0 * stride;
    const uint8_t* r1 = base + y1 * stride;
    const uint8_t* p00 = r0 + x0 * 4;
    const uint8_t* p10 = r0 + x1 * 4;
    const uint8_t* p01 = r1 + x0 * 4;
    const uint8_t* p11 = r1 + x1 * 4;
    const float w00 = (1.f - fx) * (1.f - fy);
    const float w10 = fx * (1.f - fy);
    const float w01 = (1.f - fx) * fy;
    const float w11 = fx * fy;
    for (int c = 0; c < 3; ++c) rgb[c] = p00[c] * w00 + p10[c] * w10 + p01[c] * w01 + p11[c] * w11;
}

std::vector<Tap> build_taps(int high, int low) {
    std::vector<Tap> taps(high);
    const float scale = static_cast<float>(low) / static_cast<float>(high);
    for (int i = 0; i < high; ++i) {
        float u = (i + 0.5f) * scale - 0.5f;
        u = std::min(std::max(u, 0.f), static_cast<float>(low - 1));
        const int i0 = static_cast<int>(u);
        taps[i] = {i0, std::min(i0 + 1, low - 1), u - i0};
    }
    return taps;
}
}  // namespace

void guided_compose(const GuidedComposeParams& p) {
    const std::vector<Tap> xt = build_taps(p.width, p.low_width);
    const std::vector<Tap> yt = build_taps(p.height, p.low_height);
    const float sx = static_cast<float>(p.width) / static_cast<float>(p.low_width);
    const float sy = static_cast<float>(p.height) / static_cast<float>(p.low_height);

    const auto run_rows = [&](int begin, int end) {
        for (int y = begin; y < end; ++y) {
            const Tap& ty = yt[y];
            const size_t row0 = static_cast<size_t>(ty.i0) * p.flow_stride;
            const size_t row1 = static_cast<size_t>(ty.i1) * p.flow_stride;
            const size_t mrow0 = static_cast<size_t>(ty.i0) * p.mask_stride;
            const size_t mrow1 = static_cast<size_t>(ty.i1) * p.mask_stride;
            uint8_t* out = p.output + y * p.output_stride;
            for (int x = 0; x < p.width; ++x) {
                const Tap& tx = xt[x];
                const float w00 = (1.f - tx.f) * (1.f - ty.f);
                const float w10 = tx.f * (1.f - ty.f);
                const float w01 = (1.f - tx.f) * ty.f;
                const float w11 = tx.f * ty.f;
                float f[4];
                for (int c = 0; c < 4; ++c) {
                    const float* plane = p.flow + c * p.flow_cstep;
                    f[c] = plane[row0 + tx.i0] * w00 + plane[row0 + tx.i1] * w10 +
                           plane[row1 + tx.i0] * w01 + plane[row1 + tx.i1] * w11;
                }
                const float m = std::min(std::max(
                    p.mask[mrow0 + tx.i0] * w00 + p.mask[mrow0 + tx.i1] * w10 +
                    p.mask[mrow1 + tx.i0] * w01 + p.mask[mrow1 + tx.i1] * w11, 0.f), 1.f);
                float a[3], b[3];
                sample_rgb(p.first, p.first_stride, p.width, p.height, x + f[0] * sx, y + f[1] * sy, a);
                sample_rgb(p.second, p.second_stride, p.width, p.height, x + f[2] * sx, y + f[3] * sy, b);
                uint8_t* px = out + x * 4;
                for (int c = 0; c < 3; ++c) {
                    const float v = a[c] * m + b[c] * (1.f - m);
                    px[c] = static_cast<uint8_t>(std::min(std::max(v + 0.5f, 0.f), 255.f));
                }
                px[3] = 255;
            }
        }
    };

    const int threads = std::max(1, std::min(4, p.height / 16));
    if (threads == 1) {
        run_rows(0, p.height);
        return;
    }
    std::vector<std::thread> pool;
    const int per = (p.height + threads - 1) / threads;
    for (int t = 1; t < threads; ++t) {
        const int begin = t * per;
        const int end = std::min(p.height, begin + per);
        if (begin < end) pool.emplace_back(run_rows, begin, end);
    }
    run_rows(0, std::min(p.height, per));
    for (auto& th : pool) th.join();
}
