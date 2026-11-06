package com.example.hub.session

import com.example.hub.games.OfflineCapability
import com.example.hub.games.OfflineInspection
import com.example.hub.games.SharkIncrementalAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRecoveryTest {

    @Test
    fun shortAbsenceDoesNotOfferRecovery() {
        val plan = plan(absence = agreed(10_000))
        assertEquals(ResumeAction.IGNORE, plan.action)
        assertFalse(plan.showBanner)
        assertFalse(plan.offerCatchUp)
    }

    @Test
    fun eightHourMatchRunsNativeOfflineOnce() {
        val plan = plan(absence = agreed(EIGHT_HOURS), gapSeconds = 8.0 * 3600, hashMatches = true)
        assertEquals(ResumeAction.AUTO, plan.action)
        assertTrue(plan.headline.contains("8h"))
        assertFalse(plan.offerCatchUp)
    }

    @Test
    fun aHandledShortGapDoesNotRunAgain() {
        val plan = plan(
            absence = agreed(EIGHT_HOURS),
            gapSeconds = 5.0,
            hashMatches = true,
            handled = true,
        )
        assertEquals(ResumeAction.ALREADY_APPLIED, plan.action)
        assertFalse(plan.offerCatchUp)
    }

    @Test
    fun aRunningLoopThatAlreadyMovedTheClockAsks() {
        val plan = plan(absence = agreed(EIGHT_HOURS), gapSeconds = 2.0, hashMatches = false)
        assertEquals(ResumeAction.ASK, plan.action)
        assertTrue(plan.offerCatchUp)
        assertTrue(plan.offerContinue)
        assertTrue(plan.offerInspect)
    }

    @Test
    fun disabledOfflineDoesNotCatchUp() {
        val plan = plan(absence = agreed(EIGHT_HOURS), gapSeconds = 8.0 * 3600, hashMatches = true, enabled = false)
        assertEquals(ResumeAction.DISABLED, plan.action)
        assertFalse(plan.offerCatchUp)
    }

    @Test
    fun aMissingCheckpointDoesNotCatchUp() {
        val plan = plan(absence = agreed(EIGHT_HOURS), committed = false, sha = null)
        assertEquals(ResumeAction.NO_CHECKPOINT, plan.action)
        assertFalse(plan.offerCatchUp)
        assertFalse(plan.offerInspect)
    }

    @Test
    fun aRestartDelegatesToTheGameLoadPath() {
        val plan = plan(absence = wallOnly(EIGHT_HOURS), sameBoot = false)
        assertEquals(ResumeAction.DELEGATE_TO_LOAD, plan.action)
        assertFalse(plan.showBanner)
        assertFalse(plan.offerCatchUp)
    }

    @Test
    fun anActiveSimulationIsNotStartedAgain() {
        val plan = plan(absence = agreed(EIGHT_HOURS), gapSeconds = 8.0 * 3600, hashMatches = true, active = true)
        assertEquals(ResumeAction.IN_PROGRESS, plan.action)
        assertFalse(plan.offerCatchUp)
    }

    @Test
    fun clockDisagreementDoesNotRunAutomatically() {
        val plan = plan(absence = MeasuredAbsence(EIGHT_HOURS, 5_000, ClockConfidence.DISAGREE))
        assertEquals(ResumeAction.ASK, plan.action)
        assertFalse(plan.detail.contains("completed"))
        assertTrue(plan.offerCatchUp)
    }

    @Test
    fun invalidElapsedTimeIsNotTrusted() {
        val backwards = measureAbsence(
            ClockSample(1_000, 50_000, "boot"),
            ClockSample(2_000, 1_000, "boot"),
        )
        assertEquals(ClockConfidence.INVALID, backwards.confidence)
        assertFalse(backwards.meaningful())
        val rewound = measureAbsence(
            ClockSample(10_000, 1_000, "boot"),
            ClockSample(1_000, 20_000, "boot"),
        )
        assertEquals(ClockConfidence.DISAGREE, rewound.confidence)
        assertFalse(rewound.meaningful())
    }

    @Test
    fun foldAndVaultNavigationAreNotBackgroundIntervals() {
        assertFalse(isBackgroundRecoveryTrigger(lifecyclePause = true, configurationChange = true))
        assertFalse(isBackgroundRecoveryTrigger(lifecyclePause = false, configurationChange = false))
        assertFalse(isBackgroundRecoveryTrigger(lifecyclePause = false, configurationChange = true))
        assertTrue(isBackgroundRecoveryTrigger(lifecyclePause = true, configurationChange = false))
    }

    @Test
    fun anUnsupportedGameDoesNotOfferCatchUp() {
        val plan = plan(absence = agreed(EIGHT_HOURS), capability = OfflineCapability.UNSUPPORTED)
        assertEquals(ResumeAction.UNSUPPORTED, plan.action)
        assertFalse(plan.offerCatchUp)
        assertTrue(plan.detail.contains("no verified offline"))
    }

    @Test
    fun sharkStartsFromItsOwnClock() {
        val script = SharkIncrementalAdapter.JS_START_OFFLINE
        assertTrue(script.contains("simulateOffline(gap)"))
        assertTrue(script.contains("player.latest_time"))
        assertFalse(script.contains("__GAP__"))
        assertTrue(SharkIncrementalAdapter().offlineSupport().capability == OfflineCapability.NATIVE_ON_RESUME)
    }

    @Test
    fun agreedClocksUseElapsedRealtime() {
        val measured = measureAbsence(
            ClockSample(1_000_000, 10_000, "boot"),
            ClockSample(1_000_000 + EIGHT_HOURS, 10_000 + EIGHT_HOURS, "boot"),
        )
        assertEquals(ClockConfidence.AGREED, measured.confidence)
        assertEquals(EIGHT_HOURS, measured.trustedMillis)
        assertEquals("7h 52m", formatAbsence(EIGHT_HOURS - 8 * 60_000))
    }

    private fun agreed(millis: Long) = MeasuredAbsence(millis, millis, ClockConfidence.AGREED)

    private fun wallOnly(millis: Long) = MeasuredAbsence(millis, null, ClockConfidence.WALL_ONLY)

    private fun plan(
        absence: MeasuredAbsence,
        gapSeconds: Double? = null,
        hashMatches: Boolean = true,
        enabled: Boolean = true,
        active: Boolean = false,
        committed: Boolean = true,
        handled: Boolean = false,
        sameBoot: Boolean = true,
        capability: OfflineCapability = OfflineCapability.NATIVE_ON_RESUME,
        sha: String? = "abc123",
    ): ResumePlan {
        val inspection = if (gapSeconds == null) {
            null
        } else {
            OfflineInspection(
                supported = true,
                latestTimeMillis = 0L,
                gameNowMillis = (gapSeconds * 1000).toLong(),
                offlineEnabled = enabled,
                simulationActive = active,
                blocksSave = active,
                payloadSha256 = if (hashMatches) sha else "other",
            )
        }
        return planBackgroundResume(
            ResumeInput(
                gameId = "shark-incremental",
                displayName = "Shark Incremental",
                capability = capability,
                absence = absence,
                checkpointCommitted = committed,
                checkpointHandled = handled,
                sameBoot = sameBoot,
                inspection = inspection,
                checkpointSha256 = sha,
            ),
        )
    }

    private companion object {
        const val EIGHT_HOURS = 8L * 60L * 60L * 1000L
    }
}
