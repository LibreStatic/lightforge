# ADR-028: Bounded local similarity graph

- Status: Accepted
- Date: 2026-08-17

## Context

Pairwise comparison across 100k–250k images is O(N²), while similar stacks must update as MediaStore generations change and users must be able to remove a mistaken member. Person recognition is separately deferred and cannot be a hidden dependency.

## Decision

Extract a versioned native descriptor from a bounded thumbnail: 64-bit DCT perceptual hash, 48-byte 4×4 RGB grid, and Laplacian blur variance. Split pHash into four indexed 16-bit LSH buckets and compare at most 256 candidates that share a corresponding bucket. A weighted pHash/descriptor score creates edges above the recorded threshold.

Persist features, edges and cluster memberships as reconstructible Room data with composite MediaStore identity and FK cascades. Cluster components are capped at 500 members; changes rebuild only the previous component. Persistent per-item exclusions implement ungroup, while restore invalidates the feature so WorkManager reprocesses it.

## Consequences

Candidate discovery is indexed rather than all-pairs. Stacks appear incrementally and stale edges disappear on generation replacement or provider deletion. The descriptor is deterministic image analysis, not a person-identity model, and its algorithm version invalidates incompatible caches.
