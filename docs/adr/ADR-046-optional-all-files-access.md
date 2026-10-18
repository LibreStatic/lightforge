# ADR-046 — Optional all-files access and media management

Status: accepted  
Date: 2026-10-18  
Supersedes: the `MANAGE_EXTERNAL_STORAGE` / `MANAGE_MEDIA` / `.nomedia` exclusions in
`PRODUCT.md`, `README.md`, `store/play/declarations.md` and `docs/SIMPLE_GALLERY_PARITY.md`.

## Context

Lightforge reads the library only through MediaStore and changes files only through
platform write, trash and delete requests, plus SAF trees for copies. That keeps the
permission surface small, but it rules out features that people expect from a file-oriented
gallery such as Simple Gallery or Fossify Gallery:

- Folders hidden with `.nomedia` cannot be shown, and folders cannot be hidden or unhidden.
- Moving files within the same volume copies every byte and then deletes the original,
  with a confirmation dialog for each batch.
- Folder operations (create, rename, move, delete an empty folder) need a SAF tree picker.
  SAF is slow by design: every `DocumentsContract` query is a binder call into
  `ExternalStorageProvider`, and moving between trees is always copy-then-delete.

Simple Gallery and Fossify Gallery ship `MANAGE_EXTERNAL_STORAGE` on Google Play as media
file managers. Lightforge's backup, SFTP/SMB mirroring, LAN migration and Private Album work
through MediaStore and SAF and do not use the permission, so they are not its justification.

## Decision

MediaStore stays the authority and the index. Nothing changes for a user who does not opt
in.

### 1. `MANAGE_MEDIA`, always declared

The app declares `MANAGE_MEDIA` (Android 12+ special access, "Media management").

- When the user grants it, the platform approves `createWriteRequest`,
  `createTrashRequest` and `createDeleteRequest` without showing a dialog.
- The existing request flows are unchanged. They simply complete without a prompt.
- `MediaManagementAccess` in `:core:mediastore` exposes `canManageMedia` and the intent
  for the system screen.
- Settings offers the grant. It is never required.

### 2. Opt-in advanced storage mode (`MANAGE_EXTERNAL_STORAGE`)

The user turns the mode on in Settings, sees an explanation, and grants all-files access
on the system screen. The mode enables:

- showing items hidden by `.nomedia` (dot-prefixed names are deferred), including a
  "temporarily show hidden" switch that turns itself off after the app has been in the
  background for 5 minutes;
- hiding and unhiding folders by creating or removing `.nomedia`;
- same-volume moves and renames with `File.renameTo`, followed by a
  `MediaScannerConnection.scanFile` of both paths;
- folder create, rename, move, hide and unhide without the SAF picker; folder copy and
  deleting non-empty folders are not offered;
- optionally, deleting folders left empty (off by default).

Not part of the shipped mode (deferred): included folders that MediaStore does not index and
hiding with a dot prefix. Unhide works for hides this app recorded; a `.nomedia` made by
another app is never deleted.

Cross-volume moves keep the verified copy path: size and hash check, then delete. Every
batch operation uses the same strategy: rename when the capability is present, otherwise
copy and verify. Verified copy stays the default path and is tested on its own.

Revoking the permission at any time returns the app to MediaStore mode on the next
capability check. The index is not touched. `Android/data` and `Android/obb` remain
inaccessible; that is a platform rule.

### 3. Reversibility

The mode must be removable at any time, whether because Play rejects it, users dislike it,
or we drop it. There are three levels of removal, and each must work without a migration
or broken data.

**The user switches it off (runtime).**

- The app records every `.nomedia` it creates in its own registry, a JSON file in
  `noBackupFilesDir/advanced-storage/`.
- Switching the mode off, revoking the permission, or flipping the build flag offers
  "Unhide the folders Lightforge hid".
- The app never records or deletes a `.nomedia` that it did not create.
- If a hide cannot be undone (for example, the permission is already revoked), Settings
  keeps listing it so it can be resolved by hand.

**We switch it off in the build (flag).**

- One Gradle property, `lightforge.allFilesAccess` (default `true`), generates
  `BuildConfig.ALL_FILES_ACCESS`.
- With the property set to `false`:
  - an overlay manifest removes `MANAGE_EXTERNAL_STORAGE` from the merged manifest;
  - the app binds `NoOpAdvancedStorage`;
  - the Settings rows do not exist.
- A Play build without the permission therefore needs no code change.
- Follow-up, not implemented yet: on the first launch after the flag flips, read the registry
  (the module is still compiled in) and offer to unhide each folder through a per-folder SAF
  tree grant. Until then, the safe order is to ship a build that unhides on mode-off before
  shipping one with the flag off.

**We delete it from the code (module).**

- `:core:storage-advanced` is the only module that:
  - declares `MANAGE_EXTERNAL_STORAGE`;
  - uses `java.io.File` on shared storage;
  - creates or deletes `.nomedia`.
- The rest of the app depends only on the `AdvancedStorage` interface in
  `:core:mediastore`, whose default implementation is a no-op.
- Removing the feature means deleting the module, its dependency, and the Hilt binding.
- No Room table or column depends on the mode.
- Its preferences are optional fields in the settings export, and imports into a build
  without the feature ignore them.

Before shipping, the following checks must pass:

1. With the flag off, the merged manifest has no `MANAGE_EXTERNAL_STORAGE`, the Settings
   rows are absent, and the tests pass.
2. Hide two folders, revoke the permission, and reopen the app: unhiding is offered and
   works.
3. Export settings with the mode on and import them into a build with the flag off: there
   is no error.

### 4. Distribution

- **GitHub and F-Droid builds:** declare the permission.
- **Google Play:** submit the "All files access" declaration as file management, which is
  what the mode does: showing `.nomedia` folders, hiding and unhiding folders, in-place moves
  and renames, and folder operations. Do not cite backup, sync, migration or encryption; they
  do not use the permission.
- **If Play rejects the declaration:**
  - ship the Play build with `-Plightforge.allFilesAccess=false`;
  - offer per-folder SAF tree grants for hidden folders as the slower fallback.
- Code decides by runtime capability, never by flavor.

## Consequences

- PRODUCT.md, README.md and the Play declarations no longer promise "no
  `MANAGE_EXTERNAL_STORAGE`, no `MANAGE_MEDIA`". They describe the default MediaStore model
  and the opt-in mode.
- Privacy is unchanged: all-files access is used only on the device, and no file data
  leaves it beyond the features that already send data to user-chosen destinations.
- Hidden-item visibility should be protected by the existing app lock (biometric or device
  credential) once that UI exists.
- Open checks:
  - `media_type=0` rows, owner deletion of its own `.nomedia` without the permission and
    `_ID` preservation across a FUSE rename are measured by
    `core/storage-advanced/src/androidTest/.../AdvancedStorageDeviceTest.kt` (run with
    `ANDROID_SERIAL=emulator-5556`). The app reads hidden media from `MediaStore.Files` through
    `AdvancedStorage.queryHiddenMedia()` and does not crawl the disk; when a device does not
    report those rows, hidden items simply do not appear.
  - Benchmark rename against verified copy on about 1000 photos on the `remoteacceptance`
    build: still open.

## Shipped scope and deferred follow-ups

Shipped behind the mode (`AdvancedStorageStatus`, active only when supported, enabled and
granted, re-read on every resume): hidden items overlay (read live, no Room column), temporary
show-hidden with the 5-minute background timeout and app-lock gate, folder Hide/Unhide, folder
create, rename, move and delete, a destination picker replacing SAF for Move/Copy, same-volume
batch rename that keeps media identity, optional deletion of empty folders, and the revoked
notice with unhide. Mode-independent fixes: a hidden row becomes inaccessible instead of being
deleted, and folder counts use accessible rows only.

Section 3 checks 1 and 3 are automated: CI builds with `-Plightforge.allFilesAccess=false` and
runs `tools/verify_offline_release.py --no-all-files`, which also asserts that
`MANAGE_EXTERNAL_STORAGE` is declared only in `core/storage-advanced`. The default build runs
with `--expect-all-files`.

Deferred: SAF tree-grant fallback after the flag flips, the rename benchmark, included
folders that MediaStore does not index, a dot-prefixed-name view, and the viewer, system
integration, photo editor, formats and slideshow sprints of the Simple Gallery gap plan.
