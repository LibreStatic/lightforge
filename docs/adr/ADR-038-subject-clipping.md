# ADR-038: Subject clipping with local fallback

Date: 2026-08-19

## Status

Proposed

## Context

M6-T04 requires subject clipping (cutting out the foreground subject from an image).
This needs a segmentation model. ML Kit Subject Segmentation is available as a
bundled dependency but has not yet been integrated.

## Decision

Implement a two-tier approach:
1. Primary: ML Kit Subject Segmentation (when bundled) — provides a foreground mask
2. Fallback: Color-distance segmentation — uses center-pixel color as reference

The fallback is explicitly labeled as COLOR_DISTANCE_FALLBACK and is not
presented as a high-quality ML result. It is a functional degradation.

## Consequences

- Without the ML Kit dependency, subject clipping works but with lower quality
- The fallback is suitable for simple cases (subject on uniform background)
- The ML path requires adding com.google.mlkit:subject-segmentation dependency
- No cloud or network dependency in either path
