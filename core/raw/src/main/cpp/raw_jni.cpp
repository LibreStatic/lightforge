#include <jni.h>
#include <libraw/libraw.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace {
struct RawCloser { void operator()(LibRaw* raw) const { if (raw) raw->recycle(); delete raw; } };

std::string utf(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string clean(const char* value) {
    std::string result(value ? value : "");
    std::replace(result.begin(), result.end(), '|', ' ');
    return result;
}

bool open(LibRaw& raw, const std::string& path, std::string& error) {
    const int code = raw.open_file(path.c_str());
    if (code == LIBRAW_SUCCESS) return true;
    error = libraw_strerror(code);
    return false;
}

void configure(LibRaw& raw, const float* values, int maxDimension, bool sixteenBit) {
    auto& params = raw.imgdata.params;
    const float temperatureRatio = std::clamp(values[1] / 6500.0f, 0.25f, 3.25f);
    const float redScale = std::pow(temperatureRatio, 0.35f);
    const float blueScale = std::pow(temperatureRatio, -0.35f);
    // Positive tint moves towards magenta (the Lightroom convention), so it must attenuate green.
    const float greenScale = std::exp(-std::clamp(values[2], -150.0f, 150.0f) / 900.0f);
    for (int channel = 0; channel < 4; ++channel) {
        const float cameraMultiplier = raw.imgdata.color.cam_mul[channel] > 0.0f
            ? raw.imgdata.color.cam_mul[channel] : 1.0f;
        const float scale = channel == 0 ? redScale : (channel == 2 ? blueScale : greenScale);
        params.user_mul[channel] = cameraMultiplier * scale;
    }
    params.use_camera_wb = 0;
    params.use_auto_wb = 0;
    params.no_auto_bright = 1;
    params.exp_correc = 1;
    // LibRaw's exposure shift only spans 0.25..8 (-2..+3 EV) and silently clamps beyond that,
    // which made the outer part of the -5..+5 EV slider a dead zone. The remainder is applied
    // through the (linear) output brightness gain, which both the bitmap and TIFF paths honour.
    const float requestedGain = std::pow(2.0f, std::clamp(values[0], -5.0f, 5.0f));
    const float appliedShift = std::clamp(requestedGain, 0.25f, 8.0f);
    params.exp_shift = appliedShift;
    params.bright = requestedGain / appliedShift;
    params.exp_preser = std::clamp(values[10], 0.0f, 1.0f);
    params.highlight = values[10] > 0.66f ? 5 : (values[10] > 0.05f ? 3 : 0);
    params.threshold = values[11] * 800.0f;
    params.fbdd_noiserd = values[12] > 0.66f ? 2 : (values[12] > 0.05f ? 1 : 0);
    params.med_passes = values[12] > 0.5f ? 1 : 0;
    params.output_color = 1;
    params.output_bps = sixteenBit ? 16 : 8;
    params.user_qual = 3;
    const int largest = std::max(raw.imgdata.sizes.width, raw.imgdata.sizes.height);
    params.half_size = maxDimension > 0 && largest > maxDimension * 2;
}

// Contrast, tone, saturation and sharpening on an 8-bit RGB buffer; shared by the stateless
// render and the persistent preview so both finish identically.
void finish(const uint8_t* rgb, int stride, int width, int height, const float* values,
            std::vector<jint>& argb) {
    const int pixels = width * height;
    argb.assign(pixels, 0);
    const float contrast = std::pow(2.0f, values[7]);
    const float saturation = 1.0f + values[8];
    const float vibrance = values[9];
    for (int i = 0; i < pixels; ++i) {
        float r = rgb[i * stride] / 255.0f;
        float g = rgb[i * stride + 1] / 255.0f;
        float b = rgb[i * stride + 2] / 255.0f;
        r = (r - 0.5f) * contrast + 0.5f;
        g = (g - 0.5f) * contrast + 0.5f;
        b = (b - 0.5f) * contrast + 0.5f;
        float luma = r * 0.2126f + g * 0.7152f + b * 0.0722f;
        const float shadowWeight = std::pow(std::clamp(1.0f - luma, 0.0f, 1.0f), 2.0f);
        const float highlightWeight = std::pow(std::clamp(luma, 0.0f, 1.0f), 2.0f);
        const float toneScale = 1.0f + values[4] * shadowWeight + values[3] * highlightWeight;
        r *= toneScale;
        g *= toneScale;
        b *= toneScale;
        // Whites and blacks operate on opposite ends of the tonal range without moving
        // mid-grey as aggressively as exposure.
        r += values[5] * 0.25f * highlightWeight + values[6] * 0.20f * shadowWeight;
        g += values[5] * 0.25f * highlightWeight + values[6] * 0.20f * shadowWeight;
        b += values[5] * 0.25f * highlightWeight + values[6] * 0.20f * shadowWeight;
        luma = r * 0.2126f + g * 0.7152f + b * 0.0722f;
        const float chroma = std::max(r, std::max(g, b)) - std::min(r, std::min(g, b));
        const float vibranceScale = 1.0f + vibrance * (1.0f - std::clamp(chroma, 0.0f, 1.0f));
        const float totalSaturation = saturation * vibranceScale;
        r = luma + (r - luma) * totalSaturation;
        g = luma + (g - luma) * totalSaturation;
        b = luma + (b - luma) * totalSaturation;
        const int ri = std::clamp(static_cast<int>(r * 255.0f), 0, 255);
        const int gi = std::clamp(static_cast<int>(g * 255.0f), 0, 255);
        const int bi = std::clamp(static_cast<int>(b * 255.0f), 0, 255);
        argb[i] = static_cast<jint>(0xff000000u | (ri << 16) | (gi << 8) | bi);
    }
    const float sharpening = std::clamp(values[13], 0.0f, 1.0f);
    if (sharpening > 0.0f && width > 2 && height > 2) {
        const std::vector<jint> original = argb;
        for (int y = 1; y < height - 1; ++y) {
            for (int x = 1; x < width - 1; ++x) {
                const int index = y * width + x;
                const jint center = original[index];
                int outputChannels[3];
                const int shifts[3] = {16, 8, 0};
                for (int channel = 0; channel < 3; ++channel) {
                    const int shift = shifts[channel];
                    const int value = (center >> shift) & 0xff;
                    const int neighbours = ((original[index - 1] >> shift) & 0xff)
                        + ((original[index + 1] >> shift) & 0xff)
                        + ((original[index - width] >> shift) & 0xff)
                        + ((original[index + width] >> shift) & 0xff);
                    outputChannels[channel] = std::clamp(
                        static_cast<int>(value + sharpening * 1.5f * (value - neighbours / 4.0f)),
                        0,
                        255
                    );
                }
                argb[index] = static_cast<jint>(0xff000000u | (outputChannels[0] << 16)
                    | (outputChannels[1] << 8) | outputChannels[2]);
            }
        }
    }
}


jobject makeImage(JNIEnv* env, const std::vector<jint>& argb, int width, int height) {
    jintArray colors = env->NewIntArray(static_cast<jsize>(argb.size()));
    env->SetIntArrayRegion(colors, 0, static_cast<jsize>(argb.size()), argb.data());
    jclass imageClass = env->FindClass("com/librestatic/lightforge/core/raw/RawNativeImage");
    jmethodID constructor = env->GetMethodID(imageClass, "<init>", "([III)V");
    return env->NewObject(imageClass, constructor, colors, width, height);
}

// Persistent preview: the RAW is opened and unpacked once, and the demosaiced base is cached so
// finishing-only slider changes never re-develop.
constexpr int DevelopKey[6] = {0, 1, 2, 10, 11, 12};

struct PreviewHandle {
    std::mutex lock;
    std::unique_ptr<LibRaw, RawCloser> raw;
    float developed[6] = {};
    int developedMaxDimension = 0;
    std::vector<uint8_t> base;  // packed RGB
    int width = 0;
    int height = 0;
    bool valid = false;
};

void channelScales(const float* values, float* scales) {
    const float ratio = std::clamp(values[1] / 6500.0f, 0.25f, 3.25f);
    scales[0] = std::pow(ratio, 0.35f);
    scales[1] = std::exp(-std::clamp(values[2], -150.0f, 150.0f) / 900.0f);
    scales[2] = std::pow(ratio, -0.35f);
}

float decodeBt709(float v) { return v < 0.081f ? v / 4.5f : std::pow((v + 0.099f) / 1.099f, 1.0f / 0.45f); }
float encodeBt709(float v) { return v < 0.018f ? v * 4.5f : 1.099f * std::pow(v, 0.45f) - 0.099f; }

// Builds per-channel LUTs that move the developed base from the developed exposure/white balance
// to the requested one without re-running the demosaic.
void buildCorrection(const float* values, const float* developed, const float* cameraMultipliers,
                     uint8_t lut[3][256]) {
    float newScales[3], oldScales[3], ratios[3];
    channelScales(values, newScales);
    const float oldValues[3] = {developed[0], developed[1], developed[2]};
    channelScales(oldValues, oldScales);
    // dcraw scale_colors normalises the absolute multipliers (camera x user scale, as configure()
    // sets them) by the smallest one without highlight recovery, otherwise by the largest. That
    // is usually green, so warming the image lowers blue instead of brightening everything.
    const bool lowestBase = developed[3] <= 0.05f;
    auto reference = [&](const float* scales) {
        float result = 0.0f;
        for (int channel = 0; channel < 4; ++channel) {
            const float camera = cameraMultipliers[channel] > 0.0f ? cameraMultipliers[channel] : 1.0f;
            const float value = camera * scales[channel == 3 ? 1 : channel];
            if (channel == 0 || (lowestBase ? value < result : value > result)) result = value;
        }
        return result;
    };
    const float newReference = reference(newScales);
    const float oldReference = reference(oldScales);
    for (int c = 0; c < 3; ++c) ratios[c] = (newScales[c] / newReference) / (oldScales[c] / oldReference);
    const float exposure = std::pow(2.0f, std::clamp(values[0], -5.0f, 5.0f)
                                              - std::clamp(developed[0], -5.0f, 5.0f));
    for (int c = 0; c < 3; ++c) {
        const float gain = exposure * ratios[c];
        for (int i = 0; i < 256; ++i) {
            const float linear = decodeBt709(i / 255.0f) * gain;
            const int encoded = static_cast<int>(encodeBt709(std::clamp(linear, 0.0f, 1.0f)) * 255.0f + 0.5f);
            lut[c][i] = static_cast<uint8_t>(std::clamp(encoded, 0, 255));
        }
    }
}

// Area-average (box) downscale of interleaved 8-bit pixels so the longest side is <= maxDimension.
void boxDownscale(const uint8_t* src, int channels, int width, int height, int maxDimension,
                  std::vector<uint8_t>& out, int& outWidth, int& outHeight) {
    const int largest = std::max(width, height);
    if (maxDimension <= 0 || largest <= maxDimension) {
        outWidth = width;
        outHeight = height;
        out.resize(static_cast<size_t>(width) * height * 3);
        for (int i = 0; i < width * height; ++i)
            for (int c = 0; c < 3; ++c) out[i * 3 + c] = src[i * channels + c];
        return;
    }
    outWidth = std::max(1, static_cast<int>(std::lround(static_cast<double>(width) * maxDimension / largest)));
    outHeight = std::max(1, static_cast<int>(std::lround(static_cast<double>(height) * maxDimension / largest)));
    out.resize(static_cast<size_t>(outWidth) * outHeight * 3);
    for (int y = 0; y < outHeight; ++y) {
        const int y0 = static_cast<int>(static_cast<int64_t>(y) * height / outHeight);
        const int y1 = std::min(height, std::max(y0 + 1, static_cast<int>((static_cast<int64_t>(y + 1) * height + outHeight - 1) / outHeight)));
        for (int x = 0; x < outWidth; ++x) {
            const int x0 = static_cast<int>(static_cast<int64_t>(x) * width / outWidth);
            const int x1 = std::min(width, std::max(x0 + 1, static_cast<int>((static_cast<int64_t>(x + 1) * width + outWidth - 1) / outWidth)));
            uint32_t sum[3] = {0, 0, 0};
            for (int sy = y0; sy < y1; ++sy) {
                const uint8_t* row = src + (static_cast<size_t>(sy) * width + x0) * channels;
                for (int sx = x0; sx < x1; ++sx, row += channels)
                    for (int c = 0; c < 3; ++c) sum[c] += row[c];
            }
            const uint32_t count = static_cast<uint32_t>((y1 - y0) * (x1 - x0));
            for (int c = 0; c < 3; ++c)
                out[(static_cast<size_t>(y) * outWidth + x) * 3 + c] = static_cast<uint8_t>((sum[c] + count / 2) / count);
        }
    }
}

bool develop(PreviewHandle& handle, const float* values, int maxDimension) {
    LibRaw& raw = *handle.raw;
    configure(raw, values, maxDimension, false);
    bool ok = raw.dcraw_process() == LIBRAW_SUCCESS;
    if (ok) {
        int code = 0;
        libraw_processed_image_t* image = raw.dcraw_make_mem_image(&code);
        ok = image && code == LIBRAW_SUCCESS && image->type == LIBRAW_IMAGE_BITMAP && image->colors >= 3;
        if (ok) {
            boxDownscale(image->data, image->colors, image->width, image->height, maxDimension,
                         handle.base, handle.width, handle.height);
            for (int i = 0; i < 6; ++i) handle.developed[i] = values[DevelopKey[i]];
            handle.developedMaxDimension = maxDimension;
        }
        if (image) LibRaw::dcraw_clear_mem(image);
    }
    // The processing buffer is the large one; the packed base is all later frames need.
    raw.free_image();
    handle.valid = ok;
    return ok;
}

int process(LibRaw& raw, std::string& error) {
    int code = raw.unpack();
    if (code == LIBRAW_SUCCESS) code = raw.dcraw_process();
    if (code != LIBRAW_SUCCESS) error = libraw_strerror(code);
    return code;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeInspect(JNIEnv* env, jobject, jstring source) {
    std::unique_ptr<LibRaw, RawCloser> raw(new LibRaw());
    std::string error;
    if (!open(*raw, utf(env, source), error)) return env->NewStringUTF(("ERROR|" + error).c_str());
    const auto& data = raw->imgdata;
    const char* layout = data.idata.filters == 9 ? "XTrans" : (data.idata.filters ? "Bayer" : "Unknown");
    std::ostringstream output;
    output << clean(data.idata.make) << '|' << clean(data.idata.model) << '|'
           << clean(data.lens.Lens) << '|' << data.sizes.width << '|' << data.sizes.height << '|'
           << data.color.maximum << '|' << data.color.black << '|' << data.other.iso_speed << '|'
           << data.other.shutter << '|' << data.other.aperture << '|' << data.other.focal_len << '|'
           << layout << '|' << clean(data.idata.cdesc);
    return env->NewStringUTF(output.str().c_str());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeRender(
    JNIEnv* env, jobject, jstring source, jfloatArray settings, jint maxDimension) {
    std::unique_ptr<LibRaw, RawCloser> raw(new LibRaw());
    std::string error;
    if (!open(*raw, utf(env, source), error)) return nullptr;
    jfloat* values = env->GetFloatArrayElements(settings, nullptr);
    configure(*raw, values, maxDimension, false);
    if (process(*raw, error) != LIBRAW_SUCCESS) {
        env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
        return nullptr;
    }
    int code = 0;
    libraw_processed_image_t* image = raw->dcraw_make_mem_image(&code);
    if (!image || code != LIBRAW_SUCCESS || image->type != LIBRAW_IMAGE_BITMAP || image->colors < 3) {
        if (image) LibRaw::dcraw_clear_mem(image);
        env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
        return nullptr;
    }
    std::vector<jint> argb;
    finish(image->data, image->colors, image->width, image->height, values, argb);
    jobject result = makeImage(env, argb, image->width, image->height);
    LibRaw::dcraw_clear_mem(image);
    env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeOpenPreview(JNIEnv* env, jobject, jstring source) {
    auto handle = std::make_unique<PreviewHandle>();
    handle->raw.reset(new LibRaw());
    std::string error;
    if (!open(*handle->raw, utf(env, source), error)) return 0;
    if (handle->raw->unpack() != LIBRAW_SUCCESS) return 0;
    return reinterpret_cast<jlong>(handle.release());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeRenderPreview(
    JNIEnv* env, jobject, jlong pointer, jfloatArray settings, jint maxDimension, jboolean approximate) {
    auto* handle = reinterpret_cast<PreviewHandle*>(pointer);
    if (!handle || env->GetArrayLength(settings) < 14) return nullptr;
    float values[16] = {};
    env->GetFloatArrayRegion(settings, 0, std::min<jsize>(env->GetArrayLength(settings), 16), values);
    std::lock_guard<std::mutex> guard(handle->lock);
    bool keyChanged = false;
    bool qualityChanged = false;
    for (int i = 0; i < 6; ++i) {
        if (values[DevelopKey[i]] != handle->developed[i]) {
            keyChanged = true;
            if (i >= 3) qualityChanged = true;
        }
    }
    // While dragging (approximate) exposure and white balance are corrected on the cached base
    // instead of re-developing, so the preview follows the finger; the final render is exact.
    // An approximate frame reuses the cached base at whatever size it was developed, so the first
    // drag after an exact render does not pay for a re-develop.
    const bool needsDevelop = !handle->valid
        || (!approximate && maxDimension != handle->developedMaxDimension)
        || (keyChanged && (!approximate || qualityChanged));
    if (needsDevelop && !develop(*handle, values, maxDimension)) return nullptr;
    std::vector<uint8_t> corrected;
    const uint8_t* rgb = handle->base.data();
    if (!needsDevelop && keyChanged) {
        uint8_t lut[3][256];
        buildCorrection(values, handle->developed, handle->raw->imgdata.color.cam_mul, lut);
        corrected.resize(handle->base.size());
        for (size_t i = 0; i < corrected.size(); ++i) corrected[i] = lut[i % 3][handle->base[i]];
        rgb = corrected.data();
    }
    std::vector<jint> argb;
    finish(rgb, 3, handle->width, handle->height, values, argb);
    return makeImage(env, argb, handle->width, handle->height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeClosePreview(JNIEnv*, jobject, jlong pointer) {
    auto* handle = reinterpret_cast<PreviewHandle*>(pointer);
    if (!handle) return;
    { std::lock_guard<std::mutex> guard(handle->lock); }
    delete handle;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeExportTiff(
    JNIEnv* env, jobject, jstring source, jstring destination, jfloatArray settings) {
    std::unique_ptr<LibRaw, RawCloser> raw(new LibRaw());
    std::string error;
    if (!open(*raw, utf(env, source), error)) return env->NewStringUTF(error.c_str());
    jfloat* values = env->GetFloatArrayElements(settings, nullptr);
    configure(*raw, values, 0, true);
    raw->imgdata.params.output_tiff = 1;
    if (process(*raw, error) == LIBRAW_SUCCESS) {
        const int code = raw->dcraw_ppm_tiff_writer(utf(env, destination).c_str());
        if (code != LIBRAW_SUCCESS) error = libraw_strerror(code);
    }
    env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
    return env->NewStringUTF(error.c_str());
}
