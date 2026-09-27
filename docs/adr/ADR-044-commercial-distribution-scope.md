# ADR-044 — Commercial distribution scope

Status: accepted  
Date: 2026-09-27

## Context

Lightforge Studio is open source under Apache-2.0. The intended distribution model is a free APK on
GitHub plus a paid Google Play listing that users can buy to support the project. `PRODUCT.md`
listed "Non-commercial use only" as a brand commitment, which originated from ADR-032: People/Me
clustering was calibrated and false-merge validated on the DigiFace-derived R-UDA corpus, which is
licensed for non-commercial research only.

`docs/legal/COMMERCIAL_USE_AUDIT.md` inventories every shipped or downloaded asset. Only the
People/Me validation corpus blocks commercial release; SFace training provenance is a gray area;
all other code, models, data and fonts permit commercial use with attribution.

## Decision

- The project, as a whole, may be distributed commercially. There is a single build: the GitHub
  APK and the Google Play APK have the same feature set, with no product flavors.
- People/Me stays as implemented and enabled in every distribution, behind the existing explicit
  consent. ADR-032 behavior is unchanged: threshold `0.47`, conservative merges, manual corrections.
- Accepted risk: the `0.47` threshold and the false-merge gate were derived from the R-UDA corpus,
  whose license is non-commercial research only. The corpus itself is never shipped; the product
  ships a single derived number. The project owner accepts that using this derived calibration in
  a paid distribution is a legal gray area.
- Mitigation: before or shortly after the first paid release, repeat the ADR-032 identity gate on
  a project-owned, consented corpus (or another corpus with commercial rights) and replace the
  R-UDA-derived calibration. Doing so removes this risk entirely and supersedes this bullet.
- The in-app People notice must not describe the feature as non-commercial or research-only in a
  paid distribution.
- The bundled SFace weights (OpenCV Zoo, Apache-2.0) are accepted. Their upstream training data is
  research-licensed; this residual risk is recorded here and in the audit, and SFace must be
  re-evaluated if its upstream license or the legal treatment of trained weights changes.
- The Apache-2.0 license grants no trademark rights (§6). The "Lightforge" name and icon are not
  licensed for redistribution under the project brand.
- The in-app licenses screen and `assets/licenses/` stay in every distribution.

## Consequences

- Selling on Google Play is compatible with the open-source model; People/Me carries the recorded
  calibration risk until the mitigation lands.
- Face data is biometric data under GDPR art. 9; the Play listing's privacy policy must state that
  face analysis is on-device, opt-in and never uploaded.
- Replacing SFace with another open embedding model does not remove the gray area: most available
  face-embedding models share the same research-licensed training datasets.
- Any new model or dataset requires a license entry in the audit before it ships or is downloaded.
