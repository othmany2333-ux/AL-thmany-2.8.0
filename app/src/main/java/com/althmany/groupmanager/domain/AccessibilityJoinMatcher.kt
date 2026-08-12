package com.althmany.groupmanager.domain

import java.util.Locale

enum class AccessibilityJoinAction {
    PREVIEW,
    JOIN,
    REQUEST,
    CONFIRM
}

/** Semantic target for an invitation control. The executor still uses the same safe JOIN/REQUEST
 * actions, but diagnostics and scoring can distinguish group invitations from communities. */
enum class AccessibilityInviteTarget {
    GROUP,
    COMMUNITY,
    UNKNOWN
}

enum class AccessibilityFailureType {
    GROUP_FULL,
    INVALID_OR_EXPIRED,
    REMOVED_OR_BANNED,
    GENERIC
}

/**
 * Conservative matcher for WhatsApp group and community invitation screens.
 *
 * The matcher only accepts explicit invitation controls. Destructive controls such as
 * cancel-request, leave, report and delete are always blocked. Terminal states are matched
 * by fragments because WhatsApp frequently adds punctuation or group-specific wording.
 */
object AccessibilityJoinMatcher {
    private val previewLabels = normalizedSetOf(
        "view group", "view group info", "preview group", "group info", "see group", "open group info",
        "view community", "view community info", "preview community", "community info", "see community",
        "عرض المجموعة", "عرض معلومات المجموعة", "معاينة المجموعة", "معلومات المجموعة", "مشاهدة المجموعة", "فتح معلومات المجموعة",
        "عرض المجتمع", "عرض معلومات المجتمع", "معاينة المجتمع", "معلومات المجتمع", "مشاهدة المجتمع", "فتح معلومات المجتمع"
    )

    private val joinLabels = normalizedSetOf(
        "join group", "join the group", "join group chat", "join chat", "join this chat", "join", "confirm join", "join this group", "join now",
        "join community", "join this community", "join the community", "join community now",
        "الانضمام إلى المجموعة", "الانضمام الى المجموعة", "انضمام إلى المجموعة", "انضمام الى المجموعة",
        "الانضمام للمجموعة", "انضم للمجموعة", "الانضمام إلى القروب", "الانضمام الى القروب", "انضمام للقروب", "انضم للقروب", "انضم إلى هذه المجموعة", "انضم الى هذه المجموعة",
        "الانضمام إلى المجتمع", "الانضمام الى المجتمع", "انضمام إلى المجتمع", "انضمام الى المجتمع",
        "الانضمام للمجتمع", "انضمام للمجتمع", "انضم للمجتمع", "انضم إلى هذا المجتمع", "انضم الى هذا المجتمع",
        "انضم الآن", "انضم الان", "انضمام", "انضم"
    )

    private val confirmationLabels = normalizedSetOf(
        "continue", "confirm", "ok", "okay", "yes", "proceed", "confirm join", "join",
        "متابعة", "تأكيد", "تاكيد", "موافق", "نعم", "استمرار", "تأكيد الانضمام", "تاكيد الانضمام"
    )

    private val requestLabels = normalizedSetOf(
        "request to join", "request to join group", "ask to join", "ask to join group", "send join request",
        "request to join community", "ask to join community", "send request", "submit request", "request access",
        "طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام الى المجموعة",
        "طلب الانضمام إلى المجتمع", "طلب الانضمام الى المجتمع", "طلب الانضمام إلى هذه المجموعة", "طلب الانضمام الى هذه المجموعة", "اطلب الانضمام", "طلب انضمام",
        "إرسال طلب الانضمام", "ارسال طلب الانضمام", "إرسال الطلب", "ارسال الطلب"
    )

    private val inviteContextFragments = normalizedSetOf(
        "group invite", "invite link", "whatsapp group", "group chat", "chat.whatsapp.com",
        "community invite", "whatsapp community", "community announcement", "community chat",
        "دعوة المجموعة", "رابط الدعوة", "مجموعة واتساب", "الدردشة الجماعية",
        "دعوة المجتمع", "مجتمع واتساب", "إعلانات المجتمع", "اعلانات المجتمع", "دردشة المجتمع"
    )

    // Strong evidence that a request was actually submitted. Do not put pre-request
    // explanatory text here (for example: "an admin must approve your request"), because
    // WhatsApp shows that text before the user presses Request to join.
    private val requestSubmittedFragments = normalizedSetOf(
        "request sent", "join request sent", "request sent to admins", "request pending", "join request pending", "request is pending", "pending approval",
        "community join request sent", "community request pending", "awaiting admin approval",
        "تم إرسال الطلب", "تم ارسال الطلب", "تم إرسال طلب الانضمام", "تم ارسال طلب الانضمام",
        "تم إرسال الطلب وفي انتظار موافقة المشرف", "تم ارسال الطلب وفي انتظار موافقة المشرف",
        "تم إرسال الطلب و في انتظار موافقة المشرف", "تم ارسال الطلب و في انتظار موافقة المشرف",
        "لقد تم إرسال طلبك", "لقد تم ارسال طلبك", "تم تقديم طلب الانضمام",
        "طلبك قيد المراجعة", "الطلب قيد المراجعة", "طلب الانضمام قيد الانتظار", "بانتظار الموافقة", "انتظار موافقة المشرف"
    )

    private val cancelRequestLabels = normalizedSetOf(
        "cancel request", "withdraw request", "cancel join request",
        "إلغاء الطلب", "الغاء الطلب", "إلغاء طلب الانضمام", "الغاء طلب الانضمام"
    )

    private val genericDialogCancelLabels = normalizedSetOf(
        "cancel", "dismiss", "close dialog",
        "إلغاء", "الغاء", "إغلاق", "اغلاق"
    )

    private val requestApprovalNoticeFragments = normalizedSetOf(
        "an admin must approve your request", "an admin needs to approve your request",
        "one of the admins must approve your request", "your request needs admin approval",
        "يجب أن يوافق أحد المشرفين على طلبك", "يجب ان يوافق احد المشرفين على طلبك",
        "يجب موافقة أحد المشرفين على طلبك", "يجب موافقة احد المشرفين على طلبك"
    )

    private val alreadyMemberFragments = normalizedSetOf(
        "you are already in this group", "you're already in this group", "you’re already in this group",
        "already a participant", "already a member", "open group",
        "you are already in this community", "you're already in this community", "already in this community", "open community",
        "أنت بالفعل في هذه المجموعة", "انت بالفعل في هذه المجموعة",
        "أنت عضو بالفعل في هذه المجموعة", "انت عضو بالفعل في هذه المجموعة",
        "أنت مشارك بالفعل", "انت مشارك بالفعل", "فتح المجموعة",
        "أنت بالفعل في هذا المجتمع", "انت بالفعل في هذا المجتمع",
        "أنت عضو بالفعل في هذا المجتمع", "انت عضو بالفعل في هذا المجتمع", "فتح المجتمع"
    )

    private val groupFullFragments = normalizedSetOf(
        "this group is full", "group is full", "group full", "group has reached its limit", "can't join this group because it is full", "cannot join this group because it is full",
        "this community is full", "community is full", "community has reached its limit",
        "المجموعة ممتلئة", "هذه المجموعة ممتلئة", "لا يمكنك الانضمام إلى هذه المجموعة لأنها ممتلئة", "لا يمكنك الانضمام الى هذه المجموعة لانها ممتلئة", "اكتمل عدد أعضاء المجموعة", "اكتمل عدد اعضاء المجموعة",
        "المجتمع ممتلئ", "هذا المجتمع ممتلئ", "بلغت المجموعة الحد الاقصى للاعضاء", "بلغت المجموعة الحد الأقصى للأعضاء"
    )

    private val invalidFragments = normalizedSetOf(
        "this invite link was reset", "invite link was reset", "this invite link has expired",
        "this group invite link is no longer valid", "this community invite link is no longer valid",
        "invalid invite link", "this invite link is invalid", "invite link expired",
        "invite is no longer available", "this invitation is no longer available",
        "this invite is no longer valid", "this invite link is no longer valid", "invite link is no longer valid", "invite link is unavailable",
        "cannot join this group because the invite link was reset",
        "can't join this group because the invite link was reset",
        "رابط الدعوة غير صالح", "تمت إعادة تعيين رابط الدعوة", "تمت اعادة تعيين رابط الدعوة",
        "انتهت صلاحية رابط الدعوة", "لم تعد هذه الدعوة متاحة", "تعذر استخدام رابط الدعوة", "رابط الدعوة لم يعد متاحا",
        "لم يعد رابط الدعوة صالحا", "لم يعد رابط الدعوة هذا صالحا", "رابط الدعوة منتهي", "هذه الدعوة غير صالحة",
        "لا يمكنك الانضمام إلى هذه المجموعة لأنه تمت إعادة تعيين رابط الدعوة",
        "لا يمكنك الانضمام الى هذه المجموعة لانه تمت اعادة تعيين رابط الدعوة",
        "لا يمكنك الانضمام إلى هذه المجموعة لأنه تمت إعادة تعيين رابط الدعوة الخاص بها",
        "لا يمكنك الانضمام الى هذه المجموعة لانه تمت اعادة تعيين رابط الدعوة الخاص بها",
        "لا يمكنك الانضمام إلى هذا القروب لأنه تمت إعادة تعيين رابط الدعوة",
        "لا يمكنك الانضمام الى هذا القروب لانه تمت اعادة تعيين رابط الدعوة",
        "لا يمكنك الانضمام إلى هذه المجموعة لأن رابط الدعوة لم يعد صالحا",
        "لا يمكنك الانضمام الى هذه المجموعة لان رابط الدعوة لم يعد صالحا",
        "لا يمكنك الانضمام إلى هذه المجموعة لأن رابط الدعوة لم يعد متاحا",
        "لا يمكنك الانضمام الى هذه المجموعة لان رابط الدعوة لم يعد متاحا",
        "لم تعد المجموعة موجودة", "هذه المجموعة لم تعد موجودة",
        "لم يعد المجتمع موجودا", "هذا المجتمع لم يعد موجودا",
        "invite link revoked", "this invite link has been revoked",
        "group no longer exists", "community no longer exists"
    )

    private val removedOrBannedFragments = normalizedSetOf(
        "you were removed from this group", "you have been removed from this group",
        "you can't join this group because you were removed", "you cannot join this group because you were removed",
        "you were removed from this community", "you cannot join this community because you were removed",
        "تمت إزالتك من هذه المجموعة", "تمت ازالتك من هذه المجموعة",
        "تم إزالتك من هذه المجموعة", "تم ازالتك من هذه المجموعة",
        "لا يمكنك الانضمام إلى هذه المجموعة لأنه تم إزالتك",
        "لا يمكنك الانضمام الى هذه المجموعة لانه تم ازالتك",
        "لا يمكنك الانضمام إلى هذه المجموعة حيث قد تمت إزالتك منها",
        "لا يمكنك الانضمام الى هذه المجموعة حيث قد تمت ازالتك منها",
        "لا يمكنك الانضمام إلى هذه المجموعة حيث تمت إزالتك منها",
        "لا يمكنك الانضمام الى هذه المجموعة حيث تمت ازالتك منها",
        "لا يمكنك الانضمام إلى هذا القروب لأنه تم إزالتك",
        "لا يمكنك الانضمام الى هذا القروب لانه تم ازالتك",
        "تمت إزالتك من هذا المجتمع", "تمت ازالتك من هذا المجتمع",
        "تمت إزالتك من المجموعة", "تمت ازالتك من المجموعة",
        "تمت إزالتك من المجتمع", "تمت ازالتك من المجتمع",
        "تم حظرك من هذه المجموعة", "تم حظرك من المجموعة",
        "تم حظرك من هذا المجتمع", "تم حظرك من المجتمع",
        "لقد تم حظرك من هذه المجموعة", "لقد تم حظرك من هذا المجتمع",
        "you were banned from this group", "you have been banned from this group",
        "you were banned from this community", "you have been banned from this community"
    )

    private val genericFailureFragments = normalizedSetOf(
        "couldn't join group", "could not join group", "unable to join group",
        "couldn't join community", "could not join community", "unable to join community",
        "couldn't load group info", "could not load group info", "unable to load group info",
        "تعذر الانضمام إلى المجموعة", "تعذر الانضمام الى المجموعة",
        "لا يمكن الانضمام إلى المجموعة", "لا يمكن الانضمام الى المجموعة",
        "تعذر الانضمام إلى المجتمع", "تعذر الانضمام الى المجتمع",
        "لا يمكن الانضمام إلى المجتمع", "لا يمكن الانضمام الى المجتمع",
        "تعذر تحميل معلومات المجموعة", "تعذر تحميل معلومات المجتمع",
        "لا يمكنك الانضمام إلى هذه المجموعة", "لا يمكنك الانضمام الى هذه المجموعة",
        "لا يمكنك الانضمام إلى هذا القروب", "لا يمكنك الانضمام الى هذا القروب",
        "لا يمكنك الانضمام إلى هذا المجتمع", "لا يمكنك الانضمام الى هذا المجتمع",
        "cannot join this group", "can't join this group",
        "cannot join this community", "can't join this community"
    )

    private val blockedActionLabels = normalizedSetOf(
        "cancel", "cancel request", "cancel join request", "withdraw request", "not now", "back", "close", "dismiss", "leave group",
        "leave community", "exit group", "exit community", "report", "delete", "remove", "block",
        "إلغاء", "الغاء", "إلغاء الطلب", "الغاء الطلب", "إلغاء طلب الانضمام", "الغاء طلب الانضمام",
        "ليس الآن", "ليس الان", "رجوع", "إغلاق", "اغلاق", "مغادرة المجموعة", "مغادرة المجتمع",
        "الخروج من المجموعة", "الخروج من المجتمع", "إبلاغ", "ابلاغ", "حذف", "إزالة", "ازالة", "حظر"
    )

    // Resource IDs and accessibility descriptions are not always exact labels. Any destructive
    // fragment wins before join/request matching so IDs such as cancel_join_button can never be
    // mistaken for a positive join action merely because they contain join_button.
    private val destructiveFragments = normalizedSetOf(
        "cancel", "withdraw", "leave_", "leave ", "exit_", "exit ", "report", "delete", "remove", "block",
        "الغاء", "إلغاء", "مغادرة", "الخروج", "ابلاغ", "إبلاغ", "حذف", "ازالة", "إزالة", "حظر"
    )

    private val safeCloseLabels = normalizedSetOf(
        "close", "dismiss", "close window", "close preview", "close invite", "exit preview",
        "إغلاق", "اغلاق", "إغلاق النافذة", "اغلاق النافذة",
        "إغلاق المعاينة", "اغلاق المعاينة", "إغلاق الدعوة", "اغلاق الدعوة"
    )

    // Safe acknowledgement controls used only AFTER a terminal result has already been recorded.
    // Keeping this separate from normal confirmation prevents an error-dialog “OK/موافق” button
    // from competing with Join/Request during classification.
    private val terminalAcknowledgementLabels = normalizedSetOf(
        "ok", "okay", "got it", "done",
        "موافق", "حسنا", "حسنًا", "تم"
    )

    private val restrictionFragments = normalizedSetOf(
        "try again later", "temporarily unavailable", "you can't join groups right now", "you have reached the limit for joining groups",
        "you cannot join groups right now", "you can't join communities right now",
        "you have joined too many groups", "you've joined too many groups", "you joined too many groups recently",
        "too many attempts", "حاول مرة أخرى لاحقا", "حاول مرة اخرى لاحقا",
        "غير متاح مؤقتا", "لا يمكنك الانضمام إلى مجموعات الآن", "لا يمكنك الانضمام الى مجموعات الآن", "وصلت إلى الحد الأقصى للانضمام إلى المجموعات", "وصلت الى الحد الاقصى للانضمام الى المجموعات",
        "لا يمكنك الانضمام إلى مجتمعات الآن", "لا يمكنك الانضمام الى مجتمعات الآن",
        "انضممت إلى مجموعات كثيرة", "انضممت الى مجموعات كثيرة", "لقد انضممت إلى عدد كبير من المجموعات", "محاولات كثيرة جدا"
    )

    private val loadingFragments = normalizedSetOf(
        "loading", "loading group", "loading group info", "loading community",
        "loading community info", "loading invitation", "loading invite", "please wait",
        "getting group info", "getting community info", "fetching group info",
        "جار التحميل", "جاري التحميل", "جارٍ التحميل", "جاري تحميل معلومات المجموعة", "جاري تحميل معلومات المجتمع", "يرجى الانتظار",
        "تحميل معلومات المجموعة", "تحميل معلومات المجتمع", "تحميل الدعوة"
    )

    // Strong home/search markers. Generic labels such as "Chats" alone are intentionally
    // excluded because they may also be exposed while a conversation is open.
    private val whatsappHomeStrongFragments = normalizedSetOf(
        "ask meta ai or search", "search or ask meta ai", "ask meta ai",
        "search chats", "search conversations",
        "اسأل meta ai أو ابحث", "اسال meta ai او ابحث",
        "ابحث أو اسأل meta ai", "ابحث او اسال meta ai",
        "البحث في الدردشات", "ابحث في الدردشات"
    )

    // Tab labels are weak evidence individually. The Accessibility adapter requires at least
    // two of them before it treats the normal WhatsApp navigation surface as home.
    private val whatsappHomeTabLabels = normalizedSetOf(
        "chats", "updates", "communities", "calls",
        "الدردشات", "التحديثات", "المجتمعات", "المكالمات"
    )

    // Strong conversation evidence used only after a real Join action. Requiring both a
    // composer and a secondary chat action prevents a chat-list row from being mistaken for
    // an opened conversation.
    private val conversationComposerFragments = normalizedSetOf(
        "type a message", "compose a message", "write a message", "message…",
        "اكتب رسالة", "اكتب رساله", "كتابة رسالة", "كتابه رساله"
    )

    private val conversationActionFragments = normalizedSetOf(
        "attach", "attachment", "emoji", "voice message", "record voice message",
        "video call", "voice call", "إرفاق", "ارفاق", "مرفق", "رمز تعبيري",
        "رسالة صوتية", "رساله صوتيه", "تسجيل رسالة صوتية", "تسجيل رساله صوتيه",
        "مكالمة فيديو", "مكالمه فيديو", "مكالمة صوتية", "مكالمه صوتيه"
    )

    fun actionType(label: CharSequence?, inviteContext: Boolean = false): AccessibilityJoinAction? {
        val value = normalize(label)
        if (value.isEmpty() || isDestructiveNormalized(value)) return null
        return when {
            value in previewLabels || idLooksLike(
                value,
                "view_group", "group_preview", "invite_preview", "invite_view_button",
                "view_community", "community_preview", "community_info"
            ) -> AccessibilityJoinAction.PREVIEW

            value in requestLabels || idLooksLike(
                value,
                "request_join", "request_to_join", "join_request", "send_join_request", "request_community", "community_request"
            ) -> AccessibilityJoinAction.REQUEST

            value in joinLabels && (inviteContext || value !in GENERIC_JOIN_LABELS) ->
                AccessibilityJoinAction.JOIN

            idLooksLike(
                value,
                "join_group", "group_join", "join_button", "join_group_button", "group_join_button",
                "join_community", "community_join", "community_join_button"
            ) -> AccessibilityJoinAction.JOIN

            else -> null
        }
    }

    fun targetType(label: CharSequence?): AccessibilityInviteTarget {
        val value = normalize(label)
        if (value.isEmpty()) return AccessibilityInviteTarget.UNKNOWN
        return when {
            value.contains("community") || value.contains("مجتمع") -> AccessibilityInviteTarget.COMMUNITY
            value.contains("group") || value.contains("chat") || value.contains("مجموعة") || value.contains("قروب") -> AccessibilityInviteTarget.GROUP
            else -> AccessibilityInviteTarget.UNKNOWN
        }
    }

    fun targetTypeAcross(labels: Sequence<CharSequence?>): AccessibilityInviteTarget {
        var groupSeen = false
        for (label in labels) {
            when (targetType(label)) {
                AccessibilityInviteTarget.COMMUNITY -> return AccessibilityInviteTarget.COMMUNITY
                AccessibilityInviteTarget.GROUP -> groupSeen = true
                AccessibilityInviteTarget.UNKNOWN -> Unit
            }
        }
        return if (groupSeen) AccessibilityInviteTarget.GROUP else AccessibilityInviteTarget.UNKNOWN
    }

    /** Safe acknowledgement matcher. The service may use this only after a terminal result is saved. */
    fun isTerminalAcknowledgement(label: CharSequence?): Boolean {
        val value = normalize(label)
        if (value.isEmpty() || isDestructiveNormalized(value)) return false
        return value in terminalAcknowledgementLabels || idLooksLike(
            value,
            "positive_button", "ok_button", "acknowledge_button",
            "button_positive", "android:id/button1", "button1"
        )
    }

    fun isBlockedAction(label: CharSequence?): Boolean {
        val value = normalize(label)
        if (value.isEmpty()) return false
        return value in blockedActionLabels ||
            containsAny(value, cancelRequestLabels) ||
            isDestructiveNormalized(value) ||
            idLooksLike(
                value,
                "negative_button", "dismiss_button", "close_button",
                "leave_group", "leave_community", "exit_group", "exit_community", "report_button"
            )
    }

    /** Strict matcher used only after a result has been recorded. */
    fun isSafeClose(label: CharSequence?): Boolean {
        val value = normalize(label)
        if (value.isEmpty()) return false
        if (value in safeCloseLabels) return true
        if (isDestructiveNormalized(value)) return false
        return idLooksLike(
            value,
            "close_button", "dismiss_button", "toolbar_close",
            "invite_close", "preview_close", "dialog_close"
        )
    }

    fun isConfirmation(label: CharSequence?): Boolean {
        val value = normalize(label)
        if (value.isEmpty() || isDestructiveNormalized(value)) return false
        return value in confirmationLabels || idLooksLike(
            value,
            "confirm_button", "positive_button", "continue_button", "join_confirm",
            "community_confirm", "confirm_join"
        )
    }

    fun isJoinAction(label: CharSequence?, inviteContext: Boolean = false): Boolean {
        val action = actionType(label, inviteContext)
        return action == AccessibilityJoinAction.JOIN || action == AccessibilityJoinAction.REQUEST
    }

    fun isTerminalFailure(label: CharSequence?): Boolean =
        failureType(label) != null || isRestricted(label)

    fun hasInviteContext(label: CharSequence?): Boolean {
        val value = normalize(label)
        return containsAny(value, inviteContextFragments) ||
            value.contains("group_invite") ||
            value.contains("community_invite") ||
            value.contains("invite_link")
    }

    fun isRequestSubmitted(label: CharSequence?): Boolean =
        containsAny(normalize(label), requestSubmittedFragments)

    fun isSafeDialogCancel(value: CharSequence?): Boolean {
        val normalized = normalize(value)
        if (normalized.isBlank()) return false
        if (cancelRequestLabels.any { normalized == it || normalized.contains(it) }) return false
        return genericDialogCancelLabels.any { normalized == it }
    }

    fun isCancelRequest(label: CharSequence?): Boolean {
        val value = normalize(label)
        return containsAny(value, cancelRequestLabels) || idLooksLike(value, "cancel_request", "withdraw_request")
    }

    /** Informational text shown before Request to join; never proof that a request was sent. */
    fun isRequestApprovalNotice(label: CharSequence?): Boolean =
        containsAny(normalize(label), requestApprovalNoticeFragments)

    fun isAlreadyMember(label: CharSequence?): Boolean =
        containsAny(normalize(label), alreadyMemberFragments)

    fun failureType(label: CharSequence?): AccessibilityFailureType? {
        val value = normalize(label)
        return when {
            containsAny(value, groupFullFragments) -> AccessibilityFailureType.GROUP_FULL
            containsAny(value, invalidFragments) -> AccessibilityFailureType.INVALID_OR_EXPIRED
            containsAny(value, removedOrBannedFragments) -> AccessibilityFailureType.REMOVED_OR_BANNED
            containsAny(value, genericFailureFragments) -> AccessibilityFailureType.GENERIC
            else -> null
        }
    }

    /**
     * Screen-level terminal matcher for WhatsApp variants that split one error sentence across
     * multiple accessibility nodes. The normal per-node matcher remains the first line of defense;
     * this aggregate pass then joins the visible semantic labels and applies order-independent
     * structural clues so a terminal dialog can never strand the queue on a "موافق/OK" screen.
     */
    fun failureTypeAcross(labels: Sequence<CharSequence?>): AccessibilityFailureType? {
        val values = labels.map(::normalize).filter(String::isNotEmpty).toList()
        var best: AccessibilityFailureType? = null
        values.forEach { value ->
            val found = failureType(value)
            if (failureRank(found) > failureRank(best)) best = found
        }
        if (best != null && best != AccessibilityFailureType.GENERIC) return best

        val corpus = values.joinToString(" ")
        if (corpus.isEmpty()) return best

        val groupOrCommunity = containsAnyFragment(
            corpus,
            "group", "community", "مجموعة", "قروب", "مجتمع"
        )
        val joinDenial = containsAnyFragment(
            corpus,
            "cannot join", "can't join", "could not join", "unable to join",
            "لا يمكنك الانضمام", "لا يمكن الانضمام", "تعذر الانضمام"
        )
        val inviteConcept = containsAnyFragment(
            corpus,
            "invite link", "invitation", "رابط الدعوة", "الدعوة"
        )

        val resetOrInvalid = inviteConcept && containsAnyFragment(
            corpus,
            "reset", "revoked", "expired", "invalid", "no longer valid", "no longer available", "unavailable",
            "اعادة تعيين", "اعيد تعيين", "الغاء الرابط", "تم الغاء", "انتهت صلاحي", "منتهي",
            "غير صالح", "لم يعد صالح", "لم يعد متاح", "غير متاح"
        )
        if (resetOrInvalid || (groupOrCommunity && containsAnyFragment(
                corpus, "no longer exists", "لم تعد المجموعة موجود", "لم يعد المجتمع موجود"
            ))
        ) return AccessibilityFailureType.INVALID_OR_EXPIRED

        val removedOrBanned = groupOrCommunity && containsAnyFragment(
            corpus,
            "removed from", "you were removed", "you have been removed", "banned from",
            "ازالتك", "تم ازالتك", "تمت ازالتك", "حظرك", "تم حظرك"
        )
        if (removedOrBanned) return AccessibilityFailureType.REMOVED_OR_BANNED

        val full = groupOrCommunity && containsAnyFragment(
            corpus,
            "is full", "reached its limit", "maximum participants", "maximum members",
            "ممتل", "اكتمل عدد", "الحد الاقصى", "الحد الأقصى"
        )
        if (full) return AccessibilityFailureType.GROUP_FULL

        if (joinDenial && groupOrCommunity) return AccessibilityFailureType.GENERIC
        return best
    }

    /** Aggregate request-state matcher for split accessibility text. */
    fun isRequestSubmittedAcross(labels: Sequence<CharSequence?>): Boolean {
        val values = labels.map(::normalize).filter(String::isNotEmpty).toList()
        if (values.any { containsAny(it, requestSubmittedFragments) }) return true
        val corpus = values.joinToString(" ")
        if (corpus.isEmpty()) return false
        val arabicSubmitted = containsAnyFragment(corpus, "تم ارسال", "ارسال الطلب") &&
            containsAnyFragment(corpus, "الطلب", "طلب الانضمام")
        val englishSubmitted = containsAnyFragment(corpus, "request sent", "request pending", "pending approval")
        return arabicSubmitted || englishSubmitted
    }

    private fun failureRank(failure: AccessibilityFailureType?): Int = when (failure) {
        AccessibilityFailureType.REMOVED_OR_BANNED -> 4
        AccessibilityFailureType.INVALID_OR_EXPIRED -> 3
        AccessibilityFailureType.GROUP_FULL -> 2
        AccessibilityFailureType.GENERIC -> 1
        null -> 0
    }

    private fun containsAnyFragment(value: String, vararg fragments: String): Boolean =
        fragments.any { normalize(it).let(value::contains) }

    fun isRestricted(label: CharSequence?): Boolean =
        containsAny(normalize(label), restrictionFragments)

    fun isLoading(label: CharSequence?): Boolean =
        containsAny(normalize(label), loadingFragments)

    /** Strong marker for the normal WhatsApp chat/search surface after an invite collapses. */
    fun isWhatsAppHomeSurface(label: CharSequence?): Boolean =
        containsAny(normalize(label), whatsappHomeStrongFragments)

    /** Weak home-tab marker; callers should require multiple distinct/visible tab signals. */
    fun isWhatsAppHomeTab(label: CharSequence?): Boolean =
        normalize(label) in whatsappHomeTabLabels


    fun isConversationComposer(label: CharSequence?): Boolean {
        val value = normalize(label)
        return containsAny(value, conversationComposerFragments) ||
            idLooksLike(value, "conversation_entry", "message_entry", "entry_text", "/entry")
    }

    fun isConversationAction(label: CharSequence?): Boolean {
        val value = normalize(label)
        return containsAny(value, conversationActionFragments) ||
            idLooksLike(
                value,
                "voice_note", "voice_note_btn", "voice_message", "attach_button", "input_attach_button", "emoji_button", "emoji_picker_btn",
                "video_call", "voice_call", "conversation_attach"
            )
    }

    private fun normalizedSetOf(vararg values: String): Set<String> =
        values.mapTo(linkedSetOf()) { normalize(it) }

    private fun containsAny(value: String, candidates: Set<String>): Boolean =
        value.isNotEmpty() && candidates.any { candidate -> value == candidate || value.contains(candidate) }

    private fun idLooksLike(value: String, vararg parts: String): Boolean =
        parts.any(value::contains)

    private fun isDestructiveNormalized(value: String): Boolean =
        destructiveFragments.any(value::contains)

    private fun normalize(label: CharSequence?): String = label
        ?.toString()
        ?.trim()
        ?.replace("ـ", "")
        ?.replace(Regex("[\u064B-\u065F\u0670]"), "")
        ?.replace('أ', 'ا')
        ?.replace('إ', 'ا')
        ?.replace('آ', 'ا')
        ?.replace('ٱ', 'ا')
        ?.replace('ى', 'ي')
        ?.replace(Regex("\\s+"), " ")
        ?.lowercase(Locale.ROOT)
        .orEmpty()

    private val GENERIC_JOIN_LABELS = normalizedSetOf("join", "انضم", "انضمام")
}
