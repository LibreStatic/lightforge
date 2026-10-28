# Architecture

Lightforge is a native Android app split into Gradle modules: `app/` (shell, navigation host, DI and manifest), `core/*` (shared engines: data, Room database, MediaStore, thumbnails, search, ML, selection, editing, RAW, security, design system) and `feature/*` (one module per surface). MediaStore is the source of truth; Room, AppSearch, thumbnails and every ML index are rebuildable caches.

Documentation hierarchy: [PRODUCT.md](../../PRODUCT.md) → [ADRs](../adr/) → architecture (this folder) → code and tests.

## Contents

- [Flow documentation](../flows/README.md): the cross-module flows (sync, startup, media actions, storage access, ML scheduling, model packages, private album, local transfer, backup and folder mirroring), with Mermaid diagrams checked against the code.
- [UI route and state matrix](UI_STATE_MATRIX.md): production surfaces and the state families each one must handle.
- [Architecture decision records](../adr/): one file per decision, ADR-001 to ADR-046.
- [User guide](../USER_GUIDE.md): what each feature does from the user's side.
