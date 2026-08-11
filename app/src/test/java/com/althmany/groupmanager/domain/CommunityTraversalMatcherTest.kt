package com.althmany.groupmanager.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityTraversalMatcherTest {
    @Test
    fun detectsArabicAndEnglishCommunityHomeEvidence() {
        assertTrue(
            CommunityTraversalMatcher.isCommunityHomeAcross(
                sequenceOf("المجتمع", "الإعلانات", "عرض كل المجموعات")
            )
        )
        assertTrue(
            CommunityTraversalMatcher.isCommunityHomeAcross(
                sequenceOf("Community", "Announcements", "Groups in this community")
            )
        )
        assertFalse(
            CommunityTraversalMatcher.isCommunityHomeAcross(
                sequenceOf("Chats", "Calls", "Updates")
            )
        )
    }

    @Test
    fun acceptsSemanticSubgroupRowsInsideVerifiedCommunitySurface() {
        assertTrue(
            CommunityTraversalMatcher.looksLikeGroupRow(
                text = "طلاب الجامعة",
                description = null,
                viewId = "com.whatsapp:id/conversation_contact_name",
                className = "android.widget.TextView",
                clickable = true
            )
        )
        assertTrue(
            CommunityTraversalMatcher.looksLikeGroupRow(
                text = "Study room",
                description = "Open group",
                viewId = "com.whatsapp:id/community_group_row",
                className = "android.widget.TextView",
                clickable = true
            )
        )
    }

    @Test
    fun rejectsAnnouncementsAndManagementOrDestructiveControls() {
        assertFalse(
            CommunityTraversalMatcher.looksLikeGroupRow(
                text = "الإعلانات",
                description = "فتح المجموعة",
                viewId = "com.whatsapp:id/community_group_row",
                className = "android.widget.TextView",
                clickable = true
            )
        )
        assertFalse(
            CommunityTraversalMatcher.looksLikeGroupRow(
                text = "إضافة مجموعة",
                description = "إدارة المجموعات",
                viewId = "com.whatsapp:id/community_group_row",
                className = "android.widget.TextView",
                clickable = true
            )
        )
        assertFalse(
            CommunityTraversalMatcher.looksLikeGroupRow(
                text = "Report",
                description = "Delete",
                viewId = "com.whatsapp:id/community_group_row",
                className = "android.widget.TextView",
                clickable = true
            )
        )
    }

    @Test
    fun rejectsHeadersEvenWhenTheyUseGenericNameIds() {
        assertFalse(
            CommunityTraversalMatcher.looksLikeGroupRow(
                text = "عرض كل المجموعات",
                description = null,
                viewId = "com.whatsapp:id/conversation_contact_name",
                className = "android.widget.TextView",
                clickable = true
            )
        )
    }
}
