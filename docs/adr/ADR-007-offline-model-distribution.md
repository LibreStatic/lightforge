# ADR-007: Private model distribution

- Status: Superseded by ADR-040, amended 2026-10-28
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Bundled ML models remain licensed, versioned, and digest-verified. Semantic-search models are the sole exception to bundled distribution: they may be downloaded from the built-in catalog under ADR-040. Remote inference remains forbidden.

For first-party model assets, the app records and verifies a SHA-256 digest through the model registry. For models embedded inside a pinned library artifact, artifact coordinates and dependency integrity are the distribution identity. M3 pins the bundled variants `com.google.mlkit:image-labeling:17.0.9` and `com.google.mlkit:text-recognition:16.0.1`; Play Services download variants are not allowed.

Every model dependency change requires a release manifest audit. `INTERNET` and `ACCESS_NETWORK_STATE` are approved only for the signed semantic package downloader. Media, metadata, search terms, embeddings, and inference never leave the device.

## Consequences

The decision is enforced by module boundaries, an HTTPS-only network security policy, a fixed-host downloader, SHA-256 plus P-256 signature verification, pinned dependencies, tests, build guards, and device evidence. Analysis and indexing remain behind explicit user opt-in.

## Amendment (2026-10-28)

`INTERNET` and `ACCESS_NETWORK_STATE` are no longer used only by the signed semantic package downloader (see the ADR-013 amendment). They also serve these user-initiated actions:

- pet and object-eraser model downloads. These are pinned by size and SHA-256 but not signed (`core/ml` `PinnedModelDownload`, `PetModelStore`).
- backup and OwnSync to the user's own SFTP/SMB servers.
- encrypted transfer over the local network.

Bundled distribution is no longer the rule. Semantic, pet and inpainting models are all downloaded, or imported for pets. "Approved only for the signed semantic package downloader" no longer applies. Remote inference stays forbidden, and media, metadata, queries and embeddings never leave the device for a third party.
