package com.example.hub.sync

import com.example.hub.games.SaveComparison
import com.example.hub.games.SaveStanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveMergeTest {

    @Test
    fun newerClockDoesNotMoveEitherSave() {
        assertEquals(MergeDecision.Skip, decideMerge(localCapturedAtMillis = 20, remoteCapturedAtMillis = 10))
        assertEquals(MergeDecision.Skip, decideMerge(localCapturedAtMillis = 10, remoteCapturedAtMillis = 20))
        assertEquals(
            MergeDecision.Skip,
            decideMerge(
                localCapturedAtMillis = 10,
                remoteCapturedAtMillis = 50,
                localStanding = SaveStanding.PROGRESSED,
                remoteStanding = SaveStanding.PROGRESSED,
                comparison = SaveComparison.MATCH,
            ),
        )
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
    fun incomparableProgressDoesNotFollowTheClock() {
        assertEquals(
            MergeDecision.Skip,
            decideMerge(
                localCapturedAtMillis = 10,
                remoteCapturedAtMillis = 50,
                localStanding = SaveStanding.PROGRESSED,
                remoteStanding = SaveStanding.PROGRESSED,
                comparison = SaveComparison.INCONCLUSIVE,
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

    @Test
    fun newerInstallableBuildIsAvailable() {
        val check = classifyUpdateResponse(
            installedVersionCode = 9,
            versionCode = 10,
            versionName = "0.1.9",
            downloadUrl = "http://192.168.86.3:8006/app/hub.apk",
        )
        assertTrue(check is UpdateCheck.Available)
        val info = (check as UpdateCheck.Available).info
        assertEquals(10, info.versionCode)
        assertEquals("0.1.9", info.versionName)
        assertNull(info.commitMessage)
        assertNull(updateCheckStatus(check))
    }

    @Test
    fun availableBuildKeepsTheCommitMessage() {
        val check = classifyUpdateResponse(
            installedVersionCode = 9,
            versionCode = 10,
            versionName = "0.1.9",
            downloadUrl = "http://example/hub.apk",
            commitMessage = "  Show the note in the update dialog.\n\nSecond paragraph.  ",
        )
        val info = (check as UpdateCheck.Available).info
        assertEquals(
            "Show the note in the update dialog.\n\nSecond paragraph.",
            info.commitMessage,
        )
    }

    @Test
    fun longCommitMessageTruncatesOnAWord() {
        val message = "word ".repeat(80).trim()
        val shown = truncateCommitMessage(message, maxChars = 40)
        assertTrue(shown.endsWith("..."))
        assertTrue(shown.length <= 43)
        assertTrue(shown.dropLast(3).endsWith("word"))
    }

    @Test
    fun shortCommitMessageStaysWhole() {
        val message = "Fix the launcher rule.\n\nIt now spans the screen."
        assertEquals(message, truncateCommitMessage(message))
    }

    @Test
    fun currentOrOlderBuildStaysCurrent() {
        assertEquals(
            UpdateCheck.Current,
            classifyUpdateResponse(9, 9, "0.1.8", "http://example/hub.apk"),
        )
        assertEquals(
            UpdateCheck.Current,
            classifyUpdateResponse(9, 8, "0.1.7", "http://example/hub.apk"),
        )
        assertEquals("This install is current.", updateCheckStatus(UpdateCheck.Current))
    }

    @Test
    fun incompleteUpdatePayloadIsUnreachable() {
        assertEquals(
            UpdateCheck.Unreachable,
            classifyUpdateResponse(9, -1, "", ""),
        )
        assertEquals(
            UpdateCheck.Unreachable,
            classifyUpdateResponse(9, 10, "0.1.9", ""),
        )
        assertEquals(
            "The workstation did not offer an update.",
            updateCheckStatus(UpdateCheck.Unreachable),
        )
    }
}
