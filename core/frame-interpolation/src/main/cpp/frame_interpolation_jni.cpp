#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <gpu.h>
#include <memory>
#include <mutex>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include "rife/rife.h"
#include "guided_compose.h"

namespace {
constexpr const char* TAG = "LightforgeRife";
std::mutex gpu_mutex;
int gpu_users = 0;

struct Engine {
    std::unique_ptr<RIFE> rife;
    bool vulkan;
    bool gpu_instance_acquired;

    Engine(const std::string& model_dir, bool prefer_vulkan) {
        int gpu_id = -1;
        gpu_instance_acquired = false;
        if (prefer_vulkan) {
            std::lock_guard<std::mutex> lock(gpu_mutex);
            if (gpu_users++ == 0) ncnn::create_gpu_instance();
            gpu_instance_acquired = true;
            if (ncnn::get_gpu_count() > 0) gpu_id = ncnn::get_default_gpu_index();
            vulkan = gpu_id >= 0;
        } else {
            vulkan = false;
        }
        rife = std::make_unique<RIFE>(gpu_id, false, false, false, 2, false, true);
        if (rife->load(model_dir) != 0) throw std::runtime_error("Could not load bundled RIFE model");
    }

    ~Engine() {
        rife.reset();
        if (gpu_instance_acquired) {
            std::lock_guard<std::mutex> lock(gpu_mutex);
            if (--gpu_users == 0) ncnn::destroy_gpu_instance();
        }
    }
};

bool bitmap_to_rgb(JNIEnv* env, jobject bitmap, ncnn::Mat& out, AndroidBitmapInfo& info) {
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    std::vector<unsigned char> rgb(static_cast<size_t>(info.width) * info.height * 3);
    for (uint32_t y = 0; y < info.height; ++y) {
        auto* row = reinterpret_cast<uint8_t*>(pixels) + y * info.stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            const size_t source = x * 4;
            const size_t target = (static_cast<size_t>(y) * info.width + x) * 3;
            rgb[target] = row[source];
            rgb[target + 1] = row[source + 1];
            rgb[target + 2] = row[source + 2];
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    // Match stb/Android image inputs used by the upstream RIFE executable: one
    // packed RGB element per pixel. RIFE immediately converts this packed Mat
    // to planar float channels with Mat::from_pixels().
    out = ncnn::Mat(static_cast<int>(info.width), static_cast<int>(info.height), static_cast<size_t>(3u), 3);
    memcpy(out.data, rgb.data(), rgb.size());
    return true;
}

bool rgb_to_bitmap(JNIEnv* env, const ncnn::Mat& image, jobject bitmap) {
    AndroidBitmapInfo info{};
    const int info_status = AndroidBitmap_getInfo(env, bitmap, &info);
    if (info_status != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        static_cast<int>(info.width) != image.w || static_cast<int>(info.height) != image.h) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            TAG,
            "Output mismatch status=%d bitmap=%ux%u format=%d model=%dx%d c=%d elemsize=%zu pack=%d",
            info_status, info.width, info.height, info.format, image.w, image.h, image.c, image.elemsize, image.elempack
        );
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    const auto* rgb = reinterpret_cast<const uint8_t*>(image.data);
    for (uint32_t y = 0; y < info.height; ++y) {
        auto* row = reinterpret_cast<uint8_t*>(pixels) + y * info.stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            const size_t source = (static_cast<size_t>(y) * info.width + x) * 3;
            const size_t target = x * 4;
            row[target] = rgb[source];
            row[target + 1] = rgb[source + 1];
            row[target + 2] = rgb[source + 2];
            row[target + 3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_librestatic_lightforge_core_frameinterpolation_RifeFrameInterpolator_nativeCreate(
    JNIEnv* env, jobject, jstring model_dir, jboolean prefer_vulkan
) {
    const char* raw = env->GetStringUTFChars(model_dir, nullptr);
    try {
        auto* engine = new Engine(raw, prefer_vulkan);
        env->ReleaseStringUTFChars(model_dir, raw);
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception& error) {
        env->ReleaseStringUTFChars(model_dir, raw);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_librestatic_lightforge_core_frameinterpolation_RifeFrameInterpolator_nativeUsesVulkan(
    JNIEnv*, jobject, jlong handle
) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    return engine && engine->vulkan;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_librestatic_lightforge_core_frameinterpolation_RifeFrameInterpolator_nativeInterpolate(
    JNIEnv* env, jobject, jlong handle, jobject first, jobject second, jfloat timestep, jobject output
) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (!engine || timestep <= 0.f || timestep >= 1.f) return -1;
    AndroidBitmapInfo first_info{}, second_info{};
    ncnn::Mat first_rgb, second_rgb, result;
    if (!bitmap_to_rgb(env, first, first_rgb, first_info) ||
        !bitmap_to_rgb(env, second, second_rgb, second_info) ||
        first_info.width != second_info.width || first_info.height != second_info.height) return -2;
    // RIFE's upstream API writes pixels into caller-owned storage rather than
    // allocating outimage itself (the CLI wraps its output byte buffer in Mat).
    result = ncnn::Mat(
        static_cast<int>(first_info.width),
        static_cast<int>(first_info.height),
        static_cast<size_t>(3u),
        3
    );
    const int status = engine->rife->process(first_rgb, second_rgb, timestep, result);
    if (status != 0) return status;
    return rgb_to_bitmap(env, result, output) ? 0 : -3;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_librestatic_lightforge_core_frameinterpolation_RifeFrameInterpolator_nativeInterpolateGuided(
    JNIEnv* env, jobject, jlong handle, jobject low_first, jobject low_second,
    jobject high_first, jobject high_second, jfloat timestep, jobject output
) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (!engine || !engine->vulkan || timestep <= 0.f || timestep >= 1.f) return -1;
    AndroidBitmapInfo low_a{}, low_b{}, high_a{}, high_b{}, out_info{};
    ncnn::Mat first_rgb, second_rgb, flow, mask;
    if (!bitmap_to_rgb(env, low_first, first_rgb, low_a) ||
        !bitmap_to_rgb(env, low_second, second_rgb, low_b) ||
        low_a.width != low_b.width || low_a.height != low_b.height) return -2;
    if (AndroidBitmap_getInfo(env, high_first, &high_a) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, high_second, &high_b) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, output, &out_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        high_a.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        high_b.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        out_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        high_a.width != high_b.width || high_a.height != high_b.height ||
        out_info.width != high_a.width || out_info.height != high_a.height) return -3;
    const int status = engine->rife->process_v4_flow(first_rgb, second_rgb, timestep, flow, mask);
    if (status != 0) return status;

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
    params.low_width = static_cast<int>(low_a.width);
    params.low_height = static_cast<int>(low_a.height);
    params.flow = static_cast<const float*>(flow.data);
    params.flow_cstep = flow.cstep;
    params.flow_stride = static_cast<size_t>(flow.w);
    params.mask = static_cast<const float*>(mask.data);
    params.mask_stride = static_cast<size_t>(mask.w);
    guided_compose(params);
    AndroidBitmap_unlockPixels(env, output);
    AndroidBitmap_unlockPixels(env, high_second);
    AndroidBitmap_unlockPixels(env, high_first);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_librestatic_lightforge_core_frameinterpolation_RifeFrameInterpolator_nativeClose(
    JNIEnv*, jobject, jlong handle
) {
    delete reinterpret_cast<Engine*>(handle);
}
