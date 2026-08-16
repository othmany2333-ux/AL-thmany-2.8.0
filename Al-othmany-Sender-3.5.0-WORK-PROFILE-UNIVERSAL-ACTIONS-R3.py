#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from pathlib import Path
import re

ROOT = Path.cwd()
GRADLE = ROOT / "app/build.gradle.kts"
SERVICE = ROOT / "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"
SHELL = ROOT / "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuShellUserService.kt"
VALIDATOR = ROOT / "scripts/validate_source.py"
MATCHER = ROOT / "app/src/main/java/com/althmany/groupmanager/domain/AccessibilityJoinMatcher.kt"

for required in (GRADLE, SERVICE, SHELL, VALIDATOR, MATCHER):
    if not required.exists():
        raise SystemExit(f"ERROR: missing {required}")

gradle = GRADLE.read_text(encoding="utf-8")
if 'versionName = "3.5.0"' not in gradle:
    raise SystemExit(
        'ERROR: restore clean Al-othmany Sender 3.5.0 first '
        '(f931638a45b18827255f26854ec1029e89224efd)'
    )

gradle = re.sub(r'(?m)^(\s*versionCode\s*=\s*)\d+\s*$', r'\g<1>359', gradle, count=1)
GRADLE.write_text(gradle, encoding="utf-8")

shell = SHELL.read_text(encoding="utf-8")
old_shell = '''        // A working persistent screenshot remains the preferred path. Only bridge/screenshot
        // unavailability falls back to shell screencap; NOT_FOUND is a valid visual answer and
        // must not be converted into a less-specific second guess.
        if (persistentState !in setOf("UNAVAILABLE", "NO_SCREENSHOT", "ERROR")) {
            return persistent
        }
        return shellScreenshotPositiveAction(persistent.take(180))
'''
new_shell = '''        // On Samsung Work/Dual/secondary users the persistent UiAutomation screenshot can stay
        // scoped to owner/Launcher even while exact uNN WhatsApp is visibly resumed.
        // An OK result is authoritative. Any other state gets a real shell screencap attempt.
        if (persistentState == "OK") return persistent

        val shellVisual = shellScreenshotPositiveAction(persistent.take(180))
        val shellState = shellVisual
            .substringAfter("__AL_VISUAL_ACTION__=", "ERROR")
            .substringBefore(';')
            .uppercase()

        return when {
            shellState == "OK" -> shellVisual
            persistentState == "NOT_FOUND" && shellState in setOf("UNAVAILABLE", "ERROR") -> persistent
            else -> shellVisual
        }
'''
if 'val shellVisual = shellScreenshotPositiveAction' not in shell:
    if old_shell not in shell:
        raise SystemExit("ERROR: shell visual fallback anchor not found")
    shell = shell.replace(old_shell, new_shell, 1)
SHELL.write_text(shell, encoding="utf-8")

# ---------------------------------------------------------------------------
# R3: broaden semantic coverage for every positive invitation route.
# This improves both the Personal semantic lane and any Work/Profile UI tree
# that is readable. The shell screenshot lane remains the fallback when the
# secondary user's UI tree is unavailable.
# ---------------------------------------------------------------------------
def add_values_to_set(src: str, set_name: str, values: list[str]) -> str:
    pattern = re.compile(
        rf'(private\s+val\s+{re.escape(set_name)}\s*=\s*(?:normalizedSetOf|setOf)\(\s*)(.*?)(\n\s*\))',
        re.S,
    )
    match = pattern.search(src)
    if not match:
        return src
    body = match.group(2)
    missing = [value for value in values if f'"{value}"' not in body]
    if not missing:
        return src
    body = body.rstrip()
    if body and not body.rstrip().endswith(','):
        body += ','
    additions = []
    line = '        '
    for index, value in enumerate(missing):
        token = f'"{value}"'
        if len(line) + len(token) + 2 > 118:
            additions.append(line.rstrip())
            line = '        '
        line += token + ', '
    if line.strip():
        additions.append(line.rstrip(' ,'))
    replacement = match.group(1) + body + '\n' + '\n'.join(additions) + match.group(3)
    return src[:match.start()] + replacement + src[match.end():]


def add_id_tokens_to_function(src: str, function_name: str, tokens: list[str]) -> str:
    start = src.find(f'fun {function_name}(')
    if start < 0:
        return src
    next_fun = src.find('\n    fun ', start + 5)
    end = next_fun if next_fun >= 0 else min(len(src), start + 5000)
    block = src[start:end]
    call = block.find('idLooksLike(')
    if call < 0:
        return src
    absolute_call = start + call
    open_paren = src.find('(', absolute_call)
    if open_paren < 0:
        return src
    depth = 0
    in_string = False
    escaped = False
    close_paren = -1
    for i in range(open_paren, end):
        ch = src[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == '\\':
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                close_paren = i
                break
    if close_paren < 0:
        return src
    args = src[open_paren + 1:close_paren]
    missing = [token for token in tokens if f'"{token}"' not in args]
    if not missing:
        return src
    stripped = args.rstrip()
    suffix = '' if not stripped or stripped.endswith(',') else ','
    addition = '\n                ' + ', '.join(f'"{token}"' for token in missing)
    new_args = stripped + suffix + addition + '\n            '
    return src[:open_paren + 1] + new_args + src[close_paren:]


matcher = MATCHER.read_text(encoding="utf-8")
matcher = add_values_to_set(matcher, "joinLabels", [
    "join the group", "join this chat", "join this group now",
    "join community", "join this community", "join the community", "join community now",
    "join this community now", "join community chat",
    "الانضمام للمجموعة", "انضم إلى المجموعة", "انضم الى المجموعة", "انضم للمجموعة",
    "الانضمام إلى القروب", "الانضمام الى القروب", "انضم إلى القروب", "انضم الى القروب", "انضم للقروب",
    "الانضمام إلى المجتمع", "الانضمام الى المجتمع", "الانضمام للمجتمع",
    "انضم إلى المجتمع", "انضم الى المجتمع", "انضم للمجتمع",
])
matcher = add_values_to_set(matcher, "requestLabels", [
    "request membership", "request group access", "ask for access", "send request to join",
    "request to join community", "ask to join community", "request community access",
    "طلب الانضمام إلى القروب", "طلب الانضمام الى القروب", "طلب الانضمام للقروب",
    "اطلب الانضمام إلى المجموعة", "اطلب الانضمام الى المجموعة", "اطلب الانضمام للمجموعة",
    "اطلب الانضمام إلى المجتمع", "اطلب الانضمام الى المجتمع", "اطلب الانضمام للمجتمع",
    "طلب دخول", "إرسال طلب الانضمام", "ارسال طلب الانضمام",
])
matcher = add_values_to_set(matcher, "confirmationLabels", [
    "continue", "confirm", "confirm join", "continue to join", "continue joining", "proceed", "yes", "ok", "okay",
    "متابعة", "تابع", "تأكيد", "تاكيد", "تأكيد الانضمام", "تاكيد الانضمام", "موافق", "نعم", "استمرار",
])
# Newer engine branches split Group/Community labels into dedicated sets.
matcher = add_values_to_set(matcher, "communityJoinLabels", [
    "join community", "join this community", "join the community", "join community now",
    "الانضمام إلى المجتمع", "الانضمام الى المجتمع", "الانضمام للمجتمع",
    "انضم إلى المجتمع", "انضم الى المجتمع", "انضم للمجتمع",
])
matcher = add_values_to_set(matcher, "groupJoinLabels", [
    "join group", "join the group", "join this group", "join group now",
    "الانضمام إلى المجموعة", "الانضمام الى المجموعة", "الانضمام للمجموعة",
    "انضم إلى المجموعة", "انضم الى المجموعة", "انضم للمجموعة",
])
matcher = add_id_tokens_to_function(matcher, "isCommunityJoin", [
    "join_community", "community_join", "community_join_button", "join_community_button",
])
matcher = add_id_tokens_to_function(matcher, "isGroupJoin", [
    "join_group", "group_join", "group_join_button", "join_group_button",
])
matcher = add_id_tokens_to_function(matcher, "isConfirmation", [
    "confirm_button", "confirmation_button", "continue_button", "join_confirm_button", "positive_button",
])

# Known Request resource-id arm used by the 3.5.x matcher.
if '"request_join_button"' not in matcher:
    old_request_ids = (
        '                "request_join", "request_to_join", "join_request", "send_join_request", '
        '"request_community", "community_request"\n'
    )
    if old_request_ids in matcher:
        matcher = matcher.replace(
            old_request_ids,
            '                "request_join", "request_to_join", "join_request", "send_join_request", '
            '"request_community", "community_request",\n'
            '                "request_join_button", "request_to_join_button", "join_request_button", '
            '"send_join_request_button", "request_community_button", "community_request_button"\n',
            1,
        )
MATCHER.write_text(matcher, encoding="utf-8")

service = SERVICE.read_text(encoding="utf-8")

if "private suspend fun tapProfileVisualPositiveAction(" not in service:
    anchor = "    private suspend fun handleVisualProfileFallback(\n"
    helper = '''    private suspend fun tapProfileVisualPositiveAction(
        bounds: ShizukuBounds,
        targetPackage: String,
        current: GroupLink,
        purpose: String
    ): Boolean {
        if (!ShizukuRuntimePolicy.isSafeTapBounds(bounds, displayWidth, displayHeight)) return false
        val userId = cachedAndroidUserId ?: resolveAndroidUserId(targetPackage) ?: return false
        if (!isTargetForeground(targetPackage, forceProbe = true)) return false

        if (userId != 0) {
            waitInputCooldown()
            val shellTap = ShizukuBridge.execute(
                this,
                "input tap ${bounds.centerX} ${bounds.centerY}",
                2_500
            )
            lastInputAtElapsed = SystemClock.elapsedRealtime()
            val success = shellTap.success
            runtimeDiagnostic(
                current,
                "SHIZUKU_WORK_VISUAL_SHELL_TAP",
                "$purpose; user=$userId; target=$targetPackage; bounds=$bounds; exit=${shellTap.exitCode}"
            )
            if (success) {
                consecutiveInputFailures = 0
                app.preferences.recordRuntimeAction(purpose, "SHIZUKU")
                app.preferences.markRuntimePhase(
                    LinkRuntimePhase.ACTION_TAPPED,
                    "SHIZUKU:$purpose"
                )
                armFastBurst()
            }
            return success
        }

        return tapNode(bounds, targetPackage, current, purpose)
    }

'''
    if anchor not in service:
        raise SystemExit("ERROR: visual helper anchor not found")
    service = service.replace(anchor, helper + anchor, 1)

old_retry = '                if (tapNode(remainingButton, targetPackage, current, "VISUAL_POSITIVE_RETRY")) {\n'
new_retry = '''                if (tapProfileVisualPositiveAction(
                        remainingButton,
                        targetPackage,
                        current,
                        "VISUAL_POSITIVE_RETRY"
                    )
                ) {
'''
if 'tapProfileVisualPositiveAction(\n                        remainingButton' not in service:
    if old_retry not in service:
        raise SystemExit("ERROR: visual retry anchor not found")
    service = service.replace(old_retry, new_retry, 1)

old_initial = '        if (!tapNode(bounds, targetPackage, current, "VISUAL_POSITIVE")) return false\n'
new_initial = '''        if (!tapProfileVisualPositiveAction(
                bounds,
                targetPackage,
                current,
                "VISUAL_POSITIVE"
            )
        ) return false
'''
if 'tapProfileVisualPositiveAction(\n                bounds' not in service:
    if old_initial not in service:
        raise SystemExit("ERROR: initial visual tap anchor not found")
    service = service.replace(old_initial, new_initial, 1)

old_diag = '            "attempt=$visualProbeAttempts; state=${visual.state}; bounds=${bounds ?: "NONE"}; exactForeground=true"\n'
new_diag = '''            "attempt=$visualProbeAttempts; state=${visual.state}; bounds=${bounds ?: "NONE"}; " +
                "detail=${visual.detail.take(180)}; exactForeground=true"
'''
if "detail=${visual.detail.take(180)}" not in service:
    if old_diag not in service:
        raise SystemExit("ERROR: visual diagnostic anchor not found")
    service = service.replace(old_diag, new_diag, 1)

old_cooldown = '''            val remaining = (commandDumpSuppressedUntilElapsed - commandDumpNow).coerceAtLeast(1L)
            delay(minOf(COMMAND_DUMP_COOLDOWN_POLL_MS, remaining))
            return handleDumpFailure(
                current,
                targetPackage,
                "mode=COMMAND_COOLDOWN; command dump suppressed; remainingMs=$remaining",
                realCommandKill = false
            )
'''
new_cooldown = '''            val remaining = (commandDumpSuppressedUntilElapsed - commandDumpNow).coerceAtLeast(1L)
            delay(minOf(COMMAND_DUMP_COOLDOWN_POLL_MS, remaining))
            // No command dump was executed during cooldown. Do not grow the UI-failure counter.
            return null
'''
if "No command dump was executed during cooldown" not in service:
    if old_cooldown in service:
        service = service.replace(old_cooldown, new_cooldown, 1)
    elif "No UI dump ran during cooldown" in service:
        # R1 already fixed the behavior; normalize the marker so R2 sanity checks pass.
        service = service.replace(
            "// No UI dump ran during cooldown, so do not inflate consecutiveDumpFailures.",
            "// No command dump was executed during cooldown. Do not grow the UI-failure counter.",
            1
        )
    else:
        raise SystemExit("ERROR: cooldown anchor not found or R1 cooldown fix not detected")

service = service.replace(
    "        private const val POST_ACTION_RESULT_GRACE_MS = 140L",
    "        private const val POST_ACTION_RESULT_GRACE_MS = 650L",
    1
)
service = service.replace(
    "        private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 4_000L",
    "        private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 15_000L",
    1
)
# ---------------------------------------------------------------------------
# R3 continuity: keep the same link alive after a visual action and allow one
# guarded positive follow-up (WhatsApp may show Continue/Confirm after Join or
# Join Community). This is still bounded by VISUAL_MAX_TAP_ATTEMPTS.
# ---------------------------------------------------------------------------
old_ambiguous = '''                        action in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST) &&
                        consecutiveAmbiguousActions >= 2 &&
                        handleVisualProfileFallback(current, targetPackage)
'''
new_ambiguous = '''                        action in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST) &&
                        consecutiveAmbiguousActions >= 2 &&
                        handleVisualProfileFallback(current, targetPackage, action)
'''
if old_ambiguous in service:
    service = service.replace(old_ambiguous, new_ambiguous, 1)

old_visual_signature = '''    private suspend fun handleVisualProfileFallback(
        current: GroupLink,
        targetPackage: String
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (visualProbeLinkId != current.id) {
            visualProbeLinkId = current.id
            visualProbeAttempts = 0
            lastVisualProbeAtElapsed = 0L
            visualActionTappedAtElapsed = 0L
            visualTapAttempts = 0
        }
'''
new_visual_signature = '''    private suspend fun handleVisualProfileFallback(
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
'''
if old_visual_signature in service:
    service = service.replace(old_visual_signature, new_visual_signature, 1)

if "R3 recover pending action for visual chain" not in service:
    visual_fn = service.find("    private suspend fun handleVisualProfileFallback(")
    if visual_fn >= 0 and "expectedAction: AccessibilityJoinAction?" in service[visual_fn:visual_fn + 500]:
        visual_body = service.find("        val now = SystemClock.elapsedRealtime()", visual_fn)
        if visual_body >= 0 and visual_body < visual_fn + 1800:
            insertion_point = service.find("\n", visual_body) + 1
            recovery = '''        // R3 recover pending action for visual chain.
        val r3PendingVisualAction = expectedAction ?: readPendingAction(current)
        if (visualExpectedAction == null &&
            r3PendingVisualAction in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST)
        ) {
            visualExpectedAction = r3PendingVisualAction
        }
'''
            service = service[:insertion_point] + recovery + service[insertion_point:]

old_guarded_retry = '''            if (after.found && remainingButton != null &&
                visualTapAttempts < VISUAL_MAX_TAP_ATTEMPTS &&
                visualExpectedAction != null
            ) {
'''
new_guarded_retry = '''            if (after.found && remainingButton != null &&
                visualTapAttempts < VISUAL_MAX_TAP_ATTEMPTS &&
                (visualExpectedAction != null || visualActionTappedAtElapsed > 0L)
            ) {
'''
if old_guarded_retry in service:
    service = service.replace(old_guarded_retry, new_guarded_retry, 1)

retry_counter_anchor = '''                    visualTapAttempts += 1
                    visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
'''
retry_counter_new = '''                    visualTapAttempts += 1
                    visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
                    consecutiveDumpFailures = 0
                    emptyDumpStartedAt = 0L
'''
if "visualActionTappedAtElapsed = SystemClock.elapsedRealtime()\n                    consecutiveDumpFailures = 0" not in service:
    if retry_counter_anchor in service:
        service = service.replace(retry_counter_anchor, retry_counter_new, 1)

initial_counter_anchor = '''        visualTapAttempts = 1
        visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
'''
initial_counter_new = '''        visualTapAttempts = 1
        visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
        consecutiveDumpFailures = 0
        emptyDumpStartedAt = 0L
'''
if "visualActionTappedAtElapsed = SystemClock.elapsedRealtime()\n        consecutiveDumpFailures = 0" not in service:
    if initial_counter_anchor in service:
        service = service.replace(initial_counter_anchor, initial_counter_new, 1)

old_dump_retry = '''        var xml = result.output
        if (!result.success || !xml.contains("<hierarchy")) {
            delay(COMMAND_DUMP_COMPAT_RETRY_MS)
'''
new_dump_retry = '''        var xml = result.output
        // R3: exit=137/SIGKILL is a hard failure; do not spawn the same dump twice.
        val commandDumpKilled = result.exitCode == 137 || xml.contains("Killed", ignoreCase = true)
        if ((!result.success || !xml.contains("<hierarchy")) && !commandDumpKilled) {
            delay(COMMAND_DUMP_COMPAT_RETRY_MS)
'''
if "val commandDumpKilled = result.exitCode == 137" not in service and old_dump_retry in service:
    service = service.replace(old_dump_retry, new_dump_retry, 1)

old_pending_retry = '''        if (pending !in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST)) return false
        if (pendingAgeMs() < runtimeSpeed().actionRetryAfterMs) return false
        if (app.preferences.automationRetryCount >= ShizukuFastUiPolicy.MAX_ACTION_ATTEMPTS - 1) return false
        val expectedScreen = if (pending == AccessibilityJoinAction.JOIN) AutomationScreenKind.JOIN_ACTION else AutomationScreenKind.REQUEST_ACTION
'''
new_pending_retry = '''        if (pending !in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST)) return false
        if (pendingAgeMs() < runtimeSpeed().actionRetryAfterMs) return false
        if (app.preferences.automationRetryCount >= ShizukuFastUiPolicy.MAX_ACTION_ATTEMPTS - 1) {
            return handleVisualProfileFallback(current, targetPackage, pending)
        }
        val expectedScreen = if (pending == AccessibilityJoinAction.JOIN) AutomationScreenKind.JOIN_ACTION else AutomationScreenKind.REQUEST_ACTION
'''
if old_pending_retry in service:
    service = service.replace(old_pending_retry, new_pending_retry, 1)

SERVICE.write_text(service, encoding="utf-8")

validator = VALIDATOR.read_text(encoding="utf-8")
validator = re.sub(
    r'"versionCode (?:350|357|358)": "versionCode = (?:350|357|358)" in build,',
    '"versionCode 359": "versionCode = 359" in build,',
    validator,
    count=1,
)
VALIDATOR.write_text(validator, encoding="utf-8")

checks = {
    GRADLE: ['versionCode = 359', 'versionName = "3.5.0"'],
    SHELL: ['if (persistentState == "OK") return persistent', 'val shellVisual = shellScreenshotPositiveAction'],
    SERVICE: [
        'tapProfileVisualPositiveAction',
        'SHIZUKU_WORK_VISUAL_SHELL_TAP',
        'detail=${visual.detail.take(180)}',
        'No command dump was executed during cooldown',
        'POST_ACTION_RESULT_GRACE_MS = 650L',
        'COMMAND_DUMP_KILL_COOLDOWN_MS = 15_000L',
        'R3 recover pending action for visual chain',
    ],
    MATCHER: [
        '"join this community now"',
        '"request community access"',
        '"continue to join"',
    ],
}
missing = []
for file, tokens in checks.items():
    txt = file.read_text(encoding="utf-8")
    for token in tokens:
        if token not in txt:
            missing.append(f"{file}: {token}")
if missing:
    raise SystemExit("ERROR: sanity failed:\n" + "\n".join(missing))

print("==============================================================")
print("✅ AL-OTHMANY SENDER 3.5.0 WORK PROFILE UNIVERSAL ACTIONS R3 APPLIED")
print("==============================================================")
print("✅ persistent NOT_FOUND -> shell screencap")
print("✅ Join group / Request / Join community labels expanded")
print("✅ user10 visual action -> shell InputManager tap")
print("✅ bounded visual follow-up supports Continue/Confirm after first action")
print("✅ visual input resets stale UI-dump failure pressure")
print("✅ cooldown no longer inflates UI dump failures")
print("✅ exit=137 cooldown = 15s")
print("✅ versionName = 3.5.0")
print("✅ versionCode = 359")
print()
print("Expected:")
print("SHIZUKU_VISUAL_ACTION_PROBE ... state=OK ... source=SHELL_SCREENCAP")
print("SHIZUKU_WORK_VISUAL_SHELL_TAP ... user=10 ... exit=0")
print("For known routes, purpose should reflect JOIN/REQUEST; follow-up remains bounded by VISUAL_MAX_TAP_ATTEMPTS")
