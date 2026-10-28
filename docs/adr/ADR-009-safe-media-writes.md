# ADR-009: Safe media writes

- Status: Accepted
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Save as copy is default. Render to private temporary storage, insert a pending MediaStore row, copy and validate, then publish. Overwrite requires an explicit action and system authorization.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.

## M2 implementation

Generic copy/export writes insert `IS_PENDING=1`, persist a bounded recovery descriptor, stream
with coroutine cancellation, compare source/destination byte counts and SHA-256, and publish only
after verification. Any failure deletes the pending row. A move returns a verified published copy
plus a required system delete action; it never deletes the source inside the copy transaction.

Available-space preflight is used only when the destination can report a reliable value. Otherwise
provider failure still rolls back the unpublished row. Startup cleanup is restricted by owner,
relative-path prefix, volume, media kind, and age.

## Implementation status (2026-10-28)

The decision stands, but the implementation is incomplete:

- Only `VideoExportQueue` persists the pending URI through `persistPending`. The Private Album `stageStream` caller (`PrivateExportPublication`) records it in its own export receipt through `onInserted`.
- The other `GalleryViewModel` exporters (`copy`, `publishFile`) use the default no-op `persistPending`.
- `PendingMediaWriter.recoverOwnedPending` has no production caller, so the startup cleanup described above does not run.
- Private export keeps its rows on purpose, for a reviewed recovery: `Partial` rows are resolved by the user through `discardExport`.
