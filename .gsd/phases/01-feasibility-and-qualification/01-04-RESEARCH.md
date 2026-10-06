# S04: Integrated Play-Capture-Restore Spike — Research

**Date:** 2026-10-04

## Summary

Slice S04 proves the full vertical slice on real device: **play a real game → capture its actual save → destroy the WebView → create a fresh profile → import the save → verify game progress is preserved**. Unlike S03 (which fell back to synthetic saves when games weren't ready), S04 requires a genuine play session with real game content and an **explicit destroy-and-recreate lifecycle** that S03 never exercised.

The critical non-obvious invariant to prove is that `WebView.destroy()` releases the WebView's binding to a profile but **does not** destroy the profile's localStorage — the saved data must survive WebView destruction and be re-attachable via a fresh WebView bound to the same profile. This is the linchpin of the entire vault architecture: if destroy wiped profile data, the restore pipeline would be impossible.

Build is currently passing (UP-TO-DATE). All infrastructure from S01–S03 is available: `GameAdapter` interface, `SharkIncrementalAdapter`, `Fe000000Adapter`, `ProfileStore`, `WebViewCompat.setProfile()`, `CompletableDeferred` bridging pattern, and the Compose UI pattern (Scaffold + LazyColumn + Card).

## Recommendation

**Use Shark Incremental as the primary test target.** FE000000 has no autosave — it requires an explicit user click on an Import/Load button. Simulating that click from native code would require UI automation (not available in this spike) or a synthetic-inject fallback that defeats the purpose of proving a real play session. Shark has a 15-second autosave interval, making it feasible to wait for a real save to appear.

The integrated spike should:
1. Navigate to the real Shark URL and wait for `player` JS global
2. Wait for a non-empty `shark_inc_save` key to appear (autosave)
3. Capture the real save via `SharkIncrementalAdapter.capture()`
4. **Destroy the source WebView** and log the profile's localStorage state
5. Create a fresh profile WebView and verify the profile data survived
6. Import the captured save into the fresh WebView
7. Verify the restored save matches the captured save

This proves both the data-preservation invariant (destroy doesn't wipe profile) and the round-trip invariant (import restores correct state).

## Implementation Landscape

### Key Files

- **`app/src/main/java/com/example/hub/integrated/IntegratedSpike.kt`** — Core logic. New package. Proves the full vertical slice: real-play → capture → destroy → fresh-profile import → verify. Contains the `IntegratedSpikeResult` data class and the `runSpike()` suspend function. Key invariant: after `webView.destroy()`, query the source profile's WebStorage to confirm localStorage keys survived, then bind a fresh WebView to a fresh profile and import.
- **`app/src/main/java/com/example/hub/integrated/IntegratedSpikeResult.kt`** — Immutable result: `sourceProfileName`, `capturedSaveLength`, `destroyedWebView`, `profileDataSurvived`, `freshProfileName`, `importVerified`, `blocker`, `stages`.
- **`app/src/main/java/com/example/hub/ui/IntegratedSpikeScreen.kt`** — Compose UI. Scaffold + LazyColumn + per-stage cards (green=PASS, red=FAIL) + evidence rows + blocker banner + refresh button. Follows the exact pattern from `GameQualificationScreen.kt` and `BootstrapScreen.kt`.
- **`app/src/main/java/com/example/hub/IntegratedSpikeActivity.kt`** — Standalone Activity host. Pattern from `BootstrapActivity.kt` and `GameQualificationActivity.kt`: `ComponentActivity` → `setContent` → `MaterialTheme` → `Surface` → screen composable.
- **`app/src/main/AndroidManifest.xml`** — Register `IntegratedSpikeActivity` with `android:exported="false"`.
- **`app/src/main/res/values/strings.xml`** — Add `title_activity_integrated_spike` string.

### Build Order

1. **`IntegratedSpikeResult.kt`** — Smallest, no dependencies. Defines the result contract.
2. **`IntegratedSpike.kt`** — Core proof. Depends only on existing `GameAdapter`, `ProfileStore`, `WebViewCompat`. First proof: does WebView destroy preserve profile localStorage?
3. **`IntegratedSpikeScreen.kt`** — UI. Depends on `IntegratedSpikeResult`.
4. **`IntegratedSpikeActivity.kt`** + **manifest** + **strings** — Wiring. Final assembly.
5. **Build verification** — `./gradlew :app:assembleDebug --no-daemon` must exit 0.

### Dependency Slice Summaries

#### S01 Summary
Profile isolation via `WebViewCompat.setProfile()` before navigation. `ProfileStore.getOrCreateProfile()` + `deleteProfile()` lifecycle. `CompletableDeferred` bridging for WebView async callbacks. **Pattern to reuse:** profile bind-before-navigate, destroy-in-finally, CompletableDeferred bridge.

#### S02 Summary
Native HTML bootstrap via `WebViewAssetLoader` or `loadDataWithBaseURL`. `WebView.destroy()` is already exercised in `BootstrapDetector.kt:284` and `PreLaunchSnapshot.kt:162` — the pattern is proven, but **never with a profile-bound WebView that had real game data**. S04 extends this by proving destroy survives with data.

#### S03 Summary
`GameAdapter` interface with 6 methods. `SharkIncrementalAdapter` (base64-JSON decode) and `Fe000000Adapter` (marker-stripping + gzip). `GameQualifier` harness with disposable-profile lifecycle, stage-accumulating pipeline, JSON report. **Key gap S04 fills:** S03's `GameQualifier` falls back to synthetic saves when the game isn't ready. S04 requires a genuine play session with real autosaved data.

### Forward Intelligence / Watch-outs

- **FE000000 no-autosave:** FE000000 requires explicit user click. S04 should use Shark as primary target. If FE000000 is included, it needs a synthetic-inject fallback path (same as S03) with a clear log distinction.
- **WebView destroy timing:** `webView.destroy()` is synchronous but the WebViewClient callback may fire after. Use a flag or deferred to track whether destroy completed before proceeding.
- **Shark autosave cadence:** 15-second interval per game definition. The spike needs a timeout (30s recommended) for the game to autosave. If the timeout fires, the spike should log a warning but not fail — the adapter's synthetic fallback can apply.
- **Profile data survival:** The critical invariant. After destroy, before creating the fresh WebView, the code must **explicitly query the source profile's WebStorage** to confirm the save key still exists. This is the proof step that distinguishes S04 from S03.
- **Fresh profile vs. same profile:** S04 should use a **fresh** (empty) profile for the restore step, not re-bind the same profile. This proves the adapter's import path works independently of the source WebView — the actual production scenario.

## Verification

- **Must-have 1:** Build passes (`./gradlew :app:assembleDebug --no-daemon` exits 0).
- **Must-have 2:** `IntegratedSpike.kt` contains explicit `webView.destroy()` call followed by localStorage verification.
- **Must-have 3:** UI displays per-stage PASS/FAIL with evidence lines for the full flow.
- **Must-have 4:** Activity registered in manifest and launchable.
- **Must-have 5:** JSON report written to `filesDir/integrated_spike_report.json`.
- **Contract verification:** `IntegratedSpikeResult.destroyedWebView == true` and `IntegratedSpikeResult.profileDataSurvived == true` and `IntegratedSpikeResult.importVerified == true`.
- **Operational verification:** N/A (spike, not shipped feature).
- **UAT verification:** Device tester installs APK, launches Integrated Spike activity, confirms all stages show PASSED.

## New Requirements Surfaced

None expected. S04 exercises R002 (profile isolation), R004 (bridge message protocol — implicitly via adapter capture/restore), R008 (cold bootstrap ordering — via destroy+restore proof), R010 (generic localStorage adapter — via adapter round-trip), R011 (game definition — via adapter selection).

## Operational Readiness

- **Health signal:** JSON report at `filesDir/integrated_spike_report.json`, logcat tagged `IntegratedSpike`.
- **Failure signal:** Any stage shows FAIL in the UI, or `BUILD FAILURE` from Gradle.
- **Recovery:** Standard Gradle rebuild (`./gradlew :app:assembleDebug`). Device-side failure is informational (feature support gap on target WebView version).
<!-- gsd:state-version=106:0 -->
