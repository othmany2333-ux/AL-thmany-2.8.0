package com.althmany.groupmanager.domain

/**
 * Small timing policy for leaving a just-joined conversation before the next deep link.
 * The Back pulse is opportunistic only: continuity must never depend on Back succeeding.
 */
object ConversationFastExitPolicy {
    fun settleMs(turbo: Boolean): Long = if (turbo) 0L else 48L

    fun shouldAttemptBack(turbo: Boolean, loading: Boolean, restricted: Boolean): Boolean {
        @Suppress("UNUSED_VARIABLE") val fast = turbo
        return !loading && !restricted
    }
}
