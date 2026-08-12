#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()

def read(rel):
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"ERROR: missing {rel}. Run from repository root.")
    return p, p.read_text(encoding="utf-8")

def write(p, s):
    p.write_text(s, encoding="utf-8")

def replace_required(s, old, new, label):
    if old in s:
        return s.replace(old, new, 1)
    if new in s:
        print(f"SKIP: {label} already applied")
        return s
    raise SystemExit(f"ERROR: anchor not found: {label}")

# ------------------------------------------------------------------
# 1) Version 2.8.8
# ------------------------------------------------------------------
p, s = read("app/build.gradle.kts")
s = re.sub(r'versionCode\s*=\s*28[5-8]', 'versionCode = 288', s, count=1)
s = re.sub(r'versionName\s*=\s*"2\.8\.[5-8]"', 'versionName = "2.8.8"', s, count=1)
if 'versionCode = 288' not in s or 'versionName = "2.8.8"' not in s:
    raise SystemExit("ERROR: version bump failed")
write(p, s)

# ------------------------------------------------------------------
# 2) Personal: restore the 2.4.4 start behavior + force fast preset.
#    Do not block an explicit Start only because the local heartbeat is late.
# ------------------------------------------------------------------
p, s = read("app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt")

start_anchor = '''    private fun startAutomaticRun(allowQueuedContinuation: Boolean = false) {\n        // Pressing Start is an explicit instruction to execute. Never let a developer-only Shadow\n        // preference inherited from an older build suppress every Join while the UI says running.\n        app.preferences.runtimeShadowMode = false\n'''
start_new = '''    private fun startAutomaticRun(allowQueuedContinuation: Boolean = false) {\n        // 2.8.8 Fast Parity: explicit Start always means the proven 2.4.4-style fast JOIN path.\n        // Keep semantic verification, but remove artificial inter-link waiting.\n        app.preferences.runtimeShadowMode = false\n        app.preferences.fastHandsFreeMode = true\n        app.preferences.interLinkDelayMs = AutomationPolicy.FAST_INTER_LINK_DELAY_MS\n        app.preferences.accessibilityActionTimeoutSeconds = AutomationPolicy.FAST_ACTION_TIMEOUT_SECONDS\n'''
s = replace_required(s, start_anchor, start_new, "fast preset on Start")

# Queued/new-run Accessibility gates: 2.4.4 trusted Android's enabled state and let the
# WhatsApp window event wake the service. Replace both stricter local-heartbeat gates.
old_gate = '''            if (backend == AutomationBackend.ACCESSIBILITY) {\n                val readiness = AccessibilityStatus.readiness(this@MainActivity)\n                if (!readiness.systemEnabled || !readiness.localServiceConnected) {\n                    pendingAutoStartAfterSettings = true\n                    pendingQueueContinuationAfterSettings = false\n                    app.preferences.accessibilityQuickJoin = true\n                    waitForLocalAccessibilityBind(allowQueuedContinuation = false, startAfterBind = true)\n                    return\n                }\n            }'''
new_gate = '''            if (backend == AutomationBackend.ACCESSIBILITY &&\n                !AccessibilityStatus.isQuickJoinServiceEnabled(this@MainActivity)\n            ) {\n                pendingAutoStartAfterSettings = true\n                pendingQueueContinuationAfterSettings = false\n                app.preferences.accessibilityQuickJoin = true\n                showOneTimeSetupDialog(true)\n                return\n            }'''
if old_gate in s:
    s = s.replace(old_gate, new_gate, 1)

old_queued = '''            if (backend == AutomationBackend.ACCESSIBILITY) {\n                val readiness = AccessibilityStatus.readiness(this@MainActivity)\n                if (!readiness.systemEnabled || !readiness.localServiceConnected) {\n                    pendingAutoStartAfterSettings = true\n                    pendingQueueContinuationAfterSettings = true\n                    app.preferences.accessibilityQuickJoin = true\n                    waitForLocalAccessibilityBind(allowQueuedContinuation = true, startAfterBind = true)\n                    return\n                }\n            }'''
new_queued = '''            if (backend == AutomationBackend.ACCESSIBILITY &&\n                !AccessibilityStatus.isQuickJoinServiceEnabled(this@MainActivity)\n            ) {\n                pendingAutoStartAfterSettings = true\n                pendingQueueContinuationAfterSettings = true\n                app.preferences.accessibilityQuickJoin = true\n                showOneTimeSetupDialog(true)\n                return\n            }'''
if old_queued in s:
    s = s.replace(old_queued, new_queued, 1)

# onResume: if Android says the service is enabled, restart the pending run quickly like 2.4.4.
old_resume = '''        if (pendingAutoStartAfterSettings) {\n            val continueQueuedBatch = pendingQueueContinuationAfterSettings\n            val readiness = AccessibilityStatus.readiness(this@MainActivity)\n            when {\n                ProfileControlPolicy.mayStartWhileServiceBinds(readiness.systemEnabled) &&\n                    readiness.localServiceConnected -> {\n                    pendingAutoStartAfterSettings = false\n                    pendingQueueContinuationAfterSettings = false\n                    // Do not deadlock on Samsung's delayed service callback. The enabled service\n                    // receives the WhatsApp window event and self-heals its runtime connection.\n                    binding.root.postDelayed({ startAutomaticRun(allowQueuedContinuation = continueQueuedBatch) }, 180L)\n                }\n                else -> {\n                    // Do not reopen the setup dialog from one transient negative read after an\n                    // APK update/resume. The bind gate confirms a genuinely disabled service.\n                    waitForLocalAccessibilityBind(continueQueuedBatch, startAfterBind = true)\n                }\n            }\n        }'''
new_resume = '''        if (pendingAutoStartAfterSettings &&\n            AccessibilityStatus.isQuickJoinServiceEnabled(this@MainActivity)\n        ) {\n            val continueQueuedBatch = pendingQueueContinuationAfterSettings\n            pendingAutoStartAfterSettings = false\n            pendingQueueContinuationAfterSettings = false\n            binding.root.postDelayed(\n                { startAutomaticRun(allowQueuedContinuation = continueQueuedBatch) },\n                90L\n            )\n        }'''
if old_resume in s:
    s = s.replace(old_resume, new_resume, 1)

write(p, s)

# ------------------------------------------------------------------
# 3) Personal Accessibility Speed+ cadence.
#    Still event-first, still stable-screen aware, still semantic only.
# ------------------------------------------------------------------
p, s = read("app/src/main/java/com/althmany/groupmanager/domain/RuntimeCadencePolicy.kt")
repls = {
    'FAST_FALLBACK_POLL_MS = 95L': 'FAST_FALLBACK_POLL_MS = 80L',
    'FAST_EVENT_SCAN_MS = 14L': 'FAST_EVENT_SCAN_MS = 12L',
    'FAST_STABLE_SCAN_MS = 36L': 'FAST_STABLE_SCAN_MS = 30L',
    'FAST_CLICK_THROTTLE_MS = 68L': 'FAST_CLICK_THROTTLE_MS = 60L',
    'FAST_GESTURE_DURATION_MS = 18L': 'FAST_GESTURE_DURATION_MS = 16L',
    'FAST_RESULT_INFERENCE_MS = 88L': 'FAST_RESULT_INFERENCE_MS = 72L',
    'FAST_EXIT_SETTLE_MS = 18L': 'FAST_EXIT_SETTLE_MS = 12L',
    'FAST_TERMINAL_SETTLE_MS = 42L': 'FAST_TERMINAL_SETTLE_MS = 32L',
}
for a,b in repls.items():
    if a in s: s = s.replace(a,b,1)
write(p, s)

p, s = read("app/src/main/java/com/althmany/groupmanager/domain/InvitationStabilityPolicy.kt")
s = s.replace('FAST_POST_JOIN_MIN_EVIDENCE_AGE_MS = 45L', 'FAST_POST_JOIN_MIN_EVIDENCE_AGE_MS = 35L', 1)
write(p, s)

# ------------------------------------------------------------------
# 4) Work/Shizuku Speed+ parity. Same X/Back/verify state machine,
#    but shorter event, input, result, and compatibility waits.
# ------------------------------------------------------------------
p, s = read("app/src/main/java/com/althmany/groupmanager/domain/ShizukuFastUiPolicy.kt")
repls = {
    'EVENT_SCAN_MS = 14L': 'EVENT_SCAN_MS = 12L',
    'STABLE_SCAN_MS = 36L': 'STABLE_SCAN_MS = 30L',
    'FALLBACK_POLL_MS = 95L': 'FALLBACK_POLL_MS = 80L',
    'WATCHDOG_INTERVAL_MS = 40L': 'WATCHDOG_INTERVAL_MS = 32L',
    'CLICK_THROTTLE_MS = 68L': 'CLICK_THROTTLE_MS = 60L',
    'GESTURE_DURATION_MS = 18L': 'GESTURE_DURATION_MS = 16L',
    'RESULT_ANALYSIS_FALLBACK_MS = 88L': 'RESULT_ANALYSIS_FALLBACK_MS = 72L',
    'ACTION_RETRY_AFTER_MS = 120L': 'ACTION_RETRY_AFTER_MS = 95L',
    'POST_JOIN_MIN_EVIDENCE_MS = 35L': 'POST_JOIN_MIN_EVIDENCE_MS = 30L',
    'TERMINAL_ESCAPE_SETTLE_MS = 42L': 'TERMINAL_ESCAPE_SETTLE_MS = 32L',
    'NORMAL_EXIT_SETTLE_MS = 24L': 'NORMAL_EXIT_SETTLE_MS = 18L',
}
for a,b in repls.items():
    if a in s: s = s.replace(a,b,1)
write(p, s)

p, s = read("app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt")
repls = {
    'private const val ACTION_SETTLE_MS = 80L': 'private const val ACTION_SETTLE_MS = 45L',
    'private const val SCAN_INTERVAL_MS = 18L': 'private const val SCAN_INTERVAL_MS = 14L',
    'private const val POST_JOIN_MIN_MS = 70L': 'private const val POST_JOIN_MIN_MS = 55L',
    'private const val FOREGROUND_RECHECK_MS = 45L': 'private const val FOREGROUND_RECHECK_MS = 36L',
    'private const val DUMP_RETRY_MS = 35L': 'private const val DUMP_RETRY_MS = 28L',
    'private const val COMMAND_DUMP_COMPAT_RETRY_MS = 60L': 'private const val COMMAND_DUMP_COMPAT_RETRY_MS = 50L',
    'private const val ACTIVITY_PROBE_RETRY_MS = 55L': 'private const val ACTIVITY_PROBE_RETRY_MS = 45L',
    'private const val REQUEST_TERMINAL_PROBE_MIN_AGE_MS = 420L': 'private const val REQUEST_TERMINAL_PROBE_MIN_AGE_MS = 320L',
    'private const val REQUEST_TERMINAL_PROBE_COOLDOWN_MS = 360L': 'private const val REQUEST_TERMINAL_PROBE_COOLDOWN_MS = 280L',
    'private const val VISUAL_PROFILE_PROBE_AFTER_MS = 180L': 'private const val VISUAL_PROFILE_PROBE_AFTER_MS = 140L',
    'private const val VISUAL_PROFILE_PROBE_INTERVAL_MS = 110L': 'private const val VISUAL_PROFILE_PROBE_INTERVAL_MS = 90L',
    'private const val VISUAL_POST_ACTION_VERIFY_MS = 420L': 'private const val VISUAL_POST_ACTION_VERIFY_MS = 320L',
    'private const val VISUAL_DISMISS_SETTLE_MS = 40L': 'private const val VISUAL_DISMISS_SETTLE_MS = 30L',
}
for a,b in repls.items():
    if a in s: s = s.replace(a,b,1)
write(p, s)

# ------------------------------------------------------------------
# 5) Keep source validator/regressions aligned with the new Speed+ contract.
# ------------------------------------------------------------------
p, s = read("scripts/validate_source.py")
s = re.sub(r'"versionCode 28[5-8]":\s*"versionCode = 28[5-8]" in build,',
           '"versionCode 288": "versionCode = 288" in build,', s, count=1)
s = re.sub(r'"versionName 2\.8\.[5-8]":\s*\'versionName = "2\.8\.[5-8]"\' in build,',
           '"versionName 2.8.8": \'versionName = "2.8.8"\' in build,', s, count=1)
validator_repls = {
    'FAST_TERMINAL_SETTLE_MS = 42L': 'FAST_TERMINAL_SETTLE_MS = 32L',
    'FAST_FALLBACK_POLL_MS = 95L': 'FAST_FALLBACK_POLL_MS = 80L',
    'FAST_EVENT_SCAN_MS = 14L': 'FAST_EVENT_SCAN_MS = 12L',
    'FAST_STABLE_SCAN_MS = 36L': 'FAST_STABLE_SCAN_MS = 30L',
    'FAST_CLICK_THROTTLE_MS = 68L': 'FAST_CLICK_THROTTLE_MS = 60L',
    'EVENT_SCAN_MS = 14L': 'EVENT_SCAN_MS = 12L',
    'STABLE_SCAN_MS = 36L': 'STABLE_SCAN_MS = 30L',
    'FALLBACK_POLL_MS = 95L': 'FALLBACK_POLL_MS = 80L',
    'CLICK_THROTTLE_MS = 68L': 'CLICK_THROTTLE_MS = 60L',
    'GESTURE_DURATION_MS = 18L': 'GESTURE_DURATION_MS = 16L',
    'RESULT_ANALYSIS_FALLBACK_MS = 88L': 'RESULT_ANALYSIS_FALLBACK_MS = 72L',
    'ACTION_RETRY_AFTER_MS = 120L': 'ACTION_RETRY_AFTER_MS = 95L',
    'POST_JOIN_MIN_EVIDENCE_MS = 35L': 'POST_JOIN_MIN_EVIDENCE_MS = 30L',
    'COMMAND_DUMP_COMPAT_RETRY_MS = 60L': 'COMMAND_DUMP_COMPAT_RETRY_MS = 50L',
    'ACTIVITY_PROBE_RETRY_MS = 55L': 'ACTIVITY_PROBE_RETRY_MS = 45L',
    'FAST_POST_JOIN_MIN_EVIDENCE_AGE_MS = 45L': 'FAST_POST_JOIN_MIN_EVIDENCE_AGE_MS = 35L',
}
for a,b in validator_repls.items():
    s = s.replace(a,b)

# 2.8.8 deliberately restores the 2.4.4 enabled-service start gate instead of requiring
# a fresh local heartbeat before WhatsApp is opened. Keep the validator aligned with that design.
s = s.replace(
    '"Accessibility enabled-start handoff 2.7.4": "mayStartWhileServiceBinds" in profile_control_policy and "ProfileControlPolicy.mayStartWhileServiceBinds" in main_activity,',
    '"Accessibility 2.4.4-style enabled start 2.8.8": "isQuickJoinServiceEnabled(this@MainActivity)" in main_activity and "binding.root.postDelayed" in main_activity,'
)
write(p, s)

# Unit timing floor follows the new bounded Speed+ fallback poll.
p, s = read("app/src/test/java/com/althmany/groupmanager/domain/RuntimeCadencePolicyTest.kt")
s = s.replace('assertTrue(RuntimeCadencePolicy.FAST_FALLBACK_POLL_MS >= 90L)',
              'assertTrue(RuntimeCadencePolicy.FAST_FALLBACK_POLL_MS >= 80L)')
write(p, s)

p, s = read("scripts/PureKotlinRegressionMain.kt")
s = s.replace('expect("fast fallback poll avoids busy loop", 95L, RuntimeCadencePolicy.pollIntervalMs(true))',
              'expect("fast fallback poll avoids busy loop", 80L, RuntimeCadencePolicy.pollIntervalMs(true))')
s = s.replace('expect("fast event scan cadence", 14L, RuntimeCadencePolicy.minScanIntervalMs(true, 1))',
              'expect("fast event scan cadence", 12L, RuntimeCadencePolicy.minScanIntervalMs(true, 1))')
s = s.replace('expect("stable fast screen relaxes cadence", 36L, RuntimeCadencePolicy.minScanIntervalMs(true, 8))',
              'expect("stable fast screen relaxes cadence", 30L, RuntimeCadencePolicy.minScanIntervalMs(true, 8))')
write(p, s)

print("AL-thmany 2.8.8 FAST PARITY APPLIED")
print("Personal: 2.4.4-style start + 12/30/80ms event cadence + 0ms handoff")
print("Work: X/Back/verify parity + 12/30/80ms Shizuku cadence + lighter fallback waits")
print("Next:")
print("  python3 scripts/validate_source.py")
print("  git status --short")
