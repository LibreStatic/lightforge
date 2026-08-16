# ADR-015: HDR and codec fallback

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Probe decoder/encoder capabilities per device, prefer passthrough, validate outputs, preserve HDR only end-to-end, and present explicit SDR/unsupported fallbacks without claiming success early.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
