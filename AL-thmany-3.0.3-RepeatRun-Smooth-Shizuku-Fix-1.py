#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()
BUILD = ROOT / "app/build.gradle.kts"
PREFS = ROOT / "app/src/main/java/com/althmany/groupmanager/data/AppPreferences.kt"
MAIN = ROOT / "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt"
SH = ROOT / "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"
SHELL = ROOT / "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuShellUserService.kt"
VALIDATOR = ROOT / "scripts/validate_source.py"

for path in (BUILD, PREFS, MAIN, SH, SHELL, VALIDATOR):
    if not path.exists():
        raise SystemExit(f"ERROR: missing {path}; run this patch from repository root")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        print(f"OK already applied: {label}")
        return text
    if old not in text:
        raise SystemExit(f"ERROR: anchor not found: {label}")
    print(f"FIXED: {label}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Version 3.0.1 -> 3.0.2
# -----------------------------------------------------------------------------
build = read(BUILD)
if 'versionCode = 302' not in build:
    if 'versionCode = 301' not in build:
        raise SystemExit("ERROR: expected AL-thmany 3.0.1 (versionCode 301)")
    build = build.replace('versionCode = 301', 'versionCode = 302', 1)
if 'versionName = "3.0.2"' not in build:
    if 'versionName = "3.0.1"' not in build:
        raise SystemExit("ERROR: expected versionName 3.0.1")
    build = build.replace('versionName = "3.0.1"', 'versionName = "3.0.2"', 1)
write(BUILD, build)


# -----------------------------------------------------------------------------
# Auto-pause is now the safe default. Existing installations are also forced on
# at explicit Start below, so a persisted old false value cannot keep reopening
# WhatsApp after the user deliberately leaves it.
# -----------------------------------------------------------------------------
prefs = read(PREFS)
prefs = replace_once(
    prefs,
    'get() = preferences.getBoolean(KEY_AUTO_PAUSE_OUTSIDE_WHATSAPP, false)',
    'get() = preferences.getBoolean(KEY_AUTO_PAUSE_OUTSIDE_WHATSAPP, true)',
    "default Auto Pause outside WhatsApp",
)
write(PREFS, prefs)

main = read(MAIN)
main = replace_once(
    main,
    '''        app.preferences.runtimeShadowMode = false
        app.preferences.fastHandsFreeMode = true
        app.preferences.interLinkDelayMs = AutomationPolicy.FAST_INTER_LINK_DELAY_MS
''',
    '''        app.preferences.runtimeShadowMode = false
        app.preferences.fastHandsFreeMode = true
        // Explicit hands-free runs must respect a manual Home/app switch. The engine pauses
        // instead of forcing WhatsApp back to foreground, then resumes only when the user returns
        // to the same locked WhatsApp target.
        app.preferences.autoPauseOutsideWhatsApp = true
        app.preferences.interLinkDelayMs = AutomationPolicy.FAST_INTER_LINK_DELAY_MS
''',
    "explicit Start enables user-exit Auto Pause",
)
write(MAIN, main)


# -----------------------------------------------------------------------------
# Shizuku UserService: independent shell screenshot visual fallback.
# Persistent UiAutomation.takeScreenshot() is not available on some Samsung/
# Android combinations. In that case we capture the already-visible display via
# shell screencap, run the existing guarded VisualActionButtonPolicy locally in
# the shell UserService, and return ONLY bounds to the app process.
# -----------------------------------------------------------------------------
shell = read(SHELL)
shell = replace_once(
    shell,
    '''import android.os.Process
import java.io.ByteArrayOutputStream
''',
    '''import android.graphics.BitmapFactory
import android.os.Process
import com.althmany.groupmanager.domain.VisualActionButtonPolicy
import java.io.ByteArrayOutputStream
''',
    "shell visual fallback imports",
)

shell = replace_once(
    shell,
    '''    override fun fastFindWidePositiveAction(): String = fastUi.findWidePositiveAction()
''',
    '''    override fun fastFindWidePositiveAction(): String {
        val persistent = fastUi.findWidePositiveAction()
        val persistentState = persistent
            .substringAfter("__AL_VISUAL_ACTION__=", "ERROR")
            .substringBefore(';')
            .uppercase()

        // A working persistent screenshot remains the preferred path. Only bridge/screenshot
        // unavailability falls back to shell screencap; NOT_FOUND is a valid visual answer and
        // must not be converted into a less-specific second guess.
        if (persistentState !in setOf("UNAVAILABLE", "NO_SCREENSHOT", "ERROR")) {
            return persistent
        }
        return shellScreenshotPositiveAction(persistent.take(180))
    }

    /**
     * Screenshot-only rescue for devices where the hidden persistent UiAutomation connection is
     * unavailable. Processing stays inside the Shizuku shell UserService so PNG bytes never cross
     * Binder. The existing VisualActionButtonPolicy accepts only a wide WhatsApp-green control;
     * no fixed tap coordinate and no WhatsApp restriction bypass is introduced.
     */
    private fun shellScreenshotPositiveAction(persistentDetail: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/screencap", "-p").start()
            val captured = ByteArrayOutputStream(256 * 1024)
            val errors = ByteArrayOutputStream(2 * 1024)

            val reader = thread(name = "althmany-screencap-output", isDaemon = true) {
                val buffer = ByteArray(16 * 1024)
                process.inputStream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        val remaining = MAX_SCREENSHOT_BYTES - captured.size()
                        if (remaining > 0) captured.write(buffer, 0, minOf(count, remaining))
                        // Keep draining after the safety cap so screencap cannot block on stdout.
                    }
                }
            }
            val errorReader = thread(name = "althmany-screencap-error", isDaemon = true) {
                val buffer = ByteArray(2 * 1024)
                process.errorStream.use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        val remaining = MAX_SCREENSHOT_ERROR_BYTES - errors.size()
                        if (remaining > 0) errors.write(buffer, 0, minOf(count, remaining))
                    }
                }
            }

            val finished = process.waitFor(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(150, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            reader.join(500)
            errorReader.join(300)

            if (!finished) {
                return visualActionMarker(
                    "UNAVAILABLE",
                    "source=SHELL_SCREENCAP;timeout=true;persistent=${persistentDetail.replace(';', ',')}"
                )
            }
            if (process.exitValue() != 0) {
                return visualActionMarker(
                    "UNAVAILABLE",
                    "source=SHELL_SCREENCAP;exit=${process.exitValue()};error=${errors.toString(Charsets.UTF_8.name()).take(120)}"
                )
            }

            val bytes = captured.toByteArray()
            if (bytes.isEmpty() || bytes.size >= MAX_SCREENSHOT_BYTES) {
                return visualActionMarker(
                    "UNAVAILABLE",
                    "source=SHELL_SCREENCAP;bytes=${bytes.size};invalidOrCapped=true"
                )
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return visualActionMarker(
                    "UNAVAILABLE",
                    "source=SHELL_SCREENCAP;decode=null;bytes=${bytes.size}"
                )
            try {
                val bounds = VisualActionButtonPolicy.findWidePositiveAction(
                    width = bitmap.width,
                    height = bitmap.height,
                    pixelAt = bitmap::getPixel
                ) ?: return visualActionMarker(
                    "NOT_FOUND",
                    "source=SHELL_SCREENCAP;image=${bitmap.width}x${bitmap.height}"
                )
                visualActionMarker(
                    "OK",
                    "bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom};" +
                        "image=${bitmap.width}x${bitmap.height};source=SHELL_SCREENCAP"
                )
            } finally {
                runCatching { bitmap.recycle() }
            }
        } catch (t: Throwable) {
            visualActionMarker(
                "UNAVAILABLE",
                "source=SHELL_SCREENCAP;${t.javaClass.simpleName}:${t.message.orEmpty().take(120)}"
            )
        }
    }

    private fun visualActionMarker(state: String, detail: String): String =
        "__AL_VISUAL_ACTION__=$state;$detail"
''',
    "independent shell screencap positive-action detector",
)

shell = replace_once(
    shell,
    '''    private companion object {
        const val MAX_BINDER_TEXT = 700_000
    }
''',
    '''    private companion object {
        const val MAX_BINDER_TEXT = 700_000
        const val MAX_SCREENSHOT_BYTES = 16 * 1024 * 1024
        const val MAX_SCREENSHOT_ERROR_BYTES = 8 * 1024
        const val SCREENSHOT_TIMEOUT_MS = 2_500L
    }
''',
    "shell screenshot safety limits",
)
write(SHELL, shell)


# -----------------------------------------------------------------------------
# Shizuku runtime:
#   1) Auto-pause on stable manual exit and auto-resume only on manual return.
#   2) Do not waste a second UIAutomator process after exit=137/Killed.
#   3) Reset the command-dump watchdog as soon as visual input succeeds.
# -----------------------------------------------------------------------------
sh = read(SH)

sh = replace_once(
    sh,
    '''    private var lastForegroundVerifiedPackage: String? = null
    private var lastForegroundVerifiedUserId: Int? = null
    // 2.7.0 probes the persistent event-first bridge on every fresh Shizuku UserService. Devices
''',
    '''    private var lastForegroundVerifiedPackage: String? = null
    private var lastForegroundVerifiedUserId: Int? = null
    private var outsideTargetCandidateStartedAtElapsed = 0L
    private var lastOutsideTargetProbeAtElapsed = 0L
    // 2.7.0 probes the persistent event-first bridge on every fresh Shizuku UserService. Devices
''',
    "Shizuku user-exit tracking state",
)

sh = replace_once(
    sh,
    '''            if (prefs.accessibilityPaused) {
                updateNotification(getString(R.string.shizuku_service_paused))
                delay(PAUSED_POLL_MS)
                continue
            }
''',
    '''            if (prefs.accessibilityPaused) {
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
''',
    "Shizuku automatic resume on exact target return",
)

sh = replace_once(
    sh,
    '''            if (!isTargetForeground(targetPackage)) {
                val now = System.currentTimeMillis()
''',
    '''            // Distinguish a deliberate user Home/app switch from a transient Android window
            // handoff. Once a stable manual departure is proved, pause BEFORE the legacy recovery
            // lane can reopen the Deep Link and drag the user back into WhatsApp.
            if (shouldAutoPauseForUserExit(current, targetPackage)) {
                updateNotification("Paused: return to the selected WhatsApp to continue")
                delay(PAUSED_POLL_MS)
                continue
            }

            if (!isTargetForeground(targetPackage)) {
                val now = System.currentTimeMillis()
''',
    "pause before forced foreground recovery",
)

sh = replace_once(
    sh,
    '''    private suspend fun isTargetForeground(targetPackage: String, forceProbe: Boolean = false): Boolean {
''',
    '''    private suspend fun shouldAutoPauseForUserExit(
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
        if (currentLaunchElapsed <= 0L || launchAge < USER_EXIT_LAUNCH_GRACE_MS) {
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
''',
    "Shizuku user-exit pause/resume helpers",
)

sh = replace_once(
    sh,
    '''        recordForegroundLease(targetPackage, userId)
        currentLaunchElapsed = SystemClock.elapsedRealtime()
''',
    '''        recordForegroundLease(targetPackage, userId)
        currentLaunchElapsed = SystemClock.elapsedRealtime()
        outsideTargetCandidateStartedAtElapsed = 0L
        lastOutsideTargetProbeAtElapsed = 0L
''',
    "reset user-exit evidence on engine Deep Link launch",
)

sh = replace_once(
    sh,
    '''        foregroundRecoveryAttempts = 0
        fastUiNoRootStartedAtElapsed = 0L
''',
    '''        foregroundRecoveryAttempts = 0
        outsideTargetCandidateStartedAtElapsed = 0L
        lastOutsideTargetProbeAtElapsed = 0L
        fastUiNoRootStartedAtElapsed = 0L
''',
    "reset user-exit evidence per link",
)

sh = replace_once(
    sh,
    '''        var xml = result.output
        if (!result.success || !xml.contains("<hierarchy")) {
            delay(COMMAND_DUMP_COMPAT_RETRY_MS)
''',
    '''        var xml = result.output
        // exit=137/SIGKILL is a hard failure on this Android runtime. Spawning the same
        // UIAutomator command again only burns another second and delays the screenshot rescue.
        val commandDumpKilled = result.exitCode == 137 || xml.contains("Killed", ignoreCase = true)
        if ((!result.success || !xml.contains("<hierarchy")) && !commandDumpKilled) {
            delay(COMMAND_DUMP_COMPAT_RETRY_MS)
''',
    "skip duplicate UIAutomator retry after exit 137",
)

sh = replace_once(
    sh,
    '''                if (tapNode(remainingButton, targetPackage, current, "VISUAL_POSITIVE_RETRY")) {
                    visualTapAttempts += 1
                    visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
''',
    '''                if (tapNode(remainingButton, targetPackage, current, "VISUAL_POSITIVE_RETRY")) {
                    visualTapAttempts += 1
                    visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
                    consecutiveDumpFailures = 0
                    emptyDumpStartedAt = 0L
''',
    "visual retry keeps current link alive",
)

sh = replace_once(
    sh,
    '''        if (!tapNode(bounds, targetPackage, current, "VISUAL_POSITIVE")) return false
        visualTapAttempts = 1
        visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
''',
    '''        if (!tapNode(bounds, targetPackage, current, "VISUAL_POSITIVE")) return false
        visualTapAttempts = 1
        visualActionTappedAtElapsed = SystemClock.elapsedRealtime()
        consecutiveDumpFailures = 0
        emptyDumpStartedAt = 0L
''',
    "visual first tap keeps current link alive",
)

sh = replace_once(
    sh,
    '''        private const val FAST_UI_DISABLE_AFTER_FAILURES = 4
        private const val NOTIFICATION_THROTTLE_MS = 500L
''',
    '''        private const val FAST_UI_DISABLE_AFTER_FAILURES = 4
        private const val USER_EXIT_LAUNCH_GRACE_MS = 650L
        private const val USER_EXIT_CONFIRM_MS = 420L
        private const val USER_EXIT_PROBE_INTERVAL_MS = 180L
        private const val USER_RETURN_PROBE_INTERVAL_MS = 220L
        private const val NOTIFICATION_THROTTLE_MS = 500L
''',
    "Shizuku user-exit timing constants",
)

write(SH, sh)


# -----------------------------------------------------------------------------
# Validator: version + regression guards for the two exact failures diagnosed.
# -----------------------------------------------------------------------------
validator = read(VALIDATOR)
validator = validator.replace(
    '"versionCode 301": "versionCode = 301" in build,',
    '"versionCode 302": "versionCode = 302" in build,',
)
validator = validator.replace(
    '"versionName 3.0.1": \'versionName = "3.0.1"\' in build,',
    '"versionName 3.0.2": \'versionName = "3.0.2"\' in build,',
)

validator = replace_once(
    validator,
    '''shizuku_service = (JAVA / "com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt").read_text(encoding="utf-8")
shizuku_parser = (JAVA / "com/althmany/groupmanager/domain/ShizukuUiDumpParser.kt").read_text(encoding="utf-8")
''',
    '''shizuku_service = (JAVA / "com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt").read_text(encoding="utf-8")
shizuku_shell_service = (JAVA / "com/althmany/groupmanager/shizuku/ShizukuShellUserService.kt").read_text(encoding="utf-8")
shizuku_parser = (JAVA / "com/althmany/groupmanager/domain/ShizukuUiDumpParser.kt").read_text(encoding="utf-8")
''',
    "validator loads Shizuku shell service",
)

validator = replace_once(
    validator,
    '''    "versionCode 302": "versionCode = 302" in build,
    "versionName 3.0.2": 'versionName = "3.0.2"' in build,
''',
    '''    "versionCode 302": "versionCode = 302" in build,
    "versionName 3.0.2": 'versionName = "3.0.2"' in build,
    "3.0.2 Shizuku shell visual recovery": all(token in shizuku_shell_service for token in [
        "/system/bin/screencap", "SHELL_SCREENCAP", "VisualActionButtonPolicy", "BitmapFactory.decodeByteArray"
    ]),
    "3.0.2 Shizuku user-exit pause resume": all(token in shizuku_service for token in [
        "shouldAutoPauseForUserExit", "tryAutoResumeAfterUserReturn", "SHIZUKU_USER_EXIT_AUTO_PAUSE", "SHIZUKU_USER_RETURN_AUTO_RESUME"
    ]) and "app.preferences.autoPauseOutsideWhatsApp = true" in main_activity,
''',
    "validator guards 3.0.2 Shizuku recovery",
)
write(VALIDATOR, validator)

print()
print("AL-thmany 3.0.2 SHIZUKU VISUAL RECOVERY + AUTO PAUSE APPLIED")
print("Fixes:")
print("  - shell screencap visual rescue when persistent UiAutomation is unavailable")
print("  - detects the existing guarded wide WhatsApp Join/Request/Confirm button")
print("  - exit=137 no longer triggers a duplicate UIAutomator retry")
print("  - successful visual taps reset the UI-dump failure watchdog")
print("  - leaving WhatsApp pauses instead of forcing WhatsApp back")
print("  - returning to the same locked WhatsApp auto-resumes the saved link")
print("  - no restriction bypass; package/user/profile guards remain intact")
print()
print("NEXT:")
print("  grep -nE 'versionCode|versionName' app/build.gradle.kts")
print("  python3 scripts/validate_source.py")

# =============================================================================
# 3.0.3 FOLLOW-UP: repeat-run lifecycle + smooth Shizuku navigation
# This section runs immediately after the 3.0.2 fixes above. The input repo is still expected
# to be 3.0.1; the first section upgrades it to 3.0.2, then this section finalizes 3.0.3.
# =============================================================================
PERSISTENT = ROOT / "app/src/main/java/com/althmany/groupmanager/shizuku/PersistentUiAutomationBridge.kt"
SHIZUKU_BRIDGE = ROOT / "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuBridge.kt"
AIDL = ROOT / "app/src/main/aidl/com/althmany/groupmanager/shizuku/IShizukuShellService.aidl"
for path in (PERSISTENT, SHIZUKU_BRIDGE, AIDL):
    if not path.exists():
        raise SystemExit(f"ERROR: missing {path}")

# Final version 3.0.3
build = read(BUILD)
build = replace_once(build, 'versionCode = 302', 'versionCode = 303', 'versionCode 303')
build = replace_once(build, 'versionName = "3.0.2"', 'versionName = "3.0.3"', 'versionName 3.0.3')
write(BUILD, build)

# -----------------------------------------------------------------------------
# Persistent UiAutomation repeat-run repair.
# The Shizuku UserService survives between app runs. A reboot killed that process, which explains
# why the first run after reboot could be fast while later runs reused a stale UiAutomation object
# or a sticky unavailableReason. Reset only the private AL-thmany bridge state, not Shizuku itself.
# -----------------------------------------------------------------------------
persistent = read(PERSISTENT)
persistent = replace_once(
    persistent,
    '''    fun destroy() {
        clearClickCache()
        synchronized(lock) {
            val current = automation
            automation = null
            if (current != null) {
                runCatching {
                    val method = UiAutomation::class.java.methods.firstOrNull { it.name == "destroy" && it.parameterCount == 0 }
                    method?.invoke(current)
                }
            }
            runCatching { thread?.quitSafely() }
            thread = null
        }
    }

    private fun ensureConnected(): UiAutomation? {
''',
    '''    fun destroy() {
        clearClickCache()
        synchronized(lock) {
            val current = automation
            automation = null
            if (current != null) {
                runCatching {
                    val method = UiAutomation::class.java.methods.firstOrNull { it.name == "destroy" && it.parameterCount == 0 }
                    method?.invoke(current)
                }
            }
            runCatching { thread?.quitSafely() }
            thread = null
        }
    }

    /**
     * New explicit-run boundary. Clear stale UiAutomation connection/error/event state so a second
     * run behaves like the first run after a reboot without rebooting the phone or Shizuku.
     */
    fun resetForNewRun(): Boolean {
        clearClickCache()
        synchronized(lock) {
            val current = automation
            automation = null
            if (current != null) {
                runCatching {
                    val method = UiAutomation::class.java.methods.firstOrNull {
                        it.name == "destroy" && it.parameterCount == 0
                    }
                    method?.invoke(current)
                }
            }
            runCatching { thread?.quitSafely() }
            thread = null
            unavailableReason = null
            eventSequence.set(0L)
            packageEventSequence.clear()
        }
        return ensureConnected() != null
    }

    private fun ensureConnected(): UiAutomation? {
''',
    "Persistent UiAutomation fresh-run reset",
)
write(PERSISTENT, persistent)

aidl = read(AIDL)
aidl = replace_once(
    aidl,
    '''    String fastUiStatus();
''',
    '''    String fastUiStatus();
    boolean fastResetUiAutomation();
''',
    "AIDL fast UI reset",
)
write(AIDL, aidl)

shell = read(SHELL)
shell = replace_once(
    shell,
    '''    override fun fastUiStatus(): String = fastUi.status()
''',
    '''    override fun fastUiStatus(): String = fastUi.status()

    override fun fastResetUiAutomation(): Boolean = fastUi.resetForNewRun()
''',
    "UserService fast UI reset endpoint",
)
write(SHELL, shell)

bridge = read(SHIZUKU_BRIDGE)
bridge = replace_once(
    bridge,
    '''    suspend fun fastUiStatus(context: Context): String = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext "UNAVAILABLE:user service unavailable"
        runCatching { remote?.fastUiStatus().orEmpty() }.getOrDefault("UNAVAILABLE:remote error")
    }

    suspend fun execute(
''',
    '''    suspend fun fastUiStatus(context: Context): String = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext "UNAVAILABLE:user service unavailable"
        runCatching { remote?.fastUiStatus().orEmpty() }.getOrDefault("UNAVAILABLE:remote error")
    }

    suspend fun fastResetUiAutomation(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!ensureBound(context)) return@withContext false
        runCatching { remote?.fastResetUiAutomation() == true }.getOrDefault(false)
    }

    suspend fun execute(
''',
    "app bridge fast UI reset wrapper",
)
write(SHIZUKU_BRIDGE, bridge)

# -----------------------------------------------------------------------------
# Shizuku active-run lifecycle and navigation smoothing.
# -----------------------------------------------------------------------------
sh = read(SH)
sh = replace_once(
    sh,
    '''    private var lastOutsideTargetProbeAtElapsed = 0L
    // 2.7.0 probes the persistent event-first bridge on every fresh Shizuku UserService. Devices
''',
    '''    private var lastOutsideTargetProbeAtElapsed = 0L
    private var fastUiSessionRecoveryAttempts = 0
    // 2.7.0 probes the persistent event-first bridge on every fresh Shizuku UserService. Devices
''',
    "fast UI session recovery counter",
)

# Reset the shell-owned UiAutomation session once per explicit run. Failure is not fatal because
# 3.0.2 already has the independent shell screenshot fallback.
sh = replace_once(
    sh,
    '''        val userId = resolveAndroidUserId(targetPackage)
''',
    '''        val freshFastUi = ShizukuBridge.fastResetUiAutomation(this)
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
''',
    "fresh fast UI session per explicit run",
)

# Remove the 3.0.2 hot-path force foreground probe. It was correct functionally but could add a
# dumpsys round trip during every active scan. Auto-pause now reuses an already-failed foreground
# check, and the UI-tree failure threshold performs one force confirmation only when needed.
sh = replace_once(
    sh,
    '''            // Distinguish a deliberate user Home/app switch from a transient Android window
            // handoff. Once a stable manual departure is proved, pause BEFORE the legacy recovery
            // lane can reopen the Deep Link and drag the user back into WhatsApp.
            if (shouldAutoPauseForUserExit(current, targetPackage)) {
                updateNotification("Paused: return to the selected WhatsApp to continue")
                delay(PAUSED_POLL_MS)
                continue
            }

            if (!isTargetForeground(targetPackage)) {
                val now = System.currentTimeMillis()
''',
    '''            if (!isTargetForeground(targetPackage)) {
                if (pauseAfterStableForegroundLoss(current, targetPackage)) {
                    updateNotification("Paused: return to the selected WhatsApp to continue")
                    delay(PAUSED_POLL_MS)
                    continue
                }
                val now = System.currentTimeMillis()
''',
    "auto pause without hot-path force probe",
)

# Keep the old helper unused for upgrade compatibility and add the lightweight helper immediately
# before it. This avoids broad refactors in a working state machine.
sh = replace_once(
    sh,
    '''    private suspend fun shouldAutoPauseForUserExit(
        current: GroupLink,
        targetPackage: String
    ): Boolean {
''',
    '''    private fun pauseAfterStableForegroundLoss(
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
''',
    "lightweight foreground-loss pause helper",
)

# If UI acquisition is failing while the user manually leaves, verify that once at the failure
# threshold and pause instead of recording UNKNOWN then forcing the next Deep Link.
sh = sh.replace(
    'return handleDumpFailure(current, "exit=${result.exitCode}; ${xml.take(320)}; mode=COMMAND_COMPAT")',
    'return handleDumpFailure(current, targetPackage, "exit=${result.exitCode}; ${xml.take(320)}; mode=COMMAND_COMPAT")',
)
sh = sh.replace(
    'return handleDumpFailure(current, "hierarchy exists but selected package nodes are hidden; fast=$lastFastUiDetail")',
    'return handleDumpFailure(current, targetPackage, "hierarchy exists but selected package nodes are hidden; fast=$lastFastUiDetail")',
)
sh = replace_once(
    sh,
    '''    private suspend fun handleDumpFailure(current: GroupLink, detail: String): ShizukuUiSnapshot? {
''',
    '''    private suspend fun handleDumpFailure(
        current: GroupLink,
        targetPackage: String,
        detail: String
    ): ShizukuUiSnapshot? {
''',
    "UI dump failure receives exact target",
)
sh = replace_once(
    sh,
    '''        if (age >= ShizukuContinuityPolicy.UI_TREE_ADVANCE_AFTER_MS ||
            consecutiveDumpFailures >= ShizukuContinuityPolicy.MAX_UI_TREE_FAILURES
        ) {
            runtimeDiagnostic(
''',
    '''        if (age >= ShizukuContinuityPolicy.UI_TREE_ADVANCE_AFTER_MS ||
            consecutiveDumpFailures >= ShizukuContinuityPolicy.MAX_UI_TREE_FAILURES
        ) {
            val launchAge = if (currentLaunchElapsed > 0L) {
                (SystemClock.elapsedRealtime() - currentLaunchElapsed).coerceAtLeast(0L)
            } else 0L
            if (app.preferences.autoPauseOutsideWhatsApp &&
                launchAge >= USER_EXIT_LAUNCH_GRACE_MS &&
                !isTargetForeground(targetPackage, forceProbe = true)
            ) {
                app.preferences.pauseAccessibilityBatch(
                    diagnostic = "Paused automatically after confirmed user exit while UI acquisition was unavailable",
                    outsideTarget = true
                )
                runtimeDiagnostic(
                    current,
                    "SHIZUKU_USER_EXIT_AUTO_PAUSE",
                    "source=UI_TREE_FAILURE; target=$targetPackage; user=${cachedAndroidUserId ?: -1}; noReopen=true"
                )
                emptyDumpStartedAt = 0L
                consecutiveDumpFailures = 0
                return null
            }
            runtimeDiagnostic(
''',
    "UI failure pauses instead of forcing next link after manual exit",
)

# Persistent bridge gets one bounded self-heal in the same run before it is permanently downgraded
# to command compatibility. This handles stale/disconnected hidden UiAutomation without a reboot.
sh = replace_once(
    sh,
    '''                if (!forceCommandDump && (fast.unavailable || fastUiFailureCount >= FAST_UI_DISABLE_AFTER_FAILURES)) {
                    if (fastUiMode != FastUiMode.DISABLED) {
''',
    '''                if (!forceCommandDump && (fast.unavailable || fastUiFailureCount >= FAST_UI_DISABLE_AFTER_FAILURES)) {
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
''',
    "one bounded fast UI self-heal",
)

# Exit smoothing: use the already-valid exact-user/package lease before paying dumpsys again.
sh = replace_once(
    sh,
    '''        val forcedForeground = isTargetForeground(targetPackage, forceProbe = true)
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
''',
    '''        val userId = resolveAndroidUserId(targetPackage) ?: return false
        if (!foregroundLeaseValid(targetPackage, userId) &&
            !isTargetForeground(targetPackage, forceProbe = true)
        ) return false
        waitInputCooldown()
''',
    "lease-first result Back",
)
sh = replace_once(
    sh,
    '''        val forcedForeground = isTargetForeground(targetPackage, forceProbe = true)
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
''',
    '''        val exactUser = resolveAndroidUserId(targetPackage) ?: return false
        val exactTargetTreeVisible = initialSnapshot.nodes.any { node -> node.belongsTo(targetPackage) }
        if (exactTargetTreeVisible) {
            recordForegroundLease(targetPackage, exactUser)
        } else if (!foregroundLeaseValid(targetPackage, exactUser) &&
            !isTargetForeground(targetPackage, forceProbe = true)
        ) {
            return false
        }
        var snapshot = initialSnapshot
''',
    "snapshot-first X/Close proof",
)
sh = replace_once(
    sh,
    '''    private suspend fun dismissVisualActionSurface(
        targetPackage: String,
        current: GroupLink,
        purpose: String
    ): Boolean {
        if (!isTargetForeground(targetPackage, forceProbe = true)) return false
        waitInputCooldown()
''',
    '''    private suspend fun dismissVisualActionSurface(
        targetPackage: String,
        current: GroupLink,
        purpose: String
    ): Boolean {
        val userId = resolveAndroidUserId(targetPackage) ?: return false
        if (!foregroundLeaseValid(targetPackage, userId) &&
            !isTargetForeground(targetPackage, forceProbe = true)
        ) return false
        waitInputCooldown()
''',
    "lease-first visual surface close",
)

sh = replace_once(
    sh,
    '''        private const val FAST_UI_DISABLE_AFTER_FAILURES = 4
        private const val USER_EXIT_LAUNCH_GRACE_MS = 650L
''',
    '''        private const val FAST_UI_DISABLE_AFTER_FAILURES = 4
        private const val FAST_UI_SESSION_RECOVERY_MAX = 1
        private const val USER_EXIT_LAUNCH_GRACE_MS = 650L
''',
    "bounded fast UI recovery constant",
)
sh = sh.replace('private const val USER_RETURN_PROBE_INTERVAL_MS = 220L', 'private const val USER_RETURN_PROBE_INTERVAL_MS = 450L', 1)
write(SH, sh)

# -----------------------------------------------------------------------------
# Validator final version and 3.0.3 regression guards.
# -----------------------------------------------------------------------------
v = read(VALIDATOR)
v = v.replace('"versionCode 302": "versionCode = 302" in build,', '"versionCode 303": "versionCode = 303" in build,')
v = v.replace('"versionName 3.0.2": \'versionName = "3.0.2"\' in build,', '"versionName 3.0.3": \'versionName = "3.0.3"\' in build,')
# Extend validator source loading if not already present.
v = v.replace(
    'shizuku_shell_service = (JAVA / "com/althmany/groupmanager/shizuku/ShizukuShellUserService.kt").read_text(encoding="utf-8")\n',
    'shizuku_shell_service = (JAVA / "com/althmany/groupmanager/shizuku/ShizukuShellUserService.kt").read_text(encoding="utf-8")\n'
    'shizuku_persistent = (JAVA / "com/althmany/groupmanager/shizuku/PersistentUiAutomationBridge.kt").read_text(encoding="utf-8")\n'
    'shizuku_bridge_source = (JAVA / "com/althmany/groupmanager/shizuku/ShizukuBridge.kt").read_text(encoding="utf-8")\n'
    'shizuku_aidl_source = (ROOT / "app/src/main/aidl/com/althmany/groupmanager/shizuku/IShizukuShellService.aidl").read_text(encoding="utf-8")\n',
    1,
)
v = v.replace(
    '''    "3.0.2 Shizuku user-exit pause resume": all(token in shizuku_service for token in [
        "shouldAutoPauseForUserExit", "tryAutoResumeAfterUserReturn", "SHIZUKU_USER_EXIT_AUTO_PAUSE", "SHIZUKU_USER_RETURN_AUTO_RESUME"
    ]) and "app.preferences.autoPauseOutsideWhatsApp = true" in main_activity,
''',
    '''    "3.0.3 Shizuku user-exit pause resume": all(token in shizuku_service for token in [
        "pauseAfterStableForegroundLoss", "tryAutoResumeAfterUserReturn", "SHIZUKU_USER_EXIT_AUTO_PAUSE", "SHIZUKU_USER_RETURN_AUTO_RESUME"
    ]) and "app.preferences.autoPauseOutsideWhatsApp = true" in main_activity,
    "3.0.3 repeat-run persistent UI reset": all(token in shizuku_service for token in [
        "SHIZUKU_FAST_UI_RUN_RESET", "SHIZUKU_FAST_UI_SELF_HEAL", "fastResetUiAutomation"
    ]) and "resetForNewRun" in shizuku_persistent and "fastResetUiAutomation" in shizuku_bridge_source and "fastResetUiAutomation" in shizuku_aidl_source,
''',
    1,
)
write(VALIDATOR, v)

print()
print("AL-thmany 3.0.3 REPEAT-RUN + SMOOTH SHIZUKU FIX APPLIED")
print("New 3.0.3 corrections:")
print("  - resets stale Persistent UiAutomation at every explicit run; no reboot required")
print("  - one bounded in-run self-heal before command compatibility fallback")
print("  - keeps 3.0.2 shell screenshot rescue for exit=137 / missing UI trees")
print("  - removes active hot-path dumpsys from Auto Pause")
print("  - X/Back reuse the exact-user foreground lease for smoother Work/Profile navigation")
print("  - leaving WhatsApp pauses; returning to the same target resumes")
print("  - package/user/profile and Restriction safeguards remain intact")
print()
print("NEXT:")
print("  grep -nE 'versionCode|versionName' app/build.gradle.kts")
print("  python3 scripts/validate_source.py")
