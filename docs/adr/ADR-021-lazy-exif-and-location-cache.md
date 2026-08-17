# ADR-021: Load EXIF lazily and gate cached location

## Status
Accepted for M2.

## Decision
Details first read cheap indexed fields from Room. EXIF is opened only on demand, off the main
thread, and cached by `(volumeName, mediaStoreId, generationModified)`. The cache stores typed
camera/timezone/orientation fields rather than raw metadata payloads.

GPS is requested through `MediaStore.setRequireOriginal` only when unredacted-location access is
currently granted. Cached coordinates are never returned when that access is absent, and the
permission-revocation hook purges coordinates and their authorization marker from Room. Corrupt
containers and security failures are explicit states, never empty-success metadata.

## Consequences
- Opening the details surface does not perform EXIF I/O until requested.
- Generation changes invalidate stale camera metadata without paths as identity.
- Revocation removes cached GPS while retaining non-location EXIF fields.
- Original EXIF time and offset strings are retained together to avoid silently applying the
  device's current timezone.
