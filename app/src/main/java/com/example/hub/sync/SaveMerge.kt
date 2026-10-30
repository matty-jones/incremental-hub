package com.example.hub.sync

import com.example.hub.games.SaveComparison
import com.example.hub.games.SaveStanding

/**
 * One saved game, as stored on the workstation and compared on the phone.
 * [capturedAtMillis] is the phone clock from the capture that produced [snapshotPayload].
 */
data class RemoteSave(
    val gameId: String,
    val snapshotPayload: String,
    val capturedAtMillis: Long,
    val profileName: String,
    val sessionIdentity: String,
    val lastPlayedAtMillis: Long,
    val resumeToken: String?,
    val recoveryState: String?,
)

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val commitMessage: String? = null,
)

sealed class UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck()
    data object Current : UpdateCheck()
    data object Unreachable : UpdateCheck()
}

/**
 * A newer installable build is [UpdateCheck.Available]. An older or equal
 * build that still names a download is [UpdateCheck.Current]. Anything else
 * cannot be installed.
 */
fun classifyUpdateResponse(
    installedVersionCode: Int,
    versionCode: Int,
    versionName: String,
    downloadUrl: String,
    commitMessage: String? = null,
): UpdateCheck {
    if (versionCode < 0 || versionName.isBlank() || downloadUrl.isBlank()) {
        return UpdateCheck.Unreachable
    }
    if (versionCode <= installedVersionCode) return UpdateCheck.Current
    return UpdateCheck.Available(
        UpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            downloadUrl = downloadUrl,
            commitMessage = commitMessage?.trim()?.takeIf { it.isNotEmpty() },
        ),
    )
}

/** Dialog copy for a commit message. Short notes stay whole; a long one keeps its line breaks up to [maxChars]. */
fun truncateCommitMessage(message: String, maxChars: Int = 280): String {
    val trimmed = message.trim()
    if (trimmed.length <= maxChars) return trimmed
    val cut = trimmed.take(maxChars)
    val boundary = cut.lastIndexOfAny(charArrayOf(' ', '\n'))
    val end = if (boundary >= maxChars / 2) boundary else cut.length
    return cut.take(end).trimEnd() + "..."
}

/** Status line for a manual check. An available build uses the update dialog. */
fun updateCheckStatus(check: UpdateCheck): String? = when (check) {
    is UpdateCheck.Available -> null
    UpdateCheck.Current -> "This install is current."
    UpdateCheck.Unreachable -> "The workstation did not offer an update."
}

sealed interface PutResult {
    data object Stored : PutResult
    data class Stale(val remote: RemoteSave) : PutResult
}

enum class MergeDecision {
    Push,
    Pull,
    Skip,
}

/**
 * A missing timestamp means that side has no snapshot.
 * A progressed save beats a fresh one.
 * When both saves have progressed, [comparison] treats the local payload as live.
 * A wall clock is not a progression order. Two present snapshots with no
 * accepted comparison stay where they are.
 */
fun decideMerge(
    localCapturedAtMillis: Long?,
    remoteCapturedAtMillis: Long?,
    localStanding: SaveStanding? = null,
    remoteStanding: SaveStanding? = null,
    comparison: SaveComparison? = null,
): MergeDecision {
    if (localCapturedAtMillis == null && remoteCapturedAtMillis == null) return MergeDecision.Skip
    if (localCapturedAtMillis == null) return MergeDecision.Pull
    if (remoteCapturedAtMillis == null) return MergeDecision.Push
    val localProgressed = localStanding == SaveStanding.PROGRESSED
    val remoteProgressed = remoteStanding == SaveStanding.PROGRESSED
    if (localProgressed && remoteStanding == SaveStanding.FRESH) return MergeDecision.Push
    if (localStanding == SaveStanding.FRESH && remoteProgressed) return MergeDecision.Pull
    if (localProgressed && remoteProgressed) {
        return when (comparison) {
            SaveComparison.LIVE_AHEAD -> MergeDecision.Push
            SaveComparison.LIVE_BEHIND, SaveComparison.LIVE_MISSING -> MergeDecision.Pull
            else -> MergeDecision.Skip
        }
    }
    return MergeDecision.Skip
}

data class SnapshotUpload(
    val gameId: String,
    val snapshotId: String,
    val lineageId: String,
    val snapshotPayload: String,
    val capturedAtMillis: Long,
    val idempotencyKey: String,
    val integrity: String,
    val reason: String,
    val profileName: String,
    val sessionIdentity: String,
    val lastPlayedAtMillis: Long,
    val resumeToken: String?,
)

sealed class SnapshotUploadResult {
    data class Stored(
        val created: Boolean,
        val snapshotId: String,
        val sha256: String,
    ) : SnapshotUploadResult()

    data object Rejected : SnapshotUploadResult()
}

data class RemotePointer(
    val gameId: String,
    val lineageId: String,
    val snapshotId: String,
    val version: Int,
    val snapshotPayload: String,
    val capturedAtMillis: Long,
    val sha256: String,
    val profileName: String,
    val sessionIdentity: String,
    val lastPlayedAtMillis: Long,
    val resumeToken: String?,
    val recoveryState: String?,
)

data class RemoteSnapshot(
    val snapshotId: String,
    val gameId: String,
    val lineageId: String,
    val sha256: String,
    val snapshotPayload: String,
    val capturedAtMillis: Long,
    val integrity: String,
    val source: String,
)

data class SnapshotMetadata(
    val snapshotId: String,
    val gameId: String,
    val lineageId: String,
    val sha256: String,
    val capturedAtMillis: Long,
    val receivedAtMillis: Long,
    val integrity: String,
    val source: String,
)

data class HistoryPage(
    val entries: List<SnapshotMetadata>,
    val complete: Boolean,
)

sealed class PointerFetch {
    data class Found(val pointer: RemotePointer) : PointerFetch()
    data object Absent : PointerFetch()
    data object Unreachable : PointerFetch()
}

data class PointerProposal(
    val gameId: String,
    val expectedVersion: Int,
    val snapshotId: String,
    val lineageId: String,
)

sealed class PointerProposalResult {
    data class Stored(val pointer: RemotePointer) : PointerProposalResult()
    data class Conflict(val conflict: String, val pointer: RemotePointer?) : PointerProposalResult()
}

/**
 * An open session keeps its stored pointer. A pull is archived by the caller
 * and is not written over the save the game is using.
 */
fun replacesStoredPointer(sessionOpen: Boolean, decision: MergeDecision): Boolean =
    decision == MergeDecision.Pull && !sessionOpen
