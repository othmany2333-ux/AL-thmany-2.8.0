package com.althmany.groupmanager.shizuku

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.althmany.groupmanager.BuildConfig
import com.althmany.groupmanager.GroupManagerApp
import com.althmany.groupmanager.R
import com.althmany.groupmanager.accessibility.AccessibilityStatus
import com.althmany.groupmanager.domain.AccessibilityInviteTarget
import com.althmany.groupmanager.domain.AccessibilityJoinAction
import com.althmany.groupmanager.domain.AccessibilityJoinMatcher
import com.althmany.groupmanager.domain.AutomationCommand
import com.althmany.groupmanager.domain.AutomationDecisionEngine
import com.althmany.groupmanager.domain.AutomationPolicy
import com.althmany.groupmanager.domain.AutomationScreenKind
import com.althmany.groupmanager.domain.AutomationStage
import com.althmany.groupmanager.domain.AutomationStopReason
import com.althmany.groupmanager.domain.LinkRuntimePhase
import com.althmany.groupmanager.domain.RestrictionHandlingMode
import com.althmany.groupmanager.domain.CommunityTraversalPolicy
import com.althmany.groupmanager.domain.CommunityTraversalStage
import com.althmany.groupmanager.domain.ShizukuBounds
import com.althmany.groupmanager.domain.ShizukuActivityProofPolicy
import com.althmany.groupmanager.domain.ShizukuRuntimePolicy
import com.althmany.groupmanager.domain.ShizukuFastUiPolicy
import com.althmany.groupmanager.domain.ShizukuContinuityPolicy
import com.althmany.groupmanager.domain.ShizukuLaunchPolicy
import com.althmany.groupmanager.domain.ShizukuUiDumpParser
import com.althmany.groupmanager.domain.ShizukuUiNode
import com.althmany.groupmanager.domain.ShizukuUiSnapshot
import com.althmany.groupmanager.model.AutomationBackend
import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.LinkResultCode
import com.althmany.groupmanager.model.LinkStatus
import com.althmany.groupmanager.receiver.AutomationActionReceiver
import com.althmany.groupmanager.ui.MainActivity
import com.althmany.groupmanager.util.GroupJoinerResultStore
import com.althmany.groupmanager.util.AutomationScreenAwakeGuard
import com.althmany.groupmanager.util.QuickJoinNotification
import com.althmany.groupmanager.util.RuntimeDiagnosticStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Smart Shizuku backend.
 *
 * It never assumes shell access bypasses Knox/DPC. Before consuming queue state it proves that the
 * selected WhatsApp and AL-thmany exist in the same Android user. Every injected action is then
 * gated by foreground package/user, package-aware UI evidence, adaptive semantic consensus, safe
 * screen bounds and an idempotency cooldown. 2.6.4 uses an event-coalesced persistent shell-owned UiAutomation
 * connection inside the Shizuku UserService. When Android exposes that connection, node-tree reads,
 * taps and Back actions avoid spawning `uiautomator`/`input` processes for every cycle and approach
 * Accessibility-style latency. If the platform blocks the persistent connection, the established
 * command-based UIAutomator path remains an automatic fallback. Ambiguous screens keep guarded
 * verification and no Knox/DPC boundary is bypassed.
 */
class ShizukuAutomationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app: GroupManagerApp get() = application as GroupManagerApp

    private fun runtimeSpeed() = app.preferences.runtimeSpeedProfile()
    private var runJob: Job? = null
    private val resultCommitExecuting = AtomicBoolean(false)

    private var cachedAndroidUserId: Int? = null
    private var cachedTargetPackage: String? = null
    private var cachedResolvedActivityUserId: Int? = null
    private var cachedResolvedActivityTargetPackage: String? = null
    private var cachedResolvedActivityName: String? = null
    private var displayWidth = 0
    private var displayHeight = 0

    private var lastSnapshotSignature = 0
    private var stableSnapshotScans = 0
    private var emptyDumpStartedAt = 0L
    private var foregroundWaitStartedAt = 0L
    private var lastForegroundProbeSummary = "not-run"
    private var consecutiveDumpFailures = 0
    private var commandDumpKillRecoveryAttempts = 0
    private var commandDumpSuppressedUntilElapsed = 0L
    private var lastDumpFailureCountedAtElapsed = 0L
    private var userServiceRestartAttempts = 0
    private var lastPeriodicUiRefreshProcessed = 0
    private var consecutiveAmbiguousActions = 0
    private var consecutiveInputFailures = 0

    private var lastActionFingerprint: String? = null
    private var stableActionScans = 0
    private var lastInputAtElapsed = 0L
    private var lastCapabilityCheckElapsed = 0L
    private var lastForegroundVerifiedAtElapsed = 0L
    private var lastForegroundVerifiedPackage: String? = null
    private var lastForegroundVerifiedUserId: Int? = null
    private var outsideTargetCandidateStartedAtElapsed = 0L
    private var lastOutsideTargetProbeAtElapsed = 0L
    private var fastUiSessionRecoveryAttempts = 0
    // 2.7.0 probes the persistent event-first bridge on every fresh Shizuku UserService. Devices
    // that reject it fall back once to command UIAutomator; compatible Work/Secure profiles keep
    // the fast event/tree connection for the whole run.
    private var fastUiMode = FastUiMode.UNKNOWN
    private var fastUiFailureCount = 0
    private var lastFastUiDetail = "not-probed"
    private var lastNotificationAtElapsed = 0L
    private var fastBurstUntilElapsed = 0L
    private var lastFastEventSequence = 0L
    private var fastPresetLogged = false
    private var loadingStartedAtElapsed = 0L
    private var unknownStartedAtElapsed = 0L
    private var foregroundRecoveryAttempts = 0
    private var fastUiNoRootStartedAtElapsed = 0L
    private var fastUiTargetHiddenStartedAtElapsed = 0L
    private var profileCompatCommandProbes = 0
    private var lastRequestTerminalProbeAtElapsed = 0L
    private var currentLaunchElapsed = 0L
    private var currentLaunchEventBaseline = 0L
    private var currentLaunchSawTargetEvent = false
    private var currentLaunchSawTargetForeground = false
    private var visualProbeLinkId = -1L
    private var visualProbeAttempts = 0
    private var lastVisualProbeAtElapsed = 0L
    private var visualActionTappedAtElapsed = 0L
    private var visualTapAttempts = 0
    private var visualExpectedAction: AccessibilityJoinAction? = null

    private var communityHomeStableScans = 0
    private var communityEmptyStableScans = 0
    private var communityReturnBackSteps = 0
    private var communityStageStartedAtElapsed = 0L
    private var lastCommunityCandidateFingerprint: String? = null
    private var communityCandidateStableScans = 0
    private var lastCommunityHomeSignature = 0
    private var communityNoProgressScans = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.shizuku_service_starting)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRun(AutomationStopReason.USER_STOPPED, "Shizuku automation stopped by user")
            return START_NOT_STICKY
        }
        if (runJob?.isActive != true) runJob = serviceScope.launch { automationLoop() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AutomationScreenAwakeGuard.release()
        runJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun automationLoop() {
        val prefs = app.preferences
        if (prefs.runtimeAutomationBackend != AutomationBackend.SHIZUKU) {
            stopSelf()
            return
        }
        if (prefs.runtimeShadowMode) {
            // Starting an explicit run always means execute. Samsung backup/restore can revive an
            // old developer Shadow preference after MainActivity cleared it.
            prefs.runtimeShadowMode = false
            RuntimeDiagnosticStore.append(this, "SHADOW_AUTO_DISABLED", "Shizuku explicit run restored executable input")
        }

        val targetPackage = prefs.runtimeLockedWhatsAppPackage
        if (targetPackage.isNullOrBlank() || !PACKAGE_NAME.matches(targetPackage)) {
            stopRun(AutomationStopReason.TARGET_UNSUPPORTED, "No safe locked WhatsApp package is available")
            return
        }
        if (!preflightRuntime(targetPackage)) return

        while (serviceScope.isActive && prefs.accessibilityBatchRunning &&
            prefs.runtimeAutomationBackend == AutomationBackend.SHIZUKU
        ) {
            AutomationScreenAwakeGuard.sync(
                this,
                prefs.keepScreenAwake && !prefs.accessibilityPaused
            )
            if (!runtimeHeartbeat(targetPackage)) {
                delay(ShizukuContinuityPolicy.RUNTIME_RECOVERY_POLL_MS)
                continue
            }

            if (prefs.accessibilityPaused) {
                // 3.4.1: leaving WhatsApp is an explicit user pause.
                updateNotification(
                    if (prefs.pausedBecauseOutsideTarget) {
                        "Paused after leaving WhatsApp • open Al-othmany Sender and tap Resume"
                    } else {
                        getString(R.string.shizuku_service_paused)
                    }
                )
                delay(PAUSED_POLL_MS)
                continue
            }
            if (prefs.accessibilityProcessedCount >= AutomationPolicy.BATCH_SIZE) {
                completeRun(AutomationStopReason.BATCH_LIMIT_REACHED, "Shizuku explicit-run limit reached")
                return
            }

            val sessionId = prefs.accessibilitySessionId
            if (sessionId.isNullOrBlank() || prefs.activeSessionId != sessionId) {
                stopRun(AutomationStopReason.SESSION_CHANGED, "Active session changed while Shizuku was running")
                return
            }

            var current = withContext(Dispatchers.IO) { app.repository.loadAutomationCurrent(sessionId) }
            if (current == null) {
                val next = withContext(Dispatchers.IO) { app.repository.loadAutomationNext(sessionId) }
                if (next == null) {
                    completeRun(AutomationStopReason.SESSION_COMPLETE, "All invitation links are complete")
                    return
                }
                current = withContext(Dispatchers.IO) { app.repository.markOpened(next.id) }
                if (current == null) {
                    stopRun(AutomationStopReason.OPEN_FAILED, "Could not reserve the next invitation link")
                    return
                }
                prefs.transitionAutomation(
                    AutomationStage.OPENING_LINK,
                    "Shizuku is opening invitation ${current.position + 1}",
                    true
                )
            }

            prefs.beginRuntimeLink(
                current.id,
                current.position,
                current.url,
                "SHIZUKU"
            )

            if (!prefs.communityTraversalActive &&
                (prefs.automationStage == AutomationStage.OPENING_LINK ||
                    prefs.automationStage == AutomationStage.WAITING_BEFORE_NEXT)
            ) {
                if (!openInvitation(current, targetPackage)) {
                    completeCurrent(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.OPEN_FAILED,
                        "Exact-user deep link launch failed; continuity recorded the failure and advanced to the next queued invitation"
                    )
                    continue
                }
                prefs.markAutomationLaunched()
                updateNotification(getString(R.string.shizuku_service_opened_link, current.position + 1))
                if (fastUiMode != FastUiMode.ACTIVE) delay(runtimeSpeed().postTapWaitMs.coerceAtMost(OPEN_SETTLE_MS))
            }

            if (!isTargetForeground(targetPackage)) {
                // Once this link was actually foreground in the locked WhatsApp, foreground loss
                // means user departure. Never force-open WhatsApp again in this branch.
                if (prefs.autoPauseOutsideWhatsApp && currentLaunchSawTargetForeground) {
                    if (shouldAutoPauseForUserExit(current, targetPackage)) {
                        updateNotification("Paused: return to the selected WhatsApp to continue")
                        delay(PAUSED_POLL_MS)
                        continue
                    }
                    prefs.transitionAutomation(
                        AutomationStage.WAITING_FOR_WHATSAPP,
                        "User left the selected WhatsApp; waiting without forcing it back"
                    )
                    delay(minOf(runtimeSpeed().stableScanMs, USER_EXIT_PROBE_INTERVAL_MS))
                    continue
                }

                // Recovery is reserved for an initial launch that never reached WhatsApp.
                val now = System.currentTimeMillis()
                if (foregroundWaitStartedAt == 0L) foregroundWaitStartedAt = now
                val waitAge = now - foregroundWaitStartedAt
                prefs.transitionAutomation(
                    AutomationStage.WAITING_FOR_WHATSAPP,
                    "Continuity engine is acquiring the selected WhatsApp window"
                )

                if (waitAge >= ShizukuContinuityPolicy.FOREGROUND_REOPEN_AFTER_MS &&
                    foregroundRecoveryAttempts < ShizukuContinuityPolicy.MAX_FOREGROUND_REOPEN_ATTEMPTS
                ) {
                    foregroundRecoveryAttempts += 1
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_FOREGROUND_RECOVER",
                        "attempt=$foregroundRecoveryAttempts; waitedMs=$waitAge; initial launch recovery"
                    )
                    if (openInvitation(current, targetPackage, forceResolvedActivity = true)) {
                        prefs.markAutomationLaunched()
                        foregroundWaitStartedAt = System.currentTimeMillis()
                        delay(ShizukuContinuityPolicy.FOREGROUND_RECOVERY_SETTLE_MS)
                        continue
                    }
                }

                if (waitAge >= ShizukuContinuityPolicy.FOREGROUND_ADVANCE_AFTER_MS) {
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_FOREGROUND_CONTINUITY_ADVANCE",
                        "target=$targetPackage; waitedMs=$waitAge; attempts=$foregroundRecoveryAttempts; probe=${lastForegroundProbeSummary.take(300)}"
                    )
                    completeCurrent(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.UNKNOWN_SCREEN,
                        "Selected WhatsApp never reached foreground after bounded initial-launch recovery; continuity advanced without guessing a click"
                    )
                    continue
                }
                delay(runtimeSpeed().stableScanMs)
                continue
            }
            currentLaunchSawTargetForeground = true
            outsideTargetCandidateStartedAtElapsed = 0L
            foregroundWaitStartedAt = 0L
            foregroundRecoveryAttempts = 0

            val snapshot = dumpSnapshot(current, targetPackage) ?: run {
                if (handleVisualProfileFallback(current, targetPackage)) continue
                if (fastUiMode != FastUiMode.ACTIVE) delay(runtimeSpeed().stableScanMs)
                continue
            }
            if (handleSnapshot(current, targetPackage, snapshot)) return
            if (fastUiMode != FastUiMode.ACTIVE) delay(runtimeSpeed().eventScanMs)
        }

        if (!prefs.accessibilityBatchRunning) stopSelf()
    }

    private fun fallbackToAccessibility(reason: String): Boolean {
        if (!AccessibilityStatus.isQuickJoinServiceConnectedLocally(this)) return false
        val prefs = app.preferences
        prefs.runtimeAutomationBackend = AutomationBackend.ACCESSIBILITY
        prefs.accessibilityQuickJoin = true
        prefs.transitionAutomation(
            AutomationStage.LOOKING_FOR_PREVIEW,
            "Shizuku fallback: $reason",
            resetRetries = true
        )
        RuntimeDiagnosticStore.append(this, "BACKEND_FALLBACK", "SHIZUKU -> ACCESSIBILITY; $reason")
        stopSelf()
        return true
    }

    private suspend fun preflightRuntime(targetPackage: String): Boolean {
        if (!ShizukuBridge.status().ready || !ShizukuBridge.ensureBound(this)) {
            if (app.preferences.hasValidRemoteSecureTarget()) {
                stopRun(
                    AutomationStopReason.SERVICE_DISABLED,
                    "Remote Secure requires the host Shizuku service; local Accessibility fallback is intentionally disabled"
                )
                return false
            }
            if (fallbackToAccessibility("Shizuku is not running, permission is missing, or UserService could not bind")) return false
            stopRun(AutomationStopReason.SERVICE_DISABLED, "Shizuku is not running, permission is missing, or UserService could not bind")
            return false
        }
        val freshFastUi = ShizukuBridge.fastResetUiAutomation(this)
        fastUiMode = FastUiMode.UNKNOWN
        fastUiFailureCount = 0
        fastUiSessionRecoveryAttempts = 0
        lastFastEventSequence = 0L
        currentLaunchEventBaseline = 0L
        RuntimeDiagnosticStore.append(
            this,
            "SHIZUKU_FAST_UI_RUN_RESET",
            "freshPersistentSession=$freshFastUi; status=${ShizukuBridge.fastUiStatus(this).take(180)}"
        )

        val userId = resolveAndroidUserId(targetPackage)
        if (userId == null) {
            val reason = "AL-thmany and the selected WhatsApp could not be proven to exist in the same Android user/profile"
            if (fallbackToAccessibility(reason)) return false
            stopRun(AutomationStopReason.TARGET_UNSUPPORTED, reason)
            return false
        }
        if (!resolveDisplaySize()) {
            val reason = "Shizuku could not read the active display bounds safely"
            if (fallbackToAccessibility(reason)) return false
            stopRun(AutomationStopReason.TARGET_UNSUPPORTED, reason)
            return false
        }
        val id = ShizukuBridge.execute(this, "id", 3_000)
        if (!id.success) {
            if (fallbackToAccessibility("Shizuku shell capability probe failed before queue execution")) return false
            stopRun(AutomationStopReason.SERVICE_DISABLED, "Shizuku shell capability probe failed before queue execution")
            return false
        }
        RuntimeDiagnosticStore.append(
            this,
            "SHIZUKU_PREFLIGHT",
            "target=$targetPackage; user=$userId; display=${displayWidth}x$displayHeight; shell=${id.output.trim().take(120)}"
        )
        lastCapabilityCheckElapsed = SystemClock.elapsedRealtime()
        return true
    }

    private suspend fun runtimeHeartbeat(targetPackage: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCapabilityCheckElapsed < ShizukuRuntimePolicy.CAPABILITY_RECHECK_MS) return true
        lastCapabilityCheckElapsed = now
        val status = ShizukuBridge.status()
        if (!status.ready || !ShizukuBridge.ensureBound(this, 2_000L)) {
            if (fallbackToAccessibility("Shizuku connection was lost; current link remains resumable")) return false
            lastCapabilityCheckElapsed = 0L
            updateNotification("Shizuku connection is temporarily unavailable; waiting to reconnect without consuming the current link")
            RuntimeDiagnosticStore.append(
                this,
                "SHIZUKU_RUNTIME_RECONNECT_WAIT",
                "binder=${status.binderAlive}; permission=${status.permissionGranted}; userService=${status.userServiceBound}; current link preserved"
            )
            return false
        }
        if (cachedTargetPackage != targetPackage || cachedAndroidUserId == null) {
            if (resolveAndroidUserId(targetPackage) == null) {
                val reason = "Target/profile capability changed during the run"
                if (fallbackToAccessibility(reason)) return false
                stopRun(AutomationStopReason.TARGET_UNSUPPORTED, reason)
                return false
            }
        }
        return true
    }

    /** Returns true only when the whole service should stop. */
    private suspend fun handleSnapshot(
        current: GroupLink,
        targetPackage: String,
        snapshot: ShizukuUiSnapshot
    ): Boolean {
        val prefs = app.preferences
        prefs.markRuntimePhase(
            when {
                snapshot.screenKind == AutomationScreenKind.LOADING -> LinkRuntimePhase.OPENING
                snapshot.screenKind == AutomationScreenKind.PREVIEW_ACTION -> LinkRuntimePhase.PREVIEW
                snapshot.screenKind in setOf(
                    AutomationScreenKind.JOIN_ACTION,
                    AutomationScreenKind.REQUEST_ACTION
                ) -> LinkRuntimePhase.ACTION_READY
                readPendingAction(current) != null -> LinkRuntimePhase.VERIFYING
                else -> prefs.runtimeLinkPhase
            },
            "SHIZUKU:${snapshot.screenKind.name}"
        )
        updateSnapshotStability(snapshot)

        if (snapshot.screenKind == AutomationScreenKind.RESTRICTED) {
            if (prefs.restrictionHandlingMode == RestrictionHandlingMode.STOP_RUN) {
                withContext(Dispatchers.IO) {
                    app.repository.markStatus(
                        current.id,
                        LinkStatus.FAILED,
                        LinkResultCode.RESTRICTED,
                        prefs.buildRuntimeAuditDetail(
                            "WhatsApp displayed a restriction/retry-later screen; no bypass attempted",
                            LinkResultCode.RESTRICTED.name,
                            "SHIZUKU"
                        )
                    )
                }
                stopRun(
                    AutomationStopReason.RESTRICTED_SCREEN,
                    "Restriction detected; user policy is Stop run"
                )
                return true
            }

            completeTerminalResult(
                current,
                LinkStatus.FAILED,
                LinkResultCode.RESTRICTED,
                "Restriction recorded for this link; no bypass attempted",
                snapshot,
                targetPackage
            )
            return false
        }

        if (maybeHandleCommunityTraversal(current, targetPackage, snapshot)) return false

        if (fastUiMode == FastUiMode.ACTIVE && handleFastUiTimeoutGuards(current, snapshot)) return false

        val pending = readPendingAction(current)

        // Work-profile parity: WhatsApp may render the post-request sheet while persistent
        // UiAutomation exposes only a subset of its semantics. Once this exact link has a
        // confirmed REQUEST input, either the explicit request-sent sentence or the visible
        // Cancel-request control is sufficient terminal evidence. This mirrors Accessibility
        // semantics and prevents the run from parking on the "request sent" sheet.
        val pendingApprovalVariant =
            pending == AccessibilityJoinAction.REQUEST &&
                snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isRequestApprovalNotice) &&
                snapshot.screenKind != AutomationScreenKind.REQUEST_ACTION

        if (pending == AccessibilityJoinAction.REQUEST &&
            (snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED ||
                snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isCancelRequest) ||
                AccessibilityJoinMatcher.isRequestSubmittedAcross(snapshot.labels.asSequence()) ||
                pendingApprovalVariant)
        ) {
            runtimeDiagnostic(
                current,
                "SHIZUKU_REQUEST_SUBMITTED_HANDOFF",
                "pending=REQUEST; screen=${snapshot.screenKind.name}; cancel=${snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isCancelRequest)}; approvalVariant=$pendingApprovalVariant; directNext=true"
            )
            completeTerminalResult(
                current,
                LinkStatus.REQUESTED,
                LinkResultCode.REQUEST_SENT,
                "Join request submission confirmed; advancing to the next invitation immediately",
                snapshot,
                targetPackage
            )
            return false
        }

        // Continuity parity: a freshly launched invite may resolve straight into an existing group
        // conversation (already a member) without exposing Preview/Join. Only trust this shortcut
        // after the persistent UiAutomation bridge observed a target-package event newer than the
        // launch baseline; this prevents the previous chat from being misclassified during a fast
        // zero-delay handoff.
        val launchAgeMs = if (currentLaunchElapsed > 0L)
            (SystemClock.elapsedRealtime() - currentLaunchElapsed).coerceAtLeast(0L) else Long.MAX_VALUE
        if (!prefs.communityTraversalActive && pending == null && snapshot.conversationSurface &&
            !snapshot.inviteContext && currentLaunchSawTargetEvent &&
            ShizukuContinuityPolicy.isDirectConversationResolution(
                prefs.automationStage, launchAgeMs, stableSnapshotScans
            )
        ) {
            runtimeDiagnostic(
                current,
                "SHIZUKU_DIRECT_CONVERSATION_HANDOFF",
                "launchAgeMs=$launchAgeMs; stable=$stableSnapshotScans; eventAfterLaunch=true; advancing immediately"
            )
            dismissKnownResultSurface(targetPackage, current, snapshot)
            completeCurrent(
                current,
                LinkStatus.JOINED,
                LinkResultCode.ALREADY_MEMBER,
                "Invite deep link resolved directly to a stable group conversation after a new WhatsApp event; exited the conversation before direct handoff"
            )
            return false
        }

        val confirm = if (pending != null) snapshot.confirmationNode(targetPackage) else null
        if (pending != null && confirm?.bounds != null) {
            val fingerprint = confirm.stableKey("CONFIRM")
            if (!actionConsensus(fingerprint)) return false
            if (tapNode(confirm.bounds, targetPackage, current, "CONFIRM")) {
                prefs.transitionAutomation(AutomationStage.VERIFYING_RESULT, "Confirmation pressed after stable Shizuku consensus")
                resetActionConsensus()
                if (fastUiMode != FastUiMode.ACTIVE) delay(ACTION_SETTLE_MS)
            }
            return false
        }

        if (pending == AccessibilityJoinAction.JOIN &&
            !snapshot.inviteContext &&
            pendingAgeMs() >= postJoinMinEvidenceMs() &&
            stableSnapshotScans >= postJoinStableScans() &&
            snapshot.strongPostActionSurface
        ) {
            if (prefs.accessibilityPendingTarget == AccessibilityInviteTarget.COMMUNITY &&
                prefs.communityTraversalEnabled
            ) {
                beginCommunityTraversalAfterJoin(current, targetPackage, snapshot)
            } else {
                if (snapshot.conversationSurface) {
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_FAST_CONVERSATION_HANDOFF",
                        "conversation verified; closing the joined surface before direct next-link handoff"
                    )
                }
                completeResultForCurrentOrSubgroup(
                    current,
                    LinkStatus.JOINED,
                    LinkResultCode.JOIN_ACTION_COMPLETED,
                    "Join verified; result surface close and direct next-link handoff started",
                    snapshot,
                    targetPackage
                )
            }
            return false
        }

        if (fastUiMode == FastUiMode.ACTIVE && maybeRetryFastPendingAction(current, targetPackage, snapshot, pending)) {
            return false
        }

        if (fastUiMode == FastUiMode.ACTIVE && shouldFastWatchdogAdvance(snapshot, pending)) {
            // Samsung can temporarily hide the UI tree immediately after a successful Join.
            // Prove the exact package/user Conversation activity before calling it a timeout.
            if (pending == AccessibilityJoinAction.JOIN &&
                probeJoinedConversationActivity(targetPackage, current)
            ) {
                exitConversationBeforeDirectHandoff(targetPackage, current)
                completeCurrent(
                    current,
                    LinkStatus.JOINED,
                    LinkResultCode.JOIN_ACTION_COMPLETED,
                    "Fast watchdog recovered a verified exact-user WhatsApp conversation after Join"
                )
                return false
            }

            runtimeDiagnostic(
                current,
                "SHIZUKU_FAST_WATCHDOG_HANDOFF",
                "pending=${pending?.name ?: "NONE"}; ageMs=${pendingAgeMs()}; stable=$stableSnapshotScans; nonLoading=true"
            )
            completeCurrent(
                current,
                LinkStatus.FAILED,
                LinkResultCode.ACTION_TIMEOUT,
                "Fast UI watchdog advanced after a stable non-loading post-action screen without verifiable success"
            )
            return false
        }

        val stageAge = (System.currentTimeMillis() - prefs.automationStageStartedAt).coerceAtLeast(0L)
        val decision = AutomationDecisionEngine.decide(
            stage = prefs.automationStage,
            screen = snapshot.screenKind,
            stageAgeMs = stageAge,
            retryCount = prefs.automationRetryCount,
            pendingAction = pending
        )

        when (decision.command) {
            AutomationCommand.CLICK_PREVIEW,
            AutomationCommand.CLICK_JOIN,
            AutomationCommand.CLICK_REQUEST -> {
                val action = when (decision.command) {
                    AutomationCommand.CLICK_PREVIEW -> AccessibilityJoinAction.PREVIEW
                    AutomationCommand.CLICK_REQUEST -> AccessibilityJoinAction.REQUEST
                    else -> AccessibilityJoinAction.JOIN
                }
                val selection = snapshot.actionSelection(action, targetPackage)
                val candidate = selection.candidate
                if (candidate?.node?.bounds == null) {
                    consecutiveAmbiguousActions += 1
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_ACTION_AMBIGUOUS",
                        "action=${action.name}; ambiguous=${selection.ambiguous}; best=${candidate?.score ?: -1}; runner=${selection.runnerUpScore}; count=$consecutiveAmbiguousActions"
                    )

                    // Samsung/Work can expose a partial semantic tree even while the real wide
                    // WhatsApp Join/Request control is visibly present. Do not park forever waiting
                    // for a node that this profile never publishes. Reuse the existing screenshot
                    // fallback only after two semantic misses, only for positive JOIN/REQUEST
                    // actions, and only after exact package/user foreground proof inside that helper.
                    if (fastUiMode == FastUiMode.ACTIVE &&
                        action in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST) &&
                        consecutiveAmbiguousActions >= 2 &&
                        handleVisualProfileFallback(current, targetPackage, action)
                    ) {
                        runtimeDiagnostic(
                            current,
                            "SHIZUKU_ACTION_VISUAL_RESCUE",
                            "action=${action.name}; semantic tree partial; guarded visual positive-action lane engaged"
                        )
                        consecutiveAmbiguousActions = 0
                        resetActionConsensus()
                        return false
                    }

                    if (consecutiveAmbiguousActions >= ShizukuRuntimePolicy.MAX_CONSECUTIVE_AMBIGUOUS_ACTIONS) {
                        runtimeDiagnostic(
                            current,
                            "SHIZUKU_AMBIGUOUS_CONTINUITY_ADVANCE",
                            "safe semantic action remained ambiguous; advancing without tapping"
                        )
                        completeCurrent(
                            current,
                            LinkStatus.FAILED,
                            LinkResultCode.UNKNOWN_SCREEN,
                            "No unambiguous safe WhatsApp action could be established; continuity skipped this link without a low-confidence tap"
                        )
                        return false
                    }
                    prefs.transitionAutomation(decision.nextStage, "Waiting for an unambiguous WhatsApp action node")
                    return false
                }
                consecutiveAmbiguousActions = 0
                val requiredScans = ShizukuRuntimePolicy.actionConsensusScans(
                    score = candidate.score,
                    runnerUpScore = selection.runnerUpScore,
                    clickable = candidate.node.clickable,
                    exactPackage = candidate.node.packageName == targetPackage,
                    ambiguous = selection.ambiguous
                )

                val consensusReady = actionConsensus(candidate.fingerprint, requiredScans)
                val exactPositiveActionRescue =
                    fastUiMode == FastUiMode.ACTIVE &&
                        action in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST) &&
                        snapshot.inviteContext &&
                        !selection.ambiguous &&
                        candidate.node.enabled &&
                        candidate.node.packageName == targetPackage &&
                        candidate.score >= ShizukuRuntimePolicy.MIN_ACTION_SCORE &&
                        ((action == AccessibilityJoinAction.JOIN &&
                            snapshot.screenKind == AutomationScreenKind.JOIN_ACTION) ||
                         (action == AccessibilityJoinAction.REQUEST &&
                            snapshot.screenKind == AutomationScreenKind.REQUEST_ACTION)) &&
                        stageAge >= maxOf(60L, runtimeSpeed().eventScanMs)

                if (!consensusReady && !exactPositiveActionRescue) return false

                if (exactPositiveActionRescue && !consensusReady) {
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_FAST_SEMANTIC_RESCUE",
                        "action=${action.name}; score=${candidate.score}; exactPackage=true; " +
                            "screen=${snapshot.screenKind.name}; consensus fingerprint was unstable, tapping guarded semantic bounds"
                    )
                } else if (requiredScans == 1) {
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_FAST_ACTION",
                        "action=${action.name}; score=${candidate.score}; runner=${selection.runnerUpScore}; oneScan=true"
                    )
                }

                val tapped = tapNode(candidate.node.bounds, targetPackage, current, action.name)
                if (tapped) {
                    if (action == AccessibilityJoinAction.JOIN || action == AccessibilityJoinAction.REQUEST) {
                        prefs.setAccessibilityPending(current.id, action.name, snapshot.inviteTarget)
                    }
                    prefs.transitionAutomation(decision.nextStage, decision.diagnostic)
                    resetActionConsensus()
                    if (fastUiMode != FastUiMode.ACTIVE) {
                        delay(ACTION_SETTLE_MS)
                        // Command UIAutomator is the slow compatibility path. On WhatsApp builds
                        // whose joined group opens the well-known Conversation activity, prove the
                        // exact package/user cheaply and hand off immediately instead of spawning
                        // another multi-second hierarchy dump. Unknown activities still return to
                        // semantic UI verification, so this shortcut never guesses.
                        if (action == AccessibilityJoinAction.JOIN &&
                            snapshot.inviteTarget != AccessibilityInviteTarget.COMMUNITY &&
                            probeJoinedConversationActivity(targetPackage, current)
                        ) {
                            exitConversationBeforeDirectHandoff(targetPackage, current)
                            completeCurrent(
                                current,
                                LinkStatus.JOINED,
                                LinkResultCode.JOIN_ACTION_COMPLETED,
                                "Exact-user WhatsApp Conversation activity verified after Join; exited before next invitation"
                            )
                        }
                    }
                }
            }

            AutomationCommand.COMPLETE_JOINED -> {
                if (snapshot.inviteTarget == AccessibilityInviteTarget.COMMUNITY && prefs.communityTraversalEnabled) {
                    val open = snapshot.openCommunityNode(targetPackage)
                    if (open?.bounds != null) {
                        val key = open.stableKey("OPEN_COMMUNITY")
                        if (actionConsensus(key) && tapNode(open.bounds, targetPackage, current, "OPEN_COMMUNITY")) {
                            if (!prefs.communityTraversalActive) prefs.beginCommunityTraversal(current.id)
                            setCommunityStage(CommunityTraversalStage.ENTERING_COMMUNITY)
                            prefs.clearAccessibilityPending()
                            resetActionConsensus()
                            delay(ACTION_SETTLE_MS)
                        }
                    } else {
                        completeCurrent(current, LinkStatus.SKIPPED, LinkResultCode.ALREADY_MEMBER, decision.diagnostic)
                    }
                } else {
                    completeResultForCurrentOrSubgroup(
                        current, LinkStatus.JOINED, LinkResultCode.JOIN_ACTION_COMPLETED,
                        decision.diagnostic, snapshot, targetPackage
                    )
                }
            }
            AutomationCommand.COMPLETE_REQUESTED -> completeTerminalResult(
                current, LinkStatus.REQUESTED, LinkResultCode.REQUEST_SENT, decision.diagnostic, snapshot, targetPackage
            )
            AutomationCommand.COMPLETE_ALREADY_MEMBER -> {
                if (!app.preferences.communityTraversalActive &&
                    snapshot.inviteTarget == AccessibilityInviteTarget.COMMUNITY &&
                    prefs.communityTraversalEnabled
                ) {
                    val open = snapshot.openCommunityNode(targetPackage) ?: snapshot.actionNode(
                        AccessibilityJoinAction.PREVIEW,
                        targetPackage
                    )
                    if (open?.bounds != null) {
                        val key = open.stableKey("OPEN_COMMUNITY_ALREADY_MEMBER")
                        if (actionConsensus(key) && tapNode(open.bounds, targetPackage, current, "OPEN_COMMUNITY_ALREADY_MEMBER")) {
                            prefs.beginCommunityTraversal(current.id)
                            setCommunityStage(CommunityTraversalStage.ENTERING_COMMUNITY)
                            prefs.clearAccessibilityPending()
                            resetActionConsensus()
                            delay(ACTION_SETTLE_MS)
                        }
                    } else {
                        completeCurrent(current, LinkStatus.SKIPPED, LinkResultCode.ALREADY_MEMBER, decision.diagnostic)
                    }
                } else {
                    completeTerminalResult(
                        current, LinkStatus.SKIPPED, LinkResultCode.ALREADY_MEMBER, decision.diagnostic, snapshot, targetPackage
                    )
                }
            }
            AutomationCommand.COMPLETE_GROUP_FULL -> completeTerminalResult(
                current, LinkStatus.SKIPPED, LinkResultCode.GROUP_FULL, decision.diagnostic, snapshot, targetPackage
            )
            AutomationCommand.COMPLETE_INVALID -> completeTerminalResult(
                current, LinkStatus.SKIPPED, LinkResultCode.INVALID_OR_EXPIRED, decision.diagnostic, snapshot, targetPackage
            )
            AutomationCommand.COMPLETE_REMOVED -> completeTerminalResult(
                current, LinkStatus.SKIPPED, LinkResultCode.REMOVED_OR_BANNED, decision.diagnostic, snapshot, targetPackage
            )
            AutomationCommand.COMPLETE_FAILED -> completeTerminalResult(
                current, LinkStatus.FAILED, LinkResultCode.WHATSAPP_REJECTED, decision.diagnostic, snapshot, targetPackage
            )
            AutomationCommand.STOP_RESTRICTED -> {
                if (prefs.restrictionHandlingMode == RestrictionHandlingMode.STOP_RUN) {
                    stopRun(AutomationStopReason.RESTRICTED_SCREEN, decision.diagnostic)
                    return true
                }
                completeTerminalResult(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.RESTRICTED,
                    "${decision.diagnostic}; recorded and continued",
                    snapshot,
                    targetPackage
                )
                return false
            }
            AutomationCommand.STOP_UNKNOWN -> {
                if (attemptUnknownRecoveryOnce(current, targetPackage, snapshot)) {
                    return false
                }
                runtimeDiagnostic(current, "SHIZUKU_UNKNOWN_CONTINUITY_ADVANCE", decision.diagnostic)
                completeCurrent(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.UNKNOWN_SCREEN,
                    "${decision.diagnostic}. Scan/Exit/Back/one-reopen recovery exhausted; skipped safely"
                )
                return false
            }
            AutomationCommand.RETRY -> {
                prefs.incrementAutomationRetry()
                prefs.transitionAutomation(decision.nextStage, decision.diagnostic)
            }
            AutomationCommand.WAIT -> if (prefs.automationStage != decision.nextStage) {
                prefs.transitionAutomation(decision.nextStage, decision.diagnostic)
            }
        }
        return false
    }

    private fun updateSnapshotStability(snapshot: ShizukuUiSnapshot) {
        val signature = snapshot.nodes.joinToString("\u001f") {
            "${it.packageName}|${it.resourceId}|${it.text}|${it.contentDescription}|${it.bounds}"
        }.hashCode()
        stableSnapshotScans = if (signature == lastSnapshotSignature) stableSnapshotScans + 1 else 1
        lastSnapshotSignature = signature
    }

    private fun actionConsensus(
        fingerprint: String,
        requiredScans: Int = ShizukuRuntimePolicy.ACTION_CONSENSUS_SCANS
    ): Boolean {
        if (fingerprint == lastActionFingerprint) stableActionScans += 1
        else {
            lastActionFingerprint = fingerprint
            stableActionScans = 1
        }
        return ShizukuRuntimePolicy.consensusReached(stableActionScans, requiredScans)
    }

    private fun resetActionConsensus() {
        lastActionFingerprint = null
        stableActionScans = 0
    }

    private fun shouldProbeRequestTerminal(
        current: GroupLink,
        snapshot: ShizukuUiSnapshot?,
        eventTriggered: Boolean
    ): Boolean {
        if (readPendingAction(current) != AccessibilityJoinAction.REQUEST) return false
        val age = pendingAgeMs()
        if (age < REQUEST_TERMINAL_PROBE_MIN_AGE_MS && !eventTriggered) return false
        if (snapshot?.screenKind in setOf(
                AutomationScreenKind.REQUEST_SUBMITTED,
                AutomationScreenKind.REQUEST_ACTION,
                AutomationScreenKind.LOADING,
                AutomationScreenKind.RESTRICTED
            )
        ) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastRequestTerminalProbeAtElapsed < REQUEST_TERMINAL_PROBE_COOLDOWN_MS) return false
        lastRequestTerminalProbeAtElapsed = now
        return true
    }

    private suspend fun dumpSnapshot(current: GroupLink, targetPackage: String): ShizukuUiSnapshot? {
        var forceCommandDump = false
        var profileCompatibilityProbe = false
        if (fastUiMode != FastUiMode.DISABLED) {
            val frame = if (fastUiMode == FastUiMode.ACTIVE) {
                ShizukuBridge.waitAndSnapshot(
                    this,
                    targetPackage,
                    lastFastEventSequence,
                    fastFrameTimeoutMs(current).toInt(),
                    FAST_UI_MAX_NODES
                )
            } else null
            val fast = frame?.result ?: ShizukuBridge.fastSnapshot(this, targetPackage, FAST_UI_MAX_NODES)
            if (frame != null && frame.sequence > lastFastEventSequence) {
                lastFastEventSequence = frame.sequence
            }
            if (frame != null && frame.eventTriggered && frame.sequence > currentLaunchEventBaseline) {
                currentLaunchSawTargetEvent = true
            }
            lastFastUiDetail = buildString {
                append(fast.state).append(':').append(fast.detail)
                if (frame != null) append("; event=").append(frame.eventTriggered).append("; seq=").append(frame.sequence)
            }
            if (fast.success) {
                val snapshot = ShizukuUiDumpParser.parse(fast.xml)
                val targetVisible = snapshot.nodes.any { it.packageName == targetPackage }
                if (targetVisible) {
                    if (fastUiMode != FastUiMode.ACTIVE) {
                        RuntimeDiagnosticStore.append(
                            this,
                            "SHIZUKU_FAST_UI_ACTIVE",
                            "Persistent UiAutomation compact parity path enabled; ${fast.detail.take(220)}"
                        )
                    }
                    fastUiMode = FastUiMode.ACTIVE
                    if (frame == null) armFastEventSequence(targetPackage)
                    if (!fastPresetLogged) {
                        fastPresetLogged = true
                        RuntimeDiagnosticStore.append(
                            this,
                            "SHIZUKU_FAST_UI_PRESET",
                            "event=14ms; compactTree=true; visualWorkFallback=true; stable=36ms; poll=95ms; watchdog=40ms/1000ms; click=60ms; gesture=72ms; result=72ms; next=0ms"
                        )
                    }
                    fastUiFailureCount = 0
                    fastUiSessionRecoveryAttempts = 0
                    consecutiveDumpFailures = 0
                    emptyDumpStartedAt = 0L
                    fastUiNoRootStartedAtElapsed = 0L
                    fastUiTargetHiddenStartedAtElapsed = 0L
                    cachedAndroidUserId?.let { recordForegroundLease(targetPackage, it) }
                    val requestTerminalProbe = shouldProbeRequestTerminal(
                        current, snapshot, frame?.eventTriggered == true
                    )
                    if (!requestTerminalProbe) return snapshot
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_REQUEST_TERMINAL_PROBE",
                        "persistent screen=${snapshot.screenKind.name}; pendingAgeMs=${pendingAgeMs()}; event=${frame?.eventTriggered == true}; shellProbe=true"
                    )
                    // Intentionally fall through to one request-only command dump. This never
                    // runs on the normal Join hot path and gives Work Profile a second semantic
                    // source when the persistent tree is partial after Request to join.
                    forceCommandDump = true
                } else {
                    // A successful persistent snapshot from AL-thmany/system UI while WhatsApp is
                    // transitioning is NOT a UiAutomation failure. Older builds counted four of
                    // these 14–36 ms transition frames and immediately fell into heavyweight
                    // `uiautomator dump`, which made Work Profile feel much slower than Accessibility.
                    // Arm the persistent event path and wait for the target-package event instead.
                    val now = SystemClock.elapsedRealtime()
                    if (fastUiTargetHiddenStartedAtElapsed == 0L) fastUiTargetHiddenStartedAtElapsed = now
                    val hiddenAge = now - fastUiTargetHiddenStartedAtElapsed
                    if (fastUiMode == FastUiMode.UNKNOWN) {
                        fastUiMode = FastUiMode.ACTIVE
                        armFastEventSequence(targetPackage)
                        runtimeDiagnostic(
                            current,
                            "SHIZUKU_FAST_UI_ARMED",
                            "persistent bridge healthy before target root; event-first transition wait enabled"
                        )
                    }
                    val requestTerminalProbe = shouldProbeRequestTerminal(
                        current, null, frame?.eventTriggered == true
                    )
                    val profileProbe = ShizukuContinuityPolicy.shouldProbeProfileCompatibleTree(
                        hiddenAge,
                        profileCompatCommandProbes
                    )
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_FAST_UI_TARGET_HIDDEN",
                        "ageMs=$hiddenAge; requestProbe=$requestTerminalProbe; " +
                            "profileProbe=$profileProbe; shellFallback=${requestTerminalProbe || profileProbe}; ${lastFastUiDetail.take(240)}"
                    )
                    if (requestTerminalProbe || profileProbe) {
                        // Request-result semantics are the only transition where a single bounded
                        // shell probe was previously useful. Samsung Work Profile also needs this
                        // compatibility path when persistent UiAutomation remains attached to the
                        // owner Launcher. The command hierarchy still must contain exact WhatsApp
                        // package nodes before the guarded action engine can click anything.
                        if (profileProbe) {
                            profileCompatCommandProbes += 1
                            profileCompatibilityProbe = true
                            runtimeDiagnostic(
                                current,
                                "SHIZUKU_PROFILE_COMPAT_PROBE",
                                "source=TARGET_HIDDEN; ageMs=$hiddenAge; attempt=$profileCompatCommandProbes"
                            )
                            // Screenshot capture is persistent and local; try it before spawning a
                            // multi-second command UIAutomator process. If it safely dispatched the
                            // wide WhatsApp action, return to the outer verification loop now.
                            if (handleVisualProfileFallback(current, targetPackage)) return null
                        }
                        forceCommandDump = true
                    } else {
                        if (hiddenAge >= ShizukuContinuityPolicy.TARGET_HIDDEN_FOREGROUND_REPROBE_MS) {
                            // The optimistic exact-user launch lease may still point at AL-thmany.
                            // Expire it once, so the outer loop re-proves the resumed uNN WhatsApp
                            // activity and can reopen the same link if needed.
                            invalidateForegroundLease()
                        }
                        return null
                    }
                }
            } else {
                if (fast.state == "NO_ROOT" && fastUiMode != FastUiMode.DISABLED) {
                    // A null root during Activity/Profile handoff is a normal transient state, not a
                    // reason to spawn command-based UIAutomator. Keep one persistent event session
                    // hot from the first frame, exactly like an Accessibility event loop.
                    if (fastUiMode == FastUiMode.UNKNOWN) {
                        fastUiMode = FastUiMode.ACTIVE
                        armFastEventSequence(targetPackage)
                        runtimeDiagnostic(
                            current,
                            "SHIZUKU_FAST_UI_ARMED",
                            "persistent bridge connected with NO_ROOT; waiting for target event without shell dump"
                        )
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (fastUiNoRootStartedAtElapsed == 0L) fastUiNoRootStartedAtElapsed = now
                    val age = now - fastUiNoRootStartedAtElapsed
                    val profileProbe = ShizukuContinuityPolicy.shouldProbeProfileCompatibleTree(
                        age,
                        profileCompatCommandProbes
                    )
                    if (profileProbe) {
                        profileCompatCommandProbes += 1
                        profileCompatibilityProbe = true
                        runtimeDiagnostic(
                            current,
                            "SHIZUKU_PROFILE_COMPAT_PROBE",
                            "source=NO_ROOT; ageMs=$age; attempt=$profileCompatCommandProbes"
                        )
                        if (handleVisualProfileFallback(current, targetPackage)) return null
                        forceCommandDump = true
                    }
                    if (age >= ShizukuContinuityPolicy.NO_ROOT_ADVANCE_AFTER_MS) {
                        runtimeDiagnostic(current, "SHIZUKU_NO_ROOT_CONTINUITY_ADVANCE", "ageMs=$age; fast=${lastFastUiDetail.take(220)}")
                        fastUiNoRootStartedAtElapsed = 0L
                        completeCurrent(
                            current,
                            LinkStatus.FAILED,
                            LinkResultCode.UNKNOWN_SCREEN,
                            "Persistent UiAutomation exposed no active WhatsApp root within the continuity budget; advanced without guessing a click"
                        )
                        return null
                    }
                    val requestTerminalProbe = shouldProbeRequestTerminal(
                        current, null, frame?.eventTriggered == true
                    )
                    if (requestTerminalProbe) {
                        runtimeDiagnostic(
                            current,
                            "SHIZUKU_REQUEST_TERMINAL_PROBE",
                            "persistent=NO_ROOT; pendingAgeMs=${pendingAgeMs()}; event=${frame?.eventTriggered == true}; shellProbe=true"
                        )
                        forceCommandDump = true
                    }
                    if (!forceCommandDump) return null
                }

                // Only actual bridge errors/unavailability count toward disabling the persistent
                // path. NO_ROOT and target-hidden transition frames are handled above.
                if (fast.state != "NO_ROOT") fastUiFailureCount += 1
                if (!forceCommandDump && (fast.unavailable || fastUiFailureCount >= FAST_UI_DISABLE_AFTER_FAILURES)) {
                    if (fastUiSessionRecoveryAttempts < FAST_UI_SESSION_RECOVERY_MAX) {
                        fastUiSessionRecoveryAttempts += 1
                        val recovered = ShizukuBridge.fastResetUiAutomation(this)
                        runtimeDiagnostic(
                            current,
                            "SHIZUKU_FAST_UI_SELF_HEAL",
                            "attempt=$fastUiSessionRecoveryAttempts; recovered=$recovered; state=${fast.state}; detail=${fast.detail.take(160)}"
                        )
                        if (recovered) {
                            fastUiMode = FastUiMode.UNKNOWN
                            fastUiFailureCount = 0
                            lastFastEventSequence = 0L
                            currentLaunchEventBaseline = 0L
                            armFastEventSequence(targetPackage)
                            return null
                        }
                    }
                    if (fastUiMode != FastUiMode.DISABLED) {
                        RuntimeDiagnosticStore.append(
                            this,
                            "SHIZUKU_FAST_UI_FALLBACK",
                            "Persistent UiAutomation unavailable/error; falling back to command dump; ${fast.state}:${fast.detail.take(220)}"
                        )
                    }
                    fastUiMode = FastUiMode.DISABLED
                } else if (!forceCommandDump && fastUiMode == FastUiMode.ACTIVE) {
                    return null
                }
            }
        }

        val commandDumpNow = SystemClock.elapsedRealtime()
        if (commandDumpNow < commandDumpSuppressedUntilElapsed) {
            runtimeDiagnostic(
                current,
                "SHIZUKU_COMMAND_DUMP_COOLDOWN",
                "remainingMs=${commandDumpSuppressedUntilElapsed - commandDumpNow}; " +
                    "persistent/visual/Back recovery preferred over repeatedly spawning killed uiautomator"
            )
            val remaining = (commandDumpSuppressedUntilElapsed - commandDumpNow).coerceAtLeast(1L)
            delay(minOf(COMMAND_DUMP_COOLDOWN_POLL_MS, remaining))
            return handleDumpFailure(
                current,
                targetPackage,
                "mode=COMMAND_COOLDOWN; command dump suppressed; remainingMs=$remaining",
                realCommandKill = false
            )
        }

        var result = ShizukuBridge.execute(
            this,
            "rm -f /data/local/tmp/althmany_ui.xml; " +
                "/system/bin/uiautomator dump --compressed /data/local/tmp/althmany_ui.xml 2>&1; " +
                "rc=$?; if [ -s /data/local/tmp/althmany_ui.xml ]; then cat /data/local/tmp/althmany_ui.xml; " +
                "else echo __AL_UI_FILE_EMPTY__; fi; rm -f /data/local/tmp/althmany_ui.xml; exit \$rc",
            UI_DUMP_TIMEOUT_MS
        )
        var xml = result.output
        // exit=137/SIGKILL is a hard failure on this Android runtime. Spawning the same
        // UIAutomator command again only burns another second and delays the screenshot rescue.
        val commandDumpKilled = result.exitCode == 137 || xml.contains("Killed", ignoreCase = true)
        if ((!result.success || !xml.contains("<hierarchy")) && !commandDumpKilled) {
            delay(COMMAND_DUMP_COMPAT_RETRY_MS)
            result = ShizukuBridge.execute(
                this,
                "rm -f /data/local/tmp/althmany_ui_retry.xml; " +
                    "/system/bin/uiautomator dump /data/local/tmp/althmany_ui_retry.xml 2>&1; " +
                    "rc=$?; if [ -s /data/local/tmp/althmany_ui_retry.xml ]; then cat /data/local/tmp/althmany_ui_retry.xml; " +
                    "else echo __AL_UI_RETRY_EMPTY__; fi; rm -f /data/local/tmp/althmany_ui_retry.xml; exit \$rc",
                UI_DUMP_TIMEOUT_MS
            )
            xml = result.output
        }
        if (!result.success || !xml.contains("<hierarchy")) {
            val realCommandKill =
                result.exitCode == 137 || xml.contains("Killed", ignoreCase = true)
            return handleDumpFailure(
                current,
                targetPackage,
                "exit=${result.exitCode}; ${xml.take(320)}; mode=COMMAND_COMPAT",
                realCommandKill = realCommandKill
            )
        }
        val snapshot = ShizukuUiDumpParser.parse(xml)
        val targetVisible = snapshot.nodes.any { it.packageName == targetPackage }
        if (!targetVisible) {
            return handleDumpFailure(current, targetPackage, "hierarchy exists but selected package nodes are hidden; fast=$lastFastUiDetail")
        }
        if (profileCompatibilityProbe) {
            // The standard command hierarchy proved that WhatsApp is visible while the persistent
            // root stayed on Launcher/SystemUI. Keep this run on the compatible semantic path;
            // clicks remain exact-node/bounds guarded and no blind coordinate is introduced.
            fastUiMode = FastUiMode.DISABLED
            RuntimeDiagnosticStore.append(
                this,
                "SHIZUKU_PROFILE_COMPAT_ACTIVE",
                "Command UI hierarchy proved $targetPackage after persistent target/root mismatch"
            )
        }
        consecutiveDumpFailures = 0
        emptyDumpStartedAt = 0L
        cachedAndroidUserId?.let { recordForegroundLease(targetPackage, it) }
        return snapshot
    }

    private suspend fun handleDumpFailure(
        current: GroupLink,
        targetPackage: String,
        detail: String,
        realCommandKill: Boolean = false
    ): ShizukuUiSnapshot? {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (lastDumpFailureCountedAtElapsed > 0L) {
            val sinceLast = nowElapsed - lastDumpFailureCountedAtElapsed
            if (sinceLast in 0 until DUMP_FAILURE_MIN_INTERVAL_MS) {
                delay(DUMP_FAILURE_MIN_INTERVAL_MS - sinceLast)
                return null
            }
        }
        lastDumpFailureCountedAtElapsed = SystemClock.elapsedRealtime()

        if (emptyDumpStartedAt == 0L) emptyDumpStartedAt = System.currentTimeMillis()
        consecutiveDumpFailures += 1
        val age = System.currentTimeMillis() - emptyDumpStartedAt

        runtimeDiagnostic(
            current,
            "SHIZUKU_UI_DUMP_EMPTY",
            "ageMs=$age; count=$consecutiveDumpFailures; realKill=$realCommandKill; $detail"
        )

        if (realCommandKill) {
            commandDumpSuppressedUntilElapsed = maxOf(
                commandDumpSuppressedUntilElapsed,
                SystemClock.elapsedRealtime() + COMMAND_DUMP_KILL_COOLDOWN_MS
            )

            if (commandDumpKillRecoveryAttempts < 1) {
                commandDumpKillRecoveryAttempts += 1
                val recovered = ShizukuBridge.fastResetUiAutomation(this)
                runtimeDiagnostic(
                    current,
                    "SHIZUKU_UI_DUMP_KILL_SELF_HEAL",
                    "attempt=$commandDumpKillRecoveryAttempts; recovered=$recovered; exactUser=${cachedAndroidUserId ?: -1}"
                )
                if (recovered) {
                    fastUiMode = FastUiMode.UNKNOWN
                    fastUiFailureCount = 0
                    fastUiSessionRecoveryAttempts = 0
                    lastFastEventSequence = 0L
                    currentLaunchEventBaseline = 0L
                    emptyDumpStartedAt = 0L
                    consecutiveDumpFailures = 0
                    lastDumpFailureCountedAtElapsed = 0L
                    userServiceRestartAttempts = 0
                    commandDumpSuppressedUntilElapsed = 0L
                    armFastEventSequence(targetPackage)
                    delay(80L)
                    return null
                }
            }

            if (userServiceRestartAttempts < USER_SERVICE_RESTART_MAX) {
                userServiceRestartAttempts += 1
                val restarted = ShizukuBridge.restartUserService(this)
                runtimeDiagnostic(
                    current,
                    "SHIZUKU_USER_SERVICE_RESTART",
                    "attempt=$userServiceRestartAttempts; restarted=$restarted; user=${cachedAndroidUserId ?: -1}"
                )
                if (restarted) {
                    fastUiMode = FastUiMode.UNKNOWN
                    fastUiFailureCount = 0
                    fastUiSessionRecoveryAttempts = 0
                    lastFastEventSequence = 0L
                    currentLaunchEventBaseline = 0L
                    commandDumpSuppressedUntilElapsed = 0L
                    emptyDumpStartedAt = 0L
                    consecutiveDumpFailures = 0
                    lastDumpFailureCountedAtElapsed = 0L
                    delay(120L)
                    return null
                }
            }
        }

        val pending = readPendingAction(current) ?: visualExpectedAction
        val actionAge = when {
            visualActionTappedAtElapsed > 0L ->
                (SystemClock.elapsedRealtime() - visualActionTappedAtElapsed).coerceAtLeast(0L)
            app.preferences.automationStage == AutomationStage.VERIFYING_RESULT && lastInputAtElapsed > 0L ->
                (SystemClock.elapsedRealtime() - lastInputAtElapsed).coerceAtLeast(0L)
            else -> Long.MAX_VALUE
        }

        if (app.preferences.automationStage == AutomationStage.VERIFYING_RESULT &&
            actionAge < POST_ACTION_RESULT_GRACE_MS
        ) {
            delay(minOf(VERIFY_RESULT_POLL_MS, (POST_ACTION_RESULT_GRACE_MS - actionAge).coerceAtLeast(1L)))
            return null
        }

        if (age < ShizukuContinuityPolicy.UI_TREE_ADVANCE_AFTER_MS ||
            consecutiveDumpFailures < ShizukuContinuityPolicy.MAX_UI_TREE_FAILURES
        ) {
            return null
        }

        if (pending == AccessibilityJoinAction.JOIN &&
            probeJoinedConversationActivity(targetPackage, current)
        ) {
            exitConversationBeforeDirectHandoff(targetPackage, current)
            emptyDumpStartedAt = 0L
            consecutiveDumpFailures = 0
            lastDumpFailureCountedAtElapsed = 0L
            commandDumpKillRecoveryAttempts = 0
            userServiceRestartAttempts = 0
            completeCurrent(
                current,
                LinkStatus.JOINED,
                LinkResultCode.JOIN_ACTION_COMPLETED,
                "UI tree unavailable, but exact-user WhatsApp Conversation proved Join success"
            )
            return null
        }

        val userId = cachedAndroidUserId ?: resolveAndroidUserId(targetPackage)
        val exactForeground =
            (userId != null && foregroundLeaseValid(targetPackage, userId)) ||
                isTargetForeground(targetPackage, forceProbe = true)

        if (!exactForeground) {
            runtimeDiagnostic(
                current,
                "SHIZUKU_UI_TREE_FOREGROUND_DEFER",
                "ageMs=$age; count=$consecutiveDumpFailures; treeFailureIsNotUserExit=true"
            )
            emptyDumpStartedAt = 0L
            consecutiveDumpFailures = 0
            lastDumpFailureCountedAtElapsed = 0L
            commandDumpKillRecoveryAttempts = 0
            return null
        }

        if (probeJoinedConversationActivity(targetPackage, current)) {
            exitConversationBeforeDirectHandoff(targetPackage, current)
            completeCurrent(
                current,
                LinkStatus.JOINED,
                LinkResultCode.JOIN_ACTION_COMPLETED,
                "Final exact-user conversation proof recovered Join before UI-tree escape"
            )
            return null
        }

        val backSent = pressResultBack(
            targetPackage,
            current,
            if (pending == AccessibilityJoinAction.REQUEST) {
                "UI_TREE_FAILURE_REQUEST_BACK_HANDOFF"
            } else {
                "UI_TREE_FAILURE_BACK_HANDOFF"
            }
        )

        val reset = ShizukuBridge.fastResetUiAutomation(this)
        if (reset) {
            fastUiMode = FastUiMode.UNKNOWN
            fastUiFailureCount = 0
            fastUiSessionRecoveryAttempts = 0
            lastFastEventSequence = 0L
            currentLaunchEventBaseline = 0L
            userServiceRestartAttempts = 0
            commandDumpSuppressedUntilElapsed = 0L
        }

        runtimeDiagnostic(
            current,
            "SHIZUKU_UI_TREE_BACK_HANDOFF",
            "back=$backSent; persistentReset=$reset; pending=${pending?.name ?: "NONE"}; " +
                "actionAgeMs=$actionAge; exactForeground=true; pause=false; next=true"
        )

        emptyDumpStartedAt = 0L
        consecutiveDumpFailures = 0
        lastDumpFailureCountedAtElapsed = 0L
        commandDumpKillRecoveryAttempts = 0
        completeCurrent(
            current,
            LinkStatus.FAILED,
            LinkResultCode.UNKNOWN_SCREEN,
            "Unreadable WhatsApp surface escaped after bounded verification; no random success counted"
        )
        return null
    }

    private suspend fun attemptUnknownRecoveryOnce(
        current: GroupLink,
        targetPackage: String,
        snapshot: ShizukuUiSnapshot
    ): Boolean {
        if (readPendingAction(current) != null) return false
        if (!app.preferences.recordRecoveryReopen(current.id)) return false

        app.preferences.markRuntimePhase(
            LinkRuntimePhase.EXITING,
            "SHIZUKU:UNKNOWN_RECOVERY"
        )
        if (snapshot.inviteContext || snapshot.conversationSurface) {
            dismissKnownResultSurface(targetPackage, current, snapshot)
        }

        val reopened = openInvitation(
            current,
            targetPackage,
            forceResolvedActivity = true
        )
        if (reopened) {
            app.preferences.markAutomationLaunched()
            app.preferences.markRuntimePhase(
                LinkRuntimePhase.OPENING,
                "SHIZUKU:RECOVERY_REOPENED"
            )
            runtimeDiagnostic(
                current,
                "SHIZUKU_UNKNOWN_REOPEN",
                "one bounded exact-user/package Deep Link recovery dispatched"
            )
        }
        return reopened
    }

    private suspend fun beginCommunityTraversalAfterJoin(
        current: GroupLink,
        targetPackage: String,
        snapshot: ShizukuUiSnapshot
    ) {
        val prefs = app.preferences
        if (!prefs.communityTraversalActive) prefs.beginCommunityTraversal(current.id)
        prefs.clearAccessibilityPending()
        resetCommunityEvidence()
        runtimeDiagnostic(
            current,
            "SHIZUKU_COMMUNITY_START",
            "home=${snapshot.communityHomeSurface}; conversation=${snapshot.conversationSurface}"
        )
        when {
            snapshot.communityHomeSurface -> {
                setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                handleCommunityHome(current, targetPackage, snapshot)
            }
            snapshot.conversationSurface -> {
                setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                sendBack(targetPackage, current, "COMMUNITY_JOIN_RETURN")
            }
            else -> setCommunityStage(CommunityTraversalStage.ENTERING_COMMUNITY)
        }
    }

    /** Returns true when the community state machine consumes this snapshot. */
    private suspend fun maybeHandleCommunityTraversal(
        current: GroupLink,
        targetPackage: String,
        snapshot: ShizukuUiSnapshot
    ): Boolean {
        val prefs = app.preferences
        if (!prefs.communityTraversalEnabled || !prefs.communityTraversalActive) return false
        if (prefs.communityTraversalParentLinkId != current.id) {
            prefs.clearCommunityTraversal()
            return false
        }

        val pending = readPendingAction(current)
        when (prefs.communityTraversalStage) {
            CommunityTraversalStage.INACTIVE -> return false

            CommunityTraversalStage.ENTERING_COMMUNITY -> {
                if (snapshot.communityHomeSurface) {
                    setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                    handleCommunityHome(current, targetPackage, snapshot)
                    return true
                }
                if (snapshot.inviteContext) return false
                if (snapshot.conversationSurface) {
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    sendBack(targetPackage, current, "COMMUNITY_ENTER_RETURN")
                    return true
                }
                if (communityStageAgeMs() >= CommunityTraversalPolicy.RETURN_TIMEOUT_MS) {
                    if (communityReturnBackSteps < CommunityTraversalPolicy.MAX_RETURN_BACK_STEPS) {
                        sendBack(targetPackage, current, "COMMUNITY_FIND_HOME")
                    } else finishCommunityTraversal(current, "Community joined but group list could not be reached safely")
                }
                return true
            }

            CommunityTraversalStage.DISCOVERING_GROUPS -> {
                if (snapshot.communityHomeSurface) {
                    handleCommunityHome(current, targetPackage, snapshot)
                    return true
                }
                if (snapshot.inviteContext || snapshot.screenKind != AutomationScreenKind.UNKNOWN) {
                    setCommunityStage(CommunityTraversalStage.PROCESSING_GROUP)
                    return false
                }
                if (snapshot.conversationSurface && !prefs.communityCurrentGroupKey.isNullOrBlank()) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    sendBack(targetPackage, current, "COMMUNITY_EXISTING_GROUP_RETURN")
                    return true
                }
                return true
            }

            CommunityTraversalStage.OPENING_GROUP -> {
                if (snapshot.inviteContext || snapshot.screenKind != AutomationScreenKind.UNKNOWN) {
                    setCommunityStage(CommunityTraversalStage.PROCESSING_GROUP)
                    return false
                }
                if (snapshot.conversationSurface) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    sendBack(targetPackage, current, "COMMUNITY_GROUP_ALREADY_MEMBER")
                    return true
                }
                if (snapshot.communityHomeSurface && communityStageAgeMs() >= CommunityTraversalPolicy.GROUP_OPEN_TIMEOUT_MS) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                    handleCommunityHome(current, targetPackage, snapshot)
                    return true
                }
                if (communityStageAgeMs() >= CommunityTraversalPolicy.GROUP_OPEN_TIMEOUT_MS) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    sendBack(targetPackage, current, "COMMUNITY_GROUP_TIMEOUT")
                    return true
                }
                return true
            }

            CommunityTraversalStage.PROCESSING_GROUP -> {
                if (snapshot.inviteContext || snapshot.screenKind != AutomationScreenKind.UNKNOWN || pending != null) return false
                if (snapshot.communityHomeSurface) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                    handleCommunityHome(current, targetPackage, snapshot)
                    return true
                }
                if (snapshot.conversationSurface) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    sendBack(targetPackage, current, "COMMUNITY_GROUP_DONE_RETURN")
                    return true
                }
                if (communityStageAgeMs() >= CommunityTraversalPolicy.GROUP_OPEN_TIMEOUT_MS) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    sendBack(targetPackage, current, "COMMUNITY_GROUP_INERT_RETURN")
                    return true
                }
                return true
            }

            CommunityTraversalStage.RETURNING_TO_COMMUNITY -> {
                if (snapshot.communityHomeSurface) {
                    communityReturnBackSteps = 0
                    setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                    handleCommunityHome(current, targetPackage, snapshot)
                    return true
                }
                if (communityStageAgeMs() >= 450L &&
                    communityReturnBackSteps < CommunityTraversalPolicy.MAX_RETURN_BACK_STEPS
                ) {
                    sendBack(targetPackage, current, "COMMUNITY_RETURN_HOME")
                    return true
                }
                if (communityStageAgeMs() >= CommunityTraversalPolicy.RETURN_TIMEOUT_MS * 2) {
                    finishCommunityTraversal(
                        current,
                        "Community pass ended after bounded return recovery; processed ${prefs.communityProcessedGroupCount} subgroup rows"
                    )
                }
                return true
            }

            CommunityTraversalStage.COMPLETE -> {
                finishCommunityTraversal(current, "Community traversal completed")
                return true
            }
        }
    }

    private suspend fun handleCommunityHome(
        current: GroupLink,
        targetPackage: String,
        snapshot: ShizukuUiSnapshot
    ) {
        val prefs = app.preferences
        communityHomeStableScans = (communityHomeStableScans + 1).coerceAtMost(20)
        if (communityHomeStableScans < CommunityTraversalPolicy.COMMUNITY_HOME_STABLE_SCANS) return
        if (!CommunityTraversalPolicy.canProcessMore(prefs.communityProcessedGroupCount)) {
            finishCommunityTraversal(current, "Community subgroup safety cap reached")
            return
        }

        val processed = prefs.communityProcessedGroupKeys
        val next = snapshot.communityGroupCandidates(targetPackage).firstOrNull { it.key !in processed }
        if (next != null) {
            communityEmptyStableScans = 0
            communityNoProgressScans = 0
            val fingerprint = next.node.stableKey("COMMUNITY_ROW:${next.key}")
            if (fingerprint == lastCommunityCandidateFingerprint) communityCandidateStableScans += 1
            else {
                lastCommunityCandidateFingerprint = fingerprint
                communityCandidateStableScans = 1
            }
            if (!ShizukuRuntimePolicy.communityConsensusReached(communityCandidateStableScans)) return

            prefs.communityCurrentGroupKey = next.key
            setCommunityStage(CommunityTraversalStage.OPENING_GROUP)
            runtimeDiagnostic(current, "SHIZUKU_COMMUNITY_GROUP_OPEN", "key=${next.key.take(110)}")
            if (!tapNode(next.node.bounds!!, targetPackage, current, "COMMUNITY_ROW")) {
                prefs.markCommunityGroupProcessed(next.key)
                setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
            }
            lastCommunityCandidateFingerprint = null
            communityCandidateStableScans = 0
            delay(ACTION_SETTLE_MS)
            return
        }

        lastCommunityCandidateFingerprint = null
        communityCandidateStableScans = 0
        val homeSignature = snapshot.communityGroupCandidates(targetPackage).joinToString("|") { it.key }.hashCode() xor
            snapshot.labels.joinToString("|").hashCode()
        if (homeSignature == lastCommunityHomeSignature) communityNoProgressScans += 1 else communityNoProgressScans = 0
        lastCommunityHomeSignature = homeSignature

        val scrollNode = snapshot.communityScrollNode(targetPackage)
        val canScroll = scrollNode != null && CommunityTraversalPolicy.canScroll(prefs.communityScrollAttempts)
        if (canScroll && communityNoProgressScans < COMMUNITY_NO_PROGRESS_LIMIT) {
            if (swipeForward(scrollNode!!.bounds!!, targetPackage, current)) {
                prefs.communityScrollAttempts += 1
                runtimeDiagnostic(current, "SHIZUKU_COMMUNITY_SCROLL", "attempt=${prefs.communityScrollAttempts}")
                delay(COMMUNITY_SCROLL_SETTLE_MS)
                return
            }
        }

        communityEmptyStableScans += 1
        if (communityEmptyStableScans >= CommunityTraversalPolicy.EMPTY_VIEW_STABLE_SCANS ||
            !CommunityTraversalPolicy.canScroll(prefs.communityScrollAttempts) ||
            communityNoProgressScans >= COMMUNITY_NO_PROGRESS_LIMIT
        ) {
            finishCommunityTraversal(
                current,
                "Community completed; processed ${prefs.communityProcessedGroupCount} semantically confirmed subgroup rows"
            )
        }
    }

    private suspend fun completeResultForCurrentOrSubgroup(
        current: GroupLink,
        status: LinkStatus,
        resultCode: LinkResultCode,
        detail: String,
        snapshot: ShizukuUiSnapshot,
        targetPackage: String
    ) {
        val prefs = app.preferences
        val subgroup = prefs.communityTraversalActive &&
            prefs.communityTraversalParentLinkId == current.id &&
            !prefs.communityCurrentGroupKey.isNullOrBlank() &&
            prefs.communityTraversalStage in setOf(
                CommunityTraversalStage.OPENING_GROUP,
                CommunityTraversalStage.PROCESSING_GROUP
            )
        if (!subgroup) {
            val terminalSurface = snapshot.screenKind in setOf(
                AutomationScreenKind.REQUEST_SUBMITTED,
                AutomationScreenKind.ALREADY_MEMBER,
                AutomationScreenKind.GROUP_FULL,
                AutomationScreenKind.INVALID_OR_EXPIRED,
                AutomationScreenKind.REMOVED_OR_BANNED,
                AutomationScreenKind.GENERIC_FAILURE,
                AutomationScreenKind.RESTRICTED
            )
            if (snapshot.inviteContext || snapshot.conversationSurface || terminalSurface) {
                prefs.markRuntimePhase(LinkRuntimePhase.EXITING, "SHIZUKU:SMART_EXIT")
                dismissKnownResultSurface(targetPackage, current, snapshot)
            }
            completeCurrent(current, status, resultCode, detail)
            return
        }

        val key = prefs.communityCurrentGroupKey
        runtimeDiagnostic(
            current,
            "SHIZUKU_COMMUNITY_GROUP_RESULT",
            "status=${status.name}; result=${resultCode.name}; key=${key.orEmpty().take(96)}; $detail"
        )
        prefs.clearAccessibilityPending()
        prefs.markCommunityGroupProcessed(key)
        setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
        communityReturnBackSteps = 0
        if (!snapshot.communityHomeSurface) sendBack(targetPackage, current, "COMMUNITY_RESULT_RETURN")
    }

    /**
     * Work/Secure parity with Accessibility:
     * X when present -> Back on conversation -> verify -> one bounded second Back -> next link.
     */
    private fun findResultSafeCloseNode(
        snapshot: ShizukuUiSnapshot,
        targetPackage: String,
        requestPendingOverride: Boolean = false
    ): ShizukuUiNode? {
        val semantic = snapshot.nodes.asSequence()
            .filter { node ->
                node.enabled && node.bounds?.valid == true && node.belongsTo(targetPackage)
            }
            .filter { node -> node.labels().any(AccessibilityJoinMatcher::isSafeClose) }
            .sortedWith(
                compareByDescending<ShizukuUiNode> { it.clickable }
                    .thenBy { it.bounds?.top ?: Int.MAX_VALUE }
                    .thenByDescending { it.bounds?.centerX ?: 0 }
            )
            .firstOrNull()
        if (semantic != null) return semantic

        // Work/Secure/RTL WhatsApp can expose the X as an unlabeled ImageView and mirror it
        // to either corner. Geometry fallback is allowed only on invite/terminal result sheets.
        val terminalSurface = snapshot.screenKind in setOf(
            AutomationScreenKind.REQUEST_SUBMITTED,
            AutomationScreenKind.ALREADY_MEMBER,
            AutomationScreenKind.GROUP_FULL,
            AutomationScreenKind.INVALID_OR_EXPIRED,
            AutomationScreenKind.REMOVED_OR_BANNED,
            AutomationScreenKind.GENERIC_FAILURE,
            AutomationScreenKind.RESTRICTED
        )
        if ((!snapshot.inviteContext && !terminalSurface) || snapshot.conversationSurface ||
            displayWidth <= 0 || displayHeight <= 0
        ) return null
        return snapshot.nodes.asSequence()
            .filter { node ->
                node.enabled && node.bounds?.valid == true && node.belongsTo(targetPackage)
            }
            .filter { node ->
                node.clickable ||
                    node.className.contains("Image", ignoreCase = true) ||
                    node.resourceId.contains("close", ignoreCase = true) ||
                    node.resourceId.contains("dismiss", ignoreCase = true)
            }
            .filter { node ->
                val b = node.bounds ?: return@filter false
                val w = b.right - b.left
                val h = b.bottom - b.top
                val nearTopCorner =
                    (b.centerX <= displayWidth * 28 / 100 ||
                     b.centerX >= displayWidth * 72 / 100) &&
                    b.centerY <= displayHeight * 32 / 100
                val requestSheetCorner =
                    (snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED ||
                        requestPendingOverride) &&
                    (b.centerX <= displayWidth * 20 / 100 ||
                     b.centerX >= displayWidth * 80 / 100) &&
                    b.centerY in (displayHeight * 26 / 100)..(displayHeight * 78 / 100) &&
                    (node.clickable ||
                     node.className.contains("Image", ignoreCase = true) ||
                     node.resourceId.contains("close", ignoreCase = true) ||
                     node.resourceId.contains("dismiss", ignoreCase = true))
                val compact = w in 1..(displayWidth * 18 / 100).coerceAtLeast(1) &&
                    h in 1..(displayHeight * 12 / 100).coerceAtLeast(1)
                val requestCompact = w in 1..(displayWidth * 14 / 100).coerceAtLeast(1) &&
                    h in 1..(displayHeight * 9 / 100).coerceAtLeast(1)
                val roughlySquare = w <= h * 2 && h <= w * 2
                (nearTopCorner && compact && roughlySquare) ||
                    (requestSheetCorner && requestCompact && roughlySquare)
            }
            .minByOrNull { node ->
                val b = node.bounds!!
                minOf(
                    b.centerX.coerceAtLeast(0),
                    (displayWidth - b.centerX).coerceAtLeast(0)
                ) + b.centerY.coerceAtLeast(0)
            }
    }

    private suspend fun quickResultSnapshot(targetPackage: String): ShizukuUiSnapshot? {
        val fast = ShizukuBridge.fastSnapshot(this, targetPackage, FAST_UI_MAX_NODES)
        if (!fast.success) return null
        val snapshot = ShizukuUiDumpParser.parse(fast.xml)
        if (snapshot.nodes.none { it.packageName == targetPackage }) return null
        return snapshot
    }

    private suspend fun pressResultBack(
        targetPackage: String,
        current: GroupLink,
        purpose: String
    ): Boolean {
        val userId = resolveAndroidUserId(targetPackage) ?: return false
        if (!foregroundLeaseValid(targetPackage, userId) &&
            !isTargetForeground(targetPackage, forceProbe = true)
        ) return false
        waitInputCooldown()
        val persistent = ShizukuBridge.fastBack(this)
        val shell = if (!persistent) {
            ShizukuBridge.execute(this, "input keyevent 4", 2_500)
        } else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        runtimeDiagnostic(
            current,
            "SHIZUKU_RESULT_BACK",
            "$purpose; success=$success; persistent=$persistent; exit=${shell?.exitCode ?: -1}"
        )
        if (success) armFastBurst(FAST_ACTION_BURST_MS)
        return success
    }

    private suspend fun dismissKnownResultSurface(
        targetPackage: String,
        current: GroupLink,
        initialSnapshot: ShizukuUiSnapshot
    ): Boolean {
        val exactUser = resolveAndroidUserId(targetPackage) ?: return false
        val exactTargetTreeVisible = initialSnapshot.nodes.any { node -> node.belongsTo(targetPackage) }
        if (exactTargetTreeVisible) {
            recordForegroundLease(targetPackage, exactUser)
        } else if (!foregroundLeaseValid(targetPackage, exactUser) &&
            !isTargetForeground(targetPackage, forceProbe = true)
        ) {
            return false
        }
        var snapshot = initialSnapshot

        // 1) Prefer WhatsApp's actual X/Close.
        val requestPendingSurface =
            snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED ||
                (readPendingAction(current) == AccessibilityJoinAction.REQUEST &&
                    snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isRequestApprovalNotice) &&
                    snapshot.screenKind != AutomationScreenKind.REQUEST_ACTION)
        val close = findResultSafeCloseNode(snapshot, targetPackage, requestPendingSurface)
        if (close?.bounds != null) {
            val closed = tapNode(close.bounds, targetPackage, current, "RESULT_SAFE_CLOSE")
            runtimeDiagnostic(
                current,
                "SHIZUKU_RESULT_X",
                "found=true; clicked=$closed; semantic=${close.labels().any(AccessibilityJoinMatcher::isSafeClose)}"
            )
            if (closed) {
                delay(ShizukuFastUiPolicy.TERMINAL_ESCAPE_SETTLE_MS)
                val after = quickResultSnapshot(targetPackage)
                if (after == null) return true
                snapshot = after
                if (!snapshot.inviteContext && !snapshot.conversationSurface) return true
            }
        }

        if (requestPendingSurface) {
            // Exact flow: Request sent -> X -> verify. If X is unavailable, Back.
            // Never press "Cancel request"; that would withdraw the submitted request.
            if (pressResultBack(targetPackage, current, "REQUEST_SENT_SHEET_BACK_FALLBACK")) {
                val settle = runtimeSpeed().postTapWaitMs.coerceIn(6L, 120L)
                if (settle > 0L) delay(settle)
                val afterRequestBack = quickResultSnapshot(targetPackage)
                if (afterRequestBack == null) return true
                snapshot = afterRequestBack
                if (!snapshot.inviteContext && !snapshot.conversationSurface) return true
            }
        }

        val safeCancel = snapshot.nodes.asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds?.valid == true &&
                    node.belongsTo(targetPackage)
            }
            .firstOrNull { node ->
                node.labels().any(AccessibilityJoinMatcher::isSafeDialogCancel)
            }
        if (safeCancel?.bounds != null) {
            val dismissed = tapNode(
                safeCancel.bounds,
                targetPackage,
                current,
                "RESULT_SAFE_CANCEL"
            )
            if (dismissed) {
                delay(runtimeSpeed().postTapWaitMs.coerceIn(6L, 120L))
                val afterCancel = quickResultSnapshot(targetPackage)
                if (afterCancel == null) return true
                snapshot = afterCancel
                if (!snapshot.inviteContext && !snapshot.conversationSurface) return true
            }
        }

        // 3) Conversation/result fallback: bounded verified Back attempts.
        repeat(com.althmany.groupmanager.domain.SmartExitControllerPolicy.MAX_BACK_ATTEMPTS) { attempt ->
            if (!snapshot.inviteContext && !snapshot.conversationSurface) return true
            if (!pressResultBack(targetPackage, current, "RESULT_EXIT_${attempt + 1}")) return false

            val settle = if (fastUiMode == FastUiMode.ACTIVE) {
                ShizukuFastUiPolicy.STABLE_SCAN_MS
            } else {
                maxOf(ACTION_SETTLE_MS, ShizukuFastUiPolicy.NORMAL_EXIT_SETTLE_MS)
            }
            if (settle > 0L) delay(settle)

            val after = quickResultSnapshot(targetPackage)
            if (after == null) return true
            snapshot = after
            if (!snapshot.inviteContext && !snapshot.conversationSurface) return true
        }

        runtimeDiagnostic(
            current,
            "SHIZUKU_RESULT_EXIT_BOUNDED",
            "surface remained after X/Back recovery; next exact-user deep link will replace it"
        )
        return false
    }

    private suspend fun exitConversationBeforeDirectHandoff(
        targetPackage: String,
        current: GroupLink
    ): Boolean {
        repeat(2) { attempt ->
            if (!pressResultBack(targetPackage, current, "CONVERSATION_EXIT_${attempt + 1}")) return false

            val settle = if (fastUiMode == FastUiMode.ACTIVE) {
                ShizukuFastUiPolicy.STABLE_SCAN_MS
            } else {
                maxOf(ACTION_SETTLE_MS, ShizukuFastUiPolicy.NORMAL_EXIT_SETTLE_MS)
            }
            if (settle > 0L) delay(settle)

            val after = quickResultSnapshot(targetPackage)
            if (after != null && !after.conversationSurface && !after.inviteContext) return true

            if (after == null && !probeJoinedConversationActivity(targetPackage, current)) return true
        }

        runtimeDiagnostic(
            current,
            "SHIZUKU_CONVERSATION_EXIT_BOUNDED",
            "conversation remained after two Back attempts; direct next-link launch remains armed"
        )
        return false
    }

    private suspend fun finishCommunityTraversal(current: GroupLink, detail: String) {
        val processed = app.preferences.communityProcessedGroupCount
        runtimeDiagnostic(current, "SHIZUKU_COMMUNITY_COMPLETE", "$detail; processed=$processed")
        app.preferences.clearCommunityTraversal()
        resetCommunityEvidence()
        completeCurrent(
            current,
            LinkStatus.JOINED,
            LinkResultCode.JOIN_ACTION_COMPLETED,
            "$detail. Community membership was preserved; WhatsApp/Knox restrictions were never bypassed."
        )
    }

    private fun setCommunityStage(stage: CommunityTraversalStage) {
        app.preferences.transitionCommunityTraversal(stage)
        communityStageStartedAtElapsed = SystemClock.elapsedRealtime()
        if (stage != CommunityTraversalStage.DISCOVERING_GROUPS) communityHomeStableScans = 0
        if (stage != CommunityTraversalStage.RETURNING_TO_COMMUNITY) communityReturnBackSteps = 0
    }

    private fun communityStageAgeMs(): Long {
        val started = communityStageStartedAtElapsed
        return if (started <= 0L) 0L else (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
    }

    private fun resetCommunityEvidence() {
        communityHomeStableScans = 0
        communityEmptyStableScans = 0
        communityReturnBackSteps = 0
        communityStageStartedAtElapsed = SystemClock.elapsedRealtime()
        lastCommunityCandidateFingerprint = null
        communityCandidateStableScans = 0
        lastCommunityHomeSignature = 0
        communityNoProgressScans = 0
    }

    private suspend fun openInvitation(
        current: GroupLink,
        targetPackage: String,
        forceResolvedActivity: Boolean = false
    ): Boolean {
        invalidateForegroundLease()
        val userId = resolveAndroidUserId(targetPackage)
        if (userId == null) {
            stopRun(
                AutomationStopReason.TARGET_UNSUPPORTED,
                if (app.preferences.hasValidRemoteSecureTarget()) {
                    "Remote Secure target could not be verified. Knox may block this Android user/package from the host Shizuku shell."
                } else {
                    "Shizuku cannot find AL-thmany and the selected WhatsApp package in the same Android user/profile"
                }
            )
            return false
        }
        // Do not use `-W`: Accessibility does not wait for ActivityManager's full launch report,
        // and waiting here added visible latency before every link. The command is already locked to
        // the exact Android user and package; the following UI dump still proves target visibility.
        var resolvedComponent = if (forceResolvedActivity) {
            resolveDeepLinkActivity(
                userId = userId,
                targetPackage = targetPackage,
                url = current.url,
                refresh = true
            )
        } else {
            cachedResolvedActivityName.takeIf {
                cachedResolvedActivityUserId == userId &&
                    cachedResolvedActivityTargetPackage == targetPackage
            }
        }
        var result = startDeepLink(
            userId = userId,
            targetPackage = targetPackage,
            url = current.url,
            resolvedComponent = resolvedComponent
        )
        var retriedResolved = false
        if (!ShizukuLaunchPolicy.launchAccepted(result.exitCode, result.output)) {
            // ActivityManager sometimes reports semantic failures with exit=0. Resolve the exact
            // exported WhatsApp VIEW activity and retry once instead of waiting on Launcher/SystemUI.
            val refreshed = resolveDeepLinkActivity(
                userId = userId,
                targetPackage = targetPackage,
                url = current.url,
                refresh = true
            )
            if (refreshed != null) {
                resolvedComponent = refreshed
                retriedResolved = true
                result = startDeepLink(
                    userId = userId,
                    targetPackage = targetPackage,
                    url = current.url,
                    resolvedComponent = refreshed
                )
            }
        }
        if (!ShizukuLaunchPolicy.launchAccepted(result.exitCode, result.output)) {
            runtimeDiagnostic(
                current,
                "SHIZUKU_OPEN_FAILED",
                "user=$userId; exit=${result.exitCode}; resolved=${resolvedComponent.orEmpty().take(90)}; " +
                    "retry=$retriedResolved; ${result.output.take(180)}"
            )
            return false
        }
        // Accessibility gets the target window event immediately. Shizuku has no equivalent event,
        // so trust this exact-user/package launch only long enough to perform the first UI dump.
        // dumpSnapshot still rejects the frame unless WhatsApp nodes from targetPackage are visible.
        recordForegroundLease(targetPackage, userId)
        currentLaunchElapsed = SystemClock.elapsedRealtime()
        outsideTargetCandidateStartedAtElapsed = 0L
        lastOutsideTargetProbeAtElapsed = 0L
        currentLaunchEventBaseline = lastFastEventSequence
        currentLaunchSawTargetEvent = false
        currentLaunchSawTargetForeground = false
        profileCompatCommandProbes = 0
        fastUiSessionRecoveryAttempts = 0
        fastUiNoRootStartedAtElapsed = 0L
        armFastBurst(FAST_OPEN_BURST_MS)
        runtimeDiagnostic(
            current,
            "SHIZUKU_DIRECT_LAUNCH",
            "user=$userId; target=$targetPackage; resolved=${resolvedComponent != null}; retry=$retriedResolved; " +
                "noWait=true; ${result.output.lineSequence().firstOrNull().orEmpty().take(100)}"
        )
        return true
    }

    private suspend fun startDeepLink(
        userId: Int,
        targetPackage: String,
        url: String,
        resolvedComponent: String?
    ): ShizukuBridge.ShellResult {
        val targetArg = resolvedComponent?.let { "-n ${shellQuote(it)}" }
            ?: "-p ${shellQuote(targetPackage)}"
        val command = "am start --user $userId -a android.intent.action.VIEW " +
            "-c android.intent.category.BROWSABLE -f 0x14000000 -d ${shellQuote(url)} " +
            "$targetArg 2>&1"
        return ShizukuBridge.execute(this, command, 3_000)
    }

    private suspend fun resolveDeepLinkActivity(
        userId: Int,
        targetPackage: String,
        url: String,
        refresh: Boolean
    ): String? {
        if (!refresh &&
            cachedResolvedActivityUserId == userId &&
            cachedResolvedActivityTargetPackage == targetPackage
        ) {
            return cachedResolvedActivityName
        }
        val result = ShizukuBridge.execute(
            this,
            "cmd package resolve-activity --brief --user $userId -a android.intent.action.VIEW " +
                "-c android.intent.category.BROWSABLE -d ${shellQuote(url)} -p ${shellQuote(targetPackage)} 2>/dev/null",
            900
        )
        val component = result.output.lineSequence()
            .map(String::trim)
            .lastOrNull { candidate ->
                COMPONENT_NAME.matches(candidate) && candidate.substringBefore('/') == targetPackage
            }
        cachedResolvedActivityUserId = userId
        cachedResolvedActivityTargetPackage = targetPackage
        cachedResolvedActivityName = component
        return component
    }

    private suspend fun resolveAndroidUserId(targetPackage: String): Int? {
        val prefs = app.preferences
        val remoteRequested = prefs.hasValidRemoteSecureTarget() &&
            prefs.remoteSecureWhatsAppPackage == targetPackage
        if (cachedTargetPackage == targetPackage && cachedAndroidUserId != null &&
            (!remoteRequested || cachedAndroidUserId == prefs.remoteSecureAndroidUserId)
        ) return cachedAndroidUserId
        val appPackage = BuildConfig.APPLICATION_ID
        val processUid = Process.myUid()
        if (!PACKAGE_NAME.matches(appPackage) || !PACKAGE_NAME.matches(targetPackage) || processUid <= 0) return null


        if (remoteRequested) {
            val remoteUserId = prefs.remoteSecureAndroidUserId
            val verify = ShizukuBridge.execute(
                this,
                "pm list packages --user $remoteUserId ${shellQuote(targetPackage)} 2>/dev/null",
                2_500
            )
            val exactPackagePresent = verify.success && verify.output.lineSequence()
                .map(String::trim)
                .any { it == "package:$targetPackage" }
            if (!exactPackagePresent) {
                RuntimeDiagnosticStore.append(
                    this,
                    "SHIZUKU_REMOTE_SECURE_PACKAGE_MISSING",
                    "user=$remoteUserId; target=$targetPackage; exit=${verify.exitCode}"
                )
                return null
            }
            if (!prefs.lockRuntimeAndroidUserId(remoteUserId)) {
                RuntimeDiagnosticStore.append(
                    this,
                    "SHIZUKU_REMOTE_SECURE_USER_MISMATCH",
                    "expected=${prefs.runtimeLockedAndroidUserId}; requested=$remoteUserId; target=$targetPackage"
                )
                return null
            }
            if (cachedTargetPackage != targetPackage || cachedAndroidUserId != remoteUserId) {
                cachedResolvedActivityUserId = null
                cachedResolvedActivityTargetPackage = null
                cachedResolvedActivityName = null
            }
            cachedTargetPackage = targetPackage
            cachedAndroidUserId = remoteUserId
            RuntimeDiagnosticStore.append(
                this,
                "SHIZUKU_REMOTE_SECURE_USER_READY",
                "hostUid=$processUid; remoteUser=$remoteUserId; target=$targetPackage; capability=package-visible"
            )
            return remoteUserId
        }

        // Do not choose the first Android user that happens to contain both package names. On a
        // Samsung device the same APK can exist in Personal, Work and Secure Folder at once. Match
        // the exact UID of this AL-thmany process first, then require WhatsApp in that same user.
        val script = "for u in \$(pm list users | sed -n 's/.*UserInfo{\\([0-9][0-9]*\\):.*/\\1/p'); do " +
            "line=\$(pm list packages -U --user \$u ${shellQuote(appPackage)} 2>/dev/null | head -n1); " +
            "echo \"\$line\" | grep -q ${shellQuote("package:$appPackage")} || continue; " +
            "echo \"\$line\" | grep -Eq ${shellQuote("uid:${processUid}([^0-9]|$)")} || continue; " +
            "pm list packages --user \$u ${shellQuote(targetPackage)} 2>/dev/null | grep -qx ${shellQuote("package:$targetPackage")} || continue; " +
            "echo \$u; break; done"
        val result = ShizukuBridge.execute(this, script, 6_000)
        val id = result.output.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toIntOrNull()
        if (cachedTargetPackage != targetPackage || cachedAndroidUserId != id) {
            cachedResolvedActivityUserId = null
            cachedResolvedActivityTargetPackage = null
            cachedResolvedActivityName = null
        }
        if (id != null && !app.preferences.lockRuntimeAndroidUserId(id)) {
            RuntimeDiagnosticStore.append(
                this,
                "SHIZUKU_ANDROID_USER_MISMATCH",
                "expected=${app.preferences.runtimeLockedAndroidUserId}; actual=$id; target=$targetPackage"
            )
            return null
        }
        cachedTargetPackage = targetPackage
        cachedAndroidUserId = id
        return id
    }

    private suspend fun resolveDisplaySize(): Boolean {
        if (displayWidth > 0 && displayHeight > 0) return true
        val result = ShizukuBridge.execute(this, "wm size", 2_500)
        val match = DISPLAY_SIZE.findAll(result.output).lastOrNull() ?: return false
        displayWidth = match.groupValues[1].toIntOrNull() ?: 0
        displayHeight = match.groupValues[2].toIntOrNull() ?: 0
        return displayWidth > 0 && displayHeight > 0
    }

    private fun pauseAfterStableForegroundLoss(
        current: GroupLink,
        targetPackage: String
    ): Boolean {
        val prefs = app.preferences
        if (!prefs.autoPauseOutsideWhatsApp || prefs.accessibilityPaused ||
            !prefs.accessibilityBatchRunning
        ) {
            outsideTargetCandidateStartedAtElapsed = 0L
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val launchAge = if (currentLaunchElapsed > 0L) {
            (now - currentLaunchElapsed).coerceAtLeast(0L)
        } else 0L
        if (currentLaunchElapsed <= 0L || launchAge < USER_EXIT_LAUNCH_GRACE_MS) {
            outsideTargetCandidateStartedAtElapsed = 0L
            return false
        }
        if (outsideTargetCandidateStartedAtElapsed == 0L) {
            outsideTargetCandidateStartedAtElapsed = now
            return false
        }
        val outsideAge = (now - outsideTargetCandidateStartedAtElapsed).coerceAtLeast(0L)
        if (outsideAge < USER_EXIT_CONFIRM_MS) return false

        prefs.pauseAccessibilityBatch(
            diagnostic = "Paused automatically because the user left the selected WhatsApp target",
            outsideTarget = true
        )
        runtimeDiagnostic(
            current,
            "SHIZUKU_USER_EXIT_AUTO_PAUSE",
            "outsideMs=$outsideAge; target=$targetPackage; user=${cachedAndroidUserId ?: -1}; noReopen=true"
        )
        invalidateForegroundLease()
        outsideTargetCandidateStartedAtElapsed = 0L
        return true
    }

    private suspend fun shouldAutoPauseForUserExit(
        current: GroupLink,
        targetPackage: String
    ): Boolean {
        val prefs = app.preferences
        if (!prefs.autoPauseOutsideWhatsApp || prefs.accessibilityPaused ||
            !prefs.accessibilityBatchRunning
        ) {
            outsideTargetCandidateStartedAtElapsed = 0L
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val launchAge = if (currentLaunchElapsed > 0L) {
            (now - currentLaunchElapsed).coerceAtLeast(0L)
        } else 0L
        // Never interpret the normal app -> WhatsApp Deep Link transition as a user exit.
        if (!currentLaunchSawTargetForeground &&
            (currentLaunchElapsed <= 0L || launchAge < USER_EXIT_LAUNCH_GRACE_MS)
        ) {
            outsideTargetCandidateStartedAtElapsed = 0L
            return false
        }
        if (now - lastOutsideTargetProbeAtElapsed < USER_EXIT_PROBE_INTERVAL_MS) return false
        lastOutsideTargetProbeAtElapsed = now

        if (isTargetForeground(targetPackage, forceProbe = true)) {
            outsideTargetCandidateStartedAtElapsed = 0L
            return false
        }

        if (outsideTargetCandidateStartedAtElapsed == 0L) {
            outsideTargetCandidateStartedAtElapsed = now
            return false
        }
        val outsideAge = (now - outsideTargetCandidateStartedAtElapsed).coerceAtLeast(0L)
        if (outsideAge < USER_EXIT_CONFIRM_MS) return false

        prefs.pauseAccessibilityBatch(
            diagnostic = "Paused automatically because the user left the selected WhatsApp target",
            outsideTarget = true
        )
        runtimeDiagnostic(
            current,
            "SHIZUKU_USER_EXIT_AUTO_PAUSE",
            "outsideMs=$outsideAge; target=$targetPackage; user=${cachedAndroidUserId ?: -1}; noReopen=true"
        )
        invalidateForegroundLease()
        outsideTargetCandidateStartedAtElapsed = 0L
        return true
    }

    private suspend fun tryAutoResumeAfterUserReturn(targetPackage: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOutsideTargetProbeAtElapsed < USER_RETURN_PROBE_INTERVAL_MS) return false
        lastOutsideTargetProbeAtElapsed = now
        if (!isTargetForeground(targetPackage, forceProbe = true)) return false

        app.preferences.resumeAccessibilityBatch(
            "Selected WhatsApp returned to foreground; automatic Shizuku resume"
        )
        currentLaunchElapsed = now
        currentLaunchSawTargetForeground = true
        outsideTargetCandidateStartedAtElapsed = 0L
        armFastBurst(FAST_OPEN_BURST_MS)
        RuntimeDiagnosticStore.append(
            this,
            "SHIZUKU_USER_RETURN_AUTO_RESUME",
            "target=$targetPackage; user=${cachedAndroidUserId ?: -1}; current link preserved"
        )
        return true
    }

    private suspend fun isTargetForeground(targetPackage: String, forceProbe: Boolean = false): Boolean {
        val userId = resolveAndroidUserId(targetPackage) ?: run {
            lastForegroundProbeSummary = "no-exact-user"
            return false
        }
        if (!forceProbe && foregroundLeaseValid(targetPackage, userId)) {
            lastForegroundProbeSummary = "lease:u$userId:$targetPackage"
            return true
        }

        // Work Profile / Secure Folder can expose a different first window-focus line than the
        // actually resumed activity. Never use `grep -m1` here: on Samsung multi-profile devices
        // it can stop on a personal/system window even while u10/uNN WhatsApp is visibly resumed.
        // Prefer ActivityManager's resumed-activity records because they carry the Android user
        // token (u10, u150, ...), preserving the exact-user lock instead of weakening it.
        val activity = ShizukuBridge.execute(
            this,
            "dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 24",
            900
        )
        val userToken = "u$userId"
        val resumedMatch = activity.success && activity.output.lineSequence().any { line ->
            ShizukuActivityProofPolicy.containsExactPackage(line, targetPackage) &&
                Regex("(^|[^A-Za-z0-9_])" + Regex.escape(userToken) + "([^0-9]|$)").containsMatchIn(line)
        }
        if (resumedMatch) {
            lastForegroundProbeSummary = "activity:$userToken:$targetPackage"
            recordForegroundLease(targetPackage, userId)
            return true
        }

        // A second dumpsys-window command was diagnostic-only and could add multiple seconds on
        // Samsung Work Profile. It cannot authorize input, so omit it from the hot recovery path.
        val activitySummary = activity.output.lineSequence()
            .filter { it.contains("ResumedActivity") || it.contains("mResumedActivity") || it.contains("topResumedActivity") }
            .take(2)
            .joinToString(" | ")
            .replace(';', ',')
            .take(180)
        lastForegroundProbeSummary = "expected=$userToken/$targetPackage; activity=${activitySummary.ifBlank { "none" }}; window=skipped-fast"
        return false
    }

    /** Fast high-confidence success proof used only after this service pressed Join. */
    private suspend fun probeJoinedConversationActivity(
        targetPackage: String,
        current: GroupLink
    ): Boolean {
        val userId = resolveAndroidUserId(targetPackage) ?: return false
        repeat(ACTIVITY_PROBE_ATTEMPTS) { attempt ->
            val result = ShizukuBridge.execute(
                this,
                "dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' | head -n 12",
                ACTIVITY_PROBE_TIMEOUT_MS
            )
            if (!result.success) return false
            val proof = ShizukuActivityProofPolicy.findJoinedConversationProof(
                result.output.lineSequence(),
                targetPackage,
                userId
            )
            if (proof != null) {
                runtimeDiagnostic(
                    current,
                    "SHIZUKU_ACTIVITY_JOIN_PROOF",
                    "user=$userId; target=$targetPackage; attempt=${attempt + 1}; activity=${proof.trim().take(180)}"
                )
                return true
            }
            if (attempt + 1 < ACTIVITY_PROBE_ATTEMPTS) delay(ACTIVITY_PROBE_RETRY_MS)
        }
        return false
    }

    private fun foregroundLeaseValid(targetPackage: String, userId: Int): Boolean {
        if (lastForegroundVerifiedPackage != targetPackage || lastForegroundVerifiedUserId != userId) return false
        val age = SystemClock.elapsedRealtime() - lastForegroundVerifiedAtElapsed
        return age in 0..ShizukuRuntimePolicy.FOREGROUND_LEASE_MS
    }

    private fun recordForegroundLease(targetPackage: String, userId: Int) {
        lastForegroundVerifiedPackage = targetPackage
        lastForegroundVerifiedUserId = userId
        lastForegroundVerifiedAtElapsed = SystemClock.elapsedRealtime()
    }

    private fun invalidateForegroundLease() {
        lastForegroundVerifiedPackage = null
        lastForegroundVerifiedUserId = null
        lastForegroundVerifiedAtElapsed = 0L
    }

    /**
     * Last-resort Samsung profile lane. It is entered only after semantic UiAutomation returned no
     * target tree, the exact Android user/package is independently proved foreground, and a fresh
     * screenshot contains one wide WhatsApp-green positive control. No fixed coordinate is used.
     */
    private suspend fun handleVisualProfileFallback(
        current: GroupLink,
        targetPackage: String,
        expectedAction: AccessibilityJoinAction? = null
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (visualProbeLinkId != current.id) {
            visualProbeLinkId = current.id
            visualProbeAttempts = 0
            lastVisualProbeAtElapsed = 0L
            visualActionTappedAtElapsed = 0L
            visualTapAttempts = 0
            visualExpectedAction = expectedAction
        } else if (expectedAction != null) {
            visualExpectedAction = expectedAction
        }

        if (visualActionTappedAtElapsed > 0L) {
            val age = (now - visualActionTappedAtElapsed).coerceAtLeast(0L)
            if (age < VISUAL_POST_ACTION_VERIFY_MS) return true

            val after = ShizukuBridge.fastFindWidePositiveAction(this)
            val remainingButton = after.scaleTo(displayWidth, displayHeight)
                ?.takeIf { ShizukuRuntimePolicy.isSafeTapBounds(it, displayWidth, displayHeight) }
            if (after.found && remainingButton != null &&
                visualTapAttempts < VISUAL_MAX_TAP_ATTEMPTS &&
                visualExpectedAction != null
            ) {
                if (tapNode(remainingButton, targetPackage, current, "VISUAL_POSITIVE_RETRY")) {
                    visualTapAttempts += 1
                    visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
                    consecutiveDumpFailures = 0
                    emptyDumpStartedAt = 0L
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_VISUAL_ACTION_RETRY",
                        "attempt=$visualTapAttempts; bounds=$remainingButton; semanticTreeHidden=true"
                    )
                    return true
                }
            }

            if (after.found && remainingButton != null) {
                dismissVisualActionSurface(targetPackage, current, "VISUAL_ACTION_UNCHANGED")
                completeCurrent(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.ACTION_TIMEOUT,
                    "A protected visual Join/Request control remained visible after two direct input attempts; closed it and continued"
                )
                return true
            }

            val expected = visualExpectedAction ?: readPendingAction(current)
            val joinedConversation = probeJoinedConversationActivity(targetPackage, current)
            dismissVisualActionSurface(targetPackage, current, "VISUAL_ACTION_COMPLETED")

            when {
                joinedConversation -> completeCurrent(
                    current,
                    LinkStatus.JOINED,
                    LinkResultCode.JOIN_ACTION_COMPLETED,
                    "Wide WhatsApp action disappeared and the exact-user conversation activity proved Join success"
                )
                expected == AccessibilityJoinAction.REQUEST -> completeCurrent(
                    current,
                    LinkStatus.REQUESTED,
                    LinkResultCode.REQUEST_SENT,
                    "Known Request action disappeared after protected input; recorded as requested"
                )
                else -> completeCurrent(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.UNKNOWN_SCREEN,
                    "Visual positive action disappeared but its Join/Request result could not be proved; not counted as a false request"
                )
            }
            return true
        }

        val launchAge = if (currentLaunchElapsed > 0L) now - currentLaunchElapsed else 0L
        if (launchAge < VISUAL_PROFILE_PROBE_AFTER_MS) return false
        if (visualProbeAttempts >= VISUAL_MAX_PROBE_ATTEMPTS) return false
        if (now - lastVisualProbeAtElapsed < VISUAL_PROFILE_PROBE_INTERVAL_MS) return false
        if (app.preferences.automationStage !in setOf(
                AutomationStage.WAITING_FOR_WHATSAPP,
                AutomationStage.LOOKING_FOR_PREVIEW,
                AutomationStage.LOOKING_FOR_JOIN,
                AutomationStage.VERIFYING_RESULT
            )
        ) return false
        if (!isTargetForeground(targetPackage, forceProbe = true)) return false

        visualProbeAttempts += 1
        lastVisualProbeAtElapsed = now
        val visual = ShizukuBridge.fastFindWidePositiveAction(this)
        val bounds = visual.scaleTo(displayWidth, displayHeight)
        runtimeDiagnostic(
            current,
            "SHIZUKU_VISUAL_ACTION_PROBE",
            "attempt=$visualProbeAttempts; state=${visual.state}; bounds=${bounds ?: "NONE"}; exactForeground=true"
        )
        if (!visual.found || bounds == null ||
            !ShizukuRuntimePolicy.isSafeTapBounds(bounds, displayWidth, displayHeight)
        ) return false

        if (!tapNode(bounds, targetPackage, current, "VISUAL_POSITIVE")) return false
        visualTapAttempts = 1
        visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
        consecutiveDumpFailures = 0
        emptyDumpStartedAt = 0L
        visualExpectedAction?.let { expected ->
            app.preferences.setAccessibilityPending(
                current.id,
                expected.name,
                AccessibilityInviteTarget.UNKNOWN
            )
        }
        app.preferences.transitionAutomation(
            AutomationStage.VERIFYING_RESULT,
            "Protected visual WhatsApp Join/Request input dispatched; verifying disappearance before direct handoff",
            resetRetries = true
        )
        runtimeDiagnostic(
            current,
            "SHIZUKU_VISUAL_ACTION_TAP",
            "attempt=1; bounds=$bounds; exactUser=${cachedAndroidUserId ?: -1}; fixedCoordinate=false"
        )
        return true
    }

    private suspend fun dismissVisualActionSurface(
        targetPackage: String,
        current: GroupLink,
        purpose: String
    ): Boolean {
        val userId = resolveAndroidUserId(targetPackage) ?: return false
        if (!foregroundLeaseValid(targetPackage, userId) &&
            !isTargetForeground(targetPackage, forceProbe = true)
        ) return false
        waitInputCooldown()
        val persistent = ShizukuBridge.fastBack(this)
        val shell = if (!persistent) ShizukuBridge.execute(this, "input keyevent 4", 2_500) else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        runtimeDiagnostic(
            current,
            "SHIZUKU_VISUAL_SURFACE_CLOSE",
            "$purpose; success=$success; persistent=$persistent; exit=${shell?.exitCode ?: -1}"
        )
        if (success && VISUAL_DISMISS_SETTLE_MS > 0L) delay(VISUAL_DISMISS_SETTLE_MS)
        return success
    }

    private suspend fun tapNode(
        bounds: ShizukuBounds,
        targetPackage: String,
        current: GroupLink,
        purpose: String
    ): Boolean {
        if (!ShizukuRuntimePolicy.isSafeTapBounds(bounds, displayWidth, displayHeight)) {
            runtimeDiagnostic(current, "SHIZUKU_UNSAFE_BOUNDS", "$purpose bounds=$bounds display=${displayWidth}x$displayHeight")
            return false
        }
        if (!isTargetForeground(targetPackage)) return false
        waitInputCooldown()
        val directTouchFirst = purpose.contains("JOIN", ignoreCase = true) ||
            purpose.contains("REQUEST", ignoreCase = true) ||
            purpose.contains("CONFIRM", ignoreCase = true) ||
            purpose.startsWith("VISUAL_POSITIVE")
        var nodeClick = false
        var persistentTap = false
        if (fastUiMode == FastUiMode.ACTIVE) {
            if (directTouchFirst) {
                persistentTap = ShizukuBridge.fastTap(this, bounds.centerX, bounds.centerY)
                if (!persistentTap) {
                    nodeClick = ShizukuBridge.fastClickNode(this, targetPackage, bounds.centerX, bounds.centerY)
                }
            } else {
                nodeClick = ShizukuBridge.fastClickNode(this, targetPackage, bounds.centerX, bounds.centerY)
                if (!nodeClick) persistentTap = ShizukuBridge.fastTap(this, bounds.centerX, bounds.centerY)
            }
        }
        val shell = if (!nodeClick && !persistentTap) {
            ShizukuBridge.execute(this, "input tap ${bounds.centerX} ${bounds.centerY}", 2_500)
        } else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = nodeClick || persistentTap || shell?.success == true
        if (!success) {
            consecutiveInputFailures += 1
            runtimeDiagnostic(
                current,
                "SHIZUKU_INPUT_FAILED",
                "$purpose nodeClick=$nodeClick; persistentTap=$persistentTap; exit=${shell?.exitCode ?: -1}; count=$consecutiveInputFailures"
            )
            if (consecutiveInputFailures >= ShizukuRuntimePolicy.MAX_CONSECUTIVE_INPUT_FAILURES) {
                runtimeDiagnostic(
                    current,
                    "SHIZUKU_INPUT_CONTINUITY_RECOVERY",
                    "Repeated input failures; keeping the state machine alive so the bounded action watchdog can skip safely instead of stopping the run"
                )
                resetActionConsensus()
            }
            return false
        }
        consecutiveInputFailures = 0
        app.preferences.recordRuntimeAction(purpose, "SHIZUKU")
        app.preferences.markRuntimePhase(
            LinkRuntimePhase.ACTION_TAPPED,
            "SHIZUKU:$purpose"
        )
        armFastBurst()
        return true
    }

    private suspend fun swipeForward(
        bounds: ShizukuBounds,
        targetPackage: String,
        current: GroupLink
    ): Boolean {
        if (!ShizukuRuntimePolicy.isSafeSwipeBounds(bounds, displayWidth, displayHeight)) return false
        if (!isTargetForeground(targetPackage)) return false
        waitInputCooldown()
        val x = bounds.centerX
        val startY = bounds.top + ((bounds.bottom - bounds.top) * 74 / 100)
        val endY = bounds.top + ((bounds.bottom - bounds.top) * 30 / 100)
        if (startY <= endY) return false
        val selectedGestureMs = runtimeSpeed().gestureDurationMs.coerceIn(8L, 72L)
        val persistent = fastUiMode == FastUiMode.ACTIVE &&
            ShizukuBridge.fastSwipe(this, x, startY, x, endY, selectedGestureMs.toInt())
        val shell = if (!persistent) {
            val shellGestureMs = (selectedGestureMs * 4L).coerceIn(60L, 160L)
            ShizukuBridge.execute(
                this,
                "input swipe $x $startY $x $endY $shellGestureMs",
                3_000
            )
        } else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        if (!success) runtimeDiagnostic(current, "SHIZUKU_SCROLL_FAILED", "persistent=$persistent; exit=${shell?.exitCode ?: -1}")
        if (success) armFastBurst(FAST_ACTION_BURST_MS)
        return success
    }

    private suspend fun sendBack(targetPackage: String, current: GroupLink, purpose: String): Boolean {
        if (communityReturnBackSteps >= CommunityTraversalPolicy.MAX_RETURN_BACK_STEPS) return false
        if (!isTargetForeground(targetPackage)) return false
        waitInputCooldown()
        val persistent = fastUiMode == FastUiMode.ACTIVE && ShizukuBridge.fastBack(this)
        val shell = if (!persistent) ShizukuBridge.execute(this, "input keyevent 4", 2_500) else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        if (success) {
            communityReturnBackSteps += 1
            communityStageStartedAtElapsed = SystemClock.elapsedRealtime()
            armFastBurst(COMMUNITY_BACK_BURST_MS)
            runtimeDiagnostic(current, "SHIZUKU_BACK", "$purpose; step=$communityReturnBackSteps; persistent=$persistent")
            delay(ACTION_SETTLE_MS)
        }
        return success
    }

    private suspend fun waitInputCooldown() {
        val elapsed = SystemClock.elapsedRealtime() - lastInputAtElapsed
        val cooldown = if (fastUiMode == FastUiMode.ACTIVE) {
            runtimeSpeed().clickThrottleMs
        } else {
            ShizukuRuntimePolicy.inputCooldownMs(false)
        }
        val wait = cooldown - elapsed
        if (wait > 0) delay(wait)
    }

    private suspend fun completeCurrent(
        current: GroupLink,
        status: LinkStatus,
        resultCode: LinkResultCode,
        detail: String
    ) {
        val prefs = app.preferences

        // A second detector can arrive a few milliseconds after another detector completed/stopped
        // the run. Never let that late callback overwrite the real result with SESSION_CHANGED.
        if (!prefs.accessibilityBatchRunning) {
            runtimeDiagnostic(
                current,
                "SHIZUKU_LATE_RESULT_IGNORED",
                "result=${resultCode.name}; run already stopped/completed"
            )
            return
        }

        val targetPackage = prefs.runtimeLockedWhatsAppPackage
            ?.takeIf { it.isNotBlank() && PACKAGE_NAME.matches(it) }
            ?: cachedTargetPackage
                ?.takeIf { it.isNotBlank() && PACKAGE_NAME.matches(it) }

        val sessionId = prefs.accessibilitySessionId
            ?.takeIf { it.isNotBlank() }
            ?: prefs.activeSessionId?.takeIf { it.isNotBlank() }

        if (targetPackage.isNullOrBlank() || sessionId.isNullOrBlank() ||
            prefs.activeSessionId != sessionId
        ) {
            stopRun(
                AutomationStopReason.SESSION_CHANGED,
                "Shizuku handoff could not recover a valid active session/locked target"
            )
            return
        }

        // Port Accessibility's completeAndAdvance behavior: commit current exactly once, fetch the
        // next actionable row in the same IO transaction window, and launch it directly instead of
        // waiting for the outer polling loop to rediscover WAITING_BEFORE_NEXT. Multiple terminal
        // detectors may observe the same frame, so serialize the commit just like Accessibility.
        if (!resultCommitExecuting.compareAndSet(false, true)) return
        val state = try {
            withContext(Dispatchers.IO) {
                val stillCurrent = app.repository.loadAutomationCurrent(sessionId)
                if (stillCurrent?.id != current.id) return@withContext null
                prefs.markRuntimePhase(
                    LinkRuntimePhase.EXITING,
                    "SHIZUKU:${resultCode.name}"
                )
                val auditedDetail = prefs.buildRuntimeAuditDetail(
                    detail,
                    resultCode.name,
                    "SHIZUKU"
                )
                app.repository.markStatus(current.id, status, resultCode, auditedDetail)
                prefs.finishRuntimeLink(
                    current.position,
                    "SHIZUKU:${resultCode.name}"
                )
                prefs.clearAccessibilityPending()
                prefs.accessibilityProcessedCount += 1
                val processed = prefs.accessibilityProcessedCount
                val next = app.repository.loadAutomationNext(sessionId)
                DirectAdvanceState(next, processed, processed >= AutomationPolicy.BATCH_SIZE, next == null)
            }
        } finally {
            resultCommitExecuting.set(false)
        } ?: return

        runtimeDiagnostic(
            current,
            "SHIZUKU_RESULT",
            "status=${status.name}; result=${resultCode.name}; processed=${state.processed}; next=${state.next?.position ?: -1}"
        )

        // Full result-file mirrors are expensive and were making Shizuku visibly heavier than
        // Accessibility. Mirror at the same cadence as Accessibility, and always on a terminal run.
        val forceMirror = state.limitReached || state.complete || state.next == null
        if (forceMirror || state.processed % RESULT_MIRROR_SYNC_EVERY == 0) {
            withContext(Dispatchers.IO) {
                GroupJoinerResultStore.sync(this@ShizukuAutomationService, app.repository.loadActiveSnapshot())
            }
        }

        updateNotification(getString(R.string.shizuku_service_completed_link, state.processed))
        resetPerLinkEvidence()

        if (state.processed > 0 &&
            state.processed % PERIODIC_UI_REFRESH_EVERY == 0 &&
            lastPeriodicUiRefreshProcessed != state.processed
        ) {
            lastPeriodicUiRefreshProcessed = state.processed
            val refreshed = ShizukuBridge.fastResetUiAutomation(this)
            runtimeDiagnostic(
                current,
                "SHIZUKU_PERIODIC_UI_REFRESH",
                "processed=${state.processed}; refreshed=$refreshed; preventiveLongRunMaintenance=true"
            )
            if (refreshed) {
                fastUiMode = FastUiMode.UNKNOWN
                fastUiFailureCount = 0
                fastUiSessionRecoveryAttempts = 0
                lastFastEventSequence = 0L
                currentLaunchEventBaseline = 0L
                commandDumpSuppressedUntilElapsed = 0L
            }
        }

        if (state.limitReached) {
            if (prefs.autoResumeCurrentRun && state.next != null) {
                prefs.accessibilityProcessedCount = 0
                runtimeDiagnostic(
                    current,
                    "SHIZUKU_AUTO_BATCH_CONTINUE",
                    "1000-link internal window completed; Auto Resume continues queued links"
                )
            } else {
                completeRun(
                    AutomationStopReason.BATCH_LIMIT_REACHED,
                    "Shizuku run window reached; queued links remain resumable"
                )
                return
            }
        }
        if (state.complete || state.next == null) {
            completeRun(AutomationStopReason.SESSION_COMPLETE, "All invitation links are complete")
            return
        }

        prefs.markRuntimePhase(LinkRuntimePhase.ADVANCING, "SHIZUKU:NEXT")
        val waitMs = prefs.runtimeSpeedProfile().interLinkDelayMs
        prefs.transitionAutomation(
            AutomationStage.WAITING_BEFORE_NEXT,
            if (waitMs <= 0L) "Result recorded; direct Accessibility-like next-link handoff"
            else "Waiting ${waitMs}ms before direct next-link handoff",
            resetRetries = true
        )
        if (waitMs > 0L) delay(waitMs)

        val opened = withContext(Dispatchers.IO) {
            prefs.transitionAutomation(AutomationStage.OPENING_LINK, "Opening next invitation directly", resetRetries = true)
            app.repository.markOpened(state.next.id)
        } ?: run {
            stopRun(AutomationStopReason.OPEN_FAILED, "Could not reserve the next invitation during direct Shizuku handoff")
            return
        }

        if (!openInvitation(opened, targetPackage)) return
        prefs.markAutomationLaunched()
        runtimeDiagnostic(
            opened,
            "SHIZUKU_DIRECT_HANDOFF",
            "from=${current.position}; to=${opened.position}; delayMs=$waitMs; exactUser=${cachedAndroidUserId ?: -1}"
        )
        updateNotification(getString(R.string.shizuku_service_opened_link, opened.position + 1))
        val nextSettleMs = if (fastUiMode == FastUiMode.ACTIVE)
            ShizukuFastUiPolicy.USER_INSTANT_ADVANCE_SETTLE_MS else NEXT_LINK_SETTLE_MS
        if (nextSettleMs > 0L) delay(nextSettleMs)
    }

    private data class DirectAdvanceState(
        val next: GroupLink?,
        val processed: Int,
        val limitReached: Boolean,
        val complete: Boolean
    )

    private fun resetPerLinkEvidence() {
        stableSnapshotScans = 0
        lastSnapshotSignature = 0
        foregroundWaitStartedAt = 0L
        emptyDumpStartedAt = 0L
        consecutiveDumpFailures = 0
        lastDumpFailureCountedAtElapsed = 0L
        commandDumpKillRecoveryAttempts = 0
        consecutiveAmbiguousActions = 0
        consecutiveInputFailures = 0
        invalidateForegroundLease()
        resetActionConsensus()
        loadingStartedAtElapsed = 0L
        unknownStartedAtElapsed = 0L
        foregroundRecoveryAttempts = 0
        outsideTargetCandidateStartedAtElapsed = 0L
        lastOutsideTargetProbeAtElapsed = 0L
        fastUiSessionRecoveryAttempts = 0
        fastUiNoRootStartedAtElapsed = 0L
        fastUiTargetHiddenStartedAtElapsed = 0L
        profileCompatCommandProbes = 0
        lastRequestTerminalProbeAtElapsed = 0L
        currentLaunchElapsed = 0L
        currentLaunchEventBaseline = lastFastEventSequence
        currentLaunchSawTargetEvent = false
        currentLaunchSawTargetForeground = false
        visualProbeLinkId = -1L
        visualProbeAttempts = 0
        lastVisualProbeAtElapsed = 0L
        visualActionTappedAtElapsed = 0L
        visualTapAttempts = 0
        visualExpectedAction = null
    }

    private suspend fun armFastEventSequence(targetPackage: String) {
        val sequence = ShizukuBridge.fastEventSequence(this, targetPackage)
        if (sequence > currentLaunchEventBaseline) currentLaunchSawTargetEvent = true
        if (sequence > lastFastEventSequence) lastFastEventSequence = sequence
    }

    private fun armFastBurst(durationMs: Long = FAST_ACTION_BURST_MS) {
        val until = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(0L)
        if (until > fastBurstUntilElapsed) fastBurstUntilElapsed = until
    }

    private fun fastFrameTimeoutMs(current: GroupLink): Long {
        val pending = readPendingAction(current)
        val speed = runtimeSpeed()
        return when {
            pending != null -> {
                val untilResultFallback = (ShizukuFastUiPolicy.RESULT_ANALYSIS_FALLBACK_MS - pendingAgeMs())
                    .coerceAtLeast(1L)
                minOf(speed.watchdogIntervalMs, untilResultFallback)
            }
            SystemClock.elapsedRealtime() <= fastBurstUntilElapsed -> speed.stableScanMs
            stableSnapshotScans < ShizukuFastUiPolicy.STABLE_SCANS_BEFORE_FALLBACK_POLL -> speed.stableScanMs
            else -> speed.fallbackPollMs
        }
    }

    private fun postJoinMinEvidenceMs(): Long =
        if (fastUiMode == FastUiMode.ACTIVE) {
            runtimeSpeed().postTapWaitMs.coerceAtLeast(12L)
        } else POST_JOIN_MIN_MS

    private fun postJoinStableScans(): Int =
        if (fastUiMode == FastUiMode.ACTIVE) ShizukuFastUiPolicy.POST_JOIN_STABLE_SCANS
        else ShizukuRuntimePolicy.POST_ACTION_STABLE_SCANS

    private suspend fun handleFastUiTimeoutGuards(current: GroupLink, snapshot: ShizukuUiSnapshot): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (snapshot.screenKind == AutomationScreenKind.LOADING) {
            unknownStartedAtElapsed = 0L
            if (loadingStartedAtElapsed == 0L) loadingStartedAtElapsed = now
            if (now - loadingStartedAtElapsed >= ShizukuFastUiPolicy.LOADING_TIMEOUT_MS) {
                runtimeDiagnostic(current, "SHIZUKU_FAST_LOADING_TIMEOUT", "ageMs=${now - loadingStartedAtElapsed}")
                completeCurrent(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.ACTION_TIMEOUT,
                    "WhatsApp remained on a genuine loading surface for the protected 20-second budget"
                )
                return true
            }
            return false
        }
        loadingStartedAtElapsed = 0L

        val pending = readPendingAction(current)
        val stableUnknown = snapshot.screenKind == AutomationScreenKind.UNKNOWN &&
            pending == null &&
            !snapshot.inviteContext &&
            !snapshot.conversationSurface
        if (stableUnknown) {
            if (unknownStartedAtElapsed == 0L) unknownStartedAtElapsed = now
            if (stableSnapshotScans >= ShizukuFastUiPolicy.MIN_STABLE_SCANS_FOR_WATCHDOG &&
                now - unknownStartedAtElapsed >= ShizukuFastUiPolicy.UNKNOWN_TIMEOUT_MS
            ) {
                runtimeDiagnostic(current, "SHIZUKU_FAST_UNKNOWN_ADVANCE", "ageMs=${now - unknownStartedAtElapsed}; stable=$stableSnapshotScans")
                completeCurrent(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.UNKNOWN_SCREEN,
                    "Fast UI found a stable unknown non-loading WhatsApp surface for 2 seconds; advanced to preserve continuity"
                )
                return true
            }
        } else {
            unknownStartedAtElapsed = 0L
        }
        return false
    }

    private suspend fun maybeRetryFastPendingAction(
        current: GroupLink,
        targetPackage: String,
        snapshot: ShizukuUiSnapshot,
        pending: AccessibilityJoinAction?
    ): Boolean {
        if (pending !in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST)) return false
        if (pendingAgeMs() < runtimeSpeed().actionRetryAfterMs) return false
        if (app.preferences.automationRetryCount >= ShizukuFastUiPolicy.MAX_ACTION_ATTEMPTS - 1) return false
        val expectedScreen = if (pending == AccessibilityJoinAction.JOIN) AutomationScreenKind.JOIN_ACTION else AutomationScreenKind.REQUEST_ACTION
        if (snapshot.screenKind != expectedScreen) return false
        val selection = snapshot.actionSelection(pending!!, targetPackage)
        val candidate = selection.candidate ?: return false
        val required = ShizukuRuntimePolicy.actionConsensusScans(
            candidate.score, selection.runnerUpScore, candidate.node.clickable,
            candidate.node.packageName == targetPackage, selection.ambiguous
        )
        if (required != 1 || candidate.node.bounds == null) return false
        if (!tapNode(candidate.node.bounds, targetPackage, current, "${pending.name}_FAST_RETRY")) return false
        app.preferences.incrementAutomationRetry()
        app.preferences.setAccessibilityPending(current.id, pending.name, snapshot.inviteTarget)
        runtimeDiagnostic(
            current,
            "SHIZUKU_FAST_ACTION_RETRY",
            "action=${pending.name}; afterMs=${ShizukuFastUiPolicy.ACTION_RETRY_AFTER_MS}; attempt=${app.preferences.automationRetryCount + 1}"
        )
        resetActionConsensus()
        return true
    }

    private fun shouldFastWatchdogAdvance(snapshot: ShizukuUiSnapshot, pending: AccessibilityJoinAction?): Boolean {
        if (pending == null) return false
        if (snapshot.screenKind == AutomationScreenKind.LOADING || snapshot.screenKind == AutomationScreenKind.RESTRICTED) return false
        if (snapshot.screenKind in setOf(
                AutomationScreenKind.ALREADY_MEMBER, AutomationScreenKind.REQUEST_SUBMITTED,
                AutomationScreenKind.GROUP_FULL, AutomationScreenKind.INVALID_OR_EXPIRED,
                AutomationScreenKind.REMOVED_OR_BANNED, AutomationScreenKind.GENERIC_FAILURE
            )
        ) return false
        return stableSnapshotScans >= ShizukuFastUiPolicy.MIN_STABLE_SCANS_FOR_WATCHDOG &&
            pendingAgeMs() >= ShizukuFastUiPolicy.NON_LOADING_WATCHDOG_MS
    }

    private suspend fun completeTerminalResult(
        current: GroupLink,
        status: LinkStatus,
        resultCode: LinkResultCode,
        detail: String,
        snapshot: ShizukuUiSnapshot,
        targetPackage: String
    ) {
        if (fastUiMode == FastUiMode.ACTIVE && ShizukuFastUiPolicy.TERMINAL_ESCAPE_SETTLE_MS > 0L) {
            delay(ShizukuFastUiPolicy.TERMINAL_ESCAPE_SETTLE_MS)
        }
        completeResultForCurrentOrSubgroup(current, status, resultCode, detail, snapshot, targetPackage)
    }

    private fun readPendingAction(current: GroupLink): AccessibilityJoinAction? {
        if (app.preferences.accessibilityPendingLinkId != current.id) return null
        return runCatching {
            enumValueOf<AccessibilityJoinAction>(app.preferences.accessibilityPendingAction.orEmpty())
        }.getOrNull()
    }

    private fun pendingAgeMs(): Long =
        (System.currentTimeMillis() - app.preferences.accessibilityPendingAt).coerceAtLeast(0L)

    private fun stopRun(reason: AutomationStopReason, diagnostic: String) {
        AutomationScreenAwakeGuard.release()
        app.preferences.stopAccessibilityBatch(reason, diagnostic)
        updateNotification(diagnostic, force = true)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun completeRun(reason: AutomationStopReason, diagnostic: String) {
        AutomationScreenAwakeGuard.release()
        app.preferences.completeAccessibilityBatch(reason, diagnostic)
        updateNotification(diagnostic, force = true)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runtimeDiagnostic(current: GroupLink, kind: String, detail: String) {
        if (!app.preferences.runtimeDiagnosticJournal) return
        val line = "link=${current.id}; pos=${current.position}; stage=${app.preferences.automationStage.name}; " +
            "community=${app.preferences.communityTraversalStage.name}; $detail"
        // Diagnostic file I/O must never sit on the Join -> Verify -> Next hot path.
        serviceScope.launch(Dispatchers.IO) {
            RuntimeDiagnosticStore.append(this@ShizukuAutomationService, kind, line)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.shizuku_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(body: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            5300,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            5301,
            Intent(this, ShizukuAutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getBroadcast(
            this,
            5302,
            Intent(this, AutomationActionReceiver::class.java).setAction(QuickJoinNotification.ACTION_TOGGLE_PAUSE_AUTOMATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(getString(R.string.shizuku_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(if (app.preferences.accessibilityPaused) R.string.automation_action_resume else R.string.automation_action_pause),
                pauseIntent
            )
            .addAction(0, getString(R.string.automation_action_stop), stopIntent)
            .build()
    }

    private fun updateNotification(body: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAtElapsed < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAtElapsed = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(body))
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private enum class FastUiMode { UNKNOWN, ACTIVE, DISABLED }

    companion object {
        private const val ACTION_START = "com.althmany.groupmanager.action.START_SHIZUKU_AUTOMATION"
        private const val ACTION_STOP = "com.althmany.groupmanager.action.STOP_SHIZUKU_AUTOMATION"
        private const val CHANNEL_ID = "shizuku_automation_v2"
        private const val NOTIFICATION_ID = 5301
        private const val OPEN_SETTLE_MS = 8L
        private const val ACTION_SETTLE_MS = 45L
        private const val SCAN_INTERVAL_MS = 14L
        private const val POST_JOIN_MIN_MS = 55L
        private const val PAUSED_POLL_MS = 180L
        private const val FOREGROUND_RECHECK_MS = 36L
        private const val DUMP_RETRY_MS = 28L
        private const val COMMAND_DUMP_COMPAT_RETRY_MS = 50L
        private const val UI_DUMP_TIMEOUT_MS = 4_500
        private const val UI_DUMP_FAILURE_STOP_MS = 9_000L
        private const val FOREGROUND_WAIT_STOP_MS = 12_000L
        private const val COMMUNITY_SCROLL_SETTLE_MS = 140L
        private const val COMMUNITY_NO_PROGRESS_LIMIT = 3
        private const val NEXT_LINK_SETTLE_MS = 0L
        private const val ACTIVITY_PROBE_TIMEOUT_MS = 1_500
        private const val ACTIVITY_PROBE_ATTEMPTS = 4
        private const val ACTIVITY_PROBE_RETRY_MS = 45L
        private const val RESULT_MIRROR_SYNC_EVERY = 1000
        private const val FAST_UI_MAX_NODES = 900
        private const val REQUEST_TERMINAL_PROBE_MIN_AGE_MS = 320L
        private const val REQUEST_TERMINAL_PROBE_COOLDOWN_MS = 280L
        private const val FAST_OPEN_BURST_MS = 1_100L
        private const val FAST_ACTION_BURST_MS = 750L
        private const val COMMUNITY_BACK_BURST_MS = 350L
        private const val VISUAL_PROFILE_PROBE_AFTER_MS = 140L
        private const val VISUAL_PROFILE_PROBE_INTERVAL_MS = 90L
        private const val VISUAL_MAX_PROBE_ATTEMPTS = 5
        private const val VISUAL_POST_ACTION_VERIFY_MS = 650L
        private const val VISUAL_MAX_TAP_ATTEMPTS = 2
        private const val VISUAL_DISMISS_SETTLE_MS = 30L
        private const val FAST_UI_DISABLE_AFTER_FAILURES = 4
        private const val FAST_UI_SESSION_RECOVERY_MAX = 1
        private const val COMMAND_DUMP_COOLDOWN_POLL_MS = 120L
        private const val DUMP_FAILURE_MIN_INTERVAL_MS = 120L
        private const val UI_TREE_FAILURE_MAX = 8
        private const val POST_ACTION_RESULT_GRACE_MS = 140L
        private const val VERIFY_RESULT_POLL_MS = 90L
        private const val USER_SERVICE_RESTART_MAX = 1
        private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 4_000L
        private const val PERIODIC_UI_REFRESH_EVERY = 100
        private const val USER_EXIT_LAUNCH_GRACE_MS = 650L
        private const val USER_EXIT_CONFIRM_MS = 140L
        private const val USER_EXIT_PROBE_INTERVAL_MS = 70L
        private const val USER_RETURN_PROBE_INTERVAL_MS = 180L
        private const val NOTIFICATION_THROTTLE_MS = 500L
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")
        private val COMPONENT_NAME = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.\$]+")
        private val DISPLAY_SIZE = Regex("(?:Physical size|Override size):\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ShizukuAutomationService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ShizukuAutomationService::class.java).setAction(ACTION_STOP))
        }
    }
}
