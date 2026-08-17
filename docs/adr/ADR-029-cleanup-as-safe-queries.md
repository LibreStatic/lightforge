# ADR-029: Cleanup collections as safe queries

- Status: Accepted
- Date: 2026-08-17

## Context

Cleanup suggestions must update as provider/derived state changes, scale without materializing IDs, and never bypass Android's trash confirmation. A recommendation is not authorization to delete.

## Decision

Represent large videos, screenshots, blurry candidates, and exact duplicate groups as typed, validated `MediaQuery.Scope` values. `RoomSelectionTargetSource` counts and keyset-pages those queries in chunks of at most 500, so `SelectionSpec.QueryAll + exclusions` remains O(1) in memory and process-restorable.

Expose a reactive Room summary for counts, large-video bytes, and exact-duplicate recoverable bytes. The cleanup repository performs no write or delete. User-selected targets continue through the M2 MediaStore action coordinator and platform trash confirmation.

## Consequences

Estimates automatically invalidate on media, hash, similarity, access, or trash changes. Blurry items remain explicitly labeled candidates. Exact recoverable space counts one kept copy per full-SHA group; no media is automatically deleted.
