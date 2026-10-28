# ADR-039: Object eraser with local inpainting fallback

Date: 2026-08-19

## Status

Proposed, amended 2026-10-28

## Context

M6-T05 requires a local object eraser that can remove unwanted objects from photos.
This depends on M6-T04 (subject clipping) for mask generation.
The feature must be offline, original-preserving, and must not promise quality
until a proper inpainting model is integrated.

## Decision

Implement a two-tier approach:
1. Primary: ML-based inpainting (when a model is bundled) - not yet implemented
2. Fallback: Neighbor interpolation inpainting - bilinear interpolation from
   border pixels of the erased region

The fallback is explicitly labeled as NEIGHBOR_INTERPOLATION_FALLBACK and is not
presented as high-quality ML inpainting. It is a functional degradation.

The original bitmap is never modified; a copy is always returned.

## Consequences

- Without an ML model, object erasing works but with visible artifacts
- The fallback is suitable for small regions on uniform backgrounds
- The ML path requires bundling an inpainting model (future work)
- No cloud or network dependency in either path

## Amendment (2026-10-28)

The ML path no longer needs a bundled model. Models are downloaded on the user's request, under the shared model-download gate (`InpaintModelDownloadWorker`, `InpaintModelStore`, `InpaintModelCatalog` in `feature/objecteraser`):

- MI-GAN 512 (about 14 MB): fetched through `PinnedModelDownload` and pinned by size and SHA-256.
- The generative Moebius package: wired in `InpaintModelCatalog`, but its release assets are not published yet (`TODO(moebius-release)`).

Without a model, `ObjectEraser` still uses `NEIGHBOR_INTERPOLATION_FALLBACK`.

"The ML path requires bundling an inpainting model" and "No cloud or network dependency" no longer apply. Inference stays on the device; only the model download uses the network.
