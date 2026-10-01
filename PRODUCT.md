# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Kotlin, Jetpack Compose, AndroidX, Android SDK (minSdk 30, compileSdk 37, targetSdk 36), Room, Paging 3, AppSearch LocalStorage, WorkManager, Media3 ExoPlayer + Transformer, ML Kit bundled, LiteRT CompiledModel, Android Keystore + BiometricPrompt, Hilt, Macrobenchmark + Baseline Profiles, Gradle Kotlin DSL with version catalog and KSP.

## Users

People with large personal photo and video libraries (100,000-250,000 items) who want a fast, private, offline-first gallery that organizes media locally without relying on cloud services. They need to browse, search, manage, edit, and protect their media entirely on-device.

## Product Purpose

Lightforge is a 100% native Android gallery app that makes managing massive local photo/video libraries fast, private, and reliable. It exists because stock gallery apps and cloud-dependent solutions do not handle 250k-item libraries well, and users increasingly want privacy without sacrificing organization. Success means: the gallery works without connectivity, handles 250k items without ANR/OOM, provides ML-powered organization (faces, places, semantic search) on-device, and never sends user data to any server.

## Positioning

A gallery that scales to quarter-million-item libraries on-device, with local ML organization, no service dependency, and no OEM privilege assumptions. Connectivity is used only to download user-controlled, signed semantic model packages; gallery data and queries never leave the device. Unlike stock gallery apps, it does not assume MANAGE_EXTERNAL_STORAGE or OEM media-management privileges. The differentiator is full local-first architecture: MediaStore as authority, Room/AppSearch/ML as rebuildable indexes, and a system-permission-respecting access model.

## Operating Context

- Libraries of 100k-250k photos and videos stored on-device or on removable SD cards.
- Permission states are fluid: full, partial (selected media), and revoked; revalidated on foreground return.
- MediaStore is the single source of truth; all indexes (Room, AppSearch, thumbnails, ML) are caches that can be rebuilt.
- Identity key is (volumeName, mediaStoreId), never absolute paths.
- Incremental sync via ContentObserver, generation cursors, and per-volume reconciliation; never full scan at startup.
- All ML (face detection, OCR, clustering, semantic search) runs locally. Semantic-search packages come only from the signed built-in catalog and are fully user-managed in Settings.
- Person/pet recognition is opt-in with pausable processing and manual correction.
- Editing is copy-by-default; overwriting originals requires explicit action and system authorization flow.
- All writes are atomic or recoverable (IS_PENDING pattern for exports).
- Adaptive layouts: compact (phone), medium (foldable half-open), expanded (tablet); foldable postures and multi-window supported.
- Tested on reference phone (API 36) and Galaxy Tab S9+ (API 36) with removable SD card.

## Capabilities and Constraints

- Timeline grid with pinch-to-zoom, anchor preservation on rotation/resize/fold.
- Album management (create, add, remove) with atomic writes.
- Full-screen viewer with large image tiling, video playback (Media3 ExoPlayer).
- Search: keyword (AppSearch), semantic (local embeddings), by person, by place.
- Face detection (ML Kit) + face recognition (LiteRT embeddings + clustering + manual correction); opt-in.
- OCR, pet recognition; local, opt-in where applicable. Object eraser and subject clip are experimental photo editor tools with basic, explicitly labelled fallback quality (no ML model yet).
- Moments/collections auto-grouping by time and location.
- Private albums (biometric-encrypted, Android Keystore).
- Collage, GIF, slideshow, motion photo playback.
- Places map (offline reverse geocoding).
- Trash, favorites, share.
- Dark theme, Material 3 theming, Dynamic Color (Material You).
- Accessibility: TalkBack, font scale, touch targets >= 48dp.
- Localization: English, Spanish, French, Portuguese, Italian, and German.
- INTERNET access is restricted to verified semantic model package delivery; no user content, metadata, query, embedding, or inference traffic.
- No MANAGE_EXTERNAL_STORAGE, no MANAGE_MEDIA, no OEM privilege assumptions.
- No misleading P2 stubs; features either work or degrade explicitly.

## Brand Commitments

- App name: Lightforge Studio (launcher label: "Lightforge"; application id `com.librestatic.lightforge`)
- Open source (Apache-2.0); commercial distribution allowed with one feature set for GitHub and Google Play (ADR-044)
- No [redacted] trade dress, code, or resources copied; functional parity with own identity
- Local-first, privacy-first positioning
- English-first strings with full localization support

## Evidence on Hand

- 41 ADRs in docs/adr/ covering all architectural decisions
- Physical device testing: reference phone (reference phone, API 36) and Galaxy Tab S9+ (reference tablet, API 36)
- Macrobenchmark results and Baseline Profiles in baselineprofile/ and benchmark/ modules
- Face identification test datasets provided by user (face_identification_test_v2.zip, face_identification_test_v3.zip)

## Product Principles

1. Local-first always: no cloud or service dependency; network access is limited to signed, user-controlled semantic model delivery.
2. MediaStore is authority; everything else is a rebuildable cache.
3. Permission states are first-class: full, partial, and revoked all work explicitly.
4. Scale without compromise: 250k items must work without ANR/OOM.
5. Honesty over illusion: no misleading stubs, no hidden errors, no invented success.

## Accessibility & Inclusion

- TalkBack screen reader support with localized labels
- Font scale up to 200% must not break layouts
- Touch targets minimum 48x48dp
- Material 3 accessibility roles and contrast variants
- Dynamic Color and dark theme as first-class schemes
