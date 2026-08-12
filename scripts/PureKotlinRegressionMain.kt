import com.althmany.groupmanager.domain.AccessibilityJoinAction
import com.althmany.groupmanager.domain.AccessibilityActionScoringPolicy
import com.althmany.groupmanager.domain.AccessibilityFailureType
import com.althmany.groupmanager.domain.RuntimeCircuitBreaker
import com.althmany.groupmanager.domain.RuntimeCadencePolicy
import com.althmany.groupmanager.domain.RuntimeConfidenceBand
import com.althmany.groupmanager.domain.RuntimeDecisionCoordinator
import com.althmany.groupmanager.domain.RuntimeDirective
import com.althmany.groupmanager.domain.RuntimeIntelligencePolicy
import com.althmany.groupmanager.domain.RuntimeIdempotencyGuard
import com.althmany.groupmanager.domain.RuntimeObservedScreen
import com.althmany.groupmanager.domain.RuntimeReplayEngine
import com.althmany.groupmanager.domain.RuntimeRecoveryPolicy
import com.althmany.groupmanager.domain.RuntimeReplayFrame
import com.althmany.groupmanager.domain.RuntimeScreenFingerprint
import com.althmany.groupmanager.domain.RuntimeWatchdogPolicy
import com.althmany.groupmanager.domain.RuntimeWatchdogState
import com.althmany.groupmanager.domain.AccessibilityJoinMatcher
import com.althmany.groupmanager.domain.AccessibilityScreenClassifier
import com.althmany.groupmanager.domain.AdaptiveInteractionPolicy
import com.althmany.groupmanager.domain.AutomationCommand
import com.althmany.groupmanager.domain.AutomationDecisionEngine
import com.althmany.groupmanager.domain.AutomationPolicy
import com.althmany.groupmanager.domain.AutomationScreenKind
import com.althmany.groupmanager.domain.AutomationStage
import com.althmany.groupmanager.domain.ForegroundTargetPolicy
import com.althmany.groupmanager.domain.ContinuousHandoffPolicy
import com.althmany.groupmanager.domain.ConversationFastExitPolicy
import com.althmany.groupmanager.domain.HybridBackendPolicy
import com.althmany.groupmanager.domain.ProfileControlCapability
import com.althmany.groupmanager.domain.ProfileControlPolicy
import com.althmany.groupmanager.domain.CommunityTraversalMatcher
import com.althmany.groupmanager.domain.CommunityTraversalPolicy
import com.althmany.groupmanager.domain.TerminalEscapePolicy
import com.althmany.groupmanager.domain.TerminalEscapeMode
import com.althmany.groupmanager.domain.InvitationStabilityPolicy
import com.althmany.groupmanager.domain.ScreenEvidenceConflict
import com.althmany.groupmanager.domain.ScreenEvidencePolicy
import com.althmany.groupmanager.domain.ScreenEvidenceSummary
import com.althmany.groupmanager.domain.ShizukuUiDumpParser
import com.althmany.groupmanager.domain.ShizukuRuntimePolicy
import com.althmany.groupmanager.domain.ShizukuFastUiPolicy
import com.althmany.groupmanager.domain.ShizukuContinuityPolicy
import com.althmany.groupmanager.domain.ShizukuBounds
import com.althmany.groupmanager.domain.ShizukuActivityProofPolicy
import com.althmany.groupmanager.domain.ShizukuLaunchPolicy
import com.althmany.groupmanager.domain.VisualActionButtonPolicy
import com.althmany.groupmanager.domain.WhatsAppLinkParser
import com.althmany.groupmanager.model.AutomationBackend
import com.althmany.groupmanager.util.RuntimeHealthMonitor
import com.althmany.groupmanager.domain.NativeProfileClass
import com.althmany.groupmanager.domain.NativeEngineSetupAction
import com.althmany.groupmanager.domain.NativeProfileEnginePolicy

private fun <T> expect(name: String, expected: T, actual: T) {
    check(expected == actual) { "$name: expected=$expected actual=$actual" }
}

fun main() {
    run {
        val width = 720
        val height = 1544
        val pixels = IntArray(width * height) { 0xfff7f8fa.toInt() }
        for (y in 1340 until 1432) for (x in 38 until 682) {
            pixels[y * width + x] = 0xff008069.toInt()
        }
        for (y in 1372 until 1402) for (x in 260 until 460) {
            pixels[y * width + x] = 0xffffffff.toInt()
        }
        val visualAction = VisualActionButtonPolicy.findWidePositiveAction(width, height) { x, y ->
            pixels[y * width + x]
        }
        check(visualAction != null && visualAction.centerX in 300..420 && visualAction.centerY in 1350..1425)

        val floatingOnly = IntArray(width * height) { 0xffffffff.toInt() }
        for (y in 1350 until 1430) for (x in 620 until 700) {
            floatingOnly[y * width + x] = 0xff25d366.toInt()
        }
        check(VisualActionButtonPolicy.findWidePositiveAction(width, height) { x, y ->
            floatingOnly[y * width + x]
        } == null)
    }

    expect(
        "pre-request admin notice still offers Request",
        AutomationScreenKind.REQUEST_ACTION,
        AccessibilityScreenClassifier.classify(
            sequenceOf("يجب أن يوافق أحد المشرفين على طلبك.", "طلب الانضمام")
        )
    )
    expect(
        "loading invitation is a wait state",
        AutomationScreenKind.LOADING,
        AccessibilityScreenClassifier.classify(sequenceOf("جار التحميل...", "إغلاق"))
    )
    check(AccessibilityJoinMatcher.isLoading("Loading group info"))
    check(AccessibilityJoinMatcher.isWhatsAppHomeSurface("اسأل Meta AI أو ابحث"))
    check(AccessibilityJoinMatcher.isWhatsAppHomeSurface("Ask Meta AI or Search"))
    check(!AccessibilityJoinMatcher.isWhatsAppHomeSurface("Chats"))
    check(AccessibilityJoinMatcher.isWhatsAppHomeTab("Chats"))
    check(AccessibilityJoinMatcher.isWhatsAppHomeTab("المكالمات"))
    check(AccessibilityJoinMatcher.isConversationComposer("اكتب رسالة"))
    check(AccessibilityJoinMatcher.isConversationComposer("Type a message"))
    check(AccessibilityJoinMatcher.isConversationAction("إرفاق"))
    check(AccessibilityJoinMatcher.isConversationAction("Voice message"))
    expect("outcome needs confirming scan", false, AdaptiveInteractionPolicy.shouldTrustOutcome(1))
    expect("outcome accepted after confirming scan", true, AdaptiveInteractionPolicy.shouldTrustOutcome(2))
    expect(
        "home surface waits for stable evidence",
        false,
        AdaptiveInteractionPolicy.shouldAdvanceFromHome(2, 8_000L)
    )
    expect(
        "stable home surface advances without reopening the same link",
        true,
        AdaptiveInteractionPolicy.shouldAdvanceFromHome(3, 4_500L)
    )
    expect("recent loading settles", true, AdaptiveInteractionPolicy.shouldWaitAfterLoading(500L))
    expect("settled loading releases", false, AdaptiveInteractionPolicy.shouldWaitAfterLoading(1_500L))
    expect("unknown needs repeated evidence", false, AdaptiveInteractionPolicy.unknownIsStableEnough(3))
    expect("unknown accepted after repeated evidence", true, AdaptiveInteractionPolicy.unknownIsStableEnough(4))

    expect(
        "submitted request with Cancel request",
        AutomationScreenKind.REQUEST_SUBMITTED,
        AccessibilityScreenClassifier.classify(
            sequenceOf("تم إرسال الطلب وفي انتظار موافقة المشرف.", "إلغاء الطلب")
        )
    )
    expect(
        "cancel request alone means request is already pending",
        AutomationScreenKind.REQUEST_SUBMITTED,
        AccessibilityScreenClassifier.classify(sequenceOf("إلغاء الطلب"))
    )
    expect(
        "current removed wording",
        AccessibilityFailureType.REMOVED_OR_BANNED,
        AccessibilityJoinMatcher.failureType("لا يمكنك الانضمام إلى هذه المجموعة حيث قد تمت إزالتك منها.")
    )
    expect(
        "group target detection",
        com.althmany.groupmanager.domain.AccessibilityInviteTarget.GROUP,
        AccessibilityJoinMatcher.targetType("الانضمام إلى المجموعة")
    )
    expect(
        "community target detection",
        com.althmany.groupmanager.domain.AccessibilityInviteTarget.COMMUNITY,
        AccessibilityJoinMatcher.targetType("انضمام للمجتمع")
    )
    expect(
        "screen-wide community target detection",
        com.althmany.groupmanager.domain.AccessibilityInviteTarget.COMMUNITY,
        AccessibilityJoinMatcher.targetTypeAcross(sequenceOf("دعوة", "عرض المجتمع", "انضمام للمجتمع"))
    )
    expect(
        "screen-wide group target detection",
        com.althmany.groupmanager.domain.AccessibilityInviteTarget.GROUP,
        AccessibilityJoinMatcher.targetTypeAcross(sequenceOf("معلومات", "الانضمام إلى المجموعة"))
    )
    check(
        CommunityTraversalMatcher.isCommunityHomeAcross(
            sequenceOf("المجتمع", "الإعلانات", "عرض كل المجموعات")
        )
    )
    check(
        CommunityTraversalMatcher.looksLikeGroupRow(
            text = "مجموعة الدراسة",
            description = "فتح المجموعة",
            viewId = "com.whatsapp:id/community_group_row",
            className = "android.widget.TextView",
            clickable = true
        )
    )
    check(
        !CommunityTraversalMatcher.looksLikeGroupRow(
            text = "الإعلانات",
            description = "فتح المجموعة",
            viewId = "com.whatsapp:id/community_group_row",
            className = "android.widget.TextView",
            clickable = true
        )
    )
    check(
        !CommunityTraversalMatcher.looksLikeGroupRow(
            text = "إضافة مجموعة",
            description = "إدارة المجموعات",
            viewId = "com.whatsapp:id/community_group_row",
            className = "android.widget.TextView",
            clickable = true
        )
    )
    check(
        CommunityTraversalMatcher.looksLikeGroupRow(
            text = "طلاب الجامعة",
            description = null,
            viewId = "com.whatsapp:id/conversation_contact_name",
            className = "android.widget.TextView",
            clickable = true
        )
    )
    check(
        !CommunityTraversalMatcher.looksLikeGroupRow(
            text = "عرض كل المجموعات",
            description = null,
            viewId = "com.whatsapp:id/conversation_contact_name",
            className = "android.widget.TextView",
            clickable = true
        )
    )
    expect("community cap allows first rows", true, CommunityTraversalPolicy.canProcessMore(0))
    expect("community cap stops at bound", false, CommunityTraversalPolicy.canProcessMore(CommunityTraversalPolicy.MAX_GROUPS_PER_COMMUNITY))
    expect("community scroll bounded", false, CommunityTraversalPolicy.canScroll(CommunityTraversalPolicy.MAX_SCROLL_ATTEMPTS))
    expect(
        "community empty-view completion waits for stability",
        false,
        CommunityTraversalPolicy.shouldFinishEmptyView(2, canScroll = false, scrollAttempts = 0)
    )
    expect(
        "community empty-view completion is bounded",
        true,
        CommunityTraversalPolicy.shouldFinishEmptyView(3, canScroll = false, scrollAttempts = 0)
    )
    check(AccessibilityJoinMatcher.isTerminalAcknowledgement("موافق"))
    check(!AccessibilityJoinMatcher.isTerminalAcknowledgement("إلغاء الطلب"))
    expect(
        "reset invitation",
        AutomationScreenKind.INVALID_OR_EXPIRED,
        AccessibilityScreenClassifier.classify(
            sequenceOf("لا يمكنك الانضمام إلى هذه المجموعة لأنه تمت إعادة تعيين رابط الدعوة الخاص بها.", "موافق")
        )
    )
    expect(
        "split reset invitation screen",
        AccessibilityFailureType.INVALID_OR_EXPIRED,
        AccessibilityJoinMatcher.failureTypeAcross(
            sequenceOf(
                "لا يمكنك الانضمام إلى هذه المجموعة لأنه تمت إعادة تعيين رابط",
                "الدعوة الخاص بها.",
                "موافق"
            )
        )
    )
    expect(
        "similar generic join denial",
        AccessibilityFailureType.GENERIC,
        AccessibilityJoinMatcher.failureTypeAcross(
            sequenceOf("لا يمكنك الانضمام", "إلى هذه المجموعة", "موافق")
        )
    )
    expect(
        "split full group denial",
        AccessibilityFailureType.GROUP_FULL,
        AccessibilityJoinMatcher.failureTypeAcross(
            sequenceOf("تعذر الانضمام", "بلغت المجموعة الحد الأقصى", "للأعضاء")
        )
    )
    check(
        AccessibilityJoinMatcher.isRequestSubmittedAcross(
            sequenceOf("تم إرسال", "طلب الانضمام", "وفي انتظار موافقة المشرف")
        )
    )
    check(
        !AccessibilityJoinMatcher.isRequestSubmittedAcross(
            sequenceOf("يجب أن يوافق أحد المشرفين", "على طلبك", "طلب الانضمام")
        )
    )
    expect(
        "removed account",
        AutomationScreenKind.REMOVED_OR_BANNED,
        AccessibilityScreenClassifier.classify(
            sequenceOf("لا يمكنك الانضمام إلى هذه المجموعة لأنه تم إزالتك.")
        )
    )
    expect(
        "full group",
        AutomationScreenKind.GROUP_FULL,
        AccessibilityScreenClassifier.classify(sequenceOf("هذه المجموعة ممتلئة الآن"))
    )
    expect(
        "compact community join",
        AccessibilityJoinAction.JOIN,
        AccessibilityJoinMatcher.actionType("انضمام للمجتمع")
    )
    expect(
        "colloquial group join",
        AccessibilityJoinAction.JOIN,
        AccessibilityJoinMatcher.actionType("انضم للقروب", inviteContext = true)
    )
    expect(
        "expanded request wording",
        AccessibilityJoinAction.REQUEST,
        AccessibilityJoinMatcher.actionType("اطلب الانضمام")
    )
    expect(
        "full group sentence",
        AccessibilityFailureType.GROUP_FULL,
        AccessibilityJoinMatcher.failureType("لا يمكنك الانضمام إلى هذه المجموعة لأنها ممتلئة")
    )
    expect(
        "cancel_join_button cannot be Join",
        null,
        AccessibilityJoinMatcher.actionType("com.whatsapp:id/cancel_join_button", inviteContext = true)
    )
    check(AccessibilityJoinMatcher.isBlockedAction("com.whatsapp:id/cancel_join_button"))
    check(AccessibilityJoinMatcher.isSafeClose("إغلاق الدعوة"))
    check(AccessibilityJoinMatcher.isSafeClose("Exit preview"))


    expect(
        "exact photographed reset-link dialog",
        AccessibilityFailureType.INVALID_OR_EXPIRED,
        AccessibilityJoinMatcher.failureTypeAcross(
            sequenceOf(
                "لا يمكنك الانضمام إلى هذه المجموعة",
                "لأنه تمت إعادة تعيين رابط الدعوة الخاص بها.",
                "موافق"
            )
        )
    )


    val resetEscape = TerminalEscapePolicy.assess(
        failure = AccessibilityFailureType.INVALID_OR_EXPIRED,
        requestSubmitted = false,
        alreadyMember = false,
        restricted = false
    )
    expect("reset is immediate terminal escape", TerminalEscapeMode.IMMEDIATE, resetEscape.mode)
    check(resetEscape.bypassInterLinkDelay)

    expect(
        "immediate terminal outranks stale loading",
        RuntimeDirective.HANDLE_TERMINAL,
        RuntimeDecisionCoordinator.decide(
            RuntimeObservedScreen(
                restricted = false,
                loading = true,
                conflict = ScreenEvidenceConflict.TERMINAL_WITH_POSITIVE_ACTION,
                hasTerminalEvidence = true,
                hasAction = true,
                immediateTerminal = true
            ),
            conflictShouldHold = true
        )
    )
    expect(
        "restriction still outranks terminal escape",
        RuntimeDirective.STOP_RESTRICTED,
        RuntimeDecisionCoordinator.decide(
            RuntimeObservedScreen(
                restricted = true,
                loading = false,
                conflict = ScreenEvidenceConflict.NONE,
                hasTerminalEvidence = true,
                hasAction = false,
                immediateTerminal = true
            ),
            conflictShouldHold = false
        )
    )
    expect(
        "generic denial remains verified",
        TerminalEscapeMode.VERIFIED,
        TerminalEscapePolicy.assess(
            failure = AccessibilityFailureType.GENERIC,
            requestSubmitted = false,
            alreadyMember = false,
            restricted = false
        ).mode
    )
    check(AccessibilityJoinMatcher.isTerminalAcknowledgement("android:id/button1"))

    val staleSuccessFirst = AccessibilityScreenClassifier.classify(
        sequenceOf("أنت عضو بالفعل في هذه المجموعة", "تمت إعادة تعيين رابط الدعوة")
    )
    val errorFirst = AccessibilityScreenClassifier.classify(
        sequenceOf("تمت إعادة تعيين رابط الدعوة", "أنت عضو بالفعل في هذه المجموعة")
    )
    expect("order-independent terminal state 1", AutomationScreenKind.INVALID_OR_EXPIRED, staleSuccessFirst)
    expect("order-independent terminal state 2", AutomationScreenKind.INVALID_OR_EXPIRED, errorFirst)

    val requestUnknown = AutomationDecisionEngine.decide(
        stage = AutomationStage.VERIFYING_RESULT,
        screen = AutomationScreenKind.UNKNOWN,
        stageAgeMs = AutomationDecisionEngine.RESULT_INFERENCE_AFTER_MS + 1,
        retryCount = 1,
        pendingAction = AccessibilityJoinAction.REQUEST
    )
    expect("request disappearance is not proof", AutomationCommand.WAIT, requestUnknown.command)

    val joinUnknown = AutomationDecisionEngine.decide(
        stage = AutomationStage.VERIFYING_RESULT,
        screen = AutomationScreenKind.UNKNOWN,
        stageAgeMs = AutomationDecisionEngine.RESULT_INFERENCE_AFTER_MS + 1,
        retryCount = 1,
        pendingAction = AccessibilityJoinAction.JOIN
    )
    expect("join disappearance alone is not proof", AutomationCommand.WAIT, joinUnknown.command)
    expect("1s balanced setting gets 10s runtime grace", 10_000L, InvitationStabilityPolicy.effectiveActionTimeoutMs(1))
    expect("1s turbo setting keeps 1s runtime grace", 1_000L, InvitationStabilityPolicy.effectiveActionTimeoutMs(1, turbo = true))
    expect("turbo loading settle clears quickly", false, AdaptiveInteractionPolicy.shouldWaitAfterLoading(220L, turbo = true))
    expect("turbo home recovery can advance after stable evidence", true, AdaptiveInteractionPolicy.shouldAdvanceFromHome(2, 1_000L, turbo = true))
    expect("loading gets 20s minimum", 20_000L, InvitationStabilityPolicy.loadingTimeoutMs(1))
    expect("same-link recovery reopen disabled", 0, InvitationStabilityPolicy.MAX_UNKNOWN_RELAUNCHES)
    expect("conversation detection accepts one strong scan", 1, InvitationStabilityPolicy.CONVERSATION_STABLE_SCANS)

    val terminalActionConflict = ScreenEvidencePolicy.conflict(
        ScreenEvidenceSummary(
            terminalEvidenceCount = 1,
            positiveActionCount = 1,
            conversationSurface = false,
            homeSurface = false
        )
    )
    expect(
        "terminal plus action is treated as transition conflict",
        ScreenEvidenceConflict.TERMINAL_WITH_POSITIVE_ACTION,
        terminalActionConflict
    )
    expect("first conflict scan waits", true, ScreenEvidencePolicy.shouldHold(terminalActionConflict, 1))
    expect("repeated conflict may be resolved", false, ScreenEvidencePolicy.shouldHold(terminalActionConflict, 2))

    val multipleActionsConflict = ScreenEvidencePolicy.conflict(
        ScreenEvidenceSummary(
            terminalEvidenceCount = 0,
            positiveActionCount = 2,
            conversationSurface = false,
            homeSurface = false
        )
    )
    expect(
        "multiple positive actions are treated as transition conflict",
        ScreenEvidenceConflict.MULTIPLE_POSITIVE_ACTIONS,
        multipleActionsConflict
    )
    expect("multiple action conflict waits first scan", true, ScreenEvidencePolicy.shouldHold(multipleActionsConflict, 1))
    expect(
        "pending Join ignores stale Preview",
        false,
        ScreenEvidencePolicy.actionAllowedWhilePending(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.PREVIEW)
    )
    expect(
        "pending Join allows confirmation",
        true,
        ScreenEvidencePolicy.actionAllowedWhilePending(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.CONFIRM)
    )

    val report = WhatsAppLinkParser.extract(
        "https://chat.whatsapp.com/ABCDEF1234567890abcdef\n" +
            "https://chat.whatsapp.com/ABCDEF1234567890abcdef\n" +
            "\u200Fhttps://chat.whatsapp.com/ZYXWV9876543210zyxwv"
    )
    expect("parser accepted unique links", 2, report.accepted.size)
    expect("parser duplicate count", 1, report.duplicateCount)
    expect("session queue cap", 1_000_000, AutomationPolicy.MAX_LINKS_PER_SESSION)
    expect("explicit run cap", 1000, AutomationPolicy.BATCH_SIZE)
    expect("explicit-run windows per full queue", 1_000, AutomationPolicy.MAX_BATCHES_PER_SESSION)
    expect(
        "exact-user conversation activity proof",
        true,
        ShizukuActivityProofPolicy.findJoinedConversationProof(
            sequenceOf("topResumedActivity=ActivityRecord{42 u10 com.whatsapp/.Conversation t9}"),
            "com.whatsapp",
            10
        ) != null
    )
    expect(
        "cross-profile activity proof rejected",
        null,
        ShizukuActivityProofPolicy.findJoinedConversationProof(
            sequenceOf("topResumedActivity=ActivityRecord{42 u0 com.whatsapp/.Conversation t9}"),
            "com.whatsapp",
            10
        )
    )

    expect("turbo continuity watchdog interval", 40L, ContinuousHandoffPolicy.watchIntervalMs(true))
    expect("normal continuity watchdog interval", 180L, ContinuousHandoffPolicy.watchIntervalMs(false))
    expect(
        "turbo continuity does not force-skip loading",
        false,
        ContinuousHandoffPolicy.shouldForceAdvance(5_000L, loading = true, restricted = false, turbo = true)
    )
    expect(
        "turbo continuity advances a stalled non-loading action",
        true,
        ContinuousHandoffPolicy.shouldForceAdvance(1_000L, loading = false, restricted = false, turbo = true)
    )
    expect(
        "restriction is never force-advanced",
        false,
        ContinuousHandoffPolicy.shouldForceAdvance(10_000L, loading = false, restricted = true, turbo = true)
    )
    expect("turbo conversation uses direct next-link handoff", true, ContinuousHandoffPolicy.shouldLaunchNextDirectlyFromConversation(true))
    expect("conversation fast back settle", 0L, ConversationFastExitPolicy.settleMs(true))
    expect("conversation fast back enabled", true, ConversationFastExitPolicy.shouldAttemptBack(true, loading = false, restricted = false))
    expect("conversation back suppressed during loading", false, ConversationFastExitPolicy.shouldAttemptBack(true, loading = true, restricted = false))
    expect(
        "profile control rejects a system-only cross-profile service",
        ProfileControlCapability.SERVICE_NOT_CONNECTED_LOCALLY,
        ProfileControlPolicy.classify(
            systemEnabled = true,
            localServiceConnected = false,
            targetEventAgeMs = null,
            rootAgeMs = null,
            rootAvailable = false
        )
    )
    expect(
        "profile control becomes ready only with local event and root evidence",
        ProfileControlCapability.READY,
        ProfileControlPolicy.classify(
            systemEnabled = true,
            localServiceConnected = true,
            targetEventAgeMs = 100L,
            rootAgeMs = 100L,
            rootAvailable = true
        )
    )
    expect(
        "profile control identifies missing target UI tree",
        ProfileControlCapability.TARGET_UI_TREE_UNAVAILABLE,
        ProfileControlPolicy.classify(
            systemEnabled = true,
            localServiceConnected = true,
            targetEventAgeMs = 100L,
            rootAgeMs = 100L,
            rootAvailable = false
        )
    )

    expect(
        "explicit Shizuku falls back to Accessibility when Shizuku is dead",
        AutomationBackend.ACCESSIBILITY,
        HybridBackendPolicy.chooseForStart(AutomationBackend.SHIZUKU, accessibilityEnabled = true, shizukuReady = false)
    )
    expect(
        "live Shizuku retains ownership",
        AutomationBackend.SHIZUKU,
        HybridBackendPolicy.chooseForStart(AutomationBackend.SHIZUKU, accessibilityEnabled = true, shizukuReady = true)
    )


    expect(
        "transient foreground change does not auto-pause",
        false,
        ForegroundTargetPolicy.shouldPauseOutsideTarget(100L, 500L, true)
    )
    expect(
        "stable foreground departure auto-pauses",
        true,
        ForegroundTargetPolicy.shouldPauseOutsideTarget(400L, 400L, true)
    )

    RuntimeHealthMonitor.clear()
    RuntimeHealthMonitor.updateScreen(
        nowElapsedMs = 100L,
        fingerprint = 42L,
        stableScreenScans = 3,
        directive = "HANDLE_ACTION",
        action = "REQUEST",
        conflict = "NONE"
    )
    RuntimeHealthMonitor.updateConfidence(110L, 94, "VERY_HIGH")
    RuntimeHealthMonitor.updateWatchdog(120L, "HEALTHY")
    val health = checkNotNull(RuntimeHealthMonitor.snapshot())
    expect("runtime health confidence", 94, health.confidence)
    expect("runtime health directive", "HANDLE_ACTION", health.directive)
    RuntimeHealthMonitor.clear()

    val requestScore = AccessibilityActionScoringPolicy.score(
        action = AccessibilityJoinAction.REQUEST,
        requestApprovalNoticeSeen = true,
        clickable = true,
        clickableParent = false,
        buttonClass = true,
        hasViewId = true,
        textLabel = true,
        inviteContext = true,
        adequateBounds = true
    )
    val joinScoreDuringApproval = AccessibilityActionScoringPolicy.score(
        action = AccessibilityJoinAction.JOIN,
        requestApprovalNoticeSeen = true,
        clickable = true,
        clickableParent = false,
        buttonClass = true,
        hasViewId = true,
        textLabel = true,
        inviteContext = true,
        adequateBounds = true
    )
    check(requestScore > joinScoreDuringApproval)

    val actionAssessment = RuntimeIntelligencePolicy.assessAction(
        action = AccessibilityJoinAction.REQUEST,
        inviteContext = true,
        loading = false,
        positiveActionCount = 1,
        candidateScoreMargin = Int.MAX_VALUE,
        terminalEvidenceCount = 0,
        conflict = ScreenEvidenceConflict.NONE,
        stableActionScans = 2,
        stableScreenScans = 2,
        pendingAction = null
    )
    check(actionAssessment.safeToAct)
    expect("strong action confidence", RuntimeConfidenceBand.VERY_HIGH, actionAssessment.band)

    val loadingAssessment = RuntimeIntelligencePolicy.assessAction(
        action = AccessibilityJoinAction.JOIN,
        inviteContext = true,
        loading = true,
        positiveActionCount = 1,
        candidateScoreMargin = Int.MAX_VALUE,
        terminalEvidenceCount = 0,
        conflict = ScreenEvidenceConflict.NONE,
        stableActionScans = 2,
        stableScreenScans = 2,
        pendingAction = null
    )
    expect("loading blocks action confidence", false, loadingAssessment.safeToAct)

    val fingerprintA = RuntimeScreenFingerprint.calculate(sequenceOf("Join group", "Group invite"))
    val fingerprintB = RuntimeScreenFingerprint.calculate(sequenceOf("Group invite", "Join group"))
    expect("fingerprint order independence", fingerprintA, fingerprintB)

    expect("circuit breaker before threshold", false, RuntimeCircuitBreaker.shouldTrip(5))
    expect("circuit breaker threshold", true, RuntimeCircuitBreaker.shouldTrip(6))
    expect("known result resets circuit breaker", 0, RuntimeCircuitBreaker.nextCount(4, "GROUP_FULL"))

    expect(
        "runtime coordinator gives loading priority",
        RuntimeDirective.WAIT_LOADING,
        RuntimeDecisionCoordinator.decide(
            RuntimeObservedScreen(
                restricted = false,
                loading = true,
                conflict = ScreenEvidenceConflict.TERMINAL_WITH_POSITIVE_ACTION,
                hasTerminalEvidence = true,
                hasAction = true
            ),
            conflictShouldHold = true
        )
    )
    val replay = RuntimeReplayEngine.replay(
        initialStage = AutomationStage.WAITING_FOR_WHATSAPP,
        frames = listOf(
            RuntimeReplayFrame(AutomationScreenKind.LOADING, 500L),
            RuntimeReplayFrame(AutomationScreenKind.REQUEST_ACTION, 2_000L),
            RuntimeReplayFrame(AutomationScreenKind.REQUEST_SUBMITTED, 3_000L, pendingAction = AccessibilityJoinAction.REQUEST)
        )
    )
    expect("replay frame count", 3, replay.size)
    expect("replay ends requested", AutomationCommand.COMPLETE_REQUESTED, replay.last().decision.command)
    expect(
        "watchdog detects stable stall",
        RuntimeWatchdogState.STALLED,
        RuntimeWatchdogPolicy.assess(12, 8_500L, loading = false, conflict = ScreenEvidenceConflict.NONE)
    )
    expect(
        "watchdog never calls loading a stall",
        RuntimeWatchdogState.LOADING,
        RuntimeWatchdogPolicy.assess(30, 30_000L, loading = true, conflict = ScreenEvidenceConflict.NONE)
    )
    expect(
        "turbo watchdog detects inert screen earlier",
        RuntimeWatchdogState.STALLED,
        RuntimeWatchdogPolicy.assess(7, 1_700L, loading = false, conflict = ScreenEvidenceConflict.NONE, turbo = true)
    )
    expect("fast fallback poll avoids busy loop", 80L, RuntimeCadencePolicy.pollIntervalMs(true))
    expect("fast event scan cadence", 12L, RuntimeCadencePolicy.minScanIntervalMs(true, 1))
    expect("stable fast screen relaxes cadence", 30L, RuntimeCadencePolicy.minScanIntervalMs(true, 8))
    val maxSpeed = RuntimeSpeedProfilePolicy.resolve(RuntimeSpeedMode.MAX)
    expect("MAX event scan", 6L, maxSpeed.eventScanMs)
    expect("MAX stable scan", 14L, maxSpeed.stableScanMs)
    expect("MAX fallback poll", 40L, maxSpeed.fallbackPollMs)
    expect("MAX post tap", 22L, maxSpeed.postTapWaitMs)
    expect("MAX next link", 0L, maxSpeed.interLinkDelayMs)

    val customSpeed = RuntimeSpeedProfilePolicy.resolve(
        RuntimeSpeedMode.CUSTOM,
        customScanMs = 18,
        customPostTapMs = 75,
        customInterLinkMs = 0
    )
    expect("Custom event scan", 18L, customSpeed.eventScanMs)
    expect("Custom post tap", 75L, customSpeed.postTapWaitMs)
    expect("Custom zero next", 0L, customSpeed.interLinkDelayMs)
    expect(
        "stalled unknown is recoverable",
        true,
        RuntimeRecoveryPolicy.shouldAdvanceStalledUnknown(
            RuntimeWatchdogState.STALLED,
            loading = false,
            restricted = false,
            pendingAction = false,
            hasRecognizedAction = false
        )
    )

    val idempotency = RuntimeIdempotencyGuard()
    check(idempotency.shouldAllow("1:JOIN", 1_000L, 1_200L))
    idempotency.recordSuccess("1:JOIN", 1_000L)
    expect("duplicate action is suppressed", false, idempotency.shouldAllow("1:JOIN", 1_500L, 1_200L))
    expect("bounded retry becomes available", true, idempotency.shouldAllow("1:JOIN", 2_300L, 1_200L))

    val shizukuDump = """<hierarchy rotation="0"><node text="دعوة المجموعة" resource-id="" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" bounds="[0,0][500,120]"/><node text="الانضمام إلى المجموعة" resource-id="com.whatsapp:id/join_button" class="android.widget.Button" package="com.whatsapp" content-desc="" clickable="true" enabled="true" bounds="[120,1600][960,1740]"/></hierarchy>"""
    val shizukuSnapshot = ShizukuUiDumpParser.parse(shizukuDump)
    expect("shizuku dump classifies join", AutomationScreenKind.JOIN_ACTION, shizukuSnapshot.screenKind)
    expect("shizuku dump has safe action", true, shizukuSnapshot.actionNode(AccessibilityJoinAction.JOIN) != null)
    expect("shizuku join x", 540, shizukuSnapshot.actionNode(AccessibilityJoinAction.JOIN)!!.bounds!!.centerX)
    val homeDump = """<hierarchy rotation="0"><node text="Chats" resource-id="com.whatsapp:id/chats" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="true" enabled="true" bounds="[0,2100][260,2300]"/><node text="Communities" resource-id="com.whatsapp:id/communities" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="true" enabled="true" bounds="[260,2100][520,2300]"/></hierarchy>"""
    val homeSnapshot = ShizukuUiDumpParser.parse(homeDump)
    expect("whatsapp home tabs do not fabricate invite context", false, homeSnapshot.inviteContext)


    val wrongPackageDump = """<hierarchy rotation="0"><node text="دعوة المجموعة" resource-id="" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" bounds="[0,0][500,120]"/><node text="الانضمام إلى المجموعة" resource-id="com.fake:id/join_button" class="android.widget.Button" package="com.fake" content-desc="" clickable="true" enabled="true" bounds="[120,1600][960,1740]"/></hierarchy>"""
    val wrongPackageSnapshot = ShizukuUiDumpParser.parse(wrongPackageDump)
    expect("shizuku rejects action from another package", true, wrongPackageSnapshot.actionNode(AccessibilityJoinAction.JOIN, "com.whatsapp") == null)

    val flattenedLabelDump = """<hierarchy rotation="0"><node text="دعوة المجموعة" resource-id="" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" bounds="[0,0][500,120]"/><node text="الانضمام إلى المجموعة" resource-id="" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" bounds="[120,1600][960,1740]"/></hierarchy>"""
    val flattenedLabelSnapshot = ShizukuUiDumpParser.parse(flattenedLabelDump)
    expect("flattened work-profile join label remains a guarded target", true, flattenedLabelSnapshot.actionNode(AccessibilityJoinAction.JOIN, "com.whatsapp") != null)
    expect("flattened non-clickable label keeps two-scan consensus", 2, ShizukuRuntimePolicy.actionConsensusScans(66, Int.MIN_VALUE, false, true, false))

    val ambiguousDump = """<hierarchy rotation="0"><node text="دعوة المجموعة" class="android.widget.TextView" package="com.whatsapp" content-desc="" resource-id="" clickable="false" enabled="true" bounds="[0,0][500,120]"/><node text="الانضمام إلى المجموعة" class="android.widget.Button" package="com.whatsapp" content-desc="" resource-id="com.whatsapp:id/join_button_a" clickable="true" enabled="true" bounds="[100,1500][500,1650]"/><node text="الانضمام إلى المجموعة" class="android.widget.Button" package="com.whatsapp" content-desc="" resource-id="com.whatsapp:id/join_button_b" clickable="true" enabled="true" bounds="[520,1500][980,1650]"/></hierarchy>"""
    val ambiguousSnapshot = ShizukuUiDumpParser.parse(ambiguousDump)
    expect("shizuku ambiguous duplicate actions are withheld", true, ambiguousSnapshot.actionSelection(AccessibilityJoinAction.JOIN, "com.whatsapp").candidate == null)

    val communityDump = """<hierarchy rotation="0"><node text="معلومات المجتمع" resource-id="com.whatsapp:id/community_title" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" scrollable="false" bounds="[0,0][700,120]"/><node text="المجموعات في هذا المجتمع" resource-id="com.whatsapp:id/groups_header" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" scrollable="false" bounds="[0,120][700,220]"/><node text="مجموعة الاختبار" resource-id="com.whatsapp:id/group_row" class="android.view.ViewGroup" package="com.whatsapp" content-desc="فتح المجموعة" clickable="true" enabled="true" scrollable="false" bounds="[0,300][1080,480]"/><node text="" resource-id="com.whatsapp:id/list" class="android.widget.ScrollView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" scrollable="true" bounds="[0,220][1080,2100]"/></hierarchy>"""
    val communitySnapshot = ShizukuUiDumpParser.parse(communityDump)
    val compactPayload = listOf(
        "__AL_FAST_COMPACT__=1",
        listOf("N", "Join group", "", "com.whatsapp:id/join", "android.widget.Button", "com.whatsapp", "1", "1", "0", "0", "100,200,500,280").joinToString("\t"),
        listOf("N", "Message", "", "com.whatsapp:id/entry", "android.widget.EditText", "com.whatsapp", "1", "1", "0", "0", "40,1200,680,1500").joinToString("\t"),
        listOf("N", "Voice message", "", "com.whatsapp:id/voice", "android.widget.ImageButton", "com.whatsapp", "1", "1", "0", "0", "620,1200,710,1500").joinToString("\t")
    ).joinToString("\n")
    val compactSnapshot = ShizukuUiDumpParser.parse(compactPayload)
    expect("compact fast frame preserves action evidence", AutomationScreenKind.JOIN_ACTION, compactSnapshot.screenKind)
    expect("compact fast frame preserves clickable join", true, compactSnapshot.actionNode(AccessibilityJoinAction.JOIN, "com.whatsapp") != null)

    expect("shizuku recognizes community home", true, communitySnapshot.communityHomeSurface)
    expect("shizuku finds semantic community subgroup", 1, communitySnapshot.communityGroupCandidates("com.whatsapp").size)
    expect("shizuku finds community scroll surface", true, communitySnapshot.communityScrollNode("com.whatsapp") != null)

    expect("safe shizuku tap bounds", true, ShizukuRuntimePolicy.isSafeTapBounds(ShizukuBounds(120, 1600, 960, 1740), 1080, 2340))
    expect("oversized shizuku tap bounds rejected", false, ShizukuRuntimePolicy.isSafeTapBounds(ShizukuBounds(0, 0, 1080, 2340), 1080, 2340))
    expect("high confidence shizuku action uses one scan", 1, ShizukuRuntimePolicy.actionConsensusScans(78, Int.MIN_VALUE, true, true, false))
    expect("close runner-up keeps two scans", 2, ShizukuRuntimePolicy.actionConsensusScans(78, 70, true, true, false))
    expect("cross-package action keeps two scans", 2, ShizukuRuntimePolicy.actionConsensusScans(100, Int.MIN_VALUE, true, false, false))
    expect("shizuku fallback input cooldown", 32L, ShizukuRuntimePolicy.INPUT_COOLDOWN_MS)
    expect("shizuku active click throttle", 60L, ShizukuRuntimePolicy.inputCooldownMs(true))
    expect("fast ui event scan", 12L, ShizukuFastUiPolicy.EVENT_SCAN_MS)
    expect("fast ui stable scan", 30L, ShizukuFastUiPolicy.STABLE_SCAN_MS)
    expect("fast ui fallback poll", 80L, ShizukuFastUiPolicy.FALLBACK_POLL_MS)
    expect("fast ui watchdog interval", 32L, ShizukuFastUiPolicy.WATCHDOG_INTERVAL_MS)
    expect("fast ui watchdog hard limit", 1_000L, ShizukuFastUiPolicy.NON_LOADING_WATCHDOG_MS)
    expect("fast ui action retry", 95L, ShizukuFastUiPolicy.ACTION_RETRY_AFTER_MS)
    expect("fast ui post join evidence", 30L, ShizukuFastUiPolicy.POST_JOIN_MIN_EVIDENCE_MS)
    expect("fast ui loading guard", 20_000L, ShizukuFastUiPolicy.LOADING_TIMEOUT_MS)
    expect("fast ui instant next settle", 0L, ShizukuFastUiPolicy.USER_INSTANT_ADVANCE_SETTLE_MS)
    expect("fast ui event tree coalesce", 0L, ShizukuFastUiPolicy.EVENT_TREE_COALESCE_MS)
    expect("continuity foreground reopen", 260L, ShizukuContinuityPolicy.FOREGROUND_REOPEN_AFTER_MS)
    expect("continuity foreground advance", 1_100L, ShizukuContinuityPolicy.FOREGROUND_ADVANCE_AFTER_MS)
    expect("continuity target-hidden re-probe", 260L, ShizukuContinuityPolicy.TARGET_HIDDEN_FOREGROUND_REPROBE_MS)
    expect("normal am launch accepted", true, ShizukuLaunchPolicy.launchAccepted(0, "Starting: Intent"))
    expect("am semantic error rejected", false, ShizukuLaunchPolicy.launchAccepted(0, "Error: Activity class does not exist"))
    expect("am shell error rejected", false, ShizukuLaunchPolicy.launchAccepted(126, ""))
    expect("accessibility setup one false read ignored", false, ProfileControlPolicy.shouldPromptAccessibilitySetup(false, 1))
    expect("accessibility setup two false reads ignored", false, ProfileControlPolicy.shouldPromptAccessibilitySetup(false, 2))
    expect("accessibility setup three false reads confirmed", true, ProfileControlPolicy.shouldPromptAccessibilitySetup(false, 3))
    expect("accessibility setup never re-prompts after enabled was observed", false, ProfileControlPolicy.shouldPromptAccessibilitySetup(true, 99))
    expect("accessibility process callback proves local connection", true, ProfileControlPolicy.isLocalConnectionAlive(true, false))
    expect("fresh profile heartbeat repairs delayed callback", true, ProfileControlPolicy.isLocalConnectionAlive(false, true))
    expect("no callback or heartbeat is not locally connected", false, ProfileControlPolicy.isLocalConnectionAlive(false, false))
    expect("enabled accessibility may start while Samsung binds", true, ProfileControlPolicy.mayStartWhileServiceBinds(true))
    expect("disabled accessibility still requires setup", false, ProfileControlPolicy.mayStartWhileServiceBinds(false))
    expect("continuity no-root advance", 1_200L, ShizukuContinuityPolicy.NO_ROOT_ADVANCE_AFTER_MS)
    expect("work compatibility probe waits for bounded transition", false, ShizukuContinuityPolicy.shouldProbeProfileCompatibleTree(179L, 0))
    expect("work compatibility probe starts after persistent mismatch", true, ShizukuContinuityPolicy.shouldProbeProfileCompatibleTree(180L, 0))
    expect("work compatibility probe is bounded", false, ShizukuContinuityPolicy.shouldProbeProfileCompatibleTree(900L, 2))
    expect("continuity direct conversation", true, ShizukuContinuityPolicy.isDirectConversationResolution(AutomationStage.WAITING_FOR_WHATSAPP, 35L, 1))
    expect("continuity stale conversation rejected", false, ShizukuContinuityPolicy.isDirectConversationResolution(AutomationStage.WAITING_FOR_WHATSAPP, 3_001L, 1))
    expect("shizuku post-action strong surface single scan", 1, ShizukuRuntimePolicy.POST_ACTION_STABLE_SCANS)
    expect("shizuku persistent foreground lease", 8_000L, ShizukuRuntimePolicy.FOREGROUND_LEASE_MS)
    expect("shizuku capability recheck amortized", 12_000L, ShizukuRuntimePolicy.CAPABILITY_RECHECK_MS)

    val nativePersonal = NativeProfileEnginePolicy.choose(
        requested = AutomationBackend.AUTO,
        profileClass = NativeProfileClass.OWNER,
        accessibilityLocalReady = true,
        shizukuReady = true,
        selfCanManageWorkPolicy = false,
        workPolicyBlocksSelf = false
    )
    expect("native router prefers local accessibility", AutomationBackend.ACCESSIBILITY, nativePersonal.backend)

    val nativePersonalShizukuFallback = NativeProfileEnginePolicy.choose(
        requested = AutomationBackend.ACCESSIBILITY,
        profileClass = NativeProfileClass.OWNER,
        accessibilityLocalReady = false,
        shizukuReady = true,
        selfCanManageWorkPolicy = false,
        workPolicyBlocksSelf = false
    )
    expect(
        "explicit accessibility uses shizuku when accessibility is unbound",
        AutomationBackend.SHIZUKU,
        nativePersonalShizukuFallback.backend
    )
    expect(
        "hybrid shizuku fallback needs no setup dialog",
        NativeEngineSetupAction.NONE,
        nativePersonalShizukuFallback.setupAction
    )

    val nativeWorkFallback = NativeProfileEnginePolicy.choose(
        requested = AutomationBackend.AUTO,
        profileClass = NativeProfileClass.MANAGED_WORK,
        accessibilityLocalReady = false,
        shizukuReady = true,
        selfCanManageWorkPolicy = false,
        workPolicyBlocksSelf = false
    )
    expect("native router uses shizuku in work when local accessibility absent", AutomationBackend.SHIZUKU, nativeWorkFallback.backend)

    val nativeWorkPolicy = NativeProfileEnginePolicy.choose(
        requested = AutomationBackend.AUTO,
        profileClass = NativeProfileClass.MANAGED_WORK,
        accessibilityLocalReady = false,
        shizukuReady = false,
        selfCanManageWorkPolicy = true,
        workPolicyBlocksSelf = true
    )
    expect("work owner gets policy setup action", NativeEngineSetupAction.APPLY_WORK_ACCESSIBILITY_POLICY, nativeWorkPolicy.setupAction)

    val nativeSecure = NativeProfileEnginePolicy.choose(
        requested = AutomationBackend.AUTO,
        profileClass = NativeProfileClass.SAMSUNG_ISOLATED,
        accessibilityLocalReady = false,
        shizukuReady = false,
        selfCanManageWorkPolicy = false,
        workPolicyBlocksSelf = false
    )
    expect("secure folder asks for shizuku when no local backend", NativeEngineSetupAction.START_OR_AUTHORIZE_SHIZUKU, nativeSecure.setupAction)

    val overCapacity = WhatsAppLinkParser.extract(
        (1..5004).joinToString("\n") { "https://chat.whatsapp.com/QueueInviteCode$it" }
    )
    expect("large queue accepted", 5004, overCapacity.accepted.size)
    expect("large queue has no overflow", 0, overCapacity.ignoredBecauseOfLimit)

    println("PURE KOTLIN REGRESSIONS PASSED")
}
