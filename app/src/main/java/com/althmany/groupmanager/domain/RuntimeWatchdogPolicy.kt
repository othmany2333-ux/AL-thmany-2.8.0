package com.althmany.groupmanager.domain

enum class RuntimeWatchdogState {
    HEALTHY,
    LOADING,
    TRANSITION_NOISE,
    STALLED
}

/**
 * Pure stall detector. The service decides the recovery action.
 *
 * Turbo uses a shorter, still multi-scan stall window so an inert WhatsApp surface cannot park the
 * run indefinitely. Normal mode keeps the conservative 8-second threshold.
 */
object RuntimeWatchdogPolicy {
    const val STALLED_STABLE_SCREEN_SCANS = 12
    const val STALLED_MIN_AGE_MS = 8_000L
    const val TURBO_STALLED_STABLE_SCREEN_SCANS = 7
    const val TURBO_STALLED_MIN_AGE_MS = 1_600L

    fun assess(
        stableScreenScans: Int,
        stageAgeMs: Long,
        loading: Boolean,
        conflict: ScreenEvidenceConflict,
        turbo: Boolean = false
    ): RuntimeWatchdogState {
        val requiredScans = if (turbo) TURBO_STALLED_STABLE_SCREEN_SCANS else STALLED_STABLE_SCREEN_SCANS
        val minimumAge = if (turbo) TURBO_STALLED_MIN_AGE_MS else STALLED_MIN_AGE_MS
        return when {
            loading -> RuntimeWatchdogState.LOADING
            conflict != ScreenEvidenceConflict.NONE && stableScreenScans < requiredScans ->
                RuntimeWatchdogState.TRANSITION_NOISE
            stableScreenScans >= requiredScans && stageAgeMs >= minimumAge ->
                RuntimeWatchdogState.STALLED
            else -> RuntimeWatchdogState.HEALTHY
        }
    }
}
