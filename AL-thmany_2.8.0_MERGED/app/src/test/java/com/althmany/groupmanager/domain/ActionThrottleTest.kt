package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionThrottleTest {
    @Test
    fun blocksRapidDuplicateActions() {
        val throttle = ActionThrottle(800)
        assertTrue(throttle.tryAcquire(1_000))
        assertFalse(throttle.tryAcquire(1_500))
        assertTrue(throttle.tryAcquire(1_800))
    }

    @Test
    fun resetAllowsImmediateAction() {
        val throttle = ActionThrottle(10_000)
        assertTrue(throttle.tryAcquire(1_000))
        throttle.reset()
        assertTrue(throttle.tryAcquire(1_001))
    }
}
