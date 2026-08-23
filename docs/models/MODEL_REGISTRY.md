# Bundled model registry policy

Every release model requires: source URL, SPDX license, immutable version, SHA-256, input/output contract, quantization, APK size contribution, and CPU/GPU/NPU measurements. Runtime downloads and remote inference are forbidden.

| Capability | M0 decision |
|---|---|
| Face detection | Bundled ML Kit candidate; detection only |
| OCR Latin | Bundled ML Kit candidate |
| Image labels | Bundled ML Kit candidate |
| Face embedding | Explicitly deferred to the M4-T03 validation gate; people identity remains unavailable |
| Pet identity | P2; no model approved |
| Semantic image/text | P2; no model approved |
| Video frame interpolation | Bundled RIFE ncnn v4.6 (`a7532fc3`) with ncnn `20220729`; Vulkan preferred, CPU fallback |

No placeholder model or misleading recognition UI may enter an offline release.

## RIFE v4.6 video frame interpolation

- Source: `https://github.com/nihui/rife-ncnn-vulkan/tree/a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7`
- Runtime: ncnn Android Vulkan shared `20220729` (the release matching the upstream RIFE ncnn submodule era)
- Licenses: RIFE implementation and weights MIT; ncnn BSD-3-Clause plus bundled notices
- Input/output: two equal-size RGB8 frames and a timestep in `(0, 1)` produce one RGB8 frame
- Quantization: fp16 Vulkan storage where supported; fp32 arithmetic/output
- `flownet.bin`: 10,614,320 bytes; SHA-256 `f334ed2260149ce0188a6dcf049844e8b0cdd912e01cbcfb63553157d2508958`
- `flownet.param`: 16,532 bytes; SHA-256 `724569596bcd1e7b9fa50455c604777ebed99746d2ef40aa86e31b5725f1053c`
- Delivery: bundled offline; runtime download and remote inference are not permitted
- Device gate: record warm/cold latency, sustained FPS, peak RSS, temperature and Vulkan/CPU parity on the physical foldable before release
