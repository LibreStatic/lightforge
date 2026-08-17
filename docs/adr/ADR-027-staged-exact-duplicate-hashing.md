# ADR-027: Staged exact duplicate hashing

- Status: Accepted
- Date: 2026-08-17

## Context

Hashing every byte of 100k–250k media items is unnecessarily expensive, while size or sampled hashes alone can produce false duplicate groups. Group construction must not materialize the library, and cleanup must never silently delete media.

## Decision

Use a versioned, resumable two-stage derived index:

1. keyset/chunk candidates by current `(volumeName, mediaStoreId)`, generation, and size;
2. hash three distinct 64 KiB windows plus size with SHA-256;
3. compute full streaming SHA-256 only when another accessible current item has the same size and sample hash;
4. expose groups only for equal size and full SHA-256 through SQL window functions and bounded keyset pages.

The derived Room row has a composite foreign key to `media_items`, so provider deletions cascade. Keep recommendations prefer favorite, then resolution, recency, and stable composite identity. They are suggestions only; deletion always uses the existing explicit selection and system-confirmation flow.

## Consequences

Unique items avoid full reads. Sample collisions remain safe because they only trigger full hashing. Per-item commits and WorkManager checkpoints make interruption replay idempotent. The hash algorithm version invalidates stale rows when sampling changes.
