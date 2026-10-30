#!/usr/bin/env bash
# Rebuilds the bundled ncnn runtime (core/frame-interpolation/src/main/jniLibs/*/libncnn.so) from the pinned
# upstream tag with 16 KB ELF segment alignment, which Google Play requires for apps targeting Android 15+.
# The flags mirror ncnn's own android-vulkan-shared release job for that tag, plus ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES.
#   tools/build-ncnn-android.sh [ndk dir]   (defaults to $ANDROID_NDK_HOME, then $ANDROID_HOME/ndk/27.1.12297006)
set -euo pipefail
tag=20220729
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ndk="${1:-${ANDROID_NDK_HOME:-${ANDROID_HOME:?set ANDROID_HOME or pass the NDK dir}/ndk/27.1.12297006}}"
out="$root/core/frame-interpolation/src/main/jniLibs"
work="${XDG_CACHE_HOME:-$HOME/.cache}/lightforge/ncnn-$tag"

if [ ! -d "$work/src" ]; then
    mkdir -p "$work"
    git clone -q --depth 1 --branch "$tag" https://github.com/Tencent/ncnn.git "$work/src"
    git -C "$work/src" submodule update --init --depth 1 glslang
fi

for abi in arm64-v8a armeabi-v7a x86_64; do
    build="$work/build-$abi"
    cmake -S "$work/src" -B "$build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_USE_LEGACY_TOOLCHAIN_FILE=False \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$build/install" -DNCNN_VERSION_STRING="1.0.$tag" \
        -DANDROID_ABI="$abi" -DANDROID_ARM_NEON=ON -DANDROID_PLATFORM=android-24 \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DNCNN_VULKAN=ON -DNCNN_BUILD_BENCHMARK=OFF -DNCNN_SHARED_LIB=ON \
        -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF -DNCNN_BUILD_TESTS=OFF
    cmake --build "$build"
    cmake --build "$build" --target install/strip
    cp "$build/install/lib/libncnn.so" "$out/$abi/libncnn.so"
done

# Every LOAD segment of the 64-bit libraries must be aligned to at least 16 KB (0x4000). 32-bit ABIs only ever run
# with 4 KB pages, so the NDK keeps them at 4 KB and Play does not check them.
for abi in arm64-v8a x86_64; do
    for align in $(readelf -lW "$out/$abi/libncnn.so" | awk '/LOAD/ { print $NF }'); do
        if (( align < 16384 )); then
            echo "not 16 KB aligned: $out/$abi/libncnn.so" >&2
            exit 1
        fi
    done
done
echo "ncnn $tag rebuilt with 16 KB alignment"
