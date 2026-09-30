# ADR-034: Encrypted private album container

**Status**: Accepted; authenticated media-master and index/session functional cuts verified; full private acceptance pending
**Date**: 2026-08-19; implementation reconciliation 2026-09-09
**Ticket**: M6-T01

## Context

Private photos and videos remain outside the public library index. Protection must distinguish UI authorization, actual Keystore authorization and encryption of the separate private index.

## Implemented container and export policy

New imports and portable restores publish version-3 authenticated streaming containers in `PrivateAlbumCrypto` (Tink). The complete header, including the exact plaintext length, is authenticated as associated data; short or long input fails verification. Version-1 and version-2 originals remain readable and are not rewritten by master-key protection. The version-2 writer remains for compatibility and encrypted-only unknown-length import staging: the provider is read once, then verified staging supplies the measured length for final v3 encryption. No plaintext staging is introduced. AES-256 per-container data keys are wrapped with the private master and recorded in the private Room index; portable restore generates fresh destination data keys. Export verifies plaintext integrity and uses `PendingMediaWriter`; portable archives use an independent password. No shared thumbnail cache, timeline/search/ML inclusion or Android backup/device-transfer of the private files is permitted.

Container format, key-wrapper format and Room schema versions are independent. Current global/private Room schemas are 31/4; private migration 3→4 adds export receipts, not a media-container rewrite. The earlier authenticated-master/index cuts below used global29/private3. Focused v3 evidence is retained in `docs/evidence/private-sized-container/VERIFICATION.txt`: cryptographic/cleanup JVM checks, API35 import/viewer/export/restore (13.989s) and injected directory-sync failure (3.686s). These are bounded fixture results, not physical power-loss or final whole-product acceptance.

## Authenticated media-master decision

New UI setup and explicitly confirmed existing-vault migration use a fresh UUID alias under `lightforge.privatealbum.auth.v1.`. AES-GCM keys require actual OS authentication, with a 30-second authorization window and `AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL`. StrongBox is requested where advertised; unavailable StrongBox falls back to the Android Keystore provider. No TEE/hardware-backed property is asserted without device `KeyInfo` evidence.

The device-credential-compatible, timed configuration does not promise invalidation on biometric enrollment. Removing/resetting the secure screen lock can invalidate keys; the UI requires an explicit confirmation and recommends an encrypted export beforehand. A successful prompt alone is not proof of key usability: callers perform real cryptographic operations before granting private access, publishing protection success or retiring the old master. Expired authorization leads to an explicit reauthentication path, not key creation.

## Resumable publication and recovery

Private Room3 adds a singleton migration record and encrypted key-wrapper staging. Durable INIT ownership precedes target-key creation. Completed wrappers persist in small batches; expiry/interruption retains them and never modifies media containers. A final transaction compares current IDs/count/source wrapper BLOBs, updates only the two wrapped-key fields, switches the metadata alias and records commitment. Concurrent old bindings reject publication after that switch.

Production SQLCipher uses WAL and FULL synchronous commits. Retirement checks effective durability, committed metadata and a real operation with the target key before deleting the prior alias. Cancellation first persists its intention, then retires only the uncommitted target and removes staging; committed migration is finalized rather than reversed. Missing committed targets never regenerate. Both cleanup paths can continue from recorded state.

## Authenticated index and revocable session

The independent random 32-byte SQLCipher password remains unchanged. An explicitly confirmed protection action upgrades its bounded v1 device-only wrapper to a v2 AES-GCM record using the authenticated media-master alias and separate, database/alias-bound AAD. Candidate decryption is verified before atomic publication; readback and directory durability precede deletion of the deterministic legacy index alias. A published v2 record is authoritative even when retirement is interrupted. Retry verifies v2 and finishes retirement; it never falls back to v1 or regenerates a missing committed key. Global/private Room schemas remain 29/3 in this cut.

Production creates a stable, initially Locked repository without reading the private index. After the platform prompt, each opening constructs a fresh SQLCipher/Room instance and exercises the committed media master before publishing Ready. Revocation synchronously closes admission, advances the epoch and cancels sensitive operations. Physical close waits for admitted atomic commit/reconciliation work, then closes SQLCipher and clears the opener-owned password reference. Cancelled queued openings also schedule independent cleanup. The 30-second Keystore authorization window applies to new cryptographic operations; it is not an automatic 30-second UI-session timeout. Background, route exit, explicit lock and disposal revoke the session.

Bound media keys and restore reviews carry the admission epoch. Queued operations capture it before IO dispatch; stale bindings/reviews do not adopt a later authentication. Long decryption observes cancellation. Snapshot observations release their leases rather than retaining a database indefinitely. Stable portable transfer workers share their journal registry across database replacement. Prepared ciphertext publication/discard remains database-independent; locking discards sensitive review/password state, not an already prepared encrypted export.

Focused evidence: `docs/evidence/private-index-session/VERIFICATION.txt`. API30 real credential fixture preserves one JPEG/container and the SQLCipher password while upgrading the wrapper, proves old handles close, rejects expired opening after 32 seconds, reauthenticates, reads original bytes and retains/discards the same prepared ciphertext through the original journal. Owned UUID aliases were removed before restoring credential None. Bootstrap UI verifies enabled Unlock without setup/index IO. JVM lease/record regressions cover cancellation, stale queued opening/use, atomic finalization, observer refresh and strict format parsing. These fixtures do not claim actual main-app process death, physical-device, theme/localization/accessibility, video seeking or release acceptance; those remain in final combined acceptance.

## Platform references

- [Key generation and authentication parameters](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
- [Biometric or device-credential authentication](https://developer.android.com/identity/sign-in/biometric-auth)
