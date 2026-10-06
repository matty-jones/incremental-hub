# S02: Native-Origin Bootstrap Spike — Research

**Date:** 2026-10-03

## Summary

Slice S02 must prove that a native HTML bootstrap document, served from app assets at a qualified storage origin, can read existing localStorage and capture a pre-launch snapshot envelope *before* any upstream game JavaScript executes. The critical unknown is **which mechanism produces a storage origin that the game's service worker cannot intercept**: `WebViewAssetLoader` (virtual domain `https://appassets.androidplatform.net/...`) or `loadDataWithBaseURL` with a native HTTPS base origin (e.g., `https://hub.internal/...`).

The spike will create a minimal Android app that:
1. Registers a `WebViewAssetLoader` path handler for a bootstrap path in `assets/bootstrap/`.
2. Binds a WebView to a named profile via `WebViewCompat.setProfile()` *before* any navigation.
3. Navigates to the bootstrap URL and proves the document reaches native-supplied HTML (not upstream content).
4. Reads existing localStorage via `evaluateJavascript` and captures a pre-launch snapshot envelope.
5. Reports success/failure with evidence in a Compose screen.

The highest-risk question is whether `WebViewAssetLoader` URLs produce an origin that is isolated from upstream service workers. If not, the fallback is `loadDataWithBaseURL` with a native HTTPS base — this is less clean but may be the only viable path on some WebView versions.

## Recommendation

**Use `WebViewAssetLoader` as the primary path, with `loadDataWithBaseURL` as a verified fallback.** The spike will test both mechanisms on the real Pixel Fold and report which one produces a qualified storage origin. This matches the open question in the milestone context: *"Bootstrap HTTPS origin mechanism — `loadDataWithBaseURL` with a native HTTPS base or `WebViewAssetLoader`? Needs device proof."*

The implementation landscape is straightforward: a new `bootstrap/` package with two detector classes, a bootstrap HTML asset, and a Compose screen. The patterns from S01 (`ProfileIsolationTest` + `DiagnosticsScreen`) carry over directly.

## Implementation Landscape

### Key Files

- `app/src/main/assets/bootstrap/native.html` — Minimal bootstrap HTML. Contains only inline JS that reads `localStorage` and posts a `window.webkit.messageHandlers.hub.postMessage()` message with the snapshot envelope. No external scripts, no network requests. The file proves reachability: if the WebView loads this and the native side receives the message, the bootstrap origin is qualified.

- `app/src/main/java/com/example/hub/bootstrap/BootstrapDetector.kt` — Queries whether `WebViewAssetLoader` and `loadDataWithBaseURL` with a native HTTPS base both produce qualified origins. Creates a test profile, binds a WebView, navigates to each bootstrap URL, and captures the origin via `evaluateJavascript("return location.origin")`. Reports which mechanisms work and which fail. Follows the same `suspend fun` + `CompletableDeferred` pattern as `ProfileIsolationTest`.

- `app/src/main/java/com/example/hub/bootstrap/PreLaunchSnapshot.kt` — Proves the pre-launch snapshot ordering invariant. Creates a profile, writes a synthetic localStorage key, navigates to the bootstrap URL, reads the key via `evaluateJavascript`, captures the snapshot envelope (SHA-256 of keys, uncompressed byte count, timestamp), and verifies the snapshot is non-empty. Returns a `SnapshotResult` data class. This is the core proof for R008.

- `app/src/main/java/com/example/hub/ui/BootstrapScreen.kt` — Compose UI displaying bootstrap detector results and pre-launch snapshot results. Mirrors `CapabilityScreen` structure: feature badges, result cards, remediation banner.

- `app/src/main/java/com/example/hub/ui/BootstrapActivity.kt` (or nav host) — New Activity or navigation destination to host `BootstrapScreen`. Since S01's `DiagnosticsScreen` is already hosted in `MainActivity`, S02 can either add a second screen in the same Activity or create a separate entry point. A separate `BootstrapActivity` is cleaner for isolation.

- `app/src/main/AndroidManifest.xml` — Register the new Activity if created.

### Build Order

1. **Bootstrap HTML asset** — Create `assets/bootstrap/native.html` first. This is the simplest file and unblocks all downstream work. The HTML must be self-contained: inline JS only, no external dependencies.

2. **BootstrapDetector** — Implements the origin-reachability proof. Tests both `WebViewAssetLoader` and `loadDataWithBaseURL` paths. This is the highest-risk component because it determines which mechanism works on the target device.

3. **PreLaunchSnapshot** — Implements the pre-launch snapshot ordering proof. Depends on BootstrapDetector having identified a working origin mechanism. Reads localStorage, computes SHA-256 of keys, captures timestamp and byte count.

4. **BootstrapScreen + wiring** — Compose UI and Activity wiring. Lowest risk; follows the S01 pattern exactly.

### Verification Approach

- **Contract verification:** Build passes (`./gradlew :app:assembleDebug`), bootstrap HTML exists in assets, `BootstrapDetector` logs which origin mechanism works, `PreLaunchSnapshot` reports non-zero `uncompressedBytes` and valid SHA-256.
- **Integration verification:** On Pixel Fold, navigate to bootstrap URL, confirm native message is received with pre-launch snapshot data, verify synthetic key is readable.
- **UAT:** Device tester installs APK, launches BootstrapScreen, confirms "Bootstrap origin qualified" and "Pre-launch snapshot captured" with non-zero byte count.

## Don't Hand-Roll

| Problem | Existing Solution | Why Use It |
|---------|------------------|------------|
| Serve local HTML at a virtual origin | `WebViewAssetLoader.AssetsPathHandler` | AndroidX WebKit 1.14.0 provides this out of the box. No custom HTTP server needed. |
| Bind WebView to a profile before navigation | `WebViewCompat.setProfile(webView, profileName)` | Already used in `ProfileIsolationTest.kt`. Proven pattern. |
| Bridge native↔JS messages | `WebViewCompat.addWebMessageListener` | Requires `WEB_MESSAGE_LISTENER` feature (detected in S01). Standard API. |
| Read localStorage before game code | `webView.evaluateJavascript("return JSON.stringify(localStorage)")` | Simple, reliable. Used in S01 isolation test. No new pattern needed. |
| Coroutines + WebView callbacks | `CompletableDeferred` pattern | Used in `ProfileIsolationTest.kt`. Avoids `suspendCancellableCoroutine` ambiguity in Kotlin 2.1+. |

## Constraints

- **minSdk 30** — `ProfileStore.getOrCreateProfile()` requires Android 13+. No fallback.
- **WebViewFeature dependency** — If `WEB_MESSAGE_LISTENER` is not supported on the target device, the bridge message approach fails and the spike must report a blocker. This is the same risk as S01.
- **No shared-profile fallback** — If `MULTI_PROFILE` is unavailable, remediation is shown, not fallback. S02 follows the same rule.
- **Single WebView** — M0 spike uses one active WebView. No concurrent profile testing in S02 (that was S01's job).
- **Immutable payloads** — Snapshot envelope must follow the atomic pattern (blob durable before metadata row), even in the spike.

## Common Pitfalls

- **Service worker interception** — If the upstream game has a service worker registered at a overlapping origin, it may intercept the bootstrap path. The spike must detect this by comparing the received origin against the expected origin. **Avoid by:** testing on a clean profile with no prior game navigation.
- **WebViewAssetLoader origin mismatch** — `WebViewAssetLoader` uses `https://appassets.androidplatform.net/` as the default domain. Some WebView versions may treat this as a cross-origin context. **Avoid by:** testing both mechanisms and reporting which one produces a consistent origin.
- **evaluateJavascript timing** — `onPageFinished` fires before all JS in the page has executed. If the bootstrap HTML has inline scripts, `evaluateJavascript` may run before they complete. **Avoid by:** using `onPageFinished` + a small delay, or having the bootstrap HTML post a message back to native after it reads localStorage.
- **ProfileStore singleton** — `ProfileStore.getInstance()` must be called on the main thread. The S01 pattern (`withContext(Dispatchers.Main)`) handles this.

## Open Risks

- **WebViewAssetLoader vs loadDataWithBaseURL** — Neither mechanism has been proven on the target Pixel Fold. The spike will determine which works; if neither works, S03 (candidate game qualification) is blocked.
- **Bootstrap HTML inline JS execution timing** — The bootstrap HTML must read localStorage and post a message *before* any game code runs. If the WebView caches or defers inline scripts, ordering may break. Device testing will reveal this.
- **Native HTTPS base origin feasibility** — `loadDataWithBaseURL("https://hub.internal/...", ...)` may or may not produce a stable storage origin across WebView restarts. This is the fallback path and needs device proof.

## Sources

- AndroidX WebKit 1.14.0 API docs: `WebViewAssetLoader`, `WebViewCompat.addWebMessageListener`, `WebViewFeature.DOCUMENT_START_SCRIPT`
- MEM002 (architecture memory): Bootstrap document reaches native-supplied content without executing upstream service worker code
- S01 summary: `ProfileIsolationTest.kt` pattern for profile-bound WebView + `CompletableDeferred` bridging
<!-- gsd:state-version=106:0 -->
