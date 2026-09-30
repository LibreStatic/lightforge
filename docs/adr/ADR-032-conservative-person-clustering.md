# ADR-032 — Conservative local person clustering with persistent corrections

Status: accepted for non-commercial activation; commercial activation requires a new compatible corpus gate (see ADR-044)  
Date: 2026-08-18

## Context

M4 requires incremental local grouping over compact SFace embeddings, zero hidden cloud work,
bounded memory, conservative automatic merges and manual corrections that survive reclustering.
Loading all embeddings or all face IDs is forbidden. The user confirmed Lightforge is non-commercial, which is compatible with the available DigiFace-derived R-UDA research corpus for this M4 gate.

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
- Register the clustering runtime and expose People/Me only after explicit user consent. The activation remains scoped to non-commercial use; commercial/unrestricted release must repeat the identity gate with compatible rights.

## Local Me profile

The optional Me profile uses 1–20 explicit references, a 128-byte compact centroid and a derived
match table. Matching advances through a persisted composite keyset cursor in idempotent chunks.
Deleting the profile cascades references/matches; deleting all face data also removes clusters,
constraints, overrides, names and the profile without deleting photos.

## Consequences

- False negatives/fragmentation are preferable to an unsafe automatic merge.
- The projection index is rebuildable and bounded but remains approximate; manual corrections are
  first-class product state rather than model output.
- The reference phone research holdout passed 7/7 positives and 0/48 negatives. The sample is small and its R-UDA license still prevents presenting it as an unrestricted production/commercial validation.
