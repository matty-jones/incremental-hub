# Manrope

Official source: Google Fonts `ofl/manrope`, file `Manrope[wght].ttf`.

- Repository: https://github.com/google/fonts
- Commit: `b31870aff700ab7a1d74fa0c6887d95beb9e0037`
- Commit date: 2026-09-14
- Path: `ofl/manrope/Manrope[wght].ttf`
- SHA-256: `3ae11c49db0455a3cc33e37d380f20fdb8c7f8b41dc07625c177e3d87a9d6ae6`

Licence: SIL Open Font License 1.1, in `OFL.txt` next to this file and the variable font.

The Android app does not load this variable font at runtime. `tools/fonts/instantiate_manrope.py` uses fontTools to write static weights 400, 500, 600, and 700 into `app/src/main/res/font/`. Those four files are the bundled resources. Rebuild them from this checkout rather than fetching static cuts from somewhere else.
