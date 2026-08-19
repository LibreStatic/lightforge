# ADR-036: Home-screen widget with local photo rotation

Date: 2026-08-19

## Status

Proposed

## Context

M6-T08 requires a home-screen widget that rotates local photos.
The widget must not use network, must not expose private/trash photos,
and must be battery-reasonable.

## Decision

Use a standard AppWidgetProvider with RemoteViews:
- Photo URIs are queried from MediaStore (images only, IS_TRASHED=0)
- Private album items are not in MediaStore, so they are inherently excluded
- Results are cached in SharedPreferences for 30 minutes to avoid repeated queries
- Rotation index persists across widget refreshes
- updatePeriodMillis=1800000 (30 min) is battery-reasonable
- Widget is resizable (horizontal|vertical)
- Clicking the widget advances to the next photo
- No network permission in the widget module manifest

## Consequences

- Widget only shows photos accessible via MediaStore (no private album)
- Battery impact is minimal: one lightweight query every 30 minutes
- Widget responds to resize events by re-rendering at the new size
- No cloud or network dependency
