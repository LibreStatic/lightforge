# ADR-014: Vector index strategy

- Status: Proposed
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Do not store large FloatArray values in Room. If P1/P2 evaluation approves embeddings, store quantized vectors in versioned mmap-friendly chunks with Room offsets; keep the backend replaceable.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
