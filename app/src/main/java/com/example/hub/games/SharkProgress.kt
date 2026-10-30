package com.example.hub.games

import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

/**
 * Sign, tower height, and top exponent of a break_eternity Decimal.
 * Coefficients are not compared. Zero is sign 0, whatever the exponent is.
 * Tower 1 is a normal scientific value. `ee10` is tower 2. Infinity uses
 * [Int.MAX_VALUE] and is not a milestone.
 */
data class DecimalRank(
    val sign: Int,
    val exponent: BigInteger,
    val tower: Int = 1,
) : Comparable<DecimalRank> {
    override fun compareTo(other: DecimalRank): Int {
        if (sign != other.sign) return sign.compareTo(other.sign)
        if (sign == 0) return 0
        val towerOrder = if (sign < 0) other.tower.compareTo(tower) else tower.compareTo(other.tower)
        if (towerOrder != 0) return towerOrder
        return if (sign < 0) other.exponent.compareTo(exponent) else exponent.compareTo(other.exponent)
    }
}

enum class SharkParseKind {
    OK,
    BASE64,
    JSON,
    MISSING_FIELD,
    DECIMAL,
    INVALID_GAME,
}

data class SharkParseReport(
    val kind: SharkParseKind,
    val field: String?,
    val detail: String,
    val progress: SharkProgress?,
)

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
        fun parsePayload(payload: String?): SharkProgress? = diagnosePayload(payload).progress

        fun diagnosePayload(payload: String?): SharkParseReport {
            if (payload.isNullOrBlank()) {
                return SharkParseReport(SharkParseKind.MISSING_FIELD, null, "No save key", null)
            }
            val decoded = try {
                String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
            } catch (e: Exception) {
                return SharkParseReport(SharkParseKind.BASE64, null, "Base64 decoding failed", null)
            }
            val json = try {
                JSONObject(decoded)
            } catch (e: Exception) {
                return SharkParseReport(SharkParseKind.JSON, null, "Save JSON is invalid", null)
            }
            return diagnosePlayer(json)
        }

        fun parseRuntime(jsonText: String?): SharkProgress? {
            if (jsonText.isNullOrBlank()) return null
            val json = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                return null
            }
            return diagnosePlayer(json).progress
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
            val report = diagnosePayload(payload)
            val progress = report.progress
            if (progress == null || progress.version < SharkIncrementalAdapter.MIN_VERSION) {
                return SaveSummary(
                    SaveStanding.INVALID,
                    progress?.version,
                    report.detail,
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
         *
         * Inside one milestone, totalTimePlayed, shark level, total fish, and every
         * other JSON field are unordered. Identical bytes and different bytes at
         * the same milestones are both [ProgressionClass.EQUAL]. Spending resources
         * or gaining an upgrade is continued play. The previous payload is retained
         * by the commit. A stale save at the same milestones can become the pointer,
         * and the earlier payload remains recoverable.
         */
        fun classifyCapture(candidate: String?, reference: String?): ProgressionClass {
            if (candidate.isNullOrBlank()) return ProgressionClass.INVALID
            val report = diagnosePayload(candidate)
            val current = report.progress
            if (current == null || current.version < SharkIncrementalAdapter.MIN_VERSION) {
                val preserve = report.kind == SharkParseKind.MISSING_FIELD ||
                    report.kind == SharkParseKind.DECIMAL ||
                    (report.kind == SharkParseKind.INVALID_GAME && report.field != "VERSION")
                return if (preserve) ProgressionClass.INCOMPARABLE else ProgressionClass.INVALID
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

        private fun diagnosePlayer(json: JSONObject): SharkParseReport {
            if (!json.has("VERSION") || json.isNull("VERSION")) {
                return failure(SharkParseKind.MISSING_FIELD, "VERSION", "Missing field VERSION")
            }
            val version = json.optString("VERSION").toIntOrNull() ?: json.optInt("VERSION", -1)
            if (version < 0) {
                return failure(SharkParseKind.INVALID_GAME, "VERSION", "Invalid game data in VERSION")
            }
            if (version < SharkIncrementalAdapter.MIN_VERSION) {
                return failure(SharkParseKind.INVALID_GAME, "VERSION", "Unsupported Shark version $version")
            }
            val feature = json.requiredInt("feature")
                ?: return failure(SharkParseKind.MISSING_FIELD, "feature", "Missing field feature")
            val played = readPlayTime(json)
                ?: return decimalFailure(json, "totalTimePlayed")
            val shark = readDecimal(json, "shark_level")
            if (shark.report != null) return shark.report
            val fish = readDecimal(json, "total_fish")
            if (fish.report != null) return fish.report
            val prestige = json.optJSONObject("prestige")?.requiredLong("times")
                ?: return failure(SharkParseKind.MISSING_FIELD, "prestige.times", "Missing field prestige.times")
            val core = json.optJSONObject("core")?.requiredLong("times")
                ?: return failure(SharkParseKind.MISSING_FIELD, "core.times", "Missing field core.times")
            val humanoid = json.optJSONObject("humanoid")?.requiredLong("times")
                ?: return failure(SharkParseKind.MISSING_FIELD, "humanoid.times", "Missing field humanoid.times")
            val singularity = json.optJSONObject("singularity")
                ?: return failure(SharkParseKind.MISSING_FIELD, "singularity.first", "Missing field singularity.first")
            if (!singularity.has("first") || singularity.isNull("first")) {
                return failure(SharkParseKind.MISSING_FIELD, "singularity.first", "Missing field singularity.first")
            }
            return SharkParseReport(
                SharkParseKind.OK,
                null,
                "Shark progress parsed",
                SharkProgress(
                    version = version,
                    feature = feature,
                    totalTimePlayed = played,
                    sharkLevel = shark.rank!!,
                    totalFish = fish.rank!!,
                    prestigeTimes = prestige,
                    coreTimes = core,
                    humanoidTimes = humanoid,
                    singularityFirst = singularity.getBoolean("first"),
                ),
            )
        }

        private fun failure(kind: SharkParseKind, field: String, detail: String): SharkParseReport {
            return SharkParseReport(kind, field, detail, null)
        }

        private data class DecimalRead(val rank: DecimalRank?, val report: SharkParseReport?)

        private fun readDecimal(json: JSONObject, key: String): DecimalRead {
            if (!json.has(key) || json.isNull(key)) {
                return DecimalRead(null, failure(SharkParseKind.MISSING_FIELD, key, "Missing field $key"))
            }
            val raw = json.opt(key)
            if (isNan(raw)) {
                return DecimalRead(null, failure(SharkParseKind.INVALID_GAME, key, "Invalid game data in $key"))
            }
            val rank = parseDecimal(raw)
                ?: return DecimalRead(
                    null,
                    failure(SharkParseKind.DECIMAL, key, "Unsupported Decimal representation in $key"),
                )
            return DecimalRead(rank, null)
        }

        private fun readPlayTime(json: JSONObject): BigDecimal? {
            if (!json.has("totalTimePlayed") || json.isNull("totalTimePlayed")) return null
            return decimalToBig(json.opt("totalTimePlayed"))
        }

        private fun decimalFailure(json: JSONObject, key: String): SharkParseReport {
            val raw = if (json.has(key)) json.opt(key) else null
            if (raw == null || raw == JSONObject.NULL) {
                return failure(SharkParseKind.MISSING_FIELD, key, "Missing field $key")
            }
            if (isNan(raw)) return failure(SharkParseKind.INVALID_GAME, key, "Invalid game data in $key")
            return failure(SharkParseKind.DECIMAL, key, "Unsupported Decimal representation in $key")
        }

        private fun isNan(value: Any?): Boolean = when (value) {
            is Double -> value.isNaN()
            is Float -> value.isNaN()
            is String -> value.equals("NaN", ignoreCase = true)
            else -> false
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

        private fun decimalToBig(value: Any?): BigDecimal? = when (value) {
            null, JSONObject.NULL -> null
            is BigDecimal -> value
            is Number -> if (isNan(value)) null else runCatching { BigDecimal(value.toString()) }.getOrNull()
            is String -> when {
                value.equals("NaN", ignoreCase = true) -> null
                else -> runCatching { BigDecimal(value) }.getOrNull()
                    ?: parseDecimal(value)?.let { rankToBig(it) }
            }
            is JSONObject -> parseDecimal(value)?.let { rankToBig(it) }
            else -> null
        }

        private fun rankToBig(rank: DecimalRank): BigDecimal? {
            if (rank.sign == 0) return BigDecimal.ZERO
            if (rank.tower != 1) return null
            val exp = rank.exponent
            if (exp.bitLength() > 20) return null
            val digits = exp.toInt()
            if (digits > 10_000 || digits < -10_000) return null
            return if (digits >= 0) {
                BigDecimal.TEN.pow(digits).multiply(BigDecimal(rank.sign))
            } else {
                BigDecimal.ONE.scaleByPowerOfTen(digits).multiply(BigDecimal(rank.sign))
            }
        }

        fun parseDecimal(value: Any?): DecimalRank? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                if (!value.has("s") || !value.has("e") || value.isNull("s") || value.isNull("e")) null
                else {
                    val sign = value.optInt("s")
                    if (sign == 0) DecimalRank(0, BigInteger.ZERO)
                    else {
                        val exponent = exponentRank(value.opt("e")) ?: return null
                        DecimalRank(sign.coerceIn(-1, 1), exponent.exponent, exponent.tower)
                    }
                }
            }
            is Number -> if (isNan(value)) null else runCatching { rankOf(BigDecimal(value.toString())) }.getOrNull()
            is String -> rankString(value)
            else -> null
        }

        private fun exponentRank(raw: Any?): DecimalRank? = when (raw) {
            is Number -> {
                if (isNan(raw) || (raw is Double && raw.isInfinite()) || (raw is Float && raw.isInfinite())) {
                    null
                } else {
                    runCatching { DecimalRank(1, BigDecimal(raw.toString()).toBigInteger()) }.getOrNull()
                }
            }
            is String -> parseExponent(raw)?.let { DecimalRank(1, it) } ?: rankString(raw)
            is JSONObject -> parseDecimal(raw)
            else -> null
        }

        private fun rankString(value: String): DecimalRank? {
            val trimmed = value.trim()
            if (trimmed.equals("NaN", ignoreCase = true)) return null
            if (trimmed == "Infinity") return DecimalRank(1, BigInteger.ZERO, Int.MAX_VALUE)
            if (trimmed == "-Infinity") return DecimalRank(-1, BigInteger.ZERO, Int.MAX_VALUE)
            // BigDecimal rejects exponents outside Int range. Shark serialises fish
            // as strings such as "1e3689787472063838" and, after reload, as the
            // break_eternity toString forms "ee10", "(e^3)10", and "1e3.68e+15".
            runCatching { BigDecimal(trimmed) }.getOrNull()?.let { return rankOf(it) }
            towerRank(trimmed)?.let { return it }
            val match = Regex("""^([+-]?)(\d+)(?:\.(\d*))?[eE](.+)$""").matchEntire(trimmed) ?: return null
            val exponent = parseExponent(match.groupValues[4])
            if (exponent != null) {
                return scientificRank(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3],
                    exponent,
                )
            }
            val nested = rankString(match.groupValues[4]) ?: return null
            val sign = if (match.groupValues[1] == "-") -1 else 1
            if (nested.sign == 0) return DecimalRank(0, BigInteger.ZERO)
            return DecimalRank(sign * nested.sign, nested.exponent, nested.tower + 1)
        }

        private fun towerRank(value: String): DecimalRank? {
            val paren = Regex("""^([+-]?)\(e\^(\d+)\)(.+)$""", RegexOption.IGNORE_CASE).matchEntire(value)
            if (paren != null) {
                val height = paren.groupValues[2].toIntOrNull() ?: return null
                if (height < 2) return null
                return combineTower(paren.groupValues[1], height, paren.groupValues[3])
            }
            val layered = Regex("""^([+-]?)(e{2,})(.+)$""", RegexOption.IGNORE_CASE).matchEntire(value) ?: return null
            return combineTower(layered.groupValues[1], layered.groupValues[2].length, layered.groupValues[3])
        }

        private fun combineTower(signRaw: String, height: Int, magnitude: String): DecimalRank? {
            val mag = rankString(magnitude) ?: return null
            if (mag.sign == 0) return DecimalRank(0, BigInteger.ZERO)
            val sign = if (signRaw == "-") -mag.sign else mag.sign
            return DecimalRank(sign.coerceIn(-1, 1), mag.exponent, height + mag.tower - 1)
        }

        private fun scientificRank(
            signRaw: String,
            integerPart: String,
            fraction: String,
            exponent: BigInteger,
        ): DecimalRank? {
            val digits = integerPart + fraction
            val firstNonzero = digits.indexOfFirst { it != '0' }
            if (firstNonzero < 0) return DecimalRank(0, BigInteger.ZERO)
            val magnitude = exponent + BigInteger.valueOf(
                integerPart.length.toLong() - firstNonzero.toLong() - 1L,
            )
            val sign = if (signRaw == "-") -1 else 1
            return DecimalRank(sign, magnitude)
        }

        private fun parseExponent(raw: String): BigInteger? {
            val trimmed = raw.trim()
            trimmed.toBigIntegerOrNull()?.let { return it }
            val match = Regex("""^([+-]?)(\d+)(?:\.(\d*))?[eE]([+-]?\d+)$""").matchEntire(trimmed) ?: return null
            val negative = match.groupValues[1] == "-"
            val fraction = match.groupValues[3]
            val exp = match.groupValues[4].toIntOrNull() ?: return null
            val scale = exp - fraction.length
            if (scale > 1_000_000 || scale < -1_000_000) return null
            val digits = (match.groupValues[2] + fraction).trimStart('0').ifEmpty { "0" }
            if (digits == "0") return BigInteger.ZERO
            val magnitude = if (scale >= 0) {
                BigInteger(digits).multiply(BigInteger.TEN.pow(scale))
            } else {
                BigInteger(digits).divide(BigInteger.TEN.pow(-scale))
            }
            return if (negative) magnitude.negate() else magnitude
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
