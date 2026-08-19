# ADR-035: Collage, GIF, and Slideshow local-only exporters

Date: 2026-08-19

## Status

Proposed

## Context

M6-T07 requires implementing collage/GIF/slideshow capabilities for the gallery app.
All features must be local-only with no cloud or network dependency.

Key constraints:
- Output must be validated and memory-bounded
- No cloud or network
- Templates for collage
- GIF encoding
- Slideshow export as MP4

## Decision

### Collage

Use Android Canvas with Bitmap to render collage templates locally.
Six templates are provided: GRID_2, GRID_3, GRID_4, STACK_3, STRIP_3, POLAROID_3.
Source bitmaps are sampled to fit the slot, not loaded at full resolution.
Output is a single bitmap of the configured size.

### GIF Encoder

Implement a minimal GIF89a writer with:
- Uniform 6x7x6 = 252-color palette quantization per frame
- LZW compression with standard variable-width codes
- Netscape 2.0 looping extension
- Graphic control extension with per-frame delay
No external library dependency; pure Kotlin/Android.

### Slideshow Exporter

Use MediaCodec with COLOR_FormatSurface for hardware-accelerated H.264 encoding.
MediaMuxer writes the MP4 container.
Source images are loaded via NativeImageDecoder.screenPreview() which samples
before decode -- never materializes the full bitmap.
Cancellation is cooperative via CancellationSignal.

## Consequences

- All three features work fully offline with no network permission.
- Memory is bounded: collage/GIF use fixed-size bitmaps; slideshow samples
  each image to output resolution.
- GIF quality is limited to 256 colors per frame (uniform quantization).
  This is an explicit limitation, not a silent degradation.
- Slideshow uses hardware H.264 encoder; fallback for devices without
  AVC encoder is not yet implemented (future ADR if needed).
