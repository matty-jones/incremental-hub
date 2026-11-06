# Background offline progression

Hub does not invent idle gains. A game is eligible for automatic catch-up only through an adapter that names a verified native mechanism. The default capability is unsupported. There is no generic clock rewrite, no fabricated currency, and no foreground service that keeps the WebView awake.

## Capabilities

| Capability | Meaning |
| --- | --- |
| Unsupported | Hub keeps the live session and says that offline progression is not available. |
| Native on load | The game calculates the gap itself when its save is loaded. Hub does not also call that function after a warm resume. A process death or reboot uses this path. |
| Native on resume | The game exposes the same calculation for a page whose save clock has not moved. Hub may call that function only when the checks below pass. |
| Elapsed time | The game already applies real elapsed time in its ordinary loop. Hub does not add a second calculation. No current adapter uses this. |

Shark Incremental is the only qualified game. Its capability is native on resume, adapter `shark-incremental-adapter` 1.0.0. Trimps, Synergism, and the other registered games stay unsupported.

## Shark contract

Upstream `loadGame` waits briefly, computes `(Date.now() - player.latest_time) / 1000`, runs one `loop()` tick, and calls `simulateOffline` only when that gap is greater than 60 seconds and `player.radios.offline` is on. `simulateOffline` (in `js/features/offline.js`) returns immediately if `offline.active` is set. It sets `offline.nosave` and `offline.active`, then applies many `calc(dt)` ticks until the simulated elapsed time reaches the gap. `save()` does not write while `offline.nosave` is set. `calc` sets `player.latest_time` to `Date.now()`.

A warm WebView does not call `loadGame`. The next `loop()` tick can consume `Date.now() - date` in one step, set `latest_time` to now, and skip `simulateOffline`. That single tick is not the same as the offline loop. Hub therefore pauses WebView timers after a real background capture, and on return inspects the page without calling `save()`.

Automatic catch-up runs only when all of these are true:

- The absence is at least 60 seconds.
- Elapsed realtime and wall clock agree for the same boot, within 15 seconds or 10 percent, whichever is larger.
- The background capture was validated and committed, and the checkpoint records that snapshot id and content hash.
- Live storage still hashes to that checkpoint.
- Shark reports offline enabled, and `offline.active` and `offline.nosave` are both false.
- The gap Shark computes from `player.latest_time` is greater than 60 seconds and agrees with the measured absence.

The adapter script computes that gap in the page and calls `simulateOffline(gap)`. Hub does not pass a duration. WebView timers resume only after `simulateOffline` has set `offline.active`, so the ordinary loop does not consume the gap first. Hub waits until `offline.nosave` is clear and `offline.elapsed` is greater than zero, then captures through `CaptureRepository` with reason `offline-resume`. Other captures wait during that calculation. A partial run is not stored.

The same absence is not applied twice. After a completed run, `latest_time` is current, so a second call returns short and does not call `simulateOffline` again. If the live loop already moved `latest_time`, or the storage hash no longer matches the checkpoint, Hub does not call `simulateOffline`. It does not reload the checkpoint over the live session. A different boot, including process death and reboot, is left to `loadGame` and is not also sent through `simulateOffline`.

Shark's offline radio, `offline.active`, and `offline.nosave` are respected. A gap under 60 seconds, a fold, the vault, and the in-game menu do not start this path.

## Checkpoint

The checkpoint is `SharedPreferences` `hub_background_checkpoint`. It is not a Room table. HubDatabase stays at version 9. A checkpoint is marked committed only when the payload was captured, validated, and stored as a non-quarantine snapshot. A failed or missing capture is recorded as not committed and is not used for catch-up.

## What the player sees

Under 60 seconds, a fold, the vault, a process restart, and a game that already applies elapsed time stay quiet. A longer absence can show one line in the game menu bar: the elapsed time and `ran`, `skipped`, or `failed`. `ran` is used only after Shark's own offline function has finished. A missing checkpoint, a turned-off offline switch, a clock or hash mismatch, or any other check that does not pass is `skipped`, and the live session stays. `failed` means a calculation started and was interrupted; the partial result is not saved. Opening the menu shows the sentence and acknowledges that line, so the same checkpoint does not announce itself again. There is no banner and no button that reloads a background save. Loading an older snapshot is a vault restore, with its own confirmation. Diagnostics record the adapter, the clocks, the confidence, the snapshot id, a short hash prefix, the capability, and whether the operation started, finished, was skipped, or was refused. They do not include the save payload.
