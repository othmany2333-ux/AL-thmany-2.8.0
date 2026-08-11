package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRecoveryPolicyTest {
    @Test
    fun stalledUnknownCanAdvanceWithoutUnsafeClick() {
        assertTrue(
            RuntimeRecoveryPolicy.shouldAdvanceStalledUnknown(
                watchdogState = RuntimeWatchdogState.STALLED,
                loading = false,
                restricted = false,
                pendingAction = false,
                hasRecognizedAction = false
            )
        )
    }

    @Test
    fun loadingRestrictionAndPendingActionBlockRecoveryAdvance() {
        assertFalse(RuntimeRecoveryPolicy.shouldAdvanceStalledUnknown(RuntimeWatchdogState.STALLED, true, false, false, false))
        assertFalse(RuntimeRecoveryPolicy.shouldAdvanceStalledUnknown(RuntimeWatchdogState.STALLED, false, true, false, false))
        assertFalse(RuntimeRecoveryPolicy.shouldAdvanceStalledUnknown(RuntimeWatchdogState.STALLED, false, false, true, false))
        assertFalse(RuntimeRecoveryPolicy.shouldAdvanceStalledUnknown(RuntimeWatchdogState.STALLED, false, false, false, true))
    }
}
