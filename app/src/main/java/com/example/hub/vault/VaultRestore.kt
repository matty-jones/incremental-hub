package com.example.hub.vault

import com.example.hub.games.GameAdapter
import com.example.hub.games.PayloadDigest
import com.example.hub.games.ProgressionClass
import com.example.hub.games.combineProgression

/**
 * Decision for one vault restore. Classification comes from
 * [GameAdapter.classifyProgress]. A lexical comparison is not a reason to
 * restore or to move a pointer.
 */
data class VaultAssessment(
    val snapshotId: String,
    val lineageId: String,
    val capturedAtMillis: Long,
    val classification: ProgressionClass,
    val ready: Boolean,
    val allowed: Boolean,
    val requiresBranch: Boolean,
    val proposeWorkstationPointer: Boolean,
    val moveHighWater: Boolean,
    val localBranch: Boolean,
    val sourceDetail: String,
    val currentDetail: String,
    val unorderedNote: String?,
    val message: String,
    val committed: Boolean = false,
)

suspend fun assessVaultRestore(
    requestedId: String,
    requestedLineageId: String,
    recordId: String,
    recordLineageId: String,
    recordSha256: String,
    payload: String?,
    capturedAtMillis: Long,
    pointerPayload: String?,
    highWaterPayload: String? = null,
    adapter: GameAdapter,
    confirmed: Boolean,
    branchConfirmed: Boolean,
): VaultAssessment {
    val sourceDetail = summarize(adapter, payload)
    val currentDetail = summarize(adapter, pointerPayload)
    fun refuse(classification: ProgressionClass, message: String): VaultAssessment {
        return VaultAssessment(
            snapshotId = requestedId,
            lineageId = requestedLineageId,
            capturedAtMillis = capturedAtMillis,
            classification = classification,
            ready = false,
            allowed = false,
            requiresBranch = false,
            proposeWorkstationPointer = false,
            moveHighWater = false,
            localBranch = false,
            sourceDetail = sourceDetail,
            currentDetail = currentDetail,
            unorderedNote = null,
            message = message,
        )
    }
    if (requestedId != recordId || requestedLineageId != recordLineageId) {
        return refuse(
            ProgressionClass.INVALID,
            "The snapshot id and lineage do not match. Nothing was written.",
        )
    }
    if (payload.isNullOrBlank()) {
        return refuse(
            ProgressionClass.INVALID,
            "The snapshot has no payload. Nothing was written.",
        )
    }
    val digest = PayloadDigest.sha256(payload)
    if (recordSha256.isNotEmpty() && recordSha256 != digest) {
        return refuse(
            ProgressionClass.INVALID,
            "The stored hash does not match the payload. Nothing was written.",
        )
    }
    val valid = try {
        adapter.validate(payload)
    } catch (_: Exception) {
        false
    }
    if (!valid) {
        return refuse(
            ProgressionClass.INVALID,
            "The snapshot failed validation. Nothing was written.",
        )
    }
    val againstPointer = try {
        adapter.classifyProgress(payload, pointerPayload)
    } catch (_: Exception) {
        ProgressionClass.INCOMPARABLE
    }
    val classification = if (highWaterPayload.isNullOrBlank() || highWaterPayload == pointerPayload) {
        againstPointer
    } else {
        val againstHighWater = try {
            adapter.classifyProgress(payload, highWaterPayload)
        } catch (_: Exception) {
            ProgressionClass.INCOMPARABLE
        }
        combineProgression(againstPointer, againstHighWater)
    }
    val requiresBranch = classification == ProgressionClass.REGRESSED ||
        classification == ProgressionClass.INCOMPARABLE
    val unorderedNote = if (
        classification == ProgressionClass.EQUAL &&
        payload != pointerPayload
    ) {
        "Milestones match. Currencies, upgrades, and other fields are not ordered."
    } else {
        null
    }
    val allowed = when (classification) {
        ProgressionClass.INVALID -> false
        ProgressionClass.REGRESSED, ProgressionClass.INCOMPARABLE -> confirmed && branchConfirmed
        ProgressionClass.EQUAL,
        ProgressionClass.ADVANCED,
        ProgressionClass.UNORDERED,
        ProgressionClass.RESET_APPROVED,
        -> confirmed
    }
    val propose = allowed && !requiresBranch && classification == ProgressionClass.ADVANCED
    val message = when {
        !allowed && requiresBranch ->
            "This snapshot is behind the protected pointer. Restore it only on a new lineage. The current save was kept."
        !allowed ->
            "Confirm to restore this snapshot. Nothing has been written."
        requiresBranch ->
            "This restore starts a new local lineage. The workstation pointer was not moved."
        classification == ProgressionClass.ADVANCED ->
            "This snapshot is ahead of the current save."
        else ->
            "The workstation pointer and the high-water snapshot stay where they are."
    }
    return VaultAssessment(
        snapshotId = recordId,
        lineageId = recordLineageId,
        capturedAtMillis = capturedAtMillis,
        classification = classification,
        ready = true,
        allowed = allowed,
        requiresBranch = requiresBranch,
        proposeWorkstationPointer = propose,
        moveHighWater = propose && payload != pointerPayload,
        localBranch = allowed && requiresBranch,
        sourceDetail = sourceDetail,
        currentDetail = currentDetail,
        unorderedNote = unorderedNote,
        message = message,
    )
}

private fun summarize(adapter: GameAdapter, payload: String?): String {
    return try {
        adapter.summarize(payload).detail
    } catch (_: Exception) {
        "unavailable"
    }
}
