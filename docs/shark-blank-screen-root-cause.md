# Shark blank-screen root-cause audit

The v0.1.7 follow-up is `docs/shark-offline-startup.md`. Navigation and readiness fixes below still stand. They do not put Shark's offline Speed and Done controls on the Pixel Fold inner screen.

Date: 2026-10-20  
Branch: `main` (post v0.1.5 / v0.1.6 fixes)  
Target release after this audit: **0.1.7**

## Confirmed bugs

### 1. Stale `onPageFinished` advanced the game-load path (race)

**Mechanism.** Resume peeks via same-origin `StorageBootstrap` HTML, then `beginGameLoadLocked()` bumps `loadToken` and `loadUrl`s the real Shark document. WebView often delivers a late `onPageFinished` for the peek URL after `openStep` is already `LOAD_GAME`.

**v0.1.5 gap.** Ignoring finishes that still show `__hubStorageBootstrap` helped when the marker was readable, but:

- Finishes were not bound to the navigation that produced them (`finishToken` was unbound / defaulted).
- While `handlingDocument` was true, a concurrent finish was **discarded**, not queued. If the in-flight handler was the bootstrap path (or a long readiness poll), the real game finish never ran and the session stayed Opening on teal.
- Bootstrap marker timeout/exception previously failed open in some paths; awaiting readiness on an inconclusive document could still strand the session.

**Fix.** `WebViewHost` stamps `finishToken` from `loadToken` on `onPageStarted` and passes it to `onPageFinished`. `SessionController.handleDocument` ignores finishes with `finishToken < loadToken` during `LOAD_GAME`, **queues** a concurrent finish and replays it after the in-flight handler, and treats bootstrap-marker timeout/error as fail-closed ignore.

### 2. False-positive Playing on teal (weak readiness)

**Mechanism.** Shark `body` is `lightseagreen`. `#app` stays `display:none` until `loadGame()`'s ~100ms reveal, or `simulateOffline` shows `#offline`. Treating `player` existence (or a zero-height overlay) as ready entered Playing and could call `save()` — "Game saved" with no UI.

**v0.1.6 gap.** Bounding-box checks helped, but offline % height and empty resource counters still allowed edge cases.

**Fix.** `JS_UI_READY` requires: no bootstrap marker; `player.VERSION`; a painted `#offline` with non-empty `#offline-time`, or painted `#app` with non-empty fish/antimatter/god-fish text. `SharkLayout` v2 forces `100vh` on `#offline`. Opening overlay keys off `documentGate.phase != GAME`, not `loadToken == 0`.

### 3. Opening chrome vanished mid-bootstrap

**Mechanism.** `loadToken` is monotonic and never resets across opens, so `Opening && loadToken == 0` was false on re-entry and hid status while still peeking.

**Fix.** Gate OpeningStatus on `DocumentPhase.GAME`.

## Hypotheses (not disproven as sole causes, mitigated)

| Hypothesis | Status |
|------------|--------|
| Hub CSS injection alone breaks Shark init | **Rejected by matrix C** (bootstrap without layout CSS: 5/5 ready) |
| Bootstrap itself prevents readiness | **Rejected by matrix B** (peek→game with layout: 5/5) |
| Cold vs warm WebView differs | **Rejected by matrix D** (cold 5/5, warm reload 5/5) |
| Fold destroy/recreate still races capture | Mitigated by `configChanges` + retain; **not re-proven on physical Fold** this run |
| Essential JS/CSS network failure | Observable via resource diagnostics; not seen in matrix runs |

## Isolation matrix (disposable WebViews, user profile untouched)

Device: Pixel6_API34_PS emulator (`emulator-5554`), 2026-10-20.  
Test: `SharkBlankScreenMatrixTest` + `SharkStartupProbeTest` + `WebViewReliabilityTest` — **9/9 passed**.

| Scenario | Description | Result |
|----------|-------------|--------|
| A | Direct Shark load, layout inject | **5/5** |
| B | Peek bootstrap → game, layout inject | **5/5** |
| C | Peek bootstrap → game, **no** hub CSS | **5/5** |
| D cold | Direct load (repeat cold instances) | **5/5** |
| D warm | Load → ready → `reload()` → ready | **5/5** |
| E | Exit/re-entry / fold | Covered by unit + `WebViewReliabilityTest` config/reattach; no physical Fold |

Matrix wall time ~95s for the five-scenario suite. Assertion requires all rounds ready under `JS_UI_READY`.

## Observability added

- `LoadTimeline`: monotonic elapsed ms, `navId`, `loadToken`, phase/state, categories.
- Blank-screen freeze before recovery watch/overlay (`freezeBlankScreen`); report includes `appVersion`, `gitSha`, URLs, tokens, probe (no save payloads).
- Diagnostics report embeds the timeline ring.

## Regression coverage

- Unit: stale finish token ignored; queued finish replayed (`SessionControllerTest`).
- Adapter: stronger `JS_UI_READY` contract (`SharkIncrementalAdapterTest`).
- Instrumentation: matrix A–D; peek→game visible surface; offline 0-height not ready; layout `100vh` ready.

## Remaining risk

Physical Pixel Fold cold/warm and multi-day resume with a progressed offline overlay still need a device pass. Emulator matrix proves document readiness under hub bootstrap/CSS variants, not fold chrome geometry.
