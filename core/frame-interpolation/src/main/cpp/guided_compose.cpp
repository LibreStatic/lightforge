#include "guided_compose.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#if defined(__ARM_NEON) && defined(__aarch64__)
#include <arm_neon.h>
#endif

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

#if defined(__ARM_NEON) && defined(__aarch64__)
// Planes of the horizontally upsampled low-res row: flow x/y towards A, towards B, mask.
constexpr int kPlanes = 5;
constexpr int kPad = 4;

struct RowCache {
    std::vector<float> data[2];
    int key[2] = {-1, -1};
    size_t cols;
};

void upsample_row(const GuidedComposeParams& p, const std::vector<Tap>& xt, int low_row, float* dst) {
    const size_t cols = static_cast<size_t>(p.width) + kPad;
    for (int c = 0; c < kPlanes; ++c) {
        const float* src = (c < 4 ? p.flow + c * p.flow_cstep + low_row * p.flow_stride
                                  : p.mask + low_row * p.mask_stride);
        float* out = dst + c * cols;
        for (int x = 0; x < p.width; ++x) {
            const Tap& t = xt[x];
            out[x] = src[t.i0] + (src[t.i1] - src[t.i0]) * t.f;
        }
        for (int x = p.width; x < p.width + kPad; ++x) out[x] = 0.f;
    }
}

const float* cached_row(RowCache& rc, const GuidedComposeParams& p, const std::vector<Tap>& xt, int row, int keep) {
    for (int s = 0; s < 2; ++s)
        if (rc.key[s] == row) return rc.data[s].data();
    const int s = rc.key[0] == keep ? 1 : 0;
    rc.key[s] = row;
    upsample_row(p, xt, row, rc.data[s].data());
    return rc.data[s].data();
}

// Bilinear sample of four pixels; returns the four RGBA taps blended as floats.
inline float32x4_t load_rgba(const uint8_t* base, uint32_t off) {
    uint32_t v;
    __builtin_memcpy(&v, base + off, 4);
    return vcvtq_f32_u32(vmovl_u16(vget_low_u16(vmovl_u8(vreinterpret_u8_u32(vdup_n_u32(v))))));
}

struct Coords {
    alignas(16) uint32_t off[4][4];  // [tap][lane]
    alignas(16) float fx[4];
    alignas(16) float fy[4];
};

inline void make_coords(float32x4_t sx, float32x4_t sy, int w, int h, size_t stride, Coords& c) {
    sx = vminq_f32(vmaxq_f32(sx, vdupq_n_f32(0.f)), vdupq_n_f32(static_cast<float>(w - 1)));
    sy = vminq_f32(vmaxq_f32(sy, vdupq_n_f32(0.f)), vdupq_n_f32(static_cast<float>(h - 1)));
    const int32x4_t x0 = vcvtq_s32_f32(sx);
    const int32x4_t y0 = vcvtq_s32_f32(sy);
    const int32x4_t x1 = vminq_s32(vaddq_s32(x0, vdupq_n_s32(1)), vdupq_n_s32(w - 1));
    const int32x4_t y1 = vminq_s32(vaddq_s32(y0, vdupq_n_s32(1)), vdupq_n_s32(h - 1));
    vst1q_f32(c.fx, vsubq_f32(sx, vcvtq_f32_s32(x0)));
    vst1q_f32(c.fy, vsubq_f32(sy, vcvtq_f32_s32(y0)));
    const int32x4_t s = vdupq_n_s32(static_cast<int32_t>(stride));
    const int32x4_t r0 = vmulq_s32(y0, s);
    const int32x4_t r1 = vmulq_s32(y1, s);
    const int32x4_t b0 = vshlq_n_s32(x0, 2);
    const int32x4_t b1 = vshlq_n_s32(x1, 2);
    vst1q_u32(c.off[0], vreinterpretq_u32_s32(vaddq_s32(r0, b0)));
    vst1q_u32(c.off[1], vreinterpretq_u32_s32(vaddq_s32(r0, b1)));
    vst1q_u32(c.off[2], vreinterpretq_u32_s32(vaddq_s32(r1, b0)));
    vst1q_u32(c.off[3], vreinterpretq_u32_s32(vaddq_s32(r1, b1)));
}

inline float32x4_t sample_lane(const uint8_t* base, const Coords& c, int i) {
    const float32x4_t p00 = load_rgba(base, c.off[0][i]);
    const float32x4_t p10 = load_rgba(base, c.off[1][i]);
    const float32x4_t p01 = load_rgba(base, c.off[2][i]);
    const float32x4_t p11 = load_rgba(base, c.off[3][i]);
    const float32x4_t top = vfmaq_n_f32(p00, vsubq_f32(p10, p00), c.fx[i]);
    const float32x4_t bot = vfmaq_n_f32(p01, vsubq_f32(p11, p01), c.fx[i]);
    return vfmaq_n_f32(top, vsubq_f32(bot, top), c.fy[i]);
}

void run_rows(const GuidedComposeParams& p, const std::vector<Tap>& xt, const std::vector<Tap>& yt,
              float sx, float sy, int begin, int end) {
    const size_t cols = static_cast<size_t>(p.width) + kPad;
    RowCache rc;
    rc.cols = cols;
    for (auto& d : rc.data) d.assign(cols * kPlanes, 0.f);
    const float32x4_t lane = {0.f, 1.f, 2.f, 3.f};
    for (int y = begin; y < end; ++y) {
        const Tap& ty = yt[y];
        const float* h0 = cached_row(rc, p, xt, ty.i0, ty.i1);
        const float* h1 = cached_row(rc, p, xt, ty.i1, ty.i0);
        const float32x4_t vfy = vdupq_n_f32(ty.f);
        const float32x4_t vy = vdupq_n_f32(static_cast<float>(y));
        uint8_t* out = p.output + y * p.output_stride;
        for (int x = 0; x < p.width; x += 4) {
            float32x4_t f[kPlanes];
            for (int c = 0; c < kPlanes; ++c) {
                const float32x4_t a = vld1q_f32(h0 + c * cols + x);
                const float32x4_t b = vld1q_f32(h1 + c * cols + x);
                f[c] = vfmaq_f32(a, vsubq_f32(b, a), vfy);
            }
            const float32x4_t vx = vaddq_f32(vdupq_n_f32(static_cast<float>(x)), lane);
            Coords ca, cb;
            make_coords(vfmaq_n_f32(vx, f[0], sx), vfmaq_n_f32(vy, f[1], sy), p.width, p.height, p.first_stride, ca);
            make_coords(vfmaq_n_f32(vx, f[2], sx), vfmaq_n_f32(vy, f[3], sy), p.width, p.height, p.second_stride, cb);
            alignas(16) float m[4];
            vst1q_f32(m, vminq_f32(vmaxq_f32(f[4], vdupq_n_f32(0.f)), vdupq_n_f32(1.f)));
            const int n = std::min(4, p.width - x);
            for (int i = 0; i < n; ++i) {
                const float32x4_t a = sample_lane(p.first, ca, i);
                const float32x4_t b = sample_lane(p.second, cb, i);
                float32x4_t v = vfmaq_n_f32(b, vsubq_f32(a, b), m[i]);
                v = vminq_f32(vmaxq_f32(vaddq_f32(v, vdupq_n_f32(0.5f)), vdupq_n_f32(0.f)), vdupq_n_f32(255.f));
                const uint16x4_t h = vmovn_u32(vcvtq_u32_f32(v));
                const uint8x8_t bytes = vmovn_u16(vcombine_u16(h, h));
                const uint32_t px = vget_lane_u32(vreinterpret_u32_u8(bytes), 0) | 0xFF000000u;
                __builtin_memcpy(out + (x + i) * 4, &px, 4);
            }
        }
    }
}
#else
void run_rows(const GuidedComposeParams& p, const std::vector<Tap>& xt, const std::vector<Tap>& yt,
              float sx, float sy, int begin, int end) {
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
}
#endif

// Persistent worker pool. A call splits its rows into chunks that the workers and
// the calling thread claim from an atomic counter, so the caller never blocks on a
// busy pool (safe for concurrent callers and for calls made from pool threads).
struct Job {
    void (*fn)(void*, int) = nullptr;
    void* ctx = nullptr;
    int total = 0;
    std::atomic<int> next{0};
    std::atomic<int> done{0};
    std::mutex m;
    std::condition_variable cv;
};

void drain(Job& job) {
    for (;;) {
        const int c = job.next.fetch_add(1);
        if (c >= job.total) return;
        job.fn(job.ctx, c);
        if (job.done.fetch_add(1) + 1 == job.total) {
            std::lock_guard<std::mutex> lock(job.m);
            job.cv.notify_all();
        }
    }
}

class Pool {
public:
    static Pool& get() {
        static Pool* pool = new Pool();  // intentionally leaked: workers outlive static destruction
        return *pool;
    }

    int threads() const { return workers_ + 1; }

    void run(void (*fn)(void*, int), void* ctx, int chunks) {
        auto job = std::make_shared<Job>();
        job->fn = fn;
        job->ctx = ctx;
        job->total = chunks;
        {
            std::lock_guard<std::mutex> lock(m_);
            queue_.push_back(job);
        }
        cv_.notify_all();
        drain(*job);
        std::unique_lock<std::mutex> lock(job->m);
        job->cv.wait(lock, [&] { return job->done.load() == job->total; });
    }

private:
    Pool() {
        const unsigned hw = std::thread::hardware_concurrency();
        workers_ = static_cast<int>(std::min(8u, std::max(1u, hw))) - 1;
        for (int i = 0; i < workers_; ++i) std::thread([this] { loop(); }).detach();
    }

    void loop() {
        for (;;) {
            std::shared_ptr<Job> job;
            {
                std::unique_lock<std::mutex> lock(m_);
                cv_.wait(lock, [&] { return !queue_.empty(); });
                job = queue_.front();
            }
            drain(*job);
            std::lock_guard<std::mutex> lock(m_);
            const auto it = std::find(queue_.begin(), queue_.end(), job);
            if (it != queue_.end()) queue_.erase(it);
        }
    }

    int workers_ = 0;
    std::mutex m_;
    std::condition_variable cv_;
    std::deque<std::shared_ptr<Job>> queue_;
};
}  // namespace

void guided_compose(const GuidedComposeParams& p) {
    const std::vector<Tap> xt = build_taps(p.width, p.low_width);
    const std::vector<Tap> yt = build_taps(p.height, p.low_height);
    const float sx = static_cast<float>(p.width) / static_cast<float>(p.low_width);
    const float sy = static_cast<float>(p.height) / static_cast<float>(p.low_height);

    Pool& pool = Pool::get();
    const int per = std::max(16, (p.height + pool.threads() * 3 - 1) / (pool.threads() * 3));
    const int chunks = (p.height + per - 1) / per;
    if (chunks <= 1 || pool.threads() == 1) {
        run_rows(p, xt, yt, sx, sy, 0, p.height);
        return;
    }
    struct Ctx {
        const GuidedComposeParams& p;
        const std::vector<Tap>& xt;
        const std::vector<Tap>& yt;
        float sx, sy;
        int per;
    } ctx{p, xt, yt, sx, sy, per};
    pool.run([](void* c, int i) {
        const Ctx& x = *static_cast<Ctx*>(c);
        run_rows(x.p, x.xt, x.yt, x.sx, x.sy, i * x.per, std::min(x.p.height, (i + 1) * x.per));
    }, &ctx, chunks);
}
