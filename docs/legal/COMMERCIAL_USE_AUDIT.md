# Commercial Use Audit

Date: 2026-09-27. Scope: everything shipped in the APK or downloaded by the app at runtime.
Goal: determine whether Lightforge Studio can be sold on Google Play while remaining open source
(Apache-2.0 source, free APK on GitHub). This is an engineering inventory, not legal advice.

## Summary

| Verdict | Count |
|---|---|
| Blocks commercial release | 1 (People/Me identity validation corpus) |
| Gray area, needs a decision | 1 (SFace training data) |
| Compatible, attribution/notice required | everything else |

The source license (Apache-2.0, `LICENSE`, `COPYRIGHT`) permits commercial distribution. The
"Non-commercial use only" line in `PRODUCT.md` originates solely from ADR-032.

## ML models

| Asset | Where | License | Commercial | Notes |
|---|---|---|---|---|
| SFace 2021dec float16 | `core/ml/src/main/assets/models/sface_2021dec_float16.tflite` | Apache-2.0 (OpenCV Zoo) | Gray | Weights are Apache-2.0, but SFace was trained on face datasets (CASIA-WebFace / MS-Celeb-1M family) whose terms are research-only. Common industry practice treats weights as separately licensed; not settled law. |
| R-UDA / DigiFace-derived corpus | Validation only (ADR-032), not shipped | Research / non-commercial | **Blocks** | ADR-032 scopes People/Me activation to non-commercial use because this corpus calibrated the `0.47` threshold and the false-merge gate. |
| TinyCLIP Balanced / Quality | Downloaded from `LibreStatic/lightforge-models` releases (ADR-040) | MIT (TinyCLIP) + MIT (CLIP) | Yes | Ship `TinyCLIP-LICENSE.txt` and `CLIP-LICENSE.txt` (already in assets). |
| Open Noodle Pet Recognition SMALL | Download, `pet-model-notices.json` | Apache-2.0 | Yes | Verify model card lists no training-data restriction before release. |
| EfficientDet-Lite0 | Download, `pet-model-notices.json` | Apache-2.0 | Yes | |
| RIFE v4.6 (ncnn) | `core/frame-interpolation/src/main/assets/models/rife-v4.6/` | MIT | Yes | |
| ML Kit face detection / text recognition | Play services dependency | ML Kit Terms | Yes | Google terms allow commercial apps; ties the Play build to GMS (already the case). |

## Native and library code

| Component | License | Commercial | Notes |
|---|---|---|---|
| 215 AndroidX/Kotlin/etc. deps | Apache-2.0 | Yes | Generated in `app/src/main/assets/third_party_licenses.json`. |
| ONNX Runtime, MBassador, Bouncy Castle | MIT | Yes | |
| ncnn | BSD-3-Clause | Yes | |
| MapLibre Native / gestures | BSD-2-Clause | Yes | |
| Protobuf (AppSearch, DataStore) | BSD-3-Clause | Yes | |
| Tink | Apache-2.0 AND BSD-3-Clause | Yes | |
| SQLCipher Android | BSD-3-Clause AND Apache-2.0 AND WTFPL | Yes | Community edition. |
| PDFBox-Android | Apache-2.0 (bundled notices) | Yes | |
| LibRaw 0.22.2 | LGPL-2.1 or CDDL-1.0 | Yes | Built as a separate shared library (`liblightforge_raw.so`); LGPL obligations are met because the full source is public. Keep it a shared object and keep the notice. |
| Play services base/basement/tasks | Android SDK License | Yes | |

## Data and fonts

| Asset | License | Commercial | Notes |
|---|---|---|---|
| Places gazetteer (ADR-037) | GeoNames CC-BY 4.0 | Yes | Attribution required. |
| Map tiles | OpenStreetMap ODbL, Protomaps | Yes | Attribution required (`places/ATTRIBUTION.txt`). |
| Noto Sans/Serif (PDF Studio, map glyphs) | SIL OFL 1.1 | Yes | Fonts may be bundled in sold software; cannot be sold on their own. |

## Required changes for a Play Store paid release

Decision recorded in ADR-044: single build, People/Me enabled everywhere; the R-UDA-derived
calibration is an accepted risk until item 1 (own consented corpus) lands. SFace is accepted (item 2).

1. **People/Me (blocking).** Pick one:
   - Recalibrate the clustering threshold and repeat the ADR-032 false-merge gate using a corpus
     with commercial rights (own consented photos, or a commercially licensed dataset), then
     supersede ADR-032.
   - Or exclude People/Me from the `play` build (product flavor) until that gate passes.
2. **SFace (decide).** Accept the Apache-2.0 weights as-is (common practice), or replace them
   with a face-embedding model whose training data is also commercially licensed.
3. **Docs.** Remove "Non-commercial use only" from `PRODUCT.md` Brand Commitments; write a new ADR
   superseding the ADR-032 scope; rename/adjust the `peopleAndMeControlsAreExplicitAndNonCommercial`
   test.
4. **Trademark.** Apache-2.0 §6 grants no trademark rights. State in the README that the name
   "Lightforge" and the icon are not covered, so third parties cannot republish under the brand.
5. **Keep notices.** The in-app licenses screen and `assets/licenses/` already cover attribution;
   keep them in every flavor.
