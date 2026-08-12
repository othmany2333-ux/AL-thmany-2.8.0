package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCadencePolicyTest {
    @Test
    fun fastCadenceIsResponsiveWithoutBusyLooping() {
        assertTrue(RuntimeCadencePolicy.FAST_EVENT_SCAN_MS >= 12L)
        assertTrue(RuntimeCadencePolicy.FAST_FALLBACK_POLL_MS >= 80L)
        assertTrue(RuntimeCadencePolicy.FAST_CLICK_THROTTLE_MS >= 60L)
        assertTrue(RuntimeCadencePolicy.FAST_GESTURE_DURATION_MS >= 16L)
    }

    @Test
    fun stableScreensRelaxScanCadence() {
        val fresh = RuntimeCadencePolicy.minScanIntervalMs(true, 1)
        val stable = RuntimeCadencePolicy.minScanIntervalMs(true, 8)
        assertTrue(stable > fresh)
        assertEquals(RuntimeCadencePolicy.FAST_FALLBACK_POLL_MS, RuntimeCadencePolicy.pollIntervalMs(true))
    }
}
