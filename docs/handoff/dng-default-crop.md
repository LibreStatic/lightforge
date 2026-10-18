# Handoff: honour the DNG default crop (camera framing) for RAW photos

Audience: an implementing model or engineer with no prior context. The decisions in §2 were made with the product owner. Do not re-litigate them. If one turns out to be impossible, stop and report.

## 1. Goal and context

OpenCineCam (a sibling LibreStatic camera app) now records the user's digital zoom in its DNGs as `DefaultCropOrigin` (tag 0xC61F) and `DefaultCropSize` (0xC620). The files keep the full sensor data, and the tags say which part of it the photographer framed. Lightroom/ACR, darktable and RawTherapee show the cropped frame. Lightforge currently shows the full sensor, so a 4.6× zoomed shot opens as the wide 1× frame.

Every DNG carries a DefaultCrop, not only zoomed ones; Android's `DngCreator` writes an 8 px margin, for example. So this changes the frame of all DNGs slightly, and zoomed OpenCineCam shots substantially.

Feature: honour the camera framing by default everywhere a RAW is shown or developed. Let the user turn it off per photo in the editor ("Camera framing" / "Full sensor") to recover the whole sensor area, the one thing a non-destructive crop allows.

## 2. Fixed decisions

1. **Apply it natively, at open time.** All JNI entry points (`nativeInspect`, `nativeRender`, `nativeOpenPreview`, `nativeExportTiff`) open files through the single helper `open()` in `core/raw/src/main/cpp/raw_jni.cpp:28`. After `open_file` succeeds, call LibRaw's public `raw.adjust_to_raw_inset_crop(1, 0.0f)` when camera framing is on.
   - Mask `1` means DefaultCrop only, `raw_inset_crops[0]`. Do not use mask 3: index 1 is the DNG *DefaultUserCrop* / aspect crop, which is out of scope.
   - The call (bundled LibRaw 0.22, `src/utils/utils_libraw.cpp` `adjust_to_raw_inset_crop`) rewrites `sizes.left_margin/top_margin/width/height` before unpack. `half_size`, `boxDownscale`, the flip and the TIFF writer then all operate on the cropped frame, so no extra coordinate mapping is needed.
   - DNG promotion into `raw_inset_crops[0]` happens in `src/metadata/identify.cpp` around lines 1435–1460, in full-raw coordinates including margins.
   - Verify the call is a no-op when the file has no DefaultCrop (`cleft == 0xffff`).
2. **Default on; per-photo toggle in the editor**, persisted in the edit recipe. A recipe without the field means **on**.
   - Existing saved RAW recipes therefore shift by the camera margin, a few pixels (<0.5%), for normal DNGs. That is accepted, so no migration is needed.
   - For the rare already-edited zoomed OpenCineCam DNG, the shift is large. Also accepted: the format was introduced on 2026-10-11.
3. **Metadata**: `nativeInspect` reports the framed dimensions plus the full-sensor dimensions and the crop rect, so the editor can show "4000×3000 → 873×654" and the toggle knows whether a meaningful crop exists. Show the toggle only when the crop removes more than 1% on either axis; plain margins are not worth a control.
   - Extend the `|`-separated format at the end, and update `parseRawMetadata` in `RawDeveloper.kt` (~:239) and `RawMetadata`.
4. **Grid thumbnails and the full-screen viewer must match the developed frame.** Do the check first, before deciding on the work.
   - Grid thumbnails come from `ContentResolver.loadThumbnail` (MediaStore), in `core/thumbnail/.../NativeImageDecoder.kt:47`.
   - The viewer goes through `PhotoViewerPipeline.kt:50` → `NativeImageDecoder.screenDrawable`/`screenPreview`, which uses BitmapFactory. The scoper doubted it decodes DNG at all, so find the real RAW fallback.
   - If either one shows the uncropped frame, crop it with the normalized crop rect from `nativeInspect` (cache it with the media metadata; do not inspect per frame while scrolling).
   - If MediaStore's thumbnail already honours DefaultCrop, leave it alone, and document which.

## 3. Work, in order

1. **Native + metadata** (`raw_jni.cpp`, `RawDeveloper.kt`, `RawModels.kt`):
   - Thread a `cameraFraming: Boolean` through every native entry point. Settings already travel as a `FloatArray` (`RawDevelopmentSettings.nativeValues()` ~:280), so appending a value there is the least invasive option. `nativeInspect` and `nativeOpenPreview` take no settings: add a parameter.
   - Note that the preview session caches the decoded base (`PreviewHandle`, raw_jni.cpp ~:149–264), so toggling must reopen or invalidate it.
2. **Recipe + editor**:
   - Add the field to the RAW development settings / recipe in `core/model/.../EditModels.kt`, with serialization and parsing; absent means on. Add `EditModelsTest`/`PortablePhotoRecipeTest` cases: round-trip, and an old recipe without the field parses as on.
   - Add the toggle in `feature/photoeditor`, near the RAW info that already shows `rawMetadata` dims (`PhotoEditorContent.kt`).
   - Wire it through `GalleryViewModel.kt`: the RAW preview session (~:3699), `renderPhotoEditorPreview` (~:4524) and export (~:3945).
   - User geometry ops (`EditOperation.Crop` in permille, rotate, perspective) stay relative to the developed image, so they apply to the framed image. Toggling framing keeps them as they are; that is acceptable.
   - Strings in English, localized for es/fr/pt/it and the other existing locales (repo rule).
3. **Thumbnails / viewer**: per §2.4.
4. **Compose Driver**: render the editor with the toggle (add or extend a `*DriverPreviews.kt` in `src/debug`), as `AGENTS.md` requires.

## 4. Watch out

- **Uncommitted work in this tree.** At handoff time `main` had uncommitted changes in exactly the files this task touches: `GalleryViewModel.kt`, `PhotoImageRenderer.kt`, `EditModels.kt` and its tests, `TiledPngPhotoExporter.kt`, and others. Do not overwrite or commit them. Ask the owner whether to wait for them to land, or work in a worktree branched from `main` and rebase later.
- Recent commits 42a71e1d (live RAW slider preview) and 89092545 (pinch zoom) touched the same preview path.
- LibRaw's promotion only accepts the crop when `lm + ww < raw_width + left_margin`. That is a strict `<`, so a crop flush with the right edge is ignored. OpenCineCam's crops are always inside the 8 px margin, so this is fine; do not "fix" LibRaw.
- `adjust_to_raw_inset_crop(mask, maxcrop)` rejects crops smaller than `maxcrop × width`. Pass `0.0f` so heavy zooms (4–5×, i.e. 20% of the width) are accepted.
- Bayer phase: LibRaw handles odd origins when it sets `left_margin`. Verify on a crop with odd `cleft`/`ctop` that colours are not swapped (red/blue or green casts).

## 5. Validation

- **Unit tests:** recipe parsing and serialization; metadata parsing of the new fields.
- **Native/device test:** there is no DNG fixture in `testdata/`. Add a tiny synthetic one, a few KB: an uncompressed CFA DNG of e.g. 64×48 with a DefaultCrop that is clearly off-centre, and also a variant with an odd origin. Generate it with a committed script, not a binary from nowhere. Assert in an androidTest (`core/raw/src/androidTest`, new) that:
  - inspect reports the framed dims;
  - render with framing on gives the framed size and content;
  - framing off gives the full size.
- **Real files:** four OpenCineCam DNGs from a Razr Fold are in `~/.cache/claude-tmp/libremagic/rawzoom/` (not in the repo). They were shot at 1.0×, 1.6×, 2.0× and 4.6×, with DefaultCrop 3984×2984+8+8, 2416×1809+792+595, 1968×1474+1016+763 and 873×654+1564+1173, all with Orientation 6. `j20.jpg` is the camera's JPEG at 2.0×, the reference framing for `z20.dng`. Push them to a device and check grid, viewer, editor preview (toggle both ways), JPEG export and TIFF export. Exported dimensions must equal the crop size, rotated by the orientation.
- **Physical Razr:** it may be shared with an OpenCineCam session. Ask the owner before using it.
- **Test commands:** follow `README.md` / `docs/`. Run one Gradle module per invocation, and do not run Gradle builds concurrently on this host.
