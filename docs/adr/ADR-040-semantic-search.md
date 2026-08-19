# ADR-040: Semantic text-image search architecture

Date: 2026-08-19

## Status

Proposed (spike complete, model not bundled)

## Context

M6-T02 requires semantic text-image search using joint embeddings.
This needs a CLIP-compatible model that encodes both text and images into
the same vector space for cosine similarity matching.

## Decision

Implement the architecture with a two-tier approach:
1. Primary: CLIP-based joint embedding (when model is bundled)
2. Fallback: Keyword search using existing AppSearch index

The SemanticSearchEngine class:
- isSemanticAvailable(): returns false until a model is bundled
- search(): uses CLIP when available, falls back to keyword search
- The CLIP path throws UnsupportedOperationException to prevent silent fallback

Model selection criteria:
- Must be bundlable as a TFLite/LiteRT model
- Must support both text and image encoding
- Must fit within APK size budget (~20-50MB)
- No runtime model download

## Consequences

- Without a bundled model, semantic search falls back to keyword search
- The fallback is explicitly labeled KEYWORD_FALLBACK
- No misleading stub: the architecture is real but the model is unbundled
- No cloud or network dependency
