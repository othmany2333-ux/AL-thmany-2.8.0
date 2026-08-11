package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuContinuityPolicyTest {
    @Test
    fun workProfileCompatibilityProbeIsFastAndBounded() {
        assertFalse(ShizukuContinuityPolicy.shouldProbeProfileCompatibleTree(179L, 0))
        assertTrue(ShizukuContinuityPolicy.shouldProbeProfileCompatibleTree(180L, 0))
        assertTrue(ShizukuContinuityPolicy.shouldProbeProfileCompatibleTree(800L, 1))
        assertFalse(ShizukuContinuityPolicy.shouldProbeProfileCompatibleTree(800L, 2))
    }
}
