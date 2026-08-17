# ADR-017: Adaptive layout and anchor preservation

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Use Android window size and posture rather than web breakpoints. Recalculate panes/columns around hinges and preserve MediaAnchor through rotation, fold, density, and multi-window changes.

For pinch density changes, capture the visible grid anchor on the first pointer-down and consume two-pointer motion so the scroll state cannot drift before the column threshold is crossed. Change density at most once per gesture.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.

The reference phone physical Macrobenchmark pinches from three to four columns after scrolling and requires the gesture-centered visible media item to remain visible.
