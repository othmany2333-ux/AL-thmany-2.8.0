package com.althmany.groupmanager.domain

enum class CommunityTraversalStage {
    INACTIVE,
    ENTERING_COMMUNITY,
    DISCOVERING_GROUPS,
    OPENING_GROUP,
    PROCESSING_GROUP,
    RETURNING_TO_COMMUNITY,
    COMPLETE
}

/**
 * Bounded recovery policy for community traversal. The limits exist to prevent a changed
 * WhatsApp layout from producing infinite scroll/back loops. They do not bypass WhatsApp limits.
 */
object CommunityTraversalPolicy {
    const val MAX_GROUPS_PER_COMMUNITY = 512
    const val MAX_SCROLL_ATTEMPTS = 80
    const val MAX_RETURN_BACK_STEPS = 3
    const val COMMUNITY_HOME_STABLE_SCANS = 2
    const val EMPTY_VIEW_STABLE_SCANS = 3
    const val GROUP_OPEN_TIMEOUT_MS = 6_000L
    const val RETURN_TIMEOUT_MS = 4_500L

    fun canProcessMore(processedCount: Int): Boolean =
        processedCount < MAX_GROUPS_PER_COMMUNITY

    fun canScroll(scrollAttempts: Int): Boolean =
        scrollAttempts < MAX_SCROLL_ATTEMPTS

    fun shouldFinishEmptyView(
        stableEmptyScans: Int,
        canScroll: Boolean,
        scrollAttempts: Int
    ): Boolean = stableEmptyScans >= EMPTY_VIEW_STABLE_SCANS &&
        (!canScroll || scrollAttempts >= MAX_SCROLL_ATTEMPTS)
}
