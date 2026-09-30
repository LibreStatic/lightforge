# ADR-015: HDR and codec fallback

- Status: Accepted
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Probe decoder/encoder capabilities per device, prefer passthrough, validate outputs, preserve HDR only end-to-end, and present explicit SDR/unsupported fallbacks without claiming success early.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.

## M2 viewer calibration

On reference tablet/API 36, both platform region-decode paths transiently allocate a full-frame scratch
buffer for the 200 MP JPEG fixture (about 619–621 MB PSS), despite a 1024 px output tile. The
sampled static preview remains bounded at about 33 MB PSS. For this physically verified device/API
family and images of at least 150 MP, deep zoom is therefore reported as unavailable with
`DeviceDecoderMemoryRisk`; the bounded preview remains available. This is an explicit capability
fallback, not a successful tile-decode claim. reference phone evidence remains capable of tiled decode.

Animated GIF/WebP use `ImageDecoder`; static previews sample with `BitmapFactory` before decode and
apply EXIF orientation at preview size. H.264 and HEVC playback use one Media3 player owner, and
unsupported decoder errors remain explicit states.
