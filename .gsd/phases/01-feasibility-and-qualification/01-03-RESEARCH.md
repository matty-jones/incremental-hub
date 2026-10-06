# S03: Candidate Game Qualification — Research

**Date:** 2026-10-03

## Summary

S03 inspects one candidate game (FE000000 or Shark Incremental) to identify its actual localStorage keys, serialization format, save cadence, readiness signal, and import path. A capture→export→fresh-profile import→verify round-trip must be proven on-device. Both games were inspected via browser evaluation and upstream source code analysis.

**FE000000** uses a single localStorage key `fe000000-save` containing a gzip-compressed, base64-encoded save wrapped in `FE000000SaveStart`/`FE000000SaveEnd` markers. It has an explicit "Import/Load" UI button and no IndexedDB.

**Shark Incremental** uses a single localStorage key `shark_inc_save` containing a base64-encoded JSON object with a `VERSION` field (currently 3). It has an explicit `exporty()` function (downloads .txt) and a `load(x)` function (accepts base64 string). Autosave fires every 15–120 seconds based on user preference. It uses the `break_eternity.js` Decimal library for large numbers.

Shark Incremental is recommended as the primary candidate for S03 because its save format (plain base64 JSON) is simpler to adapter-implement, while FE000000's gzip layer requires an additional decompression step. Both can be qualified in the same slice.

## Recommendation

**Start with Shark Incremental as primary candidate, FE000000 as secondary.** Shark's base64-JSON format maps directly to the generic localStorage v1 adapter (R010) with a version-gating migration path. FE000000's gzip-wrapped format requires a game-specific adapter variant. The round-trip proof (capture→export→fresh-profile import→verify) is straightforward for both but is easier to implement and verify with Shark first.

The implementation landscape is straightforward: a new `games/` package with game definition JSON, a `shark-incremental` adapter, a `fe000000` adapter, and a `GameQualifier` test harness that performs the round-trip on a disposable WebView profile. The patterns from S01/S02 (profile-bound WebView, `CompletableDeferred` bridging, bootstrap origin detection) carry over directly.

## Implementation Landscape

### Key Files

- `app/src/main/assets/games/shark-incremental.json` — Game definition JSON. Fields: `schemaVersion`, `definitionVersion`, `gameId`, `displayName`, `sourceUrl`, `launchUrl`, `navigationRules`, `storageContext`, `adapterId`, `adapterVersion`, `saveCoverage`, `displayDefaults`, `versionProbe`, `compatibility`. Storage context: top-level origin `https://mrredshark77.github.io`. Save coverage: single key `shark_inc_save`, freshness `persisted-only` (autosave cadence 15–120s). Adapter: `local-storage-v1` with version gate `VERSION >= 3`.

- `app/src/main/assets/games/fe000000.json` — Game definition JSON. Storage context: top-level origin `https://dan-simon.github.io`. Save coverage: single key `fe000000-save`, format `gzip-base64-with-markers`, freshness `persisted-only` (explicit save via Import/Load button). Adapter: `fe000000-storage-v1` (specialized for gzip-wrapped format).

- `app/src/main/java/com/example/hub/games/SharkIncrementalAdapter.kt` — Adapter implementing the R007 contract: `probe()`, `awaitReady()`, `capture()`, `validate()`, `prepareRestore()`, `verifyLoaded()`. For Shark: decode base64, parse JSON, validate `VERSION` field, extract/restore `shark_inc_save` key. Handles `Decimal` serialization (break_eternity.js format) losslessly as opaque JSON strings.

- `app/src/main/java/com/example/hub/games/Fe000000Adapter.kt` — Adapter for FE000000. Strips `FE000000SaveStart`/`FE000000SaveEnd` markers, base64-decodes, gzip-decompresses for capture; reverses for restore. Validates that the decompressed payload is valid JSON before writing.

- `app/src/main/java/com/example/hub/games/GameQualifier.kt` — Test harness that orchestrates the round-trip proof. Creates a disposable profile, navigates to the game, plays for a bounded time (or simulates progress by writing a synthetic save), captures via the adapter, exports the save string, navigates to a fresh profile, imports the save string, verifies the game loaded correctly (reads back the key and compares). Returns a `QualificationResult` with pass/fail per stage.

- `app/src/main/java/com/example/hub/ui/GameQualificationScreen.kt` — Compose UI displaying qualification results for both games. Mirrors `CapabilityScreen` structure: per-game result cards, evidence lines, blocker banner if either game has showstopper issues.

- `app/src/main/java/com/example/hub/ui/GameQualificationActivity.kt` — Standalone Activity hosting `GameQualificationScreen`. Registered in `AndroidManifest.xml`.

### Build Order

1. **Game definition JSONs** — Create `assets/games/shark-incremental.json` and `assets/games/fe000000.json` first. These are the declarative contract and unblock all downstream adapter work. The definitions encode the observed storage context, save coverage, and adapter association.

2. **SharkIncrementalAdapter** — Implements the simpler base64-JSON adapter first. Tests decode/encode round-trip, version gate, and key extraction. This is the lowest-risk adapter and provides the template for FE000000.

3. **Fe000000Adapter** — Implements the gzip-wrapped adapter. Depends on understanding the marker format (already observed in browser). Tests the gzip decompress/repcompress round-trip.

4. **GameQualifier** — Orchestrates the full round-trip proof on a disposable profile. Depends on both adapters being implemented. This is the highest-risk component because it exercises the full capture→import pipeline on real WebView profiles.

5. **GameQualificationScreen + Activity wiring** — Compose UI and Activity registration. Lowest risk; follows S01/S02 patterns exactly.

### Verification Approach

- **Contract verification:** Build passes (`./gradlew :app:assembleDebug`), game definition JSONs parse correctly, both adapters round-trip synthetic data without loss, `GameQualifier` reports `QualificationResult.passed=true` for at least Shark Incremental on device.
- **Integration verification:** On Pixel Fold, navigate to Shark Incremental in a disposable profile, capture the save, import into a fresh profile, verify the `shark_inc_save` key is present and `player.VERSION` matches. Repeat for FE000000.
- **UAT:** Device tester installs APK, launches GameQualificationScreen, confirms both games show qualification results with evidence lines (key observed, format identified, round-trip passed).

## Don't Hand-Roll

| Problem | Existing Solution | Why Use It |
|---------|------------------|------------|
| Decode base64 localStorage values | `android.util.Base64.decode()` + `String(bytes, UTF_8)` | Standard Android API. No external dependency. |
| Decode gzip-compressed data | `java.util.zip.GzipInputStream` | Standard Java API. Used for FE000000's save format. |
| Parse JSON with Decimal strings | `kotlinx.serialization` with custom `JsonTransformer` | Preserves Decimal objects as opaque strings; avoids floating-point precision loss. |
| Bind WebView to disposable profile | `WebViewCompat.setProfile()` + `ProfileStore.getOrCreateProfile()` | Proven pattern from S01/S02. |
| Bridge native↔JS capture requests | `WebViewCompat.addWebMessageListener` | Requires `WEB_MESSAGE_LISTENER` (detected in S01). Standard API. |
| Read localStorage before game code | `webView.evaluateJavascript("return JSON.stringify(localStorage)")` | Used in S01/S02. Reliable for single-key games. |
| Coroutines + WebView callbacks | `CompletableDeferred` pattern | Used in S01/S02. Avoids `suspendCancellableCoroutine` ambiguity. |

## Constraints

- **minSdk 30** — `ProfileStore.getOrCreateProfile()` requires Android 13+. No fallback.
- **Single WebView** — M0 spike uses one active WebView. The qualifier creates/destroys WebViews sequentially per game.
- **No service worker assumption** — Neither game was observed to register a service worker. If one appears during device testing, the bootstrap origin mechanism from S02 becomes critical.
- **Immutable payloads** — Even in the spike, capture payloads must follow the atomic pattern (blob durable before metadata row).
- **Disposable fixtures** — All qualification tests use disposable profiles. The user's live game data is never touched.

## Common Pitfalls

- **FE000000 gzip marker mismatch** — The `FE000000SaveStart`/`FE000000SaveEnd` markers must be stripped precisely. **Avoid by:** using string `substring` with exact marker length, not regex (regex may mishandle Unicode).
- **Shark Decimal serialization** — `break_eternity.js` Decimal objects serialize to `{s:1,e:2,c:[3]}`-style JSON. The adapter must preserve these as opaque strings, not parse them into Double. **Avoid by:** using a custom Kotlin serializer that treats Decimal-valued fields as `JsonElement` and round-trips them via `JSON.stringify`/`JSON.parse`.
- **Import timing race** — If the import JS runs before the game's `loadGame()` has finished initializing, the imported save may be overwritten. **Avoid by:** importing in a candidate profile where the game has never run, or waiting for a readiness signal (e.g., `player` object exists) before writing.
- **Autosave interference** — Shark's autosave fires every 15–120s. During qualification, the game may autosave between capture and import, corrupting the captured snapshot. **Avoid by:** disabling autosave via `player.radios.autosave = 0` before capture, or noting the autosave interval in the qualification report.
- **Version migration on load** — Shark's `checkVersion()` mutates `player` in-place when loading an older save. The adapter must capture the post-migration state, not the raw saved bytes. **Avoid by:** reading the save, running the game's migration logic (or simulating it), then capturing the result.

## Open Risks

- **Shark autosave timing** — If the autosave fires during the capture window, the captured snapshot may include mid-write state. **Mitigation:** disable autosave before capture; report the autosave interval in the qualification evidence.
- **FE000000 gzip compatibility** — The gzip layer was observed in the browser but the exact compression settings (level, format) are unknown. **Mitigation:** test with the actual save string from the browser; if decompression fails, fall back to treating the value as opaque and report a blocker.
- **Upstream service worker** — Neither game showed a service worker in the browser inspection, but GitHub Pages may inject one. **Mitigation:** S02 already tested both bootstrap mechanisms; S03 uses the qualified mechanism from S02.
- **BIOTOMATA deferral** — BIOTOMATA is explicitly deferred to M6 per the spec. S03 does not investigate it.

## Skills Discovered

| Technology | Skill | Status |
|------------|-------|--------|
| Kotlinx Serialization (Decimal preservation) | n/a | none found — use manual JsonElement handling |
| Java GzipInputStream | n/a | none found — use standard library directly |

## Sources

- FE000000 browser inspection: localStorage key `fe000000-save`, format `FE000000SaveStart` + gzip(base64) + `FE000000SaveEnd` (source: browser_evaluate on `https://dan-simon.github.io/misc/fe000000/`)
- Shark Incremental save.js source: `SAVE_ID = "shark_inc_save"`, `save()` uses `btoa(JSON.stringify(player))`, autosave interval configurable (source: [save.js](https://raw.githubusercontent.com/MrRedShark77/shark-incremental/main/js/save.js))
- Shark Incremental load.js source: `loadGame(start=true)` calls `load(prevSave)` which does `JSON.parse(atob(x))` (source: [load.js](https://raw.githubusercontent.com/MrRedShark77/shark-incremental/main/js/load.js))
- SPEC.md section 5.1: Candidate game qualification requirements and compatibility levels
- S01 summary: `ProfileIsolationTest.kt` pattern for profile-bound WebView + `CompletableDeferred`
- S02 summary: `BootstrapDetector.kt` pattern for origin detection + `PreLaunchSnapshot.kt` for pre-launch ordering
<!-- gsd:state-version=106:0 -->
