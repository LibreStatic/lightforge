# ADR-019: Represent select-all as an immutable query snapshot

## Status
Accepted for M2.

## Decision
Selection has two representations: `Explicit(keys)` for individually chosen media and
`QueryAll(querySnapshot, exclusions)` for select-all. The latter captures typed query data,
never SQL or absolute paths. Bulk operations resolve the captured query with keyset paging and
emit bounded chunks; changing the visible filter does not mutate an existing selection.

Process-restorable state is versioned and stores only the explicit keys or query snapshot plus
exclusions. Every media identity remains `(volumeName, mediaStoreId)`.

## Consequences
- Selecting 100k–250k rows is O(1) in memory before user-created exclusions.
- Counts for `QueryAll` require the count of the captured query, not the current screen query.
- Bulk actions can cancel between chunks and report partial results without materializing IDs.
- Callers must determine whether a newly displayed item belongs to the captured query before
  presenting it as selected.
