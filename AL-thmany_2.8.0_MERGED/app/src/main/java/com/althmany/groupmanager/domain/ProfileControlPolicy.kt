package com.althmany.groupmanager.domain

/**
 * Pure policy for determining whether Accessibility control is actually local to the Android
 * profile where this AL-thmany process is running. A system-level "enabled" flag alone is not
 * sufficient on Work Profile / Samsung isolated environments because another profile can expose
 * the same package name while its service process is not connected here.
 */
enum class ProfileControlCapability {
    READY,
    SERVICE_DISABLED,
    SERVICE_NOT_CONNECTED_LOCALLY,
    WAITING_FOR_TARGET_EVENT,
    TARGET_UI_TREE_UNAVAILABLE
}

object ProfileControlPolicy {
    const val SERVICE_HEARTBEAT_FRESH_MS = 12_000L
    const val TARGET_EVENT_FRESH_MS = 12_000L
    const val ROOT_SNAPSHOT_FRESH_MS = 12_000L

    // Android/Samsung may briefly report an Accessibility service as disabled while a freshly
    // updated APK is reconnecting. Never reopen the one-time setup dialog from a single negative
    // sample. A real disabled state must stay negative for several consecutive profile-local reads.
    const val ACCESSIBILITY_SETUP_CONFIRM_READS = 3
    const val ACCESSIBILITY_RECONNECT_WAIT_MS = 4_000L
    const val ACCESSIBILITY_RECONNECT_POLL_MS = 100L

    /**
     * Samsung can host an enabled Accessibility service in a framework-managed process lifecycle
     * where the Activity observes the profile-local heartbeat before the companion callback flag.
     * Both signals are produced by this exact package/user, so either fresh signal proves that the
     * local service is alive. A stale secure setting alone still never passes this check.
     */
    fun isLocalConnectionAlive(
        processCallbackConnected: Boolean,
        profileHeartbeatConnected: Boolean
    ): Boolean = processCallbackConnected || profileHeartbeatConnected

    /**
     * An explicit Start may continue once Android reports the exact component enabled. The first
     * WhatsApp window event then wakes/reconnects the service. Requiring the callback beforehand
     * deadlocked Samsung devices that showed the service as running but delayed its app callback.
     */
    fun mayStartWhileServiceBinds(systemEnabled: Boolean): Boolean = systemEnabled

    fun shouldPromptAccessibilitySetup(
        sawSystemEnabledDuringWait: Boolean,
        consecutiveDisabledReads: Int
    ): Boolean = !sawSystemEnabledDuringWait &&
        consecutiveDisabledReads >= ACCESSIBILITY_SETUP_CONFIRM_READS

    fun isLocalServiceReady(systemEnabled: Boolean, heartbeatAgeMs: Long?): Boolean =
        systemEnabled && heartbeatAgeMs != null && heartbeatAgeMs in 0..SERVICE_HEARTBEAT_FRESH_MS

    fun classify(
        systemEnabled: Boolean,
        localServiceConnected: Boolean,
        targetEventAgeMs: Long?,
        rootAgeMs: Long?,
        rootAvailable: Boolean,
        targetEventMatches: Boolean = true,
        rootPackageMatches: Boolean = true
    ): ProfileControlCapability {
        if (!systemEnabled) return ProfileControlCapability.SERVICE_DISABLED
        if (!localServiceConnected) return ProfileControlCapability.SERVICE_NOT_CONNECTED_LOCALLY
        if (!targetEventMatches || targetEventAgeMs == null || targetEventAgeMs !in 0..TARGET_EVENT_FRESH_MS) {
            return ProfileControlCapability.WAITING_FOR_TARGET_EVENT
        }
        if (!rootPackageMatches || rootAgeMs == null || rootAgeMs !in 0..ROOT_SNAPSHOT_FRESH_MS || !rootAvailable) {
            return ProfileControlCapability.TARGET_UI_TREE_UNAVAILABLE
        }
        return ProfileControlCapability.READY
    }
}
