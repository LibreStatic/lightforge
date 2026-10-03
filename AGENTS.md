# Project Rules

- All commits must be written in English, regardless of the language used in the prompt.
- The application's core strings must be written in English and implemented with full localization support for English, Spanish, French, Portuguese, Italian, and other common languages.
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
