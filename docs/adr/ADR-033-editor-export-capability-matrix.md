# ADR-033: Editor export capability matrix and bounded fallbacks

- **Status:** Accepted and implemented for M5; API 33 emulator exit-gate cohort passes
- **Date:** 2026-08-18
- **Decision owners:** UGallery Android team

## Context

M5 adds native photo/video editing and private sharing for local MediaStore
items. The product must never publish a partial result, lose the source, or
claim a capability that the current device/API/codec path cannot provide.
Photo libraries can contain 200 MP stills and 4K/8K HDR video, while Android
heap and hardware encoder limits vary by API level and device.

## Decision

1. **Edit recipes are non-destructive.** `EditRecipe` stores typed operations
   against `(volumeName, mediaStoreId)` plus the source generation. A source
   generation change invalidates a stale recipe; the original MediaStore item
   is never modified by the default Save action.
2. **Identity copies stream original bytes.** A copy with no transform is
   byte-preserving and is published with `IS_PENDING`, size/SHA validation,
   rollback on cancellation/failure, and only then made visible.
3. **Transformed stills use bounded tiled encoding above the bitmap budget.**
   Preview remains sampled. Export region-decodes 512 px source tiles, applies
   the recipe per tile and streams an 8-bit PNG without allocating a full-size
   source or destination bitmap. The result MIME is propagated to MediaStore.
4. **The 200 MP transformed gate is closed for the validated SDR cohort.** The
   API 33 emulator test exports 20,000×10,000 pixels, validates transformed
   output and completes without a full-size bitmap allocation. PNG output does
   not copy HDR/container metadata; such limitations remain explicit and must
   be repeated on physical devices before claiming device-wide parity.
5. **Video uses Media3 Transformer.** Trim, constant speed, original-audio
   volume, H.264/AAC output, progress and cancellation are supported where the
   capability matrix allows them. Unsupported HDR/codec cases return an
   explicit fallback state and never report silent success.
6. **Local music selection is mixed through Media3 Composition.** A local track
   is an explicit opt-in second audio sequence, looped to the trimmed output
   duration and processed with independent volume. Export fails rather than
   silently dropping the selected track; the API 33 test decodes non-silent
   output after muting original audio.
7. **Private sharing is a derived local copy.** Images are re-encoded to
   remove EXIF/GPS; videos are locally re-encoded. `FileProvider` grants a
   bounded cache URI, originals remain untouched, and expired temporary files
   are removed.

## Consequences

- M5 is usable on ordinary devices without risking OOM or data loss.
- Users can distinguish a completed transform from a bounded-quality fallback.
- Full-resolution 200 MP SDR transformed output and mixed soundtrack export are
  validated for the API 33 emulator cohort; HDR/container/device codec parity
  remains a separate capability matrix item.
- Benchmark and device evidence must be recorded per capability cohort; a
  passing emulator run does not establish a physical-device codec guarantee.

## Alternatives rejected

- Raising `largeHeap` or decoding every source at full resolution: unsafe and
  violates the bounded-memory requirement.
- Publishing before validation: can expose corrupt or partial MediaStore rows.
- Replacing originals by default: violates the Save-copy product rule.
- Claiming music mixing while dropping the selected track: misleading UX.
