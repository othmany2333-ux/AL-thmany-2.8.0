package com.althmany.groupmanager.domain

/**
 * Event-first runtime cadence for Accessibility work.
 *
 * Fast mode must be responsive without flooding Android with 4ms scans/gestures. Accessibility
 * events remain the primary trigger; the periodic poll is only a liveness fallback. When the same
 * screen stays stable, scans are intentionally relaxed to reduce CPU pressure and touch lag.
 */
object RuntimeCadencePolicy {
    const val FAST_FALLBACK_POLL_MS = 80L
    const val NORMAL_FALLBACK_POLL_MS = 360L

    const val FAST_EVENT_SCAN_MS = 12L
    const val FAST_STABLE_SCAN_MS = 30L
    const val NORMAL_EVENT_SCAN_MS = 90L
    const val NORMAL_STABLE_SCAN_MS = 180L
    const val STABLE_SCREEN_RELAX_AFTER_SCANS = 6

    const val FAST_CLICK_THROTTLE_MS = 60L
    const val NORMAL_CLICK_THROTTLE_MS = 320L
    const val FAST_GESTURE_DURATION_MS = 16L
    const val NORMAL_GESTURE_DURATION_MS = 45L
    const val FAST_RESULT_INFERENCE_MS = 72L
    const val NORMAL_RESULT_INFERENCE_MS = 420L
    const val FAST_EXIT_SETTLE_MS = 12L
    const val NORMAL_EXIT_SETTLE_MS = 90L
    const val FAST_TERMINAL_SETTLE_MS = 32L

    fun pollIntervalMs(fast: Boolean): Long =
        if (fast) FAST_FALLBACK_POLL_MS else NORMAL_FALLBACK_POLL_MS

    fun minScanIntervalMs(fast: Boolean, stableScreenScans: Int): Long = when {
        fast && stableScreenScans >= STABLE_SCREEN_RELAX_AFTER_SCANS -> FAST_STABLE_SCAN_MS
        fast -> FAST_EVENT_SCAN_MS
        stableScreenScans >= STABLE_SCREEN_RELAX_AFTER_SCANS -> NORMAL_STABLE_SCAN_MS
        else -> NORMAL_EVENT_SCAN_MS
    }

    fun clickThrottleMs(fast: Boolean): Long =
        if (fast) FAST_CLICK_THROTTLE_MS else NORMAL_CLICK_THROTTLE_MS

    fun gestureDurationMs(fast: Boolean): Long =
        if (fast) FAST_GESTURE_DURATION_MS else NORMAL_GESTURE_DURATION_MS

    fun resultInferenceDelayMs(fast: Boolean): Long =
        if (fast) FAST_RESULT_INFERENCE_MS else NORMAL_RESULT_INFERENCE_MS

    fun exitSettleMs(fast: Boolean): Long =
        if (fast) FAST_EXIT_SETTLE_MS else NORMAL_EXIT_SETTLE_MS
}
