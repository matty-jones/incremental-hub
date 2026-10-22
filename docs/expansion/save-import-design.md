# Save import design

Research only. No import UI and no migration of existing Hub profiles.

The rule for every game: the original export text is kept, the game's own import or load path migrates it, the Hub pins a checkpoint before the write, checks the running game and the stored keys after, pins a post-import baseline, and does not replace an existing progressed profile unless that checkpoint exists and the player confirmed. A failed check writes the checkpoint back and does not leave the game running on the failed import.

Shark and FE000000 already do the checkpoint-and-rollback half of this for snapshot restore (`SessionController.confirmRecoverLocked` and `finishRollback`). Import is the same shape with a different payload source. Their save keys and profiles are not reused.

## What WebView can do in this app

`WebViewHost` sets `javaScriptEnabled` and `domStorageEnabled`. The chrome client records console lines only (`WebViewHost.kt` lines 111-117). There is no `onShowFileChooser`, no `DownloadListener`, and no `onJsAlert` / `onJsConfirm` / `onJsPrompt`.

Clipboard write exists for the diagnostics report (`SessionPanels.kt` lines 249-250). Nothing reads the clipboard into a game.

| Capability | Hub today | Needed for native UI import |
| --- | --- | --- |
| File selection | Not implemented. Ethereal Farm, vanilla AD, and Synergism open `<input type="file">` from script. | Either implement `onShowFileChooser`, or skip the file input and pass the text into the game function. The second is enough for the first version. |
| Clipboard read | Not implemented. AD and NG+3 try `document.execCommand('copy')` on export. | Not required. The adapter reads `localStorage` or the export string from the game function. |
| `alert` / `confirm` / `prompt` | Not implemented. Platform default is unverified. | NG+3 import and some NG+3 migrations call `prompt` and `confirm`. Trimps import uses a textarea, not `prompt`. Synergism and vanilla AD and Ethereal Farm use their own DOM. |
| Text import | Not implemented. | Required. A confirmed paste or share into the Hub, then a game function call. |
| Download of an export | Not implemented. AD and Synergism click a `data:` or blob link. | Not required for a round trip. The Hub already stores the payload. A "share export" action can put that text on the Android clipboard from Kotlin. |

**Experiment, before any import UI:** on the WebView build the suite will use, open a disposable page that calls `prompt`, `confirm`, `alert`, clicks `<input type="file">`, and clicks a `data:` download. Record whether a dialog appears, whether the JS call returns, and whether a file is chosen or saved. Do not infer that from the missing overrides alone. Android's `WebChromeClient` documents that returning false means the host is not handling the dialog; this app never overrides the methods, so the WebView default applies, and that default has not been observed here.

## Minimum Hub changes

1. A confirmed import action that accepts a text export. File-picker and download listener are not required for that path.
2. Envelope bootstrap from `adapter-designs.md`, so a multi-key checkpoint can be written and rolled back in one document.
3. Pinned snapshots before and after, using the existing `pre-restore` and `checkpoint` reasons. Add an origin value only if the current `originForReason` mapping is too coarse. `PRE_RESTORE` already maps to `recovery`. A distinct `import-pre` / `import-post` reason is an additive `SnapshotReasons` constant. It must be added to the prune exclusion list if it should survive like `pre-restore`.
4. Per-adapter `nativeImport`. The session never types into a guessed textarea.
5. Refuse when the current profile has `PROGRESSED` data and the player has not confirmed. Confirmation text names the game and that the current profile will be replaced only after a pinned checkpoint. Cancel leaves storage untouched.
6. For vanilla AD and NG+3, offer "new slot" when the game can add one without replacing the current slot. That path still checkpoints the whole envelope, because a bug in import can still rewrite the root or the meta key.

No change to Shark or FE000000 adapters. No automatic import on launch. No migration of an existing `snapshotPayload` into a new envelope. Old rows stay raw strings. Only new game ids use the envelope.

## Per game

### Ethereal Farm

Native function: `loadForImportDialog(shift, ctrl, text, messageFlex)`.

That function trims the text, calls `load`, and on success increments import counters, clears the recover key, and calls `saveNow()` (`ui_settings.js` lines 1162-1184). `shift` pauses and `ctrl` unpauses. The Hub passes both false.

The file button builds an input and then calls the same function. The Hub does not need the file button.

`load` rejects a newer numeric version, a too-old version, and a beta save. The failure callback does not write storage. The Hub still checkpoints first, because a successful import does write.

Post-check: primary key is `EF` base64, `state.save_error` is absent, and the runtime markers from the adapter match. The stored string will not equal the pasted export, because `saveNow` re-encodes after `presave` and the import counters changed.

Rollback: bootstrap the pre-import envelope, including backup keys. Do not call `load` again.

### Trimps

Native function: `load(exportString)` with the LZ string, not `load(true)`.

Observed on a disposable profile: `save(true)` returned a string, `load` of that string returned, and the next Hub capture validated. The captured primary hash differed from the pre-import capture, which is expected because `save()` rewrites `lastOnline`. No Hub import UI was added.

`load(true)` reads `#importBox` and clears PlayFab (`main.js` lines 266-278). `load(string)` migrates without that side effect. If the string is from before 5.0.0, the game writes `trimpBackup1` itself. The Hub's pre-import envelope already has the previous `trimpSave1`. After `load`, call `save()` only if `usingRealTimeOffline` is false, so the migrated game is what storage holds. If a warp starts, wait until it finishes, then `save()`, then peek.

Do not pass the string through the tooltip. The tooltip is a valid player path and is harder to verify.

Post-check: `stringVersion` is `5.10.1`, identity markers match the adapter rule, `trimpSave1` decompresses. Byte equality with the paste is not required.

A newer `stringVersion` makes `load` return false and leave the previous `game` in place only if it returns before `resetGame`. It calls `resetGame()` after the version check (`main.js` line 314 is after the newer-version return at 307-312). A rejected newer save does not reset. An accepted save does. If `load` returns false after `resetGame`, the profile is already changed. Checkpoint rollback is mandatory on any false return.

### Vanilla Antimatter Dimensions

Native function: `GameStorage.import(text)`.

Do not open `Modal.loadGame` and do not use the file input. `import` rejects secret strings and unknown formats (`storage.js` lines 154-161), migrates, may simulate offline, and saves.

If the profile's current slot is progressed, the default offer is not this call. `import` replaces the current slot inside the root and then `save()`s the whole root. Other slots remain only if `import` does not drop them. The code assigns the imported player onto the current slot (`loadPlayerObject` sets `this.saves[this.currentSlot] = player`) and saves the root. Other slots in `this.saves` stay. Checkpoint the whole root and backup keys anyway.

`overwriteSlot` exists but is not the UI import path. Do not call it for a player export. It skips the migration in `import`.

Offline: `loadPlayerObject` simulates when the gap is over 10 seconds and offline is enabled. A long-term desktop export will move. The post-import baseline includes that. The original paste is the thing the Hub keeps outside the profile. Verification uses the one-way marker rules, not bytes.

`canSave()` during the import's final `save(true)` can skip the write. If the key does not change to a document that contains the imported identity, roll back.

Speedrun segmented is forced true inside `exportModifiedSave`, not inside `import`. Import does not need that toggle.

### NG+3

`import_save` always prompts. Do not call it unless the experiment shows `prompt` returns a value the Hub can supply. The reliable path is the body of `import_save` after the prompt, which is not a separate function.

Preferred call, current slot empty: evaluate the same steps the function runs for `type == metaSave.current` after a string is in hand (`game.js` lines 2510-2525): decode, `verify_save`, assign `player`, `onLoad`, `startInterval`. Then call `save_game(true)` so the slot key matches memory. This is still the game's decode and `onLoad` migration. It is not a Hub reimplementation of NG+3 versions.

Preferred call, current slot progressed: the `type === "new"` branch (lines 2526-2541), which allocates an id and writes the raw export under `btoa("dsAM_" + newId)` without replacing `player`. Then checkpoint, then let the player change slot with `change_save` only after a second confirm. `change_save` saves the current slot first (`load_functions.js` line 2455). That is the game's behaviour and it is why the checkpoint exists.

Do not stub `window.prompt` to return the paste and then call `import_save`, until the dialog experiment shows the real `prompt` is unusable. A stub works only if nothing else calls `prompt` during `onLoad`. The NG+3.1 migration prompt is a known second caller (`load_functions.js` line 1322). If that runs, a stub that always returns the save text would answer the migration prompt with the save and take the wrong branch (`== "legacy"` would be false, so it would migrate). A stub is unsafe. Drive the import branch directly, and if `onLoad` calls `prompt`, abort and roll back. Detect that by wrapping `prompt` to count calls and return `null`, and treat any call as failure.

`confirm` during overwrite and delete is not on the empty-slot import path. Leave those buttons to the player. If they do not function until the dialog experiment is done, say so in the UI. Do not replace `confirm` with `true`.

### Synergism

Native function: `importSynergism(text)`.

On the live site the raw JSON must have `exporttest === true` or `exporttest === 'YES!'` (`ImportExport.ts` lines 382-408). Otherwise the function alerts and returns without writing. A production `saveSynergy()` payload qualifies: the blank player sets `exporttest: !TESTING` (`Synergism.ts` line 1064), and `playerJsonSchema` turns the string `'YES!'` into boolean `true` and leaves a boolean as it is (`PlayerSchema.ts` lines 1003-1008). `saveSynergy` stores that parsed object. A testing save (`false` or `'NO!'`) is rejected by `loadSynergy` on this host (`Synergism.ts` lines 1340-1342) and by `importSynergism`. Do not point the Hub at a testing build.

The accepted path base64-encodes the raw object, `queueSave`s it, then `reloadShit`. It does not add quarks. Quark and golden-quark grants are in `exportSynergism` (lines 248-271), not in `importSynergism`. `saveSynergy` at line 841 belongs to `promocodes`, which is a different call. Do not use it for import.

LZ input is decompressed inside `importSynergism` before that check. `reloadShit` still has its own LZ branch (`Synergism.ts` lines 4811-4828): if the string it was given still decompresses as LZ, it `localStorage.clear()`s on web and rewrites `Synergysave2`. The import path passes the already-converted base64 as `saveOverride`, so that clear runs only when the override itself is still LZ. A base64 capture does not clear. An old LZ export that is passed through `reloadShit` without the import function's conversion does. The Hub calls `importSynergism`, not `reloadShit`, so the clear is the game's LZ branch inside `reloadShit` only if `importSynergism` hands it an LZ string. Today it hands the converted base64 (`ImportExport.ts` lines 388-404). Preference keys survive that path. Say so, and still checkpoint in Room.

Do not call `exportSynergism` to test the round trip. Round-trip is `saveSynergy()`, read `Synergysave2`, pass that string to `importSynergism` on a fresh profile. Identity markers must match. Quark totals must not take the export bonus.

## Session sequence

1. Player confirms, or cancels. Cancel does not read the game.
2. If the profile has a progressed snapshot or a progressed live key, require the confirm. An empty profile does not skip the checkpoint. Pin an empty pre-import row so rollback has a defined state.
3. Peek the live envelope. Pin it as the pre-import snapshot, pinned, excluded from prune.
4. Keep the pasted export in that row's `progressJson` or in a sibling field. Do not stuff it into the game key. The original text must survive a re-encode.
5. Call `nativeImport`. The game migrates.
6. Wait for the adapter readiness probe. If a dialog wrapper counted a `prompt`/`confirm`/`alert`, abort.
7. Ask the game to save, under its own guards.
8. Peek. `acceptsRuntime` and `validate` must pass. For multi-slot games, untouched slots must still match the checkpoint.
9. Pin the new envelope as the post-import baseline and set the profile pointer to it.
10. On any failure from step 5 onward, bootstrap the pre-import envelope, confirm the peek matches it, and enter `RecoveryRequired` without loading further. This is `finishRollback` with an envelope.

A second import in the same profile repeats the sequence. It does not overwrite the pinned pre-import row of the first import. Those rows stay.

## What this does not do

- It does not merge two saves.
- It does not import a vanilla AD export into NG+3, or the reverse. The codecs and the meta keys differ. `GameStorage.import` on NG+3 is not a function. NG+3's `atob` of an `AntimatterDimensionsSavefileFormat` string throws or fails `verify_save`. The adapter must reject the other game's header before calling import.
- It does not import Synergism mods. The official `importSynergism` path is the only one in scope.
- It does not read PlayFab passwords, Firebase accounts, or Synergism login tokens.
