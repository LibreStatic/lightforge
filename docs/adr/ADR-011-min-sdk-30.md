# ADR-011: Minimum SDK 30

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

API 30 is the minimum because public batch write/favorite/trash/delete requests provide a coherent non-OEM experience. Android 10 support is excluded.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
