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

No placeholder model or misleading recognition UI may enter an offline release.
