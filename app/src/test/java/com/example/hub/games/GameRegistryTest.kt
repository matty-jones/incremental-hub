package com.example.hub.games

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * Unit tests for [GameRegistry].
 *
 * Uses Robolectric to obtain a real [android.content.Context] whose `assets/`
 * directory is populated from the test resource tree (`src/test/resources/games/`).
 * Robolectric mirrors Android's asset pipeline for files placed under
 * `src/test/resources/assets/`.
 *
 * Tests cover:
 * - Both known game definitions resolve to valid [RegisteredGame]s.
 * - Invalid JSON is rejected (validation fails, no [RegisteredGame] produced).
 * - Deterministic ordering: alphabetical when no DAO data; last-played DESC when present.
 * - [getGame] lookup by [gameId].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GameRegistryTest {

    private lateinit var registry: GameRegistry

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        registry = GameRegistry(ctx)
        // Load test definitions from classpath so tests are independent of asset merging.
        registry.injectDefinitions(
            listOf(
                loadTestClasspathJson("shark-incremental.json"),
                loadTestClasspathJson("fe000000.json")
            )
        )
    }

    // -----------------------------------------------------------------
    // Positive cases — both known definitions resolve
    // -----------------------------------------------------------------

    @Test
    fun `bothKnownDefinitionsResolve`() {
        val games = registry.getGames()
        val ids = games.map { it.gameId }.toSet()
        assertTrue("should contain shark-incremental", ids.contains("shark-incremental"))
        assertTrue("should contain fe000000", ids.contains("fe000000"))
        assertEquals(2, games.size)
    }

    @Test
    fun `eachResolvedGameHasCorrectFields`() {
        val shark = registry.getGame("shark-incremental")
        assertNotNull("shark-incremental should resolve", shark)
        assertEquals("Shark Incremental", shark!!.displayName)
        assertTrue("launchUrl should point to mrredshark77", shark.launchUrl.contains("mrredshark77"))
        assertEquals("shark-incremental-adapter", shark.adapterId)
        assertNotNull(shark.displayDefaults.colorPrimary)

        val fe = registry.getGame("fe000000")
        assertNotNull("fe000000 should resolve", fe)
        assertEquals("FE000000", fe!!.displayName)
        assertEquals("https://dan-simon.github.io/misc/fe000000/", fe.launchUrl)
        assertEquals("fe000000-adapter", fe.adapterId)
        assertNotNull(fe.displayDefaults.colorPrimary)
    }

    // -----------------------------------------------------------------
    // Negative case — invalid JSON rejected
    // -----------------------------------------------------------------

    @Test
    fun `invalidJsonRejected`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val registryWithInvalid = GameRegistry(ctx)
        registryWithInvalid.injectDefinitions(
            listOf(
                loadTestClasspathJson("shark-incremental.json"),
                "{\"schemaVersion\": \"not-a-number\"}"
            )
        )
        val games = registryWithInvalid.getGames()
        // The invalid definition should not appear in the resolved list.
        val invalidIds = games.map { it.gameId }
        assertTrue("invalid definition should not be resolved", !invalidIds.contains("invalid"))
        // allDefinitions should still include it with errors.
        val allDefs = registryWithInvalid.allDefinitions
        // Find the definition whose parsed gameId is "invalid" (our synthetic invalid entry).
        val invalidDef = allDefs.firstOrNull { it.resolved == null && it.definition.contains("not-a-number") }
        assertNotNull("allDefinitions should include the invalid entry", invalidDef)
        assertTrue("invalid definition should have errors", invalidDef!!.isError)
    }

    // -----------------------------------------------------------------
    // Ordering — deterministic alphabetical (no DAO data)
    // -----------------------------------------------------------------

    @Test
    fun `orderedAlphabeticallyWhenNoDaoData`() {
        val games = registry.getGames(lastPlayedByGameId = emptyMap())
        // Alphabetical by gameId: "fe000000" < "shark-incremental"
        assertEquals("fe000000", games[0].gameId)
        assertEquals("shark-incremental", games[1].gameId)
    }

    // -----------------------------------------------------------------
    // Ordering — last-played DESC when DAO data present
    // -----------------------------------------------------------------

    @Test
    fun `orderedByLastPlayedDescWhenDaoDataPresent`() {
        // Simulate DAO data: shark was played more recently.
        val lastPlayed = mapOf(
            "shark-incremental" to 9000L,
            "fe000000" to 5000L,
        )
        val games = registry.getGames(lastPlayedByGameId = lastPlayed)
        assertEquals("shark-incremental", games[0].gameId)
        assertEquals(9000L, games[0].lastPlayedAtMillis)
        assertEquals("fe000000", games[1].gameId)
        assertEquals(5000L, games[1].lastPlayedAtMillis)
    }

    @Test
    fun `gamesWithoutLastPlayedFollowAlphabetically`() {
        // Only shark has last-played data; fe000000 has none.
        val lastPlayed = mapOf(
            "shark-incremental" to 7000L,
        )
        val games = registry.getGames(lastPlayedByGameId = lastPlayed)
        // shark (has data) comes first, then fe000000 (no data, alphabetical).
        assertEquals("shark-incremental", games[0].gameId)
        assertEquals("fe000000", games[1].gameId)
    }

    // -----------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------

    @Test
    fun `getGame_missingGameIdReturnsNull`() {
        assertNull(registry.getGame("nonexistent-game"))
    }

    // -----------------------------------------------------------------
    // Validation surface
    // -----------------------------------------------------------------

    @Test
    fun `allDefinitionsIncludesBothValidAndInvalid`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val registryWithInvalid = GameRegistry(ctx)
        registryWithInvalid.injectDefinitions(
            listOf(
                loadTestClasspathJson("shark-incremental.json"),
                loadTestClasspathJson("fe000000.json"),
                "{\"schemaVersion\": null}"
            )
        )
        val allDefs = registryWithInvalid.allDefinitions
        // 2 valid + 1 invalid = 3 total
        assertEquals(3, allDefs.size)
        val validCount = allDefs.count { it.isValid }
        val invalidCount = allDefs.count { it.isError }
        assertEquals(2, validCount)
        assertEquals(1, invalidCount)
        // Confirm the schemaVersion-missing entry is flagged as invalid.
        val schemaMissing = allDefs.firstOrNull { it.definition.contains("\"schemaVersion\": null") }
        assertNotNull("should find the null-schemaVersion definition", schemaMissing)
        assertTrue("null schemaVersion should be invalid", schemaMissing!!.isError)
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun loadTestClasspathJson(filename: String): String {
        return javaClass.classLoader
            .getResourceAsStream("games/$filename")
            ?.bufferedReader()
            .use { it?.readText() }
            ?: throw AssertionError("Test resource not found: games/$filename")
    }
}
