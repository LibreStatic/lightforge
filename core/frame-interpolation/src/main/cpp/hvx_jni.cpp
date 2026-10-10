#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include "guided_compose.h"
#include "lfrife/hvx_weights.h"

#if defined(__aarch64__)
#include "lfrife/lfrife.h"
#define LIGHTFORGE_HVX 1
#endif

namespace {
constexpr const char* TAG = "LightforgeHvx";
// Returned by nativeInterpolateGuided when the DSP call failed: kHvxFailure + lfrife error code (-1..-8).
constexpr jint kHvxFailure = -1000;

#ifdef LIGHTFORGE_HVX
bool read_file(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    out.resize(size > 0 ? static_cast<size_t>(size) : 0);
    const bool ok = size > 0 && std::fread(out.data(), 1, out.size(), f) == out.size();
    std::fclose(f);
    return ok;
}

bool write_file_atomic(const std::string& path, const std::vector<uint8_t>& data) {
    const std::string temporary = path + ".tmp";
    FILE* f = std::fopen(temporary.c_str(), "wb");
    if (!f) return false;
    const bool ok = std::fwrite(data.data(), 1, data.size(), f) == data.size();
    const bool closed = std::fclose(f) == 0;
    if (!ok || !closed || std::rename(temporary.c_str(), path.c_str()) != 0) {
        std::remove(temporary.c_str());
        return false;
    }
    return true;
}

// The converted weights depend only on flownet.bin/.param, so they are converted once and kept next to the model.
bool load_blob(const std::string& model_dir, const std::string& cache_path, std::vector<uint8_t>& blob) {
    if (!cache_path.empty() && read_file(cache_path, blob) && hvxw::is_valid_hvx_blob(blob.data(), blob.size()))
        return true;
    std::vector<uint8_t> bin, param;
    if (!read_file(model_dir + "/flownet.bin", bin) || !read_file(model_dir + "/flownet.param", param)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not read the RIFE model in %s", model_dir.c_str());
        return false;
    }
    std::string error;
    if (!hvxw::convert_ncnn_to_hvx(bin.data(), bin.size(), reinterpret_cast<const char*>(param.data()),
                                     param.size(), blob, error)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "HVX weight conversion failed: %s", error.c_str());
        return false;
    }
    if (!cache_path.empty() && !write_file_atomic(cache_path, blob))
        __android_log_print(ANDROID_LOG_WARN, TAG, "Could not cache the HVX weights");
    return true;
}

// Per-call working memory, recycled: fresh multi-megabyte vectors cost more than the copy into them.
struct HvxBuffers {
    std::vector<uint8_t> net_a, net_b;
    std::vector<float> flow, mask;
};

struct HvxEngine {
    std::string skel_dir;
    std::mutex pool_mutex;
    std::vector<std::unique_ptr<HvxBuffers>> pool;  // idle buffer sets, one per concurrent caller at most

    std::unique_ptr<HvxBuffers> take_buffers(size_t plane) {
        std::unique_ptr<HvxBuffers> buffers;
        {
            std::lock_guard<std::mutex> lock(pool_mutex);
            if (!pool.empty()) {
                buffers = std::move(pool.back());
                pool.pop_back();
            }
        }
        if (!buffers) buffers = std::make_unique<HvxBuffers>();
        buffers->net_a.resize(plane * 4);
        buffers->net_b.resize(plane * 4);
        buffers->flow.resize(plane * 4);
        buffers->mask.resize(plane);
        return buffers;
    }

    void give_back(std::unique_ptr<HvxBuffers> buffers) {
        std::lock_guard<std::mutex> lock(pool_mutex);
        pool.push_back(std::move(buffers));
    }

    std::vector<uint8_t> blob;
    lfrife* handle = nullptr;
    int width = 0, height = 0;
    std::mutex mutex;  // one lfrife handle must never be used by two threads at once

    // Caller holds [mutex]. Reopens the (fixed-size) DSP session when the network size changes.
    int ensure_size(int w, int h) {
        if (handle && width == w && height == h) return LFRIFE_OK;
        if (handle) lfrife_close(handle);
        handle = nullptr;
        lfrife_config cfg{};
        cfg.skel_dir = skel_dir.c_str();
        cfg.weights_blob = blob.data();
        cfg.weights_size = blob.size();
        cfg.width = w;
        cfg.height = h;
        cfg.nthreads = 6;  // all HVX contexts of the SM8845 class; 4 (the library default) is ~20 % slower
        cfg.qos = -1;
        // Corner 8 holds the core at its top clock (1862 MHz on the SM8845); DCVS lets it sink to about 1100 MHz
        // while the CPU composes between calls. 255 also maxes the bus clocks: 25-55 % more energy for ~2 % speed.
        cfg.power_corner = 8;
        const int rc = lfrife_open(&cfg, &handle);
        if (rc != LFRIFE_OK) {
            handle = nullptr;
            return rc;
        }
        width = w;
        height = h;
        return LFRIFE_OK;
    }

    ~HvxEngine() {
        if (handle) lfrife_close(handle);
    }
};

// Half-pixel-centre bilinear resample of an RGBA_8888 image (alpha forced opaque).
void resize_rgba(const uint8_t* src, size_t src_stride, int sw, int sh, uint8_t* dst, int dw, int dh) {
    if (sw == dw && sh == dh) {  // the usual case: the caller already scaled to a multiple of 32
        for (int y = 0; y < dh; ++y) std::memcpy(dst + static_cast<size_t>(y) * dw * 4, src + y * src_stride, dw * 4u);
        return;
    }
    const float sx = static_cast<float>(sw) / dw;
    const float sy = static_cast<float>(sh) / dh;
    for (int y = 0; y < dh; ++y) {
        const float fy = std::min(std::max((y + 0.5f) * sy - 0.5f, 0.f), static_cast<float>(sh - 1));
        const int y0 = static_cast<int>(fy);
        const int y1 = std::min(y0 + 1, sh - 1);
        const float wy = fy - y0;
        const uint8_t* r0 = src + y0 * src_stride;
        const uint8_t* r1 = src + y1 * src_stride;
        uint8_t* out = dst + static_cast<size_t>(y) * dw * 4;
        for (int x = 0; x < dw; ++x) {
            const float fx = std::min(std::max((x + 0.5f) * sx - 0.5f, 0.f), static_cast<float>(sw - 1));
            const int x0 = static_cast<int>(fx);
            const int x1 = std::min(x0 + 1, sw - 1);
            const float wx = fx - x0;
            for (int c = 0; c < 3; ++c) {
                const float top = r0[x0 * 4 + c] * (1.f - wx) + r0[x1 * 4 + c] * wx;
                const float bottom = r1[x0 * 4 + c] * (1.f - wx) + r1[x1 * 4 + c] * wx;
                out[x * 4 + c] = static_cast<uint8_t>(top * (1.f - wy) + bottom * wy + 0.5f);
            }
            out[x * 4 + 3] = 255;
        }
    }
}
#endif  // LIGHTFORGE_HVX

[[maybe_unused]] std::string to_string(JNIEnv* env, jstring s) {
    const char* raw = env->GetStringUTFChars(s, nullptr);
    std::string out(raw);
    env->ReleaseStringUTFChars(s, raw);
    return out;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_librestatic_lightforge_core_frameinterpolation_HvxNative_nativeCompiled(JNIEnv*, jobject) {
#ifdef LIGHTFORGE_HVX
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// Returns a handle (any non-zero value outside -1000..0; tagged pointers can be negative) or the negated lfrife error code / -100 when the weights could not be prepared.
extern "C" JNIEXPORT jlong JNICALL
Java_com_librestatic_lightforge_core_frameinterpolation_HvxNative_nativeOpen(
    JNIEnv* env, jobject, jstring skel_dir, jstring model_dir, jstring blob_cache, jint width, jint height
) {
#ifdef LIGHTFORGE_HVX
    auto engine = std::make_unique<HvxEngine>();
    engine->skel_dir = to_string(env, skel_dir);
    if (!load_blob(to_string(env, model_dir), to_string(env, blob_cache), engine->blob)) return -100;
    int rc;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        rc = engine->ensure_size(width, height);
    }
    if (rc != LFRIFE_OK) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "lfrife_open failed: %s (%d)", lfrife_strerror(rc), rc);
        return rc < 0 ? rc : -100;
    }
    return reinterpret_cast<jlong>(engine.release());
#else
    return -2;  // LFRIFE_ERR_NO_RPC: no HVX on this ABI
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_librestatic_lightforge_core_frameinterpolation_HvxNative_nativeClose(JNIEnv*, jobject, jlong handle) {
#ifdef LIGHTFORGE_HVX
    delete reinterpret_cast<HvxEngine*>(handle);
#endif
}

// Same contract as RifeFrameInterpolator.nativeInterpolateGuided; the low frames are resampled to net_w x net_h
// (multiples of 32) for the DSP network. The flow and mask come back at that size, which guided_compose rescales.
extern "C" JNIEXPORT jint JNICALL
Java_com_librestatic_lightforge_core_frameinterpolation_HvxNative_nativeInterpolateGuided(
    JNIEnv* env, jobject, jlong handle, jobject low_first, jobject low_second,
    jobject high_first, jobject high_second, jfloat timestep, jobject output, jint net_w, jint net_h
) {
#ifdef LIGHTFORGE_HVX
    auto* engine = reinterpret_cast<HvxEngine*>(handle);
    if (!engine || timestep <= 0.f || timestep >= 1.f || net_w <= 0 || net_h <= 0 || net_w % 32 || net_h % 32)
        return -1;
    AndroidBitmapInfo low_a{}, low_b{}, high_a{}, high_b{}, out_info{};
    if (AndroidBitmap_getInfo(env, low_first, &low_a) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, low_second, &low_b) != ANDROID_BITMAP_RESULT_SUCCESS ||
        low_a.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || low_b.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        low_a.width != low_b.width || low_a.height != low_b.height) return -2;
    if (AndroidBitmap_getInfo(env, high_first, &high_a) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, high_second, &high_b) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output, &out_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        high_a.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        high_b.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        out_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        high_a.width != high_b.width || high_a.height != high_b.height ||
        out_info.width != high_a.width || out_info.height != high_a.height) return -3;

    const size_t plane = static_cast<size_t>(net_w) * net_h;
    std::unique_ptr<HvxBuffers> buffers = engine->take_buffers(plane);
    // Back to the pool on every return path.
    struct GiveBack {
        HvxEngine* engine; std::unique_ptr<HvxBuffers>& buffers;
        ~GiveBack() { engine->give_back(std::move(buffers)); }
    } give_back{engine, buffers};
    std::vector<uint8_t>& net_a = buffers->net_a;
    std::vector<uint8_t>& net_b = buffers->net_b;
    {
        void* pa = nullptr; void* pb = nullptr;
        if (AndroidBitmap_lockPixels(env, low_first, &pa) != ANDROID_BITMAP_RESULT_SUCCESS) return -4;
        if (AndroidBitmap_lockPixels(env, low_second, &pb) != ANDROID_BITMAP_RESULT_SUCCESS) {
            AndroidBitmap_unlockPixels(env, low_first);
            return -4;
        }
        resize_rgba(static_cast<const uint8_t*>(pa), low_a.stride, low_a.width, low_a.height,
                    net_a.data(), net_w, net_h);
        resize_rgba(static_cast<const uint8_t*>(pb), low_b.stride, low_b.width, low_b.height,
                    net_b.data(), net_w, net_h);
        AndroidBitmap_unlockPixels(env, low_second);
        AndroidBitmap_unlockPixels(env, low_first);
    }

    // Planar flow [0:2] towards the first frame, [2:4] towards the second, in network pixels; mask after the
    // sigmoid. That is exactly what RIFE::process_v4_flow returns from the ncnn blobs "327" and "332".
    std::vector<float>& flow = buffers->flow;
    std::vector<float>& mask = buffers->mask;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        int rc = engine->ensure_size(net_w, net_h);
        if (rc == LFRIFE_OK) {
            rc = lfrife_flow(engine->handle, net_a.data(), net_b.data(), net_w * 4, net_w, net_h, timestep,
                             flow.data(), mask.data());
        }
        if (rc != LFRIFE_OK) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "lfrife_flow failed: %s (%d)", lfrife_strerror(rc), rc);
            return kHvxFailure + (rc < 0 ? rc : -8);
        }
    }

    void* pa = nullptr; void* pb = nullptr; void* po = nullptr;
    if (AndroidBitmap_lockPixels(env, high_first, &pa) != ANDROID_BITMAP_RESULT_SUCCESS) return -4;
    if (AndroidBitmap_lockPixels(env, high_second, &pb) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, high_first);
        return -4;
    }
    if (AndroidBitmap_lockPixels(env, output, &po) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, high_first);
        AndroidBitmap_unlockPixels(env, high_second);
        return -4;
    }
    GuidedComposeParams params{};
    params.first = static_cast<const uint8_t*>(pa);
    params.second = static_cast<const uint8_t*>(pb);
    params.output = static_cast<uint8_t*>(po);
    params.first_stride = high_a.stride;
    params.second_stride = high_b.stride;
    params.output_stride = out_info.stride;
    params.width = static_cast<int>(high_a.width);
    params.height = static_cast<int>(high_a.height);
    params.low_width = net_w;
    params.low_height = net_h;
    params.flow = flow.data();
    params.flow_cstep = plane;
    params.flow_stride = static_cast<size_t>(net_w);
    params.mask = mask.data();
    params.mask_stride = static_cast<size_t>(net_w);
    guided_compose(params);
    AndroidBitmap_unlockPixels(env, output);
    AndroidBitmap_unlockPixels(env, high_second);
    AndroidBitmap_unlockPixels(env, high_first);
    return 0;
#else
    return kHvxFailure - 2;
#endif
}
