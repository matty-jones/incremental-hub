package com.example.hub.session

import android.content.Context
import android.os.SystemClock
import com.example.hub.games.OfflineCapability
import com.example.hub.games.OfflineInspection
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/** Shark's own load path uses this gap before it calls simulateOffline. */
const val BACKGROUND_OFFLINE_THRESHOLD_MS = 60_000L

/**
 * One process start. Elapsed realtime cannot be compared with a sample from
 * an earlier process or an earlier boot.
 */
object ProcessBoot {
    val token: String = UUID.randomUUID().toString()
}

data class ClockSample(
    val wallMillis: Long,
    val elapsedRealtimeMillis: Long,
    val bootToken: String,
)

fun interface BackgroundClock {
    fun sample(): ClockSample
}

class SystemBackgroundClock : BackgroundClock {
    override fun sample(): ClockSample = ClockSample(
        wallMillis = System.currentTimeMillis(),
        elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
        bootToken = ProcessBoot.token,
    )
}

enum class ClockConfidence {
    AGREED,
    WALL_ONLY,
    DISAGREE,
    INVALID,
}

data class MeasuredAbsence(
    val wallMillis: Long,
    val elapsedMillis: Long?,
    val confidence: ClockConfidence,
) {
    val trustedMillis: Long?
        get() = when (confidence) {
            ClockConfidence.AGREED -> elapsedMillis
            ClockConfidence.WALL_ONLY -> wallMillis.takeIf { it >= 0 }
            ClockConfidence.DISAGREE, ClockConfidence.INVALID -> null
        }

    fun meaningful(thresholdMs: Long = BACKGROUND_OFFLINE_THRESHOLD_MS): Boolean {
        val trusted = trustedMillis ?: return false
        return trusted >= thresholdMs
    }

    fun possiblyLong(thresholdMs: Long = BACKGROUND_OFFLINE_THRESHOLD_MS): Boolean {
        if (meaningful(thresholdMs)) return true
        if (confidence != ClockConfidence.DISAGREE) return false
        return (elapsedMillis ?: Long.MIN_VALUE) >= thresholdMs || wallMillis >= thresholdMs
    }
}

fun measureAbsence(
    start: ClockSample,
    end: ClockSample,
    skewAllowanceMillis: Long = 15_000L,
): MeasuredAbsence {
    val wall = end.wallMillis - start.wallMillis
    val sameBoot = start.bootToken.isNotEmpty() && start.bootToken == end.bootToken
    val elapsed = if (sameBoot) end.elapsedRealtimeMillis - start.elapsedRealtimeMillis else null
    val confidence = when {
        elapsed == null && wall >= 0 -> ClockConfidence.WALL_ONLY
        elapsed == null -> ClockConfidence.INVALID
        elapsed < 0 -> ClockConfidence.INVALID
        wall < 0 -> ClockConfidence.DISAGREE
        abs(wall - elapsed) > maxOf(skewAllowanceMillis, elapsed / 20) -> ClockConfidence.DISAGREE
        else -> ClockConfidence.AGREED
    }
    return MeasuredAbsence(wall, elapsed, confidence)
}

/**
 * A pause is a background interval only when it is not a configuration change.
 * Opening the vault or the menu does not pause the activity, so those are not intervals.
 */
fun isBackgroundRecoveryTrigger(lifecyclePause: Boolean, configurationChange: Boolean): Boolean {
    return lifecyclePause && !configurationChange
}

fun formatAbsence(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val totalMinutes = safe / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        minutes > 0L -> "${minutes}m"
        else -> "${safe / 1000L}s"
    }
}

fun absenceHeadline(gameId: String, displayName: String, millis: Long): String {
    val name = if (gameId == "shark-incremental") "Shark" else displayName
    return "$name was in the background for ${formatAbsence(millis)}."
}

enum class ResumeAction {
    IGNORE,
    AUTO,
    ALREADY_APPLIED,
    ASK,
    UNSUPPORTED,
    DISABLED,
    NO_CHECKPOINT,
    DELEGATE_TO_LOAD,
    IN_PROGRESS,
    ELAPSED,
}

data class ResumePlan(
    val action: ResumeAction,
    val headline: String,
    val detail: String,
    val showBanner: Boolean,
    val offerCatchUp: Boolean,
    val offerContinue: Boolean,
    val offerInspect: Boolean,
    val gameGapSeconds: Double?,
)

data class ResumeInput(
    val gameId: String,
    val displayName: String,
    val capability: OfflineCapability,
    val absence: MeasuredAbsence?,
    val checkpointCommitted: Boolean,
    val checkpointHandled: Boolean,
    val sameBoot: Boolean,
    val inspection: OfflineInspection?,
    val checkpointSha256: String?,
)

fun planBackgroundResume(input: ResumeInput): ResumePlan {
    val absence = input.absence
    val duration = absence?.trustedMillis ?: absence?.elapsedMillis ?: absence?.wallMillis ?: 0L
    val headline = absenceHeadline(input.gameId, input.displayName, duration.coerceAtLeast(0L))
    val gap = input.inspection?.gapSeconds
    fun plan(
        action: ResumeAction,
        detail: String,
        showBanner: Boolean = true,
        catchUp: Boolean = false,
        continueSession: Boolean = true,
        inspect: Boolean = input.checkpointCommitted,
    ) = ResumePlan(action, headline, detail, showBanner, catchUp, continueSession, inspect, gap)

    if (absence == null || !absence.possiblyLong()) {
        return plan(ResumeAction.IGNORE, "The absence was shorter than the offline threshold.", showBanner = false, continueSession = false, inspect = false)
    }
    if (!input.sameBoot) {
        return plan(
            ResumeAction.DELEGATE_TO_LOAD,
            "The process restarted. A qualified game calculates this interval when it loads. Hub did not apply it again.",
            showBanner = false,
            continueSession = false,
            inspect = false,
        )
    }
    if (input.capability == OfflineCapability.UNSUPPORTED) {
        return plan(
            ResumeAction.UNSUPPORTED,
            "This game has no verified offline calculation in Hub. The live session was kept.",
            catchUp = false,
            inspect = false,
        )
    }
    if (input.capability == OfflineCapability.ELAPSED_TIME) {
        return plan(
            ResumeAction.ELAPSED,
            "This game applies elapsed time in its own loop. Hub did not add a second calculation.",
            showBanner = false,
            continueSession = false,
            inspect = false,
        )
    }
    if (!input.checkpointCommitted || input.checkpointSha256.isNullOrBlank()) {
        return plan(
            ResumeAction.NO_CHECKPOINT,
            "Hub could not verify a background save, so offline progression was not run. The live session was kept.",
            catchUp = false,
            inspect = false,
        )
    }
    if (absence.confidence != ClockConfidence.AGREED) {
        return plan(
            ResumeAction.ASK,
            "The device clock and the elapsed timer disagree, so offline progression was not run automatically.",
            catchUp = input.capability == OfflineCapability.NATIVE_ON_RESUME ||
                input.capability == OfflineCapability.NATIVE_ON_LOAD,
        )
    }
    val inspection = input.inspection
    if (inspection == null || !inspection.supported) {
        return plan(
            ResumeAction.ASK,
            "The running game could not be compared with the background save. Nothing was changed.",
            catchUp = true,
        )
    }
    if (inspection.simulationActive || inspection.blocksSave) {
        return plan(
            ResumeAction.IN_PROGRESS,
            "Offline progression is already running. Hub did not start it again and did not save the partial result.",
            catchUp = false,
            continueSession = true,
        )
    }
    if (!inspection.offlineEnabled) {
        return plan(
            ResumeAction.DISABLED,
            "Offline progression is turned off in the game. The live session was kept.",
            catchUp = false,
        )
    }
    val hashMatches = inspection.payloadSha256 != null && inspection.payloadSha256 == input.checkpointSha256
    val gapMillis = gap?.times(1000.0)
    val trusted = absence.trustedMillis
    val gapAgrees = trusted != null && gapMillis != null &&
        abs(gapMillis - trusted) <= maxOf(15_000.0, trusted / 10.0)
    if (input.checkpointHandled && (gap == null || gap <= 60.0)) {
        return plan(
            ResumeAction.ALREADY_APPLIED,
            "Offline progression was already applied for this absence. It was not applied again.",
            catchUp = false,
        )
    }
    if (hashMatches && gap != null && gap > 60.0 && gapAgrees && input.capability == OfflineCapability.NATIVE_ON_RESUME) {
        return plan(
            ResumeAction.AUTO,
            "Offline calculation is using the game's own clock.",
            catchUp = false,
            continueSession = true,
            inspect = input.checkpointCommitted,
        )
    }
    if (!hashMatches || (gap != null && gap <= 60.0)) {
        return plan(
            ResumeAction.ASK,
            "The running session has moved on from the background save. Offline progression was not applied automatically.",
            catchUp = true,
        )
    }
    if (input.capability == OfflineCapability.NATIVE_ON_LOAD) {
        return plan(
            ResumeAction.ASK,
            "This game calculates offline progress when it loads. Hub did not reload it automatically.",
            catchUp = true,
        )
    }
    return plan(
        ResumeAction.ASK,
        "Hub could not prove that automatic catch-up was safe. The live session was kept.",
        catchUp = false,
    )
}

data class BackgroundCheckpoint(
    val gameId: String,
    val adapterId: String,
    val adapterVersion: String,
    val sessionId: String?,
    val snapshotId: String?,
    val contentSha256: String?,
    val wallStartMillis: Long,
    val elapsedRealtimeMillis: Long,
    val bootToken: String,
    val committed: Boolean,
    val handled: Boolean,
) {
    fun clock(): ClockSample = ClockSample(wallStartMillis, elapsedRealtimeMillis, bootToken)

    fun toJson(): JSONObject {
        return JSONObject()
            .put("gameId", gameId)
            .put("adapterId", adapterId)
            .put("adapterVersion", adapterVersion)
            .put("sessionId", sessionId ?: JSONObject.NULL)
            .put("snapshotId", snapshotId ?: JSONObject.NULL)
            .put("contentSha256", contentSha256 ?: JSONObject.NULL)
            .put("wallStartMillis", wallStartMillis)
            .put("elapsedRealtimeMillis", elapsedRealtimeMillis)
            .put("bootToken", bootToken)
            .put("committed", committed)
            .put("handled", handled)
    }

    companion object {
        fun fromJson(raw: String): BackgroundCheckpoint? {
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val gameId = json.optString("gameId")
            if (gameId.isBlank()) return null
            return BackgroundCheckpoint(
                gameId = gameId,
                adapterId = json.optString("adapterId"),
                adapterVersion = json.optString("adapterVersion"),
                sessionId = json.optString("sessionId").takeIf { it.isNotBlank() },
                snapshotId = json.optString("snapshotId").takeIf { it.isNotBlank() },
                contentSha256 = json.optString("contentSha256").takeIf { it.isNotBlank() },
                wallStartMillis = json.optLong("wallStartMillis"),
                elapsedRealtimeMillis = json.optLong("elapsedRealtimeMillis"),
                bootToken = json.optString("bootToken"),
                committed = json.optBoolean("committed"),
                handled = json.optBoolean("handled"),
            )
        }
    }
}

class BackgroundCheckpointStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(gameId: String): BackgroundCheckpoint? {
        val raw = prefs.getString(gameId, null) ?: return null
        return BackgroundCheckpoint.fromJson(raw)?.takeIf { it.gameId == gameId }
    }

    fun write(checkpoint: BackgroundCheckpoint) {
        prefs.edit().putString(checkpoint.gameId, checkpoint.toJson().toString()).commit()
    }

    fun markHandled(gameId: String) {
        val current = read(gameId) ?: return
        write(current.copy(handled = true))
    }

    fun clear(gameId: String) {
        prefs.edit().remove(gameId).commit()
    }

    private companion object {
        const val PREFS = "hub_background_checkpoint"
    }
}

data class BackgroundNotice(
    val gameId: String,
    val headline: String,
    val detail: String,
    val offerCatchUp: Boolean,
    val offerContinue: Boolean,
    val offerInspect: Boolean,
    val checkpointId: String?,
)

fun shaPrefix(sha: String?): String {
    if (sha.isNullOrBlank()) return "absent"
    return sha.take(12)
}
