# ADR-001: Native stack and SDK

- Status: Accepted
- Date: 2026-08-16

## Context

Lightforge must remain native, offline, scalable to 100k–250k local items, and correct under public Android permissions and storage APIs.

## Decision

Use Kotlin, Android SDK/AndroidX, single-activity Compose, UDF with StateFlow, Gradle Kotlin DSL, min SDK 30, compile SDK 37.0, and target SDK 36. Web runtimes and compatibility UI layers are prohibited.

## Consequences

The decision is enforced by module boundaries, tests, build guards, and device evidence. Any incompatible change must update this ADR, affected backlog tickets, and regression coverage.
