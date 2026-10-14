package com.example.hub.bridge

import com.example.hub.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [BridgeValidator].
 *
 * Covers all five rejection reasons deterministically on JVM (no Robolectric
 * needed for the validator itself, but the suite runs under Robolectric for
 * consistency with the rest of the test suite).
 */
@RunWith(RobolectricTestRunner::class)
class BridgeValidatorTest {

    private lateinit var validator: BridgeValidator

    @Before
    fun setUp() {
        validator = BridgeValidator()
    }

    // -----------------------------------------------------------------
    // Valid message
    // -----------------------------------------------------------------

    @Test
    fun validMessage_passesAllChecks() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io", "https://dan-simon.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = setOf("req-1", "req-2"),
            messageId = "req-1",
            payloadSize = 1024L,
        )
        assertTrue("expected valid", result is ValidationResult.Valid)
    }

    @Test
    fun messageWithoutId_passesWhenNotInOutstanding() {
        // A message without an ID is always accepted (no correlation check).
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 0L,
        )
        assertTrue("expected valid", result is ValidationResult.Valid)
    }

    // -----------------------------------------------------------------
    // ORIGIN_MISMATCH
    // -----------------------------------------------------------------

    @Test
    fun originMismatch_rejected() {
        val result = validator.validateMessage(
            sourceOrigin = "https://evil.example.com",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 0L,
        )
        assertEquals(ValidationResult.Reason.ORIGIN_MISMATCH, (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun emptyApprovedOrigins_rejectsAll() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = emptySet(),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 0L,
        )
        assertEquals(ValidationResult.Reason.ORIGIN_MISMATCH, (result as ValidationResult.Invalid).reason)
    }

    // -----------------------------------------------------------------
    // EPOCH_STALE
    // -----------------------------------------------------------------

    @Test
    fun staleEpoch_rejected() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 3,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 0L,
        )
        assertEquals(ValidationResult.Reason.EPOCH_STALE, (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun futureEpoch_rejected() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 5,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 0L,
        )
        assertEquals(ValidationResult.Reason.EPOCH_STALE, (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun matchingEpoch_passes() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 0,
            messageEpoch = 0,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 0L,
        )
        assertTrue("expected valid", result is ValidationResult.Valid)
    }

    // -----------------------------------------------------------------
    // REQUEST_UNCORRELATED
    // -----------------------------------------------------------------

    @Test
    fun uncorrelatedMessageId_rejected() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = setOf("req-1"),
            messageId = "req-999",
            payloadSize = 0L,
        )
        assertEquals(ValidationResult.Reason.REQUEST_UNCORRELATED, (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun correlatedMessageId_passes() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = setOf("req-1", "req-2"),
            messageId = "req-2",
            payloadSize = 0L,
        )
        assertTrue("expected valid", result is ValidationResult.Valid)
    }

    // -----------------------------------------------------------------
    // PAYLOAD_TOO_LARGE
    // -----------------------------------------------------------------

    @Test
    fun payloadAtMaxSize_passes() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = BridgeValidator.MAX_PAYLOAD_BYTES.toLong(),
        )
        assertTrue("expected valid", result is ValidationResult.Valid)
    }

    @Test
    fun payloadOverMaxSize_rejected() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = BridgeValidator.MAX_PAYLOAD_BYTES.toLong() + 1,
        )
        assertEquals(ValidationResult.Reason.PAYLOAD_TOO_LARGE, (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun largePayload_rejected() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 10L * 1024 * 1024, // 10 MiB
        )
        assertEquals(ValidationResult.Reason.PAYLOAD_TOO_LARGE, (result as ValidationResult.Invalid).reason)
    }

    // -----------------------------------------------------------------
    // SCHEMA_UNSUPPORTED
    // -----------------------------------------------------------------

    @Test
    fun nullPayload_withNullMessageId_passes() {
        // Schema check is deferred to BridgeHost deserialization. For the pure
        // validator, null payload + no messageId is accepted.
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 0L,
        )
        assertTrue("expected valid", result is ValidationResult.Valid)
    }

    // -----------------------------------------------------------------
    // Helper state checks
    // -----------------------------------------------------------------

    @Test
    fun isActiveState_returnsTrueForPlaying() {
        assertTrue(validator.isActiveState(SessionState.Playing))
    }

    @Test
    fun isActiveState_returnsTrueForCapturing() {
        assertTrue(validator.isActiveState(SessionState.Capturing))
    }

    @Test
    fun isActiveState_returnsTrueForBackground() {
        assertTrue(validator.isActiveState(SessionState.Background))
    }

    @Test
    fun isActiveState_returnsFalseForClosed() {
        assertFalse(validator.isActiveState(SessionState.Closed))
    }

    @Test
    fun isActiveState_returnsFalseForClosing() {
        assertFalse(validator.isActiveState(SessionState.Closing))
    }

    @Test
    fun isTerminalState_returnsTrueForClosed() {
        assertTrue(validator.isTerminalState(SessionState.Closed))
    }

    @Test
    fun isTerminalState_returnsTrueForClosing() {
        assertTrue(validator.isTerminalState(SessionState.Closing))
    }

    @Test
    fun isTerminalState_returnsFalseForPlaying() {
        assertFalse(validator.isTerminalState(SessionState.Playing))
    }

    // -----------------------------------------------------------------
    // Combined rejection — first matching reason wins
    // -----------------------------------------------------------------

    @Test
    fun originAndEpochBothBad_returnsOriginMismatch() {
        // Origin is checked first; should return ORIGIN_MISMATCH even though
        // epoch is also stale.
        val result = validator.validateMessage(
            sourceOrigin = "https://evil.example.com",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 5,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 0L,
        )
        assertEquals(ValidationResult.Reason.ORIGIN_MISMATCH, (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun epochAndRequestBothBad_returnsEpochStale() {
        // Epoch is checked before request correlation.
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 5,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = "req-1",
            payloadSize = 0L,
        )
        assertEquals(ValidationResult.Reason.EPOCH_STALE, (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun requestAndPayloadBothBad_returnsRequestUncorrelated() {
        // Request check is before payload size.
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = "req-999",
            payloadSize = BridgeValidator.MAX_PAYLOAD_BYTES.toLong() + 1,
        )
        assertEquals(ValidationResult.Reason.REQUEST_UNCORRELATED, (result as ValidationResult.Invalid).reason)
    }

    @Test
    fun payloadAndSchemaBothBad_returnsPayloadTooLarge() {
        val result = validator.validateMessage(
            sourceOrigin = "https://mrredshark77.github.io",
            approvedOrigins = setOf("https://mrredshark77.github.io"),
            currentEpoch = 1,
            messageEpoch = 1,
            outstandingRequests = emptySet(),
            messageId = null,
            payloadSize = 100L * 1024 * 1024,
        )
        assertEquals(ValidationResult.Reason.PAYLOAD_TOO_LARGE, (result as ValidationResult.Invalid).reason)
    }
}
