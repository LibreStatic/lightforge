# ADR-003: Generation-based synchronization

- Status: Accepted, amended 2026-10-28
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Persist version and generation checkpoints per volume, coalesce ContentObserver signals, and use reconciliation to detect deletions. Partial-access invisibility is not physical deletion.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.

## Amendment (2026-10-28)

Reconciliation does not delete rows; it hides them, because absence can also mean a revoked selection or a `.nomedia` folder.

- A full volume scan ends in `LibraryDao.completeVolumeScan`, which calls `hideItemsNotSeenInScan`; a folder rescan calls `hideFolderRowsNotIn` (`IncrementalMediaSynchronizer`, `LibraryDao`).
- With `reconcileUnobservedChanges`, a generation delta is followed by `IncrementalMediaSynchronizer.sweepAbsences`, an id-only sweep that also hides indexed ids MediaStore no longer lists.
- A row is physically deleted only in `IncrementalMediaSynchronizer.applyRowHint`: the row-specific URI reports it absent, image and video access are both `Full`, and the file no longer exists. Otherwise the row is hidden or the hint reports `PermissionLost`.

"Use reconciliation to detect deletions" no longer applies as written: reconciliation detects absence and hides it. Physical deletion comes only from a row hint.
