# ADR-018: RecyclerView escape hatch for the timeline

- Status: Rejected after physical prototype
- Date: 2026-08-17
- Evaluates: ADR-004 escape hatch

## Context

The native Compose `LazyVerticalGrid` spike was measured repeatedly on the physical reference reference phone (`reference phone`, API 36, 120 Hz). The provisional high-refresh gate is aggregate positive frame overruns of at most 8%.

The first 100k run passed at 7.191%, but the archived repeat runs failed at 9.356% with memory tracing and 12.596% with frame-only tracing. The frame-only failure had 1,594 overruns across 12,655 frames, CPU frame duration P95 5.980 ms, and no thermal throttling. A 250k frame-only run passed at 1.544%, showing that item-count virtualization works but the 100k journey does not meet the gate reproducibly.

## Evaluated proposal

Use a native `RecyclerView` with `GridLayoutManager` only for the timeline grid. Compose would remain the primary UI for the route, headers, navigation, states, overlays, and every other surface.

The prototype exposed a virtual item count, stable long IDs, a bounded recycled-view pool, fixed-size square cells, and anchor-plus-offset restoration when span density changed.

## Result and decision

The first RecyclerView 100k run produced 95.010% positive frame overruns and CPU P95 16.058 ms. Removing per-cell accessibility descriptions and adding view caching/prefetch did not materially improve it: the repeat produced 94.939% overruns and CPU P95 15.828 ms, with no thermal throttling.

Reject this RecyclerView prototype and retain Compose. A renderer may claim the provisional gate only under the documented reproducible physical protocol; default-governor cohorts remain separate product-risk evidence.

## Consequences

- RecyclerView is not shipped by this decision and its dependency/prototype code is removed.
- Compose remains the timeline implementation.
- Both renderer datasets remain archived, including failed measurements.
- A future timeline renderer change requires new profiling evidence and an ADR update.

## Follow-up physical evidence

On the reference tablet/API 36 tablet with the display actively running at 120 Hz, Compose passed two 100k cohorts at 1.517% and 1.897% aggregate positive overruns and one 250k cohort at 3.031%; thermal throttle sleep was zero. This supports retaining Compose and indicates that item-count virtualization is not the limiting factor.

The follow-up reference phone default-governor cohort again varied widely at 16.597%, confirming DVFS/governor noise rather than permitting the failed runs to be discarded. The gate protocol therefore enables Android fixed-performance mode before each cohort and restores it afterward. Three independent reference phone 100k cohorts passed at 1.674%, 1.707%, and 1.780%, all with zero thermal throttle sleep.

AndroidX continued to report `cpuLocked=false`; this is recorded because it is a cpufreq heuristic and is not treated as proof of fixed-mode state. Default-governor results remain visible as non-gating UX-risk evidence. A separate physical pinch benchmark passed after fixing anchor capture, and the final anchor-fixed code passed another 100k scroll cohort at 0.071%. M0-T07 is complete without weakening the 8% threshold.
