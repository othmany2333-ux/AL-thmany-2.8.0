package com.althmany.groupmanager.domain

/**
 * Deterministic self-recovery rules. The policy never clicks an unknown control and never overrides
 * loading or restriction handling. It only decides when a structurally stalled link should be
 * recorded and handed off so the explicit run cannot remain parked forever on an inert screen.
 */
object RuntimeRecoveryPolicy {
    const val FAST_ROOT_UNAVAILABLE_TIMEOUT_MS = 2_500L
    const val NORMAL_ROOT_UNAVAILABLE_TIMEOUT_MS = 8_000L

    fun rootUnavailableTimeoutMs(fast: Boolean): Long =
        if (fast) FAST_ROOT_UNAVAILABLE_TIMEOUT_MS else NORMAL_ROOT_UNAVAILABLE_TIMEOUT_MS

    fun shouldAdvanceStalledUnknown(
        watchdogState: RuntimeWatchdogState,
        loading: Boolean,
        restricted: Boolean,
        pendingAction: Boolean,
        hasRecognizedAction: Boolean
    ): Boolean =
        watchdogState == RuntimeWatchdogState.STALLED &&
            !loading &&
            !restricted &&
            !pendingAction &&
            !hasRecognizedAction
}
