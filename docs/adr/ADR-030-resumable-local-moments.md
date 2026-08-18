# ADR-030: Resumable bounded local moments

- Status: Accepted
- Date: 2026-08-17

## Context

Moment suggestions must remain useful on libraries of 100,000–250,000 items without retaining the
library, GPS history, or large candidate sets in memory. They also need to survive interruption and
must never overwrite a user's title, cover, order, or saved state.

## Decision

Use an offline deterministic heuristic over ascending composite-keyset pages of 256 images. Split
events after a six-hour gap, extend the gap to 24 hours only when consecutive permission-authorized
coordinates are within 10 km, and force-close after 72 hours or 5,000 items. Keep at most 96 ranked
staging candidates and publish at most 30 diverse members. Screenshots and scan-like media are not
cover candidates.

Persist only the keyset checkpoint, event time bounds, item count, and bounded candidate identities.
Coordinates are read from the existing permission-scoped EXIF cache but never copied into moment
tables. Resuming without the previous coordinate intentionally falls back to the stricter six-hour
rule for the first comparison.

Moment members and covers reference `(volumeName, mediaStoreId)` with foreign-key cascades. A
temporary permission or removable-volume loss only hides members; it does not remove their refs.
Actual MediaStore reconciliation deletion cascades derived refs. Any user edit marks the moment as
protected from automatic replacement.

## Consequences

- Work is bounded, deterministic, local, idempotent, and resumable without an LLM or network.
- Stable IDs allow regeneration to replace only untouched suggestions.
- A checkpoint-boundary event can split more conservatively after process death because raw GPS is
  deliberately not persisted.
- User reordering requires the complete member set, preventing hidden members from being dropped.
