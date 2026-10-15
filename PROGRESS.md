# Progress

## Dark-first chrome

The hub theme lives in `app/src/main/java/com/example/hub/ui/theme/HubTheme.kt`.

- `HubTheme(darkTheme = true)` is the default. Callers do not follow the system setting. Pass `darkTheme = false` to use the light scheme already defined in `hubColorScheme`.
- Palette: background `#0E1116`, surface `#171B22`, hub accent `#E6B450`. Per-game accents still come from `DisplayDefaults.colorPrimary` through `hubAccentColor` in `ui/chrome/AccentColor.kt`.
- The window theme in `app/src/main/res/values/themes.xml` is a dark no-action-bar theme so the first frame matches. `enableHubEdgeToEdge()` on each activity keeps system-bar icons light.
- Shared chrome is in `app/src/main/java/com/example/hub/ui/chrome/`: `HubScaffold`, `HubTopBar`, `StatusChip`, `AccentCard`, `OverlaySheet`, and `PlayChrome`. New screens should use these instead of local colors and sheets.
- `OverlaySheet` is a bottom sheet below `HubWideWidth` (600dp) and a side sheet at that width or above. The in-game menu and the recovery overlay both use it. Dismissing the menu only hides the sheet. "Return to hub" is still the action that leaves the game.
- The WebView in `FoldAwareLayout` is full-bleed. Status and navigation insets belong to `PlayChrome` and `OverlaySheet`. Hinge padding and IME padding are unchanged.

## Not in this pass

Session opening, capture, adapters, and navigation routes were left as they were.
