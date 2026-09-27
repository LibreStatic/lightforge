# ADR-007: Private model distribution

- Status: Superseded by ADR-040
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Bundled ML models remain licensed, versioned, and digest-verified. Semantic-search models are the sole exception to bundled distribution: they may be downloaded from the built-in catalog under ADR-040. Remote inference remains forbidden.

For first-party model assets, the app records and verifies a SHA-256 digest through the model registry. For models embedded inside a pinned library artifact, artifact coordinates and dependency integrity are the distribution identity. M3 pins the bundled variants `com.google.mlkit:image-labeling:17.0.9` and `com.google.mlkit:text-recognition:16.0.1`; Play Services download variants are not allowed.

Every model dependency change requires a release manifest audit. `INTERNET` and `ACCESS_NETWORK_STATE` are approved only for the signed semantic package downloader. Media, metadata, search terms, embeddings, and inference never leave the device.

## Consequences

The decision is enforced by module boundaries, an HTTPS-only network security policy, a fixed-host downloader, SHA-256 plus P-256 signature verification, pinned dependencies, tests, build guards, and device evidence. Analysis and indexing remain behind explicit user opt-in.
