# Benchmark execution

Generate deterministic fixtures:

```bash
python tools/testdata/generate_media_dataset.py /tmp/lightforge-10k --count 10000 --materialize
tools/testdata/seed_device.sh /tmp/lightforge-10k emulator-5554
```

Run smoke benchmarks:

```bash
./gradlew :benchmark:connectedOfflineBenchmarkAndroidTest \
  -P android.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

## Baseline and Startup Profiles

```bash
ANDROID_SERIAL=emulator-5554 scripts/generate-baseline-profile.sh
```

The script runs `:app:generateOfflineReleaseBaselineProfile` with `androidx.benchmark.enabledRules=BaselineProfile` and a fresh `fixtureUuid`, logs to `~/.cache/claude-tmp/ugallery/generate-baseline-profile.log` and prints the diff stat of `app/src/main/baseline-prof.txt` and `startup-prof.txt` (20 to 40 minutes, depending on host load). Commit the two files only after reviewing the diff.

Journeys (`baselineprofile/.../GalleryBaselineProfile.kt`):

| Journey | Runtime | Feeds |
| --- | --- | --- |
| `productionStartup` | production cold start, timeline flings | baseline + startup profile (the only startup-profile source) |
| `startupAndTimeline` | synthetic 100k grid | baseline profile only |
| `productionViewerAndSearch` | viewer, local search | baseline profile only |
| `productionPhotoEditor` | viewer, editor, every visible tab, one Adjust slider drag, discard | baseline profile only |

Preconditions:

- A single device or emulator, selected with `ANDROID_SERIAL` (default `emulator-5554`). Tests must not run on a device holding data you care about: they install the `offline` `nonMinifiedRelease` build of `com.librestatic.lightforge`.
- Nothing to do by hand for permissions or onboarding. Each production journey grants `READ_MEDIA_*` through the instrumentation, launches once, skips the first-run wizard, declines the local-analysis prompt and force-stops, without clearing app data. A fresh install therefore works.
- The viewer and editor journeys publish one PNG under `Pictures/LightforgeProfile-<uuid>/` and delete it by exact URI afterwards. An existing receipt for the same UUID aborts the run.

## Benchmarks added for the profiled paths

- `ProductionStartupBenchmark`: production cold start with `StartupTimingMetric`, with (`BaselineProfileMode.Require`) and without a profile.
- `PhotoEditorBenchmark`: `FrameTimingMetric` while opening the editor and switching tabs. It uses the first library photo that offers Edit (non-RAW first), so the device needs seeded photos (`tools/testdata/seed_device.sh` above). The benchmark module has no MediaStore fixture helper and the editor cost does not depend on the photo.

Both run through the Macrobenchmark command above. They grant permissions and skip onboarding themselves (`ProductionTargetSupport.kt`).

Release gates must run on the physical matrix defined in the technical plan; emulator results cannot approve performance.

