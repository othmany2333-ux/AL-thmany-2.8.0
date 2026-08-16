#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Al-othmany Sender 3.5.0 - R6 LIGHTNING ULTRA SPEED + REAL JOIN CLICK FIX

from pathlib import Path

ROOT = Path.cwd()
if not (ROOT / "app").is_dir():
    raise SystemExit("ERROR: run from the Android repository root")

changed = []

def read(rel: str) -> str:
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"ERROR: missing {rel}")
    return p.read_text(encoding="utf-8")

def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")
    if rel not in changed:
        changed.append(rel)

# 1) Personal Accessibility: keep nearest semantic Join/Request bounds.
ACC = "app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt"
a = read(ACC)

old = '''            if (!blocked && safeAncestor) {
                gestureTarget = current
                if (current.isClickable) clickableCandidates += current
            }
'''
new = '''            if (!blocked && safeAncestor) {
                // R5: keep the nearest safe semantic bounds. WhatsApp frequently exposes the
                // Join/Request label as a non-clickable child inside a MaterialButton. Replacing
                // it with a large ancestor moves the gesture center away from the real control.
                if (gestureTarget == null) gestureTarget = current
                if (current.isClickable) clickableCandidates += current
            }
'''
if old in a:
    a = a.replace(old, new, 1)
elif "R5: keep the nearest safe semantic bounds" not in a:
    raise SystemExit("ERROR: Accessibility gesture-target anchor not found")

anchor = '''        if (accessibilityVisualActionTappedAtElapsed > 0L) return true

        val stageAge = (System.currentTimeMillis() - app.preferences.automationStageStartedAt).coerceAtLeast(0L)
'''
replacement = '''        if (screen.requestApprovalNoticeSeen) {
            accessibilityVisualExpectedAction = AccessibilityJoinAction.REQUEST
        }
        if (accessibilityVisualActionTappedAtElapsed > 0L) return true

        val stageAge = (System.currentTimeMillis() - app.preferences.automationStageStartedAt).coerceAtLeast(0L)
'''
if "accessibilityVisualExpectedAction = AccessibilityJoinAction.REQUEST\n        }\n        if (accessibilityVisualActionTappedAtElapsed" not in a:
    if anchor not in a:
        raise SystemExit("ERROR: Accessibility visual-request expectation anchor not found")
    a = a.replace(anchor, replacement, 1)

write(ACC, a)

# 2) Matcher: Request-to-Join IDs.
MATCHER = "app/src/main/java/com/althmany/groupmanager/domain/AccessibilityJoinMatcher.kt"
m = read(MATCHER)

old = '''                "request_join", "request_to_join", "join_request", "send_join_request", "request_community", "community_request"
'''
new = '''                "request_join", "request_to_join", "join_request", "send_join_request", "request_community", "community_request",
                "request_join_button", "request_to_join_button", "join_request_button", "send_join_request_button",
                "request_membership", "request_access", "request_access_button", "community_request_button"
'''
if old in m:
    m = m.replace(old, new, 1)
elif '"request_join_button"' not in m:
    raise SystemExit("ERROR: Request resource-id anchor not found")

write(MATCHER, m)

# 3) Work/Dual: NOT_FOUND must try SHELL_SCREENCAP.
SHELL = "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuShellUserService.kt"
s = read(SHELL)

old = '''        // A working persistent screenshot remains the preferred path. Only bridge/screenshot
        // unavailability falls back to shell screencap; NOT_FOUND is a valid visual answer and
        // must not be converted into a less-specific second guess.
        if (persistentState !in setOf("UNAVAILABLE", "NO_SCREENSHOT", "ERROR")) {
            return persistent
        }
        return shellScreenshotPositiveAction(persistent.take(180))
'''
new = '''        // R5 Work/Dual fix: persistent UiAutomation can remain attached to owner/SystemUI while
        // the exact secondary-user WhatsApp is foreground. In that state NOT_FOUND is not
        // authoritative. Keep OK, otherwise perform one real shell-owned screencap.
        if (persistentState == "OK") return persistent

        val shellVisual = shellScreenshotPositiveAction(persistent.take(180))
        val shellState = shellVisual
            .substringAfter("__AL_VISUAL_ACTION__=", "ERROR")
            .substringBefore(';')
            .uppercase()

        return when {
            shellState == "OK" -> shellVisual
            persistentState == "NOT_FOUND" &&
                shellState in setOf("UNAVAILABLE", "ERROR") -> persistent
            else -> shellVisual
        }
'''
if old in s:
    s = s.replace(old, new, 1)
elif "R5 Work/Dual fix" not in s:
    raise SystemExit("ERROR: shell visual fallback anchor not found")

write(SHELL, s)

# 4) Shizuku: visual rescue stays active during command-dump cooldown/exit=137.
SH = "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"
sh = read(SH)

old = '''            val remaining = (commandDumpSuppressedUntilElapsed - commandDumpNow).coerceAtLeast(1L)
            delay(minOf(COMMAND_DUMP_COOLDOWN_POLL_MS, remaining))
            // No UI dump ran during cooldown, so do not inflate consecutiveDumpFailures.
            return null
'''
new = '''            val remaining = (commandDumpSuppressedUntilElapsed - commandDumpNow).coerceAtLeast(1L)
            // R5: cooldown suppresses only killed uiautomator. It must not suppress screenshot
            // perception or exact-user shell input.
            if (handleVisualProfileFallback(
                    current,
                    targetPackage,
                    readPendingAction(current)
                )
            ) {
                return null
            }
            delay(minOf(COMMAND_DUMP_COOLDOWN_POLL_MS, remaining))
            // No UI dump ran during cooldown, so do not inflate consecutiveDumpFailures.
            return null
'''
if old in sh:
    sh = sh.replace(old, new, 1)
elif "R5: cooldown suppresses only killed uiautomator" not in sh:
    raise SystemExit("ERROR: Shizuku cooldown anchor not found")

old = '''        if (!result.success || !xml.contains("<hierarchy")) {
            val realCommandKill =
                result.exitCode == 137 || xml.contains("Killed", ignoreCase = true)
            return handleDumpFailure(
                current,
                targetPackage,
                "exit=${result.exitCode}; ${xml.take(320)}; mode=COMMAND_COMPAT",
                realCommandKill = realCommandKill
            )
        }
'''
new = '''        if (!result.success || !xml.contains("<hierarchy")) {
            val realCommandKill =
                result.exitCode == 137 || xml.contains("Killed", ignoreCase = true)
            if (realCommandKill &&
                handleVisualProfileFallback(
                    current,
                    targetPackage,
                    readPendingAction(current)
                )
            ) {
                return null
            }
            return handleDumpFailure(
                current,
                targetPackage,
                "exit=${result.exitCode}; ${xml.take(320)}; mode=COMMAND_COMPAT",
                realCommandKill = realCommandKill
            )
        }
'''
if old in sh:
    sh = sh.replace(old, new, 1)
elif "realCommandKill &&\n                handleVisualProfileFallback(" not in sh:
    raise SystemExit("ERROR: first exit=137 visual-rescue anchor not found")

old = '''            "attempt=$visualProbeAttempts; state=${visual.state}; bounds=${bounds ?: "NONE"}; exactForeground=true"
'''
new = '''            "attempt=$visualProbeAttempts; state=${visual.state}; bounds=${bounds ?: "NONE"}; " +
                "detail=${visual.detail.take(180)}; exactForeground=true"
'''
if old in sh:
    sh = sh.replace(old, new, 1)

write(SH, sh)


# ---------------------------------------------------------------------------
# 5) R6 LIGHTNING SPEED ENGINE
# ---------------------------------------------------------------------------

SPEED = "app/src/main/java/com/althmany/groupmanager/domain/RuntimeSpeedProfile.kt"
sp = read(SPEED)

def replace_required(text: str, old: str, new: str, label: str) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise SystemExit(f"ERROR: {label} anchor not found")

fast_old = '''        RuntimeSpeedMode.FAST -> RuntimeSpeedProfile(
            eventScanMs = 14L,
            stableScanMs = 36L,
            fallbackPollMs = 95L,
            postTapWaitMs = 70L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 60L,
            gestureDurationMs = 18L,
            watchdogIntervalMs = 40L,
            unknownRecoveryAfterMs = 1_000L,
            actionRetryAfterMs = 120L
        )
'''
fast_new = '''        RuntimeSpeedMode.FAST -> RuntimeSpeedProfile(
            eventScanMs = 8L,
            stableScanMs = 18L,
            fallbackPollMs = 50L,
            postTapWaitMs = 28L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 36L,
            gestureDurationMs = 12L,
            watchdogIntervalMs = 24L,
            unknownRecoveryAfterMs = 650L,
            actionRetryAfterMs = 70L
        )
'''
sp = replace_required(sp, fast_old, fast_new, "FAST speed")

turbo_old = '''        RuntimeSpeedMode.TURBO -> RuntimeSpeedProfile(
            eventScanMs = 9L,
            stableScanMs = 22L,
            fallbackPollMs = 65L,
            postTapWaitMs = 38L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 44L,
            gestureDurationMs = 14L,
            watchdogIntervalMs = 28L,
            unknownRecoveryAfterMs = 700L,
            actionRetryAfterMs = 90L
        )
'''
turbo_new = '''        RuntimeSpeedMode.TURBO -> RuntimeSpeedProfile(
            eventScanMs = 5L,
            stableScanMs = 10L,
            fallbackPollMs = 28L,
            postTapWaitMs = 16L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 24L,
            gestureDurationMs = 10L,
            watchdogIntervalMs = 16L,
            unknownRecoveryAfterMs = 400L,
            actionRetryAfterMs = 45L
        )
'''
sp = replace_required(sp, turbo_old, turbo_new, "TURBO speed")

max_old = '''        RuntimeSpeedMode.MAX -> RuntimeSpeedProfile(
            eventScanMs = 6L,
            stableScanMs = 14L,
            fallbackPollMs = 40L,
            postTapWaitMs = 22L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 30L,
            gestureDurationMs = 10L,
            watchdogIntervalMs = 20L,
            unknownRecoveryAfterMs = 500L,
            actionRetryAfterMs = 70L
        )
'''
max_new = '''        RuntimeSpeedMode.MAX -> RuntimeSpeedProfile(
            eventScanMs = 4L,
            stableScanMs = 8L,
            fallbackPollMs = 20L,
            postTapWaitMs = 12L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 20L,
            gestureDurationMs = 10L,
            watchdogIntervalMs = 12L,
            unknownRecoveryAfterMs = 320L,
            actionRetryAfterMs = 35L
        )
'''
sp = replace_required(sp, max_old, max_new, "MAX speed")

for old_value, new_value in {
    "const val MIN_CUSTOM_SCAN_MS = 6": "const val MIN_CUSTOM_SCAN_MS = 4",
    "clickThrottleMs = maxOf(28L, scan * 3L)": "clickThrottleMs = maxOf(20L, scan * 3L)",
    "watchdogIntervalMs = maxOf(20L, scan * 2L)": "watchdogIntervalMs = maxOf(12L, scan * 2L)",
    "unknownRecoveryAfterMs = maxOf(500L, post * 8L)": "unknownRecoveryAfterMs = maxOf(320L, post * 8L)",
    "actionRetryAfterMs = maxOf(70L, post * 2L)": "actionRetryAfterMs = maxOf(35L, post * 2L)",
}.items():
    sp = sp.replace(old_value, new_value)

write(SPEED, sp)

# Accessibility event-first cadence: faster click, result, back/X/OK.
CADENCE = "app/src/main/java/com/althmany/groupmanager/domain/RuntimeCadencePolicy.kt"
rc = read(CADENCE)
for old_value, new_value in {
    "const val FAST_FALLBACK_POLL_MS = 80L": "const val FAST_FALLBACK_POLL_MS = 35L",
    "const val FAST_EVENT_SCAN_MS = 12L": "const val FAST_EVENT_SCAN_MS = 6L",
    "const val FAST_STABLE_SCAN_MS = 30L": "const val FAST_STABLE_SCAN_MS = 14L",
    "const val STABLE_SCREEN_RELAX_AFTER_SCANS = 6": "const val STABLE_SCREEN_RELAX_AFTER_SCANS = 4",
    "const val FAST_CLICK_THROTTLE_MS = 60L": "const val FAST_CLICK_THROTTLE_MS = 30L",
    "const val FAST_GESTURE_DURATION_MS = 16L": "const val FAST_GESTURE_DURATION_MS = 10L",
    "const val FAST_RESULT_INFERENCE_MS = 72L": "const val FAST_RESULT_INFERENCE_MS = 36L",
    "const val FAST_EXIT_SETTLE_MS = 12L": "const val FAST_EXIT_SETTLE_MS = 8L",
    "const val FAST_TERMINAL_SETTLE_MS = 32L": "const val FAST_TERMINAL_SETTLE_MS = 18L",
}.items():
    rc = rc.replace(old_value, new_value)
write(CADENCE, rc)

# Accessibility visual fallback, scrolling and long-run responsiveness.
a = read(ACC)
for old_value, new_value in {
    "private const val IDEMPOTENCY_SUPPRESSION_MS = 1_200L":
        "private const val IDEMPOTENCY_SUPPRESSION_MS = 450L",
    "private const val NOTIFICATION_REFRESH_MIN_MS = 300L":
        "private const val NOTIFICATION_REFRESH_MIN_MS = 750L",
    "private const val DIRECT_CONVERSATION_FAST_MIN_AGE_MS = 60L":
        "private const val DIRECT_CONVERSATION_FAST_MIN_AGE_MS = 30L",
    "private const val ACCESSIBILITY_VISUAL_FAST_PROBE_AFTER_MS = 180L":
        "private const val ACCESSIBILITY_VISUAL_FAST_PROBE_AFTER_MS = 80L",
    "private const val ACCESSIBILITY_VISUAL_PROBE_INTERVAL_MS = 420L":
        "private const val ACCESSIBILITY_VISUAL_PROBE_INTERVAL_MS = 120L",
    "private const val ACCESSIBILITY_VISUAL_MAX_PROBE_ATTEMPTS = 3":
        "private const val ACCESSIBILITY_VISUAL_MAX_PROBE_ATTEMPTS = 4",
    "private const val ACCESSIBILITY_VISUAL_FAST_VERIFY_MS = 420L":
        "private const val ACCESSIBILITY_VISUAL_FAST_VERIFY_MS = 220L",
}.items():
    a = a.replace(old_value, new_value)

a = a.replace(
    "val duration = runtimeSpeed().gestureDurationMs.coerceIn(48L, 96L)",
    "val duration = runtimeSpeed().gestureDurationMs.coerceIn(12L, 40L)"
)
a = a.replace(
    "runtimeSpeed().postTapWaitMs.coerceIn(6L, 120L)",
    "runtimeSpeed().postTapWaitMs.coerceIn(4L, 80L)"
)
write(ACC, a)

# Shizuku Work/Dual hot path.
sh = read(SH)
for old_value, new_value in {
    "private const val OPEN_SETTLE_MS = 8L":
        "private const val OPEN_SETTLE_MS = 0L",
    "private const val ACTION_SETTLE_MS = 45L":
        "private const val ACTION_SETTLE_MS = 18L",
    "private const val SCAN_INTERVAL_MS = 14L":
        "private const val SCAN_INTERVAL_MS = 8L",
    "private const val POST_JOIN_MIN_MS = 55L":
        "private const val POST_JOIN_MIN_MS = 30L",
    "private const val FOREGROUND_RECHECK_MS = 36L":
        "private const val FOREGROUND_RECHECK_MS = 20L",
    "private const val DUMP_RETRY_MS = 28L":
        "private const val DUMP_RETRY_MS = 15L",
    "private const val COMMAND_DUMP_COMPAT_RETRY_MS = 50L":
        "private const val COMMAND_DUMP_COMPAT_RETRY_MS = 25L",
    "private const val COMMUNITY_SCROLL_SETTLE_MS = 140L":
        "private const val COMMUNITY_SCROLL_SETTLE_MS = 70L",
    "private const val ACTIVITY_PROBE_RETRY_MS = 45L":
        "private const val ACTIVITY_PROBE_RETRY_MS = 25L",
    "private const val REQUEST_TERMINAL_PROBE_MIN_AGE_MS = 320L":
        "private const val REQUEST_TERMINAL_PROBE_MIN_AGE_MS = 180L",
    "private const val REQUEST_TERMINAL_PROBE_COOLDOWN_MS = 280L":
        "private const val REQUEST_TERMINAL_PROBE_COOLDOWN_MS = 140L",
    "private const val FAST_OPEN_BURST_MS = 1_100L":
        "private const val FAST_OPEN_BURST_MS = 700L",
    "private const val FAST_ACTION_BURST_MS = 750L":
        "private const val FAST_ACTION_BURST_MS = 420L",
    "private const val COMMUNITY_BACK_BURST_MS = 350L":
        "private const val COMMUNITY_BACK_BURST_MS = 180L",
    "private const val VISUAL_PROFILE_PROBE_AFTER_MS = 140L":
        "private const val VISUAL_PROFILE_PROBE_AFTER_MS = 80L",
    "private const val VISUAL_PROFILE_PROBE_INTERVAL_MS = 90L":
        "private const val VISUAL_PROFILE_PROBE_INTERVAL_MS = 50L",
    "private const val VISUAL_POST_ACTION_VERIFY_MS = 650L":
        "private const val VISUAL_POST_ACTION_VERIFY_MS = 320L",
    "private const val VISUAL_DISMISS_SETTLE_MS = 30L":
        "private const val VISUAL_DISMISS_SETTLE_MS = 12L",
    "private const val COMMAND_DUMP_COOLDOWN_POLL_MS = 120L":
        "private const val COMMAND_DUMP_COOLDOWN_POLL_MS = 60L",
    "private const val DUMP_FAILURE_MIN_INTERVAL_MS = 120L":
        "private const val DUMP_FAILURE_MIN_INTERVAL_MS = 60L",
    "private const val POST_ACTION_RESULT_GRACE_MS = 650L":
        "private const val POST_ACTION_RESULT_GRACE_MS = 350L",
    "private const val VERIFY_RESULT_POLL_MS = 90L":
        "private const val VERIFY_RESULT_POLL_MS = 45L",
    "private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 4_000L":
        "private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 1_500L",
    "private const val USER_EXIT_LAUNCH_GRACE_MS = 650L":
        "private const val USER_EXIT_LAUNCH_GRACE_MS = 400L",
    "private const val USER_EXIT_CONFIRM_MS = 140L":
        "private const val USER_EXIT_CONFIRM_MS = 90L",
    "private const val USER_EXIT_PROBE_INTERVAL_MS = 70L":
        "private const val USER_EXIT_PROBE_INTERVAL_MS = 40L",
    "private const val USER_RETURN_PROBE_INTERVAL_MS = 180L":
        "private const val USER_RETURN_PROBE_INTERVAL_MS = 100L",
}.items():
    sh = sh.replace(old_value, new_value)

sh = sh.replace(
    "runtimeSpeed().postTapWaitMs.coerceIn(6L, 120L)",
    "runtimeSpeed().postTapWaitMs.coerceIn(4L, 80L)"
)
write(SH, sh)

# Rename workflow/artifact so the generated APK is clearly R6.
WF = ".github/workflows/build-r4.yml"
if (ROOT / WF).exists():
    wf = read(WF)
    wf = wf.replace("Build Al-othmany R4 APK", "Build Al-othmany R6 LIGHTNING APK")
    wf = wf.replace("Build Al-othmany R5 APK", "Build Al-othmany R6 LIGHTNING APK")
    wf = wf.replace("Al-othmany-Sender-3.5.0-R4.apk", "Al-othmany-Sender-3.5.0-R6-LIGHTNING.apk")
    wf = wf.replace("Al-othmany-Sender-3.5.0-R5.apk", "Al-othmany-Sender-3.5.0-R6-LIGHTNING.apk")
    wf = wf.replace("name: Al-othmany-Sender-3.5.0-R4", "name: Al-othmany-Sender-3.5.0-R6-LIGHTNING")
    wf = wf.replace("name: Al-othmany-Sender-3.5.0-R5", "name: Al-othmany-Sender-3.5.0-R6-LIGHTNING")
    write(WF, wf)

# ---------------------------------------------------------------------------
# 6) R6 static verification.
# ---------------------------------------------------------------------------
checks = {
    ACC: [
        "R5: keep the nearest safe semantic bounds",
        "accessibilityVisualExpectedAction = AccessibilityJoinAction.REQUEST",
        "ACCESSIBILITY_VISUAL_FAST_PROBE_AFTER_MS = 80L",
        "ACCESSIBILITY_VISUAL_FAST_VERIFY_MS = 220L",
    ],
    MATCHER: [
        "request_join_button",
        "request_to_join_button",
        "community_request_button",
    ],
    SHELL: [
        "R5 Work/Dual fix",
        "val shellVisual = shellScreenshotPositiveAction",
    ],
    SH: [
        "R5: cooldown suppresses only killed uiautomator",
        "handleVisualProfileFallback(",
        "OPEN_SETTLE_MS = 0L",
        "ACTION_SETTLE_MS = 18L",
        "VISUAL_PROFILE_PROBE_AFTER_MS = 80L",
        "COMMAND_DUMP_KILL_COOLDOWN_MS = 1_500L",
    ],
    SPEED: [
        "eventScanMs = 4L",
        "interLinkDelayMs = 0L",
        "clickThrottleMs = 20L",
        "actionRetryAfterMs = 35L",
    ],
    CADENCE: [
        "FAST_EVENT_SCAN_MS = 6L",
        "FAST_CLICK_THROTTLE_MS = 30L",
        "FAST_EXIT_SETTLE_MS = 8L",
        "FAST_TERMINAL_SETTLE_MS = 18L",
    ],
}

missing = []
for rel, tokens in checks.items():
    source = read(rel)
    for token in tokens:
        if token not in source:
            missing.append(f"{rel}: {token}")

if missing:
    raise SystemExit("ERROR: R6 static verification failed:\n" + "\n".join(missing))

print()
print("================================================================")
print(" Al-othmany Sender 3.5.0 R6 LIGHTNING ULTRA SPEED APPLIED")
print("================================================================")
for rel in changed:
    print(" -", rel)
print()
print("LIGHTNING PROFILE:")
print(" - Next-link artificial delay: 0 ms")
print(" - MAX event scan: 4 ms")
print(" - MAX stable scan: 8 ms")
print(" - MAX fallback poll: 20 ms")
print(" - MAX post-tap verify: 12 ms")
print(" - MAX click throttle: 20 ms")
print(" - MAX gesture: 10 ms")
print(" - Fast Back/X/OK settle: 8-18 ms")
print(" - Work/Dual visual probe begins at 80 ms")
print(" - Work/Dual visual re-probe: 50 ms")
print(" - exit=137 cooldown: 1500 ms with visual rescue still active")
print()
print("R5 CLICK FIXES INCLUDED:")
print(" - Join / Request to Join / Join Community")
print(" - Confirm / OK / X / Back")
print(" - Work/Dual NOT_FOUND -> SHELL_SCREENCAP")
print()
print("NEXT:")
print(" python3 scripts/validate_source.py")
print(" git diff --check")
print(" git status --short")
