#include <android/bitmap.h>
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

// Settings index 16: develop the camera framing (DNG DefaultCrop). Missing means on.
constexpr int FramingIndex = 16;
constexpr int SettingsCount = 17;

bool framingOf(const float* values, jsize length) {
    return length <= FramingIndex || values[FramingIndex] >= 0.5f;
}

// Narrows the visible area to the DNG DefaultCrop. Mask 1 selects DefaultCrop only (index 1,
// DefaultUserCrop, is out of scope) and maxcrop 0 accepts the 4-5x digital zooms some recorders
// store. Non-DNG maker-note inset crops are deliberately left alone. LibRaw's open_file already
// rounds the crop origin up to an even pixel, so the Bayer phase stays valid; nothing to do here.
void applyCameraFraming(LibRaw& raw) {
    if (raw.imgdata.idata.dng_version != 0) raw.adjust_to_raw_inset_crop(1, 0.0f);
}

bool open(LibRaw& raw, const std::string& path, bool cameraFraming, std::string& error) {
    const int code = raw.open_file(path.c_str());
    if (code != LIBRAW_SUCCESS) {
        error = libraw_strerror(code);
        return false;
    }
    if (cameraFraming) applyCameraFraming(raw);
    return true;
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
// render, the persistent preview and the bitmap export so all finish identically. Rows are
// produced one at a time (0xAARRGGBB) and handed to [emit], so callers choose the destination
// and the sharpening only ever needs a three-row window instead of a copy of the frame.
template <typename Emit>
void finishRows(const uint8_t* rgb, int stride, int width, int height, const float* values,
                Emit emit) {
    const float contrast = std::pow(2.0f, values[7]);
    const float saturation = 1.0f + values[8];
    const float vibrance = values[9];
    auto toneRow = [&](int y, uint32_t* out) {
        const uint8_t* row = rgb + static_cast<size_t>(y) * width * stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t* pixel = row + static_cast<size_t>(x) * stride;
            float r = pixel[0] / 255.0f;
            float g = pixel[1] / 255.0f;
            float b = pixel[2] / 255.0f;
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
            out[x] = 0xff000000u | (static_cast<uint32_t>(ri) << 16) | (static_cast<uint32_t>(gi) << 8)
                | static_cast<uint32_t>(bi);
        }
    };
    const float sharpening = std::clamp(values[13], 0.0f, 1.0f);
    if (!(sharpening > 0.0f && width > 2 && height > 2)) {
        std::vector<uint32_t> row(width);
        for (int y = 0; y < height; ++y) {
            toneRow(y, row.data());
            emit(y, row.data());
        }
        return;
    }
    // Sharpen from the unsharpened rows above and below; the one-pixel border stays untouched.
    std::vector<uint32_t> previous(width), current(width), next(width), output(width);
    toneRow(0, current.data());
    emit(0, current.data());
    toneRow(1, next.data());
    for (int y = 1; y < height - 1; ++y) {
        std::swap(previous, current);
        std::swap(current, next);
        toneRow(y + 1, next.data());
        output = current;
        const int shifts[3] = {16, 8, 0};
        for (int x = 1; x < width - 1; ++x) {
            uint32_t packed = 0xff000000u;
            for (int channel = 0; channel < 3; ++channel) {
                const int shift = shifts[channel];
                const int value = (current[x] >> shift) & 0xff;
                const int neighbours = ((current[x - 1] >> shift) & 0xff)
                    + ((current[x + 1] >> shift) & 0xff)
                    + ((previous[x] >> shift) & 0xff)
                    + ((next[x] >> shift) & 0xff);
                const int sharpened = std::clamp(
                    static_cast<int>(value + sharpening * 1.5f * (value - neighbours / 4.0f)), 0, 255);
                packed |= static_cast<uint32_t>(sharpened) << shift;
            }
            output[x] = packed;
        }
        emit(y, output.data());
    }
    emit(height - 1, next.data());
}

void finish(const uint8_t* rgb, int stride, int width, int height, const float* values,
            std::vector<jint>& argb) {
    argb.assign(static_cast<size_t>(width) * height, 0);
    finishRows(rgb, stride, width, height, values, [&](int y, const uint32_t* row) {
        std::copy(row, row + width, reinterpret_cast<uint32_t*>(argb.data()) + static_cast<size_t>(y) * width);
    });
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
    std::string path;
    bool cameraFraming = true;
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
    if (!open(*raw, utf(env, source), false, error)) return env->NewStringUTF(("ERROR|" + error).c_str());
    const auto& data = raw->imgdata;
    // Report the full visible area first, then the camera framing relative to it.
    const int fullWidth = data.sizes.width;
    const int fullHeight = data.sizes.height;
    const int fullLeft = data.sizes.left_margin;
    const int fullTop = data.sizes.top_margin;
    applyCameraFraming(*raw);
    const int cropLeft = data.sizes.left_margin - fullLeft;
    const int cropTop = data.sizes.top_margin - fullTop;
    const char* layout = data.idata.filters == 9 ? "XTrans" : (data.idata.filters ? "Bayer" : "Unknown");
    std::ostringstream output;
    output << clean(data.idata.make) << '|' << clean(data.idata.model) << '|'
           << clean(data.lens.Lens) << '|' << data.sizes.width << '|' << data.sizes.height << '|'
           << data.color.maximum << '|' << data.color.black << '|' << data.other.iso_speed << '|'
           << data.other.shutter << '|' << data.other.aperture << '|' << data.other.focal_len << '|'
           << layout << '|' << clean(data.idata.cdesc) << '|'
           << fullWidth << '|' << fullHeight << '|' << cropLeft << '|' << cropTop << '|'
           << data.sizes.width << '|' << data.sizes.height << '|' << data.sizes.flip;
    return env->NewStringUTF(output.str().c_str());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeRender(
    JNIEnv* env, jobject, jstring source, jfloatArray settings, jint maxDimension) {
    std::unique_ptr<LibRaw, RawCloser> raw(new LibRaw());
    std::string error;
    jfloat* values = env->GetFloatArrayElements(settings, nullptr);
    const bool framing = framingOf(values, env->GetArrayLength(settings));
    if (!open(*raw, utf(env, source), framing, error)) {
        env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
        return nullptr;
    }
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

// Returns the developed ARGB_8888 Bitmap, or a String with the error ("OutOfMemory" when the
// bitmap cannot be allocated). The bitmap is created here from LibRaw's actual output size, which
// can differ from the inspected one (odd DefaultCrop origins, orientation, LibRaw margins).
extern "C" JNIEXPORT jobject JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeRenderToBitmap(
    JNIEnv* env, jobject, jstring source, jfloatArray settings, jint maxDimension) {
    std::string error;
    std::unique_ptr<LibRaw, RawCloser> raw(new LibRaw());
    jfloat* values = env->GetFloatArrayElements(settings, nullptr);
    const bool framing = framingOf(values, env->GetArrayLength(settings));
    auto fail = [&](const std::string& message) -> jobject {
        env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
        return env->NewStringUTF(message.c_str());
    };
    if (!open(*raw, utf(env, source), framing, error)) return fail(error);
    configure(*raw, values, maxDimension, false);
    if (process(*raw, error) != LIBRAW_SUCCESS) return fail(error);
    int code = 0;
    libraw_processed_image_t* image = raw->dcraw_make_mem_image(&code);
    // The demosaiced working buffers are the largest allocation: release them before converting.
    raw->recycle();
    if (!image || code != LIBRAW_SUCCESS || image->type != LIBRAW_IMAGE_BITMAP || image->colors < 3) {
        if (image) LibRaw::dcraw_clear_mem(image);
        return fail("RAW render failed (" + std::to_string(code) + ")");
    }
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb = env->GetStaticObjectField(configClass, argbField);
    jmethodID create = env->GetStaticMethodID(
        bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(
        bitmapClass, create, static_cast<jint>(image->width), static_cast<jint>(image->height), argb);
    if (env->ExceptionCheck() || !bitmap) {
        env->ExceptionClear();
        LibRaw::dcraw_clear_mem(image);
        return fail("OutOfMemory");
    }
    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS
        || AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        LibRaw::dcraw_clear_mem(image);
        return fail("Bitmap pixels cannot be locked");
    }
    // ARGB_8888 is RGBA bytes in memory, i.e. 0xAABBGGRR as a little-endian word.
    finishRows(image->data, image->colors, image->width, image->height, values,
               [&](int y, const uint32_t* row) {
        auto* out = reinterpret_cast<uint32_t*>(static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride);
        for (uint32_t x = 0; x < info.width; ++x) {
            const uint32_t p = row[x];
            out[x] = (p & 0xff00ff00u) | ((p >> 16) & 0xffu) | ((p & 0xffu) << 16);
        }
    });
    AndroidBitmap_unlockPixels(env, bitmap);
    LibRaw::dcraw_clear_mem(image);
    env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
    return bitmap;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeOpenPreview(
    JNIEnv* env, jobject, jstring source, jboolean cameraFraming) {
    auto handle = std::make_unique<PreviewHandle>();
    handle->raw.reset(new LibRaw());
    std::string error;
    handle->path = utf(env, source);
    handle->cameraFraming = cameraFraming == JNI_TRUE;
    if (!open(*handle->raw, handle->path, handle->cameraFraming, error)) return 0;
    if (handle->raw->unpack() != LIBRAW_SUCCESS) return 0;
    return reinterpret_cast<jlong>(handle.release());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_librestatic_lightforge_core_raw_LibRawBridge_nativeRenderPreview(
    JNIEnv* env, jobject, jlong pointer, jfloatArray settings, jint maxDimension, jboolean approximate) {
    auto* handle = reinterpret_cast<PreviewHandle*>(pointer);
    if (!handle || env->GetArrayLength(settings) < 14) return nullptr;
    float values[SettingsCount] = {};
    values[FramingIndex] = 1.0f;
    const jsize length = env->GetArrayLength(settings);
    env->GetFloatArrayRegion(settings, 0, std::min<jsize>(length, SettingsCount), values);
    std::lock_guard<std::mutex> guard(handle->lock);
    const bool framing = framingOf(values, length);
    if (framing != handle->cameraFraming) {
        // The unpacked raw and the developed base are both tied to the visible area: reopen it.
        std::unique_ptr<LibRaw, RawCloser> reopened(new LibRaw());
        std::string error;
        if (!open(*reopened, handle->path, framing, error) || reopened->unpack() != LIBRAW_SUCCESS) {
            return nullptr;
        }
        handle->raw = std::move(reopened);
        handle->cameraFraming = framing;
        handle->valid = false;
    }
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
    jfloat* values = env->GetFloatArrayElements(settings, nullptr);
    const bool framing = framingOf(values, env->GetArrayLength(settings));
    if (!open(*raw, utf(env, source), framing, error)) {
        env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
        return env->NewStringUTF(error.c_str());
    }
    configure(*raw, values, 0, true);
    raw->imgdata.params.output_tiff = 1;
    if (process(*raw, error) == LIBRAW_SUCCESS) {
        const int code = raw->dcraw_ppm_tiff_writer(utf(env, destination).c_str());
        if (code != LIBRAW_SUCCESS) error = libraw_strerror(code);
    }
    env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
    return env->NewStringUTF(error.c_str());
}
