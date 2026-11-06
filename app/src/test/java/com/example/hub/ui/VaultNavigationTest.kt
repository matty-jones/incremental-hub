package com.example.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultNavigationTest {

    @Test
    fun detailBackReturnsToTheTimeline() {
        assertEquals(VaultBackTarget.TIMELINE, vaultBackTarget(detailOpen = true))
    }

    @Test
    fun timelineBackLeavesTheVault() {
        assertEquals(VaultBackTarget.LEAVE, vaultBackTarget(detailOpen = false))
    }

    @Test
    fun aCommittedRestoreLeavesTheOverlay() {
        assertTrue(closeVaultAfterCommittedRestore(committed = true))
        assertFalse(closeVaultAfterCommittedRestore(committed = false))
    }

    @Test
    fun leavingAfterACommitDoesNotClearThePlan() {
        assertFalse(cancelVaultPlanOnLeave(committed = true))
        assertTrue(cancelVaultPlanOnLeave(committed = false))
    }

    @Test
    fun recoveryConfirmationBackDoesNotLeaveTheSession() {
        assertTrue(
            recoveryBackDismissesConfirmation(
                confirmingRestore = true,
                confirmingSnapshot = false,
                confirmingStartOver = false,
            ),
        )
        assertTrue(
            recoveryBackDismissesConfirmation(
                confirmingRestore = false,
                confirmingSnapshot = true,
                confirmingStartOver = false,
            ),
        )
        assertFalse(
            recoveryBackDismissesConfirmation(
                confirmingRestore = false,
                confirmingSnapshot = false,
                confirmingStartOver = false,
            ),
        )
    }
}
