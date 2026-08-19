# ADR-034: Encrypted private album container

**Status**: Accepted
**Date**: 2026-08-19
**Ticket**: M6-T01

## Context

The gallery must support a private album feature where users can move media items into an encrypted container that is protected by BiometricPrompt and isolated from the main library index.

## Decision

### Container format

Each private media item is stored as a chunked authenticated container with AES-256-GCM. Chunk size is 1 MiB, each chunk independently decryptable for video seek. Per-chunk 96-bit nonce from a monotonic counter with random base. Footer SHA-256 for whole-container integrity.

### Key management

Master key in Android Keystore (StrongBox if available, fallback TEE). Key alias ugallery.privatealbum.master. PURPOSE_ENCRYPT|DECRYPT, BLOCK_MODE_GCM, AUTH_BIOMETRIC_STRONG, 30s timeout. setInvalidatedByBiometricEnrollment(true). Per-container AES-256 data key encrypted by Keystore master and stored in Room.

### Video seek

Custom Media3 DataSource decrypts chunks on-demand. Bounded LRU cache (3 chunks = 3 MiB).

### Room schema

Separate database file ugallery-private.db with private_media and private_album_metadata tables.

### FLAG_SECURE

Compose route sets FLAG_SECURE via DisposableEffect.

### Privacy

Excluded from backup, timeline, search, ML indexes, and widget. No shared thumbnail cache.

### Export/recovery

Export via PendingMediaWriter.publishFile. Key invalidation warning. Uninstall warning on first setup.

## Consequences

- Video seek has small per-chunk GCM decrypt overhead.
- Key invalidation risk mitigated with warning and export.
- Separate database ensures isolation.
- Crypto review required before release.
