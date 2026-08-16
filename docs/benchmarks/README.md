# Benchmark execution

Generate deterministic fixtures:

```bash
python tools/testdata/generate_media_dataset.py /tmp/ugallery-10k --count 10000 --materialize
tools/testdata/seed_device.sh /tmp/ugallery-10k emulator-5554
```

Run smoke benchmarks:

```bash
./gradlew :benchmark:connectedOfflineBenchmarkAndroidTest \
  -P android.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

Generate the Baseline Profile separately with `androidx.benchmark.enabledRules=BaselineProfile`. Release gates must run on the physical matrix defined in the technical plan; emulator results cannot approve performance.

