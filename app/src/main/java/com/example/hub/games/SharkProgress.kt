package com.example.hub.games

import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

/**
 * Sign and scientific exponent of a break_eternity Decimal.
 * Coefficients are not compared. Zero is sign 0, whatever the exponent is.
 */
data class DecimalRank(val sign: Int, val exponent: BigInteger) : Comparable<DecimalRank> {
    override fun compareTo(other: DecimalRank): Int {
        if (sign != other.sign) return sign.compareTo(other.sign)
        if (sign == 0) return 0
        return if (sign < 0) other.exponent.compareTo(exponent) else exponent.compareTo(other.exponent)
    }
}

/**
 * Stable Shark progress markers taken from [getPlayerData] defaults.
 * Currency magnitudes stay as [DecimalRank] and are never converted to Double.
 */
data class SharkProgress(
    val version: Int,
    val feature: Int,
    val totalTimePlayed: BigDecimal,
    val sharkLevel: DecimalRank,
    val totalFish: DecimalRank,
    val prestigeTimes: Long,
    val coreTimes: Long,
    val humanoidTimes: Long,
    val singularityFirst: Boolean,
) : Comparable<SharkProgress> {

    val fresh: Boolean
        get() = feature == 0 &&
            totalTimePlayed.signum() == 0 &&
            sharkLevel.sign == 0 &&
            totalFish.sign == 0 &&
            prestigeTimes == 0L &&
            coreTimes == 0L &&
            humanoidTimes == 0L &&
            !singularityFirst

    fun detail(): String {
        val shark = if (sharkLevel.sign == 0) "0" else "e${sharkLevel.exponent}"
        val fish = if (totalFish.sign == 0) "0" else "e${totalFish.exponent}"
        return "feature $feature, played ${totalTimePlayed.toPlainString()}, shark $shark, fish $fish, " +
            "prestige $prestigeTimes, core $coreTimes, humanoid $humanoidTimes, singularity $singularityFirst"
    }

    override fun compareTo(other: SharkProgress): Int {
        feature.compareTo(other.feature).takeIf { it != 0 }?.let { return it }
        totalTimePlayed.compareTo(other.totalTimePlayed).takeIf { it != 0 }?.let { return it }
        sharkLevel.compareTo(other.sharkLevel).takeIf { it != 0 }?.let { return it }
        totalFish.compareTo(other.totalFish).takeIf { it != 0 }?.let { return it }
        prestigeTimes.compareTo(other.prestigeTimes).takeIf { it != 0 }?.let { return it }
        coreTimes.compareTo(other.coreTimes).takeIf { it != 0 }?.let { return it }
        humanoidTimes.compareTo(other.humanoidTimes).takeIf { it != 0 }?.let { return it }
        return singularityFirst.compareTo(other.singularityFirst)
    }

    companion object {
        fun parsePayload(payload: String?): SharkProgress? {
            if (payload.isNullOrBlank()) return null
            val decoded = try {
                String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
            } catch (e: Exception) {
                return null
            }
            val json = try {
                JSONObject(decoded)
            } catch (e: Exception) {
                return null
            }
            return parsePlayer(json)
        }

        fun parseRuntime(jsonText: String?): SharkProgress? {
            if (jsonText.isNullOrBlank()) return null
            val json = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                return null
            }
            return parsePlayer(json)
        }

        /**
         * The running game may have applied offline gains. Counters that Shark
         * does not rewrite on load must match. Currencies and play time may grow.
         */
        fun acceptsRuntime(snapshot: SharkProgress, runtime: SharkProgress): Boolean {
            if (runtime.feature != snapshot.feature) return false
            if (runtime.prestigeTimes != snapshot.prestigeTimes) return false
            if (runtime.coreTimes != snapshot.coreTimes) return false
            if (runtime.humanoidTimes != snapshot.humanoidTimes) return false
            if (runtime.singularityFirst != snapshot.singularityFirst) return false
            if (runtime.totalTimePlayed < snapshot.totalTimePlayed) return false
            if (runtime.sharkLevel < snapshot.sharkLevel) return false
            if (runtime.totalFish < snapshot.totalFish) return false
            return true
        }

        fun summarize(payload: String?): SaveSummary {
            if (payload.isNullOrBlank()) {
                return SaveSummary(SaveStanding.ABSENT, null, "No save key", 0)
            }
            val progress = parsePayload(payload)
            if (progress == null || progress.version < SharkIncrementalAdapter.MIN_VERSION) {
                return SaveSummary(
                    SaveStanding.INVALID,
                    progress?.version,
                    "Save did not parse as Shark progress",
                    payload.length,
                )
            }
            val standing = if (progress.fresh) SaveStanding.FRESH else SaveStanding.PROGRESSED
            return SaveSummary(standing, progress.version, progress.detail(), payload.length)
        }

        /**
         * Milestone order for a capture commit. Feature, prestige, core, humanoid,
         * and singularity are monotonic. A later milestone may spend currencies.
         * Play time and currency rank never prove an advance or excuse a lost milestone.
         */
        fun classifyCapture(candidate: String?, reference: String?): ProgressionClass {
            val current = parsePayload(candidate)
            if (current == null || current.version < SharkIncrementalAdapter.MIN_VERSION) {
                return ProgressionClass.INVALID
            }
            if (reference.isNullOrBlank()) return ProgressionClass.ADVANCED
            val stored = parsePayload(reference) ?: return ProgressionClass.INCOMPARABLE
            if (stored.version < SharkIncrementalAdapter.MIN_VERSION) return ProgressionClass.INCOMPARABLE
            if (candidate == reference) return ProgressionClass.EQUAL
            val milestones = listOf(
                current.feature.compareTo(stored.feature),
                current.prestigeTimes.compareTo(stored.prestigeTimes),
                current.coreTimes.compareTo(stored.coreTimes),
                current.humanoidTimes.compareTo(stored.humanoidTimes),
                current.singularityFirst.compareTo(stored.singularityFirst),
            )
            if (milestones.any { it < 0 }) return ProgressionClass.REGRESSED
            if (milestones.any { it > 0 }) return ProgressionClass.ADVANCED
            return ProgressionClass.EQUAL
        }

        fun compare(livePayload: String?, storedPayload: String?): SaveComparison {
            val storedSummary = summarize(storedPayload)
            if (storedSummary.standing == SaveStanding.ABSENT) return SaveComparison.NO_SNAPSHOT
            val liveSummary = summarize(livePayload)
            if (liveSummary.standing == SaveStanding.ABSENT) return SaveComparison.LIVE_MISSING
            if (liveSummary.standing == SaveStanding.INVALID || storedSummary.standing == SaveStanding.INVALID) {
                return SaveComparison.INCONCLUSIVE
            }
            val live = parsePayload(livePayload) ?: return SaveComparison.INCONCLUSIVE
            val stored = parsePayload(storedPayload) ?: return SaveComparison.INCONCLUSIVE
            val cmp = live.compareTo(stored)
            return when {
                cmp == 0 -> SaveComparison.MATCH
                cmp > 0 -> SaveComparison.LIVE_AHEAD
                else -> SaveComparison.LIVE_BEHIND
            }
        }

        fun chooseStored(candidates: List<String>): String? {
            val parsed = candidates.mapNotNull { payload ->
                parsePayload(payload)?.takeIf { it.version >= SharkIncrementalAdapter.MIN_VERSION }?.let { payload to it }
            }
            val progressed = parsed.filter { !it.second.fresh }
            if (progressed.isNotEmpty()) {
                return progressed.maxBy { it.second }.first
            }
            return parsed.firstOrNull()?.first
        }

        private fun parsePlayer(json: JSONObject): SharkProgress? {
            if (!json.has("VERSION") || json.isNull("VERSION")) return null
            val version = json.optString("VERSION").toIntOrNull() ?: json.optInt("VERSION", -1)
            if (version < 0) return null
            val feature = json.requiredInt("feature") ?: return null
            val played = json.requiredDecimal("totalTimePlayed") ?: return null
            val shark = parseDecimal(json.opt("shark_level")) ?: return null
            if (!json.has("shark_level") || json.isNull("shark_level")) return null
            val fish = parseDecimal(json.opt("total_fish")) ?: return null
            if (!json.has("total_fish") || json.isNull("total_fish")) return null
            val prestige = json.optJSONObject("prestige")?.requiredLong("times") ?: return null
            val core = json.optJSONObject("core")?.requiredLong("times") ?: return null
            val humanoid = json.optJSONObject("humanoid")?.requiredLong("times") ?: return null
            val singularity = json.optJSONObject("singularity") ?: return null
            if (!singularity.has("first") || singularity.isNull("first")) return null
            val playedRank = decimalToBig(played) ?: return null
            return SharkProgress(
                version = version,
                feature = feature,
                totalTimePlayed = playedRank,
                sharkLevel = shark,
                totalFish = fish,
                prestigeTimes = prestige,
                coreTimes = core,
                humanoidTimes = humanoid,
                singularityFirst = singularity.getBoolean("first"),
            )
        }

        private fun JSONObject.requiredInt(key: String): Int? {
            if (!has(key) || isNull(key)) return null
            return when (val value = opt(key)) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }

        private fun JSONObject.requiredLong(key: String): Long? {
            if (!has(key) || isNull(key)) return null
            return when (val value = opt(key)) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
        }

        private fun JSONObject.requiredDecimal(key: String): Any? {
            if (!has(key) || isNull(key)) return null
            return opt(key)
        }

        private fun decimalToBig(value: Any): BigDecimal? = when (value) {
            is BigDecimal -> value
            is Number -> runCatching { BigDecimal(value.toString()) }.getOrNull()
            is String -> runCatching { BigDecimal(value) }.getOrNull()
            else -> null
        }

        fun parseDecimal(value: Any?): DecimalRank? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                if (!value.has("s") || !value.has("e") || value.isNull("s") || value.isNull("e")) null
                else {
                    val sign = value.optInt("s")
                    val exponent = when (val raw = value.opt("e")) {
                        is Number -> runCatching { BigDecimal(raw.toString()).toBigInteger() }.getOrNull()
                        is String -> raw.toBigIntegerOrNull()
                        else -> null
                    }
                    if (sign == 0) DecimalRank(0, BigInteger.ZERO)
                    else exponent?.let { DecimalRank(sign.coerceIn(-1, 1), it) }
                }
            }
            is Number -> runCatching { rankOf(BigDecimal(value.toString())) }.getOrNull()
            is String -> rankString(value)
            else -> null
        }

        private fun rankString(value: String): DecimalRank? {
            // BigDecimal rejects exponents outside Int range. Shark legitimately
            // serialises fish as strings such as "1e3689787472063838".
            runCatching { BigDecimal(value) }.getOrNull()?.let { return rankOf(it) }
            val match = Regex("""^([+-]?)(\d+)(?:\.(\d*))?[eE]([+-]?\d+)$""")
                .matchEntire(value) ?: return null
            val integerPart = match.groupValues[2]
            val digits = integerPart + match.groupValues[3]
            val firstNonzero = digits.indexOfFirst { it != '0' }
            if (firstNonzero < 0) return DecimalRank(0, BigInteger.ZERO)
            val exponent = match.groupValues[4].toBigIntegerOrNull() ?: return null
            val magnitude = exponent + BigInteger.valueOf(
                integerPart.length.toLong() - firstNonzero.toLong() - 1L,
            )
            val sign = if (match.groupValues[1] == "-") -1 else 1
            return DecimalRank(sign, magnitude)
        }

        private fun rankOf(value: BigDecimal): DecimalRank {
            val stripped = value.stripTrailingZeros()
            if (stripped.signum() == 0) return DecimalRank(0, BigInteger.ZERO)
            val exponent = BigInteger.valueOf(
                stripped.precision().toLong() - stripped.scale().toLong() - 1L,
            )
            return DecimalRank(stripped.signum(), exponent)
        }
    }
}
