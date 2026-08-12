#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import sys

ROOT = Path.cwd()
F = {
 "build": ROOT/"app/build.gradle.kts",
 "policy": ROOT/"app/src/main/java/com/althmany/groupmanager/domain/AutomationPolicy.kt",
 "policy_test": ROOT/"app/src/test/java/com/althmany/groupmanager/domain/AutomationPolicyTest.kt",
 "shizuku_runtime": ROOT/"app/src/main/java/com/althmany/groupmanager/domain/ShizukuRuntimePolicy.kt",
 "shizuku_fast": ROOT/"app/src/main/java/com/althmany/groupmanager/domain/ShizukuFastUiPolicy.kt",
 "foreground": ROOT/"app/src/main/java/com/althmany/groupmanager/domain/ForegroundTargetPolicy.kt",
 "shizuku": ROOT/"app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt",
 "accessibility": ROOT/"app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt",
 "document_io": ROOT/"app/src/main/java/com/althmany/groupmanager/util/DocumentIO.kt",
 "result_store": ROOT/"app/src/main/java/com/althmany/groupmanager/util/GroupJoinerResultStore.kt",
 "main": ROOT/"app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt",
 "validator": ROOT/"scripts/validate_source.py",
 "pure": ROOT/"scripts/PureKotlinRegressionMain.kt",
}
def die(m):
 print("ERROR:", m, file=sys.stderr); raise SystemExit(1)
def rd(path):
 if not path.exists(): die(f"missing {path.relative_to(ROOT)}")
 return path.read_text(encoding="utf-8")
def wr(path,text): path.write_text(text,encoding="utf-8")
def rep(path,old,new,label):
 s=rd(path)
 if new in s: print("OK already:", label); return
 c=s.count(old)
 if c!=1: die(f"{label}: anchor count={c} in {path.relative_to(ROOT)}")
 wr(path,s.replace(old,new,1)); print("PATCHED:",label)
def rep_any(path,olds,new,label):
 s=rd(path)
 if new in s: print("OK already:",label); return
 matches=[o for o in olds if s.count(o)==1]
 if len(matches)!=1: die(f"{label}: compatible anchor count={len(matches)}")
 wr(path,s.replace(matches[0],new,1)); print("PATCHED:",label)

# Version
rep(F["build"],"versionCode = 303","versionCode = 304","versionCode 304")
rep(F["build"],'versionName = "3.0.3"','versionName = "3.0.4"',"versionName 3.0.4")

# Full-session run, no artificial 1000-link stop.
rep(F["policy"],
    "processes at most one thousand links per explicit run. Remaining links stay queued and\n * require another explicit user start.",
    "continues through the current explicit session up to the one-million-link session cap.\n * Progress remains disk-backed so interruptions can resume from the saved current link.",
    "full-session run documentation")
rep(F["policy"],"const val BATCH_SIZE = 1_000","const val BATCH_SIZE = MAX_LINKS_PER_SESSION","remove 1000-link run stop")
rep(F["policy_test"],
'''        assertEquals(1000, AutomationPolicy.BATCH_SIZE)
        assertEquals(1_000, AutomationPolicy.MAX_BATCHES_PER_SESSION)''',
'''        assertEquals(AutomationPolicy.MAX_LINKS_PER_SESSION, AutomationPolicy.BATCH_SIZE)
        assertEquals(1, AutomationPolicy.MAX_BATCHES_PER_SESSION)''',
"AutomationPolicyTest full-session run")
rep(F["pure"],
'''    expect("explicit run cap", 1000, AutomationPolicy.BATCH_SIZE)
    expect("explicit-run windows per full queue", 1_000, AutomationPolicy.MAX_BATCHES_PER_SESSION)''',
'''    expect("explicit run follows session cap", 1_000_000, AutomationPolicy.BATCH_SIZE)
    expect("one explicit-run window per full queue", 1, AutomationPolicy.MAX_BATCHES_PER_SESSION)''',
"Pure Kotlin full-session regression")

# Safe scrolling uses a different geometry policy than tapping.
rep(F["shizuku_runtime"],
'''    fun actionConsensusScans(
''',
'''    fun isSafeSwipeBounds(
        bounds: ShizukuBounds,
        displayWidth: Int,
        displayHeight: Int
    ): Boolean {
        if (!bounds.valid || displayWidth <= 0 || displayHeight <= 0) return false
        if (bounds.left < 0 || bounds.top < 0 ||
            bounds.right > displayWidth || bounds.bottom > displayHeight
        ) return false
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width < 48 || height < 120) return false
        return bounds.centerX in 1 until displayWidth &&
            bounds.centerY in 1 until displayHeight
    }

    fun actionConsensusScans(
''',"Shizuku safe swipe bounds")
for a,b,l in [
 ("const val GESTURE_DURATION_MS = 16L","const val GESTURE_DURATION_MS = 72L","Shizuku swipe duration"),
 ("const val BACK_SETTLE_MS = 0L","const val BACK_SETTLE_MS = 18L","Shizuku Back settle"),
 ("const val TERMINAL_ESCAPE_SETTLE_MS = 32L","const val TERMINAL_ESCAPE_SETTLE_MS = 64L","Shizuku X settle"),
 ("const val NORMAL_EXIT_SETTLE_MS = 18L","const val NORMAL_EXIT_SETTLE_MS = 36L","Shizuku exit settle")]:
 rep(F["shizuku_fast"],a,b,l)

rep(F["foreground"],"const val OUTSIDE_TARGET_CONFIRM_MS = 320L","const val OUTSIDE_TARGET_CONFIRM_MS = 140L","Accessibility exit confirm")
rep(F["foreground"],"const val RECENT_TARGET_GRACE_MS = 220L","const val RECENT_TARGET_GRACE_MS = 120L","Accessibility exit grace")

# Shizuku remembers whether this link was actually foreground.
rep(F["shizuku"],
'''    private var currentLaunchEventBaseline = 0L
    private var currentLaunchSawTargetEvent = false
''',
'''    private var currentLaunchEventBaseline = 0L
    private var currentLaunchSawTargetEvent = false
    private var currentLaunchSawTargetForeground = false
''',"Shizuku foreground-history flag")

old = """            if (!isTargetForeground(targetPackage)) {
                if (pauseAfterStableForegroundLoss(current, targetPackage)) {
                    updateNotification("Paused: return to the selected WhatsApp to continue")
                    delay(PAUSED_POLL_MS)
                    continue
                }
                val now = System.currentTimeMillis()
                if (foregroundWaitStartedAt == 0L) foregroundWaitStartedAt = now
                val waitAge = now - foregroundWaitStartedAt
                prefs.transitionAutomation(
                    AutomationStage.WAITING_FOR_WHATSAPP,
                    "Continuity engine is reacquiring the selected WhatsApp window"
                )

                if (waitAge >= ShizukuContinuityPolicy.FOREGROUND_REOPEN_AFTER_MS &&
                    foregroundRecoveryAttempts < ShizukuContinuityPolicy.MAX_FOREGROUND_REOPEN_ATTEMPTS
                ) {
                    foregroundRecoveryAttempts += 1
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_FOREGROUND_RECOVER",
                        "attempt=$foregroundRecoveryAttempts; waitedMs=$waitAge; reopening exact-user deep link"
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
                        "Selected WhatsApp window could not be reacquired after bounded exact-user recovery; continuity advanced to the next link without guessing a click"
                    )
                    continue
                }
                delay(runtimeSpeed().stableScanMs)
                continue
            }
            foregroundWaitStartedAt = 0L
            foregroundRecoveryAttempts = 0
"""
new = """            if (!isTargetForeground(targetPackage)) {
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
"""
rep(F["shizuku"],old,new,"Shizuku pause instead of force-reopen")

rep(F["shizuku"],
"""                    fastUiFailureCount = 0
                    consecutiveDumpFailures = 0
""",
"""                    fastUiFailureCount = 0
                    fastUiSessionRecoveryAttempts = 0
                    consecutiveDumpFailures = 0
""","Shizuku self-heal budget reset after healthy tree")

rep(F["shizuku"],
"""        outsideTargetCandidateStartedAtElapsed = 0L
        lastOutsideTargetProbeAtElapsed = 0L
        currentLaunchEventBaseline = lastFastEventSequence
        currentLaunchSawTargetEvent = false
        fastUiNoRootStartedAtElapsed = 0L
""",
"""        outsideTargetCandidateStartedAtElapsed = 0L
        lastOutsideTargetProbeAtElapsed = 0L
        currentLaunchEventBaseline = lastFastEventSequence
        currentLaunchSawTargetEvent = false
        currentLaunchSawTargetForeground = false
        profileCompatCommandProbes = 0
        fastUiSessionRecoveryAttempts = 0
        fastUiNoRootStartedAtElapsed = 0L
""","Shizuku per-launch liveness reset")

rep(F["shizuku"],
"""        if (currentLaunchElapsed <= 0L || launchAge < USER_EXIT_LAUNCH_GRACE_MS) {
            outsideTargetCandidateStartedAtElapsed = 0L
            return false
        }
        if (now - lastOutsideTargetProbeAtElapsed < USER_EXIT_PROBE_INTERVAL_MS) return false
""",
"""        if (!currentLaunchSawTargetForeground &&
            (currentLaunchElapsed <= 0L || launchAge < USER_EXIT_LAUNCH_GRACE_MS)
        ) {
            outsideTargetCandidateStartedAtElapsed = 0L
            return false
        }
        if (now - lastOutsideTargetProbeAtElapsed < USER_EXIT_PROBE_INTERVAL_MS) return false
""","Shizuku immediate user-exit detection after target seen")

rep(F["shizuku"],
"""        currentLaunchElapsed = now
        outsideTargetCandidateStartedAtElapsed = 0L
        armFastBurst(FAST_OPEN_BURST_MS)
""",
"""        currentLaunchElapsed = now
        currentLaunchSawTargetForeground = true
        outsideTargetCandidateStartedAtElapsed = 0L
        armFastBurst(FAST_OPEN_BURST_MS)
""","Shizuku same-target auto-resume state")

rep(F["shizuku"],
"""        lastOutsideTargetProbeAtElapsed = 0L
        fastUiNoRootStartedAtElapsed = 0L
        fastUiTargetHiddenStartedAtElapsed = 0L
        profileCompatCommandProbes = 0
""",
"""        lastOutsideTargetProbeAtElapsed = 0L
        fastUiSessionRecoveryAttempts = 0
        fastUiNoRootStartedAtElapsed = 0L
        fastUiTargetHiddenStartedAtElapsed = 0L
        profileCompatCommandProbes = 0
""","Shizuku per-link fast session reset")

rep(F["shizuku"],
"""        currentLaunchEventBaseline = lastFastEventSequence
        currentLaunchSawTargetEvent = false
        visualProbeLinkId = -1L
""",
"""        currentLaunchEventBaseline = lastFastEventSequence
        currentLaunchSawTargetEvent = false
        currentLaunchSawTargetForeground = false
        visualProbeLinkId = -1L
""","Shizuku per-link foreground flag reset")

for a,b,l in [
 ("private const val USER_EXIT_CONFIRM_MS = 420L","private const val USER_EXIT_CONFIRM_MS = 140L","Shizuku exit confirm"),
 ("private const val USER_EXIT_PROBE_INTERVAL_MS = 180L","private const val USER_EXIT_PROBE_INTERVAL_MS = 70L","Shizuku exit probe"),
 ("private const val USER_RETURN_PROBE_INTERVAL_MS = 450L","private const val USER_RETURN_PROBE_INTERVAL_MS = 180L","Shizuku return probe"),
 ("private const val RESULT_MIRROR_SYNC_EVERY = 10","private const val RESULT_MIRROR_SYNC_EVERY = 1000","Shizuku mirror cadence")]:
 rep(F["shizuku"],a,b,l)
rep(F["shizuku"],"click=68ms; gesture=18ms; result=88ms; next=0ms",
    "click=60ms; gesture=72ms; result=72ms; next=0ms","Shizuku preset diagnostic")

# X/Close: terminal sheets + mirrored RTL/LTR corners.
old = """        // Some Work-profile WhatsApp sheets expose the X as an unlabeled clickable image.
        // Geometry fallback is allowed only on a verified invitation surface.
        if (!snapshot.inviteContext || displayWidth <= 0 || displayHeight <= 0) return null
        return snapshot.nodes.asSequence()
            .filter { node ->
                node.enabled && node.clickable && node.bounds?.valid == true &&
                    node.belongsTo(targetPackage)
            }
            .filter { node ->
                val b = node.bounds ?: return@filter false
                val w = b.right - b.left
                val h = b.bottom - b.top
                val nearTopRight = b.centerX >= displayWidth * 72 / 100 &&
                    b.centerY <= displayHeight * 32 / 100
                val compact = w in 1..(displayWidth * 18 / 100).coerceAtLeast(1) &&
                    h in 1..(displayHeight * 12 / 100).coerceAtLeast(1)
                val roughlySquare = w <= h * 2 && h <= w * 2
                nearTopRight && compact && roughlySquare
            }
            .minByOrNull { node ->
                val b = node.bounds!!
                (displayWidth - b.centerX).coerceAtLeast(0) + b.centerY.coerceAtLeast(0)
            }
"""
new = """        // Work/Secure/RTL WhatsApp can expose the X as an unlabeled ImageView and mirror it
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
                val compact = w in 1..(displayWidth * 18 / 100).coerceAtLeast(1) &&
                    h in 1..(displayHeight * 12 / 100).coerceAtLeast(1)
                val roughlySquare = w <= h * 2 && h <= w * 2
                nearTopCorner && compact && roughlySquare
            }
            .minByOrNull { node ->
                val b = node.bounds!!
                minOf(
                    b.centerX.coerceAtLeast(0),
                    (displayWidth - b.centerX).coerceAtLeast(0)
                ) + b.centerY.coerceAtLeast(0)
            }
"""
rep(F["shizuku"],old,new,"Shizuku RTL/LTR terminal X")

old = """    private suspend fun swipeForward(
        bounds: ShizukuBounds,
        targetPackage: String,
        current: GroupLink
    ): Boolean {
        if (!ShizukuRuntimePolicy.isSafeTapBounds(bounds, displayWidth, displayHeight)) return false
        if (!isTargetForeground(targetPackage)) return false
        waitInputCooldown()
        val x = bounds.centerX
        val startY = bounds.top + ((bounds.bottom - bounds.top) * 78 / 100)
        val endY = bounds.top + ((bounds.bottom - bounds.top) * 28 / 100)
        if (startY <= endY) return false
        val persistent = fastUiMode == FastUiMode.ACTIVE &&
            ShizukuBridge.fastSwipe(this, x, startY, x, endY, ShizukuFastUiPolicy.GESTURE_DURATION_MS.toInt())
        val shell = if (!persistent) {
            ShizukuBridge.execute(this, "input swipe $x $startY $x $endY 180", 3_000)
        } else null
"""
new = """    private suspend fun swipeForward(
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
        val persistent = fastUiMode == FastUiMode.ACTIVE &&
            ShizukuBridge.fastSwipe(this, x, startY, x, endY, ShizukuFastUiPolicy.GESTURE_DURATION_MS.toInt())
        val shell = if (!persistent) {
            ShizukuBridge.execute(this, "input swipe $x $startY $x $endY 160", 3_000)
        } else null
"""
rep(F["shizuku"],old,new,"Shizuku reliable large-container swipe")

# Accessibility: real gesture fallback for scrolls that lie about ACTION_SCROLL_FORWARD support.
rep(F["accessibility"],"private const val RESULT_MIRROR_SYNC_EVERY = 10",
    "private const val RESULT_MIRROR_SYNC_EVERY = 1000","Accessibility mirror cadence")

old = """        if (pending == null && screen.inviteContext && !screen.loading && screen.action == null &&
            screen.scrollNode != null && inviteScrollAttempts < MAX_INVITE_SCROLL_ATTEMPTS
        ) {
            val scrolled = withContext(Dispatchers.Main.immediate) {
                screen.scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            if (scrolled) {
                inviteScrollAttempts += 1
                runtimeDiagnostic(current, "FAST_SCROLL", "Scrolled invitation surface; attempt=$inviteScrollAttempts")
                app.preferences.transitionAutomation(
                    AutomationStage.LOOKING_FOR_JOIN,
                    "Invitation controls were below the fold; fast semantic scroll completed",
                    resetRetries = false
                )
                lastScanAt = 0L
                requestScan()
                return
            }
        }
"""
new = """        val inviteScrollNode = screen.scrollNode
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
"""
rep(F["accessibility"],old,new,"Accessibility invite scroll fallback")

old = """        val canScroll = CommunityTraversalPolicy.canScroll(prefs.communityScrollAttempts)
        val scrollNode = screen.communityScrollNode
        if (canScroll && scrollNode != null) {
            val scrolled = withContext(Dispatchers.Main.immediate) {
                scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            if (scrolled) {
                prefs.communityScrollAttempts += 1
                communityEmptyStableScans = 0
                runtimeDiagnostic(
                    current,
                    "COMMUNITY_SCROLL",
                    "Searching for more subgroup rows; scroll=${prefs.communityScrollAttempts}"
                )
            } else {
                // ACTION_SCROLL_FORWARD=false is a strong end-of-list signal.
                prefs.communityScrollAttempts = CommunityTraversalPolicy.MAX_SCROLL_ATTEMPTS
                communityEmptyStableScans += 1
            }
            requestScan()
            return
        }
"""
new = """        val canScroll = CommunityTraversalPolicy.canScroll(prefs.communityScrollAttempts)
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
"""
rep(F["accessibility"],old,new,"Accessibility community scroll fallback")

helper = """    private fun dispatchReliableScrollGesture(node: AccessibilityNodeInfo): Boolean {
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
        val duration = maxOf(72L, runtimeSpeed().gestureDurationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

"""
rep(F["accessibility"],"""    private fun clickNodeParentOrGesture(
""",helper+"""    private fun clickNodeParentOrGesture(
""","Accessibility reliable scroll gesture helper")

old = """        if (inviteContext && closeNode == null) {
            closeNode = nodes.asSequence()
                .map { it.node }
                .filter { it.isVisibleToUser && it.isEnabled }
                .filter(::looksLikeTopRightCloseCandidate)
                .maxByOrNull(::visualCloseCandidateScore)
        }
"""
new = """        if ((inviteContext || terminalEvidenceKinds.isNotEmpty()) &&
            !conversationSurface && closeNode == null
        ) {
            closeNode = nodes.asSequence()
                .map { it.node }
                .filter { it.isVisibleToUser && it.isEnabled }
                .filter(::looksLikeTopRightCloseCandidate)
                .maxByOrNull(::visualCloseCandidateScore)
        }
"""
rep(F["accessibility"],old,new,"Accessibility terminal X geometry")

old = """    private fun looksLikeTopRightCloseCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        if (!node.isClickable && node.parent?.isClickable != true) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val maxSize = dpToPx(112)
        val topBand = minOf((height * 0.18f).toInt(), dpToPx(210))
        return bounds.centerX() >= (width * 0.70f).toInt() &&
            bounds.centerY() <= topBand &&
            bounds.width() in dpToPx(18)..maxSize &&
            bounds.height() in dpToPx(18)..maxSize
    }

    private fun visualCloseCandidateScore(node: AccessibilityNodeInfo): Int {
        val bounds = Rect().also(node::getBoundsInScreen)
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var score = 0
        if (node.isClickable) score += 50
        if (node.parent?.isClickable == true) score += 25
        if (node.className?.toString()?.contains("Image", ignoreCase = true) == true) score += 20
        score += ((bounds.centerX().toFloat() / width) * 25f).toInt()
        score += (30 - (bounds.centerY() / dpToPx(8))).coerceAtLeast(0)
        return score
    }
"""
new = """    private fun looksLikeTopRightCloseCandidate(node: AccessibilityNodeInfo): Boolean {
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
"""
rep(F["accessibility"],old,new,"Accessibility RTL/LTR close candidate")

# Excel-aware lightweight DocumentIO.
doc = r'''package com.althmany.groupmanager.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.LinkedHashSet
import java.util.zip.ZipInputStream

object DocumentIO {
    private val WHATSAPP_INVITE_REGEX = Regex(
        "https?://(?:chat\\.)?whatsapp\\.com/[A-Za-z0-9_-]+",
        RegexOption.IGNORE_CASE
    )
    private const val MAX_LEGACY_XLS_BYTES = 12 * 1024 * 1024
    private const val MAX_XLSX_SCAN_CHARS = 64L * 1024L * 1024L
    private const val STREAM_BUFFER_CHARS = 8_192
    private const val REGEX_CARRY_CHARS = 384

    fun readText(resolver: ContentResolver, uri: Uri, maxChars: Int): Result<String> = runCatching {
        require(maxChars > 0)
        val name = displayName(resolver, uri).lowercase()
        val mime = resolver.getType(uri)?.lowercase().orEmpty()
        when {
            isOpenXmlSpreadsheet(name, mime) -> readOpenXmlSpreadsheetLinks(resolver, uri, maxChars)
            isLegacySpreadsheet(name, mime) -> readLegacySpreadsheetLinks(resolver, uri, maxChars)
            else -> readPlainText(resolver, uri, maxChars)
        }.take(maxChars)
    }

    fun writeText(resolver: ContentResolver, uri: Uri, text: String): Result<Unit> = runCatching {
        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: error("Unable to create document")
    }

    private fun isOpenXmlSpreadsheet(name: String, mime: String): Boolean =
        name.endsWith(".xlsx") || name.endsWith(".xlsm") ||
            mime.contains("spreadsheetml") || mime.contains("macroenabled")

    private fun isLegacySpreadsheet(name: String, mime: String): Boolean =
        name.endsWith(".xls") || mime == "application/vnd.ms-excel"

    private fun displayName(resolver: ContentResolver, uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun readPlainText(resolver: ContentResolver, uri: Uri, maxChars: Int): String {
        val raw = resolver.openInputStream(uri) ?: error("Unable to open document")
        BufferedInputStream(raw, 16 * 1024).use { stream ->
            stream.mark(4)
            val prefix = ByteArray(3)
            val count = stream.read(prefix)
            stream.reset()
            val (charset, bomBytes) = detectCharset(prefix, count)
            var skipped = 0
            while (skipped < bomBytes && stream.read() >= 0) skipped++
            val reader = InputStreamReader(stream, charset)
            val output = StringBuilder(minOf(maxChars, 64 * 1024))
            val buffer = CharArray(STREAM_BUFFER_CHARS)
            while (output.length < maxChars) {
                val n = reader.read(buffer, 0, minOf(buffer.size, maxChars - output.length))
                if (n < 0) break
                output.append(buffer, 0, n)
            }
            return output.toString()
        }
    }

    private fun readOpenXmlSpreadsheetLinks(
        resolver: ContentResolver,
        uri: Uri,
        maxChars: Int
    ): String {
        val found = LinkedHashSet<String>()
        var outputChars = 0
        var scannedChars = 0L
        val raw = resolver.openInputStream(uri) ?: error("Unable to open spreadsheet")
        ZipInputStream(BufferedInputStream(raw, 32 * 1024)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && scannedChars < MAX_XLSX_SCAN_CHARS && outputChars < maxChars) {
                val name = entry.name.lowercase()
                val searchable = !entry.isDirectory &&
                    (name.endsWith(".xml") || name.endsWith(".rels") || name.endsWith(".txt"))
                if (searchable) {
                    val reader = InputStreamReader(zip, Charsets.UTF_8)
                    val buffer = CharArray(STREAM_BUFFER_CHARS)
                    var carry = ""
                    while (scannedChars < MAX_XLSX_SCAN_CHARS && outputChars < maxChars) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        scannedChars += n
                        val chunk = carry + String(buffer, 0, n)
                        outputChars += collectInviteLinks(chunk, found, maxChars - outputChars)
                        carry = chunk.takeLast(REGEX_CARRY_CHARS)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return found.joinToString("\n").take(maxChars)
    }

    private fun readLegacySpreadsheetLinks(
        resolver: ContentResolver,
        uri: Uri,
        maxChars: Int
    ): String {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(16 * 1024)
            var remaining = MAX_LEGACY_XLS_BYTES
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (n < 0) break
                output.write(buffer, 0, n)
                remaining -= n
            }
            output.toByteArray()
        } ?: error("Unable to open spreadsheet")

        val found = LinkedHashSet<String>()
        var outputChars = 0
        for (charset in listOf(Charsets.ISO_8859_1, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            if (outputChars >= maxChars) break
            outputChars += collectInviteLinks(bytes.toString(charset), found, maxChars - outputChars)
        }
        return found.joinToString("\n").take(maxChars)
    }

    private fun collectInviteLinks(
        text: String,
        output: LinkedHashSet<String>,
        remainingChars: Int
    ): Int {
        if (remainingChars <= 0) return 0
        var added = 0
        for (match in WHATSAPP_INVITE_REGEX.findAll(text)) {
            val link = match.value
            if (link.length + added > remainingChars) break
            if (output.add(link)) added += link.length + 1
        }
        return added
    }

    private fun detectCharset(prefix: ByteArray, count: Int): Pair<Charset, Int> = when {
        count >= 3 && prefix[0] == 0xEF.toByte() &&
            prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        count >= 2 && prefix[0] == 0xFF.toByte() &&
            prefix[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        count >= 2 && prefix[0] == 0xFE.toByte() &&
            prefix[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> Charsets.UTF_8 to 0
    }
}
'''
s=rd(F["document_io"])
if "MAX_XLSX_SCAN_CHARS" in s and "ZipInputStream" in s:
 print("OK already: Excel-aware DocumentIO")
else:
 if "object DocumentIO" not in s or "detectCharset" not in s: die("unexpected DocumentIO structure")
 wr(F["document_io"],doc); print("PATCHED: Excel-aware DocumentIO")

rep(F["main"],
"""            importDocumentsLauncher.launch(arrayOf("text/plain", "text/csv", "text/*"))
""",
"""            importDocumentsLauncher.launch(
                arrayOf(
                    "text/plain",
                    "text/csv",
                    "text/*",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "application/vnd.ms-excel.sheet.macroEnabled.12",
                    "application/octet-stream"
                )
            )
""","Excel MIME picker")

# Low-allocation result file generation; services now invoke it every 1000 links + terminal.
store = r'''package com.althmany.groupmanager.util

import android.content.Context
import com.althmany.groupmanager.model.LinkStatus
import com.althmany.groupmanager.model.SessionSnapshot
import java.io.File

/** Mirrors the observable Group Joiner output model: Joined / Fail / Left. */
object GroupJoinerResultStore {
    private const val ROOT = "Group Joiner"

    fun sync(context: Context, snapshot: SessionSnapshot?) {
        if (snapshot == null) return
        runCatching {
            val root = File(context.filesDir, ROOT).apply { mkdirs() }
            val joinedDir = File(root, "Joined").apply { mkdirs() }
            val failDir = File(root, "Fail").apply { mkdirs() }
            val leftDir = File(root, "Left").apply { mkdirs() }
            val name = "${snapshot.sessionId}.txt"

            File(joinedDir, name).bufferedWriter(Charsets.UTF_8).use { joined ->
                File(failDir, name).bufferedWriter(Charsets.UTF_8).use { failed ->
                    File(leftDir, name).bufferedWriter(Charsets.UTF_8).use { left ->
                        var joinedFirst = true
                        var failedFirst = true
                        var leftFirst = true
                        snapshot.links.forEach { link ->
                            when (link.status) {
                                LinkStatus.JOINED, LinkStatus.REQUESTED -> {
                                    if (!joinedFirst) joined.newLine()
                                    joined.write(link.url)
                                    joinedFirst = false
                                }
                                LinkStatus.FAILED, LinkStatus.SKIPPED -> {
                                    if (!failedFirst) failed.newLine()
                                    failed.write(link.url)
                                    failedFirst = false
                                }
                                LinkStatus.PENDING, LinkStatus.OPENED -> {
                                    if (!leftFirst) left.newLine()
                                    left.write(link.url)
                                    leftFirst = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
'''
s=rd(F["result_store"])
if "bufferedWriter(Charsets.UTF_8)" in s and "snapshot.links.forEach" in s:
 print("OK already: streaming result mirror")
else:
 if "snapshot.links.filter" not in s: die("unexpected GroupJoinerResultStore structure")
 wr(F["result_store"],store); print("PATCHED: streaming result mirror")

# Validator alignment.
rep_any(F["validator"],
 ('"versionCode 303": "versionCode = 303" in build,',
  '"versionCode 304": "versionCode = 304" in build,'),
 '"versionCode 304": "versionCode = 304" in build,',"validator versionCode")
rep_any(F["validator"],
 ('"versionName 3.0.3": \'versionName = "3.0.3"\' in build,',
  '"versionName 3.0.4": \'versionName = "3.0.4"\' in build,'),
 '"versionName 3.0.4": \'versionName = "3.0.4"\' in build,',"validator versionName")
rep_any(F["validator"],
 ('"1000-link explicit run window": "BATCH_SIZE = 1_000" in policy,',
  '"full-session explicit run window": "BATCH_SIZE = MAX_LINKS_PER_SESSION" in policy,'),
 '"full-session explicit run window": "BATCH_SIZE = MAX_LINKS_PER_SESSION" in policy,',"validator run window")
rep_any(F["validator"],
 ('"continuous mode default": "getBoolean(KEY_AUTO_PAUSE_OUTSIDE_WHATSAPP, false)" in preferences_source,',
  '"auto-pause outside WhatsApp default": "getBoolean(KEY_AUTO_PAUSE_OUTSIDE_WHATSAPP, true)" in preferences_source,'),
 '"auto-pause outside WhatsApp default": "getBoolean(KEY_AUTO_PAUSE_OUTSIDE_WHATSAPP, true)" in preferences_source,',
 "validator pause default")

v=rd(F["validator"])
anchor='''    "3.0.3 repeat-run persistent UI reset": all(token in shizuku_service for token in [
        "SHIZUKU_FAST_UI_RUN_RESET", "SHIZUKU_FAST_UI_SELF_HEAL", "fastResetUiAutomation"
    ]) and "resetForNewRun" in shizuku_persistent and "fastResetUiAutomation" in shizuku_bridge_source and "fastResetUiAutomation" in shizuku_aidl_source,
'''
extra=anchor+'''    "3.0.4 leave WhatsApp pauses without force reopen": "currentLaunchSawTargetForeground" in shizuku_service and "shouldAutoPauseForUserExit" in shizuku_service and "USER_EXIT_CONFIRM_MS = 140L" in shizuku_service,
    "3.0.4 reliable scroll gestures": "isSafeSwipeBounds" in shizuku_policy and "GESTURE_DURATION_MS = 72L" in shizuku_fast_policy and "dispatchReliableScrollGesture" in service,
    "3.0.4 Excel picker support": "spreadsheetml.sheet" in main_activity and "application/vnd.ms-excel" in main_activity,
'''
if '"3.0.4 leave WhatsApp pauses without force reopen"' not in v:
 if anchor not in v: die("validator 3.0.3 anchor missing")
 wr(F["validator"],v.replace(anchor,extra,1)); print("PATCHED: validator 3.0.4 guards")
else: print("OK already: validator 3.0.4 guards")

print()
print("AL-thmany 3.0.4 STABILITY + EXCEL + SMOOTH NAVIGATION FIX APPLIED")
print("Next: python3 scripts/validate_source.py")
