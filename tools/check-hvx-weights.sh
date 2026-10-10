#!/usr/bin/env bash
# Builds the on-device ncnn -> HVX weight converter for the host and checks that its output is byte-identical
# to the blob exported by lightforge-hvx (tools/export_weights.py, `make weights`).
#   tools/check-hvx-weights.sh [path/to/rife46_hvx.bin]
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
module="$root/core/frame-interpolation"
model="$module/src/main/assets/models/rife-v4.6"
cpp="$module/src/main/cpp/lfrife"
reference="${1:-$HOME/lightforge-hvx/build/weights/rife46_hvx.bin}"
work="${XDG_CACHE_HOME:-$HOME/.cache}/claude-tmp/ugallery"
mkdir -p "$work"
${CXX:-c++} -std=c++17 -O2 -Wall -Wextra -I"$cpp" -o "$work/hvx-weights-check" \
    "$root/tools/hvx-weights-check.cpp" "$cpp/hvx_weights.cpp"
"$work/hvx-weights-check" "$model/flownet.bin" "$model/flownet.param" "$reference"
