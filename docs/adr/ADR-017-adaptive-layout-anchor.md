# ADR-017: Adaptive layout and anchor preservation

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Use Android window size and posture rather than web breakpoints. Recalculate panes/columns around hinges and preserve MediaAnchor through rotation, fold, density, and multi-window changes.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
