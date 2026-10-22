# Implementation roadmap

Milestones 0 and 1 are implemented on `expansion/save-envelope`. No new game is registered and no release APK is produced. Later milestones in this list are still research. Shark Incremental and FE000000 keep their existing save and comparison behaviour.

Estimates are relative: **S** is a few files and the existing test style, **M** is a new adapter plus instrumentation, **L** is multi-key or import UI. They are not calendar time.

## Blockers to clear before coding an adapter

| Id | Blocker | Why it blocks | Done when |
| --- | --- | --- | --- |
| B1 | Pixel Fold AVD died in `ProfileStore.getInstance()` on WebView 113.0.5672.136. | The qualification plan's fold gates cannot run. | Done on this branch. WebView 155.0.8059.43 is installed on `PixelFold_API34`. `FoldProfileContinuityTest` launches the Hub, creates a profile, and keeps one Shark WebView across folded and unfolded postures. `Pixel6_API34_PS` was not deleted. |
| B2 | JS dialog, file input, and `data:` download were unobserved. | NG+3 migrations call `prompt`. File import is optional if text import exists. | Done. On WebView 155.0.8059.43, `alert`, `confirm`, and `prompt` appear and return. A file input and a `data:` download return without a system UI. Recorded in `game-compatibility-matrix.md`. |
| B3 | Ethereal Farm live `document.title` suffix was not in the bundle as a literal. | The adapter must know the running version it refuses to exceed. The numeric code `525444` is verified. | Title verified: `Ethereal Farm v0.18.4d`, prefix `ef_state`. The key list after `saveNow()` is still blocked: `saveNow` is not a global, and a fresh profile's `localStorage` stayed empty. |
| B4 | AD user agent versus `supportedBrowsers` was unverified on-device. | A failed check never calls `init()`, so there is no save to capture. | Done. UA contains `Chrome/155.0.8059.43`. `#browser-warning` display is `none`. |

B2 does not block Trimps. B3's remaining gap blocks only the Ethereal Farm key list, not the version ceiling. B4 does not block vanilla AD's browser gate on this WebView. B1 does not block fold evidence.

## Milestone 0 — Close the evidence gaps

**Status:** done, with one remaining gap. B1, B2, and B4 are verified on WebView 155.0.8059.43. B3's title and `ef_state` prefix are verified. The key list after `saveNow()` is blocked because that function is not global. See `game-compatibility-matrix.md`.

**Depends on:** this research. **Complexity:** S. **Release:** none.

Disposable profiles only.

Definition of done:

- B2, B3, and B4 have results appended to `game-compatibility-matrix.md`, each marked verified or still blocked, with the WebView version.
- B1 is either fixed or explicitly left blocked, with the log line.
- No production behaviour change. A temporary instrumentation activity is allowed only if it is not registered as a launcher game and is not required to ship.

## Milestone 1 — Envelope bootstrap, without new games

**Status:** done on this branch. No new game id is registered.

**Depends on:** Milestone 0 for B2, because the import API should not grow a file picker that the experiment showed is unnecessary. The bootstrap itself does not depend on B2. **Complexity:** M.

Additive defaults on `GameAdapter`. `StorageBootstrap` writes a `hubEnvelope` 1 document and still writes a raw string for Shark and FE000000. `SessionController` stores and rolls back envelopes only when the adapter opts in. `INCONCLUSIVE` remains a non-resume. Shark's total order and FE000000's `compare` stay.

Definition of done:

- Unit tests: one-key bootstrap bytes for Shark and FE000000 unchanged; envelope write and remove; rollback restores every key; `chooseStored` default does not rank by length.
- Existing Shark instrumentation still passes on a device that can create a profile.
- No new file under `assets/games/`. `GameAdapters.create` still returns null for the five new ids.

**Release boundary:** this can ship inside a Hub update. It must not change capture bytes for the two current games.

## Milestone 2 — Trimps

**Depends on:** Milestone 1. **Complexity:** M.

First game. One progress key, live files hash-match `b9edb1df`, import is `load(string)`, offline warp has an explicit flag the capture must obey.

Definition of done:

- Adapter and definition, id `trimps`, launch `https://trimps.github.io/`.
- Qualification cases for Trimps except fold, or fold too if B1 is done.
- `playFabPass` absent from snapshots.
- Shark and FE000000 regression from Milestone 1 still passes.
- A failed capture does not replace the profile pointer.

**Release boundary:** Trimps can ship alone, after its qualification record is attached to the change. It does not wait for the other four.

## Milestone 3 — Synergism

**Depends on:** Milestone 1. Can follow Trimps in parallel after Milestone 1, not before the regression suite is green. **Complexity:** M.

Launch `https://synergism.cc/` only. Capture calls `saveSynergy(false)`. Round-trip tests must show quark totals do not take the export bonus.

Definition of done:

- Qualification cases for Synergism except fold.
- An LZ export through `importSynergism` is stored as base64 and does not clear preference keys. `exporttest: false` is rejected and does not write. A raw LZ string passed into `reloadShit`, if that function is reachable, clears origin storage and still rolls back from the Room checkpoint.
- Version string in the readiness probe matches the cited `Config.ts` or the matrix is updated in the same change.

**Release boundary:** its own ship, after Trimps or before it. Do not combine with NG+3.

## Milestone 4 — Ethereal Farm

**Depends on:** B3 and Milestone 1. **Complexity:** M.

Prefix comes from `localstorageNameHTML` on the live page. Primary key is the only resume candidate. Backup suffixes are stored and restored, not ranked.

Definition of done:

- Qualification cases, including "daily backup is not chosen over primary".
- A newer numeric version is refused and does not load.
- Beta fixture (reason 9) is refused if one can be produced without a personal save. If it cannot, the case is documented as skipped, not passed.

**Release boundary:** own ship.

## Milestone 5 — Vanilla Antimatter Dimensions

**Depends on:** B4 and Milestone 1. **Complexity:** L.

Launch `https://ivark.github.io/AntimatterDimensions/`. Probe fails closed on `dimensionTestSave` or a visible browser warning.

Definition of done:

- Three slots in one root survive kill and relaunch.
- Backup keys are not selected as the current save.
- `canSave() === false` does not change `dimensionSave`.
- Import of a synthetic export into the current slot checkpoints the root; a truncated import rolls back.
- No NG+3 key is read or written.
- Qualification except fold.

**Release boundary:** own ship. Do not register NG+3 in the same release.

## Milestone 6 — NG+3

**Depends on:** B2, Milestone 1, and a prompt-counter result. **Complexity:** L.

Launch `https://aarextiaokhiao.github.io/NG-plus-3/`. Separate id `antimatter-dimensions-ng3`. Profile `hub-game-antimatter-dimensions-ng3`.

Definition of done:

- Slot ids other than 1 round-trip through `btoa("dsAM_" + id)`.
- Two-slot envelope: changing which slot is current is `INCONCLUSIVE`, not `LIVE_AHEAD`.
- Import into an empty current slot does not call `prompt`. Any unexpected `prompt` rolls back.
- Import into a progressed profile uses the game's new-slot branch and leaves the previous current slot's markers in place.
- A vanilla `AntimatterDimensionsSavefileFormat` string is rejected before `onLoad`.
- The matrix still says post-2023 community exports are unverified. The milestone does not claim them.

**Release boundary:** own ship, after vanilla AD or before it, never in a build that shares a profile or a game id with vanilla AD.

## Milestone 7 — Fold layout

**Depends on:** B1 and whichever games have already shipped or are ready to ship. **Complexity:** M per game that fails the viewport check.

No save-format work. Stylesheets or zoom, on the pattern of `SharkLayout` and the FE000000 readable path: presentation only, no `save()` call, no reload. Each game gets a layout change only after its folded screenshot shows a real overflow. Do not preload five stylesheets.

Definition of done:

- Hinge fold does not recreate the WebView and does not change the progress key.
- The control named in the qualification plan is inside the visual viewport in both postures.
- Shark and FE000000 layout tests still pass.

**Release boundary:** can ship with a game or as a follow-up. It is not a reason to delay Milestone 1.

## Explicitly out of scope

- Synergism mods and the old `pseudonian.github.io` build.
- The AD source-repo dev site and `dimensionTestSave`.
- `post-NG-plus-3` and any Aarex tree other than the live `master` hash.
- Cloud saves (PlayFab, Firebase, Steam, Synergism accounts).
- Merging two progressed saves.
- A second Hub profile per game. Import confirmation plus in-game slots cover this roadmap.
- Rewriting FE000000's `compare`. It is a known sharp edge and a separate decision.

## Suggested first implementation milestone

Milestone 1, after B2 is recorded. The first game to register is Trimps (Milestone 2). It is the only target whose live `main.js` is byte-identical to the audited commit, and its storage is one key the current bootstrap can already express once the session is allowed to call `load(string)` for import.
