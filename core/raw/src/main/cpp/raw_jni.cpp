#include <jni.h>
#include <libraw/libraw.h>
#include <algorithm>
#include <cmath>
#include <memory>
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
    const float greenScale = std::exp(std::clamp(values[2], -150.0f, 150.0f) / 900.0f);
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
    params.exp_shift = std::pow(2.0f, values[0]);
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
    const int pixels = image->width * image->height;
    std::vector<jint> argb(pixels);
    const float contrast = std::pow(2.0f, values[7]);
    const float saturation = 1.0f + values[8];
    const float vibrance = values[9];
    for (int i = 0; i < pixels; ++i) {
        float r = image->data[i * image->colors] / 255.0f;
        float g = image->data[i * image->colors + 1] / 255.0f;
        float b = image->data[i * image->colors + 2] / 255.0f;
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
    if (sharpening > 0.0f && image->width > 2 && image->height > 2) {
        const std::vector<jint> original = argb;
        for (int y = 1; y < image->height - 1; ++y) {
            for (int x = 1; x < image->width - 1; ++x) {
                const int index = y * image->width + x;
                const jint center = original[index];
                int outputChannels[3];
                const int shifts[3] = {16, 8, 0};
                for (int channel = 0; channel < 3; ++channel) {
                    const int shift = shifts[channel];
                    const int value = (center >> shift) & 0xff;
                    const int neighbours = ((original[index - 1] >> shift) & 0xff)
                        + ((original[index + 1] >> shift) & 0xff)
                        + ((original[index - image->width] >> shift) & 0xff)
                        + ((original[index + image->width] >> shift) & 0xff);
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
    jintArray colors = env->NewIntArray(pixels);
    env->SetIntArrayRegion(colors, 0, pixels, argb.data());
    jclass imageClass = env->FindClass("com/librestatic/lightforge/core/raw/RawNativeImage");
    jmethodID constructor = env->GetMethodID(imageClass, "<init>", "([III)V");
    jobject result = env->NewObject(imageClass, constructor, colors, image->width, image->height);
    LibRaw::dcraw_clear_mem(image);
    env->ReleaseFloatArrayElements(settings, values, JNI_ABORT);
    return result;
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
