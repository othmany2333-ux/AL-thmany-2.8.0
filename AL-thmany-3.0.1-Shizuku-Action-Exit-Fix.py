#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()
BUILD = ROOT / "app/build.gradle.kts"
SH = ROOT / "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"
VALIDATOR = ROOT / "scripts/validate_source.py"

for p in (BUILD, SH, VALIDATOR):
    if not p.exists():
        raise SystemExit(f"ERROR: missing {p}; run this patch from repository root")

def read(p): return p.read_text(encoding="utf-8")
def write(p, s): p.write_text(s, encoding="utf-8")

def replace_once(s, old, new, label):
    if new in s:
        print(f"OK already applied: {label}")
        return s
    if old not in s:
        raise SystemExit(f"ERROR: anchor not found: {label}")
    print(f"FIXED: {label}")
    return s.replace(old, new, 1)

# ------------------------------------------------------------
# Version: 3.0.0 -> 3.0.1
# ------------------------------------------------------------
s = read(BUILD)
if 'versionCode = 301' not in s:
    if 'versionCode = 300' not in s:
        raise SystemExit("ERROR: expected AL-thmany 3.0.0 (versionCode 300)")
    s = s.replace('versionCode = 300', 'versionCode = 301', 1)
if 'versionName = "3.0.1"' not in s:
    if 'versionName = "3.0.0"' not in s:
        raise SystemExit("ERROR: expected versionName 3.0.0")
    s = s.replace('versionName = "3.0.0"', 'versionName = "3.0.1"', 1)
write(BUILD, s)

# ------------------------------------------------------------
# Shizuku engine fixes
# ------------------------------------------------------------
s = read(SH)

old = '''                if (candidate?.node?.bounds == null) {
                    consecutiveAmbiguousActions += 1
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_ACTION_AMBIGUOUS",
                        "action=${action.name}; ambiguous=${selection.ambiguous}; best=${candidate?.score ?: -1}; runner=${selection.runnerUpScore}; count=$consecutiveAmbiguousActions"
                    )
                    if (consecutiveAmbiguousActions >= ShizukuRuntimePolicy.MAX_CONSECUTIVE_AMBIGUOUS_ACTIONS) {
'''
new = '''                if (candidate?.node?.bounds == null) {
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
                        handleVisualProfileFallback(current, targetPackage)
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
'''
s = replace_once(s, old, new, "Shizuku partial-tree JOIN/REQUEST visual rescue")

old = '''                val requiredScans = ShizukuRuntimePolicy.actionConsensusScans(
                    score = candidate.score,
                    runnerUpScore = selection.runnerUpScore,
                    clickable = candidate.node.clickable,
                    exactPackage = candidate.node.packageName == targetPackage,
                    ambiguous = selection.ambiguous
                )
                if (!actionConsensus(candidate.fingerprint, requiredScans)) return false
                if (requiredScans == 1) {
                    runtimeDiagnostic(
                        current,
                        "SHIZUKU_FAST_ACTION",
                        "action=${action.name}; score=${candidate.score}; runner=${selection.runnerUpScore}; oneScan=true"
                    )
                }

                val tapped = tapNode(candidate.node.bounds, targetPackage, current, action.name)
'''
new = '''                val requiredScans = ShizukuRuntimePolicy.actionConsensusScans(
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
'''
s = replace_once(s, old, new, "Shizuku exact semantic JOIN/REQUEST liveness rescue")

old = '''    private suspend fun dismissKnownResultSurface(
        targetPackage: String,
        current: GroupLink,
        initialSnapshot: ShizukuUiSnapshot
    ): Boolean {
        if (!isTargetForeground(targetPackage, forceProbe = true)) return false
        var snapshot = initialSnapshot
'''
new = '''    private suspend fun dismissKnownResultSurface(
        targetPackage: String,
        current: GroupLink,
        initialSnapshot: ShizukuUiSnapshot
    ): Boolean {
        val forcedForeground = isTargetForeground(targetPackage, forceProbe = true)
        if (!forcedForeground) {
            val exactTargetTreeVisible =
                initialSnapshot.nodes.any { node -> node.belongsTo(targetPackage) }
            val exactUser = cachedAndroidUserId
            if (!exactTargetTreeVisible || exactUser == null) return false

            // The already-parsed snapshot came from this exact locked package and exact Android
            // user. Renew the short input lease rather than rejecting X/Back because Samsung's
            // dumpsys formatting omitted the user token during this frame.
            recordForegroundLease(targetPackage, exactUser)
            runtimeDiagnostic(
                current,
                "SHIZUKU_EXIT_FOREGROUND_RECOVERED",
                "forced activity probe missed; exact target UI tree remained visible, guarded exit lease renewed"
            )
        }
        var snapshot = initialSnapshot
'''
s = replace_once(s, old, new, "Shizuku X/Close foreground recovery")

old = '''    private suspend fun pressResultBack(
        targetPackage: String,
        current: GroupLink,
        purpose: String
    ): Boolean {
        if (!isTargetForeground(targetPackage, forceProbe = true)) return false
        waitInputCooldown()
'''
new = '''    private suspend fun pressResultBack(
        targetPackage: String,
        current: GroupLink,
        purpose: String
    ): Boolean {
        val forcedForeground = isTargetForeground(targetPackage, forceProbe = true)
        if (!forcedForeground) {
            val userId = cachedAndroidUserId ?: return false
            if (!foregroundLeaseValid(targetPackage, userId)) return false
            runtimeDiagnostic(
                current,
                "SHIZUKU_BACK_LEASE_RECOVERY",
                "$purpose; forced activity proof missed but exact-user/package foreground lease is still valid"
            )
        }
        waitInputCooldown()
'''
s = replace_once(s, old, new, "Shizuku Back foreground lease recovery")

old = '''    private suspend fun completeCurrent(
        current: GroupLink,
        status: LinkStatus,
        resultCode: LinkResultCode,
        detail: String
    ) {
        val prefs = app.preferences
        val targetPackage = prefs.runtimeLockedWhatsAppPackage
        val sessionId = prefs.accessibilitySessionId

        if (targetPackage.isNullOrBlank() || sessionId.isNullOrBlank()) {
            stopRun(AutomationStopReason.SESSION_CHANGED, "Direct Shizuku handoff lost its locked session/target")
            return
        }

        // Port Accessibility's completeAndAdvance behavior: commit current exactly once, fetch the
'''
new = '''    private suspend fun completeCurrent(
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
'''
s = replace_once(s, old, new, "Shizuku direct-handoff session/target recovery")

write(SH, s)

# ------------------------------------------------------------
# Validator version expectations: 3.0.0 -> 3.0.1
# ------------------------------------------------------------
v = read(VALIDATOR)
v = v.replace('"versionCode 300": "versionCode = 300" in build,',
              '"versionCode 301": "versionCode = 301" in build,')
v = v.replace('"versionName 3.0.0": \'versionName = "3.0.0"\' in build,',
              '"versionName 3.0.1": \'versionName = "3.0.1"\' in build,')
write(VALIDATOR, v)

print()
print("AL-thmany 3.0.1 SHIZUKU ACTION + EXIT FIX APPLIED")
print("Fixes:")
print("  - JOIN/REQUEST cannot deadlock forever on unstable consensus")
print("  - guarded visual positive-action fallback after repeated partial trees")
print("  - X/Close can use exact-target snapshot proof on Samsung Work/Secure")
print("  - Back can use a still-valid exact-user/package foreground lease")
print("  - late result callbacks no longer overwrite the run with lost session/target")
print("  - direct handoff can recover the pinned target/session while the run is active")
print()
print("NEXT:")
print("  grep -nE 'versionCode|versionName' app/build.gradle.kts")
print("  python3 scripts/validate_source.py")
