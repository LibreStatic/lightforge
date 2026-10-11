# ADR-045 — Photo colour grade on a GLES 3 pipeline

Status: accepted  
Date: 2026-10-11

## Context

The photo editor used to apply colour through one Android `ColorMatrix`. Compose `ColorFilter`
did the preview, and `ColorMatrixColorFilter` did the export and the tiles. A 4×5 linear matrix
cannot do the following:

- tone curves
- local highlights/shadows
- levels
- HSL colour mix
- vignette
- 3D LUT filters

Google Photos parity needs all of them. minSdk is 30, so AGSL (API 33) is not an option.

## Decision

The decode → geometry → tiled-export skeleton in `PhotoImageRenderer` and
`TiledPngPhotoExporter` stays. Only the colour stage is replaced. It now runs **after** all
geometry and uses one pipeline with two implementations:

- `GpuPhotoGrader`: EGL14 pbuffer context, GLES 3.0, `photo_grade_fragment.glsl`. This is the
  default.
- `CpuPhotoGrader`: pure Kotlin `PhotoGradeMath`. It is the reference implementation, the
  fallback when EGL fails, and what JVM tests and Compose Driver use. GPU output must stay within
  2/255 of it.

Live preview grades the 1600 px geometry preview on the GPU worker. Export grades every tile with
the same parameters. Two things are always expressed in **global frame coordinates**: the local
base and the vignette. Because of that, preview equals export and tiles have no seams.

### Inputs

Pixels are 8-bit premultiplied ARGB. The grader un-premultiplies them, works on `p` (sRGB-encoded
0..1), and re-premultiplies with the source alpha at the end. `lin(p)` and `enc(x)` are the exact
sRGB EOTF and OETF. Luma is `Y(x) = dot(x, (0.2126, 0.7152, 0.0722))` on linear values. Adjust
values are in −1..1. "Ratio apply" means `x *= Y' / max(Y, 1e-5)`, which keeps hue and
saturation.

### Local base `B`

1. Take the geometry-applied frame and downscale it so the long side is ≤ 256 px.
2. Compute linear luma.
3. Apply a separable Gaussian with σ = 4 px at that scale.
4. Sample it bilinearly with the global frame UV.

The CPU computes it once per frame or preview and uploads it as `R16F`. The preview builds it from
the preview bitmap. The export builds it from an `inSampleSize` decode of the full frame. It is
only needed when highlights, shadows or tone are non-zero.

### Pipeline (in order)

0. **Legacy grade.** Applies only to recipes with the old `tone`/`filter` tokens. Those
   matrices, clamped the way `ColorMatrixColorFilter` clamps, are baked into a 33³ LUT and applied
   to `p` first. Old recipes keep their exact look.
1. `x = lin(p)`.
2. **Warmth `w`, tint `t`.**
   - `x.r *= 1 + 0.10w`
   - `x.b *= 1 − 0.10w`
   - `x.g *= 1 − 0.05t` (positive tint = magenta)
3. **Brightness `b`.** Ratio apply with `Y' = Y^(2^(−0.8b))`. 1.0 stays fixed, so highlights are
   protected.
4. **Black point `k`, white point `q`.** Per channel:
   - Black point:
     - `k > 0`: `x = max(x − 0.05k, 0) / (1 − 0.05k)`
     - `k < 0`: `x = x(1 − f) + f`, with `f = 0.03|k|`
   - White point:
     - `q > 0`: `x /= 1 − 0.35q`
     - `q < 0`: `x *= 1 − 0.30|q|`
5. **Highlights `h`, shadows `s`.**
   - `Bp = enc(B)`
   - Shadow weight: `ws = 1 − smoothstep(0, 0.5, Bp)`
   - Highlight weight: `wh = smoothstep(0.5, 1, Bp)`
   - `x *= 2^(1.2·s·ws + 1.0·h·wh)`
6. **Tone `o`** (local compression).
   - Detail: `d = Y / max(B, 1e-4)`
   - Compressed base: `Bc = 2^((1 − 0.5o)·log2 B + 0.5o·log2 0.18)`
   - Ratio apply with `Y' = Bc · d`.
7. **Contrast `c`.** Applied to `u = enc(Y)`.
   - `c > 0`: `u' = mix(u, S(u), c)`, with `S(u) = u²(3 − 2u)`
   - `c < 0`: `u' = mix(u, S⁻¹(u), |c|)`, with `S⁻¹(u) = 0.5 − sin(asin(1 − 2u) / 3)`
   - Ratio apply with `Y' = lin(u')`.

   Every branch is monotone.
8. Clamp `x` to 0..1, then `p = enc(x)`. From here on the work is on encoded values.
9. **Saturation `a`.** `p = mix(Yp, p, 1 + a)`, where `Yp` is the luma of `p`. Values are then
   converted to HSV (hue in degrees). Every hue-band weight below is multiplied by
   `chroma = smoothstep(0.02, 0.12, sat)`. Hue is unstable near grey, so neutrals stay untouched.
   - **Skin tone `k`.** Weight `w = smoothstep(25, 0, dist(hue, 35))`. Then `hue += 8k·w` and
     `sat *= 1 + 0.25k·w`.
   - **Blue tone `u`.** Weight `w = smoothstep(35, 0, dist(hue, 220))`. Then `sat *= 1 + 0.6u·w`
     and `val *= 1 − 0.15u·w`.
   - **Colour mix.** Eight bands with centres every 45°, starting at red = 0°. Weight
     `w = max(0, 1 − dist / 45)`. Each band adds `w·(h, s, l)` to a `delta`. Then:
     - `hue += 30·delta.h`
     - `sat = clamp(sat·(1 + delta.s))`
     - `val *= 2^(0.75·delta.l)`
10. **Look LUT** (33³, `sampler3D` RGBA16F, trilinear) at intensity `i`:
    - `p = mix(p, LUT(p), i)`
    - Texture coordinate: `p·32/33 + 0.5/33`
11. **Vignette `v`.**
    - `uv` is the global frame UV. `r = length((uv − 0.5)·(aspect, 1)) / length((0.5·aspect, 0.5))`.
    - `w = smoothstep(0.35, 1, r)`.
    - `v > 0`: `p *= 1 − 0.6v·w`
    - `v < 0`: `p += (1 − p)·0.5|v|·w`
12. **Output.** Clamp, then add a deterministic dither, then round to 8 bits:
    - Dither: `(hash(gx, gy) / 2³² − 0.5) / 255`, where `gx, gy` are global pixel coordinates.
    - Hash, in uint32 arithmetic and identical on CPU and GPU:
      ```
      h = x·0x8da6b343 ^ y·0xd8163841
      h ^= h >> 16
      h *= 0x7feb352d
      h ^= h >> 15
      h *= 0x846ca68b
      h ^= h >> 16
      ```

`dist` is circular hue distance. `smoothstep(e0, e1, x)` with `e0 > e1` is a falling ramp.

### LUT filters

- **Built-in looks.** `PhotoLutBaker` bakes them from Kotlin preset definitions (curves,
  split-tone, saturation). There are no binary assets, and the names are our own.
- **"My filters".** Reinhard statistics transfer in CIELAB. The source is a fixed neutral prior,
  not the current photo, so the LUT does not depend on the photo, and preview equals export.
  - Reference: the reference photo's mean and standard deviation per Lab channel.
  - Neutral prior: L μ50 σ20; a, b μ0 σ12.
  - The scale is clamped to 0.5–2, the chroma shift to ±20 and the L mean to 50 ± 15.
  - Storage: `filesDir/photo-filters/<uuid>.lut` (binary, float16) plus `<uuid>.json`.
  - A missing file renders without the look and shows a warning.
- **Backups.** Custom looks are dropped from portable backups.

## Consequences

- A new colour control needs code in three places: the shader, `PhotoGradeMath` and the GPU/CPU
  parity test.
- GLES work runs on one dedicated thread. The context is released when the editor closes, after
  being idle, or on `onTrimMemory`.
- Perspective is geometry. `Matrix.setPolyToPoly` maps the source quad onto the full output rect,
  so no empty corners appear and no automatic crop is needed.
