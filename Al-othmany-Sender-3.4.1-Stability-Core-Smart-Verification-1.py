#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from pathlib import Path
import re

ROOT = Path.cwd()
if not (ROOT / "app").is_dir():
    raise SystemExit("ERROR: Run from repository root.")

changed = []

def rd(rel):
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"ERROR: Missing {rel}")
    return p.read_text(encoding="utf-8")

def wr(rel, s):
    p = ROOT / rel
    old = p.read_text(encoding="utf-8")
    if old != s:
        p.write_text(s, encoding="utf-8")
        changed.append(rel)

# Version
rel = "app/build.gradle.kts"
s = rd(rel)
if 'versionCode = 341' not in s:
    if 'versionCode = 340' not in s or 'versionName = "3.4.0"' not in s:
        raise SystemExit("ERROR: Expected source 3.4.0")
    s = s.replace("versionCode = 340", "versionCode = 341", 1)
    s = s.replace('versionName = "3.4.0"', 'versionName = "3.4.1"', 1)
    wr(rel, s)

# ShizukuBridge restart recovery
rel = "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuBridge.kt"
s = rd(rel)
if "import kotlinx.coroutines.delay" not in s:
    s = s.replace("import kotlinx.coroutines.Dispatchers\n",
                  "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.delay\n", 1)
if "suspend fun restartUserService(" not in s:
    anchor = '''    suspend fun fastResetUiAutomation(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext false
        runCatching { remote?.fastResetUiAutomation() == true }.getOrDefault(false)
    }

'''
    add = anchor + '''    suspend fun restartUserService(
        context: Context,
        timeoutMs: Long = 4_500L
    ): Boolean = withContext(Dispatchers.IO) {
        if (!status().ready) return@withContext false
        val appContext = context.applicationContext
        val args = userServiceArgs(appContext)
        runCatching {
            Shizuku::class.java.methods
                .firstOrNull { it.name == "unbindUserService" && it.parameterTypes.size == 3 }
                ?.invoke(null, args, serviceConnection, true)
        }
        synchronized(connectionLock) {
            remote = null
            binding?.cancel()
            binding = null
        }
        delay(120L)
        ensureBound(appContext, timeoutMs)
    }

'''
    if anchor not in s:
        raise SystemExit("ERROR: ShizukuBridge anchor not found")
    s = s.replace(anchor, add, 1)
wr(rel, s)

# Shizuku service
rel = "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"
s = rd(rel)

if "lastDumpFailureCountedAtElapsed" not in s:
    a = '''    private var consecutiveDumpFailures = 0
    private var commandDumpKillRecoveryAttempts = 0
    private var commandDumpSuppressedUntilElapsed = 0L
    private var lastPeriodicUiRefreshProcessed = 0
'''
    b = '''    private var consecutiveDumpFailures = 0
    private var commandDumpKillRecoveryAttempts = 0
    private var commandDumpSuppressedUntilElapsed = 0L
    private var lastDumpFailureCountedAtElapsed = 0L
    private var userServiceRestartAttempts = 0
    private var lastPeriodicUiRefreshProcessed = 0
'''
    if a not in s: raise SystemExit("ERROR: service state anchor not found")
    s = s.replace(a, b, 1)

if "leaving WhatsApp is an explicit user pause" not in s:
    old = '''            if (prefs.accessibilityPaused) {
                // A manual Pause remains manual. Only the special outside-target pause may resume
                // itself, and only after the user brings the exact locked WhatsApp/user back.
                if (prefs.pausedBecauseOutsideTarget &&
                    prefs.autoPauseOutsideWhatsApp &&
                    prefs.autoResumeCurrentRun &&
                    tryAutoResumeAfterUserReturn(targetPackage)
                ) {
                    updateNotification("WhatsApp returned; resuming the saved invitation")
                    continue
                }
                updateNotification(getString(R.string.shizuku_service_paused))
                delay(PAUSED_POLL_MS)
                continue
            }
'''
    new = '''            if (prefs.accessibilityPaused) {
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
'''
    if old not in s: raise SystemExit("ERROR: paused-loop anchor not found")
    s = s.replace(old, new, 1)

if "mode=COMMAND_COOLDOWN; command dump suppressed" not in s:
    old = '''            return handleDumpFailure(
                current,
                targetPackage,
                "command dump temporarily suppressed after exit=137; mode=COMMAND_COOLDOWN"
            )
'''
    new = '''            val remaining = (commandDumpSuppressedUntilElapsed - commandDumpNow).coerceAtLeast(1L)
            delay(minOf(COMMAND_DUMP_COOLDOWN_POLL_MS, remaining))
            return handleDumpFailure(
                current,
                targetPackage,
                "mode=COMMAND_COOLDOWN; command dump suppressed; remainingMs=$remaining",
                realCommandKill = false
            )
'''
    if old not in s: raise SystemExit("ERROR: cooldown anchor not found")
    s = s.replace(old, new, 1)

if "realCommandKill = realCommandKill" not in s:
    old = '''        if (!result.success || !xml.contains("<hierarchy")) {
            return handleDumpFailure(current, targetPackage, "exit=${result.exitCode}; ${xml.take(320)}; mode=COMMAND_COMPAT")
        }
'''
    new = '''        if (!result.success || !xml.contains("<hierarchy")) {
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
    if old not in s: raise SystemExit("ERROR: command failure anchor not found")
    s = s.replace(old, new, 1)

pattern = re.compile(r'''    private suspend fun handleDumpFailure\(
        current: GroupLink,
        targetPackage: String,
        detail: String
    \): ShizukuUiSnapshot\? \{
[\s\S]*?
    \}

(?=    private suspend fun attemptUnknownRecoveryOnce)''')
m = pattern.search(s)
if m:
    fn = '''    private suspend fun handleDumpFailure(
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

'''
    s = s[:m.start()] + fn + s[m.end():]
elif "POST_ACTION_RESULT_GRACE_MS" not in s:
    raise SystemExit("ERROR: handleDumpFailure function not found")

old = '''            if (after.found && remainingButton != null && visualTapAttempts < VISUAL_MAX_TAP_ATTEMPTS) {
'''
new = '''            if (after.found && remainingButton != null &&
                visualTapAttempts < VISUAL_MAX_TAP_ATTEMPTS &&
                visualExpectedAction != null
            ) {
'''
if "visualExpectedAction != null\n            ) {" not in s:
    if old not in s: raise SystemExit("ERROR: visual retry anchor not found")
    s = s.replace(old, new, 1)

tail = s.split("private fun resetPerLinkEvidence()", 1)
if len(tail) != 2: raise SystemExit("ERROR: resetPerLinkEvidence not found")
if "lastDumpFailureCountedAtElapsed = 0L" not in tail[1][:1000]:
    old = '''        emptyDumpStartedAt = 0L
        consecutiveDumpFailures = 0
        commandDumpKillRecoveryAttempts = 0
        consecutiveAmbiguousActions = 0
'''
    new = '''        emptyDumpStartedAt = 0L
        consecutiveDumpFailures = 0
        lastDumpFailureCountedAtElapsed = 0L
        commandDumpKillRecoveryAttempts = 0
        consecutiveAmbiguousActions = 0
'''
    if old not in s: raise SystemExit("ERROR: reset anchor not found")
    s = s.replace(old, new, 1)

s = s.replace("private const val VISUAL_POST_ACTION_VERIFY_MS = 320L",
              "private const val VISUAL_POST_ACTION_VERIFY_MS = 650L")
s = s.replace("private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 12_000L",
              "private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 4_000L")

if "DUMP_FAILURE_MIN_INTERVAL_MS" not in s:
    old = '''        private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 4_000L
        private const val PERIODIC_UI_REFRESH_EVERY = 100
'''
    new = '''        private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 4_000L
        private const val COMMAND_DUMP_COOLDOWN_POLL_MS = 120L
        private const val DUMP_FAILURE_MIN_INTERVAL_MS = 120L
        private const val POST_ACTION_RESULT_GRACE_MS = 650L
        private const val VERIFY_RESULT_POLL_MS = 90L
        private const val USER_SERVICE_RESTART_MAX = 1
        private const val PERIODIC_UI_REFRESH_EVERY = 100
'''
    if old not in s: raise SystemExit("ERROR: constants anchor not found")
    s = s.replace(old, new, 1)

wr(rel, s)

# Accessibility: target return stays paused
rel = "app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt"
s = rd(rel)
pat = re.compile(r'''    private fun maybeAutoResumeOnTargetReturn\(\) \{
[\s\S]*?
    \}

(?=    private )''')
m = pat.search(s)
if m:
    fn = '''    private fun maybeAutoResumeOnTargetReturn() {
        val prefs = app.preferences
        if (!prefs.accessibilityBatchRunning || !prefs.accessibilityPaused ||
            !prefs.pausedBecauseOutsideTarget
        ) return
        runtimeDiagnostic(
            cachedCurrentLink,
            "ACCESSIBILITY_TARGET_RETURN_WAITING_MANUAL_RESUME",
            "selected WhatsApp returned; paused run preserved; manual Resume required"
        )
        refreshAutomationNotification(force = true)
    }

'''
    s = s[:m.start()] + fn + s[m.end():]
elif "ACCESSIBILITY_TARGET_RETURN_WAITING_MANUAL_RESUME" not in s:
    raise SystemExit("ERROR: Accessibility auto-resume anchor not found")
wr(rel, s)

# New installs default Auto Resume off
rel = "app/src/main/java/com/althmany/groupmanager/data/AppPreferences.kt"
s = rd(rel)
s = s.replace("get() = preferences.getBoolean(KEY_AUTO_RESUME_CURRENT_RUN, true)",
              "get() = preferences.getBoolean(KEY_AUTO_RESUME_CURRENT_RUN, false)", 1)
wr(rel, s)

# Secure Folder / Knox discovery: add Samsung persona enumeration only
rel = "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt"
s = rd(rel)
if "dumpsys persona" not in s:
    old = '''                "{ pm list users 2>/dev/null; cmd user list 2>/dev/null; dumpsys user 2>/dev/null; }",
                6_000
'''
    new = '''                "{ pm list users 2>/dev/null; cmd user list 2>/dev/null; dumpsys user 2>/dev/null; " +
                    "dumpsys persona 2>/dev/null; }",
                7_000
'''
    if old not in s: raise SystemExit("ERROR: secure command anchor not found")
    s = s.replace(old, new, 1)

if "val personaIdRegex = Regex(" not in s:
    old = '''        val users = userRegex.findAll(userResult.output)
            .mapNotNull { match ->
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                id to match.groupValues[2].trim().ifBlank { "Android user $id" }
            }
            .distinctBy { it.first }
            .toList()
'''
    new = '''        val userInfoPairs = userRegex.findAll(userResult.output)
            .mapNotNull { match ->
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                id to match.groupValues[2].trim().ifBlank { "Android user $id" }
            }
            .toList()

        val personaIdRegex = Regex(
            "(?:userId|user_id|mUserId|containerId)\\\\s*[=:]\\\\s*([0-9]+)",
            RegexOption.IGNORE_CASE
        )
        val personaPairs = personaIdRegex.findAll(userResult.output)
            .mapNotNull { match ->
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                id to "Samsung Knox profile $id"
            }
            .toList()

        val users = (userInfoPairs + personaPairs)
            .distinctBy { it.first }
'''
    if old not in s: raise SystemExit("ERROR: secure parser anchor not found")
    s = s.replace(old, new, 1)
wr(rel, s)

# Validator
rel = "scripts/validate_source.py"
s = rd(rel)
s = s.replace('"versionCode 340": "versionCode = 340" in build,',
              '"versionCode 341": "versionCode = 341" in build,')
s = s.replace('"versionName 3.4.0": \'versionName = "3.4.0"\' in build,',
              '"versionName 3.4.1": \'versionName = "3.4.1"\' in build,')

if '"3.4.1 cooldown is non-recursive"' not in s:
    anchor = '''    "3.4 pending approval post-click": "postClickApprovalVariant" in service and
        "pendingApprovalVariant" in shizuku_service,
'''
    add = anchor + '''    "3.4.1 cooldown is non-recursive": "realCommandKill: Boolean = false" in shizuku_service and
        "mode=COMMAND_COOLDOWN; command dump suppressed" in shizuku_service,
    "3.4.1 verification grace": all(token in shizuku_service for token in [
        "DUMP_FAILURE_MIN_INTERVAL_MS",
        "POST_ACTION_RESULT_GRACE_MS",
        "VERIFY_RESULT_POLL_MS",
        "age < ShizukuContinuityPolicy.UI_TREE_ADVANCE_AFTER_MS ||",
        "readPendingAction(current) ?: visualExpectedAction"
    ]),
    "3.4.1 UserService hard recovery": "restartUserService" in shizuku_bridge_source and
        "SHIZUKU_USER_SERVICE_RESTART" in shizuku_service,
    "3.4.1 manual user-exit resume": "ACCESSIBILITY_TARGET_RETURN_WAITING_MANUAL_RESUME" in service and
        "leaving WhatsApp is an explicit user pause" in shizuku_service,
    "3.4.1 Secure persona discovery": "dumpsys persona" in main_activity and
        "personaIdRegex" in main_activity,
'''
    if anchor not in s: raise SystemExit("ERROR: validator insertion anchor not found")
    s = s.replace(anchor, add, 1)
wr(rel, s)

# Static sanity
checks = {
    "app/build.gradle.kts": ['versionCode = 341', 'versionName = "3.4.1"'],
    "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuBridge.kt": ["restartUserService"],
    "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt": [
        "realCommandKill: Boolean = false",
        "mode=COMMAND_COOLDOWN; command dump suppressed",
        "SHIZUKU_USER_SERVICE_RESTART",
        "DUMP_FAILURE_MIN_INTERVAL_MS",
        "POST_ACTION_RESULT_GRACE_MS",
        "readPendingAction(current) ?: visualExpectedAction",
        "leaving WhatsApp is an explicit user pause",
        "VISUAL_POST_ACTION_VERIFY_MS = 650L",
        "COMMAND_DUMP_KILL_COOLDOWN_MS = 4_000L",
    ],
    "app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt": [
        "ACCESSIBILITY_TARGET_RETURN_WAITING_MANUAL_RESUME"
    ],
    "app/src/main/java/com/althmany/groupmanager/data/AppPreferences.kt": [
        "getBoolean(KEY_AUTO_RESUME_CURRENT_RUN, false)"
    ],
    "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt": [
        "dumpsys persona", "personaIdRegex"
    ],
}
missing = []
for rel, tokens in checks.items():
    src = rd(rel)
    for token in tokens:
        if token not in src:
            missing.append(f"{rel}: {token}")
if missing:
    raise SystemExit("ERROR: sanity failed:\n" + "\n".join(missing))

print("Al-othmany Sender 3.4.1 Stability Core applied")
print("Changed files:")
for x in changed:
    print(" -", x)
print()
print("NEXT:")
print('grep -n "versionCode\\|versionName" app/build.gradle.kts')
print("python3 scripts/validate_source.py")
print("git diff --check")
print("gradle --no-daemon --stacktrace :app:compileDebugKotlin")
print()
print("If all pass:")
print("git add .")
print('git commit -m "Al-othmany Sender 3.4.1 stability core smart verification"')
print("git push origin main")
