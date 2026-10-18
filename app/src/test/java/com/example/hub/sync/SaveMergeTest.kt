package com.example.hub.sync

import com.example.hub.games.SaveComparison
import com.example.hub.games.SaveStanding
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveMergeTest {

    @Test
    fun localNewerPushes() {
        assertEquals(MergeDecision.Push, decideMerge(localCapturedAtMillis = 20, remoteCapturedAtMillis = 10))
    }

    @Test
    fun remoteNewerPulls() {
        assertEquals(MergeDecision.Pull, decideMerge(localCapturedAtMillis = 10, remoteCapturedAtMillis = 20))
    }

    @Test
    fun equalTimestampsSkip() {
        assertEquals(MergeDecision.Skip, decideMerge(localCapturedAtMillis = 10, remoteCapturedAtMillis = 10))
    }

    @Test
    fun localOnlyPushes() {
        assertEquals(MergeDecision.Push, decideMerge(localCapturedAtMillis = 10, remoteCapturedAtMillis = null))
    }

    @Test
    fun remoteOnlyPulls() {
        assertEquals(MergeDecision.Pull, decideMerge(localCapturedAtMillis = null, remoteCapturedAtMillis = 10))
    }

    @Test
    fun neitherSideSkips() {
        assertEquals(MergeDecision.Skip, decideMerge(localCapturedAtMillis = null, remoteCapturedAtMillis = null))
    }

    @Test
    fun freshLocalDoesNotReplaceProgressedRemote() {
        assertEquals(
            MergeDecision.Pull,
            decideMerge(
                localCapturedAtMillis = 50,
                remoteCapturedAtMillis = 10,
                localStanding = SaveStanding.FRESH,
                remoteStanding = SaveStanding.PROGRESSED,
            ),
        )
    }

    @Test
    fun freshRemoteDoesNotReplaceProgressedLocal() {
        assertEquals(
            MergeDecision.Push,
            decideMerge(
                localCapturedAtMillis = 10,
                remoteCapturedAtMillis = 50,
                localStanding = SaveStanding.PROGRESSED,
                remoteStanding = SaveStanding.FRESH,
            ),
        )
    }

    @Test
    fun furtherProgressWinsOverANewerClock() {
        assertEquals(
            MergeDecision.Pull,
            decideMerge(
                localCapturedAtMillis = 50,
                remoteCapturedAtMillis = 10,
                localStanding = SaveStanding.PROGRESSED,
                remoteStanding = SaveStanding.PROGRESSED,
                comparison = SaveComparison.LIVE_BEHIND,
            ),
        )
        assertEquals(
            MergeDecision.Push,
            decideMerge(
                localCapturedAtMillis = 10,
                remoteCapturedAtMillis = 50,
                localStanding = SaveStanding.PROGRESSED,
                remoteStanding = SaveStanding.PROGRESSED,
                comparison = SaveComparison.LIVE_AHEAD,
            ),
        )
    }
}
