# Simple Gallery compatible-parity ledger

Reference: `SimpleMobileTools/Simple-Gallery` at `a356401`. This is a behavioral,
clean-room inventory; no GPL source code, resources, names, or visual assets are copied.

| Capability group | Lightforge implementation | Status |
|---|---|---|
| Timeline, albums, favorites, search, details, sharing, trash | MediaStore + Room/AppSearch production paths | Existing |
| GIF/WebP, RAW, large images, video, motion photos | Native decoder and Media3 pipelines with explicit fallbacks | Existing |
| Crop, rotate, flip, tone, filters, video trim/speed/audio | Non-destructive editors and verified pending publication | Existing |
| Collage, GIF creation, widget, private album | Isolated local feature modules | Existing |
| Slideshow | Not implemented | Missing |
| Autoplay, mute, looping, brightness and remembered-position policy | Typed settings plus Room-backed per-video resume checkpoints | Implemented |
| Photo/video pinch zoom, focal double-tap, side skip, brightness, volume, swipe-down | Unified configurable viewer gesture policy | Implemented |
| Thumbnail crop/animation/badges/density | Typed defaults wired into the production timeline | Implemented |
| App/destructive locks | BiometricPrompt with strong biometric or device credential | Implemented |
| Settings backup/restore | Versioned Lightforge JSON through SAF, with legacy settings-only import | Implemented |
| Favorites backup/restore | Fingerprinted media records resolved against the current index, restored through chunked platform approval | Implemented |
| Sort/filter/group and included/excluded folders | Persistent bound Room paging queries over MediaStore volume/bucket identities, shared by timeline, viewer navigation and select-all | Implemented |
| Rename/copy/move/open-with/set-as/print/date repair | Write/delete requests, SAF tree copies, framework intents and local print adapter | Partial: single item from the viewer only; no batch copy/move/rename from the grid selection |
| Hidden (`.nomedia`) folders, hide/unhide, in-place moves, folder operations | Opt-in advanced storage mode, `:core:storage-advanced` (ADR-046) | Implemented behind the mode; SAF fallback and rename benchmark deferred |

## Explicit compatibility mappings

- Hidden/locked folders map to the encrypted Private Album.
- Recycle Bin maps to `MediaStore` trash and its platform expiry behavior.
- Included/excluded folders map to indexed volume/bucket identities.
- PIN/pattern authentication maps to the device credential; Lightforge stores no reusable PIN.
- File operations remain chunked and recoverable and never bypass Android authorization.
  `MANAGE_MEDIA` and the advanced storage mode are platform grants the user gives
  explicitly (ADR-046).

## Explicit exclusions

- Requiring `MANAGE_EXTERNAL_STORAGE`, or silently bypassing platform confirmations. All-files
  access, `.nomedia` mutation and deleting empty folders are allowed only in the opt-in,
  removable advanced storage mode (ADR-046); MediaStore stays the default model.
- Simple Gallery themes, app-icon/color replacement, legacy alternate layouts, and its
  separate video-player screen, because the Lightforge visual contract remains authoritative.
- Importing Simple Gallery settings/favorites files; Lightforge uses its own versioned format.
