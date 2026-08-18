# M4-T03 face embedding candidate report

Status: **OpenCV Zoo SFace approved; non-commercial identity gate passed**  
Date: 2026-08-18

## Required gate

An accepted artifact needs an immutable source, an SPDX-compatible redistribution grant covering
the weights (not only wrapper code), training/model provenance, SHA-256, fixed preprocessing and
output contract, bounded APK cost, LiteRT CPU/GPU/NPU measurements, deterministic golden outputs,
and an approved representative false-merge evaluation.

## Candidate findings

| Candidate | Finding | Decision |
|---|---|---|
| InsightFace MobileFaceNet/ArcFace model-zoo weights | The project states that its provided pretrained models and associated training data are for non-commercial research use. The MIT source-code license does not grant unrestricted use of those weights. | Rejected for bundled release. |
| Community-converted `mobilefacenet.tflite` files | Permissive repository licenses generally cover wrapper/conversion code but do not establish the exact weights’ origin, training-data terms, immutable upstream version, or redistribution grant. | Rejected pending verifiable provenance. |
| MediaPipe / ML Kit face APIs | Official artifacts provide bundled face detection/landmarks. They do not provide a supported person-identity embedding task or identity model. | Accepted only for M4-T02 detection. |
| OpenCV Zoo SFace `face_recognition_sface_2021dec.onnx` | OpenCV Zoo states that every file in the SFace directory, including the weights, is Apache-2.0. It identifies the contributor, MobileFaceNet architecture, SFace loss, upstream paper/code, 112x112 input, and 128-value output. The upstream training recipes identify CASIA-WebFace, VGGFace2, and MS1MV2; the exact recipe used for this exported checkpoint is not stated in the ONNX metadata. | Accepted for local technical integration under the explicit directory license. Preserve the provenance limitation in release review and do not claim demographic or production identity accuracy before the private identity corpus gate. |
| Train/procure an application-owned model | Could satisfy the gate with documented licensed training data, evaluation and redistribution rights, but no approved weights or corpus were supplied. | Viable future path; not implemented. |

## Accepted artifact contract

- Upstream artifact: `face_recognition_sface_2021dec.onnx`
- Upstream SHA-256: `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79`
- Conversion: pinned `onnx2tf` container, TensorFlow converter backend, float16 weights with float32 input/output
- Bundled artifact: `core/ml/src/main/assets/models/sface_2021dec_float16.tflite`
- Bundled SHA-256: `0eed234c5ef82ae7de14277d7bb72f066b007f8c6b78a8f697173bdbca8b60ed`
- Conversion check against ONNX: cosine `0.99999624`, maximum absolute difference `0.004063` on the fixed random probe
- Input: one aligned 112x112 BGR float32 face, values 0..255; normalization is embedded in the graph
- Output: 128 float32 values, L2-normalized by the application and compacted to signed int8 for persistence
- Runtime: bundled LiteRT 2.2.0 `CompiledModel`; no runtime model download and no network inference
- License copy: `docs/models/licenses/SFACE_APACHE_2.0.txt`
- Reproduction: `tools/models/prepare_sface_litert.sh`

Primary references:

- InsightFace repository license statement: https://github.com/deepinsight/insightface
- InsightFace model-zoo license statement: https://github.com/deepinsight/insightface/tree/master/model_zoo
- Official ML Kit bundled face detection documentation: https://developers.google.com/ml-kit/vision/face-detection/android
- Google AI Edge MediaPipe task inventory: https://github.com/google-ai-edge/mediapipe-samples
- OpenCV Zoo SFace model card and directory license: https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface
- SFace paper/code and training recipes: https://github.com/zhongyy/SFace

## Consequence

The accepted artifact may enter the local embedding runtime and benchmark lane. The user-supplied
DigiFace-derived v3 corpus passed the research false-merge holdout at the conservative `0.47` gate,
and the user confirmed UGallery is non-commercial. Automatic People/Me surfaces may be enabled for that scope, but the R-UDA corpus cannot support an unrestricted commercial claim unless a production-compatible corpus passes the same gate. The checkpoint's incomplete exact training
recipe remains a documented release-review risk rather than being silently presented as known.
