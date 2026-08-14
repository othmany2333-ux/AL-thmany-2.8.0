package com.althmany.groupmanager.domain

/**
 * Event-first timing contract used only while the persistent Shizuku UiAutomation bridge is ACTIVE.
 *
 * These values intentionally do not modify AccessibilityService timing. The fast Shizuku path
 * reacts to UiAutomation accessibility events first and uses bounded polling only as liveness
 * fallback. Restriction/loading guards remain authoritative and are never bypassed.
 */
object ShizukuFastUiPolicy {
    const val EVENT_SCAN_MS = 12L
    const val STABLE_SCAN_MS = 30L
    const val FALLBACK_POLL_MS = 80L
    const val WATCHDOG_INTERVAL_MS = 32L

    const val CLICK_THROTTLE_MS = 60L
    const val GESTURE_DURATION_MS = 72L
    const val RESULT_ANALYSIS_FALLBACK_MS = 72L

    const val ACTION_RETRY_AFTER_MS = 95L
    const val MAX_ACTION_ATTEMPTS = 2
    const val POST_JOIN_MIN_EVIDENCE_MS = 30L
    const val POST_JOIN_STABLE_SCANS = 1

    const val NON_LOADING_WATCHDOG_MS = 1_000L
    const val MIN_STABLE_SCANS_FOR_WATCHDOG = 2
    const val UNKNOWN_TIMEOUT_MS = 2_000L
    const val LOADING_TIMEOUT_MS = 20_000L

    const val BACK_SETTLE_MS = 18L
    const val TERMINAL_ESCAPE_SETTLE_MS = 24L
    const val NORMAL_EXIT_SETTLE_MS = 36L
    const val USER_INSTANT_ADVANCE_SETTLE_MS = 0L
    const val EVENT_TREE_COALESCE_MS = 0L

    const val STABLE_SCANS_BEFORE_FALLBACK_POLL = 3
}
