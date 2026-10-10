/*
 * SPDX-License-Identifier: MIT
 *
 * On-disk weight blob format for the RIFE v4.6 IFNet port (written by
 * tools/export_weights.py, read by the host/arm64 reference, the apps-side
 * loader and the DSP skel).
 *
 * Two flavours with the same header and layer table:
 *   kind 0 (LFW_KIND_F32): torch layouts, fp32.
 *       conv:   w[co][ci][ky][kx], b[co]
 *       deconv: w[ci][co][ky][kx] (ConvTranspose2d, 4x4 stride 2 pad 1), b[co]
 *       ResConv beta is already folded into w and b.
 *   kind 1 (LFW_KIND_HVX): fp16 weights pre-arranged for the HVX kernels.
 *       conv:   w[ob][tap 0..8][ci][64 lanes] (lane = co % 64, tap = dy*3+dx),
 *               b = fp32 [ob][2][32]: [0][i] = bias[ob*64+2i], [1][i] = bias[ob*64+2i+1]
 *       deconv: w[pu 0..1][tap 0..5][ci][64 lanes], tap = dyi*3+(dx+1)
 *               (dy = dyi-1+pu), lane = ((pv*2+i)*2+j)*5+ch, ch 0..3 flow, 4 mask;
 *               b = fp32 [2][32] as above
 *       Offsets are 128-byte aligned.
 *
 * All multi-byte values are little endian.
 */
#ifndef LFRIFE_WEIGHTS_H
#define LFRIFE_WEIGHTS_H

#include <stdint.h>

#define LFW_MAGIC "LFRIFE46"
#define LFW_VERSION 1
#define LFW_KIND_F32 0
#define LFW_KIND_HVX 1

#define LFW_NBLOCKS 4
#define LFW_LAYERS_PER_BLOCK 11 /* conv0, conv1, 8 x ResConv, deconv */
#define LFW_NLAYERS (LFW_NBLOCKS * LFW_LAYERS_PER_BLOCK)

enum {
    LFW_CONV_S2 = 0,  /* 3x3 stride 2 pad 1 + LeakyReLU(0.2) */
    LFW_RESCONV = 1,  /* 3x3 stride 1 pad 1, y = LeakyReLU(0.2)(conv(x) + x) */
    LFW_DECONV = 2,   /* ConvTranspose 4x4 s2 p1, 24 outputs, then PixelShuffle(2) */
};

typedef struct {
    uint32_t type;
    uint32_t cin;
    uint32_t cout;
    uint32_t stride;
    uint64_t w_off; /* byte offsets from the start of the blob */
    uint64_t w_bytes;
    uint64_t b_off;
    uint64_t b_bytes;
} lfw_layer;

typedef struct {
    char magic[8];
    uint32_t version;
    uint32_t kind;
    uint32_t nlayers;
    uint32_t reserved0;
    uint64_t total_bytes;
    uint32_t block_cin[LFW_NBLOCKS];   /* 7, 12, 12, 12 */
    uint32_t block_c[LFW_NBLOCKS];     /* 192, 128, 96, 64 */
    uint32_t block_scale[LFW_NBLOCKS]; /* 8, 4, 2, 1 */
    uint32_t pad[4];
    /* lfw_layer layers[nlayers] follows immediately; data after, 128-byte aligned */
} lfw_header;

#endif
