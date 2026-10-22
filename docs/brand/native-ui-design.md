# Native UI

The launcher icon and `drawable-nodpi/ihub_splash_art.png` are the visual source of truth. This note covers the Compose UI around them. Do not recolour or retime the splash. `ColdStartSplash` stays cold-start only, non-blocking, and tap-to-dismiss (`HOLD_MS` 600, `FADE_MS` 180). `@color/ihub_brand_black` stays `#01060B`.

## Palette

Gold and the cool game accents are fixed. Outline is the one surface shade that moved: the starting `#354653` is `OutlineSubtle` (dividers). Card edges use `#5A7384` so they clear 3:1 on Surface.

| Token | Hex | Use |
| --- | --- | --- |
| SpaceBlack | `#01060B` | Canvas, window background, splash field |
| Surface | `#111B24` | Cards and standard panels |
| SurfaceRaised | `#1A2833` | Sheets, dialogues, ordinary menu rows |
| Outline | `#5A7384` | Card and control edges |
| OutlineSubtle | `#354653` | Quiet separators |
| Gold | `#E6B450` | Primary filled actions, Resume row, checkpoint icons |
| GoldLight | `#F5D38A` | Launcher rule glint only |
| OnGold | `#2A1C00` | Text on gold |
| Cyan | `#45DCEB` | FE000000 identity |
| Teal | `#35C7B7` | Cool accent cycle |
| Azure | `#58B9F0` | Shark Incremental identity |
| Ice | `#81D9E8` | Cool accent cycle |
| TextPrimary | `#F0F3F5` | Headings and body |
| TextSecondary | `#AEC0C9` | Metadata, captions |
| Success | `#8FCB9B` | Ready / pass text |
| SuccessContainer | `#143528` | Ready chip and pass cards |
| Warning | `#FF8C42` | Debug chip and diagnostic warnings. Not a CTA |
| Error | `#FF8F80` | Failures and the recovery title |

`displayDefaults.colorPrimary` stays in game JSON and is not read by native chrome. A favicon may still contain its own colours.

Light scheme exists for `hubColorScheme(false)` and is not offered as a setting. Its primary is `#7A5608` with white text.

## Type

Manrope, SIL Open Font License 1.1. Source pin and checksum: `third_party/fonts/manrope/SOURCE.md`. Licence: `third_party/fonts/manrope/OFL.txt`.

`tools/fonts/instantiate_manrope.py` writes static instances into `app/src/main/res/font/`:

- `manrope_regular.ttf` weight 400
- `manrope_medium.ttf` weight 500
- `manrope_semibold.ttf` weight 600
- `manrope_bold.ttf` weight 700

`HubFontFamily` maps those files to Normal, Medium, SemiBold, and Bold. `HubTypography` applies that family to every Material 3 role and keeps the default sizes and line heights. Headlines and `titleLarge` / `titleMedium` are SemiBold. `titleSmall` and `labelLarge` are Medium. Body, labels, errors, game titles, and buttons do not use wide tracking. Font scale is the platform scale; nothing locks text to px.

`HubBrandTitle` is Manrope SemiBold, 22sp, `0.12.em`. `HubTopBar(branded = true)` uppercases the title and draws a full-width gold rule with a small diamond at the screen centre. The rule sits under the title row and runs edge to edge, outside the header's horizontal padding. Only the launcher sets `branded`. At large font scale that one line ellipsizes instead of growing the header.

## Chrome

- `HubCard` is a Surface fill with a 1dp outline. Game cards pass `identityAccent` for a 2dp leading edge. Settings cards pass none.
- Play / Resume, Check for update, and the forward recovery action are filled gold (`HubPrimaryButton`), including the disabled state from the colour scheme. Secondary actions are outlined or `HubTextButton` in on-surface, not gold.
- Chips: Resume/ready uses success, Play/no save uses a neutral surface, Debug uses warning. The workstation line stays sentence text in TextSecondary.
- Menu: Resume is the gold row. Snapshot now and Checkpoint keep a gold icon on a neutral row. Other rows are SurfaceRaised. Disabled rows stay faded and keep the "(disabled)" description. Labels are unchanged.
- Recovery title "Session Recovery Required" uses the error colour. Close is outlined, not an error button. One gold action per state: Retry, Restore this snapshot, or Recover from snapshot.
- Update dialogue: Update is primary, Later is muted, and a failed download sentence uses the error colour. A workstation commit message, when present, sits under the version sentence in TextSecondary and is cut at 280 characters.
- The in-game bar stays slim. Its Menu control is tonal and at least 48dp. No gold rule sits over the WebView.
- Game marks keep the site icon. The backing colour is the game accent. Letter fallbacks use that accent too.

## Game accents

`HubGameAccent.forGame` ignores source-site hex:

- `fe000000` -> Cyan
- `shark-incremental` -> Azure
- any other id -> `floorMod` of the Java string hash (`31 * hash + code`) across Cyan, Teal, Azure, Ice

The result does not depend on list order or the current session. Gold is not an identity colour.
