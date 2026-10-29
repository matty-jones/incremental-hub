package com.example.hub.games

import org.json.JSONObject
import java.math.BigDecimal
import java.util.Base64

/**
 * Synergism 4.4.0 save rules from the live `synergism.cc` bundle and
 * `Pseudo-Corp/SynergismOfficial` `01b246de`. The stored value is standard
 * Base64 of the JSON `playerJsonSchema` writes. `player.version` is not
 * rewritten on load, so an older version string can still be a current save.
 */
internal object SynergismSave {
    const val REFERENCE = "4.4.0"
    const val REFERENCE_LABEL = "4.4.0 October 26, 2026: The Corrupted Update"
    const val UPSTREAM = "01b246dead3a2c52e90ffccdb59e09efe4c6d88b"
    const val KEY = "Synergysave2"
    private const val MIN_FIELDS = 40
    private val REFERENCE_PARTS = intArrayOf(4, 4, 0)

    data class Markers(
        val version: String,
        val singularityCount: Long,
        val highestSingularityCount: Long,
        val worlds: BigDecimal,
        val goldenQuarks: BigDecimal,
        val totalQuarksEver: BigDecimal,
        val prestigeCount: Long,
        val transcendCount: Long,
        val reincarnationCount: Long,
        val ascensionCount: Long,
        val offlineTick: BigDecimal,
        val fresh: Boolean,
    )

    fun body(encoded: String): JSONObject? {
        if (encoded.isEmpty() || !BASE64.matches(encoded)) return null
        val text = try {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        if (text.isEmpty() || text.first() != '{') return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    fun acceptable(encoded: String): Boolean = rejection(encoded) == null

    fun rejection(encoded: String): String? {
        if (encoded.isEmpty()) return "empty"
        if (!BASE64.matches(encoded)) return "malformed-base64"
        val json = body(encoded) ?: return "invalid-json"
        if (json.length() < MIN_FIELDS) return "missing-fields"
        val version = json.opt("version")
        if (version !is String || semver(version) == null) return "missing-version"
        if (isNewerThanReference(version)) return "future-version"
        if (!exportAccepted(json.opt("exporttest"))) return "exporttest"
        if (json.optJSONObject("corruptions")?.opt("used") !is Number) return "corruptions"
        if (json.optJSONObject("talismans") == null) return "talismans"
        if (!json.has("coins") || json.isNull("coins")) return "coins"
        val coins = json.opt("coins")
        if (coins !is String && coins !is Number) return "coins"
        for (key in COUNT_FIELDS) {
            if (longNumber(json, key) == null) return key
        }
        for (key in QUARK_FIELDS) {
            val value = decimal(json, key) ?: return key
            if (value < BigDecimal.ZERO) return key
        }
        val offline = decimal(json, "offlinetick") ?: return "offlinetick"
        if (offline < BigDecimal.ZERO) return "offlinetick"
        return null
    }

    fun markers(encoded: String): Markers? {
        if (rejection(encoded) != null) return null
        val json = body(encoded) ?: return null
        val singularity = longNumber(json, "singularityCount") ?: return null
        val highest = longNumber(json, "highestSingularityCount") ?: return null
        val worlds = decimal(json, "worlds") ?: return null
        val golden = decimal(json, "goldenQuarks") ?: return null
        val ever = decimal(json, "totalQuarksEver") ?: return null
        val prestige = longNumber(json, "prestigeCount") ?: return null
        val transcend = longNumber(json, "transcendCount") ?: return null
        val reincarnation = longNumber(json, "reincarnationCount") ?: return null
        val ascension = longNumber(json, "ascensionCount") ?: return null
        val offline = decimal(json, "offlinetick") ?: return null
        val fresh = singularity == 0L &&
            highest == 0L &&
            worlds.compareTo(BigDecimal.ZERO) == 0 &&
            golden.compareTo(BigDecimal.ZERO) == 0 &&
            ever.compareTo(BigDecimal.ZERO) == 0 &&
            prestige == 0L &&
            transcend == 0L &&
            reincarnation == 0L &&
            ascension == 0L
        return Markers(
            version = json.optString("version"),
            singularityCount = singularity,
            highestSingularityCount = highest,
            worlds = worlds,
            goldenQuarks = golden,
            totalQuarksEver = ever,
            prestigeCount = prestige,
            transcendCount = transcend,
            reincarnationCount = reincarnation,
            ascensionCount = ascension,
            offlineTick = offline,
            fresh = fresh,
        )
    }

    /**
     * Singularity and quark totals order a pair only when they do not contradict.
     * A lower quark balance alone is not "behind": spending quarks reduces it.
     */
    fun compare(live: Markers, stored: Markers): SaveComparison {
        val singularity = direction(
            live.singularityCount.compareTo(stored.singularityCount),
            live.highestSingularityCount.compareTo(stored.highestSingularityCount),
        )
        val quarks = quarkDirection(
            live.worlds.compareTo(stored.worlds),
            live.goldenQuarks.compareTo(stored.goldenQuarks),
            live.totalQuarksEver.compareTo(stored.totalQuarksEver),
        )
        if (singularity == null || quarks == null) return SaveComparison.INCONCLUSIVE
        if (singularity == 0 && quarks == 0) return SaveComparison.INCONCLUSIVE
        if (singularity != 0 && quarks != 0 && singularity != quarks) return SaveComparison.INCONCLUSIVE
        val decided = if (singularity != 0) singularity else quarks
        return if (decided > 0) SaveComparison.LIVE_AHEAD else SaveComparison.LIVE_BEHIND
    }

    /**
     * Singularity, lifetime quarks, and the reset counters are monotonic.
     * Worlds and golden quarks may fall when they are spent.
     */
    fun classify(candidate: Markers, reference: Markers): ProgressionClass {
        val milestones = listOf(
            candidate.singularityCount.compareTo(reference.singularityCount),
            candidate.highestSingularityCount.compareTo(reference.highestSingularityCount),
            candidate.totalQuarksEver.compareTo(reference.totalQuarksEver),
            candidate.prestigeCount.compareTo(reference.prestigeCount),
            candidate.transcendCount.compareTo(reference.transcendCount),
            candidate.reincarnationCount.compareTo(reference.reincarnationCount),
            candidate.ascensionCount.compareTo(reference.ascensionCount),
        )
        if (milestones.any { it < 0 }) return ProgressionClass.REGRESSED
        if (milestones.any { it > 0 }) return ProgressionClass.ADVANCED
        return ProgressionClass.EQUAL
    }

    fun accepts(snapshot: Markers, runtime: Markers, pageVersion: String): Boolean {
        if (!pageVersion.contains(REFERENCE)) return false
        if (isNewerThanReference(runtime.version)) return false
        if (runtime.singularityCount < snapshot.singularityCount) return false
        if (runtime.highestSingularityCount < snapshot.highestSingularityCount) return false
        if (runtime.worlds < snapshot.worlds) return false
        if (runtime.goldenQuarks < snapshot.goldenQuarks) return false
        if (runtime.totalQuarksEver < snapshot.totalQuarksEver) return false
        return true
    }

    fun isNewerThanReference(version: String): Boolean {
        val parts = semver(version) ?: return true
        for (index in REFERENCE_PARTS.indices) {
            if (parts[index] > REFERENCE_PARTS[index]) return true
            if (parts[index] < REFERENCE_PARTS[index]) return false
        }
        return false
    }

    private fun quarkDirection(vararg deltas: Int): Int? {
        var up = false
        var down = false
        for (delta in deltas) {
            if (delta > 0) up = true
            if (delta < 0) down = true
        }
        if (up && down) return null
        if (down) return null
        return if (up) 1 else 0
    }

    private fun direction(vararg deltas: Int): Int? {
        var sign = 0
        for (delta in deltas) {
            if (delta == 0) continue
            if (sign == 0) {
                sign = delta
            } else if (sign != delta) {
                return null
            }
        }
        return sign
    }

    private fun exportAccepted(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is String -> value == "YES!"
        else -> false
    }

    private fun semver(version: String): IntArray? {
        val match = SEMVER.find(version) ?: return null
        return IntArray(3) { index -> match.groupValues[index + 1].toInt() }
    }

    private fun longNumber(json: JSONObject, key: String): Long? {
        val value = decimal(json, key) ?: return null
        if (value < BigDecimal.ZERO) return null
        return try {
            value.longValueExact()
        } catch (_: Exception) {
            null
        }
    }

    private fun decimal(json: JSONObject, key: String): BigDecimal? {
        if (!json.has(key) || json.isNull(key)) return null
        val raw = json.get(key)
        return when (raw) {
            is Number -> BigDecimal(raw.toString())
            is String -> raw.toBigDecimalOrNull()
            else -> null
        }
    }

    private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")
    private val SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)""")
    private val COUNT_FIELDS = listOf(
        "singularityCount",
        "highestSingularityCount",
        "prestigeCount",
        "transcendCount",
        "reincarnationCount",
        "ascensionCount",
    )
    private val QUARK_FIELDS = listOf("worlds", "goldenQuarks", "totalQuarksEver")
}
