package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRuntimeProTest {
    @Test
    fun requestActionWinsDuringAdminApprovalNotice() {
        val request = AccessibilityActionScoringPolicy.score(
            AccessibilityJoinAction.REQUEST, true, true, false, true, true, true, true, true
        )
        val join = AccessibilityActionScoringPolicy.score(
            AccessibilityJoinAction.JOIN, true, true, false, true, true, true, true, true
        )
        assertTrue(request > join)
    }

    @Test
    fun confidenceBlocksLoadingAndAcceptsStableInviteAction() {
        val stable = RuntimeIntelligencePolicy.assessAction(
            AccessibilityJoinAction.JOIN,
            inviteContext = true,
            loading = false,
            positiveActionCount = 1,
            terminalEvidenceCount = 0,
            conflict = ScreenEvidenceConflict.NONE,
            stableActionScans = 2,
            stableScreenScans = 2,
            pendingAction = null
        )
        assertTrue(stable.safeToAct)

        val loading = RuntimeIntelligencePolicy.assessAction(
            AccessibilityJoinAction.JOIN,
            inviteContext = true,
            loading = true,
            positiveActionCount = 1,
            terminalEvidenceCount = 0,
            conflict = ScreenEvidenceConflict.NONE,
            stableActionScans = 2,
            stableScreenScans = 2,
            pendingAction = null
        )
        assertFalse(loading.safeToAct)
    }

    @Test
    fun screenFingerprintDoesNotDependOnTraversalOrder() {
        assertEquals(
            RuntimeScreenFingerprint.calculate(sequenceOf("Join group", "Group invite")),
            RuntimeScreenFingerprint.calculate(sequenceOf("Group invite", "Join group"))
        )
    }

    @Test
    fun circuitBreakerTripsOnlyAfterRepeatedStructuralFailures() {
        assertFalse(RuntimeCircuitBreaker.shouldTrip(5))
        assertTrue(RuntimeCircuitBreaker.shouldTrip(6))
        assertEquals(0, RuntimeCircuitBreaker.nextCount(5, "GROUP_FULL"))
    }

    @Test
    fun coordinatorPrioritizesRestrictionThenLoading() {
        assertEquals(
            RuntimeDirective.STOP_RESTRICTED,
            RuntimeDecisionCoordinator.decide(
                RuntimeObservedScreen(true, true, ScreenEvidenceConflict.NONE, true, true),
                conflictShouldHold = false
            )
        )
        assertEquals(
            RuntimeDirective.WAIT_LOADING,
            RuntimeDecisionCoordinator.decide(
                RuntimeObservedScreen(false, true, ScreenEvidenceConflict.NONE, true, true),
                conflictShouldHold = false
            )
        )
    }
}
