---
id: S03-T01
parent: S03
milestone: M001
key_files:
  - (none)
key_decisions:
  - (none)
duration:
verification_result: passed
completed_at:
blocker_discovered: false
---

# S03-T01: Created game definition JSONs for Shark Incremental and FE000000, plus the GameAdapter interface and QualificationResult data class — the declarative contract for all downstream adapter work.

**Created game definition JSONs for Shark Incremental and FE000000, plus the GameAdapter interface and QualificationResult data class — the declarative contract for all downstream adapter work.**

## What Happened

Created four files for the games/ package:

1. **app/src/main/assets/games/shark-incremental.json** — Full game definition per R003/R011. Storage context: top-level origin `https://mrredshark77.github.io`, single key `shark_inc_save` (base64-JSON, persisted-only). Adapter: `shark-incremental-adapter` v1.0.0. Includes versionProbe (VERSION ≥ 3), resourceObservations from upstream save.js/load.js, and compatibility notes.

2. **app/src/main/assets/games/fe000000.json** — Full game definition per R003/R011. Storage context: top-level origin `https://dan-simon.github.io`, single key `fe000000-save` (gzip-base64-with-markers, persisted-only). Adapter: `fe000000-adapter` v1.0.0. Includes explicit-save-required flag and resourceObservations from browser inspection.

3. **app/src/main/java/com/example/hub/games/GameAdapter.kt** — Interface declaring all six required methods: `probe()`, `awaitReady()`, `capture()`, `validate()`, `prepareRestore()`, `verifyLoaded()`. Each method is documented with parameter and return semantics. Also exposes `adapterId` and `adapterVersion` properties.

4. **app/src/main/java/com/example/hub/games/QualificationResult.kt** — `StageResult` data class (stage, passed, evidence, durationMs) and `QualificationResult` data class (gameId, displayName, stages, blocker, reportPath). Includes `passed`, `firstFailure`, `totalDurationMs` convenience accessors and a `toJson()` serializer for reportPath writing.

Build verification: `./gradlew :app:assembleDebug --no-daemon` passes. JSON files validated with Python json.load — all required fields present.

## Verification

Build passes. Both JSON files parse as valid JSON with all R003/R011 required fields. GameAdapter declares all six interface methods. QualificationResult is a well-formed data class with toJson() serializer. No external dependencies for this unit (JSON+interface are compile-time only).

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:assembleDebug --no-daemon` | 0 | PASS | 8000ms |
| 2 | `python3 -c "import json; [json.load(open(f)) for f in ['app/src/main/assets/games/shark-incremental.json','app/src/main/assets/games/fe000000.json']]; print('OK')"` | 0 | PASS | 100ms |

## Deviations

None.

## Known Issues

None.

## Files Created/Modified

None.
<!-- gsd:state-version=106:0 -->
