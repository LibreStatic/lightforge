#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${TMPDIR:-/tmp}/lightforge-sface-litert"
ONNX_SHA="0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
TFLITE_SHA="0eed234c5ef82ae7de14277d7bb72f066b007f8c6b78a8f697173bdbca8b60ed"
CONVERTER="pinto0309/onnx2tf@sha256:c28077a775e14f0a30efdb348133c477379b5c703a020ed3223a2c973170b03e"

mkdir -p "$WORK" "$ROOT/core/ml/src/main/assets/models"
curl --fail --location --retry 3 \
  https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx \
  --output "$WORK/sface.onnx"
echo "$ONNX_SHA  $WORK/sface.onnx" | sha256sum --check
chmod -R a+rwX "$WORK"
docker run --rm -v "$WORK:/work" "$CONVERTER" \
  onnx2tf -i /work/sface.onnx -o /work/converted -tb tf_converter
cp "$WORK/converted/sface_float16.tflite" \
  "$ROOT/core/ml/src/main/assets/models/sface_2021dec_float16.tflite"
echo "$TFLITE_SHA  $ROOT/core/ml/src/main/assets/models/sface_2021dec_float16.tflite" | sha256sum --check
