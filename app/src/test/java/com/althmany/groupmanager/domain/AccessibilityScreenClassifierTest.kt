package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityScreenClassifierTest {
    @Test
    fun terminalSignalsHavePriorityOverButtons() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("Join group", "This group is full")
        )

        assertEquals(AutomationScreenKind.GROUP_FULL, kind)
    }

    @Test
    fun recognizesArabicRequestAction() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("معلومات المجموعة", "طلب الانضمام إلى المجموعة")
        )

        assertEquals(AutomationScreenKind.REQUEST_ACTION, kind)
    }

    @Test
    fun loadingScreenWaitsInsteadOfBecomingUnknownOrAction() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("جار التحميل...", "إغلاق")
        )
        assertEquals(AutomationScreenKind.LOADING, kind)
    }

    @Test
    fun terminalErrorStillOutranksLoading() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("جار التحميل...", "هذه المجموعة ممتلئة")
        )
        assertEquals(AutomationScreenKind.GROUP_FULL, kind)
    }

    @Test
    fun recognizesRestrictionScreen() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("حاول مرة أخرى لاحقا")
        )

        assertEquals(AutomationScreenKind.RESTRICTED, kind)
    }

    @Test
    fun recognizesCommunityJoinAction() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("Community invite", "Join community")
        )

        assertEquals(AutomationScreenKind.JOIN_ACTION, kind)
    }

    @Test
    fun recognizesRemovedOrBannedScreen() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("لا يمكنك الانضمام إلى هذه المجموعة لأنه تم إزالتك.")
        )

        assertEquals(AutomationScreenKind.REMOVED_OR_BANNED, kind)
    }

    @Test
    fun recognizesPendingRequestWithoutPressingCancel() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("تم إرسال الطلب وفي انتظار موافقة المشرف.", "إلغاء الطلب")
        )

        assertEquals(AutomationScreenKind.REQUEST_SUBMITTED, kind)
    }

    @Test
    fun screenshotPreRequestAdminNoticeStillClicksRequestButton() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("يجب أن يوافق أحد المشرفين على طلبك.", "طلب الانضمام")
        )

        assertEquals(AutomationScreenKind.REQUEST_ACTION, kind)
    }

    @Test
    fun screenshotCommunityJoinVariantIsRecognized() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("المجتمع - 54 مجموعة", "انضمام للمجتمع")
        )

        assertEquals(AutomationScreenKind.JOIN_ACTION, kind)
    }

    @Test
    fun screenshotResetInviteHasPriorityOverOkayButton() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf(
                "لا يمكنك الانضمام إلى هذه المجموعة لأنه تمت إعادة تعيين رابط الدعوة الخاص بها.",
                "موافق"
            )
        )

        assertEquals(AutomationScreenKind.INVALID_OR_EXPIRED, kind)
    }

    @Test
    fun classificationDoesNotDependOnNodeOrderWhenErrorAndStaleSuccessCoexist() {
        val first = AccessibilityScreenClassifier.classify(
            sequenceOf("أنت عضو بالفعل في هذه المجموعة", "تمت إعادة تعيين رابط الدعوة")
        )
        val second = AccessibilityScreenClassifier.classify(
            sequenceOf("تمت إعادة تعيين رابط الدعوة", "أنت عضو بالفعل في هذه المجموعة")
        )
        assertEquals(AutomationScreenKind.INVALID_OR_EXPIRED, first)
        assertEquals(AutomationScreenKind.INVALID_OR_EXPIRED, second)
    }

    @Test
    fun destructiveJoinLikeIdIsNeverClassifiedAsJoinAction() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("com.whatsapp:id/cancel_join_button")
        )
        assertEquals(AutomationScreenKind.UNKNOWN, kind)
    }

    @Test
    fun cancelRequestAloneMeansPendingRequestButFreshRequestStillWins() {
        assertEquals(
            AutomationScreenKind.REQUEST_SUBMITTED,
            AccessibilityScreenClassifier.classify(sequenceOf("إلغاء الطلب"))
        )
        assertEquals(
            AutomationScreenKind.REQUEST_ACTION,
            AccessibilityScreenClassifier.classify(sequenceOf("يجب أن يوافق أحد المشرفين على طلبك", "طلب الانضمام", "إلغاء الطلب"))
        )
    }

    @Test
    fun splitTerminalSentenceStillAdvancesAsInvalid() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf(
                "لا يمكنك الانضمام إلى هذه المجموعة لأنه تمت إعادة تعيين رابط",
                "الدعوة الخاص بها.",
                "موافق"
            )
        )
        assertEquals(AutomationScreenKind.INVALID_OR_EXPIRED, kind)
    }

    @Test
    fun unknownButStrongJoinDenialBecomesGenericTerminalFailure() {
        val kind = AccessibilityScreenClassifier.classify(
            sequenceOf("لا يمكنك الانضمام", "إلى هذه المجموعة", "موافق")
        )
        assertEquals(AutomationScreenKind.GENERIC_FAILURE, kind)
    }

}
