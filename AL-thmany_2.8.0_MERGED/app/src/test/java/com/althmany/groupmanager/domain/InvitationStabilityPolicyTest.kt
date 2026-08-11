package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationStabilityPolicyTest {
    @Test
    fun oneSecondUiTimeoutStillGetsHumanSizedRuntimeGrace() {
        assertEquals(10_000L, InvitationStabilityPolicy.effectiveActionTimeoutMs(1))
        assertEquals(1_000L, InvitationStabilityPolicy.effectiveActionTimeoutMs(1, turbo = true))
        assertEquals(20_000L, InvitationStabilityPolicy.loadingTimeoutMs(1))
    }

    @Test
    fun loadingBudgetScalesButIsBounded() {
        assertEquals(24_000L, InvitationStabilityPolicy.loadingTimeoutMs(12))
        assertEquals(60_000L, InvitationStabilityPolicy.loadingTimeoutMs(60))
        assertTrue(InvitationStabilityPolicy.ACTION_STABLE_SCANS >= 2)
        assertTrue(InvitationStabilityPolicy.POST_JOIN_STABLE_NON_INVITE_SCANS >= 2)
        assertEquals(1, InvitationStabilityPolicy.TURBO_POST_JOIN_STABLE_NON_INVITE_SCANS)
        assertEquals(1, InvitationStabilityPolicy.CONVERSATION_STABLE_SCANS)
    }
}
