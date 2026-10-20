# Shark offline startup

Date: 2026-10-20. Started from v0.1.7 (`0b18da0`). Phone build: 0.1.8 (versionCode 9) at `http://192.168.86.3:8006/app/download/latest.apk`. Device under test: headless `PixelFold_API34` (`emulator-5556`).

## What the phone log already showed

v0.1.7 reached `RecoveryRequired` with `documentPhase=GAME`, `openStep=LOAD_GAME`, `gameDocumentLoads=1`, `comparison=MATCH`, and:

```json
{"bootstrap":"","player":true,"appDisplay":"none","offlineDisplay":"","title":"Shark Incremental","readyState":"complete"}
```

That DOM is Shark's offline branch, not a failed document load. `loadGame` waits 100ms, then if the save is more than 60 seconds old and `player.radios.offline` is truthy it calls `simulateOffline`. That function sets `#app` to `display:none` and `#offline` to `display:""` (the stylesheet then shows the overlay). `offlineDone` is what shows `#app` again. The default offline radio is Enabled (`start_position: 1`). A fresh or recent save skips this and shows `#app`.

## What the fold emulator measured

WebView 113, real `https://mrredshark77.github.io/shark-incremental/`, synthetic saves written into that origin's `localStorage` only.

Without Hub CSS, an aged save still entered `offline.active === true`. The overlay's box was the layout viewport (unfolded 842 x 645, later folded 412 x 749). Pixel sampling of the WebView was `teal=0` and mostly the overlay's near-black background. The percentage-height collapse to a turquoise body did not reproduce on this WebView. Resizing the WebView from 1px tall to full size also updated the overlay height, from 1 to 645, without a reload.

The failure that did reproduce is the controls:

- Shark's graph is CSS 800 x 600 (`.graph` in `style/main.css`).
- On the inner screen the viewport is 645 CSS pixels tall, so Speed and Done are laid out below it.
- Probe before the layout change, after the calculation finished (`offline.nosave === false`, Done's inline display cleared): `done.hit === false` and `speed.hit === false`.
- The screenshot is the graph and the "You have been offline" line. There is no Done button in the viewport, and `#app` stays `display:none`.
- v0.1.6/v0.1.7 `SharkLayout` only forced `#offline` to `100vh`. The measured box was already viewport-sized, and Speed stayed `hit:false`.
- A fixed overlay plus a short graph put Speed on screen (`hit:true`) unfolded and folded.

`JS_UI_READY` in v0.1.7 returned true for a tall overlay with offline text even when both buttons were off-screen. That accepts a screen the player cannot leave. The phone's `RecoveryRequired` means that build's check stayed false for the whole watch; this image did not reproduce a 0-height overlay, so the phone may still differ in WebView version or viewport. The DOM it logged is this offline branch, and the missing fields were the control rectangles. Those are in `SharkProbe` now.

Recent saves and aged saves with the offline radio set to 0 show `#app` and do not enter `simulateOffline`.

## Repair

`SharkLayout` v3, still presentation only:

- `#offline` is `position:fixed` pinned to the viewport, so it covers the teal body.
- `#offline-graph` is limited to 200px / 36vh and the cover width, so Speed and Done stay in the viewport. Sticky positioning keeps them there if the overlay scrolls.
- The Hub does not call `offlineDone()`, does not force `#app` visible, and does not rewrite the save.

`JS_UI_READY` now requires a usable offline control:

- `#offline` is painted, and either the offline text is non-empty or `offline.active` is true.
- `#offline-speed` or `#offline-done` is displayed and intersects the visual viewport.
- Otherwise a hidden `#app` is still not ready.
- The main game path is unchanged: painted `#app` plus a non-empty fish, antimatter, or god-fish amount.

The startup probe records offline active/elapsed/speed/nosave, element boxes, viewport and visual viewport, truncated offline text, loop progress, save age in seconds, and whether Hub chrome is opening, recovery, or clear. It does not include the save document.

## Acceptance on emulator-5556

`SharkOfflineAcceptanceTest` (94s):

| Check | Result |
| --- | --- |
| Cold launches into the main game, save kept | 10/10 |
| Warm reloads into the main game, save kept | 10/10 |
| Aged progressed save: offline control on screen, Done click reveals `#app` | 5/5 |
| Fold/unfold during play (1080x2092 and 2208x1840) | 5/5 |
| Fold/unfold during offline calculation, Speed still hit | 1/1 |
| Pause/resume | save kept, main game still ready |
| New WebView after the previous one was destroyed | save kept, main game ready |

A launch was counted only when `JS_UI_READY` was true, which now requires the main interface or an on-screen offline control. `onPageFinished` alone was not enough. Favicon 404s stay informational (`classifyResourceFailure`); the unit test `subresourceFailureDoesNotLeavePlaying` still passes.

Screenshots from the production layout run are in `docs/shark-startup/`.
