# Compose Driver harness

Development-only tooling that renders a single Compose screen headlessly (Robolectric, no emulator,
no app install) and drives it over HTTP, so an agent can read the semantics tree, look at
screenshots and click, type, scroll or swipe. It uses the published
[Compose Driver](https://github.com/jdemeulenaere/compose-driver) library
(`io.github.jdemeulenaere:compose-driver`, version in `gradle/libs.versions.toml`).

Nothing ships with the app: the `:tools:compose-driver` module is only included in the build when
one of its tasks is requested (or with `-Plightforge.composeDriver` for IDE sync), and preview
wrappers live in `src/debug` source sets, which release and benchmark builds do not compile.

## Commands

Everything goes through `scripts/compose-driver.sh` (bash; `help` prints usage).

```bash
scripts/compose-driver.sh list
scripts/compose-driver.sh start PhotoEditorPreview     # returns once GET /status answers "ok"
scripts/compose-driver.sh tree                         # semantics tree, unmerged=false
scripts/compose-driver.sh shot editor                  # build/compose-driver/screenshots/editor.png
scripts/compose-driver.sh swipe LEFT tag=photo-editor-tool-adjust
scripts/compose-driver.sh click tag=photo-editor-tool-objecteraser
scripts/compose-driver.sh shot eraser
scripts/compose-driver.sh reset                        # recreate state, same composable
scripts/compose-driver.sh reset CleanupPreview         # switch composable without rebuilding
scripts/compose-driver.sh stop
```

| Command | Purpose |
|---|---|
| `start <composable> [--dark] [--qualifiers Q] [--font-scale F]` | Build and serve. `<composable>` is a preview name from `list` or `pkg.FileKt.Function`. |
| `status`, `stop` | Readiness check; stop the server and its Gradle build. |
| `reset [composable]` | Fresh UI state, optionally another composable (same Gradle build, so only modules already on the classpath). |
| `tree [selector]` | Semantics tree as text. |
| `shot [name] [selector]` | PNG of the root or of the selected node. |
| `click`, `longclick`, `doubleclick`, `scrollto`, `waitfor` `[selector]` | Act on or wait for a node. |
| `type <text>`, `replace <text>`, `clear` `[selector]` | Text fields. |
| `swipe <UP\|DOWN\|LEFT\|RIGHT> [selector]`, `back`, `idle` | Gestures, system back, wait for idle. |
| `call <endpoint> [k=v ...]` | Any other endpoint, e.g. `call keyEvent key=Enter`, `call pointerInput/down x=10 y=20`. |

Selectors: `tag=<testTag>`, `text=<exact text>`, plus `substring` / `ignorecase`; `gif=<ms>` records
the interaction as a GIF (needs `ffmpeg`). Without a selector the root is used.

The raw HTTP API is also available at `http://localhost:8137` (`/printTree`, `/screenshot`,
`/click?nodeTag=…`, `/reset?composable=…`, …); see the upstream README for every endpoint.

### Viewport and options

- Default: Pixel 8-like phone, `w412dp-h915dp-420dpi` (1081 × 2401 px screenshots), SDK 36, light theme.
- `--dark` adds the `night` qualifier; `LightforgeTheme` follows the system setting.
- `--qualifiers w360dp-h640dp` (or `w840dp-h900dp` for a tablet-like width) is layered over the default, density included.
- `--font-scale 1.3` checks large-text wrapping.
- Each option needs a `start` (it restarts the server); `reset` keeps the current configuration.

### Environment

- `COMPOSE_DRIVER_PORT` (default `8137`; port 8080, the upstream default, is often taken locally).
- `COMPOSE_DRIVER_GRADLE_ARGS`, e.g. `--max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process` on a loaded host.
- `COMPOSE_DRIVER_START_TIMEOUT` seconds (default 900, enough for a cold build).
- Server log: `build/compose-driver/server.log`.

## Adding a screen

1. In the feature module, create `src/debug/kotlin/<package>/<Name>DriverPreviews.kt`.
2. Add public, zero-argument `@Composable` functions (also annotate them `@Preview` so Android Studio
   shows them) that wrap the real production composable in `LightforgeTheme { Surface { … } }` and pass
   fake `UiState` plus no-op callbacks. Debug code can reach `internal` composables of its own module.
3. If the screen only exists behind a ViewModel, repository or DI, render its stateless content
   composable instead; refactor just enough to expose one if none exists.
4. Add `debugImplementation("androidx.compose.ui:ui-tooling-preview")` to the feature if missing,
   and `testImplementation(project(":feature:<name>"))` to `tools/compose-driver/build.gradle.kts`.
5. Prefer existing text and test tags in selectors; add `Modifier.testTag("feature-thing")` only where
   a control or container cannot be selected reliably otherwise.

Current previews: `PhotoEditorPreview`, `PhotoEditorLoadingPreview`, `EraserModelPanelStatesPreview`
(`feature/photoeditor`), `CleanupPreview`, `CleanupAnalysisOffPreview`, `CleanupEmptyPreview`,
`CleanupLoadingPreview` (`feature/collections`), `PhotosTimelinePreview`, `PhotosSelectionPreview`,
`PhotosFilterEmptyPreview` (`feature/photos`), `PickerGridPreview`, `PickerSingleGridPreview`
(`feature/picker`), `DesignSystemCataloguePreview`, `DesignSystemStatesPreview`, `SelectionBarPreview`
(`core/designsystem`).

## Screenshot matrix

A lean, repeatable set of window classes for layout work. Shots are not committed; they land in
`build/compose-driver/screenshots/<name>.png`. Use the `-<window>[-dark][-fs13]` suffix so runs compare.

| Window | Qualifiers | Why |
|---|---|---|
| `phone` | `w393dp-h852dp` | Compact portrait, the most common case. |
| `phone-land` | `w852dp-h393dp` | Compact height: bars, sheets and headers must not eat the content. |
| `fold` | `w673dp-h841dp` | Medium width (unfolded book posture). |
| `fold-land` | `w841dp-h673dp` | Expanded width, short height. |
| `tablet` | `w1280dp-h800dp` | Expanded landscape tablet. |
| `desktop` | `w1920dp-h1080dp` | Desktop window: margins, max widths, no stretched rows. |

Per screen, check at least `phone` (light), `phone --dark`, `phone --font-scale 1.3` and one wide
window; run the full matrix for grid or shell changes. Each option needs its own `start`:

```bash
scripts/compose-driver.sh start PhotosTimelinePreview --qualifiers w1280dp-h800dp --dark
scripts/compose-driver.sh shot photos-tablet-dark
```

Shared grid and selection components (`AdaptiveMediaGrid`, `GallerySelectionBar`) are covered by
`SelectionBarPreview`; screens that adopt them should add their own preview rather than relying on it.
`CleanupLoadingPreview` (`feature/collections`), `VideoEditorPreview`, `VideoFilmstripLoadingPreview`
(`feature/videoeditor`).

## Behavior worth knowing

- Lazy lists only compose what is on screen: a node below the fold is missing from `tree` and
  `scrollto` cannot find it. `swipe UP tag=<list>` (or `LEFT` for rows) first, then `waitfor` + `scrollto`.
- Dialogs and popups are separate roots. `tree` lists every root (`1)`, `2)` …) but a plain `shot`
  captures only the main window; select a node inside the dialog, e.g. `shot dialog tag=cleanup-trash-dialog`.
- `back` goes through Compose's navigation-event dispatcher: it reaches `BackHandler`-style handlers,
  not `Dialog` windows. Dismiss dialogs with their buttons (`click text=Cancel`).
- The clock is virtual: infinite animations (indeterminate progress) appear frozen; use `gif=<ms>`
  on an action to see motion.
- WorkManager, Hilt, MediaStore, ML models and thumbnails are not initialized. Buttons that call
  them (for example the eraser model Download/Cancel/Remove buttons) can crash the server; `start` again.
  Thumbnails render as placeholders.
- Dynamic color uses Robolectric's default system palette, not a wallpaper-derived one.

## Still needs an emulator or device

Real thumbnails and photo decoding, video playback and codecs, camera, SurfaceView/TextureView,
WebView, ncnn/Vulkan or other native ML, WorkManager-driven flows, system bars/insets and edge-to-edge,
predictive back and dialog back handling, OEM behavior, real animation timing and performance.
