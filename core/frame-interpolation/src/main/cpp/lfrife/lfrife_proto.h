/*
 * SPDX-License-Identifier: MIT
 *
 * Wire protocol between the arm64 side and the RIFE DSP skel (liblfrife_skel.so).
 * Same FastRPC conventions as inc/lfhvx_proto.h.
 */
#ifndef LFRIFE_PROTO_H
#define LFRIFE_PROTO_H

#include <stdint.h>

#include "lfhvx_proto.h" /* LF_SCALARS_* */

#define LFRIFE_SKEL_NAME "liblfrife_skel.so"
#define LFRIFE_SKEL_SYMBOL "lfrife_skel_handle_invoke"
#define LFRIFE_PROTO_VERSION 2

enum lfrife_method {
    LFRIFE_M_OPEN = 0,
    LFRIFE_M_CLOSE = 1,
    /* PROBE: in[0] = lfrife_probe_args, in[1] = bytes, out[0] = bytes (experiment-specific) */
    LFRIFE_M_PROBE = 2,
    /*
     * SETUP: in[0] = lfrife_setup_args, in[1] = rife46_hvx.bin, out[0] = lfrife_setup_res.
     * Copies the weights into DSP-side memory and sizes the scratch for w x h. May be
     * called again with another size; weights are only re-copied if flags & 1.
     */
    LFRIFE_M_SETUP = 3,
    /*
     * RUN: in[0] = lfrife_run_args, in[1] = rgba0, in[2] = rgba1,
     *      out[0] = flow (4*h*w f32, planar) followed by mask (h*w f32),
     *      out[1] = lfrife_run_stats.
     */
    LFRIFE_M_RUN = 4,
};

typedef struct {
    uint32_t exp;
    uint32_t a, b, c;
} lfrife_probe_args;

typedef struct {
    uint32_t version;
    uint32_t w, h;
    uint32_t flags;
    uint32_t nthreads;
    uint32_t power; /* 0: leave the clock to DCVS; otherwise a DCVS voltage corner to pin while the session lives */
    uint32_t pad[2];
} lfrife_setup_args;

typedef struct {
    int32_t rc;
    uint32_t hvx_units;
    uint32_t vtcm_size;
    uint32_t scratch_bytes;
    uint32_t nthreads; /* threads the DSP will really use: min(requested, LFP_MAX, HVX contexts, contexts that locked) */
    int32_t power_rc;  /* HAP_power_set result for the requested corner; -1 when the symbol is missing */
    uint32_t pad[2];
} lfrife_setup_res;

typedef struct {
    float t;
    uint32_t w, h;
    uint32_t stride; /* bytes per rgba row */
    uint32_t flags;  /* bit0: collect per-group cycle counters */
    uint32_t pad[3];
} lfrife_run_args;

#define LFRIFE_NGROUPS 24
typedef struct {
    int32_t rc;
    uint32_t nthreads;
    uint64_t total_ticks;               /* UTIMER (19.2 MHz) over the whole run */
    uint64_t group_ticks[LFRIFE_NGROUPS]; /* per stage group, UTIMER ticks (see lfrife_group_names) */
    uint64_t pcycles;                   /* processor cycles over the same span: clock = pcycles / total_ticks * 19.2 MHz */
    uint64_t pad[3];
} lfrife_run_stats;

#endif
