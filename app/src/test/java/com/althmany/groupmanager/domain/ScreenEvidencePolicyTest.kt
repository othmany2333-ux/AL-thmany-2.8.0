package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenEvidencePolicyTest {
    @Test
    fun contradictoryTerminalAndActionWaitsForAConfirmingFrame() {
        val conflict = ScreenEvidencePolicy.conflict(
            ScreenEvidenceSummary(1, 1, conversationSurface = false, homeSurface = false)
        )
        assertEquals(ScreenEvidenceConflict.TERMINAL_WITH_POSITIVE_ACTION, conflict)
        assertTrue(ScreenEvidencePolicy.shouldHold(conflict, 1))
        assertFalse(ScreenEvidencePolicy.shouldHold(conflict, 2))
    }

    @Test
    fun multipleTerminalStatesAreTreatedAsTransitionNoiseFirst() {
        val conflict = ScreenEvidencePolicy.conflict(
            ScreenEvidenceSummary(2, 0, conversationSurface = false, homeSurface = false)
        )
        assertEquals(ScreenEvidenceConflict.MULTIPLE_TERMINAL_STATES, conflict)
    }

    @Test
    fun multiplePositiveActionsAreHeldAsTransitionNoiseFirst() {
        val conflict = ScreenEvidencePolicy.conflict(
            ScreenEvidenceSummary(0, 2, conversationSurface = false, homeSurface = false)
        )
        assertEquals(ScreenEvidenceConflict.MULTIPLE_POSITIVE_ACTIONS, conflict)
        assertTrue(ScreenEvidencePolicy.shouldHold(conflict, 1))
    }

    @Test
    fun pendingActionRejectsUnrelatedStaleControl() {
        assertFalse(
            ScreenEvidencePolicy.actionAllowedWhilePending(
                AccessibilityJoinAction.JOIN, AccessibilityJoinAction.PREVIEW
            )
        )
        assertTrue(
            ScreenEvidencePolicy.actionAllowedWhilePending(
                AccessibilityJoinAction.JOIN, AccessibilityJoinAction.CONFIRM
            )
        )
    }
}
