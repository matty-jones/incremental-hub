package com.example.hub.sync

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
)

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
 * Newest capture wins. A null timestamp means that side has no snapshot.
 * Equal timestamps stay put so a retry does not shuffle a save that already matches.
 */
fun decideMerge(localCapturedAtMillis: Long?, remoteCapturedAtMillis: Long?): MergeDecision {
    if (localCapturedAtMillis == null && remoteCapturedAtMillis == null) return MergeDecision.Skip
    if (localCapturedAtMillis == null) return MergeDecision.Pull
    if (remoteCapturedAtMillis == null) return MergeDecision.Push
    return when {
        localCapturedAtMillis > remoteCapturedAtMillis -> MergeDecision.Push
        remoteCapturedAtMillis > localCapturedAtMillis -> MergeDecision.Pull
        else -> MergeDecision.Skip
    }
}
