// Thin JNI bridge over the vendored FFmpeg video decoders (flv/Sorenson Spark, vp6f, vp6a).
// Mirrors the YV12 surface path of Media3's libvpx extension (vpx_jni.cc).
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/log.h>
#include <libavutil/mem.h>
}

#define TAG "LegacyVideoJni"
#define HAL_PIXEL_FORMAT_YV12 0x32315659

namespace {

// Keep in sync with FfmpegVideoDecoder.kt.
enum { kOk = 0, kEagain = 1, kEof = 2, kError = -1 };

struct Decoder {
    AVCodecContext* ctx = nullptr;
    AVPacket* pkt = nullptr;
    AVFrame* frame = nullptr;
    bool has_frame = false;
};

void LogCallback(void*, int level, const char* fmt, va_list args) {
    if (level > AV_LOG_WARNING) return;
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, args);
    __android_log_write(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "FFmpeg", buf);
}

int MapStatus(int ret) {
    if (ret >= 0) return kOk;
    if (ret == AVERROR(EAGAIN)) return kEagain;
    if (ret == AVERROR_EOF) return kEof;
    return kError;
}

enum AVCodecID CodecIdFor(jint id) {
    switch (id) {
        case 0: return AV_CODEC_ID_FLV1;
        case 1: return AV_CODEC_ID_VP6F;
        case 2: return AV_CODEC_ID_VP6A;
        default: return AV_CODEC_ID_NONE;
    }
}

bool IsYuv420(const AVFrame* f) {
    return f->format == AV_PIX_FMT_YUV420P || f->format == AV_PIX_FMT_YUVJ420P ||
           f->format == AV_PIX_FMT_YUVA420P;
}

void FreeDecoder(Decoder* d) {
    if (!d) return;
    av_frame_free(&d->frame);
    av_packet_free(&d->pkt);
    avcodec_free_context(&d->ctx);
    delete d;
}

// ---- Surface cache (one window at a time; guarded by g_lock). ----
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
jobject g_surface = nullptr;  // global ref used only for IsSameObject
ANativeWindow* g_window = nullptr;
int g_w = 0, g_h = 0;

void ReleaseSurfaceLocked(JNIEnv* env) {
    if (g_window) ANativeWindow_release(g_window);
    if (g_surface) env->DeleteGlobalRef(g_surface);
    g_window = nullptr;
    g_surface = nullptr;
    g_w = g_h = 0;
}

inline int Align(int v, int a) { return (v + a - 1) & ~(a - 1); }

bool RenderPlanes(JNIEnv* env, jobject surface, const uint8_t* y, const uint8_t* u,
                  const uint8_t* v, int y_stride, int uv_stride, int w, int h) {
    if (!surface || w <= 0 || h <= 0) return false;
    pthread_mutex_lock(&g_lock);
    bool ok = false;
    if (!g_surface || !env->IsSameObject(g_surface, surface)) {
        ReleaseSurfaceLocked(env);
        g_window = ANativeWindow_fromSurface(env, surface);
        if (!g_window) goto done;
        g_surface = env->NewGlobalRef(surface);
    }
    if (g_w != w || g_h != h) {
        if (ANativeWindow_setBuffersGeometry(g_window, w, h, HAL_PIXEL_FORMAT_YV12) != 0) {
            goto done;
        }
        g_w = w;
        g_h = h;
    }
    {
        ANativeWindow_Buffer buf;
        if (ANativeWindow_lock(g_window, &buf, nullptr) != 0) goto done;
        if (buf.format != HAL_PIXEL_FORMAT_YV12 || buf.bits == nullptr) {
            ANativeWindow_unlockAndPost(g_window);
            goto done;
        }
        uint8_t* dst = static_cast<uint8_t*>(buf.bits);
        const int dst_y_stride = buf.stride;
        const int dst_uv_stride = Align(buf.stride / 2, 16);
        const int rows = h < buf.height ? h : buf.height;
        const int cols = w < buf.stride ? w : buf.stride;
        for (int r = 0; r < rows; r++) memcpy(dst + r * dst_y_stride, y + r * y_stride, cols);
        const int cw = (cols + 1) / 2, ch = (rows + 1) / 2;
        uint8_t* dst_v = dst + dst_y_stride * buf.height;
        uint8_t* dst_u = dst_v + dst_uv_stride * ((buf.height + 1) / 2);
        for (int r = 0; r < ch; r++) {
            memcpy(dst_v + r * dst_uv_stride, v + r * uv_stride, cw);
            memcpy(dst_u + r * dst_uv_stride, u + r * uv_stride, cw);
        }
        ok = ANativeWindow_unlockAndPost(g_window) == 0;
    }
done:
    pthread_mutex_unlock(&g_lock);
    return ok;
}

Decoder* H(jlong h) { return reinterpret_cast<Decoder*>(h); }

}  // namespace

extern "C" {

#define FN(name) JNICALL Java_com_librestatic_lightforge_core_legacyvideo_ffmpeg_FfmpegNative_##name

JNIEXPORT jlong FN(nativeOpen)(JNIEnv* env, jclass, jint codec, jbyteArray extradata, jint threads) {
    static bool log_set = false;
    if (!log_set) { av_log_set_callback(LogCallback); log_set = true; }
    enum AVCodecID id = CodecIdFor(codec);
    const AVCodec* c = id == AV_CODEC_ID_NONE ? nullptr : avcodec_find_decoder(id);
    if (!c) return 0;
    Decoder* d = new Decoder();
    d->ctx = avcodec_alloc_context3(c);
    d->pkt = av_packet_alloc();
    d->frame = av_frame_alloc();
    if (!d->ctx || !d->pkt || !d->frame) { FreeDecoder(d); return 0; }
    d->ctx->thread_count = threads < 1 ? 1 : threads;
    d->ctx->flags |= AV_CODEC_FLAG_UNALIGNED;  // exact VP6 cropping instead of aligned crop
    d->ctx->pkt_timebase = AVRational{1, 1000000};
    if (extradata) {
        jsize n = env->GetArrayLength(extradata);
        if (n > 0) {
            d->ctx->extradata = static_cast<uint8_t*>(av_mallocz(n + AV_INPUT_BUFFER_PADDING_SIZE));
            if (!d->ctx->extradata) { FreeDecoder(d); return 0; }
            env->GetByteArrayRegion(extradata, 0, n, reinterpret_cast<jbyte*>(d->ctx->extradata));
            d->ctx->extradata_size = n;
        }
    }
    if (avcodec_open2(d->ctx, c, nullptr) < 0) { FreeDecoder(d); return 0; }
    return reinterpret_cast<jlong>(d);
}

JNIEXPORT void FN(nativeClose)(JNIEnv*, jclass, jlong handle) { FreeDecoder(H(handle)); }

static jint Send(Decoder* d, const uint8_t* data, int size, jlong time_us, jboolean key) {
    if (size < 0) return kError;
    if (!data) {  // end of stream: enter draining mode
        return MapStatus(avcodec_send_packet(d->ctx, nullptr));
    }
    av_packet_unref(d->pkt);
    if (av_new_packet(d->pkt, size) < 0) return kError;
    memcpy(d->pkt->data, data, size);
    d->pkt->pts = d->pkt->dts = time_us;
    d->pkt->flags = key ? AV_PKT_FLAG_KEY : 0;
    int ret = avcodec_send_packet(d->ctx, d->pkt);
    av_packet_unref(d->pkt);
    return MapStatus(ret);
}

JNIEXPORT jint FN(nativeSendDirect)(JNIEnv* env, jclass, jlong handle, jobject buf, jint offset,
                                    jint size, jlong time_us, jboolean key) {
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
    if (!base || offset < 0 || size < 0 || env->GetDirectBufferCapacity(buf) < (jlong)offset + size)
        return kError;
    return Send(H(handle), base + offset, size, time_us, key);
}

JNIEXPORT jint FN(nativeSendArray)(JNIEnv* env, jclass, jlong handle, jbyteArray arr, jint offset,
                                   jint size, jlong time_us, jboolean key) {
    if (!arr || offset < 0 || size < 0 || (jlong)offset + size > env->GetArrayLength(arr))
        return kError;
    jbyte* p = env->GetByteArrayElements(arr, nullptr);
    if (!p) return kError;
    jint r = Send(H(handle), reinterpret_cast<uint8_t*>(p) + offset, size, time_us, key);
    env->ReleaseByteArrayElements(arr, p, JNI_ABORT);
    return r;
}

JNIEXPORT jint FN(nativeSendEos)(JNIEnv*, jclass, jlong handle) {
    return Send(H(handle), nullptr, 0, 0, JNI_FALSE);
}

// info = {width, height, ptsUs, hasAlpha}
JNIEXPORT jint FN(nativeReceive)(JNIEnv* env, jclass, jlong handle, jlongArray info) {
    Decoder* d = H(handle);
    av_frame_unref(d->frame);
    d->has_frame = false;
    int ret = avcodec_receive_frame(d->ctx, d->frame);
    if (ret < 0) return MapStatus(ret);
    if (!IsYuv420(d->frame)) { av_frame_unref(d->frame); return kError; }
    d->has_frame = true;
    jlong out[4] = {d->frame->width, d->frame->height,
                    d->frame->pts == AV_NOPTS_VALUE ? 0 : d->frame->pts,
                    d->frame->format == AV_PIX_FMT_YUVA420P ? 1 : 0};
    env->SetLongArrayRegion(info, 0, 4, out);
    return kOk;
}

JNIEXPORT void FN(nativeFlush)(JNIEnv*, jclass, jlong handle) {
    Decoder* d = H(handle);
    avcodec_flush_buffers(d->ctx);
    av_frame_unref(d->frame);
    d->has_frame = false;
}

JNIEXPORT jboolean FN(nativeCopyYuv)(JNIEnv* env, jclass, jlong handle, jobject dst, jint y_stride,
                                     jint uv_stride) {
    Decoder* d = H(handle);
    if (!d->has_frame) return JNI_FALSE;
    const AVFrame* f = d->frame;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    const int w = f->width, h = f->height, cw = (w + 1) / 2, ch = (h + 1) / 2;
    if (!base || y_stride < w || uv_stride < cw) return JNI_FALSE;
    const jlong need = (jlong)y_stride * h + 2LL * uv_stride * ch;
    if (env->GetDirectBufferCapacity(dst) < need) return JNI_FALSE;
    uint8_t* o = base;
    for (int r = 0; r < h; r++) memcpy(o + (size_t)r * y_stride, f->data[0] + (size_t)r * f->linesize[0], w);
    o += (size_t)y_stride * h;
    for (int p = 1; p <= 2; p++) {
        for (int r = 0; r < ch; r++) memcpy(o + (size_t)r * uv_stride, f->data[p] + (size_t)r * f->linesize[p], cw);
        o += (size_t)uv_stride * ch;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean FN(nativeRenderCurrent)(JNIEnv* env, jclass, jlong handle, jobject surface) {
    Decoder* d = H(handle);
    if (!d->has_frame) return JNI_FALSE;
    const AVFrame* f = d->frame;
    return RenderPlanes(env, surface, f->data[0], f->data[1], f->data[2], f->linesize[0],
                        f->linesize[1], f->width, f->height);
}

JNIEXPORT jboolean FN(nativeRenderYuv)(JNIEnv* env, jclass, jobject surface, jobject y, jobject u,
                                       jobject v, jint y_stride, jint uv_stride, jint w, jint h) {
    auto* py = static_cast<uint8_t*>(env->GetDirectBufferAddress(y));
    auto* pu = static_cast<uint8_t*>(env->GetDirectBufferAddress(u));
    auto* pv = static_cast<uint8_t*>(env->GetDirectBufferAddress(v));
    if (!py || !pu || !pv || w <= 0 || h <= 0 || y_stride < w || uv_stride < (w + 1) / 2) return JNI_FALSE;
    const int ch = (h + 1) / 2;
    if (env->GetDirectBufferCapacity(y) < (jlong)y_stride * h ||
        env->GetDirectBufferCapacity(u) < (jlong)uv_stride * ch ||
        env->GetDirectBufferCapacity(v) < (jlong)uv_stride * ch)
        return JNI_FALSE;
    return RenderPlanes(env, surface, py, pu, pv, y_stride, uv_stride, w, h);
}

JNIEXPORT void FN(nativeReleaseSurface)(JNIEnv* env, jclass) {
    pthread_mutex_lock(&g_lock);
    ReleaseSurfaceLocked(env);
    pthread_mutex_unlock(&g_lock);
}

static inline uint8_t Clip(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

JNIEXPORT jboolean FN(nativeCopyBitmap)(JNIEnv* env, jclass, jlong handle, jobject bitmap) {
    Decoder* d = H(handle);
    if (!d->has_frame) return JNI_FALSE;
    const AVFrame* f = d->frame;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || (int)info.width != f->width ||
        (int)info.height != f->height)
        return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels)
        return JNI_FALSE;
    const bool alpha = f->format == AV_PIX_FMT_YUVA420P && f->data[3];
    const bool premul = (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
    for (int r = 0; r < f->height; r++) {
        const uint8_t* yr = f->data[0] + (size_t)r * f->linesize[0];
        const uint8_t* ur = f->data[1] + (size_t)(r / 2) * f->linesize[1];
        const uint8_t* vr = f->data[2] + (size_t)(r / 2) * f->linesize[2];
        const uint8_t* ar = alpha ? f->data[3] + (size_t)r * f->linesize[3] : nullptr;
        uint8_t* out = static_cast<uint8_t*>(pixels) + (size_t)r * info.stride;
        for (int x = 0; x < f->width; x++) {
            int yy = 298 * (yr[x] - 16) + 128, uu = ur[x / 2] - 128, vv = vr[x / 2] - 128;
            int R = Clip((yy + 409 * vv) >> 8);
            int G = Clip((yy - 100 * uu - 208 * vv) >> 8);
            int B = Clip((yy + 516 * uu) >> 8);
            int A = ar ? ar[x] : 255;
            if (premul && A != 255) { R = R * A / 255; G = G * A / 255; B = B * A / 255; }
            out[4 * x] = R; out[4 * x + 1] = G; out[4 * x + 2] = B; out[4 * x + 3] = A;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

}  // extern "C"
