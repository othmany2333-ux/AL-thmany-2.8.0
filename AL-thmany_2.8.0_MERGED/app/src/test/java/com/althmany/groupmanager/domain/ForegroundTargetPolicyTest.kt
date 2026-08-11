package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTargetPolicyTest {
    @Test fun transientWindowDoesNotPause() {
        assertFalse(ForegroundTargetPolicy.shouldPauseOutsideTarget(100, 500, true))
    }

    @Test fun recentTargetEventDoesNotPause() {
        assertFalse(ForegroundTargetPolicy.shouldPauseOutsideTarget(400, 100, true))
    }

    @Test fun stableOutsideTargetPauses() {
        assertTrue(ForegroundTargetPolicy.shouldPauseOutsideTarget(400, 400, true))
    }
}
