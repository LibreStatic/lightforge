# ADR-023: Treat trash and favorites as MediaStore provider state

## Status
Accepted for M2.

## Decision
Favorites, trash membership, and trash expiry are synchronized from MediaStore columns. All scans
explicitly include trashed rows; Room mirrors `IS_FAVORITE`, `IS_TRASHED`, and `DATE_EXPIRES` as a
rebuildable index. The trash route is a bounded keyset Paging query over mirrored rows.

Restore uses `MediaAction.Trash(false)` and permanent/empty-trash deletion uses
`MediaAction.Delete`, so foreign media always follows the restorable system-confirmation
coordinator. Expiry copy formats the provider value; it never assumes a fixed retention period.

## Consequences
- External favorite/trash/restore/delete changes converge through the normal generation observer.
- A trashed row is not mistaken for a deleted row during reconciliation.
- If the provider omits an expiry, UI shows an explicit unavailable date instead of inventing one.
