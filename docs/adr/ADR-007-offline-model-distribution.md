# ADR-007: Offline model distribution

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Only bundled, licensed and versioned models may ship. Runtime model downloads and remote inference are forbidden.

For first-party model assets, the app records and verifies a SHA-256 digest through the model registry. For models embedded inside a pinned library artifact, artifact coordinates and dependency integrity are the distribution identity. M3 pins the bundled variants `com.google.mlkit:image-labeling:17.0.9` and `com.google.mlkit:text-recognition:16.0.1`; Play Services download variants are not allowed.

Every model dependency change requires an offline release manifest audit. Bundled ML Kit contributes `INTERNET` transitively even though inference is local, so the application manifest explicitly removes both `INTERNET` and `ACCESS_NETWORK_STATE` from the packaged offline release.

## Consequences

The decision is enforced by module boundaries, pinned dependencies, manifest removal rules, tests, build guards, and device evidence. Engines are registered at process start but WorkManager scheduling remains behind explicit user opt-in. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
