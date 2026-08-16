# ADR-003: Generation-based synchronization

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Persist version and generation checkpoints per volume, coalesce ContentObserver signals, and use reconciliation to detect deletions. Partial-access invisibility is not physical deletion.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
