# ADR-004: Compose grid performance fallback

- Status: Accepted
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Compose LazyVerticalGrid is mandatory first. RecyclerView is permitted only for the timeline grid after the physical 100k/250k Macrobenchmark fails the jank gate and a superseding ADR records evidence.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
