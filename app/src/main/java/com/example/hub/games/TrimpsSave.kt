package com.example.hub.games

import org.json.JSONObject
import java.math.BigDecimal

/**
 * Trimps save rules taken from `load` and `compareVersion` in the audited
 * 5.10.1 `main.js`. The numeric `global.version` floor is `killSavesBelow`.
 */
internal object TrimpsSave {
    const val REFERENCE = "5.10.1"
    const val PRIMARY = "trimpSave1"
    const val BACKUP = "trimpBackup1"
    private val MIN_VERSION = BigDecimal("0.13")

    data class Markers(
        val stringVersion: String,
        val universe: Int,
        val totalPortals: Long,
        val zone: Long,
        val resource: BigDecimal,
        val challenge: String,
        val squared: Boolean,
        val fresh: Boolean,
    )

    fun body(compressed: String): JSONObject? {
        val text = try {
            LzString.decompressFromBase64(compressed)
        } catch (_: Exception) {
            null
        } ?: return null
        if (text.isEmpty()) return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    fun acceptable(compressed: String): Boolean = fromSave(compressed) != null

    fun markers(compressed: String): Markers? = fromSave(compressed)

    fun fromGlobal(global: JSONObject): Markers? {
        val universe = global.optInt("universe", 1)
        val zone = if (universe == 2) {
            longValue(global, "highestRadonLevelCleared")
        } else {
            longValue(global, "highestLevelCleared")
        } ?: return null
        val portals = longValue(global, "totalPortals") ?: return null
        val resourceName = if (universe == 2) "totalRadonEarned" else "totalHeliumEarned"
        val resource = decimal(global, resourceName) ?: return null
        val challenge = global.optString("challengeActive", "")
        val squared = global.optBoolean("runningChallengeSquared", false)
        val helium = decimal(global, "totalHeliumEarned") ?: BigDecimal.ZERO
        val radon = decimal(global, "totalRadonEarned") ?: BigDecimal.ZERO
        val radonZone = longValue(global, "highestRadonLevelCleared") ?: 0L
        val cleared = longValue(global, "highestLevelCleared") ?: 0L
        val stringVersion = global.optString("stringVersion", "")
        if (stringVersion.isEmpty()) return null
        val fresh = universe == 1 &&
            portals == 0L &&
            cleared == 0L &&
            radonZone == 0L &&
            helium.compareTo(BigDecimal.ZERO) == 0 &&
            radon.compareTo(BigDecimal.ZERO) == 0 &&
            challenge.isEmpty() &&
            !squared
        return Markers(
            stringVersion = stringVersion,
            universe = universe,
            totalPortals = portals,
            zone = zone,
            resource = resource,
            challenge = challenge,
            squared = squared,
            fresh = fresh,
        )
    }

    private fun fromSave(compressed: String): Markers? {
        val global = body(compressed)?.optJSONObject("global") ?: return null
        if (global.optBoolean("isBeta", false)) return null
        val version = decimal(global, "version") ?: return null
        if (version < MIN_VERSION) return null
        val stringVersion = global.optString("stringVersion", "")
        if (stringVersion.isEmpty() || isNewerThan(stringVersion, REFERENCE)) return null
        return fromGlobal(global)
    }

    fun compare(live: Markers, stored: Markers): SaveComparison {
        if (live.universe != stored.universe) return SaveComparison.INCONCLUSIVE
        if (live.challenge != stored.challenge || live.squared != stored.squared) {
            return SaveComparison.INCONCLUSIVE
        }
        val portals = live.totalPortals.compareTo(stored.totalPortals)
        val zone = live.zone.compareTo(stored.zone)
        val resource = live.resource.compareTo(stored.resource)
        var direction = 0
        for (delta in listOf(portals, zone, resource)) {
            if (delta == 0) continue
            if (direction == 0) {
                direction = delta
            } else if (direction != delta) {
                return SaveComparison.INCONCLUSIVE
            }
        }
        return when {
            direction > 0 -> SaveComparison.LIVE_AHEAD
            direction < 0 -> SaveComparison.LIVE_BEHIND
            else -> SaveComparison.MATCH
        }
    }

    /**
     * Portal count is the reset. A higher portal count is progress even when
     * the zone falls. The same portal count does not allow a lost zone,
     * universe, or challenge.
     */
    fun classify(candidate: Markers, reference: Markers): ProgressionClass {
        val portals = candidate.totalPortals.compareTo(reference.totalPortals)
        if (portals < 0) return ProgressionClass.REGRESSED
        if (portals > 0) return ProgressionClass.ADVANCED
        if (candidate.universe != reference.universe ||
            candidate.challenge != reference.challenge ||
            candidate.squared != reference.squared
        ) {
            return ProgressionClass.INCOMPARABLE
        }
        val zone = candidate.zone.compareTo(reference.zone)
        if (zone < 0) return ProgressionClass.REGRESSED
        if (zone > 0) return ProgressionClass.ADVANCED
        val resource = candidate.resource.compareTo(reference.resource)
        if (resource < 0) return ProgressionClass.REGRESSED
        if (resource > 0) return ProgressionClass.ADVANCED
        return ProgressionClass.EQUAL
    }

    /** True when the running game has not lost the snapshot's progression. */
    fun accepts(snapshot: Markers, runtime: Markers): Boolean {
        if (runtime.stringVersion != REFERENCE) return false
        if (runtime.universe != snapshot.universe) return false
        if (runtime.totalPortals < snapshot.totalPortals) return false
        if (runtime.zone < snapshot.zone) return false
        if (runtime.resource < snapshot.resource) return false
        return true
    }

    fun isNewerThan(saveVersion: String, runningVersion: String): Boolean {
        val save = parts(saveVersion) ?: return true
        val running = parts(runningVersion) ?: return true
        if (running[0] < save[0]) return true
        if (running[0] > save[0]) return false
        if (running[1] < save[1]) return true
        if (running[1] > save[1]) return false
        if (running[2] < save[2]) return true
        return false
    }

    private fun parts(version: String): IntArray? {
        val pieces = version.split('.')
        if (pieces.isEmpty() || pieces.size > 3) return null
        val values = IntArray(3)
        for (index in pieces.indices) {
            values[index] = pieces[index].toIntOrNull() ?: return null
        }
        return values
    }

    private fun decimal(json: JSONObject, key: String): BigDecimal? {
        if (!json.has(key) || json.isNull(key)) return null
        return try {
            BigDecimal(json.get(key).toString())
        } catch (_: Exception) {
            null
        }
    }

    private fun longValue(json: JSONObject, key: String): Long? {
        val value = decimal(json, key) ?: return null
        return try {
            value.toLong()
        } catch (_: Exception) {
            null
        }
    }
}
