# ADR-006: AppSearch LocalStorage

- Status: Accepted
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Use AppSearch LocalStorage behind a repository interface. It is offline, versioned, permission-purgeable, and completely rebuildable.

The production `MediaDocument` uses a namespace per MediaStore volume and a document ID derived from the composite `(volumeName, mediaStoreId)` identity. Cheap MediaStore fields, OCR, canonical labels, optional person IDs, generation, schema version, and per-model versions are explicit typed properties. Writes and deletes are capped at 500 documents.

Rebuilds enumerate accessible, non-trashed Room rows in stable composite-key order. A SharedPreferences checkpoint stores only the last key and count, so interruption never persists a full ID list. Incompatible model fields are removed with a destructive schema override followed by a rebuild; AppSearch remains a derived index, never an authority.

## Consequences

Volume/permission loss purges the affected namespaces or bounded key batches. The decision is enforced by module boundaries, tests, build guards, and physical-device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.

Production lifecycle wiring rebuilds only on first schema use or an actual full-volume reconciliation. Row-specific `ContentObserver` hints update/remove one composite document directly; generation sync triggers a Room-backed rebuild only when it reports more changed rows than were covered by hints. App startup with an unchanged schema and generation never performs a full AppSearch rebuild.
