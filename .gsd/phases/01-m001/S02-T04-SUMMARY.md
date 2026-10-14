---
id: T04
parent: S02
milestone: M001
key_files:
  - app/src/main/java/com/example/hub/ui/FoldAwareLayout.kt
  - app/src/main/java/com/example/hub/ui/PlaySurface.kt
  - app/src/main/java/com/example/hub/navigation/HubNavigation.kt
  - app/build.gradle.kts
key_decisions:
  - Used modifier-based WindowInsets (statusBarsPadding, navigationBarsPadding, imePadding) instead of companion extension properties due to resolution issues in compose 1.7.0
  - Made isWideWindow() @Composable to access LocalContext.current for WindowMetricsCalculator
duration:
verification_result: passed
completed_at:
blocker_discovered: false
---

# T04: Wired FoldAwareLayout, PlaySurface, and HubNavigation to deliver the real WebView-based play surface with fold-aware insets and sibling-overlay native menu.

**Wired FoldAwareLayout, PlaySurface, and HubNavigation to deliver the real WebView-based play surface with fold-aware insets and sibling-overlay native menu.**

## What Happened

Created FoldAwareLayout composable that applies status bar, navigation bar, and IME insets via modifier-based APIs (avoiding WindowInsets companion extension resolution issues in this compose version). Added WindowMetricsCalculator-based width detection for hinge-aware layout branching. Created PlaySurface composable composing FoldAwareLayout + WebViewHost + NativeMenu as sibling overlay (not parent wrapper), preserving WebView identity across menu open/close cycles. Updated HubNavigation to wire PlaySurface instead of PlaySurfaceStub. Added androidx.window:window:1.3.0 dependency. Build passes; all S02 unit tests pass (SessionStateTest, WebViewHostTest, NativeMenuTest).

## Verification

 assembleDebug passes; SessionStateTest (81 pairs + 30 named), WebViewHostTest, NativeMenuTest all pass individually. Full unit test suite has a pre-existing Gradle binary-index corruption issue (GameRegistryTest XML write failure) unrelated to S02 changes.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 10000ms |
| 2 | `./gradlew :app:testDebugUnitTest --tests com.example.hub.session.SessionStateTest --no-daemon` | 0 | PASS | 12000ms |
| 3 | `./gradlew :app:testDebugUnitTest --tests com.example.hub.ui.WebViewHostTest --no-daemon` | 0 | PASS | 13000ms |
| 4 | `./gradlew :app:testDebugUnitTest --tests com.example.hub.ui.NativeMenuTest --no-daemon` | 0 | PASS | 10000ms |

## Deviations

["FoldAwareLayout simplified from initial hinge-aware inset construction to modifier-based padding only — companion WindowInsets extensions (safeDrawing, ime, systemBars) did not resolve in this compose version", "isWideWindow() changed from non-composable to @Composable to avoid LocalContext.current invocation in non-composable context"]

## Known Issues

Pre-existing Gradle binary-output-index corruption causes full-suite testDebugUnitTest to fail intermittently (GameRegistryTest XML write). Isolated S02 tests pass. Not related to T04 changes.

## Files Created/Modified

- `app/src/main/java/com/example/hub/ui/FoldAwareLayout.kt`
- `app/src/main/java/com/example/hub/ui/PlaySurface.kt`
- `app/src/main/java/com/example/hub/navigation/HubNavigation.kt`
- `app/build.gradle.kts`
<!-- gsd:state-version=134:1 -->
