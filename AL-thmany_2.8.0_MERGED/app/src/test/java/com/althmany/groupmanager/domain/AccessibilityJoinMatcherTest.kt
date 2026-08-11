package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityJoinMatcherTest {
    @Test
    fun matchesExplicitArabicAndEnglishGroupActions() {
        assertEquals(
            AccessibilityJoinAction.PREVIEW,
            AccessibilityJoinMatcher.actionType("View group")
        )
        assertEquals(
            AccessibilityJoinAction.PREVIEW,
            AccessibilityJoinMatcher.actionType("عرض المجموعة")
        )
        assertEquals(
            AccessibilityJoinAction.JOIN,
            AccessibilityJoinMatcher.actionType("JOIN GROUP")
        )
        assertEquals(
            AccessibilityJoinAction.JOIN,
            AccessibilityJoinMatcher.actionType("الانضمام إلى المجموعة")
        )
        assertEquals(
            AccessibilityJoinAction.REQUEST,
            AccessibilityJoinMatcher.actionType("Request to join")
        )
        assertEquals(
            AccessibilityJoinAction.REQUEST,
            AccessibilityJoinMatcher.actionType("طلب الانضمام إلى المجموعة")
        )
        assertTrue(AccessibilityJoinMatcher.isJoinAction("  Join   chat "))
    }

    @Test
    fun recognizesArabicAndEnglishInvitationLoadingStates() {
        assertTrue(AccessibilityJoinMatcher.isLoading("جار التحميل..."))
        assertTrue(AccessibilityJoinMatcher.isLoading("Loading group info"))
        assertTrue(AccessibilityJoinMatcher.isLoading("Please wait"))
        assertFalse(AccessibilityJoinMatcher.isLoading("Join group"))
    }

    @Test
    fun homeDetectionUsesStrongSearchMarkersAndWeakTabsSeparately() {
        assertTrue(AccessibilityJoinMatcher.isWhatsAppHomeSurface("Ask Meta AI or Search"))
        assertTrue(AccessibilityJoinMatcher.isWhatsAppHomeSurface("اسأل Meta AI أو ابحث"))
        assertFalse(AccessibilityJoinMatcher.isWhatsAppHomeSurface("Chats"))
        assertTrue(AccessibilityJoinMatcher.isWhatsAppHomeTab("Chats"))
        assertTrue(AccessibilityJoinMatcher.isWhatsAppHomeTab("المكالمات"))
    }

    @Test
    fun rejectsGenericOrUnrelatedActions() {
        assertFalse(AccessibilityJoinMatcher.isJoinAction("Join"))
        assertFalse(AccessibilityJoinMatcher.isJoinAction("Send message"))
        assertFalse(AccessibilityJoinMatcher.isJoinAction(null))
    }

    @Test
    fun recognizesConfirmationControlsOnlyThroughDedicatedMatcher() {
        assertTrue(AccessibilityJoinMatcher.isConfirmation("Continue"))
        assertTrue(AccessibilityJoinMatcher.isConfirmation("تأكيد"))
        assertTrue(AccessibilityJoinMatcher.isConfirmation("com.whatsapp:id/confirm_button"))
        assertFalse(AccessibilityJoinMatcher.isConfirmation("Join group"))
    }

    @Test
    fun recognizesRequestSubmittedAndTerminalFailureStates() {
        assertTrue(AccessibilityJoinMatcher.isRequestSubmitted("Request sent"))
        assertTrue(AccessibilityJoinMatcher.isRequestSubmitted("تم إرسال طلب الانضمام"))
        assertTrue(AccessibilityJoinMatcher.isAlreadyMember("You are already in this group"))
        assertTrue(AccessibilityJoinMatcher.isAlreadyMember("أنت عضو بالفعل في هذه المجموعة"))
        assertTrue(AccessibilityJoinMatcher.isTerminalFailure("This group is full"))
        assertTrue(AccessibilityJoinMatcher.isTerminalFailure("تعذر الانضمام إلى المجموعة"))
        assertFalse(AccessibilityJoinMatcher.isTerminalFailure("Connecting…"))
        assertEquals(
            AccessibilityFailureType.GROUP_FULL,
            AccessibilityJoinMatcher.failureType("This group is full")
        )
        assertTrue(AccessibilityJoinMatcher.isRestricted("Try again later"))
    }
    @Test
    fun matchesCommunityPreviewJoinAndRequestActions() {
        assertEquals(
            AccessibilityJoinAction.PREVIEW,
            AccessibilityJoinMatcher.actionType("View community")
        )
        assertEquals(
            AccessibilityJoinAction.PREVIEW,
            AccessibilityJoinMatcher.actionType("عرض المجتمع")
        )
        assertEquals(
            AccessibilityJoinAction.JOIN,
            AccessibilityJoinMatcher.actionType("Join community")
        )
        assertEquals(
            AccessibilityJoinAction.JOIN,
            AccessibilityJoinMatcher.actionType("الانضمام إلى المجتمع")
        )
        assertEquals(
            AccessibilityJoinAction.REQUEST,
            AccessibilityJoinMatcher.actionType("Request to join community")
        )
        assertEquals(
            AccessibilityJoinAction.REQUEST,
            AccessibilityJoinMatcher.actionType("طلب الانضمام إلى المجتمع")
        )
        assertTrue(AccessibilityJoinMatcher.hasInviteContext("Community invite"))
        assertTrue(AccessibilityJoinMatcher.isAlreadyMember("You are already in this community"))
    }

    @Test
    fun blocksDestructiveOrCancelActions() {
        assertTrue(AccessibilityJoinMatcher.isBlockedAction("Cancel"))
        assertTrue(AccessibilityJoinMatcher.isBlockedAction("مغادرة المجموعة"))
        assertTrue(AccessibilityJoinMatcher.isBlockedAction("com.whatsapp:id/negative_button"))
        assertFalse(AccessibilityJoinMatcher.isBlockedAction("Join group"))
    }

    @Test
    fun recognizesOnlySafeInvitationCloseControls() {
        assertTrue(AccessibilityJoinMatcher.isSafeClose("Close"))
        assertTrue(AccessibilityJoinMatcher.isSafeClose("إغلاق"))
        assertTrue(AccessibilityJoinMatcher.isSafeClose("com.whatsapp:id/invite_close_button"))
        assertFalse(AccessibilityJoinMatcher.isSafeClose("Cancel request"))
        assertFalse(AccessibilityJoinMatcher.isSafeClose("مغادرة المجموعة"))
    }

    @Test
    fun recognizesRealArabicPendingInvalidRemovedAndFullScreens() {
        assertTrue(
            AccessibilityJoinMatcher.isRequestSubmitted(
                "تم إرسال الطلب وفي انتظار موافقة المشرف."
            )
        )
        assertFalse(
            AccessibilityJoinMatcher.isRequestSubmitted(
                "يجب أن يوافق أحد المشرفين على طلبك. إلغاء الطلب"
            )
        )
        assertTrue(
            AccessibilityJoinMatcher.isCancelRequest(
                "يجب أن يوافق أحد المشرفين على طلبك. إلغاء الطلب"
            )
        )
        assertEquals(
            AccessibilityFailureType.INVALID_OR_EXPIRED,
            AccessibilityJoinMatcher.failureType(
                "لا يمكنك الانضمام إلى هذه المجموعة لأنه تمت إعادة تعيين رابط الدعوة الخاص بها."
            )
        )
        assertEquals(
            AccessibilityFailureType.REMOVED_OR_BANNED,
            AccessibilityJoinMatcher.failureType(
                "لا يمكنك الانضمام إلى هذه المجموعة لأنه تم إزالتك."
            )
        )
        assertEquals(
            AccessibilityFailureType.GROUP_FULL,
            AccessibilityJoinMatcher.failureType("هذه المجموعة ممتلئة الآن")
        )
    }

    @Test
    fun screenshotRequestApprovalNoticeIsNotMistakenForSubmittedRequest() {
        assertTrue(
            AccessibilityJoinMatcher.isRequestApprovalNotice(
                "يجب أن يوافق أحد المشرفين على طلبك."
            )
        )
        assertFalse(
            AccessibilityJoinMatcher.isRequestSubmitted(
                "يجب أن يوافق أحد المشرفين على طلبك."
            )
        )
        assertEquals(
            AccessibilityJoinAction.REQUEST,
            AccessibilityJoinMatcher.actionType("طلب الانضمام")
        )
    }

    @Test
    fun screenshotCompactCommunityJoinLabelIsRecognized() {
        assertEquals(
            AccessibilityJoinAction.JOIN,
            AccessibilityJoinMatcher.actionType("انضمام للمجتمع")
        )
    }

    @Test
    fun cancelRequestIsBlockedButNotStrongSubmissionEvidence() {
        assertTrue(AccessibilityJoinMatcher.isCancelRequest("إلغاء الطلب"))
        assertTrue(AccessibilityJoinMatcher.isBlockedAction("إلغاء الطلب"))
        assertFalse(AccessibilityJoinMatcher.isRequestSubmitted("إلغاء الطلب"))
    }

    @Test
    fun precisionVariantsFromArabicScreensRemainSafe() {
        assertEquals(
            AccessibilityJoinAction.REQUEST,
            AccessibilityJoinMatcher.actionType("طلب انضمام")
        )
        assertEquals(
            AccessibilityFailureType.INVALID_OR_EXPIRED,
            AccessibilityJoinMatcher.failureType("رابط الدعوة لم يعد متاحا")
        )
        assertEquals(
            AccessibilityFailureType.GROUP_FULL,
            AccessibilityJoinMatcher.failureType("بلغت المجموعة الحد الأقصى للأعضاء")
        )
        assertFalse(
            AccessibilityJoinMatcher.isRequestSubmitted("يجب أن يوافق أحد المشرفين على طلبك")
        )
    }

    @Test
    fun destructiveResourceIdsCanNeverWinBecauseTheyContainJoinButton() {
        assertTrue(AccessibilityJoinMatcher.isBlockedAction("com.whatsapp:id/cancel_join_button"))
        assertEquals(null, AccessibilityJoinMatcher.actionType("com.whatsapp:id/cancel_join_button", inviteContext = true))
        assertTrue(AccessibilityJoinMatcher.isBlockedAction("Cancel join request"))
        assertFalse(AccessibilityJoinMatcher.isConfirmation("com.whatsapp:id/cancel_confirm_button"))
    }

    @Test
    fun normalizedArabicMatchingDoesNotDependOnHamzaVariantDuplication() {
        assertEquals(
            AccessibilityFailureType.INVALID_OR_EXPIRED,
            AccessibilityJoinMatcher.failureType("لَمْ يَعُدْ رَابِطُ الدَّعْوَةِ صَالِحًا")
        )
        assertTrue(AccessibilityJoinMatcher.isSafeClose("إغلاق الدعوة"))
        assertTrue(AccessibilityJoinMatcher.isSafeClose("Exit preview"))
    }

    @Test
    fun recognizesWhatsAppHomeSearchSurface() {
        assertTrue(AccessibilityJoinMatcher.isWhatsAppHomeSurface("اسأل Meta AI أو ابحث"))
        assertTrue(AccessibilityJoinMatcher.isWhatsAppHomeSurface("Ask Meta AI or Search"))
    }


    @Test
    fun recognizesConversationSurfacePieces() {
        assertTrue(AccessibilityJoinMatcher.isConversationComposer("اكتب رسالة"))
        assertTrue(AccessibilityJoinMatcher.isConversationComposer("Type a message"))
        assertTrue(AccessibilityJoinMatcher.isConversationAction("إرفاق"))
        assertTrue(AccessibilityJoinMatcher.isConversationAction("Voice message"))
        assertTrue(AccessibilityJoinMatcher.isConversationComposer("com.whatsapp:id/entry_text"))
        assertTrue(AccessibilityJoinMatcher.isConversationAction("com.whatsapp:id/voice_note_btn"))
        assertFalse(AccessibilityJoinMatcher.isConversationComposer("اسأل Meta AI أو ابحث"))
    }

    @Test
    fun recognizesCurrentRequestAndRestrictionVariants() {
        assertTrue(AccessibilityJoinMatcher.isRequestSubmitted("Request sent to admins"))
        assertTrue(AccessibilityJoinMatcher.isRequestSubmitted("لقد تم إرسال طلبك"))
        assertTrue(AccessibilityJoinMatcher.isRestricted("You've joined too many groups"))
        assertEquals(
            AccessibilityFailureType.INVALID_OR_EXPIRED,
            AccessibilityJoinMatcher.failureType("This group invite link is no longer valid")
        )
    }
    @Test
    fun precisionFusionRecognizesCurrentScreenshotVariants() {
        assertEquals(
            AccessibilityFailureType.REMOVED_OR_BANNED,
            AccessibilityJoinMatcher.failureType(
                "لا يمكنك الانضمام إلى هذه المجموعة حيث قد تمت إزالتك منها."
            )
        )
        assertEquals(
            AccessibilityFailureType.INVALID_OR_EXPIRED,
            AccessibilityJoinMatcher.failureType(
                "لا يمكنك الانضمام إلى هذه المجموعة لأنه تمت إعادة تعيين رابط الدعوة الخاص بها."
            )
        )
        assertTrue(AccessibilityJoinMatcher.isTerminalAcknowledgement("موافق"))
        assertFalse(AccessibilityJoinMatcher.isTerminalAcknowledgement("إلغاء الطلب"))
    }

    @Test
    fun precisionFusionDistinguishesGroupAndCommunityTargets() {
        assertEquals(
            AccessibilityInviteTarget.GROUP,
            AccessibilityJoinMatcher.targetType("الانضمام إلى المجموعة")
        )
        assertEquals(
            AccessibilityInviteTarget.COMMUNITY,
            AccessibilityJoinMatcher.targetType("انضمام للمجتمع")
        )
        assertEquals(
            AccessibilityInviteTarget.COMMUNITY,
            AccessibilityJoinMatcher.targetType("Request to join community")
        )
        assertEquals(AccessibilityInviteTarget.UNKNOWN, AccessibilityJoinMatcher.targetType("موافق"))
    }

    @Test
    fun aggregateTerminalMatcherSurvivesSplitResetDialog() {
        assertEquals(
            AccessibilityFailureType.INVALID_OR_EXPIRED,
            AccessibilityJoinMatcher.failureTypeAcross(
                sequenceOf(
                    "لا يمكنك الانضمام إلى هذه المجموعة لأنه تمت إعادة تعيين رابط",
                    "الدعوة الخاص بها.",
                    "موافق"
                )
            )
        )
    }

    @Test
    fun aggregateTerminalMatcherClassifiesSimilarJoinDenials() {
        assertEquals(
            AccessibilityFailureType.REMOVED_OR_BANNED,
            AccessibilityJoinMatcher.failureTypeAcross(
                sequenceOf("لا يمكنك الانضمام إلى هذه", "المجموعة لأنه تم حظرك منها", "موافق")
            )
        )
        assertEquals(
            AccessibilityFailureType.GROUP_FULL,
            AccessibilityJoinMatcher.failureTypeAcross(
                sequenceOf("تعذر الانضمام", "بلغت المجموعة الحد الأقصى", "للأعضاء", "OK")
            )
        )
        assertEquals(
            AccessibilityFailureType.GENERIC,
            AccessibilityJoinMatcher.failureTypeAcross(
                sequenceOf("لا يمكنك الانضمام", "إلى هذه المجموعة", "موافق")
            )
        )
    }

    @Test
    fun aggregateRequestMatcherSurvivesSplitPendingText() {
        assertTrue(
            AccessibilityJoinMatcher.isRequestSubmittedAcross(
                sequenceOf("تم إرسال", "طلب الانضمام", "وفي انتظار موافقة المشرف")
            )
        )
        assertFalse(
            AccessibilityJoinMatcher.isRequestSubmittedAcross(
                sequenceOf("يجب أن يوافق أحد المشرفين", "على طلبك", "طلب الانضمام")
            )
        )
    }

    @Test
    fun detectsTargetAcrossSplitCommunityLabels() {
        assertEquals(
            AccessibilityInviteTarget.COMMUNITY,
            AccessibilityJoinMatcher.targetTypeAcross(
                sequenceOf("دعوة", "عرض المجتمع", "انضمام للمجتمع")
            )
        )
        assertEquals(
            AccessibilityInviteTarget.GROUP,
            AccessibilityJoinMatcher.targetTypeAcross(
                sequenceOf("معلومات الدعوة", "الانضمام إلى المجموعة")
            )
        )
    }

}
