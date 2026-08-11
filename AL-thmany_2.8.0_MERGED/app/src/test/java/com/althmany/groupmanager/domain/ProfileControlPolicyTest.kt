package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileControlPolicyTest {
    @Test
    fun freshProfileHeartbeatRepairsDelayedSamsungCallback() {
        assertTrue(ProfileControlPolicy.isLocalConnectionAlive(false, true))
        assertTrue(ProfileControlPolicy.isLocalConnectionAlive(true, false))
        assertFalse(ProfileControlPolicy.isLocalConnectionAlive(false, false))
    }

    @Test
    fun exactEnabledComponentMayStartWhileCallbackBinds() {
        assertTrue(ProfileControlPolicy.mayStartWhileServiceBinds(true))
        assertFalse(ProfileControlPolicy.mayStartWhileServiceBinds(false))
    }
}
