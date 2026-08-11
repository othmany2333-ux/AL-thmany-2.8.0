package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationPolicyTest {
    @Test
    fun userTransitionDelaySupportsSubSecondControl() {
        assertEquals(0, AutomationPolicy.MIN_DELAY_SECONDS)
        assertEquals(60, AutomationPolicy.MAX_DELAY_SECONDS)
        assertEquals(0, AutomationPolicy.MIN_INTER_LINK_DELAY_MS)
        assertEquals(10_000, AutomationPolicy.MAX_INTER_LINK_DELAY_MS)
        assertEquals(100, AutomationPolicy.INTER_LINK_DELAY_STEP_MS)
        assertEquals(1, AutomationPolicy.MIN_ACTION_TIMEOUT_SECONDS)
        assertEquals(60, AutomationPolicy.MAX_ACTION_TIMEOUT_SECONDS)
    }

    @Test
    fun delayIsClampedToSupportedRange() {
        assertEquals(AutomationPolicy.MIN_DELAY_SECONDS, AutomationPolicy.clampDelaySeconds(0))
        assertEquals(42, AutomationPolicy.clampDelaySeconds(42))
        assertEquals(AutomationPolicy.MAX_DELAY_SECONDS, AutomationPolicy.clampDelaySeconds(999))
        assertEquals(0, AutomationPolicy.clampInterLinkDelayMs(-1))
        assertEquals(350, AutomationPolicy.clampInterLinkDelayMs(350))
        assertEquals(10_000, AutomationPolicy.clampInterLinkDelayMs(99_000))
        assertEquals(1, AutomationPolicy.notificationDelaySeconds(100))
        assertEquals(2, AutomationPolicy.notificationDelaySeconds(1_001))
    }

    @Test
    fun actionTimeoutIsClampedAndEstimateIncludesEveryLinkAndGap() {
        assertEquals(
            AutomationPolicy.MIN_ACTION_TIMEOUT_SECONDS,
            AutomationPolicy.clampActionTimeoutSeconds(1)
        )
        assertEquals(45, AutomationPolicy.clampActionTimeoutSeconds(45))
        assertEquals(
            AutomationPolicy.MAX_ACTION_TIMEOUT_SECONDS,
            AutomationPolicy.clampActionTimeoutSeconds(999)
        )
        assertEquals(
            10 * 35 + 9 * 12,
            AutomationPolicy.estimatedSessionSeconds(10, 12, 35)
        )
        assertEquals(
            10 * 35 + 5,
            AutomationPolicy.estimatedSessionSecondsFromMillis(10, 500, 35)
        )
    }

    @Test
    fun queueAndExplicitRunLimitsStaySynchronized() {
        assertEquals(1_000_000, AutomationPolicy.MAX_LINKS_PER_SESSION)
        assertEquals(1000, AutomationPolicy.BATCH_SIZE)
        assertEquals(1_000, AutomationPolicy.MAX_BATCHES_PER_SESSION)
    }

    @Test
    fun fastHandsFreePresetStaysInsideSupportedRanges() {
        assertEquals(
            AutomationPolicy.FAST_DELAY_SECONDS,
            AutomationPolicy.clampDelaySeconds(AutomationPolicy.FAST_DELAY_SECONDS)
        )
        assertEquals(
            AutomationPolicy.FAST_ACTION_TIMEOUT_SECONDS,
            AutomationPolicy.clampActionTimeoutSeconds(AutomationPolicy.FAST_ACTION_TIMEOUT_SECONDS)
        )
        assertEquals(1, AutomationPolicy.SMART_START_COUNTDOWN_SECONDS)
    }

}
