# M4-T03 face embedding candidate report

Status: **No model approved — person identity remains deferred**  
Date: 2026-08-17

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
| Train/procure an application-owned model | Could satisfy the gate with documented licensed training data, evaluation and redistribution rights, but no approved weights or corpus were supplied. | Viable future path; not implemented. |

Primary references:

- InsightFace repository license statement: https://github.com/deepinsight/insightface
- InsightFace model-zoo license statement: https://github.com/deepinsight/insightface/tree/master/model_zoo
- Official ML Kit bundled face detection documentation: https://developers.google.com/ml-kit/vision/face-detection/android
- Google AI Edge MediaPipe task inventory: https://github.com/google-ai-edge/mediapipe-samples

## Consequence

No face embedding asset, LiteRT identity runtime, person clustering, People route, or Me route may
enter the release. Detection-only data controls and non-identity features such as pet type and local
moments may continue. This is a truthful degradation, not a recognition stub.
