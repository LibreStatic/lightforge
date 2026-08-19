# ADR-042: Native Material icon system for visual parity

Date: 2026-08-19

## Status

Accepted

## Context

The HTML mockup uses a consistent inline SVG icon vocabulary, but the native app had no drawable icons and root navigation used placeholder text glyphs (`●` and `■`). Viewer and editor action rows also used text-only buttons, creating overflow risk and failing the visual contract.

## Decision

Use the Compose `material-icons-extended` artifact through the shared `core:designsystem` module. Expose the product vocabulary via `GalleryIcons` (`Back`, `Grid`, `Search`, `Collections`, `Share`, `Edit`, `Heart`, `Trash`, editor controls, and system status icons). Feature modules consume this shared object instead of importing individual icons ad hoc.

Use icon-plus-label actions for compact surfaces, with a four-column viewer action bar and overflow menu for secondary actions. Root navigation uses real image/collections/search icons in both NavigationBar and NavigationRail.

## Consequences

- Icons are bundled locally; no runtime network or font dependency is introduced.
- All icons inherit Material 3 tint and accessibility descriptions.
- Material filled symbols are the closest native equivalent to the mockup's stroked SVGs; exact pixel parity remains unclaimed.
- The shared mapping makes future visual-token changes auditable in one file.
