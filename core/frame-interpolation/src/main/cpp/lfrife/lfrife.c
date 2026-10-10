/*
 * SPDX-License-Identifier: MIT
 *
 * arm64 Android side of lfrife: FastRPC glue to the RIFE DSP skel.
 * libcdsprpc.so is loaded with dlopen; its symbols and structs are declared here
 * from the BSD-3 quic/fastrpc sources (inc/remote.h, inc/rpcmem.h).
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "lfrife.h"
#include "lfrife_proto.h"
#include "lfrife_weights.h"

typedef struct {
    void *pv;
    size_t nLen;
} remote_buf;
typedef union {
    remote_buf buf;
    uint32_t h;
    uint64_t h64;
    struct {
        int32_t fd;
        uint32_t offset;
    } dma;
} remote_arg;
struct remote_rpc_control_unsigned_module {
    int domain;
    int enable;
};
struct remote_rpc_control_latency {
    uint32_t enable; /* remote_rpc_latency_flags */
    uint32_t latency;
};

#define DSPRPC_CONTROL_LATENCY 1
#define DSPRPC_CONTROL_UNSIGNED_MODULE 2
#define CDSP_DOMAIN_ID 3
#define RPCMEM_HEAP_ID_SYSTEM 25
#define RPCMEM_DEFAULT_FLAGS 1

typedef int (*pfn_session_control)(uint32_t req, void *data, uint32_t len);
typedef int (*pfn_open)(const char *uri, uint64_t *h);
typedef int (*pfn_invoke)(uint64_t h, uint32_t sc, remote_arg *pra);
typedef int (*pfn_close)(uint64_t h);
typedef int (*pfn_handle_control)(uint64_t h, uint32_t req, void *data, uint32_t len);
typedef void *(*pfn_rpcmem_alloc)(int heapid, uint32_t flags, int size);
typedef void (*pfn_rpcmem_free)(void *p);

struct lfrife {
    void *lib;
    pfn_session_control session_control;
    pfn_open open;
    pfn_invoke invoke;
    pfn_close close;
    pfn_handle_control handle_control;
    pfn_rpcmem_alloc rpcmem_alloc;
    pfn_rpcmem_free rpcmem_free;
    uint64_t h;
    int opened;
    int w, h_px, nthreads;
    int profiling;
    void *in0, *in1, *outb, *tmp;
    size_t in_bytes, out_bytes, tmp_bytes;
    int last_rc;
    lfrife_stats stats;
    lfrife_run_stats *rs;
};

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static void *buf_alloc(lfrife *r, size_t n) {
    void *p = NULL;
    if (r->rpcmem_alloc) p = r->rpcmem_alloc(RPCMEM_HEAP_ID_SYSTEM, RPCMEM_DEFAULT_FLAGS, (int)n);
    if (!p && posix_memalign(&p, 4096, n)) p = NULL;
    return p;
}
static int buf_is_rpcmem(const lfrife *r) { return r->rpcmem_alloc != NULL; }

static void buf_free(lfrife *r, void *p) {
    if (!p) return;
    if (r->rpcmem_alloc && r->rpcmem_free)
        r->rpcmem_free(p);
    else
        free(p);
}

const char *lfrife_strerror(int e) {
    switch (e) {
    case LFRIFE_OK: return "ok";
    case LFRIFE_ERR_ARGS: return "bad argument";
    case LFRIFE_ERR_NO_RPC: return "libcdsprpc.so not available";
    case LFRIFE_ERR_NO_DSP: return "no usable cDSP";
    case LFRIFE_ERR_SKEL: return "DSP could not load liblfrife_skel.so";
    case LFRIFE_ERR_WEIGHTS: return "weights missing, invalid or rejected";
    case LFRIFE_ERR_NOMEM: return "out of memory";
    case LFRIFE_ERR_CALL: return "RPC call failed";
    case LFRIFE_ERR_UNSUPPORTED: return "DSP lacks required HVX features";
    default: return "unknown error";
    }
}

int lfrife_last_rc(const lfrife *h) { return h->last_rc; }

static int call(lfrife *r, uint32_t method, uint32_t nin, uint32_t nout, remote_arg *pra) {
    r->last_rc = r->invoke(r->h, LF_SCALARS_MAKE(method, nin, nout), pra);
    return r->last_rc;
}

void lfrife_close(lfrife *r) {
    if (!r) return;
    if (r->opened && r->close) r->close(r->h);
    buf_free(r, r->in0);
    buf_free(r, r->in1);
    buf_free(r, r->outb);
    buf_free(r, r->tmp);
    free(r->rs);
    if (r->lib) dlclose(r->lib);
    free(r);
}

void lfrife_set_profiling(lfrife *r, int enable) { r->profiling = enable; }

static void *read_file(const char *path, size_t *n) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    void *p = NULL;
    if (sz > 0 && posix_memalign(&p, 4096, (size_t)sz + 4096) == 0) {
        if (fread(p, 1, (size_t)sz, f) != (size_t)sz) {
            free(p);
            p = NULL;
        }
    }
    fclose(f);
    if (p) *n = (size_t)sz;
    return p;
}

int lfrife_open(const lfrife_config *cfg, lfrife **out) {
    *out = NULL;
    if (!cfg || !cfg->skel_dir) return LFRIFE_ERR_ARGS;
    const int want_net = cfg->weights_path || cfg->weights_blob;
    if (want_net && (cfg->width <= 0 || cfg->height <= 0 || cfg->width % 32 || cfg->height % 32))
        return LFRIFE_ERR_ARGS;
    lfrife *r = calloc(1, sizeof(*r));
    if (!r) return LFRIFE_ERR_NOMEM;
    int err = LFRIFE_ERR_NO_RPC;
    char path[1024];
    /* The DSP loader fetches the skel through reverse-RPC file reads on these paths. */
    snprintf(path, sizeof path, "%s;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/vendor/dsp", cfg->skel_dir);
    setenv("ADSP_LIBRARY_PATH", path, 1);
    setenv("DSP_LIBRARY_PATH", path, 1);
    r->lib = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (!r->lib) r->lib = dlopen("/vendor/lib64/libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (!r->lib) goto fail;
    r->session_control = (pfn_session_control)dlsym(r->lib, "remote_session_control");
    r->open = (pfn_open)dlsym(r->lib, "remote_handle64_open");
    r->invoke = (pfn_invoke)dlsym(r->lib, "remote_handle64_invoke");
    r->close = (pfn_close)dlsym(r->lib, "remote_handle64_close");
    r->handle_control = (pfn_handle_control)dlsym(r->lib, "remote_handle64_control");
    r->rpcmem_alloc = (pfn_rpcmem_alloc)dlsym(r->lib, "rpcmem_alloc");
    r->rpcmem_free = (pfn_rpcmem_free)dlsym(r->lib, "rpcmem_free");
    if (!r->session_control || !r->open || !r->invoke || !r->close) goto fail;

    err = LFRIFE_ERR_NO_DSP;
    struct remote_rpc_control_unsigned_module ctl = {.domain = CDSP_DOMAIN_ID, .enable = 1};
    if (r->session_control(DSPRPC_CONTROL_UNSIGNED_MODULE, &ctl, sizeof ctl) != 0) goto fail;
    if (cfg->qos >= 0) {
        struct remote_rpc_control_latency lat = {.enable = (uint32_t)cfg->qos, .latency = 100};
        r->session_control(DSPRPC_CONTROL_LATENCY, &lat, sizeof lat); /* best effort */
    }
    if (getenv("LFRIFE_LOG_WARMUP")) { /* debugging: DSP-side farf logs only start after the PD exists */
        uint64_t hh = 0;
        r->open("file:///nonexistent.so?x&_modver=1.0&_dom=cdsp", &hh);
        struct timespec ts = {0, 800 * 1000000L};
        nanosleep(&ts, NULL);
    }
    char uri[256];
    snprintf(uri, sizeof uri, "file:///%s?%s&_modver=1.0&_dom=cdsp", LFRIFE_SKEL_NAME, LFRIFE_SKEL_SYMBOL);
    int rc = r->open(uri, &r->h);
    if (rc != 0) {
        err = (rc == (int)0x80000406) ? LFRIFE_ERR_SKEL : LFRIFE_ERR_NO_DSP;
        r->last_rc = rc;
        goto fail;
    }
    r->opened = 1;
    r->rs = calloc(1, sizeof(*r->rs));
    if (!r->rs) { err = LFRIFE_ERR_NOMEM; goto fail; }

    if (want_net) {
        const void *blob = cfg->weights_blob;
        size_t bsz = cfg->weights_size;
        void *own = NULL;
        if (!blob) {
            own = read_file(cfg->weights_path, &bsz);
            blob = own;
        }
        if (!blob || bsz < sizeof(lfw_header) || memcmp(blob, LFW_MAGIC, 8) ||
            ((const lfw_header *)blob)->kind != LFW_KIND_HVX) {
            free(own);
            err = LFRIFE_ERR_WEIGHTS;
            goto fail;
        }
        void *wb = buf_alloc(r, bsz);
        if (!wb) { free(own); err = LFRIFE_ERR_NOMEM; goto fail; }
        memcpy(wb, blob, bsz);
        free(own);
        lfrife_setup_args sa = {.version = LFRIFE_PROTO_VERSION,
                                .w = (uint32_t)cfg->width,
                                .h = (uint32_t)cfg->height,
                                .flags = 1,
                                .nthreads = (uint32_t)(cfg->nthreads > 0 ? cfg->nthreads : 4),
                                .power = (uint32_t)(cfg->power_corner > 0 ? cfg->power_corner : 0)};
        lfrife_setup_res res;
        memset(&res, 0, sizeof res);
        remote_arg pra[3];
        pra[0].buf.pv = &sa;
        pra[0].buf.nLen = sizeof sa;
        pra[1].buf.pv = wb;
        pra[1].buf.nLen = bsz;
        pra[2].buf.pv = &res;
        pra[2].buf.nLen = sizeof res;
        rc = call(r, LFRIFE_M_SETUP, 2, 1, pra);
        buf_free(r, wb);
        if (rc != 0 || res.rc != 0) {
            err = (res.rc == -2) ? LFRIFE_ERR_UNSUPPORTED : LFRIFE_ERR_WEIGHTS;
            goto fail;
        }
        r->w = cfg->width;
        r->h_px = cfg->height;
        r->nthreads = res.nthreads ? (int)res.nthreads : (int)sa.nthreads;
        r->stats.power_rc = res.power_rc;
        r->in_bytes = (size_t)r->w * r->h_px * 4;
        r->out_bytes = (size_t)r->w * r->h_px * 5 * sizeof(float);
        r->in0 = buf_alloc(r, r->in_bytes);
        r->in1 = buf_alloc(r, r->in_bytes);
        r->outb = buf_alloc(r, r->out_bytes);
        if (!r->in0 || !r->in1 || !r->outb) { err = LFRIFE_ERR_NOMEM; goto fail; }
    }
    (void)buf_is_rpcmem;
    *out = r;
    return LFRIFE_OK;
fail:
    lfrife_close(r);
    return err;
}

int lfrife_flow(lfrife *r, const uint8_t *rgba0, const uint8_t *rgba1, int stride, int width, int height, float t,
                float *out_flow, float *out_mask) {
    if (!r || !r->in0 || width != r->w || height != r->h_px || stride < width * 4 || !out_flow || !out_mask)
        return LFRIFE_ERR_ARGS;
    const size_t row = (size_t)width * 4;
    for (int y = 0; y < height; y++) {
        memcpy((uint8_t *)r->in0 + y * row, rgba0 + (size_t)y * stride, row);
        memcpy((uint8_t *)r->in1 + y * row, rgba1 + (size_t)y * stride, row);
    }
    lfrife_run_args ra = {.t = t, .w = (uint32_t)width, .h = (uint32_t)height, .stride = (uint32_t)row,
                          .flags = r->profiling ? 1u : 0u};
    memset(r->rs, 0, sizeof *r->rs);
    remote_arg pra[5];
    pra[0].buf.pv = &ra;
    pra[0].buf.nLen = sizeof ra;
    pra[1].buf.pv = r->in0;
    pra[1].buf.nLen = r->in_bytes;
    pra[2].buf.pv = r->in1;
    pra[2].buf.nLen = r->in_bytes;
    pra[3].buf.pv = r->outb;
    pra[3].buf.nLen = r->out_bytes;
    pra[4].buf.pv = r->rs;
    pra[4].buf.nLen = sizeof *r->rs;
    double t0 = now_ms();
    int rc = call(r, LFRIFE_M_RUN, 3, 2, pra);
    r->stats.call_ms = now_ms() - t0;
    if (rc != 0 || r->rs->rc != 0) return LFRIFE_ERR_CALL;
    memcpy(out_flow, r->outb, sizeof(float) * 4 * width * height);
    memcpy(out_mask, (float *)r->outb + (size_t)4 * width * height, sizeof(float) * width * height);
    r->stats.total_ms = (double)r->rs->total_ticks / 19200.0;
    r->stats.nthreads = (int)r->rs->nthreads;
    r->stats.dsp_mhz = r->rs->total_ticks ? (double)r->rs->pcycles * 19.2 / (double)r->rs->total_ticks : 0.0;
    for (int i = 0; i < LFRIFE_NGROUPS; i++) r->stats.group_ms[i] = (double)r->rs->group_ticks[i] / 19200.0;
    return LFRIFE_OK;
}

int lfrife_threads(const lfrife *r) { return r ? r->nthreads : 0; }

int lfrife_get_stats(const lfrife *r, lfrife_stats *st) {
    *st = r->stats;
    return LFRIFE_OK;
}

int lfrife_probe(lfrife *r, uint32_t exp, uint32_t a, uint32_t b, uint32_t c, const void *in, size_t in_len,
                 void *out, size_t out_len) {
    lfrife_probe_args pa = {exp, a, b, c};
    void *ib = buf_alloc(r, in_len ? in_len : 128), *ob = buf_alloc(r, out_len ? out_len : 128);
    if (!ib || !ob) return LFRIFE_ERR_NOMEM;
    if (in_len) memcpy(ib, in, in_len);
    remote_arg pra[3];
    pra[0].buf.pv = &pa;
    pra[0].buf.nLen = sizeof pa;
    pra[1].buf.pv = ib;
    pra[1].buf.nLen = in_len ? in_len : 128;
    pra[2].buf.pv = ob;
    pra[2].buf.nLen = out_len ? out_len : 128;
    int rc = call(r, LFRIFE_M_PROBE, 2, 1, pra);
    if (rc == 0 && out_len) memcpy(out, ob, out_len);
    buf_free(r, ib);
    buf_free(r, ob);
    return rc == 0 ? LFRIFE_OK : LFRIFE_ERR_CALL;
}
