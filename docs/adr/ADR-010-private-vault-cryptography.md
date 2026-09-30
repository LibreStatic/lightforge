# ADR-010: Private vault cryptography

- Status: Proposed
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

P2 vault work uses app-private chunked authenticated encryption, a bounded Keystore key hierarchy, BiometricPrompt, no plaintext shared caches, copy-verify-delete ordering, and independent cryptographic review.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
