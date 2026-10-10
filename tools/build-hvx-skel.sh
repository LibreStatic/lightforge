#!/usr/bin/env bash
# Rebuilds liblfrife_skel.so (the Hexagon DSP half of the RIFE backend) from a lightforge-hvx
# checkout and copies it into the frame-interpolation assets.
#
# Usage: tools/build-hvx-skel.sh [path-to-lightforge-hvx]
#   LIGHTFORGE_HVX_DIR   checkout (default ~/lightforge-hvx; github.com/LibreStatic/lightforge-hvx)
#   HVX_SKEL_OUT         destination file override (default: the committed asset)
# Needs `clang` with the Hexagon target and `ld.lld` on PATH.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
hvx_dir="${1:-${LIGHTFORGE_HVX_DIR:-$HOME/lightforge-hvx}}"
out="${HVX_SKEL_OUT:-$repo_root/core/frame-interpolation/src/main/assets/hvx/liblfrife_skel.so}"

[[ -f "$hvx_dir/Makefile" ]] || { echo "error: no lightforge-hvx checkout at $hvx_dir" >&2; exit 1; }
for tool in clang ld.lld make; do
  command -v "$tool" >/dev/null || { echo "error: $tool not found on PATH" >&2; exit 1; }
done

make -C "$hvx_dir" build/liblfrife_skel.so
mkdir -p "$(dirname "$out")"
cp "$hvx_dir/build/liblfrife_skel.so" "$out"

echo "lightforge-hvx commit: $(git -C "$hvx_dir" rev-parse --short HEAD 2>/dev/null || echo unknown)"
echo "wrote $out ($(stat -c %s "$out") bytes)"
echo "Now bump EngineCacheKeys.LibraryVersion in" \
  "core/frame-interpolation/src/main/kotlin/com/librestatic/lightforge/core/frameinterpolation/FrameInterpolationEngine.kt"
echo "so devices drop their cached DSP library and benchmark result."
