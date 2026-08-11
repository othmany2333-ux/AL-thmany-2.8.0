package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityTraversalPolicyTest {
    @Test
    fun subgroupAndScrollBudgetsAreBounded() {
        assertTrue(CommunityTraversalPolicy.canProcessMore(0))
        assertTrue(CommunityTraversalPolicy.canProcessMore(CommunityTraversalPolicy.MAX_GROUPS_PER_COMMUNITY - 1))
        assertFalse(CommunityTraversalPolicy.canProcessMore(CommunityTraversalPolicy.MAX_GROUPS_PER_COMMUNITY))

        assertTrue(CommunityTraversalPolicy.canScroll(0))
        assertFalse(CommunityTraversalPolicy.canScroll(CommunityTraversalPolicy.MAX_SCROLL_ATTEMPTS))
    }

    @Test
    fun emptyCommunityViewRequiresRepeatedEvidence() {
        assertFalse(
            CommunityTraversalPolicy.shouldFinishEmptyView(
                stableEmptyScans = CommunityTraversalPolicy.EMPTY_VIEW_STABLE_SCANS - 1,
                canScroll = false,
                scrollAttempts = 0
            )
        )
        assertTrue(
            CommunityTraversalPolicy.shouldFinishEmptyView(
                stableEmptyScans = CommunityTraversalPolicy.EMPTY_VIEW_STABLE_SCANS,
                canScroll = false,
                scrollAttempts = 0
            )
        )
    }
}
