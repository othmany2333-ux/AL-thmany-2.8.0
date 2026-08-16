package com.althmany.groupmanager.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.althmany.groupmanager.GroupManagerApp
import com.althmany.groupmanager.domain.AccessibilityFailureType
import com.althmany.groupmanager.domain.AccessibilityJoinAction
import com.althmany.groupmanager.domain.AccessibilityInviteTarget
import com.althmany.groupmanager.domain.AccessibilityJoinMatcher
import com.althmany.groupmanager.domain.AccessibilityActionScoringPolicy
import com.althmany.groupmanager.domain.AdaptiveInteractionPolicy
import com.althmany.groupmanager.domain.AutomationPolicy
import com.althmany.groupmanager.domain.AutomationStage
import com.althmany.groupmanager.domain.AutomationStopReason
import com.althmany.groupmanager.domain.ContinuousHandoffPolicy
import com.althmany.groupmanager.domain.ConversationFastExitPolicy
import com.althmany.groupmanager.domain.HybridBackendPolicy
import com.althmany.groupmanager.domain.CommunityTraversalMatcher
import com.althmany.groupmanager.domain.CommunityTraversalPolicy
import com.althmany.groupmanager.domain.CommunityTraversalStage
import com.althmany.groupmanager.domain.ForegroundTargetPolicy
import com.althmany.groupmanager.domain.DualMessengerMatcher
import com.althmany.groupmanager.domain.InvitationStabilityPolicy
import com.althmany.groupmanager.domain.RuntimeCircuitBreaker
import com.althmany.groupmanager.domain.RuntimeCadencePolicy
import com.althmany.groupmanager.domain.RuntimeDecisionCoordinator
import com.althmany.groupmanager.domain.RuntimeDirective
import com.althmany.groupmanager.domain.RuntimeObservedScreen
import com.althmany.groupmanager.domain.RuntimeFeatureFlags
import com.althmany.groupmanager.domain.RuntimeIntelligencePolicy
import com.althmany.groupmanager.domain.RuntimeIdempotencyGuard
import com.althmany.groupmanager.domain.RuntimeScreenFingerprint
import com.althmany.groupmanager.domain.RuntimeRecoveryPolicy
import com.althmany.groupmanager.domain.RuntimeWatchdogPolicy
import com.althmany.groupmanager.domain.RuntimeWatchdogState
import com.althmany.groupmanager.domain.LinkRuntimePhase
import com.althmany.groupmanager.domain.RestrictionHandlingMode
import com.althmany.groupmanager.domain.ScreenEvidenceConflict
import com.althmany.groupmanager.domain.ScreenEvidencePolicy
import com.althmany.groupmanager.domain.ScreenEvidenceSummary
import com.althmany.groupmanager.domain.TerminalEscapePolicy
import com.althmany.groupmanager.domain.TerminalEscapeDecision
import com.althmany.groupmanager.domain.VisualActionButtonPolicy
import com.althmany.groupmanager.model.AutomationBackend
import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.LinkResultCode
import com.althmany.groupmanager.model.LinkStatus
import com.althmany.groupmanager.ui.MainActivity
import com.althmany.groupmanager.shizuku.ShizukuAutomationService
import com.althmany.groupmanager.shizuku.ShizukuBridge
import com.althmany.groupmanager.util.GroupJoinerResultStore
import com.althmany.groupmanager.util.NetworkStateMonitor
import com.althmany.groupmanager.util.AutomationScreenAwakeGuard
import com.althmany.groupmanager.util.LaunchDestination
import com.althmany.groupmanager.util.QuickJoinNotification
import com.althmany.groupmanager.util.ProfileEnvironment
import com.althmany.groupmanager.util.ProfileAccessibilityRuntime
import com.althmany.groupmanager.util.RuntimeDiagnosticStore
import com.althmany.groupmanager.util.RuntimeHealthMonitor
import com.althmany.groupmanager.util.WhatsAppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * AL-thmany 2.4.0 Native Multi-Profile context-aware Accessibility engine:
 * event monitoring + periodic rescans, group/community button clicks, gesture fallback,
 * result buckets (Joined / Fail / Left), then automatic opening of the next link.
 */
class QuickJoinAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processing = AtomicBoolean(false)
    private val actionExecuting = AtomicBoolean(false)
    private val resultCommitExecuting = AtomicBoolean(false)
    private val idempotencyGuard = RuntimeIdempotencyGuard()
    private val scanPending = AtomicBoolean(false)
    private val scheduledStartProcessing = AtomicBoolean(false)
    private var pollJob: Job? = null
    private var lastScanAt = 0L
    private var lastClickAt = 0L
    private var trackedLinkId = -1L
    private var loadingStartedAtElapsed = 0L
    private var loadingLinkId = -1L
    private var stableActionKey: String? = null
    private var stableActionScans = 0
    private var lastActionAttemptKey: String? = null
    private var lastActionAttemptAtElapsed = 0L
    private var actionAttempts = 0
    private var postJoinStableNonInviteScans = 0
    private var lastLoadingEndedAtElapsed = 0L
    private var stableOutcomeKey: String? = null
    private var stableOutcomeScans = 0
    private var homeSurfaceStableScans = 0
    private var unknownStableScans = 0
    private var conversationSurfaceStableScans = 0
    private var inviteScrollAttempts = 0
    private var stableConflictKey: ScreenEvidenceConflict = ScreenEvidenceConflict.NONE
    private var stableConflictScans = 0
    private var cachedCurrentLink: GroupLink? = null
    private var cachedSessionId: String? = null
    private var cachedSessionTotal = 0
    private var lastScreenFingerprint = 0L
    private var stableScreenFingerprintScans = 0
    private var consecutiveRuntimeFailures = 0
    private var lastRuntimeDiagnosticKey: String? = null
    private var lastRuntimeDiagnosticAtElapsed = 0L
    private var lastTargetForegroundAtElapsed = 0L
    private var outsideTargetCandidateStartedAtElapsed = 0L
    private var outsideTargetCandidatePackage: String? = null
    private var outsideTargetPauseJob: Job? = null
    private var lastNotificationRefreshAtElapsed = 0L
    private var rootUnavailableStartedAtElapsed = 0L
    private var communityHomeStableScans = 0
    private var communityEmptyStableScans = 0
    private var communityReturnBackSteps = 0
    private var communityStageStartedAtElapsed = 0L
    private var accessibilityVisualProbeLinkId = -1L
    private var accessibilityVisualProbeAttempts = 0
    private var lastAccessibilityVisualProbeAtElapsed = 0L
    private var accessibilityVisualActionTappedAtElapsed = 0L
    private var accessibilityVisualTapAttempts = 0
    private var accessibilityVisualExpectedAction: AccessibilityJoinAction? = null
    private var lastAutomationWindowClassName: String? = null
    private var lastAutomationWindowStateAtElapsed = 0L

    private val app: GroupManagerApp
        get() = application as GroupManagerApp


    private fun runtimeSpeed() = app.preferences.runtimeSpeedProfile()

    private fun currentProcessAndroidUserId(): Int =
        (android.os.Process.myUid() / 100_000).coerceAtLeast(0)

    private fun lockCurrentProcessUserOrReject(current: GroupLink): Boolean {
        val userId = currentProcessAndroidUserId()
        val ok = app.preferences.lockRuntimeAndroidUserId(userId)
        if (!ok) {
            runtimeDiagnostic(
                current,
                "ANDROID_USER_MISMATCH",
                "expected=${app.preferences.runtimeLockedAndroidUserId}; actual=$userId"
            )
        }
        return ok
    }

    /**
     * Accessibility is the compatibility-safe owner whenever it was selected, and it can reclaim
     * a live run if Shizuku was selected but its binder/runtime disappeared. This specifically
     * prevents a stale SHIZUKU runtime flag from making the personal Accessibility flow inert.
     */
    private fun accessibilityRuntimeActive(): Boolean {
        val prefs = app.preferences
        if (!prefs.accessibilityBatchRunning) return prefs.runtimeAutomationBackend != AutomationBackend.SHIZUKU
        val shizukuReady = runCatching { ShizukuBridge.status().ready }.getOrDefault(false)
        val mayOwn = HybridBackendPolicy.accessibilityMayTakeOver(prefs.runtimeAutomationBackend, shizukuReady)
        if (mayOwn && prefs.runtimeAutomationBackend == AutomationBackend.SHIZUKU && !shizukuReady) {
            prefs.runtimeAutomationBackend = AutomationBackend.ACCESSIBILITY
            prefs.accessibilityQuickJoin = true
            prefs.transitionAutomation(
                AutomationStage.LOOKING_FOR_PREVIEW,
                "Shizuku unavailable; Accessibility continuity fallback took over the current link",
                resetRetries = true
            )
            RuntimeDiagnosticStore.append(this, "BACKEND_FALLBACK", "SHIZUKU -> ACCESSIBILITY; current link preserved")
            runCatching { ShizukuAutomationService.stop(this) }
        }
        return mayOwn
    }

    override fun onCreate() {
        super.onCreate()
        liveInstance = this
        // Samsung may instantiate the enabled service before delivering onServiceConnected() to
        // the freshly started Activity process. Mark this profile-local instance alive immediately;
        // onUnbind/onDestroy still clear the signal, and every event refreshes the heartbeat.
        runtimeConnected = true
        runCatching {
            ProfileAccessibilityRuntime.recordServiceConnected(this)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        liveInstance = this
        runtimeConnected = true

        runCatching {
            ProfileAccessibilityRuntime.recordServiceConnected(this)
        }

        runCatching {
            val connectedProfile = ProfileEnvironment.current(this)
            RuntimeDiagnosticStore.append(
                this,
                "PROFILE_SERVICE_CONNECTED",
                "profile=${connectedProfile.profileKey}; handle=${connectedProfile.profileHandle}"
            )
        }
        pollJob?.cancel()
        if (app.preferences.communityTraversalActive) {
            // Community stage itself is persisted, but elapsedRealtime timestamps are process-local.
            // Re-arm the bounded timeout window after Accessibility recreation so OPENING/RETURNING
            // stages can still recover instead of waiting forever with a zero local timestamp.
            communityStageStartedAtElapsed = SystemClock.elapsedRealtime()
            communityHomeStableScans = 0
            communityEmptyStableScans = 0
            communityReturnBackSteps = 0
        }
        if (accessibilityRuntimeActive() &&
            app.preferences.autoResumeCurrentRun &&
            app.preferences.accessibilityBatchRunning &&
            !app.preferences.accessibilityPaused
        ) {
            app.preferences.transitionAutomation(
                AutomationStage.LOOKING_FOR_PREVIEW,
                "Accessibility reconnected; auto-resuming the current explicit run",
                resetRetries = true
            )
            requestScan()
        }
        if (accessibilityRuntimeActive() && app.preferences.accessibilityBatchRunning) {
            serviceScope.launch {
                val sessionId = app.preferences.accessibilitySessionId
                val state = withContext(Dispatchers.IO) {
                    if (sessionId == null) null else {
                        val current = app.repository.loadAutomationCurrent(sessionId)
                            ?: app.repository.loadAutomationNext(sessionId)
                        val total = app.repository.automationSessionTotal(sessionId) ?: 0
                        current to total
                    }
                }
                if (state != null) {
                    cachedCurrentLink = state.first
                    cachedSessionTotal = state.second
                }
                refreshAutomationNotification(force = true)
            }
        }
        requestScheduledStart()
        pollJob = serviceScope.launch {
            while (isActive) {
                val prefs = app.preferences

                if (prefs.accessibilityBatchRunning &&
                    !NetworkStateMonitor.isValidatedOnline(this@QuickJoinAccessibilityService)
                ) {
                    if (!prefs.pausedBecauseNetworkUnavailable) {
                        prefs.pauseAccessibilityBatch(
                            diagnostic = "Paused automatically because the internet connection is unavailable",
                            outsideTarget = prefs.pausedBecauseOutsideTarget
                        )
                        prefs.pausedBecauseNetworkUnavailable = true
                        runtimeDiagnostic(
                            cachedCurrentLink,
                            "ACCESSIBILITY_NETWORK_AUTO_PAUSE",
                            "saved link preserved; no next-link launch while offline"
                        )
                    }
                    AutomationScreenAwakeGuard.release()
                    delay(NETWORK_PAUSE_POLL_MS)
                    continue
                }

                if (prefs.accessibilityBatchRunning && prefs.pausedBecauseNetworkUnavailable) {
                    val activePackage = withContext(Dispatchers.Main.immediate) {
                        rootInActiveWindow?.packageName?.toString().orEmpty()
                    }
                    if (isAutomationWhatsAppPackage(activePackage) &&
                        (!prefs.pausedBecauseOutsideTarget || prefs.autoResumeCurrentRun)
                    ) {
                        prefs.resumeAccessibilityBatch(
                            "Internet restored; resuming saved invitation"
                        )
                        runtimeDiagnostic(
                            cachedCurrentLink,
                            "ACCESSIBILITY_NETWORK_AUTO_RESUME",
                            "validated internet + selected WhatsApp foreground"
                        )
                    } else {
                        delay(NETWORK_PAUSE_POLL_MS)
                        continue
                    }
                }

                AutomationScreenAwakeGuard.sync(
                    this@QuickJoinAccessibilityService,
                    app.preferences.keepScreenAwake &&
                        app.preferences.accessibilityBatchRunning &&
                        !app.preferences.accessibilityPaused
                )
                delay(runtimeSpeed().fallbackPollMs)
                ProfileAccessibilityRuntime.heartbeat(this@QuickJoinAccessibilityService)
                requestScheduledStart()
                requestScan()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        // Self-heal a delayed/missed callback flag. Receiving an AccessibilityEvent is definitive
        // proof that this exact enabled service instance is connected in the current Android user.
        if (!runtimeConnected) {
            runtimeConnected = true
            ProfileAccessibilityRuntime.recordServiceConnected(this)
            RuntimeDiagnosticStore.append(
                this,
                "PROFILE_SERVICE_EVENT_RECOVERED",
                ProfileEnvironment.current(this).profileKey
            )
        }
        val eventPackage = safeEvent.packageName?.toString().orEmpty()
        if (eventPackage.isNotBlank()) {
            ProfileAccessibilityRuntime.recordEvent(this, eventPackage)
        } else {
            ProfileAccessibilityRuntime.heartbeat(this)
        }
        if (!accessibilityRuntimeActive()) return
        if (safeEvent.eventType !in RELEVANT_EVENT_TYPES) return
        val packageName = eventPackage
        if (packageName.isBlank()) return

        // Package transitions are observed without reading the other app's window content. This lets
        // AL-thmany pause the explicit run as soon as the user leaves the selected WhatsApp app.
        if (safeEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isAutomationWhatsAppPackage(packageName)) {
                lastAutomationWindowClassName = safeEvent.className?.toString()
                lastAutomationWindowStateAtElapsed = SystemClock.elapsedRealtime()
                markTargetForeground()
                maybeAutoResumeOnTargetReturn()
            } else if (packageName !in RESOLVER_PACKAGES && !isTransientSystemPackage(packageName)) {
                scheduleAutoPauseOutsideTarget(packageName)
            }
        }

        if (isAutomationWhatsAppPackage(packageName) || packageName in RESOLVER_PACKAGES) {
            requestScan()
        }
    }

    private fun isAutomationWhatsAppPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val prefs = app.preferences
        val profile = ProfileEnvironment.current(this)
        val lockedProfile = prefs.runtimeLockedProfileKey
        if (!lockedProfile.isNullOrBlank() && lockedProfile != profile.profileKey) return false
        val locked = prefs.runtimeLockedWhatsAppPackage
        if (!locked.isNullOrBlank()) return packageName == locked
        val selected = prefs.selectedWhatsAppPackage
        if (!selected.isNullOrBlank()) return packageName == selected
        return when (prefs.preferredTarget) {
            com.althmany.groupmanager.model.PreferredTarget.PERSONAL ->
                packageName == WhatsAppLauncher.WHATSAPP_PACKAGE
            com.althmany.groupmanager.model.PreferredTarget.BUSINESS ->
                packageName == WhatsAppLauncher.WHATSAPP_BUSINESS_PACKAGE
            com.althmany.groupmanager.model.PreferredTarget.CLONED ->
                packageName == WhatsAppLauncher.WHATSAPP_CLONED_PACKAGE
            com.althmany.groupmanager.model.PreferredTarget.AUTO ->
                !profile.requiresExplicitAutoTarget && WhatsAppLauncher.isDiscoveredWhatsAppPackage(this, packageName)
            com.althmany.groupmanager.model.PreferredTarget.BROWSER -> false
        }
    }


    /**
     * Conservative target recovery used by the merged 2.8.0 runtime.
     *
     * It only repairs stale runtime locks for AUTO target selection (or when the already selected
     * package is exactly the package now visible). It never switches an explicit Personal,
     * Business or cloned target to a different WhatsApp package.
     */
    private fun recoverRuntimeWhatsAppBindingIfSafe(activePackage: String): Boolean {
        if (activePackage.isBlank()) return false
        if (!WhatsAppLauncher.isDiscoveredWhatsAppPackage(this, activePackage)) return false

        val prefs = app.preferences
        val profile = ProfileEnvironment.current(this)
        val lockedProfile = prefs.runtimeLockedProfileKey
        if (!lockedProfile.isNullOrBlank() && lockedProfile != profile.profileKey) return false

        val selected = prefs.selectedWhatsAppPackage
        val safeToRecover = when {
            selected == activePackage -> true
            prefs.preferredTarget == com.althmany.groupmanager.model.PreferredTarget.AUTO && selected.isNullOrBlank() -> true
            else -> false
        }
        if (!safeToRecover) return false

        prefs.lockRuntimeTarget(activePackage, profile.profileKey)
        return true
    }

    private fun isTransientSystemPackage(packageName: String): Boolean =
        packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName in RESOLVER_PACKAGES ||
            isCurrentInputMethodPackage(packageName)

    private fun isCurrentInputMethodPackage(packageName: String): Boolean {
        val currentIme = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty()
        return currentIme.substringBefore('/').equals(packageName, ignoreCase = false)
    }

    private fun markTargetForeground() {
        lastTargetForegroundAtElapsed = SystemClock.elapsedRealtime()
        outsideTargetCandidateStartedAtElapsed = 0L
        outsideTargetCandidatePackage = null
        outsideTargetPauseJob?.cancel()
        outsideTargetPauseJob = null
    }

    /**
     * Android can emit one-frame package transitions for keyboards, resolvers and overlays while
     * WhatsApp is still visually foreground. Confirm a stable departure before pausing so a run
     * never freezes inside a newly joined group because of a transient window event.
     */
    private fun scheduleAutoPauseOutsideTarget(packageName: String) {
        val prefs = app.preferences
        if (!prefs.accessibilityBatchRunning || prefs.accessibilityPaused || !prefs.autoPauseOutsideWhatsApp) return

        val now = SystemClock.elapsedRealtime()
        if (outsideTargetCandidatePackage != packageName) {
            outsideTargetCandidatePackage = packageName
            outsideTargetCandidateStartedAtElapsed = now
        }

        outsideTargetPauseJob?.cancel()
        outsideTargetPauseJob = serviceScope.launch {
            delay(ForegroundTargetPolicy.OUTSIDE_TARGET_CONFIRM_MS)
            val currentPrefs = app.preferences
            if (!currentPrefs.accessibilityBatchRunning || currentPrefs.accessibilityPaused ||
                !currentPrefs.autoPauseOutsideWhatsApp) return@launch

            val activePackage = withContext(Dispatchers.Main.immediate) {
                rootInActiveWindow?.packageName?.toString().orEmpty()
            }
            val stillOutside = activePackage.isNotBlank() &&
                !isAutomationWhatsAppPackage(activePackage) &&
                activePackage !in RESOLVER_PACKAGES &&
                !isTransientSystemPackage(activePackage)
            val checkedAt = SystemClock.elapsedRealtime()
            val candidateAge = (checkedAt - outsideTargetCandidateStartedAtElapsed).coerceAtLeast(0L)
            val sinceTarget = if (lastTargetForegroundAtElapsed > 0L) {
                (checkedAt - lastTargetForegroundAtElapsed).coerceAtLeast(0L)
            } else Long.MAX_VALUE

            if (!ForegroundTargetPolicy.shouldPauseOutsideTarget(candidateAge, sinceTarget, stillOutside)) {
                return@launch
            }

            currentPrefs.pauseAccessibilityBatch(
                diagnostic = "Paused automatically after a stable exit from the selected WhatsApp target ($activePackage)",
                outsideTarget = true
            )
            refreshAutomationNotification(force = true)
        }
    }

    private fun maybeAutoResumeOnTargetReturn() {
        val prefs = app.preferences
        if (!prefs.accessibilityBatchRunning || !prefs.accessibilityPaused ||
            !prefs.pausedBecauseOutsideTarget
        ) return

        if (prefs.autoResumeCurrentRun &&
            !prefs.pausedBecauseNetworkUnavailable &&
            NetworkStateMonitor.isValidatedOnline(this)
        ) {
            prefs.resumeAccessibilityBatch(
                "Returned to the selected WhatsApp; resuming saved invitation"
            )
            runtimeDiagnostic(
                cachedCurrentLink,
                "ACCESSIBILITY_TARGET_RETURN_AUTO_RESUME",
                "real WhatsApp window event; no forced reopen"
            )
            requestScan()
        } else {
            runtimeDiagnostic(
                cachedCurrentLink,
                "ACCESSIBILITY_TARGET_RETURN_WAITING_MANUAL_RESUME",
                "target returned; manual Resume remains available"
            )
        }
        refreshAutomationNotification(force = true)
    }

    private fun requestScheduledStart() {
        val preferences = app.preferences
        if (!preferences.accessibilityQuickJoin ||
            preferences.accessibilityBatchRunning ||
            !preferences.hasScheduledStart ||
            System.currentTimeMillis() < preferences.scheduledStartAtMillis
        ) return

        if (!scheduledStartProcessing.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                launchScheduledBatch()
            } finally {
                scheduledStartProcessing.set(false)
            }
        }
    }

    private suspend fun launchScheduledBatch() {
        val sessionId = app.preferences.scheduledSessionId ?: return

        // Resolve and pin the profile-local WhatsApp package BEFORE mutating queue state. If the
        // user removed/moved WhatsApp after scheduling, keep the link untouched instead of burning
        // the whole explicit run against an unavailable or unintended target.
        val targetValidation = WhatsAppLauncher.validateTarget(
            this,
            app.preferences.preferredTarget,
            app.preferences.selectedWhatsAppPackage
        )
        val targetPackage = targetValidation.packageName
        if (!targetValidation.valid || targetPackage.isNullOrBlank()) {
            app.preferences.clearScheduledStart()
            app.preferences.stopAccessibilityBatch(
                AutomationStopReason.TARGET_UNSUPPORTED,
                "Scheduled start requires an explicit WhatsApp app in the same Android profile"
            )
            QuickJoinNotification.cancel(this)
            return
        }
        val launchState = withContext(Dispatchers.IO) {
            if (app.preferences.activeSessionId != sessionId) {
                app.preferences.clearScheduledStart()
                return@withContext null
            }
            val next = app.repository.loadAutomationNext(sessionId)
            val total = app.repository.automationSessionTotal(sessionId) ?: 0
            if (next == null || total <= 0) {
                app.preferences.clearScheduledStart()
                return@withContext null
            }
            app.preferences.startAccessibilityBatch(sessionId)
            // startAccessibilityBatch clears stale runtime state, so pin the just-validated target
            // again for the lifetime of this scheduled explicit run.
            app.preferences.lockRuntimeTarget(targetPackage, targetValidation.profileKey)
            val opened = if (next.status == LinkStatus.OPENED) next else app.repository.markOpened(next.id)
            ScheduledLaunchState(opened, total)
        } ?: return

        val opened = launchState.link ?: run {
            stopBatch(AutomationStopReason.OPEN_FAILED, "Scheduled link could not be prepared")
            return
        }
        QuickJoinNotification.showAutomation(
            this,
            processedInBatch = 0,
            currentLinkNumber = opened.position + 1,
            totalLinks = launchState.total,
            delaySeconds = app.preferences.accessibilityJoinDelaySeconds,
            paused = false
        )

        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ALthmany:ScheduledStart"
        ).apply {
            setReferenceCounted(false)
            acquire(SCHEDULED_WAKE_LOCK_MS)
        }
        val destination = try {
            withContext(Dispatchers.Main.immediate) {
                WhatsAppLauncher.launch(
                    this@QuickJoinAccessibilityService,
                    opened.url,
                    app.preferences.preferredTarget,
                    app.preferences.runtimeLockedWhatsAppPackage ?: app.preferences.selectedWhatsAppPackage,
                    strictProfileTarget = app.preferences.strictProfileTargeting,
                    expectedProfileKey = app.preferences.runtimeLockedProfileKey
                )
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }

        when (destination) {
            LaunchDestination.PERSONAL,
            LaunchDestination.BUSINESS,
            LaunchDestination.CLONED,
            LaunchDestination.SELECTED,
            LaunchDestination.DUAL_CHOOSER -> {
                app.preferences.markAutomationLaunched()
                requestScan()
            }
            LaunchDestination.BROWSER -> completeAndAdvance(
                opened,
                LinkStatus.FAILED,
                LinkResultCode.BROWSER_FALLBACK,
                "Scheduled start opened outside the selected WhatsApp app"
            )
            LaunchDestination.NONE -> completeAndAdvance(
                opened,
                LinkStatus.FAILED,
                LinkResultCode.OPEN_FAILED,
                "Scheduled start could not open the selected WhatsApp app"
            )
        }
    }

    private fun requestScan() {
        if (!accessibilityRuntimeActive()) return
        // Self-heal stale preference state left by a failed backend switch.
        if (!app.preferences.accessibilityQuickJoin) app.preferences.accessibilityQuickJoin = true
        refreshAutomationNotification(force = false)
        if (!app.preferences.accessibilityQuickJoin ||
            !app.preferences.accessibilityBatchRunning ||
            app.preferences.accessibilityPaused
        ) return

        if (app.preferences.accessibilityProcessedCount >= AutomationPolicy.BATCH_SIZE) {
            finishBatch(AutomationStopReason.BATCH_LIMIT_REACHED, "Run window completed; queued links remain available for the next explicit batch")
            return
        }

        // Coalesce Accessibility event bursts into one serialized scan loop. WhatsApp can emit
        // dozens of events while replacing a sheet; starting a coroutine for every event caused
        // avoidable CPU pressure and UI stalls on mid-range phones.
        scanPending.set(true)
        if (!processing.compareAndSet(false, true)) return

        serviceScope.launch {
            try {
                while (scanPending.getAndSet(false) && isActive) {
                    val speed = runtimeSpeed()
                    val minScanInterval =
                        if (stableScreenFingerprintScans >= RuntimeCadencePolicy.STABLE_SCREEN_RELAX_AFTER_SCANS) {
                            speed.stableScanMs
                        } else {
                            speed.eventScanMs
                        }
                    val elapsed = SystemClock.elapsedRealtime() - lastScanAt
                    if (elapsed < minScanInterval) delay(minScanInterval - elapsed)
                    lastScanAt = SystemClock.elapsedRealtime()
                    processCurrentScreen()
                }
            } finally {
                processing.set(false)
                if (scanPending.get()) requestScan()
            }
        }
    }

    private suspend fun processCurrentScreen() {
        // 2.8.0 merged runtime guard: keep the proven 2.4.4 run-state protection
        // before entering the newer 2.7.5 screen pipeline.
        if (!app.preferences.accessibilityBatchRunning || app.preferences.accessibilityPaused) return

        val current = withContext(Dispatchers.IO) { loadCurrentLinkOrStop() } ?: return
        app.preferences.beginRuntimeLink(
            current.id,
            current.position,
            current.url,
            "ACCESSIBILITY"
        )
        if (!lockCurrentProcessUserOrReject(current)) {
            completeAndAdvance(
                current,
                LinkStatus.FAILED,
                LinkResultCode.UNKNOWN_SCREEN,
                "Android user/profile identity changed during the run; skipped without clicking",
                fastAdvance = true,
                surfaceAlreadyExited = true
            )
            return
        }
        ensureTrackingFor(current.id)
        val root = withContext(Dispatchers.Main.immediate) { rootInActiveWindow }
        if (root == null) {
            ProfileAccessibilityRuntime.recordRoot(this, available = false, packageName = null)
            handleUnavailableRoot(current)
            return
        }
        rootUnavailableStartedAtElapsed = 0L
        val activePackage = root.packageName?.toString().orEmpty()
        ProfileAccessibilityRuntime.recordRoot(this, available = true, packageName = activePackage)
        if (activePackage in RESOLVER_PACKAGES && app.preferences.preferredTarget == com.althmany.groupmanager.model.PreferredTarget.CLONED) {
            handleDualMessengerResolver(root)
            return
        }
        if (!isAutomationWhatsAppPackage(activePackage)) {
            // 2.8.0 recovery merge: 2.7.5 used to return silently here. That could leave a
            // running queue stuck forever after WhatsApp opened under a stale/automatic target
            // binding. Recover only when the foreground package is a locally discovered WhatsApp
            // package and the current target policy makes that rebinding safe. Explicit user
            // package selections are never overridden.
            if (recoverRuntimeWhatsAppBindingIfSafe(activePackage)) {
                runtimeDiagnostic(current, "TARGET_RECOVERED", "Recovered runtime target to $activePackage")
                requestScan()
                return
            }

            if (activePackage.isNotBlank() && activePackage !in RESOLVER_PACKAGES &&
                !isTransientSystemPackage(activePackage)
            ) {
                // Keep 2.7.5's debounced outside-target policy instead of freezing silently.
                scheduleAutoPauseOutsideTarget(activePackage)
            }
            return
        }
        markTargetForeground()

        val screen = inspectScreen(root)
        app.preferences.markRuntimePhase(
            when {
                screen.loading -> LinkRuntimePhase.OPENING
                screen.action == AccessibilityJoinAction.PREVIEW -> LinkRuntimePhase.PREVIEW
                screen.action != null -> LinkRuntimePhase.ACTION_READY
                readPendingAction(current) != null -> LinkRuntimePhase.VERIFYING
                else -> app.preferences.runtimeLinkPhase
            },
            "ACCESSIBILITY:${screen.action?.name ?: "SCREEN"}"
        )
        updateScreenFingerprintStability(screen)
        runtimeDiagnostic(
            current,
            "SCREEN",
            "fp=${screen.fingerprint}; stable=$stableScreenFingerprintScans; invite=${screen.inviteContext}; " +
                "loading=${screen.loading}; action=${screen.action?.name ?: "NONE"}; target=${screen.actionTarget.name}; terminal=${screen.terminalEvidenceCount}; margin=${screen.actionScoreMargin}; " +
                "home=${screen.homeSurface}; conversation=${screen.conversationSurface}; conflict=${screen.evidenceConflict.name}"
        )

        val terminalEscape = TerminalEscapePolicy.assess(
            failure = screen.failure,
            requestSubmitted = screen.requestSubmitted,
            alreadyMember = screen.alreadyMember,
            restricted = screen.restricted
        )
        val holdConflict = if (screen.evidenceConflict == ScreenEvidenceConflict.NONE) {
            resetConflictEvidence()
            false
        } else {
            shouldHoldConflictingEvidence(screen)
        }
        val directive = RuntimeDecisionCoordinator.decide(
            RuntimeObservedScreen(
                restricted = screen.restricted,
                loading = screen.loading,
                conflict = screen.evidenceConflict,
                hasTerminalEvidence = hasTerminalEvidence(screen),
                hasAction = screen.action != null && screen.actionNode != null,
                immediateTerminal = terminalEscape.immediate
            ),
            conflictShouldHold = holdConflict
        )
        runtimeDiagnostic(
            current,
            "DIRECTIVE",
            "${directive.name}; terminalEscape=${terminalEscape.mode.name}; stableScreen=$stableScreenFingerprintScans"
        )
        RuntimeHealthMonitor.updateScreen(
            nowElapsedMs = SystemClock.elapsedRealtime(),
            fingerprint = screen.fingerprint,
            stableScreenScans = stableScreenFingerprintScans,
            directive = directive.name,
            action = screen.action?.name ?: "NONE",
            conflict = screen.evidenceConflict.name
        )

        if (!screen.restricted) {
            if (maybeStartAlreadyMemberCommunity(current, screen)) return
            if (maybeHandleCommunityTraversal(current, screen)) return
        }

        when (directive) {
            RuntimeDirective.STOP_RESTRICTED -> {
                resetAdaptiveEvidence()
                resetConflictEvidence()
                if (app.preferences.restrictionHandlingMode == RestrictionHandlingMode.STOP_RUN) {
                    withContext(Dispatchers.IO) {
                        app.repository.markStatus(
                            current.id,
                            LinkStatus.FAILED,
                            LinkResultCode.RESTRICTED,
                            app.preferences.buildRuntimeAuditDetail(
                                "WhatsApp displayed a restriction/retry-later screen",
                                LinkResultCode.RESTRICTED.name,
                                "ACCESSIBILITY"
                            )
                        )
                    }
                    stopBatch(
                        AutomationStopReason.RESTRICTED_SCREEN,
                        "Restriction detected; user policy is Stop run"
                    )
                } else {
                    completeAndAdvance(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.RESTRICTED,
                        "Restriction recorded for this link; no bypass attempted",
                        fastAdvance = true,
                        terminalEscapeAdvance = true
                    )
                }
            }

            RuntimeDirective.WAIT_LOADING -> {
                resetOutcomeEvidence()
                resetConflictEvidence()
                homeSurfaceStableScans = 0
                unknownStableScans = 0
                conversationSurfaceStableScans = 0
                handleLoading(current)
            }

            RuntimeDirective.WAIT_CONFLICT -> {
                resetOutcomeEvidence()
                homeSurfaceStableScans = 0
                unknownStableScans = 0
                conversationSurfaceStableScans = 0
                app.preferences.transitionAutomation(
                    AutomationStage.WAITING_FOR_WHATSAPP,
                    "Conflicting WhatsApp accessibility evidence detected; waiting for the screen transition to settle",
                    resetRetries = false
                )
            }

            RuntimeDirective.HANDLE_TERMINAL -> {
                clearLoadingTracking()
                resetConflictEvidence()
                homeSurfaceStableScans = 0
                unknownStableScans = 0
                conversationSurfaceStableScans = 0
                handleStableTerminalEvidence(current, screen, terminalEscape)
            }

            RuntimeDirective.HANDLE_ACTION -> {
                val action = screen.action ?: return
                val actionNode = screen.actionNode ?: return
                resetOutcomeEvidence()
                resetConflictEvidence()
                homeSurfaceStableScans = 0
                unknownStableScans = 0
                conversationSurfaceStableScans = 0
                clearLoadingTracking()
                postJoinStableNonInviteScans = 0
                handleVisibleAction(current, action, actionNode, screen)
            }

            RuntimeDirective.HANDLE_UNKNOWN -> {
                resetOutcomeEvidence()
                resetConflictEvidence()
                clearLoadingTracking()
                handleUnknown(current, screen)
            }
        }
    }

    private fun hasTerminalEvidence(screen: ScreenInspection): Boolean =
        screen.failure != null ||
            screen.requestSubmitted ||
            screen.alreadyMember ||
            (screen.restricted &&
                app.preferences.restrictionHandlingMode == RestrictionHandlingMode.SKIP_AND_CONTINUE)

    private suspend fun handleStableTerminalEvidence(
        current: GroupLink,
        screen: ScreenInspection,
        terminalEscape: TerminalEscapeDecision
    ) {
        val sinceLoadingEnded = if (lastLoadingEndedAtElapsed > 0L) {
            (SystemClock.elapsedRealtime() - lastLoadingEndedAtElapsed).coerceAtLeast(0L)
        } else Long.MAX_VALUE
        if (!terminalEscape.immediate && AdaptiveInteractionPolicy.shouldWaitAfterLoading(
                sinceLoadingEnded,
                turbo = app.preferences.fastHandsFreeMode
            )) {
            resetOutcomeEvidence()
            return
        }

        val key = when {
            screen.failure == AccessibilityFailureType.REMOVED_OR_BANNED -> "REMOVED_OR_BANNED"
            screen.failure == AccessibilityFailureType.INVALID_OR_EXPIRED -> "INVALID_OR_EXPIRED"
            screen.failure == AccessibilityFailureType.GROUP_FULL -> "GROUP_FULL"
            screen.failure == AccessibilityFailureType.GENERIC -> "GENERIC_FAILURE"
            screen.requestSubmitted -> "REQUEST_SUBMITTED"
            screen.alreadyMember -> "ALREADY_MEMBER"
            else -> return
        }

        if (stableOutcomeKey == key) stableOutcomeScans += 1
        else {
            stableOutcomeKey = key
            stableOutcomeScans = 1
        }

        if (terminalEscape.immediate) {
            // Instant Terminal Router: a specific terminal state is authoritative on the first
            // visible frame. It bypasses Loading/conflict/stability confidence waits, but never
            // bypasses a WhatsApp restriction because restrictions are handled before this path.
            runtimeDiagnostic(
                current,
                "TERMINAL_ESCAPE_FAST_PATH",
                "key=$key; reason=${terminalEscape.reason}; first-frame handoff"
            )
        } else {
            val strongTurboTerminal = app.preferences.fastHandsFreeMode &&
                !screen.loading &&
                screen.evidenceConflict == ScreenEvidenceConflict.NONE &&
                screen.terminalEvidenceCount == 1 &&
                screen.positiveActionCount == 0
            if (!strongTurboTerminal && !AdaptiveInteractionPolicy.shouldTrustOutcome(stableOutcomeScans)) {
                app.preferences.transitionAutomation(
                    AutomationStage.VERIFYING_RESULT,
                    "WhatsApp result detected; waiting for one confirming scan before acting",
                    resetRetries = false
                )
                return
            }

            if (RuntimeFeatureFlags.CONFIDENCE_ENGINE) {
                val assessment = RuntimeIntelligencePolicy.assessTerminal(
                    loading = screen.loading,
                    terminalEvidenceCount = screen.terminalEvidenceCount,
                    positiveActionCount = screen.positiveActionCount,
                    conflict = screen.evidenceConflict,
                    stableOutcomeScans = stableOutcomeScans,
                    stableScreenScans = stableScreenFingerprintScans
                )
                runtimeDiagnostic(
                    current,
                    "TERMINAL_CONFIDENCE",
                    "key=$key; score=${assessment.score}; band=${assessment.band}; ${assessment.reason}"
                )
                RuntimeHealthMonitor.updateConfidence(SystemClock.elapsedRealtime(), assessment.score, assessment.band.name)
                if (!assessment.safeToAct) {
                    app.preferences.transitionAutomation(
                        AutomationStage.VERIFYING_RESULT,
                        "Terminal state confidence ${assessment.score}% is not strong enough yet; waiting for WhatsApp to settle",
                        resetRetries = false
                    )
                    return
                }
            }
        }

        if (key == "ALREADY_MEMBER" &&
            screen.screenTarget == AccessibilityInviteTarget.COMMUNITY &&
            app.preferences.communityTraversalEnabled &&
            screen.communityOpenNode != null &&
            maybeStartAlreadyMemberCommunity(current, screen)
        ) {
            return
        }

        when (key) {
            "GROUP_FULL" -> completeAndAdvance(
                current, LinkStatus.SKIPPED, LinkResultCode.GROUP_FULL, "Group is full",
                fastAdvance = true, terminalEscapeAdvance = terminalEscape.bypassInterLinkDelay
            )
            "INVALID_OR_EXPIRED" -> completeAndAdvance(
                current, LinkStatus.SKIPPED, LinkResultCode.INVALID_OR_EXPIRED,
                "Invite link is invalid, reset, expired, or no longer available", fastAdvance = true,
                terminalEscapeAdvance = terminalEscape.bypassInterLinkDelay
            )
            "REMOVED_OR_BANNED" -> completeAndAdvance(
                current, LinkStatus.SKIPPED, LinkResultCode.REMOVED_OR_BANNED,
                "This account was removed from the group or community", fastAdvance = true,
                terminalEscapeAdvance = terminalEscape.bypassInterLinkDelay
            )
            "GENERIC_FAILURE" -> completeAndAdvance(
                current, LinkStatus.FAILED, LinkResultCode.WHATSAPP_REJECTED,
                "WhatsApp could not complete the join", fastAdvance = true,
                terminalEscapeAdvance = terminalEscape.bypassInterLinkDelay
            )
            "REQUEST_SUBMITTED" -> {
                val requestSurfaceExited = fastExitPendingRequestSurface(screen)
                completeAndAdvance(
                    current, LinkStatus.REQUESTED, LinkResultCode.REQUEST_SENT, "Join request sent",
                    fastAdvance = true,
                    surfaceAlreadyExited = requestSurfaceExited,
                    terminalEscapeAdvance = terminalEscape.bypassInterLinkDelay
                )
            }
            "ALREADY_MEMBER" -> completeAndAdvance(
                current, LinkStatus.SKIPPED, LinkResultCode.ALREADY_MEMBER, "Already a member",
                fastAdvance = true, terminalEscapeAdvance = terminalEscape.bypassInterLinkDelay
            )
        }
    }

    private fun resetOutcomeEvidence() {
        stableOutcomeKey = null
        stableOutcomeScans = 0
    }

    private fun resetAdaptiveEvidence() {
        resetOutcomeEvidence()
        resetConflictEvidence()
        homeSurfaceStableScans = 0
        unknownStableScans = 0
    }

    private fun updateScreenFingerprintStability(screen: ScreenInspection) {
        if (!RuntimeFeatureFlags.SCREEN_FINGERPRINT) {
            stableScreenFingerprintScans = 0
            return
        }
        if (lastScreenFingerprint == screen.fingerprint) {
            stableScreenFingerprintScans = (stableScreenFingerprintScans + 1).coerceAtMost(50)
        } else {
            lastScreenFingerprint = screen.fingerprint
            stableScreenFingerprintScans = 1
        }
    }

    private fun runtimeDiagnostic(current: GroupLink?, category: String, detail: String) {
        if (!RuntimeFeatureFlags.DIAGNOSTIC_JOURNAL || !app.preferences.runtimeDiagnosticJournal) return
        val key = "${current?.id ?: -1L}:$category:$detail"
        val now = SystemClock.elapsedRealtime()
        if (key == lastRuntimeDiagnosticKey && now - lastRuntimeDiagnosticAtElapsed < DIAGNOSTIC_REPEAT_SUPPRESSION_MS) {
            return
        }
        lastRuntimeDiagnosticKey = key
        lastRuntimeDiagnosticAtElapsed = now
        serviceScope.launch(Dispatchers.IO) {
            RuntimeDiagnosticStore.append(
                this@QuickJoinAccessibilityService,
                category,
                "link=${current?.position?.plus(1) ?: 0}; id=${current?.id ?: -1L}; $detail"
            )
        }
    }

    private fun shouldHoldConflictingEvidence(screen: ScreenInspection): Boolean {
        val conflict = screen.evidenceConflict
        if (conflict == ScreenEvidenceConflict.NONE) {
            resetConflictEvidence()
            return false
        }
        if (stableConflictKey == conflict) stableConflictScans += 1
        else {
            stableConflictKey = conflict
            stableConflictScans = 1
        }
        return ScreenEvidencePolicy.shouldHold(conflict, stableConflictScans)
    }

    private fun resetConflictEvidence() {
        stableConflictKey = ScreenEvidenceConflict.NONE
        stableConflictScans = 0
    }

    private suspend fun clickAction(
        current: GroupLink,
        action: AccessibilityJoinAction,
        node: AccessibilityNodeInfo,
        target: AccessibilityInviteTarget = AccessibilityInviteTarget.UNKNOWN
    ) {
        if (!actionExecuting.compareAndSet(false, true)) {
            runtimeDiagnostic(current, "ACTION_LOCK", "Skipped overlapping ${action.name} execution")
            return
        }
        try {
            val now = SystemClock.elapsedRealtime()
            val stableKey = stableActionKeyFor(current.id, action, node)
            val attemptOrdinal = if (lastActionAttemptKey == stableKey) actionAttempts else 0
            // Each bounded retry is a distinct execution attempt. The previous implementation
            // recorded the first ACTION_CLICK as a success and then suppressed the gesture retry
            // for 1.2 seconds even when WhatsApp visibly ignored that node action.
            val actionKey = "${current.id}:${action.name}:$attemptOrdinal"
            if (!idempotencyGuard.shouldAllow(actionKey, now, IDEMPOTENCY_SUPPRESSION_MS)) {
                runtimeDiagnostic(current, "IDEMPOTENCY", "Suppressed duplicate ${action.name} action")
                return
            }
            val clickThrottle = runtimeSpeed().clickThrottleMs
            if (now - lastClickAt < clickThrottle) return

            val gestureFirst = action in setOf(
                AccessibilityJoinAction.JOIN,
                AccessibilityJoinAction.REQUEST,
                AccessibilityJoinAction.CONFIRM
            ) && attemptOrdinal % 2 == 0
            val clicked = withContext(Dispatchers.Main.immediate) {
                clickNodeParentOrGesture(node, gestureFirst = gestureFirst)
            }
            if (!clicked) {
                runtimeDiagnostic(current, "CLICK", "${action.name} could not be dispatched")
                val retry = app.preferences.incrementAutomationRetry()
                if (retry >= MAX_CLICK_RETRIES) {
                    completeAndAdvance(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.ACTION_TIMEOUT,
                        "The join control could not be clicked after automatic retries"
                    )
                }
                return
            }
            lastClickAt = now
            idempotencyGuard.recordSuccess(actionKey, now)
            recordActionAttempt(current.id, action, node)
            app.preferences.recordRuntimeAction(action.name, "ACCESSIBILITY")
            app.preferences.markRuntimePhase(
                LinkRuntimePhase.ACTION_TAPPED,
                "ACCESSIBILITY:${action.name}"
            )
            runtimeDiagnostic(current, "CLICK", "Executed ${action.name}; attempt=$actionAttempts")

            when (action) {
                AccessibilityJoinAction.PREVIEW -> {
                    app.preferences.clearAccessibilityPending()
                    app.preferences.transitionAutomation(
                        AutomationStage.LOOKING_FOR_JOIN,
                        "View group clicked; waiting for group screen",
                        resetRetries = true
                    )
                }
                AccessibilityJoinAction.JOIN,
                AccessibilityJoinAction.REQUEST -> {
                    app.preferences.setAccessibilityPending(current.id, action.name, target)
                    app.preferences.transitionAutomation(
                        AutomationStage.VERIFYING_RESULT,
                        if (action == AccessibilityJoinAction.JOIN) {
                            if (target == AccessibilityInviteTarget.COMMUNITY) {
                                "Join community clicked; verifying entry before subgroup traversal"
                            } else {
                                "Join group clicked; checking for confirmation"
                            }
                        } else {
                            "Request to join clicked; checking for confirmation"
                        },
                        resetRetries = true
                    )
                    schedulePendingInference(current.id, action)
                    scheduleContinuousHandoffWatchdog(current.id, action)
                }
                AccessibilityJoinAction.CONFIRM -> {
                    val pending = readPendingAction(current) ?: AccessibilityJoinAction.JOIN
                    app.preferences.transitionAutomation(
                        AutomationStage.VERIFYING_RESULT,
                        "Confirmation clicked; verifying the final WhatsApp result",
                        resetRetries = true
                    )
                    schedulePendingInference(current.id, pending)
                    scheduleContinuousHandoffWatchdog(current.id, pending)
                }
            }
        } finally {
            actionExecuting.set(false)
        }
    }

    private fun schedulePendingInference(linkId: Long, action: AccessibilityJoinAction) {
        serviceScope.launch {
            delay(resultInferenceDelayMs())
            if (!app.preferences.accessibilityBatchRunning || app.preferences.accessibilityPaused) return@launch
            if (app.preferences.accessibilityPendingLinkId != linkId) return@launch

            val current = withContext(Dispatchers.IO) {
                val sessionId = app.preferences.accessibilitySessionId ?: return@withContext null
                app.repository.loadAutomationCurrent(sessionId)?.takeIf { it.id == linkId }
            } ?: return@launch

            val inspection = withContext(Dispatchers.Main.immediate) {
                rootInActiveWindow
                    ?.takeIf { isAutomationWhatsAppPackage(it.packageName?.toString().orEmpty()) }
                    ?.let(::inspectScreen)
            }

            when {
                inspection?.restricted == true -> {
                    withContext(Dispatchers.IO) {
                        app.repository.markStatus(
                            current.id,
                            LinkStatus.FAILED,
                            LinkResultCode.RESTRICTED,
                            "WhatsApp displayed a restriction after the join action"
                        )
                    }
                    stopBatch(
                        AutomationStopReason.RESTRICTED_SCREEN,
                        "Restriction screen detected after action"
                    )
                }
                inspection != null && hasTerminalEvidence(inspection) -> {
                    clearLoadingTracking()
                    val terminalEscape = TerminalEscapePolicy.assess(
                        failure = inspection.failure,
                        requestSubmitted = inspection.requestSubmitted,
                        alreadyMember = inspection.alreadyMember,
                        restricted = inspection.restricted
                    )
                    handleStableTerminalEvidence(current, inspection, terminalEscape)
                }
                inspection?.loading == true -> {
                    app.preferences.transitionAutomation(
                        AutomationStage.WAITING_FOR_WHATSAPP,
                        "WhatsApp is still loading the invitation/result; waiting without closing it",
                        resetRetries = false
                    )
                }
                inspection?.action == AccessibilityJoinAction.CONFIRM -> {
                    app.preferences.transitionAutomation(
                        AutomationStage.VERIFYING_RESULT,
                        "Confirmation is visible; pressing it automatically",
                        resetRetries = false
                    )
                    lastClickAt = 0L
                    requestScan()
                }
                inspection?.action == action -> {
                    val retry = app.preferences.incrementAutomationRetry()
                    if (retry >= MAX_CLICK_RETRIES) {
                        completeAndAdvance(
                            current,
                            LinkStatus.FAILED,
                            LinkResultCode.ACTION_TIMEOUT,
                            "The join button remained visible after repeated clicks"
                        )
                    } else {
                        app.preferences.transitionAutomation(
                            AutomationStage.LOOKING_FOR_JOIN,
                            "Join action still visible; retry $retry of $MAX_CLICK_RETRIES"
                        )
                        lastClickAt = 0L
                        requestScan()
                    }
                }
                action == AccessibilityJoinAction.REQUEST -> {
                    // Do not infer a submitted request only because the button disappeared.
                    // WhatsApp can redraw the request sheet transiently; require explicit
                    // request-sent/pending evidence (or Cancel request after the fresh button is gone).
                    app.preferences.transitionAutomation(
                        AutomationStage.VERIFYING_RESULT,
                        "Request control changed; waiting for explicit pending/request-sent evidence",
                        resetRetries = false
                    )
                }
                else -> {
                    // Disappearance alone is not enough evidence. The regular scanner performs
                    // a multi-scan stability check before recording a JOIN result.
                    app.preferences.transitionAutomation(
                        AutomationStage.VERIFYING_RESULT,
                        "Join control changed; waiting for a stable post-action screen",
                        resetRetries = false
                    )
                }
            }
        }
    }

    /**
     * Independent liveness guard for Join/Request. Accessibility event streams can occasionally
     * go quiet after WhatsApp opens a conversation or redraws the pending-request sheet. This
     * watchdog never clicks destructive controls and never skips loading/restriction screens.
     * Its only fallback is to record a bounded timeout and continue with the next queued link.
     */
    private fun scheduleContinuousHandoffWatchdog(linkId: Long, action: AccessibilityJoinAction) {
        serviceScope.launch {
            var lastWatchFingerprint = 0L
            var stableWatchScans = 0
            var noInspectionStartedAtElapsed = 0L
            while (isActive) {
                delay(runtimeSpeed().watchdogIntervalMs)
                if (!app.preferences.accessibilityBatchRunning || app.preferences.accessibilityPaused) return@launch
                if (app.preferences.accessibilityPendingLinkId != linkId) return@launch

                val current = withContext(Dispatchers.IO) {
                    val sessionId = app.preferences.accessibilitySessionId ?: return@withContext null
                    app.repository.loadAutomationCurrent(sessionId)?.takeIf { it.id == linkId }
                } ?: return@launch

                val inspection = withContext(Dispatchers.Main.immediate) {
                    rootInActiveWindow
                        ?.takeIf { isAutomationWhatsAppPackage(it.packageName?.toString().orEmpty()) }
                        ?.let(::inspectScreen)
                }

                if (inspection == null) {
                    val now = SystemClock.elapsedRealtime()
                    if (noInspectionStartedAtElapsed == 0L) noInspectionStartedAtElapsed = now
                    val missingAge = (now - noInspectionStartedAtElapsed).coerceAtLeast(0L)

                    // A fresh WhatsApp Conversation window event is independent proof that Join
                    // succeeded even when rootInActiveWindow is temporarily unavailable.
                    val activityProofFresh =
                        lastClickAt > 0L &&
                            lastAutomationWindowStateAtElapsed >= lastClickAt &&
                            now - lastAutomationWindowStateAtElapsed <= 3_000L
                    if (action == AccessibilityJoinAction.JOIN &&
                        activityProofFresh &&
                        isKnownConversationActivity(lastAutomationWindowClassName)
                    ) {
                        val backSent = withContext(Dispatchers.Main.immediate) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        if (backSent) delay(exitSettleDelayMs())
                        completeAndAdvance(
                            current,
                            LinkStatus.JOINED,
                            LinkResultCode.JOIN_ACTION_COMPLETED,
                            "Accessibility root disappeared but a fresh WhatsApp Conversation activity proved Join success",
                            fastAdvance = true,
                            surfaceAlreadyExited = backSent
                        )
                        return@launch
                    }

                    // Never park forever when Samsung temporarily withholds the accessibility tree.
                    if (missingAge >= 1_800L) {
                        val backSent = withContext(Dispatchers.Main.immediate) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        if (backSent) delay(exitSettleDelayMs())
                        completeAndAdvance(
                            current,
                            LinkStatus.FAILED,
                            LinkResultCode.ACTION_TIMEOUT,
                            "Accessibility UI acquisition stayed unavailable after the action; bounded Back recovery advanced to the next link",
                            fastAdvance = true,
                            surfaceAlreadyExited = backSent
                        )
                        return@launch
                    }

                    requestScan()
                    continue
                }
                noInspectionStartedAtElapsed = 0L

                if (inspection.fingerprint == lastWatchFingerprint) {
                    stableWatchScans = (stableWatchScans + 1).coerceAtMost(20)
                } else {
                    lastWatchFingerprint = inspection.fingerprint
                    stableWatchScans = 1
                }

                if (inspection.restricted) {
                    if (app.preferences.restrictionHandlingMode == RestrictionHandlingMode.SKIP_AND_CONTINUE) {
                        completeAndAdvance(
                            current,
                            LinkStatus.FAILED,
                            LinkResultCode.RESTRICTED,
                            "Restriction recorded by the continuity watchdog; continuing without bypass",
                            fastAdvance = true,
                            terminalEscapeAdvance = true
                        )
                    } else {
                        withContext(Dispatchers.IO) {
                            app.repository.markStatus(
                                current.id,
                                LinkStatus.FAILED,
                                LinkResultCode.RESTRICTED,
                                "WhatsApp displayed a restriction while the continuity watchdog was active"
                            )
                        }
                        stopBatch(AutomationStopReason.RESTRICTED_SCREEN, "Restriction screen detected")
                    }
                    return@launch
                }

                if (inspection.loading) {
                    requestScan()
                    continue
                }

                // Request sent / Cancel request is a strong terminal state. Do not wait for another
                // event burst before handing off to the next invitation.
                if (inspection.requestSubmitted) {
                    val requestSurfaceExited = fastExitPendingRequestSurface(inspection)
                    completeAndAdvance(
                        current,
                        LinkStatus.REQUESTED,
                        LinkResultCode.REQUEST_SENT,
                        "Join request is pending; X/Back handoff opened the next invitation",
                        fastAdvance = true,
                        surfaceAlreadyExited = requestSurfaceExited
                    )
                    return@launch
                }

                // A Join action followed by a stable non-invite WhatsApp surface means the invite
                // sheet has been left. In Turbo mode the next deep link can be opened directly from
                // the conversation, so Back is not a dependency for sequence continuity.
                val pendingAge = (System.currentTimeMillis() - app.preferences.accessibilityPendingAt)
                    .coerceAtLeast(0L)
                val postJoinEvidenceAgeReached = pendingAge >=
                    InvitationStabilityPolicy.postJoinMinEvidenceAgeMs(app.preferences.fastHandsFreeMode)
                val stablePostJoinSurface = inspection.conversationSurface ||
                    stableWatchScans >= ContinuousHandoffPolicy.MIN_STABLE_SCANS_FOR_FORCE_ADVANCE

                if (action == AccessibilityJoinAction.JOIN &&
                    readPendingTarget(current) == AccessibilityInviteTarget.COMMUNITY &&
                    app.preferences.communityTraversalEnabled &&
                    !inspection.inviteContext && inspection.action == null &&
                    postJoinEvidenceAgeReached && stablePostJoinSurface
                ) {
                    beginCommunityTraversalAfterJoin(
                        current,
                        inspection,
                        "Community join verified by the continuity watchdog"
                    )
                    return@launch
                }

                if (action == AccessibilityJoinAction.JOIN &&
                    !inspection.inviteContext && inspection.action == null &&
                    postJoinEvidenceAgeReached && stablePostJoinSurface
                ) {
                    if (inspection.conversationSurface) {
                        returnFromJoinedConversationAndAdvance(current)
                    } else {
                        completeAndAdvance(
                            current,
                            LinkStatus.JOINED,
                            LinkResultCode.JOIN_ACTION_COMPLETED,
                            "Join left the invitation surface; continuity handoff opened the next invitation",
                            fastAdvance = true,
                            surfaceAlreadyExited = true
                        )
                    }
                    return@launch
                }

                if (ContinuousHandoffPolicy.shouldForceAdvance(
                        pendingAgeMs = pendingAge,
                        loading = false,
                        restricted = false,
                        turbo = app.preferences.fastHandsFreeMode,
                        stableScreenScans = stableWatchScans
                    )
                ) {
                    completeAndAdvance(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.ACTION_TIMEOUT,
                        if (action == AccessibilityJoinAction.REQUEST) {
                            "Request result did not become verifiable in the continuity window; skipped safely to the next invitation"
                        } else {
                            "Join result did not become verifiable in the continuity window; skipped safely to the next invitation"
                        },
                        fastAdvance = true
                    )
                    return@launch
                }

                requestScan()
            }
        }
    }

    private fun readPendingTarget(current: GroupLink): AccessibilityInviteTarget {
        if (app.preferences.accessibilityPendingLinkId != current.id) return AccessibilityInviteTarget.UNKNOWN
        return app.preferences.accessibilityPendingTarget
    }

    private fun isCommunitySubgroupActive(current: GroupLink): Boolean {
        val prefs = app.preferences
        return prefs.communityTraversalEnabled &&
            prefs.communityTraversalActive &&
            prefs.communityTraversalParentLinkId == current.id &&
            !prefs.communityCurrentGroupKey.isNullOrBlank() &&
            prefs.communityTraversalStage in setOf(
                CommunityTraversalStage.OPENING_GROUP,
                CommunityTraversalStage.PROCESSING_GROUP,
                CommunityTraversalStage.RETURNING_TO_COMMUNITY
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

    private suspend fun maybeStartAlreadyMemberCommunity(
        current: GroupLink,
        screen: ScreenInspection
    ): Boolean {
        val prefs = app.preferences
        if (!prefs.communityTraversalEnabled || prefs.communityTraversalActive) return false
        if (!screen.alreadyMember || screen.screenTarget != AccessibilityInviteTarget.COMMUNITY) return false
        val openNode = screen.communityOpenNode ?: return false

        prefs.beginCommunityTraversal(current.id)
        prefs.clearAccessibilityPending()
        setCommunityStage(CommunityTraversalStage.ENTERING_COMMUNITY)
        runtimeDiagnostic(current, "COMMUNITY_START", "Already a Community member; opening Community home for subgroup traversal")
        val clicked = withContext(Dispatchers.Main.immediate) { clickNodeParentOrGesture(openNode) }
        if (!clicked) {
            prefs.clearCommunityTraversal()
            return false
        }
        requestScan()
        return true
    }

    private suspend fun beginCommunityTraversalAfterJoin(
        current: GroupLink,
        screen: ScreenInspection,
        source: String
    ) {
        val prefs = app.preferences
        if (!prefs.communityTraversalEnabled) return
        if (!prefs.communityTraversalActive) prefs.beginCommunityTraversal(current.id)
        prefs.clearAccessibilityPending()
        communityEmptyStableScans = 0
        communityHomeStableScans = 0
        communityReturnBackSteps = 0
        runtimeDiagnostic(
            current,
            "COMMUNITY_START",
            "$source; communityHome=${screen.communityHomeSurface}; conversation=${screen.conversationSurface}"
        )

        when {
            screen.communityHomeSurface -> {
                setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                handleCommunityHome(current, screen)
            }
            screen.conversationSurface -> {
                setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                val backSent = withContext(Dispatchers.Main.immediate) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                if (backSent) {
                    communityReturnBackSteps = 1
                    delay(exitSettleDelayMs())
                }
                requestScan()
            }
            else -> {
                setCommunityStage(CommunityTraversalStage.ENTERING_COMMUNITY)
                requestScan()
            }
        }
    }

    /**
     * Returns true when the Community state machine consumed the current screen. Group invite
     * sheets themselves are intentionally returned to the normal invitation engine so Join /
     * Request / terminal safety rules remain exactly the same as standalone links.
     */
    private suspend fun maybeHandleCommunityTraversal(
        current: GroupLink,
        screen: ScreenInspection
    ): Boolean {
        val prefs = app.preferences
        if (!prefs.communityTraversalEnabled || !prefs.communityTraversalActive) return false
        if (prefs.communityTraversalParentLinkId != current.id) {
            prefs.clearCommunityTraversal()
            return false
        }
        if (screen.loading) return false

        val pending = readPendingAction(current)
        val stage = prefs.communityTraversalStage
        when (stage) {
            CommunityTraversalStage.INACTIVE -> return false

            CommunityTraversalStage.ENTERING_COMMUNITY -> {
                if (screen.communityHomeSurface) {
                    setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                    handleCommunityHome(current, screen)
                    return true
                }
                if (screen.inviteContext) return false
                if (screen.conversationSurface) {
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    performCommunityBack(current, "Community entry opened a chat; returning to Community home")
                    return true
                }
                if (communityStageAgeMs() >= CommunityTraversalPolicy.RETURN_TIMEOUT_MS) {
                    if (communityReturnBackSteps < CommunityTraversalPolicy.MAX_RETURN_BACK_STEPS) {
                        performCommunityBack(current, "Searching for Community home after successful join")
                    } else {
                        finishCommunityTraversal(
                            current,
                            "Community joined, but its group list could not be reached safely"
                        )
                    }
                    return true
                }
                requestScan()
                return true
            }

            CommunityTraversalStage.DISCOVERING_GROUPS -> {
                if (screen.communityHomeSurface) {
                    handleCommunityHome(current, screen)
                    return true
                }
                if (screen.inviteContext || screen.action != null || hasTerminalEvidence(screen)) {
                    setCommunityStage(CommunityTraversalStage.PROCESSING_GROUP)
                    return false
                }
                if (screen.conversationSurface && !prefs.communityCurrentGroupKey.isNullOrBlank()) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    performCommunityBack(current, "Opened subgroup is already accessible; returning to Community")
                    return true
                }
                requestScan()
                return true
            }

            CommunityTraversalStage.OPENING_GROUP -> {
                if (screen.inviteContext || screen.action != null || hasTerminalEvidence(screen)) {
                    setCommunityStage(CommunityTraversalStage.PROCESSING_GROUP)
                    return false
                }
                if (screen.conversationSurface) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    performCommunityBack(current, "Subgroup opened directly because membership already exists")
                    return true
                }
                if (screen.communityHomeSurface) {
                    if (communityStageAgeMs() >= CommunityTraversalPolicy.GROUP_OPEN_TIMEOUT_MS) {
                        runtimeDiagnostic(
                            current,
                            "COMMUNITY_GROUP_SKIP",
                            "Subgroup row did not open within the bounded timeout; skipping the row safely"
                        )
                        prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                        setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                        handleCommunityHome(current, screen)
                    } else {
                        requestScan()
                    }
                    return true
                }
                if (communityStageAgeMs() >= CommunityTraversalPolicy.GROUP_OPEN_TIMEOUT_MS) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    performCommunityBack(current, "Subgroup did not expose a safe invitation surface; returning")
                    return true
                }
                requestScan()
                return true
            }

            CommunityTraversalStage.PROCESSING_GROUP -> {
                if (screen.inviteContext || screen.action != null || hasTerminalEvidence(screen) || pending != null) {
                    return false
                }
                if (screen.communityHomeSurface) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                    handleCommunityHome(current, screen)
                    return true
                }
                if (screen.conversationSurface) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    performCommunityBack(current, "Subgroup opened as an existing member; returning to Community")
                    return true
                }
                if (communityStageAgeMs() >= CommunityTraversalPolicy.GROUP_OPEN_TIMEOUT_MS) {
                    prefs.markCommunityGroupProcessed(prefs.communityCurrentGroupKey)
                    setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
                    performCommunityBack(current, "Subgroup processing became inert; bounded recovery is returning")
                    return true
                }
                requestScan()
                return true
            }

            CommunityTraversalStage.RETURNING_TO_COMMUNITY -> {
                if (screen.communityHomeSurface) {
                    setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
                    handleCommunityHome(current, screen)
                    return true
                }
                if (screen.inviteContext) {
                    exitInvitationSurface()
                    requestScan()
                    return true
                }
                if (communityStageAgeMs() >= 500L &&
                    communityReturnBackSteps < CommunityTraversalPolicy.MAX_RETURN_BACK_STEPS
                ) {
                    performCommunityBack(current, "Returning from subgroup to Community home")
                    return true
                }
                if (communityStageAgeMs() >= CommunityTraversalPolicy.RETURN_TIMEOUT_MS * 2) {
                    finishCommunityTraversal(
                        current,
                        "Community subgroup pass ended after bounded return recovery; processed ${prefs.communityProcessedGroupCount} subgroup rows"
                    )
                    return true
                }
                requestScan()
                return true
            }

            CommunityTraversalStage.COMPLETE -> {
                finishCommunityTraversal(current, "Community traversal completed")
                return true
            }
        }
    }

    private suspend fun handleCommunityHome(current: GroupLink, screen: ScreenInspection) {
        val prefs = app.preferences
        communityHomeStableScans = (communityHomeStableScans + 1).coerceAtMost(20)
        if (communityHomeStableScans < CommunityTraversalPolicy.COMMUNITY_HOME_STABLE_SCANS) {
            requestScan()
            return
        }
        if (!CommunityTraversalPolicy.canProcessMore(prefs.communityProcessedGroupCount)) {
            finishCommunityTraversal(
                current,
                "Community subgroup safety cap reached after ${prefs.communityProcessedGroupCount} processed rows"
            )
            return
        }

        val processed = prefs.communityProcessedGroupKeys
        val nextCandidate = screen.communityGroupCandidates.firstOrNull { it.key !in processed }
        if (nextCandidate != null) {
            communityEmptyStableScans = 0
            prefs.communityCurrentGroupKey = nextCandidate.key
            setCommunityStage(CommunityTraversalStage.OPENING_GROUP)
            runtimeDiagnostic(
                current,
                "COMMUNITY_GROUP_OPEN",
                "Opening semantic subgroup row ${nextCandidate.key.take(96)}"
            )
            val clicked = withContext(Dispatchers.Main.immediate) {
                clickNodeParentOrGesture(nextCandidate.node)
            }
            if (!clicked) {
                runtimeDiagnostic(current, "COMMUNITY_GROUP_SKIP", "Subgroup row was not safely clickable")
                prefs.markCommunityGroupProcessed(nextCandidate.key)
                setCommunityStage(CommunityTraversalStage.DISCOVERING_GROUPS)
            }
            requestScan()
            return
        }

        val canScroll = CommunityTraversalPolicy.canScroll(prefs.communityScrollAttempts)
        val scrollNode = screen.communityScrollNode
        if (canScroll && scrollNode != null) {
            val semanticScrolled = withContext(Dispatchers.Main.immediate) {
                scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            val gestureScrolled = !semanticScrolled && withContext(Dispatchers.Main.immediate) {
                dispatchReliableScrollGesture(scrollNode)
            }
            if (semanticScrolled || gestureScrolled) {
                prefs.communityScrollAttempts += 1
                communityEmptyStableScans = 0
                runtimeDiagnostic(
                    current,
                    "COMMUNITY_SCROLL",
                    "Searching for more subgroup rows; scroll=${prefs.communityScrollAttempts}; mode=${if (semanticScrolled) "SEMANTIC" else "GESTURE"}"
                )
            } else {
                // End-of-list only after both semantic and touch-gesture paths fail.
                prefs.communityScrollAttempts = CommunityTraversalPolicy.MAX_SCROLL_ATTEMPTS
                communityEmptyStableScans += 1
            }
            requestScan()
            return
        }

        communityEmptyStableScans += 1
        if (CommunityTraversalPolicy.shouldFinishEmptyView(
                stableEmptyScans = communityEmptyStableScans,
                canScroll = scrollNode != null && CommunityTraversalPolicy.canScroll(prefs.communityScrollAttempts),
                scrollAttempts = prefs.communityScrollAttempts
            )
        ) {
            finishCommunityTraversal(
                current,
                "Community completed; processed ${prefs.communityProcessedGroupCount} semantically identified subgroup rows"
            )
        } else {
            requestScan()
        }
    }

    private suspend fun performCommunityBack(current: GroupLink, diagnostic: String) {
        if (communityReturnBackSteps >= CommunityTraversalPolicy.MAX_RETURN_BACK_STEPS) return
        val sent = withContext(Dispatchers.Main.immediate) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (sent) {
            communityReturnBackSteps += 1
            runtimeDiagnostic(current, "COMMUNITY_BACK", "$diagnostic; step=$communityReturnBackSteps")
            delay(exitSettleDelayMs())
        }
        requestScan()
    }

    private suspend fun completeCommunitySubgroupAndContinue(
        current: GroupLink,
        status: LinkStatus,
        resultCode: LinkResultCode,
        detail: String,
        surfaceAlreadyExited: Boolean
    ) {
        val prefs = app.preferences
        val key = prefs.communityCurrentGroupKey
        runtimeDiagnostic(
            current,
            "COMMUNITY_GROUP_RESULT",
            "status=${status.name}; result=${resultCode.name}; key=${key.orEmpty().take(96)}; $detail"
        )
        prefs.clearAccessibilityPending()
        prefs.markCommunityGroupProcessed(key)

        if (!surfaceAlreadyExited) exitInvitationSurface()
        if (!CommunityTraversalPolicy.canProcessMore(prefs.communityProcessedGroupCount)) {
            finishCommunityTraversal(
                current,
                "Community subgroup safety cap reached after ${prefs.communityProcessedGroupCount} processed rows"
            )
            return
        }

        setCommunityStage(CommunityTraversalStage.RETURNING_TO_COMMUNITY)
        communityReturnBackSteps = 0
        requestScan()
    }

    private suspend fun finishCommunityTraversal(current: GroupLink, detail: String) {
        val processed = app.preferences.communityProcessedGroupCount
        runtimeDiagnostic(current, "COMMUNITY_COMPLETE", "$detail; processed=$processed")
        app.preferences.clearCommunityTraversal()
        communityHomeStableScans = 0
        communityEmptyStableScans = 0
        communityReturnBackSteps = 0
        communityStageStartedAtElapsed = 0L
        completeAndAdvance(
            current,
            LinkStatus.JOINED,
            LinkResultCode.JOIN_ACTION_COMPLETED,
            "$detail. Community membership was preserved; WhatsApp restrictions were never bypassed.",
            fastAdvance = true,
            surfaceAlreadyExited = true,
            communityParentFinalization = true
        )
    }

    private suspend fun handleUnknown(current: GroupLink, screen: ScreenInspection) {
        val pending = readPendingAction(current)
        val pendingAge = (System.currentTimeMillis() - app.preferences.accessibilityPendingAt)
            .coerceAtLeast(0L)
        val effectiveTimeoutMs = InvitationStabilityPolicy.effectiveActionTimeoutMs(
            app.preferences.accessibilityActionTimeoutSeconds,
            turbo = app.preferences.fastHandsFreeMode
        )
        val pendingTarget = readPendingTarget(current)

        // If an invite for a group that is already joined opens the conversation directly,
        // there is no Join/Request button and therefore no pending action. Treat a repeated
        // conversation surface as authoritative "already member" evidence, exit immediately,
        // and continue instead of leaving the run parked in the chat.
        val stageAge = (System.currentTimeMillis() - app.preferences.automationStageStartedAt)
            .coerceAtLeast(0L)
        val targetWindowEventFresh = lastAutomationWindowStateAtElapsed > 0L &&
            (SystemClock.elapsedRealtime() - lastAutomationWindowStateAtElapsed).coerceAtLeast(0L) <=
            DIRECT_CONVERSATION_WINDOW_EVENT_MAX_AGE_MS
        if (pending == null && targetWindowEventFresh && screen.conversationSurface &&
            !screen.inviteContext && !screen.loading
        ) {
            conversationSurfaceStableScans += 1
            val minimumAge = if (app.preferences.fastHandsFreeMode) {
                DIRECT_CONVERSATION_FAST_MIN_AGE_MS
            } else {
                DIRECT_CONVERSATION_NORMAL_MIN_AGE_MS
            }
            if (conversationSurfaceStableScans >= DIRECT_CONVERSATION_STABLE_SCANS && stageAge >= minimumAge) {
                val backSent = withContext(Dispatchers.Main.immediate) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                if (backSent) {
                    val settle = ConversationFastExitPolicy.settleMs(app.preferences.fastHandsFreeMode)
                    if (settle > 0L) delay(settle)
                }
                completeAndAdvance(
                    current,
                    LinkStatus.JOINED,
                    LinkResultCode.ALREADY_MEMBER,
                    "Invitation opened an existing group conversation; exited immediately and continued",
                    fastAdvance = true,
                    surfaceAlreadyExited = true
                )
                return
            }
        } else if (pending == null) {
            conversationSurfaceStableScans = 0
        }

        // Compatibility fallback for WhatsApp builds that draw the positive invitation control
        // without exposing a usable AccessibilityNodeInfo label/click action. This path never uses
        // a fixed coordinate: it requires the selected WhatsApp package to be foreground and a
        // fresh screenshot to contain one wide WhatsApp-green lower-screen control.
        if (pending == null && maybeHandleAccessibilityVisualFallback(current, screen)) return

        if (pending == AccessibilityJoinAction.JOIN &&
            pendingTarget == AccessibilityInviteTarget.COMMUNITY &&
            app.preferences.communityTraversalEnabled &&
            !screen.inviteContext && !screen.loading &&
            pendingAge >= InvitationStabilityPolicy.postJoinMinEvidenceAgeMs(app.preferences.fastHandsFreeMode) &&
            (screen.communityHomeSurface || screen.conversationSurface || stableScreenFingerprintScans >= 2)
        ) {
            beginCommunityTraversalAfterJoin(
                current,
                screen,
                "Community join verified by stable post-action evidence"
            )
            return
        }

        // UltraMotion recovery: if an invitation sheet is valid but its action is below the
        // visible fold, use Android's semantic scroll action instead of a long swipe gesture.
        // The attempt count is bounded per link so a changed WhatsApp layout cannot loop-scroll.
        val inviteScrollNode = screen.scrollNode
        if (pending == null && screen.inviteContext && !screen.loading && screen.action == null &&
            inviteScrollNode != null && inviteScrollAttempts < MAX_INVITE_SCROLL_ATTEMPTS
        ) {
            val semanticScrolled = withContext(Dispatchers.Main.immediate) {
                inviteScrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            val gestureScrolled = !semanticScrolled && withContext(Dispatchers.Main.immediate) {
                dispatchReliableScrollGesture(inviteScrollNode)
            }
            if (semanticScrolled || gestureScrolled) {
                inviteScrollAttempts += 1
                runtimeDiagnostic(
                    current,
                    "FAST_SCROLL",
                    "Scrolled invitation surface; attempt=$inviteScrollAttempts; mode=${if (semanticScrolled) "SEMANTIC" else "GESTURE"}"
                )
                app.preferences.transitionAutomation(
                    AutomationStage.LOOKING_FOR_JOIN,
                    "Invitation controls were below the fold; reliable scroll completed",
                    resetRetries = false
                )
                lastScanAt = 0L
                requestScan()
                return
            }
        }

        if (pending == AccessibilityJoinAction.JOIN) {
            // When Join opens the group conversation, verify the chat surface across repeated
            // scans, return once, then continue immediately to the next unique queued link.
            if (screen.conversationSurface &&
                pendingAge >= InvitationStabilityPolicy.postJoinMinEvidenceAgeMs(app.preferences.fastHandsFreeMode)
            ) {
                conversationSurfaceStableScans += 1
                postJoinStableNonInviteScans = 0
                if (conversationSurfaceStableScans >= InvitationStabilityPolicy.CONVERSATION_STABLE_SCANS) {
                    returnFromJoinedConversationAndAdvance(current)
                    return
                }
            } else {
                conversationSurfaceStableScans = 0

                // Some WhatsApp variants return directly to another stable non-invite surface.
                if (!screen.inviteContext && !screen.loading &&
                    pendingAge >= InvitationStabilityPolicy.postJoinMinEvidenceAgeMs(app.preferences.fastHandsFreeMode)
                ) {
                    postJoinStableNonInviteScans += 1
                    val requiredPostJoinScans = InvitationStabilityPolicy.postJoinStableNonInviteScans(
                        app.preferences.fastHandsFreeMode
                    )
                    if (postJoinStableNonInviteScans >= requiredPostJoinScans) {
                        completeAndAdvance(
                            current,
                            LinkStatus.JOINED,
                            LinkResultCode.JOIN_ACTION_COMPLETED,
                            "Join action was followed by a stable transition away from the invitation surface",
                            fastAdvance = true
                        )
                        return
                    }
                } else {
                    postJoinStableNonInviteScans = 0
                }
            }

            if (pendingAge >= effectiveTimeoutMs) {
                completeAndAdvance(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.ACTION_TIMEOUT,
                    "Join was pressed but WhatsApp did not expose a stable, verifiable result before timeout",
                    fastAdvance = true
                )
            }
            return
        }

        if (pending == AccessibilityJoinAction.REQUEST) {
            postJoinStableNonInviteScans = 0
            if (pendingAge >= effectiveTimeoutMs) {
                completeAndAdvance(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.ACTION_TIMEOUT,
                    "Request was pressed but WhatsApp did not expose pending/request-sent evidence before timeout"
                )
            }
            return
        }

        postJoinStableNonInviteScans = 0
        val age = stageAge
        unknownStableScans += 1
        val watchdog = RuntimeWatchdogPolicy.assess(
            stableScreenScans = stableScreenFingerprintScans,
            stageAgeMs = age,
            loading = screen.loading,
            conflict = screen.evidenceConflict,
            turbo = app.preferences.fastHandsFreeMode
        )
        RuntimeHealthMonitor.updateWatchdog(SystemClock.elapsedRealtime(), watchdog.name)
        if (watchdog == RuntimeWatchdogState.STALLED) {
            runtimeDiagnostic(
                current,
                "WATCHDOG",
                "Stable screen appears stalled; age=${age}ms; scans=$stableScreenFingerprintScans; invite=${screen.inviteContext}; home=${screen.homeSurface}"
            )
            if (RuntimeRecoveryPolicy.shouldAdvanceStalledUnknown(
                    watchdogState = watchdog,
                    loading = screen.loading,
                    restricted = screen.restricted,
                    pendingAction = pending != null,
                    hasRecognizedAction = screen.action != null
                ) && !screen.homeSurface
            ) {
                completeAndAdvance(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.UNKNOWN_SCREEN,
                    "Self-recovery advanced an inert WhatsApp screen after repeated identical evidence",
                    fastAdvance = true
                )
                return
            }
        }

        val sinceLoadingEnded = if (lastLoadingEndedAtElapsed > 0L) {
            (SystemClock.elapsedRealtime() - lastLoadingEndedAtElapsed).coerceAtLeast(0L)
        } else Long.MAX_VALUE
        if (AdaptiveInteractionPolicy.shouldWaitAfterLoading(
                sinceLoadingEnded,
                turbo = app.preferences.fastHandsFreeMode
            )) {
            homeSurfaceStableScans = 0
            return
        }

        if (screen.homeSurface) homeSurfaceStableScans += 1 else homeSurfaceStableScans = 0
        if (AdaptiveInteractionPolicy.shouldAdvanceFromHome(
                homeSurfaceStableScans,
                age,
                turbo = app.preferences.fastHandsFreeMode
            )) {
            completeAndAdvance(
                current,
                LinkStatus.FAILED,
                LinkResultCode.UNKNOWN_SCREEN,
                "WhatsApp returned to its chat/search surface without exposing the invitation; the same link will not be reopened",
                fastAdvance = true
            )
            return
        }

        // Unknown but still inside an invitation sheet: wait. Reopening the same link here was the
        // source of the visible open/close loop in the supplied video.
        if (screen.inviteContext) {
            if (age >= effectiveTimeoutMs &&
                AdaptiveInteractionPolicy.unknownIsStableEnough(unknownStableScans)
            ) {
                completeAndAdvance(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.UNKNOWN_SCREEN,
                    "Invitation surface remained visible without a recognized safe action before timeout"
                )
            }
            return
        }

        if (age >= minOf(effectiveTimeoutMs, runtimeSpeed().unknownRecoveryAfterMs) &&
            AdaptiveInteractionPolicy.unknownIsStableEnough(unknownStableScans)
        ) {
            if (pending == null && attemptUnknownDeepLinkRecovery(current)) return

            completeAndAdvance(
                current,
                LinkStatus.FAILED,
                LinkResultCode.UNKNOWN_SCREEN,
                "Unknown screen remained after bounded Scan/Exit/Back/one-reopen recovery",
                fastAdvance = true
            )
        }
    }

    private suspend fun attemptUnknownDeepLinkRecovery(current: GroupLink): Boolean {
        if (!app.preferences.recordRecoveryReopen(current.id)) return false

        app.preferences.markRuntimePhase(
            LinkRuntimePhase.EXITING,
            "ACCESSIBILITY:UNKNOWN_RECOVERY"
        )
        exitInvitationSurface()

        val destination = withContext(Dispatchers.Main.immediate) {
            WhatsAppLauncher.launch(
                this@QuickJoinAccessibilityService,
                current.url,
                app.preferences.preferredTarget,
                app.preferences.runtimeLockedWhatsAppPackage
                    ?: app.preferences.selectedWhatsAppPackage,
                strictProfileTarget = app.preferences.strictProfileTargeting,
                expectedProfileKey = app.preferences.runtimeLockedProfileKey
            )
        }

        return when (destination) {
            LaunchDestination.PERSONAL,
            LaunchDestination.BUSINESS,
            LaunchDestination.CLONED,
            LaunchDestination.SELECTED,
            LaunchDestination.DUAL_CHOOSER -> {
                app.preferences.markAutomationLaunched()
                app.preferences.markRuntimePhase(
                    LinkRuntimePhase.OPENING,
                    "ACCESSIBILITY:RECOVERY_REOPENED"
                )
                lastScanAt = 0L
                requestScan()
                true
            }
            else -> false
        }
    }

    private suspend fun handleUnavailableRoot(current: GroupLink) {
        val now = SystemClock.elapsedRealtime()
        val interactive = (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive != false
        if (!interactive) {
            // A locked/off display cannot expose the WhatsApp tree reliably. Keep the saved link
            // pending instead of burning through the queue; the event/poll loop resumes on wake.
            rootUnavailableStartedAtElapsed = now
            runtimeDiagnostic(current, "ROOT_WAIT_SCREEN", "Display is not interactive; preserving current link until Android exposes WhatsApp again")
            return
        }
        if (rootUnavailableStartedAtElapsed <= 0L) {
            rootUnavailableStartedAtElapsed = now
            runtimeDiagnostic(current, "ROOT_MISSING", "Active Accessibility root is temporarily unavailable; waiting for recovery")
            return
        }
        val age = (now - rootUnavailableStartedAtElapsed).coerceAtLeast(0L)
        val timeout = RuntimeRecoveryPolicy.rootUnavailableTimeoutMs(app.preferences.fastHandsFreeMode)
        if (age >= timeout) {
            val profile = ProfileEnvironment.current(this)
            rootUnavailableStartedAtElapsed = 0L
            if (profile.secondaryProfile) {
                // In Work Profile / Samsung isolated environments a missing root commonly means
                // the enabled service belongs to another Android profile. Never consume the link
                // or guess a click. Stop with the current invitation preserved so the user can
                // enable the profile-local service and resume from exactly the same link.
                runtimeDiagnostic(
                    current,
                    "PROFILE_UI_TREE_BLOCKED",
                    "profile=${profile.profileKey}; root unavailable for ${age}ms; current link preserved"
                )
                stopBatch(
                    AutomationStopReason.SERVICE_DISABLED,
                    "Profile-local Accessibility cannot read the selected WhatsApp UI tree; current link preserved. Enable AL-thmany Accessibility inside ${profile.profileKey} and resume."
                )
            } else {
                runtimeDiagnostic(current, "ROOT_RECOVERY", "Accessibility root unavailable for ${age}ms; advancing safely without clicking")
                completeAndAdvance(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.UNKNOWN_SCREEN,
                    "Accessibility root stayed unavailable; self-recovery moved to the next queued invitation",
                    fastAdvance = true,
                    surfaceAlreadyExited = true
                )
            }
        }
    }

    private suspend fun handleLoading(current: GroupLink) {
        val now = SystemClock.elapsedRealtime()
        if (loadingLinkId != current.id || loadingStartedAtElapsed <= 0L) {
            loadingLinkId = current.id
            loadingStartedAtElapsed = now
            stableActionKey = null
            stableActionScans = 0
            postJoinStableNonInviteScans = 0
            app.preferences.transitionAutomation(
                AutomationStage.WAITING_FOR_WHATSAPP,
                "WhatsApp is loading the invitation; waiting without closing or reopening it",
                resetRetries = false
            )
            return
        }

        val loadingAge = (now - loadingStartedAtElapsed).coerceAtLeast(0L)
        val loadingTimeout = InvitationStabilityPolicy.loadingTimeoutMs(
            app.preferences.accessibilityActionTimeoutSeconds
        )
        if (loadingAge >= loadingTimeout) {
            completeAndAdvance(
                current,
                LinkStatus.FAILED,
                LinkResultCode.ACTION_TIMEOUT,
                "WhatsApp remained on the loading invitation screen for ${loadingAge / 1_000L}s",
                fastAdvance = true
            )
        }
    }

    private fun ensureTrackingFor(linkId: Long) {
        if (trackedLinkId == linkId) return
        trackedLinkId = linkId
        loadingStartedAtElapsed = 0L
        loadingLinkId = -1L
        stableActionKey = null
        stableActionScans = 0
        lastActionAttemptKey = null
        lastActionAttemptAtElapsed = 0L
        actionAttempts = 0
        postJoinStableNonInviteScans = 0
        lastLoadingEndedAtElapsed = 0L
        stableOutcomeKey = null
        stableOutcomeScans = 0
        homeSurfaceStableScans = 0
        unknownStableScans = 0
        conversationSurfaceStableScans = 0
        inviteScrollAttempts = 0
        lastScreenFingerprint = 0L
        stableScreenFingerprintScans = 0
        lastRuntimeDiagnosticKey = null
        lastRuntimeDiagnosticAtElapsed = 0L
        rootUnavailableStartedAtElapsed = 0L
        accessibilityVisualProbeLinkId = linkId
        accessibilityVisualProbeAttempts = 0
        lastAccessibilityVisualProbeAtElapsed = 0L
        accessibilityVisualActionTappedAtElapsed = 0L
        accessibilityVisualTapAttempts = 0
        resetConflictEvidence()
    }

    private fun clearLoadingTracking() {
        if (loadingStartedAtElapsed > 0L) {
            lastLoadingEndedAtElapsed = SystemClock.elapsedRealtime()
        }
        loadingStartedAtElapsed = 0L
        loadingLinkId = -1L
    }

    private suspend fun handleVisibleAction(
        current: GroupLink,
        action: AccessibilityJoinAction,
        node: AccessibilityNodeInfo,
        screen: ScreenInspection
    ) {
        val sinceLoadingEnded = if (lastLoadingEndedAtElapsed > 0L) {
            (SystemClock.elapsedRealtime() - lastLoadingEndedAtElapsed).coerceAtLeast(0L)
        } else Long.MAX_VALUE
        if (AdaptiveInteractionPolicy.shouldWaitAfterLoading(
                sinceLoadingEnded,
                turbo = app.preferences.fastHandsFreeMode
            )) {
            stableActionKey = null
            stableActionScans = 0
            app.preferences.transitionAutomation(
                AutomationStage.WAITING_FOR_WHATSAPP,
                "Invitation finished loading; allowing the controls to settle before choosing an action",
                resetRetries = false
            )
            return
        }

        val pending = readPendingAction(current)
        if (!ScreenEvidencePolicy.actionAllowedWhilePending(pending, action)) {
            stableActionKey = null
            stableActionScans = 0
            app.preferences.transitionAutomation(
                AutomationStage.VERIFYING_RESULT,
                "Ignoring an unrelated stale invitation control while waiting for the previous action result",
                resetRetries = false
            )
            return
        }

        if (shouldClickStableAction(current, action, node, screen)) {
            if (RuntimeFeatureFlags.CONFIDENCE_ENGINE) {
                val assessment = RuntimeIntelligencePolicy.assessAction(
                    action = action,
                    inviteContext = screen.inviteContext,
                    loading = screen.loading,
                    positiveActionCount = screen.positiveActionCount,
                    candidateScoreMargin = screen.actionScoreMargin,
                    terminalEvidenceCount = screen.terminalEvidenceCount,
                    conflict = screen.evidenceConflict,
                    stableActionScans = stableActionScans,
                    stableScreenScans = stableScreenFingerprintScans,
                    pendingAction = pending
                )
                runtimeDiagnostic(
                    current,
                    "ACTION_CONFIDENCE",
                    "action=${action.name}; score=${assessment.score}; band=${assessment.band}; ${assessment.reason}"
                )
                RuntimeHealthMonitor.updateConfidence(SystemClock.elapsedRealtime(), assessment.score, assessment.band.name)
                if (!assessment.safeToAct) {
                    app.preferences.transitionAutomation(
                        AutomationStage.WAITING_FOR_WHATSAPP,
                        "Action confidence ${assessment.score}% is below the safe threshold; waiting for a cleaner WhatsApp screen",
                        resetRetries = false
                    )
                    return
                }
            }

            if (app.preferences.runtimeShadowMode) {
                // An explicit Start command is executable by definition. Self-heal stale settings
                // restored by Samsung/backup instead of silently observing a valid Join forever.
                app.preferences.runtimeShadowMode = false
                runtimeDiagnostic(current, "SHADOW_AUTO_DISABLED", "Active run restored executable ${action.name} input")
            }

            clickAction(current, action, node, screen.actionTarget)
            return
        }

        val key = stableActionKeyFor(current.id, action, node)
        if (lastActionAttemptKey != key || actionAttempts < InvitationStabilityPolicy.MAX_ACTION_ATTEMPTS) return
        val sinceLastAttempt = (SystemClock.elapsedRealtime() - lastActionAttemptAtElapsed).coerceAtLeast(0L)
        val timeout = InvitationStabilityPolicy.effectiveActionTimeoutMs(
            app.preferences.accessibilityActionTimeoutSeconds,
            turbo = app.preferences.fastHandsFreeMode
        )
        if (sinceLastAttempt >= timeout) {
            completeAndAdvance(
                current,
                LinkStatus.FAILED,
                LinkResultCode.ACTION_TIMEOUT,
                "The same invitation action remained visible after ${InvitationStabilityPolicy.MAX_ACTION_ATTEMPTS} bounded attempts"
            )
        }
    }

    private fun shouldClickStableAction(
        current: GroupLink,
        action: AccessibilityJoinAction,
        node: AccessibilityNodeInfo,
        screen: ScreenInspection
    ): Boolean {
        val key = stableActionKeyFor(current.id, action, node)
        if (stableActionKey == key) stableActionScans += 1
        else {
            stableActionKey = key
            stableActionScans = 1
        }
        val strongTurboAction = app.preferences.fastHandsFreeMode &&
            screen.inviteContext && !screen.loading &&
            screen.evidenceConflict == ScreenEvidenceConflict.NONE &&
            screen.terminalEvidenceCount == 0 &&
            screen.positiveActionCount == 1 &&
            screen.actionScoreMargin >= InvitationStabilityPolicy.TURBO_STRONG_ACTION_SCORE_MARGIN
        val requiredScans = if (strongTurboAction) {
            InvitationStabilityPolicy.TURBO_STRONG_ACTION_STABLE_SCANS
        } else {
            InvitationStabilityPolicy.ACTION_STABLE_SCANS
        }
        if (stableActionScans < requiredScans) return false

        val now = SystemClock.elapsedRealtime()
        if (lastActionAttemptKey == key) {
            val sinceLastAttempt = (now - lastActionAttemptAtElapsed).coerceAtLeast(0L)
            val retryAfter = if (app.preferences.fastHandsFreeMode) {
                InvitationStabilityPolicy.TURBO_ACTION_RETRY_AFTER_MS
            } else {
                InvitationStabilityPolicy.ACTION_RETRY_AFTER_MS
            }
            if (sinceLastAttempt < retryAfter) return false
            if (actionAttempts >= InvitationStabilityPolicy.MAX_ACTION_ATTEMPTS) return false
        }
        return true
    }

    private fun recordActionAttempt(
        linkId: Long,
        action: AccessibilityJoinAction,
        node: AccessibilityNodeInfo
    ) {
        val key = stableActionKeyFor(linkId, action, node)
        if (lastActionAttemptKey == key) actionAttempts += 1
        else {
            lastActionAttemptKey = key
            actionAttempts = 1
        }
        lastActionAttemptAtElapsed = SystemClock.elapsedRealtime()
        stableActionKey = null
        stableActionScans = 0
    }

    private fun stableActionKeyFor(
        linkId: Long,
        action: AccessibilityJoinAction,
        node: AccessibilityNodeInfo
    ): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        val bucket = dpToPx(8).coerceAtLeast(1)
        return buildString {
            append(linkId).append(':').append(action.name).append(':')
            append(bounds.centerX() / bucket).append(':')
            append(bounds.centerY() / bucket).append(':')
            append(bounds.width() / bucket).append(':')
            append(bounds.height() / bucket)
        }
    }

    /** Selects the Samsung Dual Messenger WhatsApp entry from Android/Samsung resolver UI. */
    private suspend fun handleDualMessengerResolver(root: AccessibilityNodeInfo) {
        val nodes = collectVisibleNodes(root)
        if (nodes.isEmpty()) return

        val explicitDual = nodes
            .filter { item ->
                val label = listOf(item.text, item.description, item.hint)
                    .joinToString(" ") { it?.toString().orEmpty() }
                item.node.isVisibleToUser && item.node.isEnabled &&
                    DualMessengerMatcher.isExplicitDualMessenger(label)
            }
            .maxByOrNull { resolverCandidateScore(it.node, explicit = true) }

        val whatsappRows = nodes
            .filter { item ->
                val label = listOf(item.text, item.description, item.hint)
                    .joinToString(" ") { it?.toString().orEmpty() }
                item.node.isVisibleToUser && item.node.isEnabled &&
                    DualMessengerMatcher.isWhatsApp(label)
            }
            .sortedWith(compareBy<NodeLabels> {
                Rect().also(it.node::getBoundsInScreen).centerY()
            }.thenBy {
                Rect().also(it.node::getBoundsInScreen).centerX()
            })
            .fold(mutableListOf<NodeLabels>()) { rows, candidate ->
                val candidateBounds = Rect()
                candidate.node.getBoundsInScreen(candidateBounds)
                val duplicateCell = rows.any { existing ->
                    val existingBounds = Rect()
                    existing.node.getBoundsInScreen(existingBounds)
                    kotlin.math.abs(existingBounds.centerY() - candidateBounds.centerY()) < dpToPx(24) &&
                        kotlin.math.abs(existingBounds.centerX() - candidateBounds.centerX()) < dpToPx(24)
                }
                if (!duplicateCell) rows += candidate
                rows
            }

        val candidate = explicitDual ?: whatsappRows.getOrNull(1) ?: return
        val clicked = withContext(Dispatchers.Main.immediate) {
            clickNodeParentOrGesture(candidate.node)
        }
        if (clicked) {
            lastClickAt = SystemClock.elapsedRealtime()
            app.preferences.transitionAutomation(
                AutomationStage.WAITING_FOR_WHATSAPP,
                "Samsung Dual Messenger WhatsApp selected automatically",
                resetRetries = true
            )
        }
    }

    private fun collectVisibleNodes(root: AccessibilityNodeInfo): List<NodeLabels> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val nodes = ArrayList<NodeLabels>(96)
        queue.add(root)
        var inspected = 0
        while (queue.isNotEmpty() && inspected < MAX_NODES) {
            val node = queue.removeFirst()
            inspected += 1
            nodes += NodeLabels(
                node = node,
                text = node.text,
                description = node.contentDescription,
                hint = node.hintText,
                viewId = node.viewIdResourceName
            )
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return nodes
    }

    private fun resolverCandidateScore(node: AccessibilityNodeInfo, explicit: Boolean): Int {
        var score = if (explicit) 200 else 0
        if (node.isClickable) score += 60
        if (node.parent?.isClickable == true) score += 35
        if (!node.contentDescription.isNullOrBlank()) score += 20
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!bounds.isEmpty) score += 10
        return score
    }

    private fun inspectScreen(root: AccessibilityNodeInfo): ScreenInspection {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val nodes = ArrayList<NodeLabels>(128)
        queue.add(root)
        var inspected = 0

        while (queue.isNotEmpty() && inspected < MAX_NODES) {
            val node = queue.removeFirst()
            inspected += 1
            nodes += NodeLabels(
                node = node,
                text = node.text,
                description = node.contentDescription,
                hint = node.hintText,
                viewId = node.viewIdResourceName
            )
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }

        val visibleLabels = nodes.asSequence()
            .filter { it.node.isVisibleToUser }
            .flatMap { sequenceOf(it.text, it.description, it.hint, it.viewId) }
            .toList()
        val allowConfirmation = app.preferences.accessibilityPendingLinkId > 0L
        val loadingTextSeen = visibleLabels.any(AccessibilityJoinMatcher::isLoading)
        val progressIndicatorSeen = nodes.any { item ->
            item.node.isVisibleToUser &&
                item.node.className?.toString()?.contains("ProgressBar", ignoreCase = true) == true
        }
        val topRightCloseGeometrySeen = nodes.any { looksLikeTopRightCloseCandidate(it.node) }
        val loading = loadingTextSeen || (progressIndicatorSeen && topRightCloseGeometrySeen)
        // Screenshot-derived context: this notice is shown BEFORE the request is sent.
        // It should strongly favor the explicit Request-to-join control, never mark completion by itself.
        val requestApprovalNoticeSeen = visibleLabels.any(AccessibilityJoinMatcher::isRequestApprovalNotice)
        val explicitInviteActionSeen = visibleLabels.any { label ->
            when (AccessibilityJoinMatcher.actionType(label, inviteContext = false)) {
                AccessibilityJoinAction.PREVIEW,
                AccessibilityJoinAction.JOIN,
                AccessibilityJoinAction.REQUEST -> true
                AccessibilityJoinAction.CONFIRM, null -> false
            }
        }
        val inviteContext = visibleLabels.any(AccessibilityJoinMatcher::hasInviteContext) ||
            explicitInviteActionSeen || requestApprovalNoticeSeen || loading
        val conversationComposerSeen = visibleLabels.any(AccessibilityJoinMatcher::isConversationComposer)
        val conversationActionSeen = visibleLabels.any(AccessibilityJoinMatcher::isConversationAction)
        val conversationSurface = !inviteContext && conversationComposerSeen && conversationActionSeen
        val communityHomeSurface = !inviteContext &&
            CommunityTraversalMatcher.isCommunityHomeAcross(visibleLabels.asSequence())
        val strongHomeSurface = visibleLabels.any(AccessibilityJoinMatcher::isWhatsAppHomeSurface)
        val homeTabEvidenceCount = visibleLabels.count(AccessibilityJoinMatcher::isWhatsAppHomeTab)
        val homeSurface = !inviteContext && !conversationSurface && !communityHomeSurface &&
            (strongHomeSurface || homeTabEvidenceCount >= 2)

        var restricted = false
        var alreadyMember = false
        var strongRequestSubmitted = false
        val terminalEvidenceKinds = linkedSetOf<String>()
        val positiveActionKinds = linkedSetOf<AccessibilityJoinAction>()
        var cancelRequestSeen = false
        var failure: AccessibilityFailureType? = null
        var action: AccessibilityJoinAction? = null
        var actionTarget = AccessibilityInviteTarget.UNKNOWN
        var actionNode: AccessibilityNodeInfo? = null
        var terminalAcknowledgementNode: AccessibilityNodeInfo? = null
        var bestTerminalAcknowledgementScore = Int.MIN_VALUE
        var bestActionScore = Int.MIN_VALUE
        var secondBestActionScore = Int.MIN_VALUE
        var closeNode: AccessibilityNodeInfo? = null
        var bestCloseScore = Int.MIN_VALUE
        var communityOpenNode: AccessibilityNodeInfo? = null
        var bestCommunityOpenScore = Int.MIN_VALUE

        for (item in nodes) {
            if (!item.node.isVisibleToUser) continue
            val labels = listOf(item.text, item.description, item.hint, item.viewId)
            val blockedNode = labels.any(AccessibilityJoinMatcher::isBlockedAction)
            for (label in labels) {
                if (AccessibilityJoinMatcher.isSafeClose(label) && item.node.isVisibleToUser && item.node.isEnabled) {
                    val score = closeCandidateScore(item.node, label)
                    if (score > bestCloseScore) {
                        bestCloseScore = score
                        closeNode = item.node
                    }
                }
                if (AccessibilityJoinMatcher.isTerminalAcknowledgement(label) && item.node.isVisibleToUser && item.node.isEnabled) {
                    val score = closeCandidateScore(item.node, label)
                    if (score > bestTerminalAcknowledgementScore) {
                        bestTerminalAcknowledgementScore = score
                        terminalAcknowledgementNode = item.node
                    }
                }
                if (CommunityTraversalMatcher.isOpenCommunity(label) &&
                    item.node.isVisibleToUser && item.node.isEnabled
                ) {
                    val score = actionCandidateScore(
                        item.node, AccessibilityJoinAction.PREVIEW, label, inviteContext = true,
                        requestApprovalNoticeSeen = false
                    )
                    if (score > bestCommunityOpenScore) {
                        bestCommunityOpenScore = score
                        communityOpenNode = item.node
                    }
                }
                if (AccessibilityJoinMatcher.isRestricted(label)) restricted = true
                if (AccessibilityJoinMatcher.isAlreadyMember(label)) {
                    alreadyMember = true
                    terminalEvidenceKinds += "ALREADY_MEMBER"
                }
                if (AccessibilityJoinMatcher.isRequestSubmitted(label)) {
                    strongRequestSubmitted = true
                    terminalEvidenceKinds += "REQUEST_SUBMITTED"
                }
                if (AccessibilityJoinMatcher.isCancelRequest(label)) cancelRequestSeen = true
                val foundFailure = AccessibilityJoinMatcher.failureType(label)
                if (foundFailure != null) terminalEvidenceKinds += foundFailure.name
                if (failurePriority(foundFailure) > failurePriority(failure)) failure = foundFailure
                if (blockedNode) continue

                val foundAction = when {
                    allowConfirmation && AccessibilityJoinMatcher.isConfirmation(label) ->
                        AccessibilityJoinAction.CONFIRM
                    else -> AccessibilityJoinMatcher.actionType(label, inviteContext)
                }
                if (foundAction != null && item.node.isVisibleToUser && item.node.isEnabled) {
                    positiveActionKinds += foundAction
                    val score = actionCandidateScore(
                        item.node, foundAction, label, inviteContext, requestApprovalNoticeSeen
                    )
                    if (score > bestActionScore) {
                        secondBestActionScore = bestActionScore
                        bestActionScore = score
                        action = foundAction
                        actionTarget = AccessibilityJoinMatcher.targetType(label)
                        actionNode = item.node
                    } else if (score > secondBestActionScore) {
                        secondBestActionScore = score
                    }
                }
            }
        }

        // Screen-level semantic pass: WhatsApp may split one terminal sentence across several
        // accessibility nodes. Aggregate all visible labels so reset/removed/full/rejected dialogs
        // are still terminal even when no single node contains the whole sentence.
        val aggregateFailure = AccessibilityJoinMatcher.failureTypeAcross(visibleLabels.asSequence())
        if (aggregateFailure != null) {
            terminalEvidenceKinds += aggregateFailure.name
            if (failurePriority(aggregateFailure) > failurePriority(failure)) failure = aggregateFailure
        }
        if (AccessibilityJoinMatcher.isRequestSubmittedAcross(visibleLabels.asSequence())) {
            strongRequestSubmitted = true
            terminalEvidenceKinds += "REQUEST_SUBMITTED"
        }

        // A visible Cancel request control means a request is already pending only when
        // WhatsApp is not simultaneously offering a fresh Request to join action. This avoids
        // confusing the pre-request admin-approval notice with a submitted request.
        val pendingRequestWasPressed =
            app.preferences.accessibilityPendingLinkId > 0L &&
                app.preferences.accessibilityPendingAction == AccessibilityJoinAction.REQUEST.name
        val postClickApprovalVariant =
            pendingRequestWasPressed &&
                requestApprovalNoticeSeen &&
                action != AccessibilityJoinAction.REQUEST

        val requestSubmitted = strongRequestSubmitted ||
            (cancelRequestSeen && action != AccessibilityJoinAction.REQUEST) ||
            postClickApprovalVariant
        if (requestSubmitted) terminalEvidenceKinds += "REQUEST_SUBMITTED"

        // “موافق/OK” on a terminal error is a dismissal control, not a new workflow action.
        // Removing it from positive-action evidence lets reset/removed/full/request-submitted
        // screens advance immediately in Turbo mode instead of waiting on a false conflict.
        if (terminalEvidenceKinds.isNotEmpty() && action == AccessibilityJoinAction.CONFIRM) {
            positiveActionKinds.remove(AccessibilityJoinAction.CONFIRM)
            action = null
            actionTarget = AccessibilityInviteTarget.UNKNOWN
            actionNode = null
        }

        val screenTarget = AccessibilityJoinMatcher.targetTypeAcross(visibleLabels.asSequence())
        if (actionTarget == AccessibilityInviteTarget.UNKNOWN &&
            action in setOf(AccessibilityJoinAction.PREVIEW, AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST)
        ) {
            actionTarget = screenTarget
        }

        val communityGroupCandidates = if (communityHomeSurface) {
            val seen = linkedSetOf<String>()
            nodes.asSequence()
                .filter { it.node.isVisibleToUser && it.node.isEnabled }
                .mapNotNull { item ->
                    val clickableNode = when {
                        item.node.isClickable -> item.node
                        item.node.parent?.isClickable == true -> item.node.parent
                        else -> null
                    } ?: return@mapNotNull null
                    if (!isPlausibleCommunityRowGeometry(clickableNode)) return@mapNotNull null
                    if (!CommunityTraversalMatcher.looksLikeGroupRow(
                            text = item.text,
                            description = item.description,
                            viewId = item.viewId,
                            className = item.node.className,
                            clickable = true
                        )
                    ) return@mapNotNull null
                    val key = CommunityTraversalMatcher.stableGroupKey(item.text, item.description, item.viewId)
                    if (key.isBlank() || !seen.add(key)) return@mapNotNull null
                    CommunityGroupCandidate(clickableNode, key)
                }
                .take(32)
                .toList()
        } else emptyList()

        val communityScrollNode = if (communityHomeSurface) {
            nodes.asSequence()
                .map { it.node }
                .filter { it.isVisibleToUser && it.isEnabled && it.isScrollable }
                .maxByOrNull { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.isEmpty) 0 else bounds.width() * bounds.height()
                }
        } else null

        val actionScoreMargin = if (bestActionScore == Int.MIN_VALUE || secondBestActionScore == Int.MIN_VALUE) {
            Int.MAX_VALUE
        } else {
            (bestActionScore - secondBestActionScore).coerceAtLeast(0)
        }

        val evidenceConflict = ScreenEvidencePolicy.conflict(
            ScreenEvidenceSummary(
                terminalEvidenceCount = terminalEvidenceKinds.size,
                positiveActionCount = positiveActionKinds.size,
                conversationSurface = conversationSurface,
                homeSurface = homeSurface
            )
        )
        val fingerprint = if (RuntimeFeatureFlags.SCREEN_FINGERPRINT) {
            RuntimeScreenFingerprint.calculate(
                visibleLabels.asSequence(),
                sequenceOf(
                    "invite=$inviteContext",
                    "loading=$loading",
                    "home=$homeSurface",
                    "conversation=$conversationSurface",
                    "communityHome=$communityHomeSurface",
                    "screenTarget=${screenTarget.name}",
                    "restricted=$restricted",
                    "action=${action?.name ?: "NONE"}",
                    "failure=${failure?.name ?: "NONE"}",
                    "request=$requestSubmitted",
                    "member=$alreadyMember",
                    "conflict=${evidenceConflict.name}"
                )
            )
        } else 0L

        val scrollNode = if (inviteContext && !loading && actionNode == null) {
            nodes.asSequence()
                .map { it.node }
                .filter { it.isVisibleToUser && it.isEnabled && it.isScrollable }
                .maxByOrNull { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.isEmpty) 0 else bounds.width() * bounds.height()
                }
        } else null

        // Some WhatsApp versions expose the invitation X as an unlabeled image/button.
        // Accept that geometry only inside a verified invitation surface.
        if ((inviteContext || terminalEvidenceKinds.isNotEmpty()) &&
            !conversationSurface && closeNode == null
        ) {
            val requestTerminal = "REQUEST_SUBMITTED" in terminalEvidenceKinds
            closeNode = nodes.asSequence()
                .map { it.node }
                .filter { it.isVisibleToUser && it.isEnabled }
                .filter { node ->
                    looksLikeTopRightCloseCandidate(node) ||
                        (requestTerminal && looksLikeRequestSheetCloseCandidate(node))
                }
                .maxByOrNull(::visualCloseCandidateScore)
        }

        return ScreenInspection(
            action = action,
            actionTarget = actionTarget,
            actionNode = actionNode,
            scrollNode = scrollNode,
            closeNode = closeNode,
            terminalAcknowledgementNode = terminalAcknowledgementNode,
            inviteContext = inviteContext,
            restricted = restricted,
            alreadyMember = alreadyMember,
            requestSubmitted = requestSubmitted,
            requestApprovalNoticeSeen = requestApprovalNoticeSeen,
            failure = failure,
            loading = loading,
            homeSurface = homeSurface,
            conversationSurface = conversationSurface,
            communityHomeSurface = communityHomeSurface,
            screenTarget = screenTarget,
            communityOpenNode = communityOpenNode,
            communityGroupCandidates = communityGroupCandidates,
            communityScrollNode = communityScrollNode,
            evidenceConflict = evidenceConflict,
            terminalEvidenceCount = terminalEvidenceKinds.size,
            positiveActionCount = positiveActionKinds.size,
            actionScoreMargin = actionScoreMargin,
            fingerprint = fingerprint
        )
    }

    private fun isPlausibleCommunityRowGeometry(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)
        val centerY = bounds.centerY()
        // Exclude the toolbar/community title band and the bottom navigation band. Group rows are
        // expected in the scrollable content body; this protects generic title resource IDs from
        // being treated as subgroup rows on OEM/WhatsApp layout variations.
        if (centerY < (screenHeight * 0.14f).toInt()) return false
        if (centerY > (screenHeight * 0.95f).toInt()) return false
        return bounds.width() >= dpToPx(48) && bounds.height() in dpToPx(18)..dpToPx(180)
    }

    private fun looksLikeTopRightCloseCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        val imageLike = node.className?.toString()?.contains("Image", ignoreCase = true) == true
        if (!node.isClickable && node.parent?.isClickable != true && !imageLike) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val maxSize = dpToPx(112)
        val topBand = minOf((height * 0.18f).toInt(), dpToPx(210))
        val nearMirroredCorner =
            bounds.centerX() <= (width * 0.30f).toInt() ||
            bounds.centerX() >= (width * 0.70f).toInt()
        return nearMirroredCorner &&
            bounds.centerY() <= topBand &&
            bounds.width() in dpToPx(18)..maxSize &&
            bounds.height() in dpToPx(18)..maxSize
    }


    /**
     * Rescue for WhatsApp's request-sent / waiting-for-admin bottom sheet.
     * The X can sit halfway down the physical display. Wide controls are rejected,
     * therefore "Cancel request" can never be selected by this geometry path.
     */
    private fun looksLikeRequestSheetCloseCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        val imageLike = node.className?.toString()?.contains("Image", ignoreCase = true) == true
        val id = node.viewIdResourceName.orEmpty()
        val closeId = id.contains("close", ignoreCase = true) || id.contains("dismiss", ignoreCase = true)
        if (!node.isClickable && node.parent?.isClickable != true && !imageLike && !closeId) return false

        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val nearEdge = bounds.centerX() <= (width * 0.20f).toInt() ||
            bounds.centerX() >= (width * 0.80f).toInt()
        val inSheetTopBand = bounds.centerY() in
            (height * 0.26f).toInt()..(height * 0.78f).toInt()
        val maxWidth = minOf((width * 0.14f).toInt(), dpToPx(96))
        val maxHeight = minOf((height * 0.09f).toInt(), dpToPx(96))
        val compact = bounds.width() in dpToPx(16)..maxWidth &&
            bounds.height() in dpToPx(16)..maxHeight
        val roughlySquare =
            bounds.width() <= bounds.height() * 2 &&
                bounds.height() <= bounds.width() * 2
        return nearEdge && inSheetTopBand && compact && roughlySquare
    }

    private fun visualCloseCandidateScore(node: AccessibilityNodeInfo): Int {
        val bounds = Rect().also(node::getBoundsInScreen)
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var score = 0
        if (node.isClickable) score += 50
        if (node.parent?.isClickable == true) score += 25
        if (node.className?.toString()?.contains("Image", ignoreCase = true) == true) score += 20
        val edgeDistance = minOf(
            bounds.centerX().coerceAtLeast(0),
            (width - bounds.centerX()).coerceAtLeast(0)
        )
        score += (25 - (edgeDistance * 25 / width)).coerceAtLeast(0)
        score += (30 - (bounds.centerY() / dpToPx(8))).coerceAtLeast(0)
        return score
    }

    private fun actionCandidateScore(
        node: AccessibilityNodeInfo,
        action: AccessibilityJoinAction,
        label: CharSequence?,
        inviteContext: Boolean,
        requestApprovalNoticeSeen: Boolean
    ): Int {
        val bounds = Rect().also(node::getBoundsInScreen)
        return AccessibilityActionScoringPolicy.score(
            action = action,
            requestApprovalNoticeSeen = requestApprovalNoticeSeen,
            clickable = node.isClickable,
            clickableParent = node.parent?.isClickable == true,
            buttonClass = node.className?.toString()?.contains("Button", ignoreCase = true) == true,
            hasViewId = !node.viewIdResourceName.isNullOrBlank(),
            textLabel = !node.text.isNullOrBlank() && label == node.text,
            inviteContext = inviteContext,
            adequateBounds = !bounds.isEmpty &&
                bounds.width() >= dpToPx(MIN_ACTION_WIDTH_DP) &&
                bounds.height() >= dpToPx(MIN_ACTION_HEIGHT_DP)
        )
    }

    private fun closeCandidateScore(node: AccessibilityNodeInfo, label: CharSequence?): Int {
        var score = 0
        if (node.isClickable) score += 50
        if (node.parent?.isClickable == true) score += 25
        if (!node.contentDescription.isNullOrBlank() && label == node.contentDescription) score += 30
        if (!node.viewIdResourceName.isNullOrBlank()) score += 20
        val bounds = Rect().also(node::getBoundsInScreen)
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        if (!bounds.isEmpty && bounds.centerX() >= (width * 0.65f).toInt()) score += 25
        if (!bounds.isEmpty && bounds.centerY() <= dpToPx(180)) score += 25
        return score
    }

    private fun failurePriority(failure: AccessibilityFailureType?): Int = when (failure) {
        AccessibilityFailureType.REMOVED_OR_BANNED -> 4
        AccessibilityFailureType.INVALID_OR_EXPIRED -> 3
        AccessibilityFailureType.GROUP_FULL -> 2
        AccessibilityFailureType.GENERIC -> 1
        null -> 0
    }

    private fun dispatchReliableScrollGesture(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.coerceAtLeast(2)
        val screenHeight = metrics.heightPixels.coerceAtLeast(2)
        val top = bounds.top.coerceIn(1, screenHeight - 2)
        val bottom = bounds.bottom.coerceIn(top + 1, screenHeight - 1)
        val usableHeight = bottom - top
        if (bounds.width() < dpToPx(48) || usableHeight < dpToPx(80)) return false
        val x = bounds.centerX().coerceIn(1, screenWidth - 1)
        val startY = top + usableHeight * 74 / 100
        val endY = top + usableHeight * 30 / 100
        if (startY <= endY) return false
        val path = Path().apply {
            moveTo(x.toFloat(), startY.toFloat())
            lineTo(x.toFloat(), endY.toFloat())
        }
        val duration = runtimeSpeed().gestureDurationMs.coerceIn(12L, 40L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun clickNodeParentOrGesture(
        node: AccessibilityNodeInfo,
        allowSafeClose: Boolean = false,
        gestureFirst: Boolean = false
    ): Boolean {
        val originalBounds = Rect().also(node::getBoundsInScreen)
        if (originalBounds.isEmpty || nodeHasBlockedAction(node, allowSafeClose)) return false

        var candidate: AccessibilityNodeInfo? = node
        var gestureTarget: AccessibilityNodeInfo? = null
        val clickableCandidates = ArrayList<AccessibilityNodeInfo>(MAX_PARENT_DEPTH + 1)
        repeat(MAX_PARENT_DEPTH + 1) {
            val current = candidate ?: return@repeat
            val currentBounds = Rect().also(current::getBoundsInScreen)
            val safeAncestor = isSafeActionContainer(current, currentBounds, originalBounds)
            val blocked = nodeHasBlockedAction(current, allowSafeClose)

            if (!blocked && safeAncestor) {
                // R5: keep the nearest safe semantic bounds. WhatsApp frequently exposes the
                // Join/Request label as a non-clickable child inside a MaterialButton. Replacing
                // it with a large ancestor moves the gesture center away from the real control.
                if (gestureTarget == null) gestureTarget = current
                if (current.isClickable) clickableCandidates += current
            }
            candidate = current.parent
        }

        val target = gestureTarget ?: return false
        val bounds = Rect().also(target::getBoundsInScreen)
        if (bounds.isEmpty || bounds.centerX() <= 0 || bounds.centerY() <= 0) return false

        fun dispatchSemanticGesture(): Boolean {
            val metrics = resources.displayMetrics
            val x = bounds.centerX().coerceIn(1, metrics.widthPixels - 1)
            val y = bounds.centerY().coerceIn(1, metrics.heightPixels - 1)
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val duration = runtimeSpeed().gestureDurationMs
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            return dispatchGesture(gesture, null, null)
        }

        // A real touch gesture is the primary positive-action path. Some recent WhatsApp Material
        // buttons return true for AccessibilityNodeInfo.ACTION_CLICK but discard it internally.
        if (gestureFirst && dispatchSemanticGesture()) return true
        for (clickable in clickableCandidates) {
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        if (!gestureFirst && dispatchSemanticGesture()) return true

        return false
    }

    /**
     * Accessibility compatibility lane for WhatsApp builds that visually render Join/Request but
     * do not expose a usable semantic node. The detector is deliberately narrow: only one wide
     * WhatsApp-green control in the lower half of the selected WhatsApp screen is accepted.
     */
    private suspend fun maybeHandleAccessibilityVisualFallback(
        current: GroupLink,
        screen: ScreenInspection
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (screen.loading || screen.restricted || hasTerminalEvidence(screen) || screen.action != null) return false
        if (screen.homeSurface || screen.conversationSurface || screen.communityHomeSurface) return false

        val now = SystemClock.elapsedRealtime()
        if (accessibilityVisualProbeLinkId != current.id) {
            accessibilityVisualProbeLinkId = current.id
            accessibilityVisualProbeAttempts = 0
            lastAccessibilityVisualProbeAtElapsed = 0L
            accessibilityVisualActionTappedAtElapsed = 0L
            accessibilityVisualTapAttempts = 0
            accessibilityVisualExpectedAction = when {
                screen.requestApprovalNoticeSeen -> AccessibilityJoinAction.REQUEST
                else -> readPendingAction(current)
            }
        }
        if (screen.requestApprovalNoticeSeen) {
            accessibilityVisualExpectedAction = AccessibilityJoinAction.REQUEST
        }
        if (accessibilityVisualActionTappedAtElapsed > 0L) return true

        val stageAge = (System.currentTimeMillis() - app.preferences.automationStageStartedAt).coerceAtLeast(0L)
        val probeAfter = if (app.preferences.fastHandsFreeMode) {
            ACCESSIBILITY_VISUAL_FAST_PROBE_AFTER_MS
        } else {
            ACCESSIBILITY_VISUAL_NORMAL_PROBE_AFTER_MS
        }
        if (stageAge < probeAfter) return false
        if (accessibilityVisualProbeAttempts >= ACCESSIBILITY_VISUAL_MAX_PROBE_ATTEMPTS) return false
        if (now - lastAccessibilityVisualProbeAtElapsed < ACCESSIBILITY_VISUAL_PROBE_INTERVAL_MS) return false

        accessibilityVisualProbeAttempts += 1
        lastAccessibilityVisualProbeAtElapsed = now
        val probe = captureWidePositiveActionBounds()
        val bounds = probe.bounds
        runtimeDiagnostic(
            current,
            "ACCESSIBILITY_VISUAL_ACTION_PROBE",
            "attempt=$accessibilityVisualProbeAttempts; captured=${probe.captured}; bounds=${bounds ?: "NONE"}; error=${probe.errorCode ?: 0}; semanticAction=false"
        )
        if (!probe.captured || bounds == null) return false

        val tapped = withContext(Dispatchers.Main.immediate) { dispatchGestureTap(bounds) }
        if (!tapped) {
            runtimeDiagnostic(current, "ACCESSIBILITY_VISUAL_ACTION_TAP", "gesture dispatch rejected; bounds=$bounds")
            return false
        }

        accessibilityVisualTapAttempts = 1
        accessibilityVisualActionTappedAtElapsed = SystemClock.elapsedRealtime()
        app.preferences.transitionAutomation(
            AutomationStage.VERIFYING_RESULT,
            "Visual WhatsApp Join/Request input dispatched; verifying the result before continuing",
            resetRetries = true
        )
        runtimeDiagnostic(
            current,
            "ACCESSIBILITY_VISUAL_ACTION_TAP",
            "attempt=1; bounds=$bounds; fixedCoordinate=false"
        )
        scheduleAccessibilityVisualVerification(current.id)
        return true
    }

    private fun scheduleAccessibilityVisualVerification(linkId: Long) {
        serviceScope.launch {
            val verifyDelay = if (app.preferences.fastHandsFreeMode) {
                ACCESSIBILITY_VISUAL_FAST_VERIFY_MS
            } else {
                ACCESSIBILITY_VISUAL_NORMAL_VERIFY_MS
            }
            var screenshotFailures = 0

            while (isActive) {
                delay(verifyDelay)
                if (!app.preferences.accessibilityBatchRunning || app.preferences.accessibilityPaused) return@launch
                if (accessibilityVisualProbeLinkId != linkId || accessibilityVisualActionTappedAtElapsed <= 0L) return@launch

                val current = withContext(Dispatchers.IO) {
                    val sessionId = app.preferences.accessibilitySessionId ?: return@withContext null
                    app.repository.loadAutomationCurrent(sessionId)?.takeIf { it.id == linkId }
                } ?: return@launch

                val inspection = withContext(Dispatchers.Main.immediate) {
                    rootInActiveWindow
                        ?.takeIf { isAutomationWhatsAppPackage(it.packageName?.toString().orEmpty()) }
                        ?.let(::inspectScreen)
                }

                if (inspection?.restricted == true || (inspection != null && hasTerminalEvidence(inspection))) {
                    accessibilityVisualActionTappedAtElapsed = 0L
                    requestScan()
                    return@launch
                }

                val activityProofFresh = lastAutomationWindowStateAtElapsed >= accessibilityVisualActionTappedAtElapsed
                if (inspection?.conversationSurface == true ||
                    (activityProofFresh && isKnownConversationActivity(lastAutomationWindowClassName))
                ) {
                    accessibilityVisualActionTappedAtElapsed = 0L
                    val backSent = withContext(Dispatchers.Main.immediate) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    if (backSent) {
                        val settle = ConversationFastExitPolicy.settleMs(app.preferences.fastHandsFreeMode)
                        if (settle > 0L) delay(settle)
                    }
                    completeAndAdvance(
                        current,
                        LinkStatus.JOINED,
                        LinkResultCode.JOIN_ACTION_COMPLETED,
                        "Visual Join completed; conversation detected, exited immediately, and continued",
                        fastAdvance = true,
                        surfaceAlreadyExited = true
                    )
                    return@launch
                }

                val probe = captureWidePositiveActionBounds()
                if (!probe.captured) {
                    screenshotFailures += 1
                    runtimeDiagnostic(
                        current,
                        "ACCESSIBILITY_VISUAL_VERIFY_SCREENSHOT_FAILED",
                        "failure=$screenshotFailures; error=${probe.errorCode ?: -1}"
                    )
                    if (screenshotFailures < ACCESSIBILITY_VISUAL_MAX_SCREENSHOT_FAILURES) {
                        continue
                    }
                    accessibilityVisualActionTappedAtElapsed = 0L
                    val backSent = withContext(Dispatchers.Main.immediate) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    if (backSent) delay(exitSettleDelayMs())
                    completeAndAdvance(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.ACTION_TIMEOUT,
                        "Visual action was dispatched but Android could not provide a verification screenshot",
                        fastAdvance = true,
                        surfaceAlreadyExited = true
                    )
                    return@launch
                }
                screenshotFailures = 0

                val remaining = probe.bounds
                if (remaining != null && accessibilityVisualTapAttempts < ACCESSIBILITY_VISUAL_MAX_TAP_ATTEMPTS) {
                    val retryTapped = withContext(Dispatchers.Main.immediate) { dispatchGestureTap(remaining) }
                    if (retryTapped) {
                        accessibilityVisualTapAttempts += 1
                        accessibilityVisualActionTappedAtElapsed = SystemClock.elapsedRealtime()
                        runtimeDiagnostic(
                            current,
                            "ACCESSIBILITY_VISUAL_ACTION_RETRY",
                            "attempt=$accessibilityVisualTapAttempts; bounds=$remaining"
                        )
                        continue
                    }
                }

                if (remaining != null) {
                    accessibilityVisualActionTappedAtElapsed = 0L
                    val backSent = withContext(Dispatchers.Main.immediate) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    if (backSent) delay(exitSettleDelayMs())
                    completeAndAdvance(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.ACTION_TIMEOUT,
                        "The visual WhatsApp Join/Request control remained visible after bounded touch retries",
                        fastAdvance = true,
                        surfaceAlreadyExited = true
                    )
                    return@launch
                }

                // The protected positive control disappeared. Do not invent a successful result.
                // REQUESTED is allowed only when the pre-click screen identified the request path.
                accessibilityVisualActionTappedAtElapsed = 0L
                val expected = accessibilityVisualExpectedAction ?: readPendingAction(current)
                val backSent = withContext(Dispatchers.Main.immediate) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                if (backSent) delay(exitSettleDelayMs())
                if (expected == AccessibilityJoinAction.REQUEST) {
                    completeAndAdvance(
                        current,
                        LinkStatus.REQUESTED,
                        LinkResultCode.REQUEST_SENT,
                        "Known Request visual action disappeared; request recorded and queue continued",
                        fastAdvance = true,
                        surfaceAlreadyExited = backSent
                    )
                } else {
                    completeAndAdvance(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.UNKNOWN_SCREEN,
                        "Visual positive action disappeared without verified Join/Request evidence; not counted as a random success",
                        fastAdvance = true,
                        surfaceAlreadyExited = backSent
                    )
                }
                return@launch
            }
        }
    }

    private fun isKnownConversationActivity(className: String?): Boolean {
        val value = className.orEmpty()
        if (value.isBlank()) return false
        return value.contains(".Conversation", ignoreCase = true) ||
            value.contains("ConversationActivity", ignoreCase = true) ||
            value.contains("GroupConversation", ignoreCase = true)
    }

    private fun dispatchGestureTap(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val metrics = resources.displayMetrics
        val x = bounds.centerX().coerceIn(1, metrics.widthPixels - 1)
        val y = bounds.centerY().coerceIn(1, metrics.heightPixels - 1)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = runtimeSpeed().gestureDurationMs
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private suspend fun captureWidePositiveActionBounds(): VisualScreenshotProbe {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return VisualScreenshotProbe(false, null, null)
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    var wrapped: Bitmap? = null
                    var software: Bitmap? = null
                    val result = try {
                        wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        val bitmap = software
                        if (bitmap == null) {
                            null
                        } else {
                            val found = VisualActionButtonPolicy.findWidePositiveAction(
                                width = bitmap.width,
                                height = bitmap.height,
                                pixelAt = bitmap::getPixel
                            )
                            if (found == null) {
                                null
                            } else {
                                val metrics = resources.displayMetrics
                                val scaleX = metrics.widthPixels.toFloat() / bitmap.width.toFloat()
                                val scaleY = metrics.heightPixels.toFloat() / bitmap.height.toFloat()
                                Rect(
                                    (found.left * scaleX).toInt().coerceIn(1, metrics.widthPixels - 1),
                                    (found.top * scaleY).toInt().coerceIn(1, metrics.heightPixels - 1),
                                    (found.right * scaleX).toInt().coerceIn(1, metrics.widthPixels - 1),
                                    (found.bottom * scaleY).toInt().coerceIn(1, metrics.heightPixels - 1)
                                )
                            }
                        }
                    } catch (_: Throwable) {
                        null
                    } finally {
                        runCatching { software?.recycle() }
                        runCatching { wrapped?.recycle() }
                        runCatching { buffer.close() }
                    }
                    if (continuation.isActive) continuation.resume(VisualScreenshotProbe(true, result, null))
                }

                override fun onFailure(errorCode: Int) {
                    runtimeDiagnostic(cachedCurrentLink, "ACCESSIBILITY_SCREENSHOT_FAILED", "errorCode=$errorCode")
                    if (continuation.isActive) continuation.resume(VisualScreenshotProbe(false, null, errorCode))
                }
            }

            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ContextCompat.getMainExecutor(this@QuickJoinAccessibilityService),
                    callback
                )
            }.onFailure {
                if (continuation.isActive) continuation.resume(VisualScreenshotProbe(false, null, null))
            }
        }
    }

    private fun nodeHasBlockedAction(
        node: AccessibilityNodeInfo,
        allowSafeClose: Boolean = false
    ): Boolean = sequenceOf(node.text, node.contentDescription, node.hintText, node.viewIdResourceName)
        .any { label ->
            AccessibilityJoinMatcher.isBlockedAction(label) &&
                !(allowSafeClose && AccessibilityJoinMatcher.isSafeClose(label))
        }

    private fun isSafeActionContainer(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        originalBounds: Rect
    ): Boolean {
        if (!node.isEnabled || !node.isVisibleToUser || bounds.isEmpty) return false
        val metrics = resources.displayMetrics
        val maxWidth = (metrics.widthPixels * 0.98f).toInt().coerceAtLeast(1)
        val maxHeight = minOf((metrics.heightPixels * 0.28f).toInt(), dpToPx(240))
        return bounds.width() <= maxWidth &&
            bounds.height() <= maxHeight &&
            bounds.contains(originalBounds.centerX(), originalBounds.centerY())
    }

    private fun loadCurrentLinkOrStop(): GroupLink? {
        val sessionId = app.preferences.accessibilitySessionId ?: run {
            stopBatch(AutomationStopReason.SESSION_CHANGED, "No active automation session")
            return null
        }
        if (app.preferences.activeSessionId != sessionId) {
            invalidateRuntimeCache()
            stopBatch(AutomationStopReason.SESSION_CHANGED, "Active session changed")
            return null
        }

        cachedCurrentLink?.let { cached ->
            if (cachedSessionId == sessionId && cached.sessionId == sessionId && cached.status == LinkStatus.OPENED) {
                return cached
            }
        }

        val total = app.repository.automationSessionTotal(sessionId) ?: run {
            invalidateRuntimeCache()
            stopBatch(AutomationStopReason.SESSION_CHANGED, "Session no longer exists")
            return null
        }
        if (total <= 0 || total > AutomationPolicy.MAX_LINKS_PER_SESSION) {
            invalidateRuntimeCache()
            stopBatch(AutomationStopReason.SESSION_CHANGED, "Session exceeded the supported queue limit")
            return null
        }

        cachedSessionId = sessionId
        cachedSessionTotal = total
        val current = app.repository.loadAutomationCurrent(sessionId)
        cachedCurrentLink = current
        if (current == null && app.repository.isAutomationSessionComplete(sessionId) == true) {
            finishBatch(AutomationStopReason.SESSION_COMPLETE, "Session completed")
        }
        return current
    }

    private fun invalidateRuntimeCache() {
        cachedCurrentLink = null
        cachedSessionId = null
        cachedSessionTotal = 0
        lastScreenFingerprint = 0L
        stableScreenFingerprintScans = 0
        idempotencyGuard.clear()
    }

    private suspend fun fastExitPendingRequestSurface(screen: ScreenInspection): Boolean {
        if (screen.loading || screen.restricted) return false
        val settle = maxOf(
            8L,
            ConversationFastExitPolicy.settleMs(app.preferences.fastHandsFreeMode)
        )

        suspend fun requestSurfaceStillVisible(): Boolean {
            val root = withContext(Dispatchers.Main.immediate) { rootInActiveWindow } ?: return false
            if (!isAutomationWhatsAppPackage(root.packageName?.toString().orEmpty())) return false
            val after = inspectScreen(root)
            return after.requestSubmitted || after.inviteContext
        }

        // Required sequence: X first.
        val closeClicked = screen.closeNode?.let { node ->
            withContext(Dispatchers.Main.immediate) {
                clickNodeParentOrGesture(node, allowSafeClose = true)
            }
        } == true

        if (closeClicked) {
            if (settle > 0L) delay(settle)
            if (!requestSurfaceStillVisible()) return true
        }

        // If X was absent or did not close the sheet, Back is the bounded fallback.
        // This never presses the visible "Cancel request" button.
        repeat(2) {
            val backSent = withContext(Dispatchers.Main.immediate) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            if (!backSent) return@repeat
            if (settle > 0L) delay(settle)
            if (!requestSurfaceStillVisible()) return true
        }

        return false
    }

    private fun readPendingAction(current: GroupLink): AccessibilityJoinAction? {
        if (app.preferences.accessibilityPendingLinkId != current.id) return null
        return runCatching {
            enumValueOf<AccessibilityJoinAction>(app.preferences.accessibilityPendingAction.orEmpty())
        }.getOrNull()
    }

    private suspend fun returnFromJoinedConversationAndAdvance(current: GroupLink) {
        if (isCommunitySubgroupActive(current)) {
            app.preferences.transitionAutomation(
                AutomationStage.VERIFYING_RESULT,
                "Joined Community subgroup conversation detected; returning to the Community list",
                resetRetries = false
            )
            val backSent = withContext(Dispatchers.Main.immediate) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            if (backSent) delay(exitSettleDelayMs())
            completeAndAdvance(
                current,
                LinkStatus.JOINED,
                LinkResultCode.JOIN_ACTION_COMPLETED,
                "Community subgroup joined and returned to the Community list",
                fastAdvance = true,
                surfaceAlreadyExited = backSent
            )
            return
        }

        app.preferences.transitionAutomation(
            AutomationStage.VERIFYING_RESULT,
            "Joined conversation detected; issuing one fast Back pulse, then opening the next invitation immediately",
            resetRetries = false
        )

        val backSent = withContext(Dispatchers.Main.immediate) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (backSent) {
            val settle = ConversationFastExitPolicy.settleMs(app.preferences.fastHandsFreeMode)
            if (settle > 0L) delay(settle)
        }

        completeAndAdvance(
            current,
            LinkStatus.JOINED,
            LinkResultCode.JOIN_ACTION_COMPLETED,
            if (backSent) {
                "Join opened the group conversation; fast Back pulse completed and the next unique link was launched"
            } else {
                "Join opened the group conversation; Back was unavailable, so the next unique link was launched directly"
            },
            fastAdvance = true,
            surfaceAlreadyExited = true
        )
    }

    private suspend fun completeAndAdvance(
        current: GroupLink,
        status: LinkStatus,
        resultCode: LinkResultCode,
        detail: String,
        fastAdvance: Boolean = false,
        surfaceAlreadyExited: Boolean = false,
        terminalEscapeAdvance: Boolean = false,
        communityParentFinalization: Boolean = false
    ) {
        if (!communityParentFinalization && isCommunitySubgroupActive(current)) {
            completeCommunitySubgroupAndContinue(
                current = current,
                status = status,
                resultCode = resultCode,
                detail = detail,
                surfaceAlreadyExited = surfaceAlreadyExited
            )
            return
        }

        // Scanner, result inference and continuity watchdog may observe the same terminal
        // transition at nearly the same time. Serialize only the result commit so processed
        // counts and queue advancement remain exactly-once while the later next-link launch
        // is still free to recurse for OPEN_FAILED/BROWSER_FALLBACK results.
        if (!resultCommitExecuting.compareAndSet(false, true)) return
        val state = try {
            withContext(Dispatchers.IO) {
                val sessionId = app.preferences.accessibilitySessionId ?: return@withContext null
                val stillCurrent = app.repository.loadAutomationCurrent(sessionId)
                if (stillCurrent?.id != current.id) return@withContext null

                app.preferences.markRuntimePhase(
                    LinkRuntimePhase.EXITING,
                    "ACCESSIBILITY:${resultCode.name}"
                )
                val auditedDetail = app.preferences.buildRuntimeAuditDetail(
                    detail,
                    resultCode.name,
                    "ACCESSIBILITY"
                )
                app.repository.markStatus(current.id, status, resultCode, auditedDetail)
                app.preferences.finishRuntimeLink(
                    current.position,
                    "ACCESSIBILITY:${resultCode.name}"
                )
                invalidateRuntimeCache()
                app.preferences.clearAccessibilityPending()
                app.preferences.accessibilityProcessedCount += 1
                val processed = app.preferences.accessibilityProcessedCount
                val next = app.repository.loadAutomationNext(sessionId)
                val total = app.repository.automationSessionTotal(sessionId) ?: 0
                val complete = app.repository.isAutomationSessionComplete(sessionId) == true
                AdvanceState(
                    next = next,
                    processed = processed,
                    total = total,
                    limitReached = processed >= AutomationPolicy.BATCH_SIZE,
                    complete = complete
                )
            }
        } finally {
            resultCommitExecuting.set(false)
        } ?: return

        consecutiveRuntimeFailures = if (RuntimeFeatureFlags.CIRCUIT_BREAKER) {
            RuntimeCircuitBreaker.nextCount(consecutiveRuntimeFailures, resultCode.name)
        } else 0
        runtimeDiagnostic(
            current,
            "RESULT",
            "status=${status.name}; result=${resultCode.name}; runtimeFailures=$consecutiveRuntimeFailures"
        )

        if (RuntimeFeatureFlags.CIRCUIT_BREAKER &&
            RuntimeCircuitBreaker.shouldTrip(consecutiveRuntimeFailures)
        ) {
            // Continuity mode keeps the explicit run moving. Structural failures are already
            // recorded as failed/skipped links and no low-confidence action is clicked, so the
            // safest recovery is to reset the breaker count and continue to the next queued link.
            runtimeDiagnostic(
                current,
                "CIRCUIT_BREAKER_CONTINUE",
                "Reached $consecutiveRuntimeFailures structural failures; reset counter and continue explicit run"
            )
            consecutiveRuntimeFailures = 0
        }

        // Export mirrors are intentionally throttled. Rewriting three files containing thousands
        // of URLs after every result was one of the main sources of stalls on real devices.
        syncResultMirrorIfNeeded(
            processed = state.processed,
            force = state.limitReached || state.complete || state.next == null
        )
        refreshAutomationNotification(force = true, nextLink = state.next, totalOverride = state.total)

        if (!surfaceAlreadyExited) exitInvitationSurface()

        // The result is already committed. Before touching the next row, re-check where the user is.
        if (app.preferences.autoPauseOutsideWhatsApp) {
            val activePackage = withContext(Dispatchers.Main.immediate) {
                rootInActiveWindow?.packageName?.toString().orEmpty()
            }
            val leftTarget = activePackage.isNotBlank() &&
                !isAutomationWhatsAppPackage(activePackage) &&
                activePackage !in RESOLVER_PACKAGES &&
                !isTransientSystemPackage(activePackage)
            if (leftTarget) {
                app.preferences.pauseAccessibilityBatch(
                    diagnostic = "Paused automatically because the user left the selected WhatsApp target",
                    outsideTarget = true
                )
                runtimeDiagnostic(
                    current,
                    "ACCESSIBILITY_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET",
                    "current committed; next remains pending; forced return blocked"
                )
                refreshAutomationNotification(
                    force = true,
                    nextLink = state.next,
                    totalOverride = state.total
                )
                return
            }
        }

        if (!NetworkStateMonitor.isValidatedOnline(this, force = true)) {
            app.preferences.pauseAccessibilityBatch(
                diagnostic = "Paused automatically because the internet connection was lost",
                outsideTarget = false
            )
            app.preferences.pausedBecauseNetworkUnavailable = true
            runtimeDiagnostic(
                current,
                "ACCESSIBILITY_NEXT_HANDOFF_PAUSED_OFFLINE",
                "current committed; next remains pending"
            )
            refreshAutomationNotification(
                force = true,
                nextLink = state.next,
                totalOverride = state.total
            )
            return
        }

        if (state.limitReached) {
            if (app.preferences.autoResumeCurrentRun && state.next != null) {
                app.preferences.accessibilityProcessedCount = 0
                runtimeDiagnostic(
                    current,
                    "AUTO_BATCH_CONTINUE",
                    "1000-link internal window completed; Auto Resume continues the same disk-backed queue"
                )
            } else {
                finishBatch(
                    AutomationStopReason.BATCH_LIMIT_REACHED,
                    "Run window completed; queued links remain resumable"
                )
                return
            }
        }
        if (state.complete || state.next == null) {
            finishBatch(AutomationStopReason.SESSION_COMPLETE, "All links processed")
            return
        }

        // Terminal Escape is intentionally immediate: once a specific terminal state has been
        // confirmed, waiting cannot improve the current link. Normal/success paths still honor
        // the user-selected inter-link delay.
        app.preferences.markRuntimePhase(
            LinkRuntimePhase.ADVANCING,
            "ACCESSIBILITY:NEXT"
        )
        val configuredDelay = app.preferences.runtimeSpeedProfile().interLinkDelayMs.toInt()
        val delayMs = if (terminalEscapeAdvance) 0 else configuredDelay
        if (delayMs <= 0) {
            app.preferences.transitionAutomation(
                AutomationStage.WAITING_BEFORE_NEXT,
                if (terminalEscapeAdvance) "Terminal Escape: opening the next invitation immediately"
                else if (fastAdvance) "Result recorded; opening next link at user-selected instant speed"
                else "Opening next link at user-selected instant speed",
                resetRetries = true
            )
            if (USER_INSTANT_ADVANCE_SETTLE_MS > 0L && !waitShortDelay(USER_INSTANT_ADVANCE_SETTLE_MS)) return
        } else {
            app.preferences.transitionAutomation(
                AutomationStage.WAITING_BEFORE_NEXT,
                "Waiting ${delayMs}ms before next link (user-selected speed)",
                resetRetries = true
            )
            QuickJoinNotification.showAutomation(
                this,
                state.processed,
                state.next.position + 1,
                state.total,
                AutomationPolicy.notificationDelaySeconds(delayMs),
                false
            )
            if (!waitShortDelay(delayMs.toLong())) return
        }

        val opened = withContext(Dispatchers.IO) {
            app.preferences.transitionAutomation(
                AutomationStage.OPENING_LINK, "Opening next invitation", resetRetries = true
            )
            app.repository.markOpened(state.next.id)
        } ?: return
        cachedSessionId = opened.sessionId
        cachedSessionTotal = state.total
        cachedCurrentLink = opened

        when (withContext(Dispatchers.Main.immediate) {
            WhatsAppLauncher.launch(
                this@QuickJoinAccessibilityService,
                opened.url,
                app.preferences.preferredTarget,
                app.preferences.runtimeLockedWhatsAppPackage ?: app.preferences.selectedWhatsAppPackage,
                strictProfileTarget = app.preferences.strictProfileTargeting,
                expectedProfileKey = app.preferences.runtimeLockedProfileKey
            )
        }) {
            LaunchDestination.PERSONAL,
            LaunchDestination.BUSINESS,
            LaunchDestination.CLONED,
            LaunchDestination.SELECTED,
            LaunchDestination.DUAL_CHOOSER -> {
                app.preferences.markAutomationLaunched()
                requestScan()
            }
            LaunchDestination.BROWSER -> completeAndAdvance(
                opened,
                LinkStatus.FAILED,
                LinkResultCode.BROWSER_FALLBACK,
                "Opened outside the selected WhatsApp app; continuing automatically"
            )
            LaunchDestination.NONE -> completeAndAdvance(
                opened,
                LinkStatus.FAILED,
                LinkResultCode.OPEN_FAILED,
                "Selected WhatsApp app could not open the link; continuing automatically"
            )
        }
    }

    private suspend fun syncResultMirrorIfNeeded(processed: Int, force: Boolean) {
        if (!force && processed % RESULT_MIRROR_SYNC_EVERY != 0) return
        withContext(Dispatchers.IO) {
            GroupJoinerResultStore.sync(this@QuickJoinAccessibilityService, app.repository.loadActiveSnapshot())
        }
    }

    /**
     * Leaves WhatsApp invitation surfaces before opening the next link. The routine first
     * clicks a semantic close control (the X shown by WhatsApp), then uses Android Back,
     * and finally performs a guarded top-right tap only while an invitation preview is visible.
     */
    private suspend fun exitInvitationSurface() {
        // A terminal error dialog must be *verified as dismissed* before the next invite is opened.
        // Older Turbo builds used a single exit step; on some WhatsApp builds the X/gesture could
        // report success while the bottom sheet stayed visible, leaving the next ACTION_VIEW hidden
        // behind the old dialog. Terminal surfaces now get a short bounded retry sequence.
        val maxSteps = if (app.preferences.fastHandsFreeMode) FAST_MAX_EXIT_STEPS else MAX_EXIT_STEPS
        var terminalBackFallbackUsed = false

        repeat(maxSteps) { step ->
            val root = withContext(Dispatchers.Main.immediate) { rootInActiveWindow } ?: return
            if (!isAutomationWhatsAppPackage(root.packageName?.toString().orEmpty())) return

            val inspection = inspectScreen(root)
            if (inspection.loading) {
                runtimeDiagnostic(cachedCurrentLink, "EXIT_GUARD", "Invitation/result surface is still loading; close suppressed")
                delay(exitSettleDelayMs())
                return@repeat
            }

            val terminalSurface = hasTerminalEvidence(inspection)
            if (!inspection.inviteContext && inspection.closeNode == null && inspection.action == null &&
                inspection.terminalAcknowledgementNode == null && !terminalSurface
            ) {
                return
            }

            // On a known terminal result, prefer the dialog's own positive acknowledgement
            // (موافق/OK). It is safe here because the result has already been persisted by
            // completeAndAdvance and cannot be mistaken for a Join/Request action.
            if (terminalSurface && inspection.terminalAcknowledgementNode != null) {
                val acknowledgementClicked = inspection.terminalAcknowledgementNode.let { node ->
                    withContext(Dispatchers.Main.immediate) { clickNodeParentOrGesture(node, allowSafeClose = true) }
                }
                if (acknowledgementClicked) {
                    delay(terminalDismissSettleDelayMs())
                    val afterRoot = withContext(Dispatchers.Main.immediate) { rootInActiveWindow }
                    if (afterRoot == null || !isAutomationWhatsAppPackage(afterRoot.packageName?.toString().orEmpty())) return
                    val after = inspectScreen(afterRoot)
                    if (!hasTerminalEvidence(after)) return

                    // Some WhatsApp variants acknowledge the click event before the sheet is
                    // actually removed. One guarded Back is the deterministic fallback.
                    if (!terminalBackFallbackUsed) {
                        val backSent = withContext(Dispatchers.Main.immediate) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        terminalBackFallbackUsed = backSent
                        if (backSent) delay(terminalDismissSettleDelayMs())
                    }
                    return@repeat
                }
            }

            // Prefer WhatsApp's actual X/close node for non-terminal invitation surfaces and as
            // a secondary terminal escape path when no acknowledgement control is available.
            val closeClicked = inspection.closeNode?.let { node ->
                withContext(Dispatchers.Main.immediate) { clickNodeParentOrGesture(node, allowSafeClose = true) }
            } == true
            if (closeClicked) {
                delay(if (terminalSurface) terminalDismissSettleDelayMs() else exitSettleDelayMs())
                val afterRoot = withContext(Dispatchers.Main.immediate) { rootInActiveWindow }
                if (afterRoot == null || !isAutomationWhatsAppPackage(afterRoot.packageName?.toString().orEmpty())) return
                val after = inspectScreen(afterRoot)
                if (!hasTerminalEvidence(after) && !after.inviteContext) return
                if (terminalSurface && hasTerminalEvidence(after) && !terminalBackFallbackUsed) {
                    val backSent = withContext(Dispatchers.Main.immediate) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    terminalBackFallbackUsed = backSent
                    if (backSent) delay(terminalDismissSettleDelayMs())
                }
                return@repeat
            }

            val safeCancel = collectVisibleNodes(root)
                .firstOrNull { item ->
                    item.node.isVisibleToUser &&
                        item.node.isEnabled &&
                        sequenceOf(
                            item.text,
                            item.description,
                            item.hint,
                            item.viewId
                        ).any(AccessibilityJoinMatcher::isSafeDialogCancel)
                }
                ?.node
            if (terminalSurface && safeCancel != null) {
                val cancelled = withContext(Dispatchers.Main.immediate) {
                    clickNodeParentOrGesture(safeCancel, allowSafeClose = true)
                }
                if (cancelled) {
                    delay(terminalDismissSettleDelayMs())
                    return@repeat
                }
            }

            // Normal fallback: Android Back. Coordinate close is reserved for the last verified
            // invite attempt and never used on a normal conversation.
            if (step < maxSteps - 1) {
                val backSent = withContext(Dispatchers.Main.immediate) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                if (backSent) {
                    if (terminalSurface) terminalBackFallbackUsed = true
                    delay(if (terminalSurface) terminalDismissSettleDelayMs() else exitSettleDelayMs())
                    return@repeat
                }
            }

            if (inspection.inviteContext && step == maxSteps - 1) {
                val cornerTapped = withContext(Dispatchers.Main.immediate) { tapPreviewCloseCorner() }
                if (cornerTapped) {
                    delay(if (terminalSurface) terminalDismissSettleDelayMs() else exitSettleDelayMs())
                    return@repeat
                }
            }

            val backSent = withContext(Dispatchers.Main.immediate) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            if (backSent) delay(if (terminalSurface) terminalDismissSettleDelayMs() else exitSettleDelayMs())
        }

        // Final bounded verification. If a terminal sheet is still visible after all semantic
        // attempts, dismiss it once with Back so it cannot block the next invite intent.
        val finalRoot = withContext(Dispatchers.Main.immediate) { rootInActiveWindow }
        if (finalRoot != null && isAutomationWhatsAppPackage(finalRoot.packageName?.toString().orEmpty())) {
            val finalInspection = inspectScreen(finalRoot)
            if (hasTerminalEvidence(finalInspection)) {
                val backSent = withContext(Dispatchers.Main.immediate) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                if (backSent) delay(terminalDismissSettleDelayMs())
            }
        }
    }

    private fun terminalDismissSettleDelayMs(): Long =
        if (app.preferences.fastHandsFreeMode) RuntimeCadencePolicy.FAST_TERMINAL_SETTLE_MS
        else RuntimeCadencePolicy.NORMAL_EXIT_SETTLE_MS

    private fun tapPreviewCloseCorner(): Boolean {
        val metrics = resources.displayMetrics
        // Ratio-based fallback derived from the supplied WhatsApp invite sheets.
        // It remains guarded by verified invitation context and is never used on normal chats.
        val x = metrics.widthPixels * 0.92f
        val y = minOf(metrics.heightPixels * 0.085f, dpToPx(84).toFloat())
            .coerceAtLeast(dpToPx(42).toFloat())
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    runtimeSpeed().gestureDurationMs
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun exitSettleDelayMs(): Long =
        runtimeSpeed().postTapWaitMs.coerceIn(4L, 80L)

    private suspend fun waitShortDelay(milliseconds: Long): Boolean {
        val safe = milliseconds.coerceAtLeast(0L)
        if (!app.preferences.accessibilityBatchRunning) return false
        if (safe == 0L) return !app.preferences.accessibilityPaused
        val deadline = SystemClock.elapsedRealtime() + safe
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!app.preferences.accessibilityBatchRunning) return false
            while (app.preferences.accessibilityPaused) {
                if (!app.preferences.accessibilityBatchRunning) return false
                delay(60)
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining > 0L) delay(minOf(remaining, 10L))
        }
        return app.preferences.accessibilityBatchRunning
    }

    private suspend fun waitDelay(seconds: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!app.preferences.accessibilityBatchRunning) return false
            while (app.preferences.accessibilityPaused) {
                if (!app.preferences.accessibilityBatchRunning) return false
                delay(300)
            }
            delay(200)
        }
        return app.preferences.accessibilityBatchRunning
    }

    private fun resultInferenceDelayMs(): Long =
        runtimeSpeed().postTapWaitMs

    private fun refreshAutomationNotification(
        force: Boolean,
        nextLink: GroupLink? = null,
        totalOverride: Int? = null
    ) {
        val prefs = app.preferences
        if (!prefs.accessibilityBatchRunning) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationRefreshAtElapsed < NOTIFICATION_REFRESH_MIN_MS) return
        lastNotificationRefreshAtElapsed = now

        val current = nextLink ?: cachedCurrentLink
        val total = totalOverride ?: cachedSessionTotal
        QuickJoinNotification.showAutomation(
            context = this,
            processedInBatch = prefs.accessibilityProcessedCount,
            currentLinkNumber = current?.position?.plus(1),
            totalLinks = total,
            delaySeconds = prefs.accessibilityJoinDelaySeconds,
            paused = prefs.accessibilityPaused
        )
    }

    private fun stopBatch(reason: AutomationStopReason, diagnostic: String) {
        app.preferences.stopAccessibilityBatch(reason, diagnostic)
        invalidateRuntimeCache()
        scanPending.set(false)
        QuickJoinNotification.cancel(this)
        serviceScope.launch(Dispatchers.IO) {
            GroupJoinerResultStore.sync(this@QuickJoinAccessibilityService, app.repository.loadActiveSnapshot())
        }
    }

    private fun finishBatch(reason: AutomationStopReason, diagnostic: String) {
        app.preferences.completeAccessibilityBatch(reason, diagnostic)
        invalidateRuntimeCache()
        scanPending.set(false)
        QuickJoinNotification.cancel(this)
        serviceScope.launch(Dispatchers.IO) {
            GroupJoinerResultStore.sync(this@QuickJoinAccessibilityService, app.repository.loadActiveSnapshot())
        }
        if (app.preferences.returnToAppOnRunComplete) {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("automation_finish_reason", reason.name)
                })
            }
        }
    }

    private data class NodeLabels(
        val node: AccessibilityNodeInfo,
        val text: CharSequence?,
        val description: CharSequence?,
        val hint: CharSequence?,
        val viewId: CharSequence?
    )

    private data class CommunityGroupCandidate(
        val node: AccessibilityNodeInfo,
        val key: String
    )

    private data class VisualScreenshotProbe(
        val captured: Boolean,
        val bounds: Rect?,
        val errorCode: Int?
    )

    private data class ScreenInspection(
        val action: AccessibilityJoinAction?,
        val actionTarget: AccessibilityInviteTarget,
        val actionNode: AccessibilityNodeInfo?,
        val scrollNode: AccessibilityNodeInfo?,
        val closeNode: AccessibilityNodeInfo?,
        val terminalAcknowledgementNode: AccessibilityNodeInfo?,
        val inviteContext: Boolean,
        val restricted: Boolean,
        val alreadyMember: Boolean,
        val requestSubmitted: Boolean,
        val requestApprovalNoticeSeen: Boolean,
        val failure: AccessibilityFailureType?,
        val loading: Boolean,
        val homeSurface: Boolean,
        val conversationSurface: Boolean,
        val communityHomeSurface: Boolean,
        val screenTarget: AccessibilityInviteTarget,
        val communityOpenNode: AccessibilityNodeInfo?,
        val communityGroupCandidates: List<CommunityGroupCandidate>,
        val communityScrollNode: AccessibilityNodeInfo?,
        val evidenceConflict: ScreenEvidenceConflict,
        val terminalEvidenceCount: Int,
        val positiveActionCount: Int,
        val actionScoreMargin: Int,
        val fingerprint: Long
    )

    private data class ScheduledLaunchState(
        val link: GroupLink?,
        val total: Int
    )

    private data class AdvanceState(
        val next: GroupLink?,
        val processed: Int,
        val total: Int,
        val limitReached: Boolean,
        val complete: Boolean
    )

    companion object {
        @Volatile private var runtimeConnected = false
        @Volatile private var liveInstance: QuickJoinAccessibilityService? = null

        /** True only after Android has connected this exact installed service in this app process. */
        fun isRuntimeConnected(): Boolean = runtimeConnected

        /**
         * Same-process kick used after an explicit ACTION_VIEW launch.
         * This makes a second/third run independent of whether WhatsApp emits a fresh event.
         */
        fun requestImmediateScan(): Boolean {
            val instance = liveInstance ?: return false
            if (!runtimeConnected) return false
            instance.lastScanAt = 0L
            instance.requestScan()
            return true
        }

        private const val MAX_NODES = 1_200
        private const val MIN_ACTION_WIDTH_DP = 48
        private const val MIN_ACTION_HEIGHT_DP = 32
        private const val MAX_PARENT_DEPTH = 5
        private const val MAX_CLICK_RETRIES = 4
        private const val MAX_EXIT_STEPS = 4
        private const val FAST_MAX_EXIT_STEPS = 3
        private const val MAX_INVITE_SCROLL_ATTEMPTS = 2
        private const val USER_INSTANT_ADVANCE_SETTLE_MS = 0L
        private const val SCHEDULED_WAKE_LOCK_MS = 30_000L
        private const val NETWORK_PAUSE_POLL_MS = 650L
        private const val RESULT_MIRROR_SYNC_EVERY = 1000
        private const val DIAGNOSTIC_REPEAT_SUPPRESSION_MS = 1_500L
        private const val IDEMPOTENCY_SUPPRESSION_MS = 450L
        private const val NOTIFICATION_REFRESH_MIN_MS = 750L
        private const val DIRECT_CONVERSATION_STABLE_SCANS = 1
        private const val DIRECT_CONVERSATION_FAST_MIN_AGE_MS = 30L
        private const val DIRECT_CONVERSATION_NORMAL_MIN_AGE_MS = 650L
        private const val DIRECT_CONVERSATION_WINDOW_EVENT_MAX_AGE_MS = 1_200L
        private const val ACCESSIBILITY_VISUAL_FAST_PROBE_AFTER_MS = 80L
        private const val ACCESSIBILITY_VISUAL_NORMAL_PROBE_AFTER_MS = 650L
        private const val ACCESSIBILITY_VISUAL_PROBE_INTERVAL_MS = 120L
        private const val ACCESSIBILITY_VISUAL_MAX_PROBE_ATTEMPTS = 4
        private const val ACCESSIBILITY_VISUAL_FAST_VERIFY_MS = 220L
        private const val ACCESSIBILITY_VISUAL_NORMAL_VERIFY_MS = 650L
        private const val ACCESSIBILITY_VISUAL_MAX_TAP_ATTEMPTS = 2
        private const val ACCESSIBILITY_VISUAL_MAX_SCREENSHOT_FAILURES = 3

        private val RELEVANT_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )

        private val RESOLVER_PACKAGES = setOf(
            "android",
            "com.android.intentresolver",
            "com.google.android.permissioncontroller",
            "com.samsung.android.app.sharelive"
        )
    }


    /**
     * Required AccessibilityService callback.
     * Runtime recovery is handled by the service state machine.
     */
    override fun onInterrupt() {
        // Do not destroy the persisted run state here.
        // Android may interrupt Accessibility temporarily.
    }
}
