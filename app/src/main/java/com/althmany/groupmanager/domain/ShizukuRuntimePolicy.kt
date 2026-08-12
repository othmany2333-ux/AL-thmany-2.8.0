package com.althmany.groupmanager.domain

/**
 * Safety/performance policy for the Shizuku UI-dump backend.
 *
 * 2.5.2 adds an event-coalesced persistent UiAutomation frame path and direct AccessibilityNodeInfo
 * ACTION_CLICK inside the Shizuku UserService, while keeping the 2.4.3 direct-handoff path: a single scan may authorize a tap only when the
 * semantic node is exact-package, enabled/clickable, high-confidence and clearly separated from
 * the runner-up. Ambiguous actions, confirmations and community rows keep multi-scan consensus.
 */
object ShizukuRuntimePolicy {
    const val ACTION_CONSENSUS_SCANS = 2
    const val COMMUNITY_ROW_CONSENSUS_SCANS = 2
    const val MIN_ACTION_SCORE = 64
    const val MIN_SCORE_MARGIN = 10
    const val INPUT_COOLDOWN_MS = 32L
    const val CAPABILITY_RECHECK_MS = 12_000L
    const val MAX_CONSECUTIVE_DUMP_FAILURES = 4
    const val MAX_CONSECUTIVE_AMBIGUOUS_ACTIONS = 6
    const val MAX_CONSECUTIVE_INPUT_FAILURES = 3
    const val FAST_ACTION_SCORE = 76
    const val FAST_SCORE_MARGIN = 16
    const val FOREGROUND_LEASE_MS = 8_000L
    const val POST_ACTION_STABLE_SCANS = 1

    fun inputCooldownMs(fastUiActive: Boolean): Long =
        if (fastUiActive) ShizukuFastUiPolicy.CLICK_THROTTLE_MS else INPUT_COOLDOWN_MS

    fun isSafeTapBounds(
        bounds: ShizukuBounds,
        displayWidth: Int,
        displayHeight: Int
    ): Boolean {
        if (!bounds.valid || displayWidth <= 0 || displayHeight <= 0) return false
        if (bounds.left < 0 || bounds.top < 0 || bounds.right > displayWidth || bounds.bottom > displayHeight) return false
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width < 24 || height < 24) return false
        val area = width.toLong() * height.toLong()
        val screenArea = displayWidth.toLong() * displayHeight.toLong()
        if (screenArea <= 0L || area > (screenArea * 55L) / 100L) return false
        return bounds.centerX in 1 until displayWidth && bounds.centerY in 1 until displayHeight
    }

    fun isSafeSwipeBounds(
        bounds: ShizukuBounds,
        displayWidth: Int,
        displayHeight: Int
    ): Boolean {
        if (!bounds.valid || displayWidth <= 0 || displayHeight <= 0) return false
        if (bounds.left < 0 || bounds.top < 0 ||
            bounds.right > displayWidth || bounds.bottom > displayHeight
        ) return false
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width < 48 || height < 120) return false
        return bounds.centerX in 1 until displayWidth &&
            bounds.centerY in 1 until displayHeight
    }

    fun actionConsensusScans(
        score: Int,
        runnerUpScore: Int,
        clickable: Boolean,
        exactPackage: Boolean,
        ambiguous: Boolean
    ): Int {
        if (ambiguous || !clickable || !exactPackage || score < FAST_ACTION_SCORE) return ACTION_CONSENSUS_SCANS
        val margin = if (runnerUpScore == Int.MIN_VALUE) Int.MAX_VALUE else score - runnerUpScore
        return if (margin >= FAST_SCORE_MARGIN) 1 else ACTION_CONSENSUS_SCANS
    }

    fun consensusReached(stableScans: Int, requiredScans: Int = ACTION_CONSENSUS_SCANS): Boolean =
        stableScans >= requiredScans.coerceAtLeast(1)

    fun communityConsensusReached(stableScans: Int): Boolean =
        stableScans >= COMMUNITY_ROW_CONSENSUS_SCANS
}
