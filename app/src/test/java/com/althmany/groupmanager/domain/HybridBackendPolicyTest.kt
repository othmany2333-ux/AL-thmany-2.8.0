package com.althmany.groupmanager.domain

import com.althmany.groupmanager.model.AutomationBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HybridBackendPolicyTest {
    @Test fun explicitShizukuFallsBackWhenAccessibilityIsLive() {
        assertEquals(AutomationBackend.ACCESSIBILITY, HybridBackendPolicy.chooseForStart(AutomationBackend.SHIZUKU, true, false))
    }

    @Test fun explicitShizukuStaysShizukuWhenReady() {
        assertEquals(AutomationBackend.SHIZUKU, HybridBackendPolicy.chooseForStart(AutomationBackend.SHIZUKU, true, true))
    }

    @Test fun explicitShizukuCannotRunWhenNeitherBackendIsReady() {
        assertNull(HybridBackendPolicy.chooseForStart(AutomationBackend.SHIZUKU, false, false))
    }
}
