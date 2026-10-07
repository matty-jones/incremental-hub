package com.example.hub.games

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException

/**
 * Result of validating a game definition JSON against the R011 schema.
 *
 * @param valid    true when there are no errors (warnings are acceptable).
 * @param errors   structured error messages for missing or invalid required fields.
 * @param warnings non-fatal observations (e.g. empty optional arrays).
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val errorCount get() = errors.size
    val warningCount get() = warnings.size
}

/**
 * Declarative validator for game definition JSON documents.
 *
 * Enforces the R011 schema contract: all 15 required fields, nested structural
 * checks, and additive-compatibility semantics for unknown fields.
 *
 * The R003 subset is exposed via [validateR003]. It validates everything that
 * R011 does except [definitionVersion].
 *
 * Usage:
 * ```kotlin
 * val validator = GameDefinitionValidator()
 * val result = validator.validate(jsonString)
 * if (!result.valid) { /* reject or surface errors */ }
 * ```
 */
class GameDefinitionValidator {

    /** Validate a JSON string against the full R011 schema. */
    fun validate(json: String): ValidationResult =
        validateImpl(json, includeDefinitionVersion = true)

    /** Validate an [InputStream] as UTF-8 against the full R011 schema. */
    fun validate(inputStream: InputStream): ValidationResult {
        val bytes = inputStream.readBytes()
        return validateImpl(bytes.toString(Charsets.UTF_8), includeDefinitionVersion = true)
    }

    /**
     * Validate against the R003 subset — identical to R011 but without the
     * [definitionVersion] requirement.
     */
    fun validateR003(json: String): ValidationResult =
        validateImpl(json, includeDefinitionVersion = false)

    /** Validate an [InputStream] as UTF-8 against the R003 subset. */
    fun validateR003(inputStream: InputStream): ValidationResult {
        val bytes = inputStream.readBytes()
        return validateImpl(bytes.toString(Charsets.UTF_8), includeDefinitionVersion = false)
    }

    // ---------------------------------------------------------------------
    // Internal implementation
    // ---------------------------------------------------------------------

    private fun validateImpl(json: String, includeDefinitionVersion: Boolean): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val root = runCatching { JSONObject(json) }.getOrElse {
            return ValidationResult(
                valid = false,
                errors = listOf("Invalid JSON: ${it.message}"),
            )
        }

        // --- schemaVersion (always required, even in R003) ---
        if (!root.has("schemaVersion")) {
            errors.add("Missing required field: schemaVersion")
        } else if (root["schemaVersion"] !is Number) {
            errors.add("schemaVersion must be an integer")
        } else {
            val sv = root.getInt("schemaVersion")
            if (sv < 1) errors.add("schemaVersion must be >= 1, got $sv")
        }

        // --- definitionVersion (R011 only) ---
        if (includeDefinitionVersion) {
            if (!root.has("definitionVersion")) {
                errors.add("Missing required field: definitionVersion")
            } else if (root["definitionVersion"] !is Number) {
                errors.add("definitionVersion must be an integer")
            } else {
                val dv = root.getInt("definitionVersion")
                if (dv < 1) errors.add("definitionVersion must be >= 1, got $dv")
            }
        }

        // --- gameId (non-empty string) ---
        if (!root.has("gameId")) {
            errors.add("Missing required field: gameId")
        } else if (root["gameId"] !is String) {
            errors.add("gameId must be a non-empty string")
        } else {
            val gid = root.getString("gameId")
            if (gid.trim().isEmpty()) errors.add("gameId must be a non-empty string")
        }

        // --- displayName (non-empty string) ---
        if (!root.has("displayName")) {
            errors.add("Missing required field: displayName")
        } else if (root["displayName"] !is String) {
            errors.add("displayName must be a non-empty string")
        } else {
            val dn = root.getString("displayName")
            if (dn.trim().isEmpty()) errors.add("displayName must be a non-empty string")
        }

        // --- sourceUrl (valid URL string) ---
        if (!root.has("sourceUrl")) {
            errors.add("Missing required field: sourceUrl")
        } else if (root["sourceUrl"] !is String) {
            errors.add("sourceUrl must be a valid URL string")
        } else {
            val su = root.getString("sourceUrl")
            if (!isValidUrl(su)) errors.add("sourceUrl is not a valid URL: $su")
        }

        // --- launchUrl (valid URL string) ---
        if (!root.has("launchUrl")) {
            errors.add("Missing required field: launchUrl")
        } else if (root["launchUrl"] !is String) {
            errors.add("launchUrl must be a valid URL string")
        } else {
            val lu = root.getString("launchUrl")
            if (!isValidUrl(lu)) errors.add("launchUrl is not a valid URL: $lu")
        }

        // --- navigationRules (object, maxDepth positive int) ---
        validateNavigationRules(root, errors)

        // --- storageContext (object, keys non-empty array of objects with name+format) ---
        validateStorageContext(root, errors)

        // --- adapterId (non-empty string) ---
        if (!root.has("adapterId")) {
            errors.add("Missing required field: adapterId")
        } else if (root["adapterId"] !is String) {
            errors.add("adapterId must be a non-empty string")
        } else {
            val aid = root.getString("adapterId")
            if (aid.trim().isEmpty()) errors.add("adapterId must be a non-empty string")
        }

        // --- adapterVersion (non-empty string) ---
        if (!root.has("adapterVersion")) {
            errors.add("Missing required field: adapterVersion")
        } else if (root["adapterVersion"] !is String) {
            errors.add("adapterVersion must be a non-empty string")
        } else {
            val av = root.getString("adapterVersion")
            if (av.trim().isEmpty()) errors.add("adapterVersion must be a non-empty string")
        }

        // --- saveCoverage (object, keys non-empty array) ---
        validateSaveCoverage(root, errors)

        // --- displayDefaults (object) ---
        if (!root.has("displayDefaults")) {
            errors.add("Missing required field: displayDefaults")
        } else if (root["displayDefaults"] !is JSONObject) {
            errors.add("displayDefaults must be an object")
        }

        // --- versionProbe (object) ---
        if (!root.has("versionProbe")) {
            errors.add("Missing required field: versionProbe")
        } else if (root["versionProbe"] !is JSONObject) {
            errors.add("versionProbe must be an object")
        }

        // --- compatibility (object) ---
        if (!root.has("compatibility")) {
            errors.add("Missing required field: compatibility")
        } else if (root["compatibility"] !is JSONObject) {
            errors.add("compatibility must be an object")
        }

        // --- resourceObservations (array; empty = warning not error) ---
        if (!root.has("resourceObservations")) {
            errors.add("Missing required field: resourceObservations")
        } else if (root["resourceObservations"] !is JSONArray) {
            errors.add("resourceObservations must be an array")
        } else {
            val ro = root.getJSONArray("resourceObservations")
            if (ro.length() == 0) warnings.add("resourceObservations is empty — consider adding observations for traceability")
        }

        return ValidationResult(valid = errors.isEmpty(), errors = errors, warnings = warnings)
    }

    private fun validateNavigationRules(root: JSONObject, errors: MutableList<String>) {
        if (!root.has("navigationRules")) {
            errors.add("Missing required field: navigationRules")
            return
        }
        if (root["navigationRules"] !is JSONObject) {
            errors.add("navigationRules must be an object")
            return
        }
        val nav = root.getJSONObject("navigationRules")
        if (!nav.has("maxDepth")) {
            errors.add("navigationRules.maxDepth is required")
        } else if (nav["maxDepth"] !is Number) {
            errors.add("navigationRules.maxDepth must be an integer")
        } else {
            val depth = nav.getInt("maxDepth")
            if (depth < 1) errors.add("navigationRules.maxDepth must be >= 1, got $depth")
        }
    }

    private fun validateStorageContext(root: JSONObject, errors: MutableList<String>) {
        if (!root.has("storageContext")) {
            errors.add("Missing required field: storageContext")
            return
        }
        if (root["storageContext"] !is JSONObject) {
            errors.add("storageContext must be an object")
            return
        }
        val ctx = root.getJSONObject("storageContext")
        if (!ctx.has("keys")) {
            errors.add("storageContext.keys is required")
        } else if (ctx["keys"] !is JSONArray) {
            errors.add("storageContext.keys must be an array")
        } else {
            val keys = ctx.getJSONArray("keys")
            if (keys.length() == 0) {
                errors.add("storageContext.keys must be a non-empty array")
            } else {
                for (i in 0 until keys.length()) {
                    val key = keys.opt(i)
                    if (key !is JSONObject) {
                        errors.add("storageContext.keys[$i] must be an object")
                    } else {
                        if (!key.has("name")) errors.add("storageContext.keys[$i] missing 'name'")
                        else if (key["name"] !is String || key.getString("name").trim().isEmpty())
                            errors.add("storageContext.keys[$i].name must be a non-empty string")
                        if (!key.has("format")) errors.add("storageContext.keys[$i] missing 'format'")
                        else if (key["format"] !is String || key.getString("format").trim().isEmpty())
                            errors.add("storageContext.keys[$i].format must be a non-empty string")
                    }
                }
            }
        }
    }

    private fun validateSaveCoverage(root: JSONObject, errors: MutableList<String>) {
        if (!root.has("saveCoverage")) {
            errors.add("Missing required field: saveCoverage")
            return
        }
        if (root["saveCoverage"] !is JSONObject) {
            errors.add("saveCoverage must be an object")
            return
        }
        val sc = root.getJSONObject("saveCoverage")
        if (!sc.has("keys")) {
            errors.add("saveCoverage.keys is required")
        } else if (sc["keys"] !is JSONArray) {
            errors.add("saveCoverage.keys must be an array")
        } else {
            val keys = sc.getJSONArray("keys")
            if (keys.length() == 0) errors.add("saveCoverage.keys must be a non-empty array")
        }
    }

    /**
     * Lightweight URL validation. Accepts http, https, and scheme-less absolute
     * URIs. Rejects bare paths, empty strings, and clearly malformed URLs.
     */
    private fun isValidUrl(s: String): Boolean {
        if (s.trim().isEmpty()) return false
        return runCatching {
            val uri = URI(s)
            val scheme = uri.scheme?.lowercase()
            scheme == "http" || scheme == "https"
        }.getOrDefault(false)
    }
}
