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

Folder operations never touch the `Android/` tree (any case): `Android/media/<package>`
belongs to another app, which would recreate an empty folder and orphan its files. The engine
refuses hide, create, rename, move and delete there and the explorer shows no menu on those
cards. A renamed or moved folder takes its folder rules (excluded or included, by path and by
bucket) with it, so an excluded folder stays excluded.

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
- Switching the mode off or revoking the permission offers "Unhide the folders Lightforge
  hid". Flipping the build flag does not (see "We switch it off in the build").
- The app never records or deletes a `.nomedia` that it did not create.
- If a hide cannot be undone (for example, the permission is already revoked), Settings
  keeps listing it so it can be resolved by hand.

**We switch it off in the build (flag).**

- One Gradle property, `lightforge.allFilesAccess` (default `true`), generates
  `BuildConfig.ALL_FILES_ACCESS`.
- With the property set to `false`:
  - an overlay manifest removes `MANAGE_EXTERNAL_STORAGE` from the merged manifest;
  - the app binds `FileAdvancedStorage` in recovery-only mode: `isSupported` and `isGranted`
    are false, so no mode, no file operation and no permission row exist, but it still reads
    the registry;
  - the Settings rows for the mode do not exist.
- A Play build without the permission therefore needs no code change.
- **Recovery after the flag flips (implemented):** the recovery-only instance lists the folders
  an earlier build hid (`hiddenByApp`), and unhides them with the owner delete of the app's own
  `.nomedia` or a per-folder SAF tree grant (`unhideWithTree`); neither needs
  `MANAGE_EXTERNAL_STORAGE`. The Storage access page shows the hidden-folders list whenever the
  registry is not empty, and on every launch with a non-empty registry the Settings unhide
  prompt is raised (`AdvancedStorageStatus.pendingUnhide`).

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
   rows for the mode are absent, the tests pass, and a registry written by an earlier build
   is still listed and unhidable.
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
  - Observed on 2026-10-18, Android 13 (API 33) x86_64 emulator `sdk_gphone64_x86_64`
    (emulator-5556), `AdvancedStorageDeviceTest`, all passing:
    - Hiding a folder with `.nomedia` removes the image row and leaves a `MediaStore.Files` row
      with `media_type=0` and the same `_ID` (`_ID` 1000001569 before and after);
      `queryHiddenMedia()` returned that `_ID`. Unhiding gave `media_type=1` and the same `_ID`.
    - A file rename keeps the `_ID` and updates `RELATIVE_PATH`; so do a directory move and a
      directory rename (`_ID` 1000001569 throughout).
    - Hide one folder, deny `MANAGE_EXTERNAL_STORAGE` (the process is killed), run the app again:
      `isGranted()` is false, `queryHiddenMedia()` is empty, hide returns `NotGranted`, and unhide
      returned `Done` with the owner delete of the app's own `.nomedia` (marker present before,
      gone after), not `NeedsTreeGrant`. The check hid one folder, not two; the registry path for
      several folders is covered by the unit tests. Not measured: other devices or OEM builds.
  - Benchmark rename against verified copy on about 1000 photos on the `remoteacceptance`
    build: still open.

## Shipped scope and deferred follow-ups

Shipped behind the mode (`AdvancedStorageStatus`, active only when supported, enabled and
granted, re-read on every resume): hidden items overlay (read live, no Room column), temporary
show-hidden with the 5-minute background timeout and app-lock gate, folder Hide/Unhide, folder
create, rename, move and delete, a destination picker replacing SAF for Move/Copy, same-volume
batch rename that keeps media identity, optional deletion of empty folders, and the revoked
notice with unhide. Standard top-level folders (DCIM, Pictures, Download, Movies, Music,
Podcasts, Ringtones, Alarms, Notifications, Documents, Audiobooks, Recordings, Screenshots) and
the volume root cannot be renamed, moved or deleted, and the Android tree is neither a source of
folder operations nor a destination for moved or copied files (`Android/media/<package>` is wiped
with its app). Mode-independent fixes: folder counts use accessible rows only, and the physical
album lists drop folders whose rows are all inaccessible on a mounted volume (ghosts of hidden,
moved or renamed folders) while keeping an unmounted volume's folders as unavailable. A row
MediaStore stops returning is deleted only when its file is also gone from disk; if the file still
exists (a `.nomedia` made by another app turns the row into one the app cannot read) the row is
marked inaccessible and keeps its favourites and albums. That second probe is a `java.io.File`
check, so how much of a foreign file the platform lets a non-manager app see on a given device is
not measured here; where it cannot see the file the previous behaviour (delete) applies.

Section 3 checks 1 and 3 are automated: CI builds with `-Plightforge.allFilesAccess=false` and
runs `tools/verify_offline_release.py --no-all-files`, which also asserts that
`MANAGE_EXTERNAL_STORAGE` is declared only in `core/storage-advanced`. The default build runs
with `--expect-all-files`.

Move and copy by path check each item against its current MediaStore row (`_ID`, folder and
name) before anything is touched, and a cross-volume or refused-rename original is retired only
after its copy is byte-compared with the item's own content URI. This path is not journaled
like `VerifiedMoveController`, which is built around one item and a SAF tree: a crash before
the delete approval leaves a copy beside the original, never a lost file. Journaling a batch is
deferred.

Reduced scope: the temporary show-hidden switch reaches the Folders explorer only. The Photos
overflow entry was removed because it did nothing; showing hidden rows in the Photos timeline is
deferred.

Delivered from plan section 7.4: chain compaction ("A/B/C" cards and crumbs) can be switched off
from the Device folders overflow (`compactFolderChains`, default on, persisted and exported).

Deferred (Simple Gallery gap plan section 7, explorer; numbers follow the plan): breadcrumb
long-press path entry (7.3), per-folder sort, view and filter (7.6), search inside a folder and the
`in:` token (7.7), selecting files, alone or with folders (7.8), dimming or badging excluded
folders and an Exclude shortcut (7.9), a destination picker that replaces SAF when the mode is
off, with batched MediaStore moves (7.10), tree pane, drag and drop and keyboard navigation on
large screens (7.11), restoring scroll after process death and reopening the last folder (7.12). Each folder
keeps its own scroll position while the screen is open. Section 6.13 P1 items also deferred: the
multi-select type filter, an index-time Animated flag (Animated is still MIME-based), a grouping
control in the Photos overflow and the "show even if hidden" folder rule. Album covers, pins
and other path-keyed settings are not rewritten on folder rename.

Deferred: the rename benchmark, included
folders that MediaStore does not index, a dot-prefixed-name view, and the viewer, system
integration, photo editor, formats and slideshow sprints of the Simple Gallery gap plan.
