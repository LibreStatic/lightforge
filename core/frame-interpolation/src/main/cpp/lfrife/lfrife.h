/*
 * SPDX-License-Identifier: MIT
 *
 * lfrife: RIFE v4.6 optical-flow network on the Qualcomm Hexagon cDSP (HVX).
 *
 * Small C library for arm64 Android (NDK). It dlopen()s libcdsprpc.so, so it
 * links with nothing but libdl and fails gracefully (error code, no crash) on
 * devices without a usable cDSP, so the caller can fall back to another path.
 *
 * Thread safety: one lfrife handle must not be used from two threads at once.
 */
#ifndef LFRIFE_H
#define LFRIFE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LFRIFE_API __attribute__((visibility("default")))

enum {
    LFRIFE_OK = 0,
    LFRIFE_ERR_ARGS = -1,       /* bad argument / unsupported size (w, h must be multiples of 32) */
    LFRIFE_ERR_NO_RPC = -2,     /* libcdsprpc.so (or one of its symbols) is missing */
    LFRIFE_ERR_NO_DSP = -3,     /* no cDSP / unsigned PD not available / DSP-side open failed */
    LFRIFE_ERR_SKEL = -4,       /* liblfrife_skel.so could not be loaded by the DSP */
    LFRIFE_ERR_WEIGHTS = -5,    /* weights file missing/invalid or rejected by the DSP */
    LFRIFE_ERR_NOMEM = -6,      /* rpcmem / heap allocation failed */
    LFRIFE_ERR_CALL = -7,       /* an RPC call failed (DSP crashed/restarted: close and reopen) */
    LFRIFE_ERR_UNSUPPORTED = -8 /* the DSP lacks HVX IEEE fp16 or enough HVX contexts */
};

typedef struct lfrife lfrife;

typedef struct {
    /* Directory that contains liblfrife_skel.so (on Android: the app's nativeLibraryDir). Required. */
    const char *skel_dir;
    /* rife46_hvx.bin: either a path ... */
    const char *weights_path;
    /* ... or an in-memory copy (takes precedence when non-NULL). */
    const void *weights_blob;
    size_t weights_size;
    /* Network size. Multiple of 32. */
    int width, height;
    /* HVX worker threads (0 = default 4, max 6). */
    int nthreads;
    /* remote_rpc_latency_flags-style hint: -1 default, 0 off, 1 PM QoS, 3 poll. */
    int qos;
    /* DCVS voltage corner to pin while the session is open (0 = let DCVS decide). See docs/rife.md. */
    int power_corner;
} lfrife_config;

typedef struct {
    double total_ms;     /* DSP-side time of the last lfrife_flow call (UTIMER) */
    double call_ms;      /* wall time of the whole RPC incl. marshalling */
    double group_ms[24]; /* per stage group, DSP-side (valid when profiling is on) */
    int nthreads;        /* threads that took part in the last call */
    double dsp_mhz;      /* average DSP core clock during the last call */
    int power_rc;        /* HAP_power_set result of the corner vote at open (0 ok, -1 unavailable) */
} lfrife_stats;

/* Open the DSP session, load the skel and the weights. Returns LFRIFE_OK or a negative LFRIFE_ERR_*. */
LFRIFE_API int lfrife_open(const lfrife_config *cfg, lfrife **out);

/*
 * Compute flow and mask for two 8-bit RGBA frames of exactly width x height
 * (the size given to lfrife_open). t in [0,1].
 * out_flow: 4*h*w floats, planar [0:2] towards img0 / [2:4] towards img1, px at the input resolution.
 * out_mask: h*w floats after sigmoid.
 */
LFRIFE_API int lfrife_flow(lfrife *h, const uint8_t *rgba0, const uint8_t *rgba1, int stride_bytes, int width,
                           int height, float t, float *out_flow, float *out_mask);

/* Threads the DSP uses (clamped at open to the HVX contexts it really has); 0 before a network is set up. */
LFRIFE_API int lfrife_threads(const lfrife *h);
LFRIFE_API int lfrife_get_stats(const lfrife *h, lfrife_stats *st);
LFRIFE_API void lfrife_set_profiling(lfrife *h, int enable);
LFRIFE_API void lfrife_close(lfrife *h);
LFRIFE_API const char *lfrife_strerror(int err);

/* Diagnostics (used by the bench and development probes). */
LFRIFE_API int lfrife_probe(lfrife *h, uint32_t exp, uint32_t a, uint32_t b, uint32_t c, const void *in,
                            size_t in_len, void *out, size_t out_len);
LFRIFE_API int lfrife_last_rc(const lfrife *h); /* raw rc of the last RPC (hex AEE code) */

#ifdef __cplusplus
}
#endif
#endif
