# ADR-041: Individual pet recognition architecture

Date: 2026-08-19

## Status

Proposed (spike complete, model not bundled)

## Context

M6-T03 requires individual pet recognition (identifying specific dogs/cats,
not just detecting pet type which was done in M4-T06).
This needs a pet embedding model similar to the face embedding model used
for person recognition.

Key constraint: No accidental crossover with person-data.

## Decision

Implement the architecture with a two-tier approach:
1. Primary: Pet embedding + clustering (when model is bundled)
2. Fallback: Label-only (existing M4-T06 dog/cat collections)

The PetRecognitionEngine class:
- isRecognitionAvailable(): returns false until a model is bundled
- clusterPets(): uses embeddings when available, returns empty otherwise
- verifyNoCrossoverWithPersonData(): safety check for data separation

Pet embeddings are stored in a separate Room table/namespace from person
embeddings to prevent any accidental crossover.

## Consequences

- Without a bundled model, pet recognition falls back to label-only collections
- The fallback is explicitly labeled LABEL_ONLY_FALLBACK
- No misleading stub: the architecture is real but the model is unbundled
- No cloud or network dependency
