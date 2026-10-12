#!/usr/bin/env bash
# Regenerates app/src/main/baseline-prof.txt and startup-prof.txt on a connected device by running
# every journey in :baselineprofile (synthetic grid, production startup, viewer + search, photo editor).
# Usage: scripts/generate-baseline-profile.sh   (ANDROID_SERIAL selects the device, default emulator-5554)
# Optional: GENERATE_PROFILE_GRADLE_ARGS="--max-workers=2" for extra Gradle arguments.
# Preconditions: docs/benchmarks/README.md. The journeys grant media access and skip onboarding themselves.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-emulator-5554}"
TASK=":app:generateOfflineReleaseBaselineProfile"
LOG_DIR="${GENERATE_PROFILE_LOG_DIR:-$HOME/.cache/claude-tmp/ugallery}"
LOG="$LOG_DIR/generate-baseline-profile.log"
read -r -a EXTRA_ARGS <<<"${GENERATE_PROFILE_GRADLE_ARGS:-}"

command -v adb >/dev/null || { echo "adb not found" >&2; exit 2; }
state="$(adb -s "$SERIAL" get-state 2>/dev/null || true)"
[[ "$state" == "device" ]] || { echo "Device $SERIAL is not connected (state: ${state:-none})" >&2; exit 2; }
mkdir -p "$LOG_DIR"

# The fixture photo is published under this UUID and removed again by exact URI when each journey ends.
UUID="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')"
echo "Device: $SERIAL  fixtureUuid: $UUID  log: $LOG"

cd "$ROOT"
set +e
ANDROID_SERIAL="$SERIAL" ./gradlew "$TASK" \
  --max-workers=4 \
  -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1536m" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.fixtureUuid="$UUID" \
  "${EXTRA_ARGS[@]}" >"$LOG" 2>&1
code=$?
set -e
echo "exit=$code"
grep -E '^e:|FAILED|BUILD' "$LOG" | head -20 || true

# The plugin writes to src/<variant>/generated/baselineProfiles. This repo keeps the single, committed
# copy in src/main (AGP merges every source set, so a stale second copy would pollute the startup profile).
GENERATED="$ROOT/app/src/offlineRelease/generated/baselineProfiles"
if [[ "$code" -eq 0 ]]; then
  for name in baseline-prof.txt startup-prof.txt; do
    cp "$GENERATED/$name" "$ROOT/app/src/main/$name"
  done
  rm -r "$ROOT/app/src/offlineRelease/generated"
  rmdir --ignore-fail-on-non-empty "$ROOT/app/src/offlineRelease" 2>/dev/null || true
fi

git -C "$ROOT" diff --stat -- app/src/main/baseline-prof.txt app/src/main/startup-prof.txt
exit "$code"
