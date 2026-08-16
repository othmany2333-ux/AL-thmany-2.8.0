#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from pathlib import Path
import re

ROOT = Path.cwd()
if not (ROOT / "app").is_dir():
    raise SystemExit("ERROR: run from repository root")

changed = []

def p(rel):
    q = ROOT / rel
    if not q.exists():
        raise SystemExit(f"ERROR: missing required file: {rel}")
    return q

def read(rel):
    return p(rel).read_text(encoding="utf-8")

def write(rel, text):
    q = p(rel)
    old = q.read_text(encoding="utf-8")
    if old != text:
        q.write_text(text, encoding="utf-8")
        changed.append(rel)

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"ERROR: anchor not found: {label}")
    return text.replace(old, new, 1)

# 1) Keep visible 3.5.0; raise versionCode so Android accepts install over 3.5.6.
gradle_rel = "app/build.gradle.kts"
g = read(gradle_rel)
if 'versionName = "3.5.0"' not in g:
    raise SystemExit(
        "ERROR: restore the 3.5.0 baseline first "
        "(f931638a45b18827255f26854ec1029e89224efd)"
    )
g = re.sub(r'(?m)^(\s*versionCode\s*=\s*)\d+\s*$', r'\g<1>357', g, count=1)
write(gradle_rel, g)

service_rel = "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"
s = read(service_rel)

# 2) Controlled-exit fields.
if "private var controlledExitLinkId" not in s:
    anchor = "    private var visualExpectedAction: AccessibilityJoinAction? = null\n"
    add = anchor + """    // Same-link proof that foreground loss was caused by our own verified X/Back.
    private var controlledExitLinkId = -1L
    private var controlledExitAtElapsed = 0L
    private var controlledExitReason = ""

"""
    if anchor not in s:
        raise SystemExit("ERROR: controlled-exit field anchor not found")
    s = s.replace(anchor, add, 1)

# 3) Helpers.
if "private suspend fun preferProfileSafeShellInput(" not in s:
    anchor = "    private suspend fun pressResultBack(\n"
    helpers = """    private fun markAutomationControlledExit(current: GroupLink, reason: String) {
        controlledExitLinkId = current.id
        controlledExitAtElapsed = SystemClock.elapsedRealtime()
        controlledExitReason = reason
    }

    private fun automationControlledExitReason(current: GroupLink): String? {
        if (controlledExitLinkId != current.id || controlledExitAtElapsed <= 0L) return null
        val age = (SystemClock.elapsedRealtime() - controlledExitAtElapsed).coerceAtLeast(0L)
        if (age > CONTROLLED_EXIT_HANDOFF_GRACE_MS) return null
        return controlledExitReason.ifBlank { "AUTOMATION_EXIT" }
    }

    /*
     * Persistent UiAutomation is fastest on owner user 0. On Work/Dual/Secure
     * and some OEM secondary users it can remain scoped to owner UI even while
     * the exact uNN WhatsApp activity is visibly resumed.
     */
    private suspend fun preferProfileSafeShellInput(targetPackage: String): Boolean {
        val targetUser = cachedAndroidUserId ?: resolveAndroidUserId(targetPackage) ?: return true
        val hostUser = Process.myUid() / ANDROID_UID_USER_RANGE
        return targetUser != 0 || targetUser != hostUser
    }

"""
    if anchor not in s:
        raise SystemExit("ERROR: helper anchor not found")
    s = s.replace(anchor, helpers + anchor, 1)

# 4) pressResultBack shell-first for secondary/cross-user profiles.
if 'markAutomationControlledExit(current, purpose)\n        runtimeDiagnostic(\n            current,\n            "SHIZUKU_RESULT_BACK"' not in s:
    old = """        waitInputCooldown()
        val persistent = ShizukuBridge.fastBack(this)
        val shell = if (!persistent) {
            ShizukuBridge.execute(this, "input keyevent 4", 2_500)
        } else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        runtimeDiagnostic(
            current,
            "SHIZUKU_RESULT_BACK",
"""
    new = """        waitInputCooldown()
        val profileShellFirst = preferProfileSafeShellInput(targetPackage)
        var persistent = false
        var shell: ShizukuBridge.ShellResult? = null
        if (profileShellFirst) {
            shell = ShizukuBridge.execute(this, "input keyevent 4", 2_500)
            if (shell?.success != true) persistent = ShizukuBridge.fastBack(this)
        } else {
            persistent = ShizukuBridge.fastBack(this)
            if (!persistent) shell = ShizukuBridge.execute(this, "input keyevent 4", 2_500)
        }
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        if (success) markAutomationControlledExit(current, purpose)
        runtimeDiagnostic(
            current,
            "SHIZUKU_RESULT_BACK",
"""
    s = replace_once(s, old, new, "pressResultBack")

# 5) dismissVisualActionSurface.
if 'markAutomationControlledExit(current, purpose)\n        runtimeDiagnostic(\n            current,\n            "SHIZUKU_VISUAL_SURFACE_CLOSE"' not in s:
    old = """        waitInputCooldown()
        val persistent = ShizukuBridge.fastBack(this)
        val shell = if (!persistent) ShizukuBridge.execute(this, "input keyevent 4", 2_500) else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        runtimeDiagnostic(
            current,
            "SHIZUKU_VISUAL_SURFACE_CLOSE",
"""
    new = """        waitInputCooldown()
        val profileShellFirst = preferProfileSafeShellInput(targetPackage)
        var persistent = false
        var shell: ShizukuBridge.ShellResult? = null
        if (profileShellFirst) {
            shell = ShizukuBridge.execute(this, "input keyevent 4", 2_500)
            if (shell?.success != true) persistent = ShizukuBridge.fastBack(this)
        } else {
            persistent = ShizukuBridge.fastBack(this)
            if (!persistent) shell = ShizukuBridge.execute(this, "input keyevent 4", 2_500)
        }
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        if (success) markAutomationControlledExit(current, purpose)
        runtimeDiagnostic(
            current,
            "SHIZUKU_VISUAL_SURFACE_CLOSE",
"""
    s = replace_once(s, old, new, "dismissVisualActionSurface")

# 6) tapNode: profile-safe shell tap first.
if "SHIZUKU_PROFILE_SAFE_TAP" not in s:
    old = """        var nodeClick = false
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
"""
    new = """        val profileShellFirst = preferProfileSafeShellInput(targetPackage)
        var nodeClick = false
        var persistentTap = false
        var shell: ShizukuBridge.ShellResult? = null

        if (profileShellFirst) {
            shell = ShizukuBridge.execute(
                this,
                "input tap ${bounds.centerX} ${bounds.centerY}",
                2_500
            )
            runtimeDiagnostic(
                current,
                "SHIZUKU_PROFILE_SAFE_TAP",
                "$purpose; user=${cachedAndroidUserId ?: -1}; " +
                    "x=${bounds.centerX}; y=${bounds.centerY}; exit=${shell?.exitCode ?: -1}"
            )
            if (shell?.success != true && fastUiMode == FastUiMode.ACTIVE) {
                persistentTap = ShizukuBridge.fastTap(this, bounds.centerX, bounds.centerY)
                if (!persistentTap) {
                    nodeClick = ShizukuBridge.fastClickNode(
                        this,
                        targetPackage,
                        bounds.centerX,
                        bounds.centerY
                    )
                }
            }
        } else {
            if (fastUiMode == FastUiMode.ACTIVE) {
                if (directTouchFirst) {
                    persistentTap = ShizukuBridge.fastTap(this, bounds.centerX, bounds.centerY)
                    if (!persistentTap) {
                        nodeClick = ShizukuBridge.fastClickNode(
                            this,
                            targetPackage,
                            bounds.centerX,
                            bounds.centerY
                        )
                    }
                } else {
                    nodeClick = ShizukuBridge.fastClickNode(
                        this,
                        targetPackage,
                        bounds.centerX,
                        bounds.centerY
                    )
                    if (!nodeClick) {
                        persistentTap = ShizukuBridge.fastTap(
                            this,
                            bounds.centerX,
                            bounds.centerY
                        )
                    }
                }
            }
            if (!nodeClick && !persistentTap) {
                shell = ShizukuBridge.execute(
                    this,
                    "input tap ${bounds.centerX} ${bounds.centerY}",
                    2_500
                )
            }
        }

        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = nodeClick || persistentTap || shell?.success == true
"""
    s = replace_once(s, old, new, "tapNode")

# 7) swipeForward profile-safe shell first.
a = s.find("private suspend fun swipeForward")
b = s.find("private suspend fun sendBack", a)
if a >= 0 and "profileShellFirst" not in s[a:b]:
    old = """        val selectedGestureMs = runtimeSpeed().gestureDurationMs.coerceIn(8L, 72L)
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
"""
    new = """        val selectedGestureMs = runtimeSpeed().gestureDurationMs.coerceIn(8L, 72L)
        val profileShellFirst = preferProfileSafeShellInput(targetPackage)
        var persistent = false
        var shell: ShizukuBridge.ShellResult? = null
        val shellGestureMs = (selectedGestureMs * 4L).coerceIn(60L, 160L)

        if (profileShellFirst) {
            shell = ShizukuBridge.execute(
                this,
                "input swipe $x $startY $x $endY $shellGestureMs",
                3_000
            )
            if (shell?.success != true && fastUiMode == FastUiMode.ACTIVE) {
                persistent = ShizukuBridge.fastSwipe(
                    this, x, startY, x, endY, selectedGestureMs.toInt()
                )
            }
        } else {
            persistent = fastUiMode == FastUiMode.ACTIVE &&
                ShizukuBridge.fastSwipe(this, x, startY, x, endY, selectedGestureMs.toInt())
            if (!persistent) {
                shell = ShizukuBridge.execute(
                    this,
                    "input swipe $x $startY $x $endY $shellGestureMs",
                    3_000
                )
            }
        }
"""
    s = replace_once(s, old, new, "swipeForward")

# 8) sendBack profile-safe.
a = s.find("private suspend fun sendBack")
b = s.find("private suspend fun probeJoinedConversationActivityWithGrace", a)
if a >= 0 and "profileShellFirst" not in s[a:b]:
    old = """        waitInputCooldown()
        val persistent = fastUiMode == FastUiMode.ACTIVE && ShizukuBridge.fastBack(this)
        val shell = if (!persistent) ShizukuBridge.execute(this, "input keyevent 4", 2_500) else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
"""
    new = """        waitInputCooldown()
        val profileShellFirst = preferProfileSafeShellInput(targetPackage)
        var persistent = false
        var shell: ShizukuBridge.ShellResult? = null
        if (profileShellFirst) {
            shell = ShizukuBridge.execute(this, "input keyevent 4", 2_500)
            if (shell?.success != true && fastUiMode == FastUiMode.ACTIVE) {
                persistent = ShizukuBridge.fastBack(this)
            }
        } else {
            persistent = fastUiMode == FastUiMode.ACTIVE && ShizukuBridge.fastBack(this)
            if (!persistent) shell = ShizukuBridge.execute(this, "input keyevent 4", 2_500)
        }
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
"""
    s = replace_once(s, old, new, "sendBack")

# 9) X / safe cancel controlled-exit markers.
if 'markAutomationControlledExit(current, "RESULT_SAFE_CLOSE")' not in s:
    s = replace_once(
        s,
        """            if (closed) {
                delay(ShizukuFastUiPolicy.TERMINAL_ESCAPE_SETTLE_MS)
""",
        """            if (closed) {
                markAutomationControlledExit(current, "RESULT_SAFE_CLOSE")
                delay(ShizukuFastUiPolicy.TERMINAL_ESCAPE_SETTLE_MS)
""",
        "safe close"
    )

if 'markAutomationControlledExit(current, "RESULT_SAFE_CANCEL")' not in s:
    s = replace_once(
        s,
        """            if (dismissed) {
                delay(runtimeSpeed().postTapWaitMs.coerceIn(6L, 120L))
""",
        """            if (dismissed) {
                markAutomationControlledExit(current, "RESULT_SAFE_CANCEL")
                delay(runtimeSpeed().postTapWaitMs.coerceIn(6L, 120L))
""",
        "safe cancel"
    )

# 10) Cooldown does not count as real UI-dump failure.
if "No UI dump ran during cooldown" not in s:
    old = """            val remaining = (commandDumpSuppressedUntilElapsed - commandDumpNow).coerceAtLeast(1L)
            delay(minOf(COMMAND_DUMP_COOLDOWN_POLL_MS, remaining))
            return handleDumpFailure(
                current,
                targetPackage,
                "mode=COMMAND_COOLDOWN; command dump suppressed; remainingMs=$remaining",
                realCommandKill = false
            )
"""
    new = """            val remaining = (commandDumpSuppressedUntilElapsed - commandDumpNow).coerceAtLeast(1L)
            delay(minOf(COMMAND_DUMP_COOLDOWN_POLL_MS, remaining))
            // No UI dump ran during cooldown, so do not inflate consecutiveDumpFailures.
            return null
"""
    s = replace_once(s, old, new, "command cooldown")

# 11) Visual result: verified Join only; Request disappearance is not proof.
if "SHIZUKU_VISUAL_AMBIGUOUS_NO_BACK" not in s:
    old = """            val expected = visualExpectedAction ?: readPendingAction(current)
            val joinedConversation = probeJoinedConversationActivityWithGrace(targetPackage, current)
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
"""
    new = """            val expected = visualExpectedAction ?: readPendingAction(current)
            val joinedConversation = probeJoinedConversationActivityWithGrace(targetPackage, current)

            when {
                joinedConversation -> {
                    exitConversationBeforeDirectHandoff(targetPackage, current)
                    completeCurrent(
                        current,
                        LinkStatus.JOINED,
                        LinkResultCode.JOIN_ACTION_COMPLETED,
                        "Wide WhatsApp action disappeared and the exact-user conversation activity proved Join success"
                    )
                }
                expected == AccessibilityJoinAction.REQUEST -> {
                    dismissVisualActionSurface(targetPackage, current, "VISUAL_REQUEST_UNVERIFIED")
                    completeCurrent(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.UNKNOWN_SCREEN,
                        "Known Request action disappeared without request-sent/cancel-request evidence; not counted as REQUESTED"
                    )
                }
                else -> {
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_VISUAL_AMBIGUOUS_NO_BACK",
                        "positive action disappeared; result unproved; no blind Back sent before direct handoff"
                    )
                    completeCurrent(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.UNKNOWN_SCREEN,
                        "Visual positive action disappeared but its result could not be proved; WhatsApp was left untouched and the queue advanced safely"
                    )
                }
            }
            return true
"""
    s = replace_once(s, old, new, "visual classification")

# 12) Do not pause next handoff after our own X/Back.
if "SHIZUKU_CONTROLLED_EXIT_HANDOFF" not in s:
    old = """        // Commit current result, but never reserve/open the NEXT link if the user has left WhatsApp.
        if (prefs.autoPauseOutsideWhatsApp &&
            currentLaunchSawTargetForeground &&
            !isTargetForeground(targetPackage, forceProbe = true)
        ) {
            prefs.pauseAccessibilityBatch(
                diagnostic = "Paused automatically because the user left the selected WhatsApp target",
                outsideTarget = true
            )
            runtimeDiagnostic(
                current,
                "SHIZUKU_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET",
                "current committed; next remains pending; forced return blocked"
            )
            resetPerLinkEvidence()
            updateNotification("Paused: user left WhatsApp • next link preserved")
            return
        }
"""
    new = """        val foregroundAfterResult = isTargetForeground(targetPackage, forceProbe = true)
        val controlledExit = automationControlledExitReason(current)

        if (prefs.autoPauseOutsideWhatsApp &&
            currentLaunchSawTargetForeground &&
            !foregroundAfterResult &&
            controlledExit == null
        ) {
            prefs.pauseAccessibilityBatch(
                diagnostic = "Paused automatically because the user left the selected WhatsApp target",
                outsideTarget = true
            )
            runtimeDiagnostic(
                current,
                "SHIZUKU_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET",
                "current committed; next remains pending; real foreground loss was not caused by automation"
            )
            resetPerLinkEvidence()
            updateNotification("Paused: user left WhatsApp • next link preserved")
            return
        }

        if (!foregroundAfterResult && controlledExit != null) {
            runtimeDiagnostic(
                current,
                "SHIZUKU_CONTROLLED_EXIT_HANDOFF",
                "automationExit=$controlledExit; current committed; direct next-link launch remains allowed"
            )
        }
"""
    s = replace_once(s, old, new, "controlled exit handoff")

# 13) Per-link reset.
reset_pos = s.find("private fun resetPerLinkEvidence")
if reset_pos >= 0 and "controlledExitAtElapsed = 0L" not in s[reset_pos:reset_pos + 2500]:
    s = replace_once(
        s,
        """        visualTapAttempts = 0
        visualExpectedAction = null
    }
""",
        """        visualTapAttempts = 0
        visualExpectedAction = null
        controlledExitLinkId = -1L
        controlledExitAtElapsed = 0L
        controlledExitReason = ""
    }
""",
        "reset controlled-exit"
    )

# 14) Timing constants.
s = s.replace(
    "        private const val POST_ACTION_RESULT_GRACE_MS = 140L",
    "        private const val POST_ACTION_RESULT_GRACE_MS = 650L",
    1
)

if "private const val CONTROLLED_EXIT_HANDOFF_GRACE_MS" not in s:
    anchor = "        private const val USER_EXIT_CONFIRM_MS = 140L\n"
    if anchor not in s:
        raise SystemExit("ERROR: constant anchor not found")
    s = s.replace(
        anchor,
        anchor + "        private const val CONTROLLED_EXIT_HANDOFF_GRACE_MS = 2_500L\n",
        1
    )

if "private const val ANDROID_UID_USER_RANGE" not in s:
    anchor = "    private companion object {"
    if anchor not in s:
        raise SystemExit("ERROR: companion object anchor not found")
    s = s.replace(
        anchor,
        anchor + "\n        private const val ANDROID_UID_USER_RANGE = 100_000",
        1
    )

write(service_rel, s)

# 15) Broader Request-to-join labels.
matcher_rel = "app/src/main/java/com/althmany/groupmanager/domain/AccessibilityJoinMatcher.kt"
m = read(matcher_rel)
if '"request membership"' not in m:
    old_req = """        "request to join", "request to join group", "ask to join", "ask to join group", "send join request",
        "request to join community", "ask to join community", "send request", "submit request", "request access",
        "طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام الى المجموعة",
        "طلب الانضمام إلى المجتمع", "طلب الانضمام الى المجتمع", "طلب الانضمام إلى هذه المجموعة", "طلب الانضمام الى هذه المجموعة", "اطلب الانضمام", "طلب انضمام",
        "إرسال طلب الانضمام", "ارسال طلب الانضمام", "إرسال الطلب", "ارسال الطلب"
"""
    new_req = """        "request to join", "request to join group", "ask to join", "ask to join group", "send join request",
        "request membership", "request group access", "ask for access", "send request to join",
        "request to join community", "ask to join community", "send request", "submit request", "request access",
        "طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام الى المجموعة",
        "طلب الانضمام إلى المجتمع", "طلب الانضمام الى المجتمع", "طلب الانضمام إلى هذه المجموعة", "طلب الانضمام الى هذه المجموعة",
        "طلب الانضمام إلى القروب", "طلب الانضمام الى القروب", "طلب الانضمام للقروب",
        "اطلب الانضمام", "اطلب الانضمام إلى المجموعة", "اطلب الانضمام الى المجموعة", "طلب انضمام", "طلب دخول",
        "إرسال طلب الانضمام", "ارسال طلب الانضمام", "إرسال الطلب", "ارسال الطلب"
"""
    if old_req not in m:
        raise SystemExit("ERROR: request label anchor not found")
    m = m.replace(old_req, new_req, 1)
write(matcher_rel, m)

# 16) Validator versionCode.
validator_rel = "scripts/validate_source.py"
v = read(validator_rel)
v = v.replace(
    '"versionCode 350": "versionCode = 350" in build,',
    '"versionCode 357": "versionCode = 357" in build,',
    1
)
write(validator_rel, v)

required = {
    gradle_rel: ['versionCode = 357', 'versionName = "3.5.0"'],
    service_rel: [
        "preferProfileSafeShellInput",
        "SHIZUKU_PROFILE_SAFE_TAP",
        "ANDROID_UID_USER_RANGE = 100_000",
        "SHIZUKU_CONTROLLED_EXIT_HANDOFF",
        "SHIZUKU_VISUAL_AMBIGUOUS_NO_BACK",
        "POST_ACTION_RESULT_GRACE_MS = 650L",
        "CONTROLLED_EXIT_HANDOFF_GRACE_MS = 2_500L",
        "No UI dump ran during cooldown",
    ],
    matcher_rel: [
        '"request membership"',
        '"طلب الانضمام إلى القروب"',
        '"اطلب الانضمام إلى المجموعة"',
    ],
    validator_rel: [
        '"versionCode 357": "versionCode = 357" in build',
    ],
}

missing = []
for rel, tokens in required.items():
    src = read(rel)
    for token in tokens:
        if token not in src:
            missing.append(f"{rel}: {token}")

if missing:
    raise SystemExit("ERROR: sanity check failed:\n" + "\n".join(missing))

print("================================================================")
print("✅ AL-OTHMANY SENDER 3.5.0 UNIVERSAL COMPATIBILITY R1 APPLIED")
print("================================================================")
for rel in changed:
    print(" -", rel)
print()
print("Runtime lanes:")
print(" - Personal/owner: persistent UiAutomation + node/touch + shell fallback")
print(" - Work/Dual/secondary: exact-user proof + focused shell input first")
print(" - Local Accessibility fallback remains available")
print(" - Visual rescue remains bounded and verified")
print()
print("Continuity:")
print(" - app-generated X/Back does not become a false manual-exit pause")
print(" - cooldown does not inflate UI-dump failures")
print(" - post-action verification grace = 650ms")
print(" - unproved visual actions do not send blind Back")
print(" - REQUESTED is not counted without result evidence")
print()
print("Install:")
print(" - versionName = 3.5.0")
print(" - versionCode = 357")
print()
print("NOTE: Knox/DPC restrictions cannot be bypassed.")
