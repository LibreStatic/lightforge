# Simple Gallery compatible-parity ledger

Reference: `SimpleMobileTools/Simple-Gallery` at `a356401`. This is a behavioral,
clean-room inventory; no GPL source code, resources, names, or visual assets are copied.

| Capability group | Lightforge implementation | Status |
|---|---|---|
| Timeline, albums, favorites, search, details, sharing, trash | MediaStore + Room/AppSearch production paths | Existing |
| GIF/WebP, RAW, large images, video, motion photos | Native decoder and Media3 pipelines with explicit fallbacks | Existing |
| Crop, rotate, flip, tone, filters, video trim/speed/audio | Non-destructive editors and verified pending publication | Existing |
| Collage, GIF creation, slideshow, widget, private album | Isolated local feature modules | Existing |
| Autoplay, mute, looping, brightness and remembered-position policy | Typed settings plus Room-backed per-video resume checkpoints | Implemented |
| Photo/video pinch zoom, focal double-tap, side skip, brightness, volume, swipe-down | Unified configurable viewer gesture policy | Implemented |
| Thumbnail crop/animation/badges/density | Typed defaults wired into the production timeline | Implemented |
| App/destructive locks | BiometricPrompt with strong biometric or device credential | Implemented |
| Settings backup/restore | Versioned Lightforge JSON through SAF, with legacy settings-only import | Implemented |
| Favorites backup/restore | Fingerprinted media records resolved against the current index, restored through chunked platform approval | Implemented |
| Sort/filter/group and included/excluded folders | Persistent bound Room paging queries over MediaStore volume/bucket identities, shared by timeline, viewer navigation and select-all | Implemented |
| Rename/copy/move/open-with/set-as/print/date repair | Write/delete requests, SAF tree copies, framework intents and local print adapter | Implemented |

## Explicit compatibility mappings

- Hidden/locked folders map to the encrypted Private Album.
- Recycle Bin maps to `MediaStore` trash and its platform expiry behavior.
- Included/excluded folders map to indexed volume/bucket identities.
- PIN/pattern authentication maps to the device credential; Lightforge stores no reusable PIN.
- File operations remain chunked and recoverable and never bypass Android authorization.

## Explicit exclusions

- `MANAGE_EXTERNAL_STORAGE`, raw filesystem crawling, `.nomedia` mutation, deleting empty
  filesystem folders, or silently bypassing platform confirmations.
- Simple Gallery themes, app-icon/color replacement, legacy alternate layouts, and its
  separate video-player screen, because the Lightforge visual contract remains authoritative.
- Importing Simple Gallery settings/favorites files; Lightforge uses its own versioned format.
