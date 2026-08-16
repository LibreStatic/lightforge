#!/usr/bin/env bash
set -euo pipefail

SOURCE=${1:?"usage: seed_device.sh DATASET_DIRECTORY [SERIAL]"}
SERIAL=${2:-}
ADB=(adb)
if [[ -n "$SERIAL" ]]; then ADB+=( -s "$SERIAL" ); fi

DESTINATION=/sdcard/Pictures/UGalleryBenchmark
"${ADB[@]}" shell rm -rf "$DESTINATION"
"${ADB[@]}" shell mkdir -p "$DESTINATION"
"${ADB[@]}" push "$SOURCE"/. "$DESTINATION"/
"${ADB[@]}" shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
echo "Seeded $DESTINATION; verify MediaStore count before benchmarking."

