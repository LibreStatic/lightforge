# ADR-040: Downloadable on-device semantic search

- Status: Accepted
- Date: 2026-08-25

## Context

Semantic text-image search needs a CLIP-compatible image and text encoder in one vector space. Bundling every hardware tier would inflate the APK and prevent users from choosing a different quality/performance trade-off.

## Decision

Lightforge ships a fixed, first-party catalog of immutable TinyCLIP LiteRT packages. The app selects the best technically compatible package for the device and automatically downloads it only over validated unmetered Wi-Fi. A user may explicitly allow another connected network, keep multiple packages, choose any technically compatible package despite a recommendation warning, delete packages individually, or disable semantic search without deleting them.

Each archive is downloaded resumably from the allowlisted GitHub release hosts, checked against its exact size and SHA-256 digest, verified with the embedded P-256 public key, extracted through a strict file allowlist into staging, and atomically installed. Custom URLs and unsigned sources are not supported.

Image indexing runs locally with WorkManager after local-analysis consent. Embeddings are compact 512-dimensional quantized vectors stored in Room under a model-specific index. Switching models builds a parallel replacement index while the current index remains searchable, then atomically activates the replacement. Query embedding, LSH candidate retrieval, exact cosine reranking, and reciprocal-rank fusion with AppSearch all remain on device.

Keyword search remains available whenever semantic search is disabled, has no active index, or is rebuilding its first index.

## Privacy boundary

`INTERNET` and `ACCESS_NETWORK_STATE` exist solely for model package delivery. Gallery media, thumbnails, metadata, search queries, embeddings, and inference are never sent to a service. Cleartext traffic is disabled.

## Consequences

- The APK stays small while users retain explicit control over storage and model quality.
- True ABI/RAM incompatibility blocks activation but not download; a recommendation can be overridden.
- Catalog updates require a new immutable release asset, descriptor digest/size, P-256 signature, license notice, and regression validation.
