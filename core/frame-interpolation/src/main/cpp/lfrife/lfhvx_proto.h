/*
 * SPDX-License-Identifier: MIT
 *
 * Wire protocol shared by the APPS (arm64) side and the DSP skel.
 * Hand-written replacement for what QAIC would generate from an IDL.
 *
 * FastRPC conventions (from quic/fastrpc, BSD-3, inc/remote.h and
 * src/mod_table.c):
 *   - sc (the "scalars" word) encodes method index and the number of in/out
 *     buffers and handles: REMOTE_SCALARS_MAKEX(attr, method, nIn, nOut, nInH, nOutH).
 *   - pra[] holds in-buffers first, then out-buffers, then in/out handles.
 *   - For remote_handle64 interfaces methods 0 and 1 are reserved:
 *       0 = open:  in[0] = int32 len, in[1] = uri string, out handle[0] = h64
 *       1 = close: in handle[0] = h64
 *     User methods start at 2.
 */
#ifndef LFHVX_PROTO_H
#define LFHVX_PROTO_H

#include <stdint.h>

#define LF_SCALARS_MAKEX(attr, method, nin, nout, nhin, nhout) \
    ((((uint32_t)(attr) & 0x7) << 29) | (((uint32_t)(method) & 0x1f) << 24) | \
     (((uint32_t)(nin) & 0xff) << 16) | (((uint32_t)(nout) & 0xff) << 8) | \
     (((uint32_t)(nhin) & 0x0f) << 4) | ((uint32_t)(nhout) & 0x0f))
#define LF_SCALARS_MAKE(method, nin, nout) LF_SCALARS_MAKEX(0, method, nin, nout, 0, 0)
#define LF_SCALARS_METHOD(sc) (((sc) >> 24) & 0x1f)
#define LF_SCALARS_INBUFS(sc) (((sc) >> 16) & 0xff)
#define LF_SCALARS_OUTBUFS(sc) (((sc) >> 8) & 0xff)
#define LF_SCALARS_INHANDLES(sc) (((sc) >> 4) & 0x0f)
#define LF_SCALARS_OUTHANDLES(sc) ((sc) & 0x0f)

enum lfhvx_method {
    LFHVX_M_OPEN = 0,
    LFHVX_M_CLOSE = 1,
    /* add: in[0] = {int32 a, int32 b}; out[0] = {int32 sum} */
    LFHVX_M_ADD = 2,
    /*
     * u8 saturating add of src with itself (dst = sat(src + src)).
     * in[0] = lfhvx_kernel_args, in[1] = src bytes,
     * out[0] = dst bytes, out[1] = lfhvx_kernel_result
     */
    LFHVX_M_U8ADD_HVX = 3,
    LFHVX_M_U8ADD_SCALAR = 4,
    /*
     * int16 dot-product MAC (compute bound): for rep in reps:
     *   acc.w += vdmpy(src.h, src.h):sat over the whole buffer.
     * in[0] = lfhvx_kernel_args, in[1] = src (int16), out[0] = lfhvx_kernel_result
     */
    LFHVX_M_MAC_HVX = 5,
    /* info: out[0] = lfhvx_info */
    LFHVX_M_INFO = 6,
    /*
     * Multithreaded int16 MAC: same buffers as LFHVX_M_MAC_HVX, args.flags =
     * number of qurt threads (1..4). Each thread takes a contiguous chunk,
     * locks its own HVX context and the checksums are summed.
     * result.hvx_mode reports qurt_hvx_get_units() (0 if unavailable).
     */
    LFHVX_M_MAC_MT = 7,
    /*
     * VTCM probe + bench: in[0] = lfhvx_kernel_args (len <= half the VTCM
     * granted, reps), in[1] = src; out[0] = lfhvx_vtcm_info. src is copied to
     * VTCM, then u8 sat-add VTCM->VTCM and the int16 MAC run there.
     */
    LFHVX_M_VTCM = 8,
};

typedef struct {
    uint32_t len;   /* bytes to process (multiple of 128) */
    uint32_t reps;  /* repetitions inside the timed region */
    uint32_t flags; /* reserved */
    uint32_t pad;
} lfhvx_kernel_args;

typedef struct {
    uint64_t pcycles; /* UPCYCLE delta over the timed region */
    uint64_t ticks;   /* UTIMER (19.2 MHz) delta over the timed region */
    int64_t checksum; /* sum used to keep the work observable */
    int32_t hvx_lock; /* qurt_hvx_lock() return value */
    int32_t hvx_mode; /* qurt_hvx_get_mode() after lock */
} lfhvx_kernel_result;

typedef struct {
    uint64_t pcycle;
    uint64_t utimer;
    uint32_t hvx_mode_before;
    int32_t hvx_lock_rc;
    uint32_t hvx_mode_locked;
    uint32_t skel_version;
} lfhvx_info;

typedef struct {
    int32_t has_attr_init;    /* HAP_compute_res_attr_init resolved */
    int32_t has_acquire;      /* HAP_compute_res_acquire resolved */
    int32_t has_request_vtcm; /* HAP_request_VTCM (legacy) resolved */
    int32_t has_hvx_units;    /* qurt_hvx_get_units resolved */
    int32_t hvx_units;        /* qurt_hvx_get_units() raw ((n128 << 8) | n64) */
    uint32_t vtcm_total;      /* HAP_compute_res_query_VTCM total size */
    int32_t query_rc;
    int32_t set_vtcm_rc;      /* attr_set_vtcm_param(_v2) rc */
    uint32_t ctx_id;          /* HAP_compute_res_acquire() context, 0 = failed */
    uint32_t vtcm_ptr;        /* VTCM address granted */
    uint32_t vtcm_size;       /* VTCM bytes granted */
    int32_t release_rc;
    uint32_t req_ptr;         /* HAP_request_VTCM result (0 = failed / not tried) */
    uint32_t bench_len;       /* bytes per kernel pass in the VTCM benches */
    uint32_t bench_reps;
    uint32_t dst_sum;         /* byte sum of the VTCM u8 sat-add output (for checking) */
    uint64_t u8add_pcycles;   /* u8 sat-add, src and dst in VTCM */
    uint64_t mac_pcycles;     /* int16 MAC, src in VTCM */
    int64_t mac_checksum;
    uint64_t ticks_u8add;
    uint64_t ticks_mac;
} lfhvx_vtcm_info;

#endif
