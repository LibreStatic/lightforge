# ADR-031 — Bundled SFace embeddings with compact rebuildable storage

Status: accepted for technical integration; person clustering remains gated  
Date: 2026-08-18

## Context

M4 needs local person embeddings without cloud inference, runtime downloads, ambiguous weight
licensing, full-resolution crop retention, or `FloatArray` inflation in Room. The earlier
MobileFaceNet/InsightFace candidates did not establish redistributable weight rights.

## Decision

- Bundle OpenCV Zoo SFace `face_recognition_sface_2021dec` under the directory's explicit
  Apache-2.0 license. Preserve its source, upstream/bundled SHA-256, conversion recipe, and the
  limitation that the exact training recipe is not encoded in the checkpoint.
- Convert the official ONNX graph reproducibly to float16-weight TFLite with float32 input/output.
  It matches ONNX at cosine 0.999996 on the fixed conversion probe while halving model bytes;
  rejected dynamic-int8 conversions are not shipped.
- Execute with bundled LiteRT 2.2.0 `CompiledModel`; never download a model or infer remotely.
- Align a transient 112x112 face using detected landmarks, feed BGR float32 values, L2-normalize the
  128 outputs, and persist exactly 128 signed int8 components.
- Treat `(volumeName, mediaStoreId, faceOrdinal)` as the embedding key. The table references
  `detected_faces` with `ON DELETE CASCADE`; detection/model changes and consent purge rebuild it.
- Process only bounded pending-media chunks. No crop or bitmap is persisted.
- Keep the FaceEmbeddings worker unreachable from production UI until the user-provided identity
  corpus passes the conservative false-merge gate. Bundling and benchmarking a model is not an
  identity accuracy claim.

## Build compatibility

AGP 9.3.1 rejects the official `litert` + transitive `litert-api` 2.2.0 AAR pair because both
manifests declare `com.google.ai.edge.litert`. The app therefore depends on the official
`litert-api` artifact and vendors the **unmodified** native libraries extracted from the official
`litert` AAR. Per-ABI hashes and upstream license/third-party notices are retained under
`docs/models/` rather than disabling namespace validation. The optional Google Play AI delivery
dependency is excluded because the model is bundled and the release must never gain network/model
delivery permissions.

## Consequences

- The model adds about 19.3 MB before APK compression; LiteRT JNI cost is tracked separately by ABI.
- Stored embeddings cost 128 bytes of vector payload per accepted face instead of 512 bytes for
  float32, plus Room row/index overhead.
- CPU is mandatory. GPU/NPU are measured only when LiteRT reports them available; unavailable or
  compile failures are evidence, not hidden success.
- Clusters, names, People, and Me remain unavailable until M4-T04/T05 validation.
