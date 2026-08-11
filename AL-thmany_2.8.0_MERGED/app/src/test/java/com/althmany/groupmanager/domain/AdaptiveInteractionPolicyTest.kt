package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveInteractionPolicyTest {
    @Test
    fun terminalOutcomeNeedsRepeatedEvidence() {
        assertFalse(AdaptiveInteractionPolicy.shouldTrustOutcome(1))
        assertTrue(AdaptiveInteractionPolicy.shouldTrustOutcome(2))
    }

    @Test
    fun homeRecoveryIsStableAndBounded() {
        assertFalse(AdaptiveInteractionPolicy.shouldAdvanceFromHome(2, 8_000L))
        assertTrue(AdaptiveInteractionPolicy.shouldAdvanceFromHome(3, 4_500L))
        assertFalse(AdaptiveInteractionPolicy.shouldAdvanceFromHome(0, 249L, turbo = true))
        assertTrue(AdaptiveInteractionPolicy.shouldAdvanceFromHome(1, 250L, turbo = true))
    }

    @Test
    fun controlsSettleAfterLoading() {
        assertTrue(AdaptiveInteractionPolicy.shouldWaitAfterLoading(500L))
        assertFalse(AdaptiveInteractionPolicy.shouldWaitAfterLoading(1_500L))
        assertTrue(AdaptiveInteractionPolicy.shouldWaitAfterLoading(60L, turbo = true))
        assertFalse(AdaptiveInteractionPolicy.shouldWaitAfterLoading(100L, turbo = true))
    }
}
