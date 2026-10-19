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

- showing items hidden by `.nomedia`, including a "temporarily show hidden" switch that turns
  itself off after the app has been in the background for 5 minutes, in the Folders explorer and
  merged into the Photos timeline by the library sort order;
- hiding and unhiding folders by creating or removing `.nomedia`;
- same-volume moves and renames with `File.renameTo`, followed by a
  `MediaScannerConnection.scanFile` of both paths;
- folder create, rename, move, copy, delete (non-empty folders included), hide and unhide without
  the SAF picker;
- optionally, deleting folders left empty (off by default).

Folder operations never touch the `Android/` tree (any case): `Android/media/<package>`
belongs to another app, which would recreate an empty folder and orphan its files. The engine
refuses hide, create, rename, move and delete there and the explorer shows no menu on those
cards. A renamed or moved folder takes its folder rules (excluded or included, by path and by
bucket) with it, so an excluded folder stays excluded.

Files can be hidden by name: the selection bar's "Hide by name" (mode on) renames each file with a
leading dot (`IMG.jpg` becomes `.IMG.jpg`) and records the new path as `HiddenKind.DotPrefix`.
Folders are never hidden that way (a `.nomedia` does that), and a name that is already dotted, a
taken target or a missing permission is refused. The hidden file appears in the hidden overlay and
Settings > Storage access lists it, where unhide renames it back; a dot-prefixed record of a folder
written by an earlier build is still undone. Unhide works for hides this app recorded; a `.nomedia`
made by another app is never deleted.

Scanned folders (the plan's "included folders"): Storage access lists folders the user added with the system picker. Adding one
asks the media scanner to index every photo and video below it (`AdvancedStorage.scanFolderTree`,
recursive, at most 50,000 files, never the Android data and obb trees, never `.pending-` or
`.trashed-` placeholders) and stores the absolute path in `OperationSettings.scannedFolders`, which
is part of the settings export. Every included folder is scanned again whenever the mode turns on.
Removing a folder from the list only stops that rescan; the library items stay.

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
  registry is not empty. The unhide prompt (`AdvancedStorageStatus.pendingUnhide`) is hosted at the
  app root, so it appears on the first launch of such a build, not only inside Settings. "Keep
  hidden" is remembered per folder, so the prompt returns only for folders hidden after that.

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

## Shipped scope

Shipped behind the mode (`AdvancedStorageStatus`, active only when supported, enabled and
granted, re-read on every resume): hidden items overlay (read live, no Room column), temporary
show-hidden with the 5-minute background timeout and app-lock gate, folder Hide/Unhide, folder
create, rename, move, copy and delete, a destination picker, same-volume batch rename that keeps
media identity, optional deletion of empty folders, and the revoked notice with unhide. Standard
top-level folders (DCIM, Pictures, Download, Movies, Music, Podcasts, Ringtones, Alarms,
Notifications, Documents, Audiobooks, Recordings, Screenshots) and the volume root cannot be hidden,
renamed, moved or deleted, and the Android tree is neither a source of folder operations nor a
destination for moved or copied files (`Android/media/<package>` is wiped with its app).
Mode-independent fixes: folder counts use accessible rows only, and the physical album lists drop
folders whose rows are all inaccessible on a mounted volume while keeping an unmounted volume's
folders as unavailable. A row MediaStore stops returning is deleted only when its file is also gone
from disk; if the file still exists the row is marked inaccessible and keeps its favourites and
albums. That probe is a `java.io.File` check, so how much of a foreign file the platform lets a
non-manager app see is not measured here.

Section 3 checks 1 and 3 are automated: CI builds with `-Plightforge.allFilesAccess=false` and
runs `tools/verify_offline_release.py --no-all-files`, which also asserts that
`MANAGE_EXTERNAL_STORAGE` is declared only in `core/storage-advanced`. The default build runs
with `--expect-all-files`.

Moves and copies (Simple Gallery gap plan sections 7.8 and 7.10):

- With the mode on, move and copy go by path. Each item is checked against its current MediaStore
  row before anything is touched, and a cross-volume or refused-rename original is retired only
  after its copy is byte-compared with the item's own content URI.
- Favourites, albums and archive state follow a moved item (`carryState`). A journal file
  (`FileTransferJournal`) records each transfer, and `recoverInterrupted` finishes or rolls back
  an interrupted one when the mode becomes active.
- With the mode off, the same folder picker replaces SAF: a same-volume move updates
  `RELATIVE_PATH` after one `MediaAction.Write` approval (the row keeps its identity), a copy
  inserts a row and streams the bytes, and a cross-volume move copies and then requests
  `MediaAction.Delete` for the originals. Limits: a volume root is not a destination, and a request
  above 500 items is split into chunks whose counters are summed.
- New folder in the picker with the mode off names a folder that does not exist yet (not at a volume
  root); the bar says where it will be created, confirming chooses it and the system creates it when
  the first item is written into it. A destination the library does not list (an SD card folder, a
  cloud provider) is reached with the "Choose another location" action, which opens the system folder
  picker; the items are copied into it one by one, each verified, and a move then asks the system to
  delete exactly the originals that arrived. Folders are never moved this way: folder operations need
  the mode.

Index: the Animated filter reads an `isAnimated` column set at index time from the file header (GIF
by type; WebP, APNG, AVIF and HEIF sequences by their markers). Migration 32 to 33 marks existing GIFs
and the MediaStore checkpoint's index format suffix (`+lf3`) forces one full reconciliation, which
rewrites any unchanged row whose sniffed flag turned on, so an upgraded library is backfilled.

Selection and rules (7.8, 7.9): folder cards and files are ticked together and one bar
(`GallerySelectionBar`, counting both) replaces the file bar while a folder is ticked. Copy, move and
delete are inline; pin, exclude/include, hide/unhide, add to album, rename (one folder), choose or reset
the cover (one folder), launcher shortcut, properties and select all are in its overflow. What each
selection allows is the pure function `selectionCapabilities`: copy, move, rename and delete need the
mode and never touch the Android tree, and move, rename and delete refuse standard top-level folders. A
mixed move or copy runs the files and then the folders through one destination picker (`Mixed`
request) with one result message; a mixed delete also moves the ticked files to the Trash after the
confirmation. Add to album adds everything below the folders, subfolders and archived items included. A
dimmed excluded card has a badge that includes it again, and the card menu has Exclude/Include. Covers
are stored in settings by folder key (`folders.covers`, in the settings export); the shortcut is a
pinned launcher shortcut that opens the folder through an explicit intent to the exported activity.
Pins, covers and the show-even-if-hidden rule follow a folder when it is renamed or moved.

Explorer (plan section 7), all built: breadcrumb long-press path entry (7.3), compaction of
single-child chains (7.4, switchable), per-folder sort, view and filter (7.6), search inside a
folder and below it plus the `in:<path>` token of the global search (7.7), selecting files, alone or
with folders (7.8), dimming excluded folders with an Exclude/Include shortcut (7.9), pinned folders,
restored scroll position and reopening the last folder, also after process death (7.12). The Photos
tab (6.13 P1) has multi-select type chips, a grouping control (day, month, year, none), the temporary
show-hidden toggle in its overflow, and hidden items merged into the timeline; a hidden folder can
carry the "Show in Photos even if hidden" rule. Hidden items in Photos carry a hidden badge, follow the library order including the grouping, and the Raw and Animated chips apply to them as to library items (Animated reads the file header). They open in an external viewer and
cannot be selected or edited, because they are not in the library.

Large screens (7.11): from 720 dp of content width the explorer shows a folder tree on the left and
the folder on the right. The tree lists every volume and, below an open row, its folders (chains merged like
the cards); opening a folder opens the way to it, and what the user opened or closed is kept in the saved
state. Folders, files and the ticked selection can be dragged (a mouse or stylus at once, touch by long press
on a ticked item) and dropped on a tree row, a breadcrumb segment or a folder card: a drop is a move that
follows the destination picker rules (`ExplorerDragPayload.canDropOn`) and ends with the same result
message; folders need the mode, files do not. The keyboard moves the focus with the arrows, opens with Enter,
goes up with Backspace or Alt+Up, selects all with Ctrl+A, deletes with Delete (folders ask first, files go
to the Trash) and clears the selection with Escape; Right and Left open and close a focused tree row. The
scroll position of every folder is saved per folder in the saved state and is restored once the paged files
are loaded, so it survives process death.

Per-folder options now cover the files as well as the folders (7.6): file sort (date, name, size),
type filter (all, photos, videos) and grid or list layout are stored with the folder in
`folderViewOptions`, applied to the folder's file grid and to the viewer scope opened from it. An
album screen keeps its own filter and sort per album in the same map (`AlbumViewPrefs`). The start
screen (Photos, Albums or Folders) is a setting applied once per cold start when no external intent
is being handled. A folder that exists only on the disk (just created, or empty) stays openable and
nestable because the explorer lists the directories along the whole path of the open location.
Hidden items the library already lists are dropped from the overlay, so nothing appears twice in
Photos or in a folder.

Not built: the rename-against-verified-copy benchmark. The old SAF tree launch stack (`VerifiedMoveController` and its tree
launchers) is no longer started by the folder picker and remains only because device tests use it.
The scrubber histogram does not count hidden items merged into Photos. The viewer, system
integration, photo editor, formats and slideshow sprints of the Simple Gallery gap plan are outside
this change.
