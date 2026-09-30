# ADR-008: Face recognition privacy

- Status: Accepted; M4 implementation details continued by ADR-031 and ADR-032
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Face detection does not identify people. Identity requires an approved LiteRT embedding model, conservative clustering, explicit opt-in, corrections, progress, pause, and complete deletion.

Person recognition is explicitly deferred from M0 to the M4-T03 entry gate. M0 establishes policy only. M4-T03 must select a candidate and validate its license, bundled distribution, reproducibility, size, device latency, and false-merge behavior before recognition implementation can begin.

M4-T01/T02 may ship independently as explicit-consent **face detection only**. Detection uses a
bundled ML Kit model, bounded thumbnails, conservative quality filtering, normalized geometry, and
complete deletion. It persists no crop bitmap and makes no identity claim.

The initial M4-T03 candidate review did not approve identity weights. InsightFace's published
pretrained models are limited to non-commercial research use, while commonly redistributed
MobileFaceNet TFLite files do not provide sufficient weight/training provenance merely because their
wrapper repositories use permissive source licenses. MediaPipe/ML Kit provide detection and
landmarks, not an approved person-identity embedding. Recognition therefore remains deferred until
an independently distributable model artifact and evaluation corpus pass the gate.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.

M4-T03 subsequently approved the Apache-2.0 OpenCV Zoo SFace artifact and physical accelerator
evidence (ADR-031). M4-T04/T05 backend implementation is covered by ADR-032. People/Me production
surfaces remain disabled because the available false-merge corpus is R-UDA research-only; the
original prohibition now applies to **activation and identity claims**, not to the licensed bundled
model or tested backend. The detection/privacy screen must still distinguish face-region detection
from recognition.
