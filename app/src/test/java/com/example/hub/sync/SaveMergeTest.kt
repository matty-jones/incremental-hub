package com.example.hub.sync

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
}
