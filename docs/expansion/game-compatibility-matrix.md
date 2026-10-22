# Five-game compatibility matrix

Research only. Hub revision is `main` at `6e22863` (phone update 0.1.11). No adapter, database, or catalogue change is proposed as a code change in this document.

Confidence:

- **Verified:** the cited source and the live page agree, or the live file hash-matches the cited commit.
- **Source:** read from the cited commit. Not re-checked against a running WebView.
- **Unverified:** a disposable experiment is named. Do not treat it as a compatibility claim.

None of these games store the player save in IndexedDB. Searches for `indexedDB` / `IndexedDB` in the cited trees returned no matches.

## Catalogue

| Proposed game ID | Game | Launch URL | Origin | Source commit audited | Deployment |
| --- | --- | --- | --- | --- | --- |
| `ethereal-farm` | Ethereal Farm | `https://lodev.org/etherealfarm/` | `https://lodev.org` | `lvandeve/etherealfarm` `main` `592c2b84` (2026-10-18) | Not GitHub Pages. Live HTML is a different file from the repo `index.html`. |
| `trimps` | Trimps | `https://trimps.github.io/` | `https://trimps.github.io` | `Trimps/Trimps.github.io` `master` `b9edb1df` (2025-03-12) | Repo is the site. Live `index.html`, `config.js`, and `main.js` SHA-256 match that commit. |
| `antimatter-dimensions` | Modern vanilla Antimatter Dimensions | `https://ivark.github.io/AntimatterDimensions/` | `https://ivark.github.io` | Source `IvarK/AntimatterDimensionsSourceCode` `master` `5409e320` (2026-07-17). Release merge `0b70035a` (2026-07-17). | `release` is built and pushed to `IvarK/AntimatterDimensions` `master`. Live deploy commit message names `0b70035a` (2026-08-28, deploy commit `6872931a`). |
| `antimatter-dimensions-ng3` | NG+3 / Aarex's Modifications | `https://aarextiaokhiao.github.io/NG-plus-3/` | `https://aarextiaokhiao.github.io` | `aarextiaokhiao/NG-plus-3` `master` `9a99e040` (2023-01-11) | Live `index.html` SHA-256 matches that commit. `post-NG-plus-3` is older (2022-02-16, `bb1d18f1`) and is not the live page. |
| `synergism` | Official Synergism | `https://synergism.cc/` | `https://synergism.cc` | `Pseudo-Corp/SynergismOfficial` `master` `e4dd84e8` (2026-10-21 13:43:47 UTC) | `master` builds `dist/out.js` onto `gh-pages`. Canonical host is `synergism.cc`. |

Vanilla Antimatter Dimensions and NG+3 do not share an origin, a storage key, or a save codec. They still need separate Hub game IDs and profiles (`hub-game-<gameId>` in `SessionController.transitionTo`). NG+3's document title is also "Antimatter Dimensions".

Do not launch these builds:

- `https://ivark.github.io/AntimatterDimensionsSourceCode/` is the source repo `gh-pages` build. `npm run build:master` uses `.env.master` with `VUE_APP_DEV=true`, so that build's storage key is `dimensionTestSave`.
- `https://ivark.github.io/` is a landing page, not the game.
- `https://pseudonian.github.io/SynergismOfficial/` currently titles itself "Synergism v2.1.1".
- `https://pseudo-corp.github.io/SynergismOfficial/` is the same save logic as `synergism.cc` but a different origin. Login copy in the game sends the player to `synergism.cc`.

## Side-by-side

| Question | Ethereal Farm | Trimps | Vanilla AD | NG+3 | Synergism |
| --- | --- | --- | --- | --- | --- |
| Storage | `localStorage` | `localStorage` | `localStorage` | `localStorage` | `localStorage` on web. Capacitor Preferences only when `PLATFORM === 'mobile'`. |
| Progress keys | Live page sets `localstorageNameHTML = 'ef_state'`. Derived keys use that prefix. Repo `index.html` says `state_soft`. | `trimpSave1`. One-shot `trimpBackup1` when a pre-5.0.0 save is loaded. | Production key `dimensionSave` (DEV flag is false in the live bundle). Plus `backupSave-<slot>-<id>` and `backupTimes-<slot>`. | Meta key `AD_aarexModifications`. Slot keys are `btoa("dsAM_" + id)`. Preset keys are `btoa("dsAM_ST_" + id)`. Ids are allocated at runtime. | Progress key `Synergysave2`. Other keys are preferences, not progress. |
| Key names dynamic? | Prefix is fixed on the live page. Suffixes are fixed. | No. | Slot index is 0..2. Backup id is 1..8. Names are computed, not discovered from arbitrary strings. | Yes. The id is allocated. The encoding of a known id is fixed. | Progress key is fixed. |
| Autosave | 180 seconds of wall clock, plus a save on the tick after a player action when `state.saveonaction` is true. | 60 seconds, if the option is on and a real-time offline warp is not running. | `player.options.autosaveInterval`, default 30000 ms. Slider range in the options UI is 10..60 seconds. | `player.aarexModifications.autoSaveInterval` or 30 seconds. The timer increments once per second. | 5000 ms, skipped during `G.timeWarp`. |
| Explicit save | `saveNow()`. Settings button calls it. | `save(exportThis, fromManual)`. | `GameStorage.save(silent, manual)`. | `save_game(silent)`. The `silent` argument is unused; `$.notify` still runs. | `saveSynergy(button)`. |
| Force-save mutates runtime? | `presave` writes `state.notation` and `state.precision`. The interval path also increments `state.g_numautosaves` before `saveNow`. `saveNow` itself does not. | `autoBattle.save()`, `u2Mutations.save()`, and `playerSpire.save()` run before the copy is serialized. The deletions run on the copy. | `save()` does not change player progress. A manual save increments `GameStorage.saved` and can unlock secret achievement 12. `canSave()` can skip the write. | No player-field write in `set_save`. It does show a notice. | Yes. Sets `player.offlinetick` and replaces `player.talismans` with the current fragment map before encoding. |
| Persisted save equals memory? | After `saveNow`, key `ef_state` is the encoded `state`. Memory can be ahead between saves. | After `save()`, `trimpSave1` is the LZ string of the stripped copy, not a dump of every live field. | After a successful `save()`, `dimensionSave` is the serializer output of `{current, saves}`. The current slot object is the live `player`. | After `save_game`, the current slot key is `btoa(JSON.stringify(player))`. Meta is not rewritten on every save. | After `queueSave`, `Synergysave2` is `btoa(JSON.stringify(parsed player))`. Schema parsing can drop or coerce fields. |
| Encoding | Custom base64, signature `EF`, LZ77, checksum. Numeric version is `262144*(major+2)+64*minor+patch`. | `LZString.compressToBase64` of JSON. `global.stringVersion` and numeric `global.version`. | UTF-8, pako deflate, base64, `0`/`+`/`/` rewritten, wrapped as `AntimatterDimensionsSavefileFormat` + `AAB` + payload + `EndOfSavefile`. Player field `version` is 25 in this tree. | Base64 of JSON. `Infinity` becomes the string `"Infinity"`. Meta is base64 JSON, `metaSave.version` starts at 2.02. | Base64 JSON. Live saves store `exporttest: true`. `importSynergism` accepts LZ or base64 only when `exporttest` is `true` or `'YES!'`, then writes base64. `reloadShit` clears `localStorage` only if the string it was given still decompresses as LZ. |
| Native import | Custom dialog. `loadForImportDialog` then `saveNow()`. File path creates `<input type="file">` in script. | Tooltip textarea `#importBox`. `load(true)` reads it. `load(string)` parses that string and is not the from-import flag. | Vue modal text field calls `GameStorage.import`. Separate `<input type="file" accept=".txt">`. | `import_save` always calls `window.prompt`. Current slot is replaced with no confirm. | Custom DOM `Prompt` / `Alert`, plus `<input id="importfile" type="file">`. `importSynergism(string)`. |
| Native export | Custom dialog. Copy or download the encoded text. | `save(true)` returns the string. | `GameStorage.export` copies and temporarily forces speedrun segmented, then restores it. `exportAsFile` uses a `data:` link. | `export_save` fills `#output` and `document.execCommand('copy')`. | `exportSynergism` grants quark and golden-quark export rewards, then saves, then downloads. Do not call it to capture. |
| Startup | Inline script sets the storage prefix, then `minified.js`. `loadFromLocalStorage` runs before `begin()`. | Page scripts construct `game`, then `load()` with no argument reads `trimpSave1`. | `browserCheck()` then `init()`: cloud listener, then `GameStorage.load()`. | `<body onload="initGame()">`: meta, legacy migration, `load_game(false, true)`. Container is shown 1000 ms later. | `window` `load` handler calls `reloadShit()`, which reads `Synergysave2` unless a `saveOverride` is passed. |
| Pre-script restore safe? | Yes for `localStorage`, if every key is written before `minified.js`. The game replaces `state` from the primary key during its own load. | Yes. `load()` reads storage only when called with no string. | Yes. `GameStorage.load()` reads `dimensionSave` and then backup time keys. | Yes. `initiateMetaSave` and `get_save` read storage. Missing meta creates slot 1. | Yes for `Synergysave2`. Writing it before scripts is the same path as `getStoredSave()`. |
| Usable, not merely loaded | `state` exists and `load` did not return `save_error`. UI is built in script; no stable id is in the HTML shell. | `game.global` exists. Offline warp (`#offlineWrapper`) is a real session, not a blank page. | `player` loaded, `#browser-warning` stays `display:none`, `#ui` has been filled, loading overlay hidden. | `game_loaded === true` and `#container` is `display:block`. | `player` loaded and `#versionnumber` is filled. `#preloadRecovery` appearing means the load did not finish. |
| Offline progress | Wall clock advances `state` through the update loop. A gap over 300 seconds sets `large_time_delta`. | If `lastOnline` is at least 300000 ms old and the option is 1 or 2, `offlineProgress.start()`. Autosave is suppressed while `usingRealTimeOffline`. | If offline is enabled and the gap is over 10000 ms, `simulateTime`. A gap over 14 days can also open a catch-up modal. | If `aarexModifications.offlineProgress` and the gap is over 1000 seconds, `simulateTime(diff/1000)`. | `exitOffline()` after intervals start. Autosave does not run while `G.timeWarp`. |
| Leaves the origin? | No router found. README URL is the game. | Same document. News and links can leave. Hub does not block navigation today. | News and credits link to other games, including `https://trimps.github.io/` and FE000000. | Same document. External font CSS from `fonts.googleapis.com`. | Canonical links and login point at `synergism.cc`. Changelog is `changelog.synergism.cc`. |
| Mobile / fold | Live HTML has no viewport meta. `body` hides both scrollbars. Pixel art uses `image-rendering: pixelated`. | No viewport meta. Layout is Bootstrap columns and fixed tooltips. Offline warp has its own full-page UI. | No viewport meta in `public/index.html`. Options include a fit-to-width path used by the Hub for FE000000, not for AD. Browser gate rejects user agents that fail `supportedBrowsers`. | No viewport meta. Body is `height: 95vh`. `#container` uses small fixed font sizes. `window.prompt` / `confirm` / `alert` are part of save management. | Viewport meta is `width=device-width, initial-scale=1.0, viewport-fit=cover`. Mobile hotkey panel exists. Web build still uses `localStorage`. |
| External requirements | Live page is one same-origin script, `minified.js?v=20261018`. | Same-origin scripts. Kongregate API is optional and absent on the GitHub Pages build. | Google fonts, Font Awesome, CodeMirror CSS, two `.webm` loops. Firebase is present in the live app bundle (`firebaseio.com`). `Cloud.init` returns immediately and registers an auth listener; `GameStorage.load` is not waiting on it. | jQuery 3.2.1 and notify are same-origin. Fonts are Google CSS. | `dist/out.js` same-origin. Fonts on `synergism.cc` are rewritten by the CDN. Changelog JSON is cross-origin. Mock service worker is registered only when `DEV || TESTING`. |
| Old-save risk | Load rejects a newer numeric version (reason 7) and a too-old version (reason 8). Beta saves do not load into the main game (reason 9). | A newer `stringVersion` is refused. `killSavesBelow` is 0.13. Pre-5.0.0 loads write `trimpBackup1` first. | `migrations.patchPreReality` / `patchPostReality`. Very old saves are base64 without the current header (`decodeText` falls back to `atob`). A DEV test save will not migrate the same way on the live build. | `onLoad` walks `player.version` and `newGame3PlusVersion`. A save newer than the 2023-01-11 code is outside this tree. `prompt` / `confirm` run during some migrations. | `playerUpdateVarSchema` migrates. Failure alerts and leaves the previous player. LZ imports wipe every key on the origin. |

## Per-game evidence

### Ethereal Farm

Repo: `lvandeve/etherealfarm`, branch `main` only, commit `592c2b84b9bfb02ffd65ad82e3ef1b9cdf492256`. README line 3 is `https://lodev.org/etherealfarm/`. GitHub Pages is not enabled for the source repo.

Live `https://lodev.org/etherealfarm/` (fetched 2026-10-22) is 1723 bytes and does not match repo `index.html` (3476 bytes). The live inline script is:

```html
var localstorageNameHTML = 'ef_state';
```

It then loads `minified.js?v=20261018`. Repo `index.html` line 49 sets `localstorageNameHTML` to `state_soft` and loads the unbundled scripts. The Hub must use the live prefix.

`strings.js` lines 19-25:

- `localstorageName` is `window.localstorageNameHTML` when that is set, otherwise `'local'`.
- suffixes: `_recover`, `_success`, `_prev_version`, `_undo`, `_daily1`, `_daily2`.

`game.js` `clearLocalStorage` also removes `_manual`, `_transcend`, `_prev_version2`, and `_daily3` (lines 219-230). Those are not written by the current save path.

`utils.js` `setLocalStorage` / `getLocalStorage` (lines 233-242) use `window.localStorage[name]`.

`main.js` lines 26-31 set `version_major = 0`, `version_minor = 18`, `version_patch = 4`, `version_sub = 3`. `formatVersion` appends a letter when `version_sub > 0`, so this source displays `0.18.4d`. The numeric code is `525444`. The live bundle contains the literal `525444` and the changelog text `0.18.4 (2026-09-19)`. It does not contain the literal `0.18.4d` or `ef_state`. The prefix is applied by the live HTML, and the minified decoder uses `window.localstorageNameHTML` (found in the bundle). **Verified** on WebView 155.0.8059.43: `document.title` is `Ethereal Farm v0.18.4d` and `window.localstorageNameHTML` is `ef_state`. `typeof saveNow` is `undefined` on that page, and a fresh profile's `localStorage` was still empty two seconds later, so the suffix keys are not yet observed at runtime.

`save.js` `save` (line 3284) calls `presave` then `encState`. `presave` (line 3250) writes notation and precision onto `state`. `game.js` `saveNow` (line 48) stores the encoded string on the primary key and, unless the window is unloading, copies it onto the daily key. Autosave is `time > lastSaveTime + 180` in `game.js` line 7009. `util.getTime` (`utils.js` line 659) is `Date.now()/1000`. The action autosave is lines 7028-7030.

`loadFromLocalStorage` (`game.js` lines 56-110) reads the primary key, then `_undo`. A lower numeric version is copied to `_prev_version` before load. A failed `EF` save is copied to `_recover` only if that key is empty. Success of a substantial save updates `_success` when the ticks code is at least the previous success. Import (`ui_settings.js` `loadForImportDialog`, line 1162) increments `g_numimports`, clears `_recover`, and calls `saveNow()` so a mobile kill does not reload the previous save.

Failure reasons are listed in `utils_save.js` lines 1413-1423. Reason 7 is a save newer than the game. Reason 8 is too old. Reason 9 is a beta save in the main game.

There is no service worker. The live document does not declare a viewport.

### Trimps

Repo: `Trimps/Trimps.github.io`, `master` `b9edb1dfee270ebf81bef8d934dc71276bf8d963`. `dev` `b6f32eb8` is the 2025-03-12 parent merged by that master commit. Live `index.html`, `config.js`, and `main.js` hash-match the local `master` files. `index.html` title is `Trimps 5.10.1`. `config.js` line 25 is `stringVersion: '5.10.1'`. Line 30 is `killSavesBelow: 0.13`.

`main.js` `autoSave` (lines 44-46) calls `save()` every 60000 ms when autosave is enabled and `usingRealTimeOffline` is false. `restartAutoSave` (line 39) resets that timer. `save` (line 53) calls `autoBattle.save()`, `u2Mutations.save()`, and `playerSpire.save()`, stringifies `game`, deletes presentation fields on the copy, and writes `LZString.compressToBase64` to `trimpSave1` (line 232). `save(true)` returns the string and does not write (line 220). A failed read-back posts a notice and does not throw the previous key away.

`load` (line 258) with no argument reads `trimpSave1` (line 282). `load(true)` reads `#importBox` (`updates.js` line 1458). A string argument is decoded directly and does not set the from-import flag, so it does not clear PlayFab. A newer `stringVersion` returns false (lines 307-312). Crossing into 5.0.0 writes the raw previous string to `trimpBackup1` (lines 812-816) before continuing migration.

`checkOfflineProgress` (line 3124) returns if `lastOnline` is less than 300000 ms old. Options 1 and 2 call `offlineProgress.start()` (line 2674). The warp UI is `#offlineWrapper` in `index.html`. Autosave will not run during the warp. A Hub capture that calls `save()` during the warp would persist a partial warp; the game itself refuses to do that.

Other keys: `playFabName` and `playFabPass` (`main.js` lines 19878-19889). Those are credentials, not progress. Do not capture them.

No IndexedDB, no service worker, no viewport meta. Kongregate is feature-detected; the Pages build shows an error on the bone button when `kongregate` is undefined (`main.js` lines 24-29) and continues.

### Vanilla Antimatter Dimensions

Source commit `5409e320cecef96a917cca1dfb68f1f183e499ca` on `master`. `release` `0b70035ab849f3d79c3a873e94171c62b3d08cf5` is "Merge remote-tracking branch 'origin/master' into release" at 2026-07-17T20:19:03Z. The public site's latest deploy commit (`IvarK/AntimatterDimensions` `6872931a`, 2026-08-28) says it deployed source `0b70035a`.

Live page `https://ivark.github.io/AntimatterDimensions/` loads `js/app.8440675a.js`. That bundle contains `player` field `version:25`, changelog entry `Celebrate iOS Version Release` dated `[2026, 7, 17]`, and:

```text
y="true"==={NODE_ENV:"production",BASE_URL:""}.VUE_APP_DEV
get localStorageKey(){return y?"dimensionTestSave":"dimensionSave"}
```

`VUE_APP_DEV` is absent on that object, so the production key is `dimensionSave`. This matches `storage.js` lines 91-101 when `DEV` is false (`src/env.js` line 1). `.env.master` sets `VUE_APP_DEV=true` and is what `build:master` uses. Do not point the Hub at the SourceCode Pages URL.

`version.txt` on the live site is `{"version" : 11.5, "message" : "Refresh now for some more new gameplay stuff and Secret Achievements."}`. `intervals.js` lines 69-76 compare that number with `player.version`. 11.5 is not greater than 25, so this file does not describe the save version.

`GameStorage.save` (`storage.js` lines 253-263) writes one JSON root, `{current, saves}`, containing slots 0, 1, and 2. `loadRoot` (lines 111-137) migrates a pre-slot save into slot 0. Backups are separate keys. `AutoBackupSlots` (lines 17-64) uses ids 1..8: online, offline, and one reserve. `saveToBackup` writes `backupSave-<currentSlot>-<backupSlot>` and `backupTimes-<currentSlot>`.

`GameIntervals.save` (`intervals.js` lines 57-59) restarts from `player.options.autosaveInterval` (default 30000, `player.js` line 806) minus time since `lastSaveTime`. `canSave` (lines 245-250) returns false during glyph selection, an active progress modal, or the end-game save lock. A capture that ignores `canSave` and writes storage itself would violate the game's own guard. Call `GameStorage.save()` and accept a skipped write.

`GameSaveSerializer` (`serializer.js`) version `AAB` (line 58). Header `AntimatterDimensionsSavefileFormat`. Old saves without the header are `atob`'d (lines 124-130). `migrations.js` `moveSavedStudyTrees` (lines 540-544) copies legacy `studyTree1..3` into the player and is a migration, not a second live store.

`init` (`game.js` lines 1085-1097) calls `Cloud.init()` then `GameStorage.load()`. The live bundle contains `firebaseio.com`. `Cloud.init` (cloud-saving.js line 284) returns immediately when Firebase is absent, and otherwise only registers `onAuthStateChanged`. Local load does not wait for the network. **Unverified:** whether a failed Firebase request stalls the WebView. Experiment: load the production URL with Firebase hosts blocked and confirm `player.version === 25` still appears.

`supportedBrowsers` (`supported-browsers.js`) accepts Chrome/Chromium 79 and later, among other browsers. Android System WebView user agents include a Chrome token. **Verified** on WebView 155.0.8059.43: the user agent contains `Chrome/155.0.8059.43` and `; wv`, and `#browser-warning` computed display is `none`.

Import modal (`ImportSaveModal.vue` `importSave`, line 135) calls `GameStorage.import`. That migrates, may simulate offline, and `save(true)`s. File import is `OptionsSavingTab.vue` lines 176-180. Export-to-clipboard uses `document.execCommand('copy')` (`extensions.js` lines 75-90). File download is a `data:` anchor (`storage.js` `download`, line 552).

`news.js` names `https://ivark.github.io/AntimatterDimensions/` as the web game. The same file links to other incrementals. The Hub does not implement `shouldOverrideUrlLoading`.

### NG+3

Repo `aarextiaokhiao/NG-plus-3` `master` `9a99e0404b589eb6352e1d927c5388a29fd6b959`. Live `https://aarextiaokhiao.github.io/NG-plus-3/index.html` is byte-identical to that file (344416 bytes, SHA-256 prefix `d436054821b9104c`). `post-NG-plus-3` is older than `master`. This is the NG+3 tree, not a later Aarex mod.

`load_functions.js` lines 2-6, with `betaId === ""`:

- `savePrefix` = `dsAM_`
- `presetPrefix` = `dsAM_ST_`
- `metaSaveId` = `AD_aarexModifications`

`set_save` (line 2988) writes `localStorage[btoa(savePrefix + id)]`. For id `1` the key is `ZHNBTV8x`. For id `2` it is `ZHNBTV8y`. Those examples are consequences of the formula, not a claim that a profile only has slots 1 and 2. `new_game` (line 2579) allocates `metaSave.current` as the smallest positive integer not in `saveOrder`.

`initiateMetaSave` (line 3000) parses the meta key or creates `{presetsOrder: [], version: 2.02}` and then slot 1. `migrateOldSaves` (line 3014) still reads legacy `dimensionSave_aarexModifications` and `dimensionSave_NGM` once.

`initGame` (`game.js` line 6431) writes meta, calls `load_game(false, true)`, sets `game_loaded = true`, and 1000 ms later shows `#container` (lines 6457-6459). `load_game` (`load_functions.js` line 2348) loads the current slot and calls `onLoad`. Offline simulation runs when the gap is over `1000*1000` ms (line 2286).

`save_game` (line 2413) returns immediately when `!game_loaded || noSave || infiniteDetected`. Otherwise `set_save(metaSave.current, player)` and notifies. It does not rewrite meta. Autosave is `runAutoSave` (`game.js` lines 4431-4438) from a one-second interval (line 4737). Default interval is 30 (`getAutoSaveInterval`, `load_functions.js` line 2432). Fresh NG+3 players get `newGame3PlusVersion = 2.21` in `doNGPlusThreeNewPlayer` (`game.js` line 986). Later migrations in `onLoad` raise that number; one assignment is 2.302 (`load_functions.js` line 626).

`import_save` (`game.js` line 2450) calls `prompt`. Import into the current id replaces `player` and calls `onLoad` without `confirm`. Import `type === "new"` appends a slot and writes the raw prompt text under `btoa(savePrefix + newSaveId)` (line 2530). Overwrite of a different existing slot and delete/reset use `confirm`.

`export_save` (line 2505) puts the current player, or another slot's raw storage value, into `#output` and tries `document.execCommand('copy')`.

Some migrations call `prompt` or `confirm` (`load_functions.js` lines 875 and 1322). A save that hits those paths will stall if `window.prompt` / `window.confirm` do not return. That is an emulator experiment, not a known WebView result. See `save-import-design.md`.

The document pulls `https://fonts.googleapis.com` (index.html lines 13-15). No service worker. No viewport meta. Title is "Antimatter Dimensions", the same title as vanilla. The origin is `https://aarextiaokhiao.github.io`, so any other page on that host shares `localStorage` inside one WebView profile. The Hub profile name must stay `hub-game-antimatter-dimensions-ng3`.

Long-term saves from after 2023-01-11 are **unverified**. This commit is the live site, and it is years old. Experiment: import a disposable post-2023 NG+3 export only after a newer upstream commit is identified. Do not assume the 2023 migrators accept it.

### Official Synergism

Repo `Pseudo-Corp/SynergismOfficial` `master` `e4dd84e8c68095a6b23b9090d29773d97e2bebfe`. `src/Config.ts` line 1 is `version = '4.3.1 September 25, 2026: The Purple'`. Line 3 sets `isSynergismCC` from `location.hostname === 'synergism.cc'`.

Live `https://synergism.cc/dist/out.js` contains that version string, `Synergysave2`, and `Date.UTC(2026,9,21,13,44,36)`. The `gh-pages` bundle at `https://pseudo-corp.github.io/SynergismOfficial/dist/out.js` is the same bytes after replacing `Date.UTC(2026,9,21,13,44,23)`. The 13-second gap is the build timestamp from `scripts/update-last-updated.js` in `.github/workflows/deploy-ghpages.yml`. Save logic matches. The origins do not. Launch `https://synergism.cc/`.

`SaveStorage.ts` line 1: `SAVE_STORAGE_KEY = 'Synergysave2'`. `queueSave` (line 88) writes `localStorage` when `PLATFORM !== 'mobile'`. The web Hub is that path. The Capacitor branch is the official mobile app, not Android WebView.

`saveSynergy` (`Synergism.ts` lines 1246-1289) updates `player.offlinetick` and `player.talismans`, parses with `playerJsonSchema`, and stores `btoa(JSON.stringify(p))`. Autosave is `constantIntervals` (lines 4334-4338), every 5000 ms, skipped when `G.timeWarp`.

`exportSynergism` (`ImportExport.ts` line 248) adds golden quarks and worlds, then saves. A capture must call `saveSynergy()`, not `exportSynergism()`.

`importSynergism` (line 365) accepts LZ or base64. `reloadShit` (line 4783) reads storage, and if LZ decompression succeeds it calls `localStorage.clear()` on web (lines 4823-4828) before `persistSave`. That deletes `language`, `theme`, `statSymbols`, `copyToClipboard`, `monospaceFont`, `synergism-tab-order`, and the mobile hotkey keys. Those are not progress. A Hub snapshot of progress is `Synergysave2` only. Restoring preferences is optional and must not be required for a valid load.

`Alert` and `Prompt` (`UpdateHTML.ts` lines 1174 and 1305) are DOM dialogs (`#alert`, `#prompt`), not `window.alert` / `window.prompt`. File import is `#importfile` (`EventListeners.ts` line 2241) through `importData` (`ImportExport.ts` line 338).

`reloadShit` shows `#preloadRecovery` if loading exceeds 10 seconds (lines 4775-4789). Readiness is the absence of that overlay, a parsed `player`, and text in `#versionnumber` (`index.html` line 2537).

Viewport meta is on the live page. `ticksPerSecond` is 200 on web (`Config.ts` line 7). No production service worker: `Synergism.ts` lines 4965-4972 start the mock worker only for `DEV || TESTING`.

`navigator.storage.persist` is requested after a successful load (lines 4953-4958). **Unverified:** whether WebView grants persistent storage. The save still uses `localStorage` either way.

## What the Hub does today

These constraints are why the games do not drop in unchanged.

- `GameAdapter.storageKey` remains the single raw key for Shark and FE000000. `StorageBootstrap.document` writes that raw string unless the payload is a `hubEnvelope` 1 document.
- `SessionController` peeks before the game scripts run, then loads the real document only when `shouldResumeLive` is true. `shouldResumeLive` is false for `LIVE_BEHIND`, `LIVE_MISSING`, and `INCONCLUSIVE`.
- Shark ranks progress and will not treat a merely different payload as ahead (`SharkProgress.kt`). FE000000's comparison is unchanged and is described below. Do not copy it.
- Capture runs only in `Playing`, validates, and refuses to replace `snapshotPayload` on failure. Cadence is 60 seconds. A failed envelope capture also leaves the pointer where it was.
- Automatic snapshots keep the latest 10 unpinned rows. Pinned rows and the reasons `migrated-baseline`, `diagnostic-backup`, `pre-restore`, `workstation`, and `checkpoint` are not pruned.
- Profiles are `hub-game-<gameId>`. Separate game IDs already isolate vanilla AD from NG+3.
- `WebViewHost` enables JavaScript and DOM storage. Its `WebChromeClient` only records console messages. It does not implement a file chooser, a download listener, or JS dialog callbacks. `navigationRules` are validated in JSON and not enforced.
- Shark-only layout CSS is injected when the URL contains `shark-incremental`. FE000000 is the only game allowed to leave Hub page zoom.
- Readiness probes are adapter-specific. `productionDocumentProbe` evaluates `adapter.readinessScript`. Shark's script is `SharkProbe.JS`. FE000000's script does not read Shark fields. An empty script reports `no-adapter-probe`.
- Opted-in adapters can store several `localStorage` keys in one `hubEnvelope` 1 string. Shark and FE000000 stay on the raw single-key path. A missing `value` removes that key. `"value": ""` stores an empty string. Diagnostics record SHA-256 and length, not save text.
- FE000000 `compare` still returns `LIVE_AHEAD` for any two valid unequal payloads (`Fe000000Adapter.kt`). That is not a progress order. New adapters do not copy it. Their default compare returns `INCONCLUSIVE` when progress views are missing or disagree.
- The Pixel Fold AVD (`PixelFold_API34`, `emulator-5556`) launches the Hub on WebView `155.0.8059.43`. The image's stock provider is still `113.0.5672.136`, which throws from `ProfileStore.getInstance()`. See `emulator-webview-report.md`.

## Device checks on WebView 155.0.8059.43

Disposable profiles on `PixelFold_API34` (`emulator-5556`), 21 October 2026. No production file picker was added. `ProfileStore.deleteProfile` cannot remove a profile that `getOrCreateProfile` has already loaded in the same process, so these profiles are deleted at the start of the next process.

| Check | Result |
| --- | --- |
| B1 Hub launch and Shark across postures | Verified. `FoldProfileContinuityTest` got past `ProfileStore`, created `hub-game-shark-incremental` and `hub-qual-fold-probe`, and reached Shark's usable UI. The same WebView object, in-page token, and page-finish count survived `cmd device_state state 0` (physical 1080x2092) and `state 2` (physical 2208x1840). |
| B2 `alert()` | Appeared. The default dialog blocked JavaScript until it was accepted, then the call returned. |
| B2 `confirm()` | Appeared. Same as `alert()`. |
| B2 `prompt()` | Appeared. The dialog blocked JavaScript. Accepting the focused field required moving to the button, then the call returned. |
| B2 `<input type="file">` | Returned. A tap on a visible file input did not open DocumentsUI or another chooser within 3 seconds, and the page stayed responsive. |
| B2 `data:` download | Returned. A tap on `<a download href="data:text/plain,...">` did not show a chooser or a notification for that file within 3 seconds. |
| B3 Ethereal Farm title | Verified. `document.title` is `Ethereal Farm v0.18.4d`. `window.localstorageNameHTML` is `ef_state`. |
| B3 keys after `saveNow()` | Blocked. On the live page `typeof saveNow` is `undefined`, and `Object.keys(localStorage)` was `[]` two seconds later on a fresh profile. The suffix list is not yet observed at runtime. |
| B4 AD browser gate | Verified. `navigator.userAgent` contains `Chrome/155.0.8059.43` and `; wv`. `#browser-warning` computed display is `none`. The document title is `Antimatter Dimensions`. |

## Unresolved experiments

Run these on disposable profiles. Do not use a personal save.

1. Ethereal Farm key names after a real save. Title and `ef_state` are verified above. `saveNow` is not global, so the key list is still unobserved.
2. Done for this WebView. See B4 above. NG+3 still has its own prompt-migration experiment.
3. Done for this WebView. See B2 above. A file picker is not required for Trimps text import.
4. NG+3: a synthetic slot with a non-obvious id, kill the WebView, and confirm `btoa("dsAM_" + id)` still loads. Then a synthetic save that hits the `prompt("Welcome to the NG+3.1 update!...")` migration (`load_functions.js` line 1322), to see if it stalls. `prompt()` itself appears and returns on this WebView once the dialog is accepted.
5. Synergism: call `saveSynergy()` twice and record which player fields change besides `offlinetick` and `talismans`. The source shows those two writes; a runtime diff is the check that minification did not add another.
6. AD: call `GameStorage.save()` during glyph selection and confirm storage is unchanged (`canSave` returns false). Source is clear; the experiment is only to confirm the live bundle matches.
