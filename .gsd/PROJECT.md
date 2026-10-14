# Incremental Hub — Playable Hub Project

## What This Is

A native Android launcher for web-based incremental games, built on AndroidX WebKit named-profile isolation. The application provides a stable, isolated WebView session for each game, with native menus, progress capture, lifecycle recovery, and game switching — all without cross-game session or profile leakage.

## Core Value

**Resume is the primary action.** A user should be able to tap a game card and continue playing exactly where they left off, even after weeks away, process death, or renderer failure.

## Project Shape

- **Complexity:** complex
- **Why:** Cross-cutting concerns (session lifecycle, WebView stability, bridge validation, process recovery) span all slices; no single slice can be completed in isolation from the others' contracts.
- **Web stack:** Android Jetpack Compose + Material 3; AndroidX WebKit for WebView profile isolation

## Current State

M001 (Playable Hub) is complete. The milestone delivered a usable Playable Hub with launcher, stable WebView sessions, native menu, capture cadence, process recovery, renderer crash recovery, and game switching isolation.

### M001 Completion Summary
- 4 slices complete (S01-S04), 16 tasks complete
- 190 unit tests passing across 9 test classes
- assembleDebug BUILD SUCCESSFUL
- All 16 requirements covered (9 validated in M001, 7 were already validated, 3 deferred, 3 out-of-scope)
- All cross-slice boundaries honored
- Four verification classes pass: Contract, Integration, Operational, UAT

## Architecture / Key Patterns

- **SessionController** is the sole owner of session lifecycle and transitions via a nine-state sealed state machine
- **WebView lifetime** outside transient Compose branches; AndroidView host with explicit lifecycle via remember/DisposableEffect
- **Bridge** via origin-restricted WebMessageListener with navigation epoch tracking (BridgeValidator + BridgeHost)
- **Persistence** via minimal Room schema for GameInstance (game identity, profile/session identity, last-played metadata)
- **Capture** via lifecycle-owned coroutine on 60-second cadence (no WorkManager)
- **Game definitions** as versioned JSON in assets/games/ validated at load time
- **produceState → collectAsState → build state map** pattern for DAO-backed UI without Hilt
- **SessionController at NavHost scope** via remember(lifecycleOwner, registry, dao)
- **Pure-Kotlin validation core** separated from Android wrapper

## Capability Contract

See `.gsd/REQUIREMENTS.md` for the explicit capability contract.

## Milestone Sequence

- [ ] M001: Playable Hub — Deliver a usable Playable Hub with launcher, stable WebView sessions, native menu, capture, recovery, and game switching
- [ ] M002: (TBD) — Next milestone TBD
