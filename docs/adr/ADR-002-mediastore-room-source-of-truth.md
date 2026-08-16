# ADR-002: MediaStore and Room ownership

- Status: Accepted
- Date: 2026-08-16

## Context

UGallery must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

MediaStore owns shared media. Room, AppSearch, thumbnails, and ML artifacts are reconstructible indexes. Every media foreign key uses volume name plus MediaStore ID.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
