package com.althmany.groupmanager.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuActivityProofPolicyTest {
    @Test
    fun acceptsOnlyExactUserConversationActivity() {
        val line = "topResumedActivity=ActivityRecord{42 u10 com.whatsapp/.Conversation t99}"
        assertNotNull(
            ShizukuActivityProofPolicy.findJoinedConversationProof(
                sequenceOf(line),
                "com.whatsapp",
                10
            )
        )
        assertNull(
            ShizukuActivityProofPolicy.findJoinedConversationProof(
                sequenceOf(line),
                "com.whatsapp",
                0
            )
        )
    }

    @Test
    fun rejectsHomeOrWrongPackageActivities() {
        assertNull(
            ShizukuActivityProofPolicy.findJoinedConversationProof(
                sequenceOf("mResumedActivity: ActivityRecord{1 u10 com.whatsapp/.HomeActivity}"),
                "com.whatsapp",
                10
            )
        )
        assertNull(
            ShizukuActivityProofPolicy.findJoinedConversationProof(
                sequenceOf("mResumedActivity: ActivityRecord{1 u10 com.whatsapp.w4b/.Conversation}"),
                "com.whatsapp",
                10
            )
        )
        assertTrue(
            ShizukuActivityProofPolicy.containsExactPackage(
                "u10 com.whatsapp/.Conversation",
                "com.whatsapp"
            )
        )
        assertFalse(
            ShizukuActivityProofPolicy.containsExactPackage(
                "u10 com.whatsapp.w4b/.Conversation",
                "com.whatsapp"
            )
        )
    }
}
