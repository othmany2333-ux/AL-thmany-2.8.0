package com.althmany.groupmanager.domain

/**
 * User-visible limits for the opt-in Accessibility-assisted workflow.
 * A session may queue a very large number of explicitly supplied invitation links. The queue is
 * disk-backed and may contain up to one million unique links while automation remains
 * user-visible and processes at most one thousand links per explicit run. Remaining links stay queued and
 * require another explicit user start. The workflow still stops immediately on WhatsApp restriction screens.
 */
object AutomationPolicy {
    const val MAX_LINKS_PER_SESSION = 1_000_000
    const val BATCH_SIZE = 1_000
    const val MAX_BATCHES_PER_SESSION = (MAX_LINKS_PER_SESSION + BATCH_SIZE - 1) / BATCH_SIZE

    // User controls the next-link pace directly. 0 means immediate transition after the
    // minimal UI-settle guard; larger values add an explicit pause between completed links.
    const val MIN_DELAY_SECONDS = 0
    const val MAX_DELAY_SECONDS = 60
    const val DEFAULT_DELAY_SECONDS = 0
    const val FAST_DELAY_SECONDS = 0

    // The unified speed control is stored in milliseconds so Personal/Work/Secure Folder all
    // honor the same sub-second handoff pace. The legacy seconds setting remains readable for
    // upgrades, but new UI/runtime work uses this value directly.
    const val MIN_INTER_LINK_DELAY_MS = 0
    const val MAX_INTER_LINK_DELAY_MS = 10_000
    const val DEFAULT_INTER_LINK_DELAY_MS = 0
    const val FAST_INTER_LINK_DELAY_MS = 0
    const val INTER_LINK_DELAY_STEP_MS = 100

    const val MIN_ACTION_TIMEOUT_SECONDS = 1
    const val MAX_ACTION_TIMEOUT_SECONDS = 60
    const val DEFAULT_ACTION_TIMEOUT_SECONDS = 8
    const val FAST_ACTION_TIMEOUT_SECONDS = 3

    const val SMART_START_COUNTDOWN_SECONDS = 1

    fun clampDelaySeconds(value: Int): Int =
        value.coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)

    fun clampInterLinkDelayMs(value: Int): Int =
        value.coerceIn(MIN_INTER_LINK_DELAY_MS, MAX_INTER_LINK_DELAY_MS)

    fun notificationDelaySeconds(delayMs: Int): Int =
        ((clampInterLinkDelayMs(delayMs) + 999) / 1_000)
            .coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)

    fun clampActionTimeoutSeconds(value: Int): Int =
        value.coerceIn(MIN_ACTION_TIMEOUT_SECONDS, MAX_ACTION_TIMEOUT_SECONDS)

    fun estimatedSessionSeconds(
        linkCount: Int,
        delaySeconds: Int,
        actionTimeoutSeconds: Int
    ): Int {
        val links = linkCount.coerceIn(0, MAX_LINKS_PER_SESSION)
        if (links == 0) return 0
        val gaps = (links - 1).coerceAtLeast(0)
        return links * clampActionTimeoutSeconds(actionTimeoutSeconds) +
            gaps * clampDelaySeconds(delaySeconds)
    }

    fun estimatedSessionSecondsFromMillis(
        linkCount: Int,
        delayMs: Int,
        actionTimeoutSeconds: Int
    ): Int {
        val links = linkCount.coerceIn(0, MAX_LINKS_PER_SESSION)
        if (links == 0) return 0
        val gaps = (links - 1).coerceAtLeast(0)
        val totalMs = links.toLong() * clampActionTimeoutSeconds(actionTimeoutSeconds) * 1_000L +
            gaps.toLong() * clampInterLinkDelayMs(delayMs)
        return ((totalMs + 999L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
