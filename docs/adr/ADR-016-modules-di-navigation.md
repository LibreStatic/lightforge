# ADR-016: Module boundaries, DI, and navigation

- Status: Accepted, amended 2026-10-28
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Features never depend on features. Pure domain interfaces isolate Android data implementations. Hilt is the composition mechanism and typed navigation stores compact keys/anchors only.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.

## Amendment (2026-10-28)

Hilt is used at the edges:

- `LightforgeApplication` is `@HiltAndroidApp` and `MainActivity` is `@AndroidEntryPoint`.
- `GalleryViewModel`, `StorageAccessViewModel`, `FolderExplorerViewModel` and others are `@HiltViewModel`.

It is not the only composition mechanism. The library runtime (`GalleryRuntime`) is assembled by hand in `GalleryViewModel.createRuntime`. The ML runtime is installed through process-wide singletons (`DetectedContentRuntime.install`, `MlRuntimeRegistry`) from `LightforgeApplication.onCreate`.

"Hilt is the composition mechanism" therefore applies only partly. The feature-boundary and navigation rules are unchanged.
