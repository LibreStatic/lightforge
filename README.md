# Lightforge

Lightforge Studio is a native, open-source gallery for Android, built for local libraries of 100,000 to 250,000 photos and videos. MediaStore is the single source of truth; Room, AppSearch, thumbnails and every ML index are caches that can be rebuilt. Organization, search and recognition run on the device, and nothing is presented as working until it does: unfinished paths degrade with an explicit label instead of a silent stub.

- **Version:** `0.2.0-beta` (public beta)
- **Package:** `com.librestatic.lightforge`
- **Requires:** Android 11 (API 30) or later
- **License:** [Apache-2.0](LICENSE)
- **Languages:** English, Spanish, French, Portuguese, Italian and German

## Features

### Library and browsing

- Timeline grid with pinch-to-zoom that keeps its anchor across rotation, resize and fold changes; built for 250k items without ANR or OOM.
- Incremental sync through ContentObserver, MediaStore generations and per-volume reconciliation, including removable SD cards; no full scan at startup.
- Full, selected-media and revoked photo permission states, revalidated on every return to the foreground. No all-files access.
- Full-screen viewer with large-image tiling, animated GIF/WebP, Media3 video playback and motion photos (Samsung and Google formats), with lazy EXIF details.
- Albums (physical and virtual), favorites, trash, smart collections and photo stacks, with bounded bulk actions in chunks of up to 500 items.

### Search and organization

- Keyword search (AppSearch), plus search by date, place, people, pets and text in photos (OCR).
- Optional semantic search with signed, user-managed TinyCLIP model packages; keyword search stays available without one.
- Opt-in face grouping with conservative clustering and manual correction, pet recognition, and an offline Places map with bundled reverse geocoding.
- Exact duplicates, similar photos, cleanup suggestions and memories grouped by time and place.

### Editing and creation

- Photo editor with filters, crop, straighten, adjustments and Camera RAW development. Object eraser and subject cut-out are labelled experimental.
- Video editor with trim, speed, music, color, LOG footage, LUT import and HDR export where the device supports it.
- PDF Studio, collage, GIF maker, memory videos and a home screen widget.
- Edits are saved as copies by default; overwriting an original goes through the system authorization flow.

### Privacy and sharing

- Encrypted private album (AES-256-GCM, Android Keystore, biometric gate, `FLAG_SECURE`) and an optional app lock.
- Encrypted transfer of originals to another Android device on the local network.
- Folder mirroring and backup to the user's own servers.

## Principles

- Local first: no accounts, ads, analytics, telemetry or cloud dependency. Gallery data, queries and embeddings never leave the device.
- MediaStore is the authority; identity is `(volumeName, mediaStoreId)`, never an absolute path.
- Permission states are first-class: full, partial and revoked all work explicitly.
- All writes are atomic or recoverable (`IS_PENDING` exports, size and SHA-256 verification before publishing).
- Honesty over illusion: no misleading stubs, no hidden errors, no invented success.

See the [privacy policy](store/play/privacy-policy.md) and the [commercial use audit](docs/legal/COMMERCIAL_USE_AUDIT.md) for details.

## Permissions

| Permission | Used for |
|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`, `READ_EXTERNAL_STORAGE` (API 30–32) | Reading the library, with full or selected-media access. |
| `ACCESS_MEDIA_LOCATION` | Photo locations for Places and details; suppressed when access is not granted and purged on revoke. |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_LOCAL_NETWORK` | User-initiated model downloads, backup to the user's own servers and local-network sharing only. Guarded by `tools/verify_offline_release.py`; cleartext traffic is disabled. |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING` | Visible progress for long exports, indexing, backup and transfers. |

No location, `MANAGE_EXTERNAL_STORAGE` or `MANAGE_MEDIA` permission, and no OEM privilege assumptions; Wi-Fi and location permissions pulled in by libraries are removed from the merged manifest.

## Project status

Milestones M0 through M6 are complete: library engine and timeline, management and viewer, search and similarity, people, pets and moments, editors and private sharing, and the advanced isolated features.

Still open:

- **Object eraser and subject cut-out** use explicit fallback algorithms (neighbor interpolation and color distance) until an ML model is bundled.
- **Physical-device passes** for the latest design, fold-posture and private-album wiring are still pending; emulator evidence covers them for now.
- **Exact pixel parity** with the design mockup is not claimed.

## Repository layout

| Path | Contents |
|---|---|
| `app/` | Application shell, navigation host, DI and manifest. |
| `core/` | Shared modules: data, database (Room), MediaStore, thumbnails, search, ML, selection, editing, RAW, security, design system and more. |
| `feature/` | One module per surface: photos, collections, viewer, search, editors, private album, PDF Studio, places, sharing, backup and more. |
| `benchmark/`, `baselineprofile/` | Macrobenchmark journeys and Baseline Profile generation. |
| `testdata/`, `scripts/` | Synthetic 1k–250k library generation and helper scripts. |
| `tools/` | Release guards, license generation and verification scripts. |
| `docs/` | ADRs, architecture, design, legal and privacy. |
| `store/play/` | Google Play listings, images and check scripts. |

Documentation hierarchy: [PRODUCT.md](PRODUCT.md) → [ADRs](docs/adr/) → [architecture](docs/architecture/) → code and tests.

## Building and testing

Use JDK 17 and the checked-in Gradle wrapper:

```bash
./gradlew :app:assembleOfflineDebug :app:assembleOfflineRelease test lint
```

Release guards run in CI after the build:

```bash
python tools/verify_offline_release.py
```

Instrumented tests need a device or emulator:

```bash
./gradlew :app:connectedOfflineDebugAndroidTest
```

The `offline` flavor is the shipped product; `demo` installs side by side with a `.demo` suffix. Debug builds use a `.debug` suffix so they never replace a normal install.

## Releases

- **GitHub:** every push to `main` builds a signed App Bundle and APK and publishes a prerelease tagged `v<versionName>+<versionCode>`, with the R8 mapping file and `SHA256SUMS.txt`.
- **Google Play:** the same bundle, uploaded manually. GitHub and Play ship the same feature set ([ADR-044](docs/adr/ADR-044-commercial-distribution-scope.md)).

`versionCode` comes from the CI run number plus an offset, so it always increases. Signing uses the `LIGHTFORGE_KEYSTORE_*` and `LIGHTFORGE_KEY_*` environment variables; keys never enter the repository. The Apache-2.0 license grants no rights to the Lightforge name or icon.

## Contributing

Issues and pull requests are welcome at [LibreStatic/lightforge](https://github.com/LibreStatic/lightforge). Run the checks above before opening a pull request, keep new strings localized in every supported language, and back device claims with evidence.
