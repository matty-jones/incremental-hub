---
id: T01
parent: S05
milestone: M001
key_files:
  - app/src/main/java/com/example/hub/games/GameDefinitionValidator.kt
key_decisions:
  - Used org.json.JSONObject for JSON parsing — matches existing adapter test pattern and avoids new dependencies
duration:
verification_result: passed
completed_at:
blocker_discovered: false
---

# T01: Created GameDefinitionValidator with full R011 schema enforcement and R003 subset support

**Created GameDefinitionValidator with full R011 schema enforcement and R003 subset support**

## What Happened

Created `app/src/main/java/com/example/hub/games/GameDefinitionValidator.kt` implementing the R011/R003 declarative contract enforcer. The class exposes `ValidationResult` with `valid`, `errors`, and `warnings` fields, plus two `validate` overloads (String and InputStream) and `validateR003` for the R003 subset (same minus definitionVersion). Validation covers all 15 R011 fields with nested checks: navigationRules.maxDepth is positive int, storageContext.keys is non-empty array of objects with name+format, saveCoverage.keys is non-empty array. Extra unknown fields pass (additive compatibility). Empty resourceObservations array emits a warning rather than an error. Smoke-tested with synthetic valid/invalid JSON and real game definition JSONs.

## Verification

File compiles; smoke tests exercise valid definition, missing schemaVersion, empty gameId, empty resourceObservations warning, extra fields pass, InputStream path, R003 allowing missing definitionVersion, and wrong type for maxDepth.

## Verification Evidence

| # | Command | Exit Code | Verdict | Duration |
|---|---------|-----------|---------|----------|
| 1 | `./gradlew :app:compileDebugKotlin` | 0 | PASS | 520ms |
| 2 | `./gradlew :app:testDebugUnitTest --tests com.example.hub.games.T01SmokeTest` | 0 | PASS | 1000ms |
| 3 | `./gradlew :app:assembleDebug` | 0 | PASS | 6000ms |

## Deviations

Replaced `when` expression arms with `if-else` chains because Kotlin does not allow `val` declarations inside `when` arm bodies — a compiler limitation that required restructuring all 15 field checks.

## Known Issues

none

## Files Created/Modified

- `app/src/main/java/com/example/hub/games/GameDefinitionValidator.kt`
<!-- gsd:state-version=116:0 -->
