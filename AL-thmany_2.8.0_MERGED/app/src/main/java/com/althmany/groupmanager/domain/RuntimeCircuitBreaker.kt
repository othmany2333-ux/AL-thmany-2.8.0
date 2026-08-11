package com.althmany.groupmanager.domain

/**
 * Stops a run when WhatsApp appears structurally incompatible for several consecutive links.
 * Known terminal results (full, reset, removed, request sent, already member) do not count.
 */
object RuntimeCircuitBreaker {
    const val MAX_CONSECUTIVE_RUNTIME_FAILURES = 6

    fun isRecoverableRuntimeFailure(resultName: String): Boolean = resultName in setOf(
        "UNKNOWN_SCREEN",
        "ACTION_TIMEOUT",
        "OPEN_FAILED",
        "BROWSER_FALLBACK"
    )

    fun nextCount(current: Int, resultName: String): Int =
        if (isRecoverableRuntimeFailure(resultName)) current + 1 else 0

    fun shouldTrip(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= MAX_CONSECUTIVE_RUNTIME_FAILURES
}
