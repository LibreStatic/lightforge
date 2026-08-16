# ADR-008: Face recognition privacy

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Face detection does not identify people. Identity requires an approved LiteRT embedding model, conservative clustering, explicit opt-in, corrections, progress, pause, and complete deletion.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
