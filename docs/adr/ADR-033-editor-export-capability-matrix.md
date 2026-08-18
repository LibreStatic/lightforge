# ADR-033: Editor export capability matrix and bounded fallbacks

- **Status:** Accepted for M5 implementation; two exit-gate items remain open
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
3. **Transformed stills use a bounded renderer today.** Preview and export
   sample large inputs. When a transformed source exceeds the configured
   bitmap budget, the renderer downscales and returns a user-visible warning.
   This is an explicit safe fallback, not a full-resolution claim.
4. **The 200 MP transformed full-resolution gate stays open.** We will not
   silently raise heap limits or decode a 200 MP bitmap. Closing this gate
   requires a separate tiled/native encoder spike with peak-memory, metadata,
   cancellation, and process-death evidence on the reference phone and tablet.
5. **Video uses Media3 Transformer.** Trim, constant speed, original-audio
   volume, H.264/AAC output, progress and cancellation are supported where the
   capability matrix allows them. Unsupported HDR/codec cases return an
   explicit fallback state and never report silent success.
6. **Local music selection is opt-in and honest.** The editor can select a
   local track and persist that choice, but until a Media3 audio-mixer path is
   validated, export preserves the original audio and displays a warning.
7. **Private sharing is a derived local copy.** Images are re-encoded to
   remove EXIF/GPS; videos are locally re-encoded. `FileProvider` grants a
   bounded cache URI, originals remain untouched, and expired temporary files
   are removed.

## Consequences

- M5 is usable on ordinary devices without risking OOM or data loss.
- Users can distinguish a completed transform from a bounded-quality fallback.
- Full-resolution 200 MP transformed output and mixed soundtrack export are
  explicit follow-up gates rather than hidden correctness defects.
- Benchmark and device evidence must be recorded per capability cohort; a
  passing emulator run does not establish a physical-device codec guarantee.

## Alternatives rejected

- Raising `largeHeap` or decoding every source at full resolution: unsafe and
  violates the bounded-memory requirement.
- Publishing before validation: can expose corrupt or partial MediaStore rows.
- Replacing originals by default: violates the Save-copy product rule.
- Claiming music mixing while dropping the selected track: misleading UX.
