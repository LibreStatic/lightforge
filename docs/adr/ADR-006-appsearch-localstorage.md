# ADR-006: AppSearch LocalStorage

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Use AppSearch LocalStorage behind a repository interface. It is offline, versioned, permission-purgeable, and completely rebuildable.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
