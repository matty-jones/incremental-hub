# Progress

Save point for the production-path repair requested by
`TEMP/incremental_hub_audit_cursor_implementation.md`.
No commit has been made. `local.properties` is staged for removal from the
index (`git rm --cached`) and is still present on disk. `.gitignore` lists it.

## What is true now

The launcher opens Shark Incremental and FE000000 at their real URLs.
Each game gets a non-null AndroidX WebKit profile named `hub-game-<gameId>`,
bound before the first navigation. `Opening` becomes `Playing` only after
`onPageFinished` and adapter readiness. Capture flushes, validates, and
writes `snapshot_payload` plus attempt/success timestamps. A failed capture
records the error and does not replace the last good snapshot.
`clearRecoveryState()` only clears `recovery_state`.

Vault and Display stay disabled. Snapshot now and Checkpoint both call
`requestCapture()` and persist one durable snapshot. Reload reloads only
while `Playing` with a live WebView. Menu visibility is independent of
session state. Return to hub captures, closes, and pops back to the launcher.

FE000000 uses the live save format from
`https://dan-simon.github.io/misc/fe000000/js/saving.js` (key `fe000000-save`,
zlib deflate, custom `0a`/`0b`/`0c` alphabet, markers `FE000000SaveStart` /
`FE000000SaveEnd`). Scripts are IIFEs. Payloads are quoted with
`JSONObject.quote`. A live capture that already validates is kept; a stored
snapshot is imported only when the live capture is empty or invalid.

Room is version 3. Migration `2_3` adds the snapshot and capture columns.
Destructive migration fallback is gone.

## Device evidence (2026-10-14)

Environment: PixelFold_x64 AVD, API 36, WebView 133.0.6943.137.
Emulator command (already running as `emulator-5554`; do not start a second one):

```bash
/home/blasky/android-sdk/emulator/emulator -avd PixelFold_x64 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
```

`./gradlew :app:testDebugUnitTest --offline` passed after the live-capture
change (191 tests).

`adb shell am instrument -w -e class com.example.hub.LivePlayableSessionTest com.example.hub.test/androidx.test.runner.AndroidJUnitRunner`
returned `OK (1 test)` in 13.166s. That test, for both games:

- shows both launch URLs on the launcher
- opens the real URL with profile `hub-game-<gameId>`
- waits until Opening clears and asserts no recovery overlay
- Snapshot now, validates the Room payload with the real adapter
- backgrounds via `ActivityScenario` (`ON_PAUSE`), asserts `capture succeeded (background)`, resumes to Playing
- returns to the hub, recreates the activity, resumes, and validates the snapshot again

Log sequence from that run included, for both games:
`bindProfileAndNavigate ... profileNull=false`, `onPageFinished` of the real
URL, `Opening → Playing`, `capture succeeded (request)`, `capture succeeded (background)`,
`capture succeeded (close)`.

After `am force-stop com.example.hub`, `databases/hub_database` plus its WAL
still contained:

- shark-incremental: 8472 chars, prefix `eyJmaXNoIjoiMSIsInRvdGFsX2Zpc2gi`, profile `hub-game-shark-incremental`, success timestamp set, failure 0, `recovery_state` null
- fe000000: 4814 chars, prefix `FE000000SaveStarteJztW0btv2zgS0c`, profile `hub-game-fe000000`, success timestamp set, failure 0, `recovery_state` null

Cold start of `MainActivity` after that kill showed both real URLs and
Resume on both cards. A manual tap of Shark Resume logged profile bind
before `https://mrredshark77.github.io/shark-incremental/` and `Opening → Playing`.

`./gradlew :app:connectedDebugAndroidTest` uninstalls the app when it
finishes, which deletes this evidence. Use `installDebug` plus
`am instrument` when the database must remain.

## Not device-triggered

- Renderer death: `adb shell kill` of the sandboxed WebView process returned
  `Operation not permitted`, and `adb root` is unavailable on this image.
  `SessionControllerTest.rendererGoneKeepsSnapshotAndDoesNotStayOnDeadWebView`
  covers destroy-and-recover without navigating the dead WebView.
- Fold/unfold: this run did not change the hinge.
  `FoldAwareLayout` reads `FoldingFeature` and converts pixels with display
  density. `FoldMetricsTest` checks `pxToDp(480, 3f) == 160`.
- Offline/DNS and HTTP failures were not induced on the emulator.
  Main-frame failures go to recovery; subresource failures stay diagnostic.
  Unit tests cover that split.

## How to continue

- Do not commit unless asked. The staged `local.properties` deletion is intentional.
- Do not `pm clear` or uninstall if a captured snapshot is the evidence under test.
- Do not copy `IntegratedSpike` disposable-profile cleanup into production.
- Restoring is not a legal outgoing state. Restore-on-open happens while still `Opening`.
- `captureClient` returning null must not fall through to `adapter.capture`
  (the elvis operator did that and stuck unit tests in `Opening`).
- Room callbacks resume off the main thread. Navigation after `close()` must
  hop back with `Dispatchers.Main.immediate`.
