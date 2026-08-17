# ADR-026: Idempotent local ML scheduling

- Status: Accepted
- Date: 2026-08-17

## Context

Labels, OCR, duplicate hashing, and similarity analysis can span hundreds of thousands of local items. A monolithic worker would be hard to pause, unsafe under permission loss, and likely to exceed battery and thermal budgets.

## Decision

Use unique WorkManager chains of one bounded chunk per worker. Recent work uses 50 items; full-library work uses 100, within the 50–200 calibrated limit. Every engine upserts versioned output by composite MediaStore identity and advances its checkpoint only after a complete chunk, making replay after process death idempotent.

All tasks require explicit consent, `BatteryNotLow`, and `StorageNotLow`; full scans also require charging and device idle. No task requests a network constraint. A thermal gate backs off at Android's moderate status or above before opening media. Permission loss or model-version change purges the affected derived output and checkpoint. Users can pause/resume and revoke consent with data deletion.

## Consequences

Workers never hold a full ID list and can expose persisted progress. Process initialization must register a bundled local engine; an absent engine returns retry rather than invented success. Person recognition remains deferred and no empty engine is presented as a feature.
