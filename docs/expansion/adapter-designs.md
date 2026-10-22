# Adapter designs for the five catalogue games

The five adapters are still not registered. `GameAdapters.create` returns null for their ids. No catalogue JSON was added.

The opt-in contract below is implemented. Shark and FE000000 do not set `usesSaveEnvelope`. Their raw save strings, profiles, and recovery behaviour stay on the previous path. FE000000 `compare` is intentionally unchanged: two valid unequal saves still return `LIVE_AHEAD`. That result is not a progress order. New adapters use the default compare, which returns `INCONCLUSIVE` when a progress view is missing or the two views disagree. Do not copy FE000000 when those adapters are written.

Hub contract for the existing games: one `storageKey`, `peekStorage`, `capture`, `validate`, `prepareRestore`, `verifyLoaded`, `summarize`, `compare`, `chooseStored`, `acceptsRuntime`, `confirmBootstrap`. `StorageBootstrap` writes that one key before game scripts unless the payload is a `hubEnvelope` 1 document. `SessionController.decideResume` resumes only when the comparison is `MATCH`, `LIVE_AHEAD`, or `NO_SNAPSHOT`.

## Contract changes

Add these with Kotlin default implementations so the two existing adapters keep compiling and keep their current behaviour.

| Addition | Default | Why it is required |
| --- | --- | --- |
| `storageEntries: List<StorageEntry>` | one entry, `storageKey`, required | Ethereal Farm has a fixed family of keys. Vanilla AD has backup keys. NG+3 ids are allocated. |
| `captureBundle` / `peekBundle` | call today's `capture` / `peekStorage` and wrap the single value | The session must snapshot every required key in one evaluation. |
| `prepareRestoreBundle` | today's `prepareRestore` on the single payload | Bootstrap must write every key, or remove keys that are absent in the snapshot, before any game script. |
| `progressView(payload): ProgressView?` | null | Comparison must be able to return `INCONCLUSIVE` when two valid saves cannot be ordered. |
| `nativeImport(webView, exportText): ImportOutcome` | unsupported | Each game's import function is different. The session must not paste into a guessed UI. |
| `readinessScript` | empty | `productionDocumentProbe` evaluates this script. Shark's is `SharkProbe.JS`. It must not gate other games. |

`StorageEntry` is `name`, `required`, and `role` (`progress`, `backup`, `preference`). Preference keys are not progress and are not restored unless the adapter lists them.

`DocumentGate.payload` stays a string. For these games the string is a versioned envelope:

```text
{"hubEnvelope":1,"entries":[{"name":"...","value":"..."}, ...]}
```

A missing `value` means the key must be removed. `"value": ""` stores an empty string and is not removal. JSON `null`, a non-string value, a duplicate name, an unexpected name, a denied name (`playFabPass`, `playFabName`, `accountToken`), or a missing required entry is rejected. Single-key Shark and FE000000 payloads stay the raw save string. `StorageBootstrap` learns the envelope only when `hubEnvelope` is 1. Otherwise it keeps the current one-key write. That is the compatibility boundary.

Several `setItem` calls in one bootstrap page are not one atomic write across process death. An opted-in restore writes a journal file under `filesDir/restore-journals/<profile>.json` before those calls, then again after the Room snapshot pointer is committed. On the next launch an unfinished journal keeps the game unloaded. The session re-applies the intended slots and checks every expected key, including keys that must be absent. If that check fails it restores the previous slots and the previous pointer. If the previous state cannot be verified, the journal stays and the game stays unloaded. A failed capture does not move the pointer. `INCONCLUSIVE` stays distinct from a rejected envelope.

`compare` for a new adapter must not use payload length, `Date`, or "different means ahead". If the progress view cannot order the two sides, return `INCONCLUSIVE`. `chooseStored` must not pick a winner among incomparable candidates. It may return the current profile pointer when that pointer still validates, and null when it does not. Null plus a live save that is not a clear resume becomes recovery, which is the existing `decideResume` behaviour.

Do not add a general multi-key framework beyond this envelope. Trimps and Synergism have one progress key. Ethereal Farm's extra keys are a fixed suffix list. AD's backup names are a fixed grid. NG+3 is the only adapter that discovers ids, and it discovers them from the meta document by a cited formula.

`GameDefinitionValidator` already requires `storageContext.keys[]` with `name` and `format`. Definitions can list every static key. NG+3's static entry is the meta key plus a `pattern` note. A pattern field would be a schema addition. Until then, the adapter owns discovery and the definition lists the meta key and the legacy keys it still reads.

## Shared capture rules

- Call the game's save function only when its own guards allow it. If it declines, peek storage and keep the previous Hub snapshot.
- Do not call export helpers that grant resources or change speedrun flags, except where the helper restores those flags before returning.
- After a native import, compare progress markers, not bytes. Migrations and offline catch-up rewrite the payload.
- `acceptsRuntime` allows fields the game itself changes on load (clocks, offline gains) and requires the identity fields to match.
- A fresh default save is `FRESH`, not `PROGRESSED`. Recovery must not prefer a fresh save over an older progressed one.
- Two progressed saves that disagree on an unordered field are `INCONCLUSIVE`.

## `ethereal-farm-adapter` 1.0.0

Launch `https://lodev.org/etherealfarm/`. Origin `https://lodev.org`.

### Storage

Read the prefix from the page before any encode: `window.localstorageNameHTML` if it is a non-empty string, otherwise `local`. On the live page that prefix is `ef_state`, verified on WebView 155.0.8059.43. Do not hard-code `state_soft`. That string is the repo's unbundled `index.html` only. `saveNow` is not a global on that page, so the suffix keys were not observed in a fresh profile.

Required progress key: `<prefix>`.

Backup keys, captured and restored when present, not ranked against the primary:

- `<prefix>_undo`
- `<prefix>_success`
- `<prefix>_prev_version`
- `<prefix>_recover`
- `<prefix>_daily1`
- `<prefix>_daily2`

The game's own load uses primary, then undo (`game.js` `loadFromLocalStorage`). The Hub's `chooseStored` uses the same order: a valid primary, else a valid undo, else nothing. Daily, success, previous-version, and recover keys are never selected as "further progress". They are restored only as part of the same envelope so the game's recovery dialog still has them.

### Readiness

`typeof state !== 'undefined' && state && !state.save_error && typeof state.g_numticks === 'number'`, and at least one `<canvas>` in the document. The HTML shell has no game root. The canvas check is the visible-UI signal available without a new id. Confirm the selector on a disposable launch before coding the probe.

### Capture

Call `saveNow()` only when `typeof saveNow === 'function'` and `autoSaveOk()` would be true, or the player used an explicit Hub capture. `saveNow` runs `presave`, which writes notation onto `state`. Record that in the summary. Do not increment `g_numautosaves`; that belongs to the game's interval.

Validate by the game's own header: length, base64, leading `EF`, and a numeric version less than or equal to the running `version` global. Re-implementing `decState` in Kotlin is not required for the first version. `validate` can check the `EF` prefix, base64, and that the version nibble is not newer than a recorded maximum. A full decode stays in the page via `load`'s failure reason if an import is attempted.

### Restore

Bootstrap writes the envelope, then loads the real URL. Do not call `load()` on top of a bootstrap write. The game's `loadFromLocalStorage` is the loader. `acceptsRuntime` compares `state.g_numticks`, `state.g_numresets`, and the numeric version. Ticks may increase. Resets must not decrease. A newer numeric version than the snapshot's header is a failed restore.

### Comparison

Do not order two valid primary saves by length. Markers, in order, all required to move the same way:

- numeric version (must be equal to compare; a different version is `INCONCLUSIVE`)
- `g_numresets` if the header exposes it without a full decode

**Unverified:** which header characters expose ticks versus resets. `game.js` line 82 uses character index 6 as a ticks code. A full field ranking needs `decState` on the page, returning a small JSON view. The adapter should ask the page for that view rather than parse LZ77 in Kotlin. Experiment: from a disposable save, evaluate a read-only summary of `g_numticks`, `g_numresets`, `g_numplanted`, and version, and confirm a second save with more plants and fewer resets stays `INCONCLUSIVE`.

Until that view exists, `compare` returns `MATCH` only for equal primary strings, `LIVE_MISSING` / `NO_SNAPSHOT` as today, and `INCONCLUSIVE` for any other pair of valid saves. That refuses to resume or to pick a "later" backup. It is the safe behaviour.

### Recovery

If the primary key is missing or fails the `EF` check, the candidate is the undo key, then none. Do not promote `_daily1` automatically. The game shows those only in its recovery dialog.

### Failure

- Bootstrap marker missing: do not load the game (existing rule).
- `saveNow` throws or the primary key does not change to a valid `EF` value: keep the last Hub snapshot.
- Import calls `saveNow` inside the game (`loadForImportDialog`). The post-import baseline is whatever that writes, not the original export text.

## `trimps-adapter` 1.0.0

Implemented. Catalogue `assets/games/trimps.json`, profile `hub-game-trimps`, launch `https://trimps.github.io/`.

The envelope always lists `trimpSave1` and `trimpBackup1`. A missing backup value removes that key. PlayFab names are denied. `TrimpsLayout` adds a viewport meta when the page has none, pins `#offlineWrapper` above the board, and sets a readable type size. It does not change zoom and does not reload.

`save(true)` on a disposable profile returned an export string. `load` of that string was then captured by the normal `save()` path. No Hub import screen was added.

### Storage

Required: `trimpSave1`.

Optional backup: `trimpBackup1`, restored when present, never chosen over a valid `trimpSave1`.

Ignored: `playFabName`, `playFabPass`.

### Readiness

`typeof game !== 'undefined' && game.global && game.global.stringVersion === '5.10.1'`, and either a visible resource cell or `#offlineWrapper` displayed. A warp is a ready game. A title with no `game.global` is not.

Refuse the session if `stringVersion` is missing or newer than `5.10.1`. An older string is a live save the game can migrate; the adapter validates it by `load`'s rules, not by equality to 5.10.1.

### Capture

If `usingRealTimeOffline` is true, do not call `save()`. Peek `trimpSave1` and report `INCONCLUSIVE` against memory, because memory is mid-warp and storage is the pre-warp save. The Hub must not replace its snapshot with a partial warp.

Otherwise call `save()` and read `trimpSave1`. `save()` returns nothing on the storage path. Read the key after it. If the read-back is not the string just written, the game already treats that as a failed save (`main.js` lines 233-237). Treat it as a failed capture.

### Validate

LZ-decompress to JSON. Require `global` and `global.version`. Reject when `global.stringVersion` compares newer than `5.10.1` using the game's component order (`compareVersion` in `main.js`). Reject when `global.version < 0.13`. Do not require the decompressed object to equal a fresh `game`, because `save` deletes tooltips and costs.

### Restore

Bootstrap writes `trimpSave1` and, if the envelope has it, `trimpBackup1`. The game's no-argument `load()` performs migration. `acceptsRuntime` after load:

- `game.global.stringVersion` is `5.10.1` (the game rewrites this on load, line 328)
- `game.global.universe` equals the snapshot
- `game.global.totalPortals` is not lower
- `game.global.highestLevelCleared` is not lower
- helium / radon totals are not lower

World number and resources may move during an offline warp. If the warp is on screen, `acceptsRuntime` waits until `usingRealTimeOffline` is false, with a timeout, and then applies the inequalities. A warp that is still running at the timeout is not success and not a rollback by itself: leave the page and do not capture.

### Comparison

Order only when universe and portal counts compare in one direction and the other markers do not contradict them. A universe-2 save is not "ahead" of a universe-1 save. Different challenge flags with the same portals are `INCONCLUSIVE`. `chooseStored` prefers a valid `trimpSave1` over `trimpBackup1`. It does not compare those two for progress.

### Summary fields

`stringVersion`, `version`, universe, total portals, highest zone cleared, and whether `trimpBackup1` exists. No PlayFab fields.

### Failure

Storage exception on read matches the game's path that disables autosave (`main.js` lines 284-287). The adapter reports `PeekOutcome.Failed` and does not load. Do not disable the game's autosave from the adapter.

## `antimatter-dimensions-adapter` 1.0.0

Launch `https://ivark.github.io/AntimatterDimensions/`. Origin `https://ivark.github.io`. This id is not used for NG+3.

### Storage

Required progress key: `dimensionSave`.

Backup grid, only for the slot indexes present in the root, ids 1..8:

- `backupSave-<slot>-<id>`
- `backupTimes-<slot>`

If a key named `dimensionTestSave` is present, the page is the dev build. Fail the probe. Do not import it into this adapter.

`studyTree1..3` are legacy. Capture them if present so `moveSavedStudyTrees` can run, and do not treat them as a second current save.

The root holds three slots. The whole root is one progress document. Backup blobs are copies of one slot at an earlier time. A larger backup is not further progress than the root.

### Readiness

All of:

- `player` and `player.version === 25`
- `GameStorage.localStorageKey === 'dimensionSave'`
- `#browser-warning` computed display is `none`
- `#loading` is hidden
- `#ui` has a child

A supported-browser failure leaves the warning up and never reaches this probe. That is a failed open, not an empty save.

### Capture

Call `GameStorage.save(true, false)` only when `GameStorage.canSave()` is true. If it is false, peek `dimensionSave` and do not write. Read the root and the backup keys that exist. The summary records `current` and a per-slot view.

`export()` and `exportModifiedSave()` are not the capture path. They toggle speedrun segmented around the copy.

### Validate

Deserialize with the same steps as `GameSaveSerializer` or by calling it in the page and checking the result. A valid root has `current` in 0..2 and `saves`. The current slot, after the game's `checkPlayerObject`, has antimatter (`money` or `antimatter`) and no NaN properties. Player `version` must be less than or equal to 25. A newer version is `INCONCLUSIVE` and is not restored onto this build.

Pre-slot saves (no `saves` field) are valid inputs. The game migrates them in `loadRoot`. The adapter stores them unchanged and lets `load()` migrate.

### Restore

Bootstrap writes `dimensionSave` and the backup keys in the envelope, and removes backup keys that the envelope explicitly lists as absent for that slot. Then the normal document load runs `GameStorage.load()`.

`acceptsRuntime` on the current slot:

- `player.version === 25` after migration when the snapshot version was less than or equal to 25
- `player.records.fullGameCompletions` not lower, when both sides have it
- reality, eternity, and infinity counts not lower
- antimatter not lower by exponent

Challenge flags and the active slot index must match. If the snapshot's `current` is not the runtime slot, the restore failed. Other slots are not played; they are still part of the root and must round-trip in the stored document.

Offline simulation on load can raise currencies. It must not raise them by changing slot index or completion records backwards. The inequalities are one-way for currencies only.

### Comparison

Compare the active slot only when both roots have the same `current` and the same slot count structure. Markers: realities, eternities, infinities, then antimatter exponent. If a later marker is lower while an earlier one is higher, return `INCONCLUSIVE`. Do not compare slot 0 with slot 1. Do not compare a backup timestamp with the root. `chooseStored` selects the valid root with the same `current` as the profile pointer when the pointer validates; if several roots are incomparable, return null.

### Summary

`version`, `current` slot, per-slot realities / eternities / infinities / antimatter exponent, backup ids present. Not a single "progress percent".

### Failure

- `canSave()` false: keep the last good snapshot and say why (glyph, simulation, or end state).
- Browser warning visible: do not write storage.
- Firebase errors in the console are not a save failure unless `player` never appears.

## `antimatter-dimensions-ng3-adapter` 1.0.0

Launch `https://aarextiaokhiao.github.io/NG-plus-3/`. Origin `https://aarextiaokhiao.github.io`.

Never read or write `dimensionSave`. That name is vanilla, and on this origin it is also the legacy key `dimensionSave_aarexModifications` only as a migration source. The adapter may copy that legacy key if it is present in a profile that has not yet migrated. It must not create it.

### Storage

Required: `AD_aarexModifications` (meta).

Discovered after reading meta, not guessed:

- for each id in `metaSave.saveOrder`, key `btoa("dsAM_" + id)`
- for each id in `metaSave.presetsOrder`, key `btoa("dsAM_ST_" + id)` when that array exists

Also read, once, if present: `dimensionSave_aarexModifications`, `dimensionSave_NGM`. After `migrateOldSaves` the game deletes those. A Hub restore of an already-migrated envelope must not put them back.

Slot ids are integers allocated by the game. An envelope that contains a slot key whose id is not in `saveOrder` is invalid.

### Readiness

`game_loaded === true`, `player` is an object, `#container` display is `block`, `#loading` display is `none`, and `metaSave.current` is in `metaSave.saveOrder`. This is 1000 ms after `initGame` plus load time. `awaitReady` must cover that delay.

### Capture

Require `game_loaded && !noSave && !infiniteDetected`, matching `save_game`. Call `save_game(true)`. Then read meta and every slot and preset key. `save_game` does not rewrite meta. If meta in memory differs from the stored meta, write is the game's job (`initGame` and slot changes). The capture records both. A mismatch is `INCONCLUSIVE`, not a forced meta write from the adapter.

The `silent` parameter does not suppress `$.notify`. Capture will show "Game saved".

### Validate

Meta decodes to JSON with `current` and `saveOrder`. The current slot decodes to an object (`verify_save` only checks `typeof obj == 'object'`). Reject a current id that has no slot key. Do not require a field the 2023 tree adds only after `onLoad`.

### Restore

Bootstrap writes meta first in the same document as the slot keys, then the game URL. `initGame` calls `load_game`. Do not also call `import_save`. That function prompts.

`acceptsRuntime`: `metaSave.current` matches, `player.version` is not lower than the pre-migration snapshot once `onLoad` has assigned it, and antimatter (`player.money`) is not lower. `newGame3PlusVersion` may increase during migration. A decrease is failure. Other slots' raw strings must match the envelope after load, because `load_game` should not rewrite them. **Unverified** if `onLoad` of the current slot rewrites another slot. Experiment with two disposable slots.

### Comparison

There is no total order across modes (NG, NG+, NG+3, and the other flags this tree stores). Compare two envelopes only when `current` is the same id and `aarexModifications.newGame3PlusVersion` is equal. Then compare antimatter exponent and `player.totalTimePlayed` only if neither contradicts the other. Otherwise `INCONCLUSIVE`. `chooseStored` returns the envelope whose meta `current` matches the profile pointer and whose current slot validates. It does not pick the slot with the biggest string.

A profile with two slots is one envelope. Restoring it restores both. Import into a new slot is the import workflow, not `chooseStored`.

### Summary

Current slot id, `saveOrder` length, `newGame3PlusVersion`, `player.version`, antimatter exponent. Not a ranking against vanilla AD.

### Failure

- `infiniteDetected` or `noSave`: do not capture; keep the last snapshot.
- `prompt` or `confirm` during `onLoad`: the load is not ready. Do not capture a half-migrated player. See the import design for how the suite detects a stuck dialog.
- Meta without the current slot key: invalid, do not launch.

## `synergism-adapter` 1.0.0

Launch `https://synergism.cc/`. Origin `https://synergism.cc`.

### Storage

Required progress key: `Synergysave2`.

Not progress, not required for load: `language`, `theme`, `statSymbols`, `copyToClipboard`, `monospaceFont`, `synergism-tab-order`, `mobileHotkeyPanelCorner`, `mobileHotkeyPanelEnabled`. Leave them out of the envelope. The game defaults them.

If an LZ import runs, the game clears every key. The adapter must not depend on the preference keys surviving that call.

### Readiness

`typeof player !== 'undefined' && player && typeof saveSynergy === 'function'`, `#versionnumber` text contains `4.3.1`, and `#preloadRecovery` is not shown. `G.timeWarp === true` is still a loading game: wait until it is false before capture. A warp is not a failed load.

### Capture

Call `saveSynergy(false)`, not `exportSynergism()`. Then read `Synergysave2`. The call writes `player.offlinetick` and replaces `player.talismans`. The summary records `offlinetick` so a later comparison does not treat that clock as progress.

If `queueSave` returns false, capture failed.

### Validate

Base64 to JSON. Require an object that contains `version` or that `playerUpdateVarSchema` would still accept. The Kotlin side checks base64, JSON, and the presence of a small stable set taken from a fresh save in this version: `version`, and the resource fields the schema requires. If the schema's required set is too large to duplicate, validate by evaluating `playerJsonSchema.safeParse` is not available outside the bundle. Practical check: `atob` yields JSON and `importSynergism` is not used as a validator because it mutates. A page-side `playerUpdateVarSchema.safeParse(JSON.parse(atob(value)))` during probe is the validator, and it must not assign `player`.

**Unverified** until a disposable call shows `safeParse` is reachable on `window`. If it is not exported, validate structurally from a fixture generated by `saveSynergy()` on a fresh profile, and re-check the fixture when `Config.version` changes.

### Restore

Bootstrap writes only `Synergysave2`. `reloadShit()` loads it. `acceptsRuntime` requires the same `singularityCount` / `highestSingularityCount` (whichever the parsed save has; do not invent a field), quark totals not lower, and `version` equal to the running `Config` version after migration. `offlinetick` may increase. Export rewards must not change, because capture did not export.

### Comparison

Order by singularity count, then quarks, only when both move together. Otherwise `INCONCLUSIVE`. A longer base64 string is not ahead. `chooseStored` returns the only valid `Synergysave2` candidate; if two valid candidates disagree on the unordered case, return null.

### Summary

`version`, singularity counts, whether the payload was LZ-wrapped before the game converted it. The stored Hub payload is the post-conversion base64 the game wrote, plus the original export kept outside the profile by the import flow.

### Failure

- `#preloadRecovery` visible: do not capture.
- `G.timeWarp`: do not capture.
- Schema failure on import: the game alerts and returns false (`Synergism.ts` `loadSynergy`). Roll back. Do not clear storage from the adapter. Only the game's LZ branch clears storage, and only after a successful decompress.

## Reusable pieces

- Envelope codec and bootstrap writer. One implementation for Ethereal Farm, AD, and NG+3. Trimps and Synergism can use it with one entry so the session code has one path, while Shark and FE000000 stay on the raw string.
- `INCONCLUSIVE` when a progress view is null or the vectors disagree. Shared helper. Shark keeps its own total order.
- Pinned pre-import and post-import snapshots. The reasons already exist (`pre-restore`, `checkpoint`). The import design names them.
- Readiness script owned by the adapter, so `SharkProbe` is not the gate.

## What is not a contract change

- New game ids in the registry. Not in this milestone.
- A second profile per game. The import design refuses a silent overwrite in the one existing profile. Extra profiles are a later product decision.
- Ranking saves by size, mtime, or `player.lastUpdate` alone. `lastUpdate` moves on load even when progress does not.
