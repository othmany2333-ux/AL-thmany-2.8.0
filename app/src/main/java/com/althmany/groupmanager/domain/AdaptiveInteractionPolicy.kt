package com.althmany.groupmanager.domain

/**
 * Context-aware stability rules used by the Accessibility adapter.
 *
 * These rules are intentionally deterministic: they make the engine wait for repeated evidence,
 * recover from known WhatsApp surfaces conservatively, and avoid reacting to one-frame UI noise.
 * They do not add random delays or behavior intended to disguise automation.
 */
object AdaptiveInteractionPolicy {
    const val OUTCOME_STABLE_SCANS = 2
    const val UNKNOWN_STABLE_SCANS_BEFORE_FAILURE = 4
    const val RECENT_LOADING_SETTLE_MS = 650L
    const val TURBO_RECENT_LOADING_SETTLE_MS = 80L
    const val HOME_SURFACE_STABLE_SCANS = 3
    const val TURBO_HOME_SURFACE_STABLE_SCANS = 1
    const val HOME_ADVANCE_AFTER_MS = 4_000L
    const val TURBO_HOME_ADVANCE_AFTER_MS = 250L

    fun shouldTrustOutcome(stableScans: Int): Boolean =
        stableScans >= OUTCOME_STABLE_SCANS

    fun shouldWaitAfterLoading(elapsedSinceLoadingMs: Long, turbo: Boolean = false): Boolean {
        val settle = if (turbo) TURBO_RECENT_LOADING_SETTLE_MS else RECENT_LOADING_SETTLE_MS
        return elapsedSinceLoadingMs in 0 until settle
    }

    fun shouldAdvanceFromHome(
        stableScans: Int,
        stageAgeMs: Long,
        turbo: Boolean = false
    ): Boolean {
        val requiredScans = if (turbo) TURBO_HOME_SURFACE_STABLE_SCANS else HOME_SURFACE_STABLE_SCANS
        val minimumAge = if (turbo) TURBO_HOME_ADVANCE_AFTER_MS else HOME_ADVANCE_AFTER_MS
        return stableScans >= requiredScans && stageAgeMs >= minimumAge
    }

    fun unknownIsStableEnough(stableScans: Int): Boolean =
        stableScans >= UNKNOWN_STABLE_SCANS_BEFORE_FAILURE
}
