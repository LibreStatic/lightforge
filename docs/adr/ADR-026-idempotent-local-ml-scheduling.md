# ADR-026: Idempotent local ML scheduling

- Status: Accepted
- Date: 2026-08-17

## Context

Labels, OCR, duplicate hashing, and similarity analysis can span hundreds of thousands of local items. A monolithic worker would be hard to pause, unsafe under permission loss, and likely to exceed battery and thermal budgets.

## Decision

Use unique WorkManager chains of one bounded chunk per worker. Recent work uses 50 items; full-library work uses 100, within the 50–200 calibrated limit. Every engine upserts versioned output by composite MediaStore identity and advances its checkpoint only after a complete chunk, making replay after process death idempotent.

All tasks require explicit consent, `BatteryNotLow`, and `StorageNotLow`; full scans also require charging and device idle. No task requests a network constraint. A thermal gate backs off at Android's moderate status or above before opening media. Permission loss or model-version change purges the affected derived output and checkpoint. Users can pause/resume and revoke consent with data deletion.

Cancellation is awaited before a derived-data purge so an in-flight worker cannot repopulate rows
after deletion. The process-lifetime lazy registry purges Room/AppSearch state without constructing an
inference client. Optimized builds explicitly keep ML Kit's reflectively discovered registrar classes
and no-argument constructors.

The physical performance gate runs the real bundled label, OCR, and similarity workers against the
indexed MediaStore library while the Compose benchmark surface scrolls 100,000 virtual cells. On the
reference phone (API 36, 120 Hz), five concurrent iterations recorded 2.720% aggregate positive overruns
over 14,300 frames against the 8% gate; the same-code no-ML control recorded 4.540% over 14,251 frames.
Both cohorts report `cpuLocked=false`, so the result is accepted as within-device observed UX rather
than a deterministic cross-device CPU comparison. Per-iteration distributions, device state,
benchmark JSON, result XML, and bounded diagnostic excerpts remain archived; raw Perfetto traces are
intentionally excluded from version control because the ten reproducible traces total about 350 MB.

## Consequences

Workers never hold a full ID list and can expose persisted progress. Process initialization must register a bundled local engine; an absent engine returns retry rather than invented success. Person recognition remains deferred and no empty engine is presented as a feature.

The physical run also proved that debug-only inference tests are insufficient: without the registrar
keep rule, the minified benchmark build crashed while constructing the bundled label client. Minified
physical execution is therefore part of the ML release gate.
