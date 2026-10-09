# Project Rules

- All commits must be written in English, regardless of the language used in the prompt.
- The application's core strings must be written in English and implemented with full localization support for English, Spanish, French, Portuguese, Italian, and other common languages.
- Bump `versionName` in `app/build.gradle.kts` in its own commit before every store release; never upload a new build under the previous version. Patch (`x.y.Z`) for fixes and small, self-contained changes (including a single small feature), minor (`x.Y.0`) for medium changes (several features, a reworked screen or flow), major (`X.0.0`) for a large overhaul; keep the pre-release suffix (e.g. `-beta`). The `versionCode` must also be higher than the last uploaded one.
- UI foreground/background combinations must use matching Material semantic color-role pairs (for example, `secondaryContainer` with `onSecondaryContainer`). Custom content must inherit the container content color unless an explicitly validated pair is required. Validate contrast in light, dark, and dynamic color themes; do not use unrelated semantic roles or hard-coded colors.

# Compose UI visual feedback (Compose Driver)

When changing native Android Jetpack Compose UI, use Compose Driver as the default visual feedback loop when the affected composable can run in the headless environment.

After meaningful UI changes:

1. render the affected screen/composable;
2. inspect its semantics tree;
3. inspect the screenshot visually;
4. interact with it if the task involves states, menus, scrolling, dialogs, sheets, or navigation;
5. correct obvious layout, clipping, hierarchy, contrast, or state issues;
6. render again after corrections.

Do not claim a UI change looks correct without inspecting a rendered result when Compose Driver is available.

Use a real Android emulator/device instead when behavior depends on hardware, OEM behavior, SurfaceView/TextureView, CameraX/camera hardware, codecs/video surfaces, WebView behavior, graphics behavior that Robolectric cannot reproduce, system UI/insets requiring device verification, or anything Compose Driver cannot faithfully execute.

Quick start (full guide, selectors and limitations: `tools/compose-driver/README.md`):

```bash
scripts/compose-driver.sh list                          # renderable previews
scripts/compose-driver.sh start CleanupPreview          # build + serve (≈30–40 s warm); add --dark, --qualifiers w360dp-h640dp, --font-scale 1.3
scripts/compose-driver.sh tree                          # semantics tree (text)
scripts/compose-driver.sh shot cleanup                  # -> build/compose-driver/screenshots/cleanup.png, then Read it
scripts/compose-driver.sh click tag=cleanup-trash-large-videos
scripts/compose-driver.sh reset PhotoEditorPreview      # fresh state or another preview, no rebuild
scripts/compose-driver.sh stop
```

Renderable entry points are zero-argument `*DriverPreviews.kt` files in a feature's `src/debug` source set that feed fake state into the real production composable. Add one there for the screen you are changing (never duplicate production UI, never put fake state in `src/main`) and list the feature in `tools/compose-driver/build.gradle.kts`.

# Publishing to Google Play

`tools/play-publish.py` publishes through the Play Developer API, so a release needs no Console clicks and no manual upload.

- The key is the organisation-wide LibreStatic service account `play-publisher@librestatic-publisher.iam.gserviceaccount.com`. Its JSON key lives in `~/.android/librestatic/` (override the path with `LIBRESTATIC_PLAY_KEY`). Never copy it into a repository, print it or paste it anywhere.
- The script only needs the standard library and the system `openssl`.
- `tools/play-publish.py status` lists every track with its releases.
- `tools/play-publish.py publish --aab <offline release aab> --mapping app/build/outputs/mapping/offlineRelease/mapping.txt [--track alpha] [--dry-run] [--no-review]` does the following:
  1. uploads the bundle and its R8 mapping;
  2. puts one release named `<versionCode> (<versionName>)` on the track, with the notes from `store/play/release-notes/<versionName>.txt`;
  3. commits the edit, which sends it for review.
- Run `--dry-run` first: it validates the edit and discards it.
- If the commit says the changes cannot be sent automatically, rerun with `--no-review`, then send them from Publishing overview.
- The package is read from `applicationId` (`com.librestatic.lightforge`).
- The closed track is `alpha`.
- If the API answers 403, the service account lacks access in Play Console > Users and permissions. Ask the user; do not grant it yourself.
