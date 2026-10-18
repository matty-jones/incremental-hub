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
 * A missing timestamp means that side has no snapshot.
 * A progressed save beats a fresh one even when the fresh save is newer.
 * When both saves have progressed, [comparison] treats the local payload as live.
 * Otherwise the newer capture wins, and equal timestamps stay put.
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
        when (comparison) {
            SaveComparison.LIVE_AHEAD -> return MergeDecision.Push
            SaveComparison.LIVE_BEHIND, SaveComparison.LIVE_MISSING -> return MergeDecision.Pull
            else -> Unit
        }
    }
    return when {
        localCapturedAtMillis > remoteCapturedAtMillis -> MergeDecision.Push
        remoteCapturedAtMillis > localCapturedAtMillis -> MergeDecision.Pull
        else -> MergeDecision.Skip
    }
}

/**
 * An open session keeps its stored pointer. A pull is archived by the caller
 * and is not written over the save the game is using.
 */
fun replacesStoredPointer(sessionOpen: Boolean, decision: MergeDecision): Boolean =
    decision == MergeDecision.Pull && !sessionOpen
