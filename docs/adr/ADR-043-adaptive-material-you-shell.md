# ADR-043: Adaptive Material You shell

Date: 2026-08-20

## Status

Accepted

## Context

The native interface relied on width-only branching, duplicated safe-area padding,
fixed-height nested grids, and horizontally unbounded action rows. The result felt
heavy and could clip controls on phones, foldables, tablets, and resizable
Android/ChromeOS windows.

## Decision

Use one shared adaptive policy in `core:designsystem`:

- compact windows below 600 dp use bottom navigation and 16 dp gutters;
- medium windows from 600–839 dp use a navigation rail and 24 dp gutters;
- expanded windows from 840 dp use a navigation rail, 32 dp gutters, and may use
  two-pane content;
- a vertical separating fold enables side-by-side content regardless of width class;
- fold orientation and bounds are retained in the shared adaptive model so panes
  can avoid the physical hinge; horizontal folds never force a side-by-side row;
- Android 12+ uses Material 3 dynamic color, with the existing deterministic
  light/dark schemes as the offline fallback.

Each destination owns its top app bar. The outer shell owns normal system insets,
while immersive editors and the private album own their safe-drawing insets explicitly. Content uses adaptive
grids, bounded reading widths, lazy horizontal rows for optional chips/actions,
and stacked controls where localized labels need more space.

## Consequences

- Navigation and spacing change predictably as an Android window is resized.
- Fold posture orientation and bounds are observed through Jetpack WindowManager
  without introducing a web or network dependency.
- Dynamic color follows the user's wallpaper on supported devices while tests and
  older Android versions retain deterministic fallback colors.
- Horizontally scrollable discovery rows may show a partially visible trailing
  item as an intentional scroll affordance; primary actions and form controls must
  not depend on horizontal scrolling.
- Emulator evidence covers representative phone, foldable, tablet, and desktop
  window sizes; OEM/physical-device validation remains a separate release check.
