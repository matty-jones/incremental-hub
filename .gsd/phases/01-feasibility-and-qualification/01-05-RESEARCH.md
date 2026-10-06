---
sliceId: S05
milestone: M001
researched_at: 2026-10-05T16:30:00.000Z
---

# S05: Remediation — MockContext Syntax Fix and S03 Unit Test Verification — Research

## Executive Summary

**MockContext.kt syntax error is already fixed.** The file was deleted in commit 0af980f ("fix: repair S03 adapter unit tests") which removed the 3480-line MockContext.kt that had conflicting overloads causing a syntax error at line 90 (`moveTaskToFront taskId: Int, flags: Int` missing `(`). S03 unit tests now compile and pass.

**Remaining work:** Build the game definition JSON validation harness required by R003/R011, and verify it end-to-end against both existing game definition JSONs.

## Current State

| Item | Status | Evidence |
|------|--------|----------|
| MockContext.kt syntax fix | DONE | File deleted in commit 0af980f; grep finds no references |
| S03 unit tests compile | PASS | `./gradlew :app:testDebugUnitTest` exits 0 |
| S03 unit tests pass | 9/9 PASS | Fe000000AdapterTest: 5/5, SharkIncrementalAdapterTest: 4/4 |
| Build green | PASS | `./gradlew :app:assembleDebug` exits 0 |
| JSON validation harness | MISSING | No GameDefinitionValidator.kt exists; no validation logic for R003/R011 |
| S05 tasks planned | NONE | `.gsd/phases/.../slices/S05/tasks/` is empty |

## Required Fields (from R003/R011)

**R011 (14 fields):** schemaVersion, definitionVersion, gameId, displayName, sourceUrl, launchUrl, navigationRules, storageContext, adapterId, adapterVersion, saveCoverage, displayDefaults, versionProbe, compatibility, resourceObservations

**R003 (13 fields):** schemaVersion, gameId, displayName, sourceUrl, launchUrl, navigationRules, storageContext, adapterId, adapterVersion, saveCoverage, displayDefaults, versionProbe, compatibility

Both existing JSONs (shark-incremental.json, fe000000.json) contain all 14 fields.

## Existing Codebase

### Test dependencies (already in build.gradle.kts)
```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.json:json:20260814")
```

### Existing test pattern (from adapter tests)
- Pure JUnit 4 tests in `app/src/test/java/com/example/hub/games/`
- Use `org.json.JSONObject` for JSON parsing
- Use `kotlinx.coroutines.runBlocking` for suspend function tests
- No Android dependency needed for pure logic tests

### Existing validator patterns
- `SharkIncrementalAdapter.validate()` — validates base64-decoded VERSION ≥ 3
- `Fe000000Adapter.validate()` — validates marker stripping + gzip decompress + JSON parse
- No cross-cutting JSON schema validator exists

## Design: GameDefinitionValidator

### Class contract
```kotlin
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

class GameDefinitionValidator {
    fun validate(json: String): ValidationResult
    fun validate(inputStream: InputStream): ValidationResult
}
```

### Validation rules
1. **Top-level required fields (R011):** schemaVersion, definitionVersion, gameId, displayName, sourceUrl, launchUrl, navigationRules, storageContext, adapterId, adapterVersion, saveCoverage, displayDefaults, versionProbe, compatibility, resourceObservations
2. **Type checks:** schemaVersion=int, definitionVersion=int, gameId/displayName/adapterId/adapterVersion=non-empty string, sourceUrl/launchUrl=valid URL string, navigationRules=object, storageContext=object, saveCoverage=object, displayDefaults=object, versionProbe=object, compatibility=object, resourceObservations=array
3. **nested checks:** storageContext.keys must be non-empty array of objects with "name" and "format"; saveCoverage.keys must be non-empty array; navigationRules.maxDepth must be positive int
4. **R003 subset:** same as R011 minus definitionVersion

### Test strategy
1. Valid JSON (both existing files) → pass all checks
2. Missing required field → fail with specific error
3. Wrong type for schemaVersion → fail
4. Empty gameId → fail
5. Missing storageContext.keys → fail
6. Empty resourceObservations array → warning (not error)
7. Extra unknown fields → pass (additive compatibility)

## Files to Create

1. `app/src/main/java/com/example/hub/games/GameDefinitionValidator.kt` — pure Kotlin validator
2. `app/src/test/java/com/example/hub/games/GameDefinitionValidatorTest.kt` — 6-8 unit tests

## Natural Seams

- Validator is platform-agnostic (no Android imports) → JVM unit tests
- Tests read JSON from classpath/assets → `javaClass.classLoader.getResourceAsStream("games/shark-incremental.json")`
- No shared state between tests → each test is independent

## First Proof

Create the validator with R011 field checks and run it against both existing JSON files. If both pass, the harness is end-to-end verified. This is the highest-confidence check because it exercises real data.

## Verification

```bash
# Compile and run tests
./gradlew :app:testDebugUnitTest --tests "com.example.hub.games.GameDefinitionValidatorTest"

# Full build
./gradlew :app:assembleDebug

# Verify both JSONs validate
# (captured in test assertions)
```

## Risk Assessment

- **Low risk.** Pure Kotlin logic, no Android dependency, no network, no WebView.
- **Known constraint:** `org.json:json:20260814` is the JSON library available in tests. It lacks schema validation features, so all checks are manual.
- **No blockers.** The MockContext issue is resolved; the only remaining work is new code.

## S05 Slice Plan (proposed)

### Task 1: Create GameDefinitionValidator
- File: `app/src/main/java/com/example/hub/games/GameDefinitionValidator.kt`
- Validate all R011 fields with type and constraint checks
- Return ValidationResult with errors/warnings

### Task 2: Create GameDefinitionValidatorTest
- File: `app/src/test/java/com/example/hub/games/GameDefinitionValidatorTest.kt`
- 6-8 tests covering valid JSON, missing fields, wrong types, real JSON files

### Task 3: End-to-end verification
- Run tests, confirm both real JSONs pass
- Run full build, confirm green
- Save UAT result

## Requirements Advanced

None.

## Requirements Validated

None.

## Requirements Invalidated or Re-scoped

None.
