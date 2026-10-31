package com.example.hub.vault

import com.example.hub.games.PayloadDigest
import com.example.hub.games.SharkProgress
import org.json.JSONObject

data class SharkMilestones(
    val feature: Int,
    val prestige: Long,
    val core: Long,
    val humanoid: Long,
    val singularity: Boolean,
) {
    fun detail(): String {
        return "feature $feature, prestige $prestige, core $core, humanoid $humanoid, singularity $singularity"
    }
}

data class VaultReading(
    val entry: VaultEntry,
    val payload: String?,
    val utf8Bytes: Int?,
    val computedSha: String?,
    val hashMatches: Boolean?,
    val shark: SharkMilestones?,
    val recomputedDetail: String?,
    val unavailableReason: String?,
)

fun storedProgressDetail(progressJson: String?): String? {
    if (progressJson.isNullOrBlank()) return null
    val detail = runCatching { JSONObject(progressJson).optString("detail") }.getOrNull().orEmpty()
    return detail.ifBlank { null }
}

/**
 * Milestone text for one payload. Shark currencies stay on [com.example.hub.games.DecimalRank]
 * and are not converted through floating point. The vault shows the five
 * monotonic milestones, not the currency magnitude.
 */
fun readSnapshot(gameId: String, entry: VaultEntry, payload: String?): VaultReading {
    if (payload == null) {
        val reason = if (entry.availability == VaultAvailability.WORKSTATION_UNAVAILABLE ||
            entry.residence == VaultResidence.REMOTE_ONLY
        ) {
            "This workstation snapshot is unavailable. The payload was not downloaded."
        } else {
            "The payload for this snapshot is not on the phone."
        }
        return VaultReading(
            entry = entry,
            payload = null,
            utf8Bytes = null,
            computedSha = null,
            hashMatches = null,
            shark = null,
            recomputedDetail = null,
            unavailableReason = reason,
        )
    }
    val sha = PayloadDigest.sha256(payload)
    val matches = if (entry.sha256.isBlank()) null else sha == entry.sha256
    val shark = if (gameId == SHARK_GAME_ID) sharkMilestones(payload) else null
    val recomputed = when {
        shark != null -> shark.detail()
        gameId == SHARK_GAME_ID -> SharkProgress.diagnosePayload(payload).detail
        else -> entry.storedDetail
    }
    return VaultReading(
        entry = entry,
        payload = payload,
        utf8Bytes = payload.toByteArray(Charsets.UTF_8).size,
        computedSha = sha,
        hashMatches = matches,
        shark = shark,
        recomputedDetail = recomputed,
        unavailableReason = null,
    )
}

fun sharkMilestones(payload: String): SharkMilestones? {
    val progress = SharkProgress.parsePayload(payload) ?: return null
    return SharkMilestones(
        feature = progress.feature,
        prestige = progress.prestigeTimes,
        core = progress.coreTimes,
        humanoid = progress.humanoidTimes,
        singularity = progress.singularityFirst,
    )
}

const val SHARK_GAME_ID = "shark-incremental"
