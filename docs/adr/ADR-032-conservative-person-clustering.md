# ADR-032 — Conservative local person clustering with persistent corrections

Status: backend accepted; production activation license-gated  
Date: 2026-08-18

## Context

M4 requires incremental local grouping over compact SFace embeddings, zero hidden cloud work,
bounded memory, conservative automatic merges and manual corrections that survive reclustering.
Loading all embeddings or all face IDs is forbidden. The available identity corpus is useful for
research but is licensed only under DigiFace-1M R-UDA v1.0.

## Decision

- Use cosine `0.47`, rounded conservatively above the research calibration's `0.465671` threshold.
- Index cluster centroids with four deterministic six-projection bands. Each lookup is range-bound
  and capped; at most 1,024 candidate clusters and eight boundary exemplars are evaluated.
- Require both centroid and every retained boundary exemplar to meet the threshold. Approximate
  index misses may fragment a person but cannot relax the false-merge gate.
- Persist Room clusters, memberships and projection rows as rebuildable derived data. Media/face
  identity remains `(volumeName, mediaStoreId, faceOrdinal)`.
- Persist `MUST_LINK`/`CANNOT_LINK` constraints plus explicit face-to-cluster overrides. Merge,
  split, rename and hide are always manual; no name is generated automatically.
- Use deterministic automatic IDs and explicit manual split IDs so display names/hidden state and
  assignments survive embedding rebuilds.
- Keep the runtime unscheduled and People/Me UI unavailable until the corpus license is compatible
  with the intended product scope or a production-compatible corpus passes the same gate.

## Local Me profile

The optional Me profile uses 1–20 explicit references, a 128-byte compact centroid and a derived
match table. Matching advances through a persisted composite keyset cursor in idempotent chunks.
Deleting the profile cascades references/matches; deleting all face data also removes clusters,
constraints, overrides, names and the profile without deleting photos.

## Consequences

- False negatives/fragmentation are preferable to an unsafe automatic merge.
- The projection index is rebuildable and bounded but remains approximate; manual corrections are
  first-class product state rather than model output.
- The reference phone research holdout passed 7/7 positives and 0/48 negatives, but the sample is small and its
  R-UDA license prevents presenting it as an unrestricted production/commercial validation.
