#!/usr/bin/env python3
# -*- coding: utf-8 -*-

# Al-othmany Sender 3.3.0
# Continuity + Awake Screen + Accurate Counters + Secure Classification + Compact Settings
#
# Base: Al-othmany Sender 3.2.1
# Run from repository root:
#   python3 Al-othmany-Sender-3.3.0-Continuity-Awake-Counter-Secure.py

from __future__ import annotations

from pathlib import Path
import re

ROOT = Path.cwd()
if not (ROOT / "app").is_dir():
    raise SystemExit("ERROR: Run this patch from the Android repository root.")

changed: list[str] = []

def file_path(rel: str) -> Path:
    path = ROOT / rel
    if not path.exists():
        raise SystemExit(f"ERROR: Missing required file: {rel}")
    return path

def read(rel: str) -> str:
    return file_path(rel).read_text(encoding="utf-8")

def write(rel: str, text: str) -> None:
    path = file_path(rel)
    old = path.read_text(encoding="utf-8")
    if old != text:
        path.write_text(text, encoding="utf-8")
        changed.append(rel)

def replace_once(rel: str, old: str, new: str, already: str | None = None) -> None:
    text = read(rel)
    if already and already in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"ERROR: Expected exactly one anchor in {rel}, found {count}.\n"
            f"ANCHOR:\n{old[:900]}"
        )
    write(rel, text.replace(old, new, 1))

def add_resources(rel: str, marker: str, snippet: str) -> None:
    text = read(rel)
    if marker in text:
        return
    if "</resources>" not in text:
        raise SystemExit(f"ERROR: Invalid resources file: {rel}")
    write(rel, text.replace("</resources>", snippet.rstrip() + "\n</resources>", 1))

def add_import(rel: str, anchor: str, import_line: str) -> None:
    text = read(rel)
    if import_line in text:
        return
    if anchor not in text:
        raise SystemExit(f"ERROR: Import anchor missing in {rel}: {anchor}")
    write(rel, text.replace(anchor, anchor + import_line + "\n", 1))

# ---------------------------------------------------------------------------
# 1) Version 3.3.0
# ---------------------------------------------------------------------------
build_rel = "app/build.gradle.kts"
text = read(build_rel)
updated = re.sub(r"versionCode\s*=\s*321\b", "versionCode = 330", text, count=1)
updated = re.sub(r'versionName\s*=\s*"3\.2\.1"', 'versionName = "3.3.0"', updated, count=1)
if updated == text:
    if 'versionCode = 330' not in text or 'versionName = "3.3.0"' not in text:
        raise SystemExit("ERROR: Expected the current 3.2.1 source before applying 3.3.0.")
else:
    write(build_rel, updated)

# ---------------------------------------------------------------------------
# 2) Preference: keep physical display awake during an active automation run.
# ---------------------------------------------------------------------------
prefs_rel = "app/src/main/java/com/althmany/groupmanager/data/AppPreferences.kt"

replace_once(
    prefs_rel,
    '''    var quickJoinNotification: Boolean
        get() = preferences.getBoolean(KEY_QUICK_JOIN_NOTIFICATION, true)
        set(value) = preferences.edit().putBoolean(KEY_QUICK_JOIN_NOTIFICATION, value).apply()

''',
    '''    var quickJoinNotification: Boolean
        get() = preferences.getBoolean(KEY_QUICK_JOIN_NOTIFICATION, true)
        set(value) = preferences.edit().putBoolean(KEY_QUICK_JOIN_NOTIFICATION, value).apply()

    /**
     * Keep the physical display awake while an explicit automation run is active.
     * Enabled by default and released automatically when the run pauses/stops.
     */
    var keepScreenAwake: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_AWAKE, true)
        set(value) = preferences.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, value).apply()

''',
    already="var keepScreenAwake: Boolean"
)

replace_once(
    prefs_rel,
    '        private const val KEY_QUICK_JOIN_NOTIFICATION = "quick_join_notification"\n',
    '''        private const val KEY_QUICK_JOIN_NOTIFICATION = "quick_join_notification"
        private const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake"
''',
    already='KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake"'
)

# ---------------------------------------------------------------------------
# 3) Process-wide screen awake guard.
# WAKE_LOCK permission already exists in AndroidManifest.xml.
# ---------------------------------------------------------------------------
awake_rel = "app/src/main/java/com/althmany/groupmanager/util/AutomationScreenAwakeGuard.kt"
awake_path = ROOT / awake_rel
if not awake_path.exists():
    awake_path.parent.mkdir(parents=True, exist_ok=True)
    awake_path.write_text(
        '''package com.althmany.groupmanager.util

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the display awake only while the user-started automation session is active.
 * It does not grant input/accessibility privileges and does not cross Android/Knox boundaries.
 */
object AutomationScreenAwakeGuard {
    private const val SAFETY_TIMEOUT_MS = 12L * 60L * 60L * 1_000L

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    @Synchronized
    fun sync(context: Context, keepAwake: Boolean) {
        if (!keepAwake) {
            release()
            return
        }

        val current = wakeLock
        if (current?.isHeld == true) return

        val powerManager =
            context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = current ?: powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "ALthmany:AutomationScreenAwake"
        ).apply {
            setReferenceCounted(false)
            wakeLock = this
        }

        if (!lock.isHeld) {
            lock.acquire(SAFETY_TIMEOUT_MS)
        }
    }

    @Synchronized
    fun release() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        wakeLock = null
    }
}
''',
        encoding="utf-8",
    )
    changed.append(awake_rel)

# ---------------------------------------------------------------------------
# 4) Settings UI: add awake option and make white panel much smaller.
# ---------------------------------------------------------------------------
settings_layout_rel = "app/src/main/res/layout/activity_settings.xml"

replace_once(
    settings_layout_rel,
    '''                    <com.google.android.material.materialswitch.MaterialSwitch
                        android:id="@+id/autoAdvanceSwitch"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:text="@string/auto_advance" />

''',
    '''                    <com.google.android.material.materialswitch.MaterialSwitch
                        android:id="@+id/autoAdvanceSwitch"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:text="@string/auto_advance" />

                    <com.google.android.material.materialswitch.MaterialSwitch
                        android:id="@+id/keepScreenAwakeSwitch"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="6dp"
                        android:text="@string/keep_screen_awake" />

                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="1dp"
                        android:text="@string/keep_screen_awake_description"
                        android:textColor="@color/text_secondary"
                        android:textSize="11sp" />

''',
    already='android:id="@+id/keepScreenAwakeSwitch"'
)

layout = read(settings_layout_rel)
layout = layout.replace('android:padding="16dp"', 'android:padding="10dp"')
layout = layout.replace('android:layout_marginTop="14dp"', 'android:layout_marginTop="8dp"')
layout = layout.replace('app:cardCornerRadius="16dp"', 'app:cardCornerRadius="14dp"')
write(settings_layout_rel, layout)

settings_rel = "app/src/main/java/com/althmany/groupmanager/ui/SettingsActivity.kt"

replace_once(
    settings_rel,
    '''        window.setDimAmount(0.38f)
        window.setGravity(Gravity.RIGHT)
        binding.root.post {
            val metrics = resources.displayMetrics
            val phoneWidth = (metrics.widthPixels * 0.84f).toInt()
            val maxPanelWidth = (480f * metrics.density).toInt()
            window.setLayout(minOf(phoneWidth, maxPanelWidth), ViewGroup.LayoutParams.MATCH_PARENT)
        }
''',
    '''        window.setDimAmount(0.30f)
        window.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        binding.root.post {
            val metrics = resources.displayMetrics
            val panelWidth = (metrics.widthPixels * 0.72f).toInt()
            val maxPanelWidth = (360f * metrics.density).toInt()
            val panelHeight = (metrics.heightPixels * 0.88f).toInt()
            window.setLayout(minOf(panelWidth, maxPanelWidth), panelHeight)
        }
''',
    already="metrics.widthPixels * 0.72f"
)

replace_once(
    settings_rel,
    '''        binding.autoAdvanceSwitch.isChecked = app.preferences.autoAdvance
        binding.quickJoinNotificationSwitch.isChecked = app.preferences.quickJoinNotification
''',
    '''        binding.autoAdvanceSwitch.isChecked = app.preferences.autoAdvance
        binding.keepScreenAwakeSwitch.isChecked = app.preferences.keepScreenAwake
        binding.quickJoinNotificationSwitch.isChecked = app.preferences.quickJoinNotification
''',
    already="binding.keepScreenAwakeSwitch.isChecked"
)

replace_once(
    settings_rel,
    '''        binding.autoAdvanceSwitch.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) app.preferences.autoAdvance = checked
        }

        binding.quickJoinNotificationSwitch.setOnCheckedChangeListener { _, checked ->
''',
    '''        binding.autoAdvanceSwitch.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) app.preferences.autoAdvance = checked
        }

        binding.keepScreenAwakeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!isBinding) app.preferences.keepScreenAwake = checked
        }

        binding.quickJoinNotificationSwitch.setOnCheckedChangeListener { _, checked ->
''',
    already="binding.keepScreenAwakeSwitch.setOnCheckedChangeListener"
)

add_resources(
    "app/src/main/res/values/strings.xml",
    'name="keep_screen_awake"',
    '''    <!-- Al-othmany Sender 3.3 -->
    <string name="keep_screen_awake">منع سكون الشاشة أثناء التشغيل</string>
    <string name="keep_screen_awake_description">تبقى الشاشة مضاءة طوال جلسة الانضمام النشطة، وتُحرر تلقائيًا عند الإيقاف أو الإيقاف المؤقت.</string>
    <string name="secure_remote_knox_hidden">لم يظهر Samsung Secure Folder عبر ADB. تم تجاهل Island وWork Profile وDual App لأنها ليست المجلد الآمن، ولن يتم اختيار مستخدم خاطئ تلقائيًا.</string>
'''
)

add_resources(
    "app/src/main/res/values-en/strings.xml",
    'name="keep_screen_awake"',
    '''    <!-- Al-othmany Sender 3.3 -->
    <string name="keep_screen_awake">Keep screen awake while running</string>
    <string name="keep_screen_awake_description">Keeps the display on during an active join session and releases it automatically on pause or stop.</string>
    <string name="secure_remote_knox_hidden">Samsung Secure Folder is not visible through ADB. Island, Work Profile, and Dual App were ignored because they are not Secure Folder, and no wrong user will be selected automatically.</string>
'''
)

# ---------------------------------------------------------------------------
# 5) Secure Folder classification.
# Never offer Island/Work/Dual App as "Secure Folder".
# ---------------------------------------------------------------------------
main_rel = "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt"

replace_once(
    main_rel,
    '''                val strongSecure = candidates.filter { candidate ->
                    val name = candidate.userName.lowercase()
                    listOf("secure", "knox", "folder", "مجلد", "آمن", "امن").any(name::contains)
                }
                val choices = if (strongSecure.isNotEmpty()) strongSecure else candidates

                if (choices.size == 1) {
''',
    '''                val strongSecure = candidates.filter { candidate ->
                    val name = candidate.userName.lowercase()
                    val secureName = listOf(
                        "secure", "knox", "folder", "مجلد", "آمن", "امن"
                    ).any(name::contains)
                    val knownNonSecure = listOf(
                        "island", "work", "managed", "dual_app", "dual app", "clone", "cloned"
                    ).any(name::contains)
                    secureName && !knownNonSecure
                }

                // 3.3: never relabel Work/Island/Dual Messenger as Secure Folder.
                if (strongSecure.isEmpty()) {
                    app.preferences.clearRemoteSecureTarget()
                    toast(R.string.secure_remote_knox_hidden)
                    renderInstalledTargets()
                    return@launch
                }
                val choices = strongSecure

                if (choices.size == 1) {
''',
    already="never relabel Work/Island/Dual Messenger as Secure Folder"
)

# Continuity-first defaults for the explicit run.
replace_once(
    main_rel,
    '''        app.preferences.runtimeShadowMode = false
        app.preferences.fastHandsFreeMode = true
''',
    '''        app.preferences.runtimeShadowMode = false
        app.preferences.fastHandsFreeMode = true
        app.preferences.autoResumeCurrentRun = true
        app.preferences.restrictionHandlingMode = RestrictionHandlingMode.SKIP_AND_CONTINUE
''',
    already="app.preferences.restrictionHandlingMode = RestrictionHandlingMode.SKIP_AND_CONTINUE"
)

# ---------------------------------------------------------------------------
# 6) Shizuku engine:
# - screen awake
# - recover proven JOIN before a watchdog failure
# - do not turn ambiguous visual success into a false REQUESTED count
# ---------------------------------------------------------------------------
shizuku_rel = "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"

add_import(
    shizuku_rel,
    "import com.althmany.groupmanager.util.GroupJoinerResultStore\n",
    "import com.althmany.groupmanager.util.AutomationScreenAwakeGuard"
)

replace_once(
    shizuku_rel,
    '''        while (serviceScope.isActive && prefs.accessibilityBatchRunning &&
            prefs.runtimeAutomationBackend == AutomationBackend.SHIZUKU
        ) {
            if (!runtimeHeartbeat(targetPackage)) {
''',
    '''        while (serviceScope.isActive && prefs.accessibilityBatchRunning &&
            prefs.runtimeAutomationBackend == AutomationBackend.SHIZUKU
        ) {
            AutomationScreenAwakeGuard.sync(
                this,
                prefs.keepScreenAwake && !prefs.accessibilityPaused
            )
            if (!runtimeHeartbeat(targetPackage)) {
''',
    already="prefs.keepScreenAwake && !prefs.accessibilityPaused"
)

replace_once(
    shizuku_rel,
    '''    override fun onDestroy() {
        runJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
''',
    '''    override fun onDestroy() {
        AutomationScreenAwakeGuard.release()
        runJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
''',
    already="override fun onDestroy() {\n        AutomationScreenAwakeGuard.release()"
)

replace_once(
    shizuku_rel,
    '''    private fun stopRun(reason: AutomationStopReason, diagnostic: String) {
        app.preferences.stopAccessibilityBatch(reason, diagnostic)
''',
    '''    private fun stopRun(reason: AutomationStopReason, diagnostic: String) {
        AutomationScreenAwakeGuard.release()
        app.preferences.stopAccessibilityBatch(reason, diagnostic)
''',
    already="private fun stopRun(reason: AutomationStopReason, diagnostic: String) {\n        AutomationScreenAwakeGuard.release()"
)

replace_once(
    shizuku_rel,
    '''    private fun completeRun(reason: AutomationStopReason, diagnostic: String) {
        app.preferences.completeAccessibilityBatch(reason, diagnostic)
''',
    '''    private fun completeRun(reason: AutomationStopReason, diagnostic: String) {
        AutomationScreenAwakeGuard.release()
        app.preferences.completeAccessibilityBatch(reason, diagnostic)
''',
    already="private fun completeRun(reason: AutomationStopReason, diagnostic: String) {\n        AutomationScreenAwakeGuard.release()"
)

replace_once(
    shizuku_rel,
    '''        if (fastUiMode == FastUiMode.ACTIVE && shouldFastWatchdogAdvance(snapshot, pending)) {
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
''',
    '''        if (fastUiMode == FastUiMode.ACTIVE && shouldFastWatchdogAdvance(snapshot, pending)) {
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
''',
    already="Fast watchdog recovered a verified exact-user WhatsApp conversation after Join"
)

replace_once(
    shizuku_rel,
    '''    private var visualActionTappedAtElapsed = 0L
    private var visualTapAttempts = 0
''',
    '''    private var visualActionTappedAtElapsed = 0L
    private var visualTapAttempts = 0
    private var visualExpectedAction: AccessibilityJoinAction? = null
''',
    already="private var visualExpectedAction: AccessibilityJoinAction?"
)

replace_once(
    shizuku_rel,
    '''                        action in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST) &&
                        consecutiveAmbiguousActions >= 2 &&
                        handleVisualProfileFallback(current, targetPackage)
''',
    '''                        action in setOf(AccessibilityJoinAction.JOIN, AccessibilityJoinAction.REQUEST) &&
                        consecutiveAmbiguousActions >= 2 &&
                        handleVisualProfileFallback(current, targetPackage, action)
''',
    already="handleVisualProfileFallback(current, targetPackage, action)"
)

replace_once(
    shizuku_rel,
    '''    private suspend fun handleVisualProfileFallback(
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
''',
    '''    private suspend fun handleVisualProfileFallback(
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
''',
    already="expectedAction: AccessibilityJoinAction? = null"
)

replace_once(
    shizuku_rel,
    '''            val joinedConversation = probeJoinedConversationActivity(targetPackage, current)
            dismissVisualActionSurface(targetPackage, current, "VISUAL_ACTION_COMPLETED")
            completeCurrent(
                current,
                if (joinedConversation) LinkStatus.JOINED else LinkStatus.REQUESTED,
                if (joinedConversation) LinkResultCode.JOIN_ACTION_COMPLETED else LinkResultCode.REQUEST_SENT,
                if (joinedConversation) {
                    "Wide WhatsApp Join action disappeared and the exact-user conversation activity was proved; surface closed and next link opened"
                } else {
                    "Wide WhatsApp Join/Request action disappeared after protected direct input; pending surface closed and next link opened"
                }
            )
            return true
''',
    '''            val expected = visualExpectedAction ?: readPendingAction(current)
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
''',
    already="not counted as a false request"
)

replace_once(
    shizuku_rel,
    '''        app.preferences.setAccessibilityPending(
            current.id,
            AccessibilityJoinAction.JOIN.name,
            AccessibilityInviteTarget.UNKNOWN
        )
''',
    '''        visualExpectedAction?.let { expected ->
            app.preferences.setAccessibilityPending(
                current.id,
                expected.name,
                AccessibilityInviteTarget.UNKNOWN
            )
        }
''',
    already="visualExpectedAction?.let { expected ->"
)

replace_once(
    shizuku_rel,
    '''        visualActionTappedAtElapsed = 0L
        visualTapAttempts = 0
    }

    private suspend fun armFastEventSequence''',
    '''        visualActionTappedAtElapsed = 0L
        visualTapAttempts = 0
        visualExpectedAction = null
    }

    private suspend fun armFastEventSequence''',
    already="visualTapAttempts = 0\n        visualExpectedAction = null\n    }\n\n    private suspend fun armFastEventSequence"
)

# ---------------------------------------------------------------------------
# 7) Accessibility engine:
# - screen awake
# - verified X, then Back fallback
# - root-loss watchdog cannot wait forever
# - request watchdog exits sheet before next link
# - restriction watchdog honors Skip & Continue
# ---------------------------------------------------------------------------
acc_rel = "app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt"

add_import(
    acc_rel,
    "import com.althmany.groupmanager.util.GroupJoinerResultStore\n",
    "import com.althmany.groupmanager.util.AutomationScreenAwakeGuard"
)

replace_once(
    acc_rel,
    '''        pollJob = serviceScope.launch {
            while (isActive) {
                delay(runtimeSpeed().fallbackPollMs)
                ProfileAccessibilityRuntime.heartbeat(this@QuickJoinAccessibilityService)
''',
    '''        pollJob = serviceScope.launch {
            while (isActive) {
                AutomationScreenAwakeGuard.sync(
                    this@QuickJoinAccessibilityService,
                    app.preferences.keepScreenAwake &&
                        app.preferences.accessibilityBatchRunning &&
                        !app.preferences.accessibilityPaused
                )
                delay(runtimeSpeed().fallbackPollMs)
                ProfileAccessibilityRuntime.heartbeat(this@QuickJoinAccessibilityService)
''',
    already="app.preferences.keepScreenAwake &&\n                        app.preferences.accessibilityBatchRunning"
)

replace_once(
    acc_rel,
    '''    override fun onDestroy() {
        runtimeConnected = false
''',
    '''    override fun onDestroy() {
        AutomationScreenAwakeGuard.release()
        runtimeConnected = false
''',
    already="override fun onDestroy() {\n        AutomationScreenAwakeGuard.release()"
)

replace_once(
    acc_rel,
    '''    private suspend fun fastExitPendingRequestSurface(screen: ScreenInspection) {
        if (screen.loading || screen.restricted) return
        val closeClicked = screen.closeNode?.let { node ->
            withContext(Dispatchers.Main.immediate) {
                clickNodeParentOrGesture(node, allowSafeClose = true)
            }
        } == true
        if (!closeClicked) {
            withContext(Dispatchers.Main.immediate) { performGlobalAction(GLOBAL_ACTION_BACK) }
        }
        val settle = ConversationFastExitPolicy.settleMs(app.preferences.fastHandsFreeMode)
        if (settle > 0L) delay(settle)
    }
''',
    '''    private suspend fun fastExitPendingRequestSurface(screen: ScreenInspection): Boolean {
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
''',
    already="private suspend fun fastExitPendingRequestSurface(screen: ScreenInspection): Boolean"
)

replace_once(
    acc_rel,
    '''            "REQUEST_SUBMITTED" -> {
                fastExitPendingRequestSurface(screen)
                completeAndAdvance(
                    current, LinkStatus.REQUESTED, LinkResultCode.REQUEST_SENT, "Join request sent",
                    fastAdvance = true,
                    surfaceAlreadyExited = true,
                    terminalEscapeAdvance = terminalEscape.bypassInterLinkDelay
                )
            }
''',
    '''            "REQUEST_SUBMITTED" -> {
                val requestSurfaceExited = fastExitPendingRequestSurface(screen)
                completeAndAdvance(
                    current, LinkStatus.REQUESTED, LinkResultCode.REQUEST_SENT, "Join request sent",
                    fastAdvance = true,
                    surfaceAlreadyExited = requestSurfaceExited,
                    terminalEscapeAdvance = terminalEscape.bypassInterLinkDelay
                )
            }
''',
    already="val requestSurfaceExited = fastExitPendingRequestSurface(screen)"
)

replace_once(
    acc_rel,
    '''        serviceScope.launch {
            var lastWatchFingerprint = 0L
            var stableWatchScans = 0
            while (isActive) {
''',
    '''        serviceScope.launch {
            var lastWatchFingerprint = 0L
            var stableWatchScans = 0
            var noInspectionStartedAtElapsed = 0L
            while (isActive) {
''',
    already="var noInspectionStartedAtElapsed = 0L"
)

replace_once(
    acc_rel,
    '''                if (inspection == null) {
                    requestScan()
                    continue
                }

                if (inspection.fingerprint == lastWatchFingerprint) {
''',
    '''                if (inspection == null) {
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
''',
    already="Accessibility UI acquisition stayed unavailable after the action"
)

replace_once(
    acc_rel,
    '''                if (inspection.restricted) {
                    withContext(Dispatchers.IO) {
                        app.repository.markStatus(
                            current.id,
                            LinkStatus.FAILED,
                            LinkResultCode.RESTRICTED,
                            "WhatsApp displayed a restriction while the continuity watchdog was active"
                        )
                    }
                    stopBatch(AutomationStopReason.RESTRICTED_SCREEN, "Restriction screen detected")
                    return@launch
                }
''',
    '''                if (inspection.restricted) {
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
''',
    already="Restriction recorded by the continuity watchdog; continuing without bypass"
)

replace_once(
    acc_rel,
    '''                if (inspection.requestSubmitted) {
                    completeAndAdvance(
                        current,
                        LinkStatus.REQUESTED,
                        LinkResultCode.REQUEST_SENT,
                        "Join request is pending; continuity handoff opened the next invitation",
                        fastAdvance = true
                    )
                    return@launch
                }
''',
    '''                if (inspection.requestSubmitted) {
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
''',
    already="X/Back handoff opened the next invitation"
)

# ---------------------------------------------------------------------------
# 8) Validator update.
# ---------------------------------------------------------------------------
validator_rel = "scripts/validate_source.py"
validator = read(validator_rel)
validator = validator.replace(
    '"versionCode 321": "versionCode = 321" in build,',
    '"versionCode 330": "versionCode = 330" in build,'
)
validator = validator.replace(
    '"versionName 3.2.1": \'versionName = "3.2.1"\' in build,',
    '"versionName 3.3.0": \'versionName = "3.3.0"\' in build,'
)

if '"3.3 screen awake runtime"' not in validator:
    anchor = '''    "3.2 request-sheet close rescue": "looksLikeRequestSheetCloseCandidate" in service and "requestSheetCorner" in shizuku_service,
'''
    insertion = anchor + '''    "3.3 screen awake runtime": "keepScreenAwake" in preferences_source and
        (JAVA / "com/althmany/groupmanager/util/AutomationScreenAwakeGuard.kt").exists() and
        "keepScreenAwakeSwitch" in settings_layout and
        "AutomationScreenAwakeGuard" in service and
        "AutomationScreenAwakeGuard" in shizuku_service,
    "3.3 request X Back continuity": "fastExitPendingRequestSurface(screen: ScreenInspection): Boolean" in service and
        "X/Back handoff opened the next invitation" in service and
        "REQUEST_SENT_SHEET_BACK_FALLBACK" in shizuku_service,
    "3.3 secure classifier": "never relabel Work/Island/Dual Messenger as Secure Folder" in main_activity and
        "secure_remote_knox_hidden" in main_activity,
    "3.3 evidence counter repair": "visualExpectedAction" in shizuku_service and
        "not counted as a false request" in shizuku_service and
        "Fast watchdog recovered a verified exact-user WhatsApp conversation after Join" in shizuku_service,
'''
    if anchor not in validator:
        raise SystemExit("ERROR: Could not find validator 3.2 insertion anchor.")
    validator = validator.replace(anchor, insertion, 1)

write(validator_rel, validator)

# ---------------------------------------------------------------------------
# 9) Source sanity.
# ---------------------------------------------------------------------------
required = {
    "app/build.gradle.kts": ['versionCode = 330', 'versionName = "3.3.0"'],
    prefs_rel: ["var keepScreenAwake: Boolean", 'KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake"'],
    settings_layout_rel: ['android:id="@+id/keepScreenAwakeSwitch"'],
    settings_rel: ["metrics.widthPixels * 0.72f", "binding.keepScreenAwakeSwitch"],
    main_rel: [
        "never relabel Work/Island/Dual Messenger as Secure Folder",
        "R.string.secure_remote_knox_hidden",
        "RestrictionHandlingMode.SKIP_AND_CONTINUE",
    ],
    shizuku_rel: [
        "AutomationScreenAwakeGuard",
        "visualExpectedAction",
        "not counted as a false request",
        "Fast watchdog recovered a verified exact-user WhatsApp conversation after Join",
    ],
    acc_rel: [
        "AutomationScreenAwakeGuard",
        "fastExitPendingRequestSurface(screen: ScreenInspection): Boolean",
        "Accessibility UI acquisition stayed unavailable after the action",
        "X/Back handoff opened the next invitation",
    ],
    validator_rel: ["versionCode 330", "versionName 3.3.0", "3.3 screen awake runtime"],
}

missing: list[str] = []
for rel, tokens in required.items():
    source = read(rel)
    for token in tokens:
        if token not in source:
            missing.append(f"{rel}: {token}")

if missing:
    raise SystemExit("ERROR: Post-patch sanity failed:\n" + "\n".join(missing))

print()
print("============================================================")
print(" Al-othmany Sender 3.3.0 patch applied successfully")
print("============================================================")
print("Changed files:")
for rel in changed:
    print(" -", rel)

print()
print("Included:")
print(" - Keep screen awake during active automation")
print(" - Much smaller Settings panel")
print(" - Request flow: X -> verify -> Back fallback -> next")
print(" - Bounded root/UI-loss recovery instead of endless waiting")
print(" - Continuity mode auto-resume + Skip/Continue restrictions")
print(" - Better JOINED counter recovery using exact WhatsApp Conversation proof")
print(" - No false REQUESTED result from ambiguous visual fallback")
print(" - Secure Folder picker rejects Island/Work/Dual App false matches")
print()
print("NEXT:")
print("  python3 scripts/validate_source.py")
print("  ./gradlew :app:assembleDebug")
print()
print("If both pass:")
print("  git add .")
print('  git commit -m "Al-othmany Sender 3.3.0 continuity awake counter secure"')
print("  git push origin main")
