# Qualification plan

Research only. This is the suite to run after an adapter exists. It does not add tests in this milestone.

Synthetic saves only. A test that writes, imports, corrupts, or ages a save uses a profile created for that run. It does not use a personal desktop export. A long-term-save fixture is a save produced by that game's own export on a disposable profile, then edited only in ways the plan names (timestamps, one truncated character).

## Harness

The workstation already has `scripts/android/start-pixel-fold.sh` and the hinge postures in `docs/pixel-fold-emulator.md`. Unfolded is 2208×1840. Folded is 1080×2092. `wm size` is not a substitute.

`PixelFold_API34` (`emulator-5556`) now runs WebView `155.0.8059.43`, so `ProfileStore.getInstance()` succeeds. The stock image provider is still 113 and will return if userdata is wiped. `scripts/android/check-webview.sh` refuses instrumentation below major version 118. `Pixel6_API34_PS` is unchanged. A `cmd device_state` override sticks across a later hinge change until `cmd device_state state reset`. Fold evidence has to name which control moved the posture. Details are in `emulator-webview-report.md`.

The suite is an instrumentation test like `SharkOfflineAcceptanceTest`, one process per game, profile name `hub-qual-<gameId>-<runId>`, destroyed at the end. Room uses a test database or a game id that is not `shark-incremental` or `fe000000`.

Evidence for every case, kept outside git:

- instrumentation result (pass/fail and assertion message)
- logcat slice for `SessionController`, `WebViewHost`, and the game id
- the envelope or raw payload length and SHA-256, not the full personal-scale blob unless it is synthetic and small
- the progress view JSON from the adapter
- screenshots only for the layout cases, folded and unfolded, after the readiness probe is true

## Pass and fail

A case fails if any of these happen:

- the profile pointer changes on a failed capture or a failed import
- a Shark or FE000000 key appears in the new profile, or the new game's key appears in `hub-game-shark-incremental` or `hub-game-fe000000`
- `compare` returns `LIVE_AHEAD` or `MATCH` for two fixtures the plan marks incomparable
- the game document loads when the peek is `INCONCLUSIVE` or `LIVE_BEHIND`, unless the case is Safe Launch and a diagnostic backup was pinned first
- bytes of a pinned pre-import export differ from the fixture that was pasted
- an export helper that grants resources was used as capture (`exportSynergism`, AD `export` / `exportModifiedSave`)

A case passes only when the assertion and the evidence files exist. A screenshot without the probe is not a pass.

## Cases

### Fresh launch

Delete the qual profile. Open the game. Readiness becomes true. The progress key is absent or is the game's own fresh save. Hub snapshot, if written, is `FRESH` or absent, never `PROGRESSED`. No recovery overlay.

Ethereal Farm may refuse to autosave until a plant or reset exists (`autoSaveOk`). The fresh case must not call `saveNow` itself. Storage may stay empty. That is a pass.

### Existing persisted save

Bootstrap a synthetic progressed fixture. Kill the WebView. Open again. `decideResume` loads the game without writing the fixture over itself. `acceptsRuntime` is true. The stored key still validates.

### Snapshot capture

After readiness, and after the game's own save guard allows a write, run one Hub capture. The new snapshot validates. A second capture of an unchanged game is `MATCH` or a superset under the one-way rules (clocks may move). Corrupt the in-memory function so `save` throws. The profile pointer stays the previous payload.

### Force-close and resume

After a successful capture, destroy the WebView the way `close()` does (`WebView.destroy()`). Relaunch. The game loads the same identity markers. This is the existing invariant that destroy does not wipe profile `localStorage`.

### Missing or corrupted live save

Three fixtures, each its own profile:

- key absent, Hub snapshot present: `LIVE_MISSING`, game document not loaded, recovery offers the snapshot
- key set to `not-a-save`: `INCONCLUSIVE` or invalid, game not loaded, snapshot pointer unchanged
- NG+3 meta present and current slot key absent: invalid, game not loaded

Rollback of a bad restore returns the previous key bytes.

### Aged save and offline progress

Take a synthetic save, set its clock field back by 2 hours, bootstrap it, launch.

| Game | Clock field | Expect |
| --- | --- | --- |
| Ethereal Farm | the timestamp `load` uses as `prevtime` | Game becomes ready. Whether resources increase is recorded, not assumed. Pass if the save still validates and resets do not decrease. |
| Trimps | `game.global.lastOnline` at least 300000 ms back | `#offlineWrapper` appears when the option is 1 or 2. Hub capture does not run `save()` while `usingRealTimeOffline`. After the warp ends, or after the test dismisses it through the game's own control, a capture validates. |
| Vanilla AD | `player.lastUpdate` 2 hours back, offline option true | Readiness true, currencies may rise, reality/eternity/infinity counts do not fall, slot index unchanged. |
| NG+3 | `player.lastUpdate` more than 1000 seconds back, offline flag true | `simulateTime` runs only above that threshold. A 2-hour gap qualifies. Slot id unchanged. |
| Synergism | the offline tick the game actually reads | Warp or offline dialog finishes, `Synergysave2` still validates, singularity count does not fall. |

The exact Synergism field is `player.offlinetick` in `saveSynergy`, and `reloadShit` calls `exitOffline`. **Unverified** which field a two-hour gap must set to force the dialog. The fixture is produced by saving, quitting, and rewriting that field, then the assertion is the one-way marker rule. If the dialog never appears, the case fails open: record the field that was edited and stop, do not invent another field.

### Folded and unfolded

On the fold AVD only, after the profile-crash blocker is gone:

- unfolded, readiness true, a primary control's bounding box is inside the visual viewport
- fold with the hinge sensor, the WebView is not recreated, the same progress key is still present, the control is inside the new viewport
- no capture runs because of the fold itself

Controls:

| Game | Control |
| --- | --- |
| Ethereal Farm | first `<canvas>`, and a button the disposable launch identifies |
| Trimps | a resource amount node, and `#offlineProgress` when the warp is up |
| Vanilla AD | an element inside `#ui` that is a tab button |
| NG+3 | `#coinAmount` after `#container` is shown |
| Synergism | `#savegame` |

Hub page zoom may stay at 100. These games do not get Shark's stylesheet or FE000000's readable mode unless a later change adds one. Overlap or a control past the viewport is a layout fail, not an adapter fail. File it separately. Do not "fix" it by reloading.

### Multiple slots

Vanilla AD: fill slot 0 and slot 1 with different synthetic players inside one `dimensionSave`. Relaunch. `current` is unchanged. The other slot's antimatter exponent is unchanged. `chooseStored` does not swap the slot.

NG+3: meta `saveOrder` of two ids, including an id other than 1 so the key is not only `ZHNBTV8x`. Relaunch. Both keys remain. Current id remains. A third envelope that differs only by which slot is current is `INCONCLUSIVE` against the first, not `LIVE_AHEAD`.

Ethereal Farm: primary and `_daily1` both valid and different. Resume uses the primary. `_daily1` is not chosen.

Trimps and Synergism: this case is skipped. They do not have progress slots.

### Native import

Paste a synthetic export into an empty profile. Pre-import pin exists and its stored export text equals the paste. Post-import pin exists. Readiness and `acceptsRuntime` pass. The paste text is still in the pre-import row.

Repeat on a profile that already has different progressed data. Without confirm, storage is unchanged. With confirm, the pre-import pin holds the old envelope, and failure injection (truncate the paste) restores that envelope and does not stay in `Playing`.

AD and NG+3 also run the new-slot import. The previous current slot's markers remain. NG+3 must not call `window.prompt`. The prompt counter from the import design stays at 0.

### Export / import round trip

On a fresh profile, let the game save, read storage, import that string into a second fresh profile, save, read again.

Pass when identity markers match. Fail when the test called `exportSynergism` or AD `exportModifiedSave` and a reward or speedrun flag moved. Trimps round-trip uses `save(true)`'s return value, which is the supported export string. Ethereal Farm round-trip uses the primary key's text, which is the export body. NG+3 round-trip uses `btoa(JSON.stringify(player))` for the current slot, which is what `export_save` writes for the current id.

Do this twice. The second import must not clear the first profile. Profiles are per game id; use two qual profile names.

### Game update and format change

Keep one synthetic save from the cited version. The assertion for a future adapter change is: that fixture still validates or still follows the game's documented rejection (Ethereal Farm reason 7 or 8, Trimps newer `stringVersion`, AD `version` greater than the build, NG+3 left unverified past 2023-01-11, Synergism schema failure).

Do not download a newer upstream during the run. When an upstream commit changes, update the cited hash in the matrix first, then change the fixture.

A truncated fixture must fail validation and must not become the profile pointer.

### Shark and FE000000 regression

Run the existing unit tests and the existing Shark instrumentation (`SharkOfflineAcceptanceTest` or the current successor) on a build that contains the envelope bootstrap.

Pass when:

- `shark_inc_save` and `fe000000-save` still restore through the one-key bootstrap
- Shark `chooseStored` still prefers the further progressed candidate
- FE000000 behaviour is unchanged, including its current `compare`, until a separate change is requested
- a qual profile for `trimps` does not change `hub-game-shark-incremental` or `hub-game-fe000000` row counts

## Order

1. Fix or replace the fold AVD WebView enough that `ProfileStore.getInstance()` returns and a page can load. If that is not this project, mark every fold case blocked and run the rest on the phone AVD, labelled as not a fold result.
2. Dialog / file / download experiment. Record it in the matrix. Do not block Trimps, Synergism, Ethereal Farm, or AD text import on file-picker work.
3. NG+3 prompt-counter experiment before the NG+3 import cases.
4. Fresh, persist, capture, kill, corrupt, for Trimps first. The live files hash-match the source.
5. The same for Synergism, then Ethereal Farm, then vanilla AD, then NG+3.
6. Shark and FE000000 regression on the same build as step 4, before any of those games is a candidate to ship.
