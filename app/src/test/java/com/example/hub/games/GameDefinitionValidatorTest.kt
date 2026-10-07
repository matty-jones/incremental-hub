package com.example.hub.games

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Unit tests for [GameDefinitionValidator].
 *
 * Exercises the validator against both real game-definition JSONs (loaded from
 * the Android asset pipeline) and against synthetically mutated JSONs that
 * target individual schema constraints.
 */
class GameDefinitionValidatorTest {

    private val validator = GameDefinitionValidator()

    // ---------------------------------------------------------------------
    // Positive cases — real game definitions
    // ---------------------------------------------------------------------

    @Test
    fun `validSharkDefinitionPasses`() {
        val json = loadGameDefinition("shark-incremental.json")
        val result = validator.validate(json)
        assertTrue("shark-incremental should be valid", result.valid)
        assertEquals(0, result.errorCount)
    }

    @Test
    fun `validFe000000DefinitionPasses`() {
        val json = loadGameDefinition("fe000000.json")
        val result = validator.validate(json)
        assertTrue("fe000000 should be valid", result.valid)
        assertEquals(0, result.errorCount)
    }

    // ---------------------------------------------------------------------
    // Negative cases — individual field violations
    // ---------------------------------------------------------------------

    @Test
    fun `missingSchemaVersionFails`() {
        val json = removeField(loadGameDefinition("shark-incremental.json"), "schemaVersion")
        val result = validator.validate(json)
        assertFalse("missing schemaVersion should fail", result.valid)
        assertTrue("should report missing schemaVersion", result.errors.any { it.contains("schemaVersion") })
    }

    @Test
    fun `wrongSchemaVersionTypeFails`() {
        val json = modifyField(loadGameDefinition("shark-incremental.json"), "schemaVersion") { "not-an-int" }
        val result = validator.validate(json)
        assertFalse("string schemaVersion should fail", result.valid)
        assertTrue("should report schemaVersion type error", result.errors.any { it.contains("schemaVersion") && it.contains("integer") })
    }

    @Test
    fun `emptyGameIdFails`() {
        val json = modifyField(loadGameDefinition("shark-incremental.json"), "gameId") { "" }
        val result = validator.validate(json)
        assertFalse("empty gameId should fail", result.valid)
        assertTrue("should report gameId error", result.errors.any { it.contains("gameId") })
    }

    @Test
    fun `missingStorageContextKeysFails`() {
        val json = removeNestedField(loadGameDefinition("shark-incremental.json"), "storageContext", "keys")
        val result = validator.validate(json)
        assertFalse("missing storageContext.keys should fail", result.valid)
        assertTrue("should report storageContext.keys error", result.errors.any { it.contains("storageContext.keys") })
    }

    // ---------------------------------------------------------------------
    // Warning cases
    // ---------------------------------------------------------------------

    @Test
    fun `emptyResourceObservationsWarns`() {
        val json = modifyField(loadGameDefinition("shark-incremental.json"), "resourceObservations") { emptyList<Any>() }
        val result = validator.validate(json)
        assertTrue("empty resourceObservations should still be valid", result.valid)
        assertEquals(0, result.errorCount)
        assertTrue("should emit a warning for empty resourceObservations", result.warnings.any { it.contains("resourceObservations") })
    }

    // ---------------------------------------------------------------------
    // Additive-compatibility case
    // ---------------------------------------------------------------------

    @Test
    fun `extraFieldsPass`() {
        val json = addField(loadGameDefinition("shark-incremental.json"), "unknownFutureField", "should-be-ignored")
        val result = validator.validate(json)
        assertTrue("unknown fields should not cause validation failure", result.valid)
        assertEquals(0, result.errorCount)
    }

    // ---------------------------------------------------------------------
    // Helper — load game definitions from the Android asset pipeline
    // ---------------------------------------------------------------------

    private fun loadGameDefinition(filename: String): String {
        return javaClass.classLoader
            .getResourceAsStream("games/$filename")
            ?.bufferedReader()
            .use { it?.readText() }
            ?: throw AssertionError("Test resource not found: games/$filename")
    }

    /** Remove a top-level field from a JSON string. */
    private fun removeField(json: String, fieldName: String): String {
        val obj = JSONObject(json)
        obj.remove(fieldName)
        return obj.toString()
    }

    /** Modify a top-level field via a transformer. */
    private fun modifyField(json: String, fieldName: String, transform: (Any) -> Any): String {
        val obj = JSONObject(json)
        val current = obj.opt(fieldName)
        obj.put(fieldName, transform(current ?: ""))
        return obj.toString()
    }

    /** Remove a nested field (one level deep). */
    private fun removeNestedField(json: String, parentField: String, childField: String): String {
        val obj = JSONObject(json)
        if (obj.has(parentField) && obj[parentField] is JSONObject) {
            val parent = obj.getJSONObject(parentField)
            parent.remove(childField)
        }
        return obj.toString()
    }

    /** Add a top-level field to a JSON string. */
    private fun addField(json: String, fieldName: String, value: Any): String {
        val obj = JSONObject(json)
        obj.put(fieldName, value)
        return obj.toString()
    }
}
