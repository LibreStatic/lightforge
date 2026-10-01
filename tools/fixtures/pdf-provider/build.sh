#!/usr/bin/env bash
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
SOURCE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:?Pass an explicit output directory}"
mkdir -p "$OUT/classes" "$OUT/dex"
"$SDK/build-tools/37.0.0/aapt2" link -I "$SDK/platforms/android-37.0/android.jar" --manifest "$SOURCE/AndroidManifest.xml" -o "$OUT/provider-unsigned.apk"
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
