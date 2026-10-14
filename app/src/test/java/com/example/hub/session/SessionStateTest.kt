package com.example.hub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive and targeted unit tests for [SessionState] and [canTransition].
 *
 * Covers all 81 (9×9) transition pairs plus a handful of named positive/negative
 * cases for readability. No Android dependencies are required — the test runs
 * under standard JUnit with Robolectric available if needed.
 */
class SessionStateTest {

    companion object {
        /** All nine states in a stable order for exhaustive enumeration. */
        private val STATES = listOf(
            SessionState.Closed,
            SessionState.Opening,
            SessionState.Playing,
            SessionState.Capturing,
            SessionState.Background,
            SessionState.Restoring,
            SessionState.Degraded,
            SessionState.RecoveryRequired,
            SessionState.Closing,
        )
    }

    // ---------------------------------------------------------------------
    // Exhaustive matrix — every (from, to) pair against the spec
    // ---------------------------------------------------------------------

    @Test
    fun `all81TransitionPairsMatchSpec`() {
        for (from in STATES) {
            for (to in STATES) {
                val expected = expectedTransition(from, to)
                val actual = canTransition(from, to)
                assertEquals(
                    "canTransition(${stateName(from)}, ${stateName(to)}) should be $expected",
                    expected,
                    actual,
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Positive-path named tests — the spec's canonical transitions
    // ---------------------------------------------------------------------

    @Test
    fun `closedToOpening_isAllowed`() {
        assertTrue(canTransition(SessionState.Closed, SessionState.Opening))
    }

    @Test
    fun `openingToPlaying_isAllowed`() {
        assertTrue(canTransition(SessionState.Opening, SessionState.Playing))
    }

    @Test
    fun `openingToRecoveryRequired_isAllowed`() {
        assertTrue(canTransition(SessionState.Opening, SessionState.RecoveryRequired))
    }

    @Test
    fun `playingToCapturing_isAllowed`() {
        assertTrue(canTransition(SessionState.Playing, SessionState.Capturing))
    }

    @Test
    fun `playingToBackground_isAllowed`() {
        assertTrue(canTransition(SessionState.Playing, SessionState.Background))
    }

    @Test
    fun `playingToDegraded_isAllowed`() {
        assertTrue(canTransition(SessionState.Playing, SessionState.Degraded))
    }

    @Test
    fun `playingToClosing_isAllowed`() {
        assertTrue(canTransition(SessionState.Playing, SessionState.Closing))
    }

    @Test
    fun `capturingToPlaying_isAllowed`() {
        assertTrue(canTransition(SessionState.Capturing, SessionState.Playing))
    }

    @Test
    fun `capturingToBackground_isAllowed`() {
        assertTrue(canTransition(SessionState.Capturing, SessionState.Background))
    }

    @Test
    fun `backgroundToOpening_isAllowed`() {
        assertTrue(canTransition(SessionState.Background, SessionState.Opening))
    }

    @Test
    fun `backgroundToClosing_isAllowed`() {
        assertTrue(canTransition(SessionState.Background, SessionState.Closing))
    }

    @Test
    fun `degradedToRecoveryRequired_isAllowed`() {
        assertTrue(canTransition(SessionState.Degraded, SessionState.RecoveryRequired))
    }

    @Test
    fun `degradedToClosing_isAllowed`() {
        assertTrue(canTransition(SessionState.Degraded, SessionState.Closing))
    }

    @Test
    fun `recoveryRequiredToOpening_isAllowed`() {
        assertTrue(canTransition(SessionState.RecoveryRequired, SessionState.Opening))
    }

    @Test
    fun `recoveryRequiredToClosing_isAllowed`() {
        assertTrue(canTransition(SessionState.RecoveryRequired, SessionState.Closing))
    }

    @Test
    fun `closingToClosed_isAllowed`() {
        assertTrue(canTransition(SessionState.Closing, SessionState.Closed))
    }

    // ---------------------------------------------------------------------
    // Negative-path named tests — illegal transitions that must be rejected
    // ---------------------------------------------------------------------

    @Test
    fun `closedToPlaying_isRejected`() {
        assertFalse(canTransition(SessionState.Closed, SessionState.Playing))
    }

    @Test
    fun `closingToPlaying_isRejected`() {
        assertFalse(canTransition(SessionState.Closing, SessionState.Playing))
    }

    @Test
    fun `playingToClosed_isRejected`() {
        assertFalse(canTransition(SessionState.Playing, SessionState.Closed))
    }

    @Test
    fun `playingToOpening_isRejected`() {
        assertFalse(canTransition(SessionState.Playing, SessionState.Opening))
    }

    @Test
    fun `capturingToClosed_isRejected`() {
        assertFalse(canTransition(SessionState.Capturing, SessionState.Closed))
    }

    @Test
    fun `backgroundToPlaying_isAllowed`() {
        assertTrue(canTransition(SessionState.Background, SessionState.Playing))
    }

    @Test
    fun `degradedToPlaying_isRejected`() {
        assertFalse(canTransition(SessionState.Degraded, SessionState.Playing))
    }

    @Test
    fun `recoveryRequiredToPlaying_isRejected`() {
        assertFalse(canTransition(SessionState.RecoveryRequired, SessionState.Playing))
    }

    @Test
    fun `restoringToAnyState_isRejected`() {
        // Restoring is a sink state in the spec — nothing is listed as a valid
        // transition out of it. Every target should be rejected.
        for (target in STATES) {
            assertFalse(
                "Restoring->$target should be rejected",
                canTransition(SessionState.Restoring, target),
            )
        }
    }

    @Test
    fun `selfTransitions_areRejected`() {
        // No state transitions to itself in the spec.
        for (state in STATES) {
            assertFalse(
                "${stateName(state)}->${stateName(state)} should be rejected",
                canTransition(state, state),
            )
        }
    }

    @Test
    fun `openingToClosed_isRejected`() {
        assertFalse(canTransition(SessionState.Opening, SessionState.Closed))
    }

    @Test
    fun `openingToCapturing_isRejected`() {
        assertFalse(canTransition(SessionState.Opening, SessionState.Capturing))
    }

    @Test
    fun `closingToOpening_isRejected`() {
        assertFalse(canTransition(SessionState.Closing, SessionState.Opening))
    }

    // ---------------------------------------------------------------------
    // Helper — compute the expected result for any (from, to) pair
    // ---------------------------------------------------------------------

    private fun expectedTransition(from: SessionState, to: SessionState): Boolean {
        return when (from) {
            SessionState.Closed -> to is SessionState.Opening
            SessionState.Opening -> to is SessionState.Playing || to is SessionState.Degraded || to is SessionState.RecoveryRequired
            SessionState.Playing -> to in listOf(
                SessionState.Capturing,
                SessionState.Background,
                SessionState.Degraded,
                SessionState.Closing,
            )
            SessionState.Capturing -> to is SessionState.Playing || to is SessionState.Background
            SessionState.Background -> to is SessionState.Playing || to is SessionState.Opening || to is SessionState.Closing
            SessionState.Degraded -> to is SessionState.RecoveryRequired || to is SessionState.Closing
            SessionState.RecoveryRequired -> to is SessionState.Opening || to is SessionState.Closing
            SessionState.Closing -> to is SessionState.Closed
            SessionState.Restoring -> false // sink state — no legal outgoing transitions
        }
    }

    private fun stateName(state: SessionState): String = when (state) {
        SessionState.Closed -> "Closed"
        SessionState.Opening -> "Opening"
        SessionState.Playing -> "Playing"
        SessionState.Capturing -> "Capturing"
        SessionState.Background -> "Background"
        SessionState.Restoring -> "Restoring"
        SessionState.Degraded -> "Degraded"
        SessionState.RecoveryRequired -> "RecoveryRequired"
        SessionState.Closing -> "Closing"
    }
}
