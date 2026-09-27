# ADR-013: No Internet release

- Status: Accepted
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Offline release manifests must not contain INTERNET or related network permissions. CI inspects the merged manifest and an explicit runtime dependency group allowlist.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
