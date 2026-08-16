# ADR-016: Module boundaries, DI, and navigation

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Features never depend on features. Pure domain interfaces isolate Android data implementations. Hilt is the composition mechanism and typed navigation stores compact keys/anchors only.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
