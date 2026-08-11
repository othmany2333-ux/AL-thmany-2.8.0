package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuLaunchPolicyTest {
    @Test
    fun `accepts a normal activity-manager launch`() {
        assertTrue(ShizukuLaunchPolicy.launchAccepted(0, "Starting: Intent { act=android.intent.action.VIEW }"))
    }

    @Test
    fun `accepts delivery to an already running WhatsApp activity`() {
        assertTrue(
            ShizukuLaunchPolicy.launchAccepted(
                0,
                "Warning: Activity not started, intent has been delivered to currently running top-most instance."
            )
        )
    }

    @Test
    fun `rejects semantic errors even with exit code zero`() {
        assertFalse(ShizukuLaunchPolicy.launchAccepted(0, "Error: Activity class does not exist."))
        assertFalse(ShizukuLaunchPolicy.launchAccepted(0, "SecurityException: Permission Denial"))
    }

    @Test
    fun `rejects a failing shell exit code`() {
        assertFalse(ShizukuLaunchPolicy.launchAccepted(126, ""))
    }
}
