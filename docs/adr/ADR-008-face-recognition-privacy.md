# ADR-008: Face recognition privacy

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Face detection does not identify people. Identity requires an approved LiteRT embedding model, conservative clustering, explicit opt-in, corrections, progress, pause, and complete deletion.

Person recognition is explicitly deferred from M0 to the M4-T03 entry gate. M0 establishes policy only. M4-T03 must select a candidate and validate its license, bundled distribution, reproducibility, size, device latency, and false-merge behavior before recognition implementation can begin.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.

Until M4-T03 passes, releases contain no face embedding model, recognition UI, feature stub, or identity claim.
