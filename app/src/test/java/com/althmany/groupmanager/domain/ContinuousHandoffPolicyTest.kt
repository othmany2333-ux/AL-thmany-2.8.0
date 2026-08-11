package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousHandoffPolicyTest {
    @Test
    fun turboUsesShortBoundedLivenessWindow() {
        assertEquals(40L, ContinuousHandoffPolicy.watchIntervalMs(turbo = true))
        assertEquals(1_000L, ContinuousHandoffPolicy.nonLoadingHardLimitMs(turbo = true))
    }

    @Test
    fun loadingAndRestrictionsAreNeverForceAdvanced() {
        assertFalse(
            ContinuousHandoffPolicy.shouldForceAdvance(
                pendingAgeMs = 10_000L,
                loading = true,
                restricted = false,
                turbo = true
            )
        )
        assertFalse(
            ContinuousHandoffPolicy.shouldForceAdvance(
                pendingAgeMs = 10_000L,
                loading = false,
                restricted = true,
                turbo = true
            )
        )
    }

    @Test
    fun stalledNonLoadingActionAdvancesAfterBound() {
        assertFalse(
            ContinuousHandoffPolicy.shouldForceAdvance(
                pendingAgeMs = 999L,
                loading = false,
                restricted = false,
                turbo = true
            )
        )
        assertFalse(
            ContinuousHandoffPolicy.shouldForceAdvance(
                pendingAgeMs = 5_000L,
                loading = false,
                restricted = false,
                turbo = true,
                stableScreenScans = 1
            )
        )
        assertTrue(
            ContinuousHandoffPolicy.shouldForceAdvance(
                pendingAgeMs = 1_000L,
                loading = false,
                restricted = false,
                turbo = true,
                stableScreenScans = 2
            )
        )
    }

    @Test
    fun turboConversationCanHandOffDirectly() {
        assertTrue(ContinuousHandoffPolicy.shouldLaunchNextDirectlyFromConversation(turbo = true))
        assertFalse(ContinuousHandoffPolicy.shouldLaunchNextDirectlyFromConversation(turbo = false))
    }
}
