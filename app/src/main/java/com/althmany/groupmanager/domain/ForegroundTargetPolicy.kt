package com.althmany.groupmanager.domain

/**
 * Guards the "pause when leaving WhatsApp" feature against transient Android windows.
 * A resolver, keyboard, system overlay, or one-frame package transition must not pause a run.
 */
object ForegroundTargetPolicy {
    const val OUTSIDE_TARGET_CONFIRM_MS = 140L
    const val RECENT_TARGET_GRACE_MS = 120L

    fun shouldPauseOutsideTarget(
        candidateAgeMs: Long,
        sinceTargetSeenMs: Long,
        stillOutsideTarget: Boolean
    ): Boolean = stillOutsideTarget &&
        candidateAgeMs >= OUTSIDE_TARGET_CONFIRM_MS &&
        sinceTargetSeenMs >= RECENT_TARGET_GRACE_MS
}
