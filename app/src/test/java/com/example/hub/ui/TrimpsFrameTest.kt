package com.example.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimpsFrameTest {
    @Test
    fun coverPostureUsesSmallestWidth() {
        assertTrue(isCoverPosture(411))
        assertTrue(isCoverPosture(599))
        assertFalse(isCoverPosture(600))
        assertFalse(isCoverPosture(701))
    }

    @Test
    fun onlyTheClosedCoverLocksTrimpsToLandscape() {
        assertTrue(TrimpsFrame.requestsLandscape("trimps", 411))
        assertFalse(TrimpsFrame.requestsLandscape("trimps", 701))
        assertFalse(TrimpsFrame.requestsLandscape("shark-incremental", 411))
        assertFalse(TrimpsFrame.requestsLandscape("fe000000", 411))
    }

    @Test
    fun bottomInsetKeepsLabelTextClearAndOnlyAppliesToTrimps() {
        assertEquals(TrimpsFrame.COVER_BOTTOM_INSET_PX, TrimpsFrame.bottomInsetPx("trimps", 411))
        assertEquals(TrimpsFrame.INNER_BOTTOM_INSET_PX, TrimpsFrame.bottomInsetPx("trimps", 701))
        assertEquals(0, TrimpsFrame.bottomInsetPx("shark-incremental", 411))
        assertEquals(0, TrimpsFrame.bottomInsetPx("fe000000", 701))
    }
}
