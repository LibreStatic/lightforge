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

Reject this RecyclerView prototype and retain Compose. M0-T07 remains open because the Compose 100k result also failed repeatability; no renderer may claim the gate until further profiling or a materially different implementation passes.

## Consequences

- RecyclerView is not shipped by this decision and its dependency/prototype code is removed.
- Compose remains the timeline implementation, but M0-T07 is not complete.
- Both renderer datasets remain archived, including failed measurements.
- A future timeline renderer change requires new profiling evidence and an ADR update.

## Follow-up physical evidence

On the reference tablet/API 36 tablet with the display actively running at 120 Hz, Compose passed two 100k cohorts at 1.517% and 1.897% aggregate positive overruns and one 250k cohort at 3.031%; thermal throttle sleep was zero. This supports retaining Compose and indicates that item-count virtualization is not the limiting factor.

M0-T07 nevertheless remains open because the same implementation still has failed archived reference phone repeats. The tablet result does not authorize weakening the gate or ignoring the phone reference; cross-device repeatability must be resolved first.
