package com.althmany.groupmanager.domain

/**
 * Conservative timing/stability rules for WhatsApp invitation surfaces.
 *
 * The user-facing timeout can still be configured from 1 to 60 seconds, but runtime decisions
 * never treat a partially loaded invitation as failed after only one or two seconds. Loading
 * screens get a separate, longer budget and positive actions must remain stable across scans.
 */
object InvitationStabilityPolicy {
    const val MIN_EFFECTIVE_ACTION_TIMEOUT_MS = 10_000L
    const val TURBO_MIN_EFFECTIVE_ACTION_TIMEOUT_MS = 1_000L
    const val MIN_LOADING_TIMEOUT_MS = 20_000L
    const val MAX_LOADING_TIMEOUT_MS = 60_000L
    const val UNKNOWN_ADVANCE_AFTER_MS = 10_000L
    const val MAX_UNKNOWN_RELAUNCHES = 0
    const val ACTION_STABLE_SCANS = 2
    const val TURBO_STRONG_ACTION_STABLE_SCANS = 1
    const val TURBO_STRONG_ACTION_SCORE_MARGIN = 40
    const val ACTION_RETRY_AFTER_MS = 4_500L
    const val TURBO_ACTION_RETRY_AFTER_MS = 180L
    const val MAX_ACTION_ATTEMPTS = 2
    const val FAST_POST_JOIN_MIN_EVIDENCE_AGE_MS = 45L
    const val NORMAL_POST_JOIN_MIN_EVIDENCE_AGE_MS = 1_200L
    const val POST_JOIN_STABLE_NON_INVITE_SCANS = 2
    const val TURBO_POST_JOIN_STABLE_NON_INVITE_SCANS = 1
    const val CONVERSATION_STABLE_SCANS = 1

    fun postJoinStableNonInviteScans(fastMode: Boolean): Int =
        if (fastMode) TURBO_POST_JOIN_STABLE_NON_INVITE_SCANS else POST_JOIN_STABLE_NON_INVITE_SCANS

    fun postJoinMinEvidenceAgeMs(fastMode: Boolean): Long =
        if (fastMode) FAST_POST_JOIN_MIN_EVIDENCE_AGE_MS else NORMAL_POST_JOIN_MIN_EVIDENCE_AGE_MS

    fun effectiveActionTimeoutMs(configuredSeconds: Int, turbo: Boolean = false): Long {
        val minimum = if (turbo) TURBO_MIN_EFFECTIVE_ACTION_TIMEOUT_MS else MIN_EFFECTIVE_ACTION_TIMEOUT_MS
        return (AutomationPolicy.clampActionTimeoutSeconds(configuredSeconds) * 1_000L)
            .coerceAtLeast(minimum)
    }

    fun loadingTimeoutMs(configuredSeconds: Int): Long =
        (AutomationPolicy.clampActionTimeoutSeconds(configuredSeconds) * 2_000L)
            .coerceIn(MIN_LOADING_TIMEOUT_MS, MAX_LOADING_TIMEOUT_MS)
}
