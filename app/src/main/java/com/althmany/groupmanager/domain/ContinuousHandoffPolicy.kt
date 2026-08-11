package com.althmany.groupmanager.domain

/**
 * Liveness rules for an explicit Accessibility-assisted run.
 *
 * A successfully pressed Join/Request must never leave the run parked forever, but force-advance
 * is allowed only after the post-action surface itself is stable. Loading and restriction screens
 * are never force-skipped by this policy.
 */
object ContinuousHandoffPolicy {
    const val TURBO_WATCH_INTERVAL_MS = 40L
    const val NORMAL_WATCH_INTERVAL_MS = 180L
    const val TURBO_NON_LOADING_HARD_LIMIT_MS = 1_000L
    const val NORMAL_NON_LOADING_HARD_LIMIT_MS = 4_000L
    const val MIN_STABLE_SCANS_FOR_FORCE_ADVANCE = 2

    fun watchIntervalMs(turbo: Boolean): Long =
        if (turbo) TURBO_WATCH_INTERVAL_MS else NORMAL_WATCH_INTERVAL_MS

    fun nonLoadingHardLimitMs(turbo: Boolean): Long =
        if (turbo) TURBO_NON_LOADING_HARD_LIMIT_MS else NORMAL_NON_LOADING_HARD_LIMIT_MS

    fun shouldForceAdvance(
        pendingAgeMs: Long,
        loading: Boolean,
        restricted: Boolean,
        turbo: Boolean,
        stableScreenScans: Int = Int.MAX_VALUE
    ): Boolean =
        !loading &&
            !restricted &&
            stableScreenScans >= MIN_STABLE_SCANS_FOR_FORCE_ADVANCE &&
            pendingAgeMs >= nonLoadingHardLimitMs(turbo)

    fun shouldLaunchNextDirectlyFromConversation(turbo: Boolean): Boolean = turbo
}
