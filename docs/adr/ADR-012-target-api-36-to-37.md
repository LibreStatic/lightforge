# ADR-012: Target API transition

- Status: Accepted
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Compile against Android 17 SDK 37.0 while initially targeting API 36. Maintain an API 37 compatibility lane and raise target only after permission, sharing, large-screen, media, and memory regressions pass.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
