#!/usr/bin/env bash
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
SOURCE="$(realpath -- "$(dirname -- "$0")")"
OUT="${1:?Pass an explicit new output directory}"
ASSET="${2:?Pass the explicit feature/motionphotos/src/androidTest/assets/motion_fixture.mp4 path}"
# Reject stale outputs and a substituted input before invoking Android tooling.
python3 - "$OUT" "$ASSET" <<'CHECK'
import hashlib,pathlib,sys
out,asset=map(pathlib.Path,sys.argv[1:])
assert out.is_absolute() and not out.exists(), 'Output must be a new absolute directory'
assert asset.is_absolute() and asset.is_file(), 'Explicit asset file required'
data=asset.read_bytes()
assert len(data)==145922 and hashlib.sha256(data).hexdigest()=='ca9dc6afcd80d25b6d70c9057312e8584b9a59c1ac642d8beff3c1a50fa82861', 'Seed MP4 mismatch'
out.mkdir(parents=True)
CHECK
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/assets"
cp -- "$ASSET" "$OUT/assets/motion_fixture.mp4"
"$SDK/build-tools/37.0.0/aapt2" link -I "$SDK/platforms/android-37.0/android.jar" --manifest "$SOURCE/AndroidManifest.xml" -A "$OUT/assets" -o "$OUT/provider-unsigned.apk"
javac --release 8 -classpath "$SDK/platforms/android-37.0/android.jar" -d "$OUT/classes" "$SOURCE/ControlActivity.java" "$SOURCE/FixtureDocumentsProvider.java"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$SDK/build-tools/37.0.0/d8" --min-api 30 --lib "$SDK/platforms/android-37.0/android.jar" --output "$OUT/dex" "$OUT/classes.jar"
python3 - "$OUT" <<'ZIP'
import pathlib,sys,zipfile
p=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(p/'provider-unsigned.apk','a') as z:z.write(p/'dex/classes.dex','classes.dex')
ZIP
"$SDK/build-tools/37.0.0/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$OUT/provider.apk" "$OUT/provider-unsigned.apk"
"$SDK/build-tools/37.0.0/apksigner" verify "$OUT/provider.apk"
