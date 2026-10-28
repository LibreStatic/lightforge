# Flow documentation

The flows below cross several modules and cannot be understood by reading a single file. Each document has the same structure: purpose, where the code lives, Mermaid diagrams with step notes, failure and edge cases, invariants and related ADRs. Every name in a diagram exists in the code; where the code and an ADR disagree, the document describes the code and records the difference in a note.

Diagrams use only `flowchart`, `sequenceDiagram` and `stateDiagram-v2` and are validated against Mermaid 12.2.0, the version GitHub renders with.

| Flow | What it covers |
|---|---|
| [App startup](app-startup.md) | Process init, ML engine registration, splash and onboarding choice, first library refresh. |
| [Library sync: MediaStore to Room](library-sync.md) | Resumable initial scan, generation-based incremental sync, observer coalescing, search follow-up. |
| [Storage access: permission states and advanced mode](storage-access.md) | Full, selected and revoked access, foreground revalidation, opt-in all-files advanced mode. |
| [Media actions: safe writes, trash and system confirmation](media-actions.md) | Chunked system-confirmed actions, trash, pending-file exports with verification. |
| [On-device ML scheduling](ml-scheduling.md) | WorkManager chunk chains, checkpoints, retries and waits, People stages, duplicate hashing. |
| [Model packages: download and verification](model-downloads.md) | Shared download queue and gate, pinned SHA-256 files, signed semantic packages. |
| [Private album: keys, unlock and export](private-album.md) | Key protection journal, unlock session, encrypted import, export publication and recovery. |
| [Local network transfer](local-sharing.md) | Pinned-TLS pairing by invitation, transfer protocol, receiver verification and import. |
| [Backup to your own server (SFTP and SMB)](remote-backup.md) | Destinations and host-key pinning, archive upload and download, verified no-replace publication. |
| [Folder sync to your own server (OwnSync)](own-sync.md) | Folder grant, plan review, additive or managed-mirror publication with quarantine. |

See also the [architecture overview](../architecture/README.md) and the [ADRs](../adr/).
