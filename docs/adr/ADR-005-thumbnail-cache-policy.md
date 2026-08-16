# ADR-005: Native thumbnail cache policy

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Use ContentResolver.loadThumbnail with cancellation, bounded memory/disk caches, generation-aware keys, trim-memory handling, and short directional prefetch. Never decode full resolution for grid cells.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
