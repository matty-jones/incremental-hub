---
depends_on: []
---

# M001: Feasibility and Qualification

**Vision:** Prove the three non-negotiable technical foundations — WebView profile isolation, native-origin bootstrap ordering, and candidate-game save compatibility — on the real Pixel Fold device. Produce a minimal app that demonstrates each, a capability report, and a candidate game qualification report with explicit blockers.

## Success Criteria

- [ ] Real Fold fold/unfold/rotate preserves active session identity across configuration changes
- [ ] Two WebView profiles at the same origin remain isolated (localStorage keys do not leak)
- [ ] Cold bootstrap at a qualified storage origin reads native-supplied content without executing upstream service worker code
- [ ] Fresh-profile restore proves storage ordering: pre-launch snapshot is preserved, game loads, adapter validates
- [ ] Candidate report for at least one game (FE000000 or Shark) identifies concrete blockers or confirms readiness
- [ ] MULTI_PROFILE, DOCUMENT_START_SCRIPT, and WEB_MESSAGE_LISTENER feature support is detected and reported on the target device

## Key Risks / Unknowns

- **WebView feature availability on target device** — MULTI_PROFILE, DOCUMENT_START_SCRIPT, WEB_MESSAGE_LISTENER may not all be simultaneously supported on the user's Pixel Fold WebView version. If any are missing, the supported integration path is blocked and remediation must be shown.
- **Bootstrap origin behaviour** — Loading a native HTML document with an HTTPS base origin must reach native-supplied content without executing upstream service worker code. Service workers installed by the game may intercept the bootstrap path. This is unproven on the target device.
- **Game save mechanism unknown** — FE000000 and Shark Incremental render on a browser, but their actual localStorage keys, serialisation format, save cadence, and import path are unverified. A game that renders is not automatically safe to back up or restore.
- **BIOTOMATA iframe storage** — BIOTOMATA visibly uses an iframe on an itch.io landing page. The actual runtime URL/origin and storage context may differ from the landing page origin. Cross-origin frame coordination may be required, which would defer this integration to M6.

## Proof Strategy

- **WebView feature availability** → retire in S01 by running `WebViewFeature.isFeatureSupported()` checks on the real device and reporting results
- **Bootstrap ordering** → retire in S02 by loading a native HTML bootstrap at a qualified origin, reading existing localStorage, and proving the pre-launch snapshot is captured before game code runs
- **Game save compatibility** → retire in S03 by inspecting FE000000 (or Shark) localStorage keys, serialisation, save cadence, and proving a capture→export→fresh-profile import→verify round-trip

## Verification Classes

- **Contract verification:** Feature detection results logged; bootstrap reads native content without upstream JS execution; snapshot envelope has valid SHA-256 and non-zero uncompressedBytes
- **Integration verification:** Real Fold fold/unfold/rotate preserves WebView profile identity; same-origin profiles isolated; cold bootstrap + fresh-profile restore proves storage ordering
- **Operational verification:** None (M0 is a spike, not a shipped feature)
- **UAT / human verification:** Device tester confirms fold behaviour and bootstrap ordering on Pixel Fold

## Milestone Definition of Done

- [ ] S01 deliverable complete: capability report with feature detection results
- [ ] S02 deliverable complete: bootstrap spike proves native-origin content reachability and pre-launch snapshot ordering
- [ ] S03 deliverable complete: candidate game report with concrete findings (blockers or readiness)
- [ ] S04 deliverable complete: integrated spike proving play → capture → destroy → fresh-profile restore → verify on real device
- [ ] All non-negotiable invariants from spec section 18.1 are checked against the spike code
- [ ] M0 acceptance scenario from spec section 16 (step 1) is satisfied: "Install a signed build and qualify the real device's WebView capabilities"

## Requirement Coverage

- Covers: R002, R004, R008, R011, R012, R013, R014
- Partially covers: R001, R005, R007, R009, R010, R015
- Leaves for later: R016, R017, R018
- Orphan risks: None — all M0 risks are mapped to slices

## Scope

### In Scope

- Minimal Android app scaffold (one Activity, Compose content)
- WebViewFeature capability detection and reporting
- Native HTML bootstrap document serving (via WebViewAssetLoader or loadDataWithBaseURL)
- One candidate game integration spike (FE000000 or Shark) with save inspection
- Profile isolation test (two profiles, same origin, verify localStorage separation)
- Candidate game qualification report (blockers, evidence, known limitations)
- Version pinning record (SDK, Kotlin, Compose, WebKit, Room, Gradle)

### Out of Scope / Non-Goals

- Full launcher UI (M002)
- Complete vault pipeline (M003)
- Candidate-profile restore implementation (M004)
- Archive export/import (M004)
- BIOTOMATA integration (deferred to M6 unless qualification succeeds in M0)
- Notes, capsules, assistant (M005–M009)
- CSS fixes, rescue controls (M004–M006)

## Technical Constraints

- **minSdk 30** (Android 13) — profile APIs require Android 13+
- **No shared-profile fallback** — if MULTI_PROFILE is unavailable, show remediation, do not fall back
- **Constructor DI only** — no framework injection in M0
- **One active WebView** — M0 spike uses a single game instance
- **Immutable payloads** — even in the spike, snapshot publication must follow the atomic pattern (blob durable before metadata row)
- **Real device required** — emulation cannot prove fold behaviour or WebView feature availability

## Integration Points

- **AndroidX WebKit** — Profile, ProfileStore, WebViewCompat, WebViewFeature APIs
- **AndroidX Room** — Minimal schema for GameDefinitionRecord, GameInstance, ProfileGeneration, Snapshot, SnapshotPin
- **AndroidX DataStore** — Global preferences (SDK version record, feature flags)
- **WebViewAssetLoader** — Serve native HTML bootstrap from app assets
- **Kotlin coroutines** — Async bootstrap and capture work off main thread
- **Real Pixel Fold device** — Fold/unfold/rotate testing, WebView feature detection

## Testing Requirements

- **Pure unit tests:** WebViewFeature detection logic, bootstrap URL construction, profile name generation
- **Android instrumentation:** Actual WebView profile isolation (two profiles, same origin, verify key separation), bridge message correlation, document-start script ordering
- **Real Fold checks:** Folded/unfolded sizes, rotation, active-session continuity
- **Fault injection:** None in M0 (M03 will handle this)
- **Game fixture integration:** FE000000 or Shark save inspection, round-trip prove

## Acceptance Criteria

1. Capability report documents MULTI_PROFILE, DOCUMENT_START_SCRIPT, WEB_MESSAGE_LISTENER support status on the target Pixel Fold
2. Bootstrap document loads native HTML at a qualified origin, reads existing localStorage, and captures a pre-launch snapshot before game code executes
3. Fresh-profile restore proves: source profile captured, candidate profile created, storage written, game validated, authority committed
4. Candidate game report for FE000000 or Shark includes: observed keys, serialisation format, save cadence, readiness evidence, import path, known exclusions
5. Signed build installed on Pixel Fold; fold/unfold/rotate preserves active session identity
6. All non-negotiable invariants (spec section 18.1) are checked and documented

## Open Questions

- **Exact AndroidX WebKit version to pin** — M0 will test on device and pin the version that supports all three required features. Current target: 1.13.0 (stable).
- **Compile/target SDK** — Targeting 35 (Android 15) as stable; may adjust after M0 device testing.
- **FE000000 vs Shark first** — Spec lists FE000000 first; M0 will start with whichever is easier to inspect and prove round-trip.
- **BIOTOMATA iframe origin** — Will be investigated in M0 if time permits; likely deferred to M6 if cross-origin coordination is needed.
- **Bootstrap HTTPS origin mechanism** — `loadDataWithBaseURL` with a native HTTPS base or `WebViewAssetLoader`? Needs device proof.

## Assumptions

1. **Compile SDK 35 / target SDK 35**: Stable Android 15. Spec says "compile/target the stable SDK selected at implementation kickoff." M0 will confirm on device.
2. **AndroidX WebKit 1.13.0**: Current stable. M0 will re-verify and pin the exact version that supports all three required features on the target device.
3. **Kotlin 2.0.21, Compose BOM 2024.09.00**: Current stable ecosystem.
4. **Room 2.6.1**: Stable with explicit migration support.
5. **Gradle 8.7**: Current stable Android Gradle plugin.
6. **Single-activity architecture**: One Activity host, Compose navigation. WebView owned by SessionController outside transient composable branches.
7. **BIOTOMATA deferred to M6**: Spec says "supported only if independently qualified, otherwise clearly deferred." Since qualification requires device testing and may reveal cross-origin frame issues, M6 is the safe defer point.
8. **FE000000 as first candidate**: Spec lists it first. Starting with the simpler of FE000000/Shark for the M0 spike.
9. **No account/auth**: Explicitly out of scope. App is local-first, no backend.
10. **Disposal fixtures for destructive testing**: Spec says "Use disposable fixtures for destructive testing. Never use the user's only live run as a test database." M0 will create disposable WebView profiles for testing.
<!-- gsd:state-version=106:0 -->
