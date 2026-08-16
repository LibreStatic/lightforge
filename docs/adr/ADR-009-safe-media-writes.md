# ADR-009: Safe media writes

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Save as copy is default. Render to private temporary storage, insert a pending MediaStore row, copy and validate, then publish. Overwrite requires an explicit action and system authorization.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
