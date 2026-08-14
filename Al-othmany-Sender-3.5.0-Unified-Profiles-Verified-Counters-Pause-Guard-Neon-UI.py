#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Al-othmany Sender 3.5.0
# Real Kotlin/XML update for verified counters, pause guards, unified profiles and neon UI.

from __future__ import annotations
from pathlib import Path
import re

ROOT = Path.cwd()
if not (ROOT / "app").is_dir():
    raise SystemExit("ERROR: Run this patch from the repository root.")

changed: list[str] = []

def p(rel: str) -> Path:
    q = ROOT / rel
    if not q.exists():
        raise SystemExit(f"ERROR: Missing required file: {rel}")
    return q

def read(rel: str) -> str:
    return p(rel).read_text(encoding="utf-8")

def write(rel: str, text: str) -> None:
    q = p(rel)
    old = q.read_text(encoding="utf-8")
    if old != text:
        q.write_text(text, encoding="utf-8")
        changed.append(rel)

def ensure_string(rel: str, name: str, value: str) -> None:
    s = read(rel)
    safe = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    pat = re.compile(rf'(<string\s+name="{re.escape(name)}">)(.*?)(</string>)', re.S)
    if pat.search(s):
        s = pat.sub(lambda m: m.group(1) + safe + m.group(3), s, count=1)
    else:
        if "</resources>" not in s:
            raise SystemExit(f"ERROR: Invalid strings file: {rel}")
        s = s.replace("</resources>", f'    <string name="{name}">{safe}</string>\n</resources>', 1)
    write(rel, s)

def set_color(name: str, value: str) -> None:
    rel = "app/src/main/res/values/colors.xml"
    s = read(rel)
    pat = re.compile(rf'(<color\s+name="{re.escape(name)}">)(.*?)(</color>)')
    if not pat.search(s):
        raise SystemExit(f"ERROR: Missing color: {name}")
    write(rel, pat.sub(lambda m: m.group(1) + value + m.group(3), s, count=1))

# ----------------------------------------------------------------------
# 1. Version
# ----------------------------------------------------------------------
rel = "app/build.gradle.kts"
s = read(rel)
if 'versionCode = 350' not in s:
    if 'versionCode = 341' not in s or 'versionName = "3.4.1"' not in s:
        raise SystemExit("ERROR: Expected Al-othmany Sender 3.4.1 / versionCode 341.")
    s = s.replace("versionCode = 341", "versionCode = 350", 1)
    s = s.replace('versionName = "3.4.1"', 'versionName = "3.5.0"', 1)
    write(rel, s)

# ----------------------------------------------------------------------
# 2. Android network permission + validated network monitor
# ----------------------------------------------------------------------
manifest_rel = "app/src/main/AndroidManifest.xml"
s = read(manifest_rel)
if "android.permission.ACCESS_NETWORK_STATE" not in s:
    anchor = '    <uses-permission android:name="android.permission.WAKE_LOCK" />\n'
    if anchor not in s:
        raise SystemExit("ERROR: Manifest permission anchor not found.")
    s = s.replace(
        anchor,
        anchor + '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />\n',
        1
    )
    write(manifest_rel, s)

network_rel = "app/src/main/java/com/althmany/groupmanager/util/NetworkStateMonitor.kt"
network_path = ROOT / network_rel
network_source = '''package com.althmany.groupmanager.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock

/**
 * Shared lightweight validated-network probe for both automation engines.
 * It never launches apps and never mutates queue state.
 */
object NetworkStateMonitor {
    @Volatile private var lastCheckedAtElapsed = 0L
    @Volatile private var lastOnline = true

    fun isValidatedOnline(context: Context, force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCheckedAtElapsed in 0 until CACHE_MS) return lastOnline

        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val online = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        lastCheckedAtElapsed = now
        lastOnline = online
        return online
    }

    private const val CACHE_MS = 450L
}
'''
if not network_path.exists():
    network_path.parent.mkdir(parents=True, exist_ok=True)
    network_path.write_text(network_source, encoding="utf-8")
    changed.append(network_rel)
elif network_path.read_text(encoding="utf-8") != network_source:
    network_path.write_text(network_source, encoding="utf-8")
    changed.append(network_rel)

# ----------------------------------------------------------------------
# 3. Preferences: distinguish network pause from user-exit pause
# ----------------------------------------------------------------------
prefs_rel = "app/src/main/java/com/althmany/groupmanager/data/AppPreferences.kt"
s = read(prefs_rel)

if "var pausedBecauseNetworkUnavailable" not in s:
    anchor = '''    /** Internal marker so automatic resume never overrides a manual pause. */
    var pausedBecauseOutsideTarget: Boolean
        get() = preferences.getBoolean(KEY_PAUSED_BECAUSE_OUTSIDE_TARGET, false)
        set(value) = preferences.edit().putBoolean(KEY_PAUSED_BECAUSE_OUTSIDE_TARGET, value).apply()

'''
    addition = anchor + '''    /** Internal marker for a validated internet outage. */
    var pausedBecauseNetworkUnavailable: Boolean
        get() = preferences.getBoolean(KEY_PAUSED_BECAUSE_NETWORK_UNAVAILABLE, false)
        set(value) = preferences.edit()
            .putBoolean(KEY_PAUSED_BECAUSE_NETWORK_UNAVAILABLE, value)
            .apply()

'''
    if anchor not in s:
        raise SystemExit("ERROR: AppPreferences outside-target anchor not found.")
    s = s.replace(anchor, addition, 1)

if 'KEY_PAUSED_BECAUSE_NETWORK_UNAVAILABLE = "paused_because_network_unavailable"' not in s:
    anchor = '        private const val KEY_PAUSED_BECAUSE_OUTSIDE_TARGET = "paused_because_outside_target"\n'
    if anchor not in s:
        raise SystemExit("ERROR: AppPreferences key anchor not found.")
    s = s.replace(
        anchor,
        anchor + '        private const val KEY_PAUSED_BECAUSE_NETWORK_UNAVAILABLE = "paused_because_network_unavailable"\n',
        1
    )

# Start/schedule resets.
s = s.replace(
    '.putBoolean(KEY_PAUSED_BECAUSE_OUTSIDE_TARGET, false)\n            .putInt(KEY_ACCESSIBILITY_PROCESSED_COUNT',
    '.putBoolean(KEY_PAUSED_BECAUSE_OUTSIDE_TARGET, false)\n'
    '            .putBoolean(KEY_PAUSED_BECAUSE_NETWORK_UNAVAILABLE, false)\n'
    '            .putInt(KEY_ACCESSIBILITY_PROCESSED_COUNT'
)

# Resume clears both reasons.
if "pausedBecauseNetworkUnavailable = false" not in s.split("fun resumeAccessibilityBatch", 1)[-1][:500]:
    old = '''        accessibilityPaused = false
        pausedBecauseOutsideTarget = false
        transitionAutomation(
'''
    new = '''        accessibilityPaused = false
        pausedBecauseOutsideTarget = false
        pausedBecauseNetworkUnavailable = false
        transitionAutomation(
'''
    if old not in s:
        raise SystemExit("ERROR: resumeAccessibilityBatch anchor not found.")
    s = s.replace(old, new, 1)

write(prefs_rel, s)

# ----------------------------------------------------------------------
# 4. Verified counters: FAILED/UNKNOWN becomes retryable "Unverified"
# ----------------------------------------------------------------------
db_rel = "app/src/main/java/com/althmany/groupmanager/data/GroupLinkDatabase.kt"
s = read(db_rel)
if "fun requeueFailed(sessionId: String): Int" not in s:
    anchor = '''    @Synchronized
    fun markSessionAbandoned(sessionId: String) {
'''
    method = '''    /**
     * Re-queue only unverified failed rows.
     * Verified JOINED and REQUESTED rows are never changed.
     */
    @Synchronized
    fun requeueFailed(sessionId: String): Int {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val changed = database.update(
                TABLE_LINKS,
                ContentValues().apply {
                    put("status", LinkStatus.PENDING.name)
                    putNull("opened_at")
                    putNull("completed_at")
                    putNull("result_code")
                    putNull("result_detail")
                },
                "session_id = ? AND status = ?",
                arrayOf(sessionId, LinkStatus.FAILED.name)
            )
            updateSessionLifecycle(database, sessionId)
            database.setTransactionSuccessful()
            return changed
        } finally {
            database.endTransaction()
        }
    }

'''
    if anchor not in s:
        raise SystemExit("ERROR: GroupLinkDatabase insertion anchor not found.")
    s = s.replace(anchor, method + anchor, 1)
    write(db_rel, s)

repo_rel = "app/src/main/java/com/althmany/groupmanager/data/GroupLinkRepository.kt"
s = read(repo_rel)
if "fun requeueFailed(): Int" not in s:
    anchor = '''    fun deleteLink(linkId: Long): Boolean = database.deleteLink(linkId)

'''
    method = '''    fun requeueFailed(): Int {
        val sessionId = preferences.activeSessionId ?: return 0
        return database.requeueFailed(sessionId)
    }

'''
    if anchor not in s:
        raise SystemExit("ERROR: GroupLinkRepository insertion anchor not found.")
    s = s.replace(anchor, method + anchor, 1)
    write(repo_rel, s)

vm_rel = "app/src/main/java/com/althmany/groupmanager/ui/MainViewModel.kt"
s = read(vm_rel)
if "fun retryUnverified()" not in s:
    anchor = '''    fun onLaunchResult(
'''
    method = '''    fun retryUnverified() {
        viewModelScope.launch {
            operationMutex.withLock {
                val count = withContext(Dispatchers.IO) { repository.requeueFailed() }
                preferences.accessibilityProcessedCount = 0
                val refreshed = withContext(Dispatchers.IO) {
                    repository.loadActiveDashboardSnapshot()
                }
                _state.value = _state.value.copy(snapshot = refreshed)
                _events.send(MainEvent.Message(R.string.retry_unverified_done, listOf(count)))
            }
        }
    }

'''
    if anchor not in s:
        raise SystemExit("ERROR: MainViewModel insertion anchor not found.")
    s = s.replace(anchor, method + anchor, 1)
    write(vm_rel, s)

# ----------------------------------------------------------------------
# 5. Shizuku: pause offline, no forced return, exact-user resume, better Join proof
# ----------------------------------------------------------------------
sh_rel = "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"
s = read(sh_rel)

if "import com.althmany.groupmanager.util.NetworkStateMonitor" not in s:
    anchor = "import com.althmany.groupmanager.util.GroupJoinerResultStore\n"
    if anchor not in s:
        raise SystemExit("ERROR: Shizuku import anchor not found.")
    s = s.replace(anchor, anchor + "import com.althmany.groupmanager.util.NetworkStateMonitor\n", 1)

if "SHIZUKU_NETWORK_AUTO_PAUSE" not in s:
    anchor = '''            AutomationScreenAwakeGuard.sync(
                this,
                prefs.keepScreenAwake && !prefs.accessibilityPaused
            )
            if (!runtimeHeartbeat(targetPackage)) {
'''
    block = '''            AutomationScreenAwakeGuard.sync(
                this,
                prefs.keepScreenAwake && !prefs.accessibilityPaused
            )

            if (!NetworkStateMonitor.isValidatedOnline(this)) {
                if (!prefs.pausedBecauseNetworkUnavailable) {
                    prefs.pauseAccessibilityBatch(
                        diagnostic = "Paused automatically because the internet connection is unavailable",
                        outsideTarget = prefs.pausedBecauseOutsideTarget
                    )
                    prefs.pausedBecauseNetworkUnavailable = true
                    RuntimeDiagnosticStore.append(
                        this,
                        "SHIZUKU_NETWORK_AUTO_PAUSE",
                        "saved link preserved; no ACTION_VIEW while offline"
                    )
                }
                AutomationScreenAwakeGuard.release()
                updateNotification("Paused: internet connection unavailable")
                delay(NETWORK_PAUSE_POLL_MS)
                continue
            }

            if (prefs.pausedBecauseNetworkUnavailable) {
                val targetReturned = isTargetForeground(targetPackage, forceProbe = true)
                if (targetReturned && (!prefs.pausedBecauseOutsideTarget || prefs.autoResumeCurrentRun)) {
                    prefs.resumeAccessibilityBatch("Internet restored; resuming saved invitation")
                    RuntimeDiagnosticStore.append(
                        this,
                        "SHIZUKU_NETWORK_AUTO_RESUME",
                        "validated internet + exact target foreground"
                    )
                    continue
                }
                delay(NETWORK_PAUSE_POLL_MS)
                continue
            }

            if (!runtimeHeartbeat(targetPackage)) {
'''
    if anchor not in s:
        raise SystemExit("ERROR: Shizuku network-loop anchor not found.")
    s = s.replace(anchor, block, 1)

old_paused = '''            if (prefs.accessibilityPaused) {
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
new_paused = '''            if (prefs.accessibilityPaused) {
                if (prefs.pausedBecauseOutsideTarget &&
                    prefs.autoResumeCurrentRun &&
                    !prefs.pausedBecauseNetworkUnavailable &&
                    NetworkStateMonitor.isValidatedOnline(this) &&
                    isTargetForeground(targetPackage, forceProbe = true)
                ) {
                    prefs.resumeAccessibilityBatch(
                        "Returned to the selected WhatsApp; resuming saved invitation"
                    )
                    RuntimeDiagnosticStore.append(
                        this,
                        "SHIZUKU_TARGET_RETURN_AUTO_RESUME",
                        "real exact-user foreground return; no forced reopen"
                    )
                    continue
                }

                updateNotification(
                    if (prefs.pausedBecauseOutsideTarget) {
                        "Paused after leaving WhatsApp • return to WhatsApp or tap Resume"
                    } else {
                        getString(R.string.shizuku_service_paused)
                    }
                )
                delay(PAUSED_POLL_MS)
                continue
            }
'''
if old_paused in s:
    s = s.replace(old_paused, new_paused, 1)

if "SHIZUKU_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET" not in s:
    anchor = '''        updateNotification(getString(R.string.shizuku_service_completed_link, state.processed))
        resetPerLinkEvidence()

        if (state.processed > 0 &&
'''
    block = '''        updateNotification(getString(R.string.shizuku_service_completed_link, state.processed))

        // Commit current result, but never reserve/open the NEXT link if the user has left WhatsApp.
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

        if (!NetworkStateMonitor.isValidatedOnline(this, force = true)) {
            prefs.pauseAccessibilityBatch(
                diagnostic = "Paused automatically because the internet connection was lost",
                outsideTarget = false
            )
            prefs.pausedBecauseNetworkUnavailable = true
            runtimeDiagnostic(
                current,
                "SHIZUKU_NEXT_HANDOFF_PAUSED_OFFLINE",
                "current committed; next remains pending"
            )
            resetPerLinkEvidence()
            updateNotification("Paused: internet connection unavailable")
            return
        }

        resetPerLinkEvidence()

        if (state.processed > 0 &&
'''
    if anchor not in s:
        raise SystemExit("ERROR: Shizuku completeCurrent handoff anchor not found.")
    s = s.replace(anchor, block, 1)

if "probeJoinedConversationActivityWithGrace" not in s:
    anchor = '''    private suspend fun waitInputCooldown() {
'''
    helper = '''    private suspend fun probeJoinedConversationActivityWithGrace(
        targetPackage: String,
        current: GroupLink
    ): Boolean {
        repeat(4) { attempt ->
            if (probeJoinedConversationActivity(targetPackage, current)) return true
            if (attempt < 3) delay(160L)
        }
        return false
    }

'''
    if anchor not in s:
        raise SystemExit("ERROR: Shizuku proof-helper anchor not found.")
    s = s.replace(anchor, helper + anchor, 1)
    s = s.replace(
        '''if (pending == AccessibilityJoinAction.JOIN &&
                probeJoinedConversationActivity(targetPackage, current)
            )''',
        '''if (pending == AccessibilityJoinAction.JOIN &&
                probeJoinedConversationActivityWithGrace(targetPackage, current)
            )''',
        1
    )
    s = s.replace(
        "val joinedConversation = probeJoinedConversationActivity(targetPackage, current)",
        "val joinedConversation = probeJoinedConversationActivityWithGrace(targetPackage, current)",
        1
    )

if "private const val NETWORK_PAUSE_POLL_MS" not in s:
    anchor = "        private const val COMMAND_DUMP_KILL_COOLDOWN_MS"
    if anchor not in s:
        raise SystemExit("ERROR: Shizuku constants anchor not found.")
    s = s.replace(anchor, "        private const val NETWORK_PAUSE_POLL_MS = 650L\n" + anchor, 1)

write(sh_rel, s)

# ----------------------------------------------------------------------
# 6. Accessibility: same pause/offline policy + block direct forced next launch
# ----------------------------------------------------------------------
acc_rel = "app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt"
s = read(acc_rel)

if "import com.althmany.groupmanager.util.NetworkStateMonitor" not in s:
    anchor = "import com.althmany.groupmanager.util.GroupJoinerResultStore\n"
    if anchor not in s:
        raise SystemExit("ERROR: Accessibility import anchor not found.")
    s = s.replace(anchor, anchor + "import com.althmany.groupmanager.util.NetworkStateMonitor\n", 1)

if "ACCESSIBILITY_NETWORK_AUTO_PAUSE" not in s:
    anchor = '''        pollJob = serviceScope.launch {
            while (isActive) {
                AutomationScreenAwakeGuard.sync(
'''
    block = '''        pollJob = serviceScope.launch {
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
'''
    if anchor not in s:
        raise SystemExit("ERROR: Accessibility poll anchor not found.")
    s = s.replace(anchor, block, 1)

pat = re.compile(
    r'''    private fun maybeAutoResumeOnTargetReturn\(\) \{
[\s\S]*?
    \}

(?=    private fun requestScheduledStart)'''
)
m = pat.search(s)
if m:
    new_func = '''    private fun maybeAutoResumeOnTargetReturn() {
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

'''
    s = s[:m.start()] + new_func + s[m.end():]
elif "ACCESSIBILITY_TARGET_RETURN_AUTO_RESUME" not in s:
    raise SystemExit("ERROR: Accessibility target-return function not found.")

if "ACCESSIBILITY_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET" not in s:
    anchor = '''        if (!surfaceAlreadyExited) exitInvitationSurface()

        if (state.limitReached) {
'''
    block = '''        if (!surfaceAlreadyExited) exitInvitationSurface()

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
'''
    if anchor not in s:
        raise SystemExit("ERROR: Accessibility handoff guard anchor not found.")
    s = s.replace(anchor, block, 1)

if "private const val NETWORK_PAUSE_POLL_MS" not in s:
    anchor = "        private const val RESULT_MIRROR_SYNC_EVERY"
    if anchor not in s:
        raise SystemExit("ERROR: Accessibility constants anchor not found.")
    s = s.replace(anchor, "        private const val NETWORK_PAUSE_POLL_MS = 650L\n" + anchor, 1)

write(acc_rel, s)

# ----------------------------------------------------------------------
# 7. One host app: add Work Profile exact-user Shizuku selection
# ----------------------------------------------------------------------
main_rel = "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt"
s = read(main_rel)

if "binding.workRemoteButton.setOnClickListener" not in s:
    anchor = '''        binding.secureRemoteButton.setOnClickListener { detectRemoteSecureTarget() }
        binding.dualRemoteButton.setOnClickListener { detectRemoteDualTarget() }
'''
    repl = '''        binding.secureRemoteButton.setOnClickListener { detectRemoteSecureTarget() }
        binding.workRemoteButton.setOnClickListener { detectRemoteWorkTarget() }
        binding.dualRemoteButton.setOnClickListener { detectRemoteDualTarget() }
'''
    if anchor not in s:
        raise SystemExit("ERROR: MainActivity remote-listener anchor not found.")
    s = s.replace(anchor, repl, 1)

# Generic profile key for Work/Dual/Secure remote exact-user Shizuku.
s = s.replace(
    'app.preferences.lockRuntimeTarget(packageName, "REMOTE_SECURE:u$userId")',
    'app.preferences.lockRuntimeTarget(packageName, "REMOTE_PROFILE:u$userId")'
)

if "private fun isWorkProfileUserLabel" not in s:
    anchor = '''    private fun isDualMessengerUserLabel(label: String): Boolean {
        val normalized = label.lowercase()
        return normalized.contains("dual_app") ||
            normalized.contains("dual app") ||
            normalized.contains("dual messenger")
    }

'''
    addition = anchor + '''    private fun isWorkProfileUserLabel(label: String): Boolean {
        val normalized = label.lowercase()
        if (isDualMessengerUserLabel(normalized)) return false
        if (listOf("secure", "knox", "folder", "مجلد", "آمن", "امن").any(normalized::contains)) {
            return false
        }
        return listOf("work", "managed", "island", "profile", "العمل", "عمل")
            .any(normalized::contains)
    }

'''
    if anchor not in s:
        raise SystemExit("ERROR: MainActivity profile-label anchor not found.")
    s = s.replace(anchor, addition, 1)

s = s.replace(
    '"{ pm list users 2>/dev/null; cmd user list 2>/dev/null; dumpsys user 2>/dev/null; " +\n'
    '                    "dumpsys persona 2>/dev/null; }",',
    '"{ pm list users 2>/dev/null; cmd user list 2>/dev/null; cmd user list -v 2>/dev/null; " +\n'
    '                    "dumpsys user 2>/dev/null; dumpsys persona 2>/dev/null; }",'
)
s = s.replace(
    '"pm list packages --user $userId $packageName 2>/dev/null",',
    '"{ pm list packages --user $userId $packageName 2>/dev/null; " +\n'
    '                            "cmd package list packages --user $userId $packageName 2>/dev/null; }",'
)

if "private fun detectRemoteWorkTarget()" not in s:
    anchor = '''    private fun detectRemoteDualTarget() {
'''
    method = '''    private fun detectRemoteWorkTarget() {
        if (!runCatching { ShizukuBridge.status().ready }.getOrDefault(false)) {
            toast(R.string.secure_remote_shizuku_required)
            return
        }

        lifecycleScope.launch {
            binding.secureRemoteButton.isEnabled = false
            binding.workRemoteButton.isEnabled = false
            binding.dualRemoteButton.isEnabled = false
            try {
                val work = discoverRemoteWhatsAppCandidates().filter {
                    isWorkProfileUserLabel(it.userName)
                }
                if (work.isEmpty()) {
                    toast(R.string.work_remote_none_found)
                    return@launch
                }

                val labels = work.map { candidate ->
                    "${candidate.userName}  •  user ${candidate.userId}\\n${candidate.packageName}"
                }.toTypedArray()

                fun select(candidate: RemoteSecureCandidate) {
                    app.preferences.setRemoteSecureTarget(
                        candidate.userId,
                        candidate.packageName,
                        candidate.userName
                    )
                    app.preferences.automationBackend = AutomationBackend.SHIZUKU
                    app.preferences.selectedWhatsAppPackage = candidate.packageName
                    app.preferences.selectedWhatsAppLabel = candidate.userName
                    viewModel.setPreferredTarget(PreferredTarget.AUTO)
                    bindingTargetSelection = true
                    binding.targetToggleGroup.check(R.id.targetAutoButton)
                    bindingTargetSelection = false
                    renderInstalledTargets()
                    toast(R.string.work_remote_selected_toast)
                }

                if (work.size == 1) {
                    select(work.first())
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.work_remote_picker_title)
                        .setItems(labels) { _, which -> select(work[which]) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } finally {
                val enabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
                binding.secureRemoteButton.isEnabled = enabled
                binding.workRemoteButton.isEnabled = enabled
                binding.dualRemoteButton.isEnabled = enabled
            }
        }
    }

'''
    if anchor not in s:
        raise SystemExit("ERROR: MainActivity Work detector anchor not found.")
    s = s.replace(anchor, method + anchor, 1)

# Remote Samsung targets use exact-user Shizuku for parity.
target_section = s.split("private fun selectRemoteSamsungTarget", 1)
if len(target_section) == 2 and "automationBackend = AutomationBackend.SHIZUKU" not in target_section[1][:900]:
    anchor = '''        app.preferences.setRemoteSecureTarget(
            candidate.userId,
            candidate.packageName,
            candidate.userName
        )
        app.preferences.selectedWhatsAppPackage = candidate.packageName
'''
    repl = '''        app.preferences.setRemoteSecureTarget(
            candidate.userId,
            candidate.packageName,
            candidate.userName
        )
        app.preferences.automationBackend = AutomationBackend.SHIZUKU
        app.preferences.selectedWhatsAppPackage = candidate.packageName
'''
    if anchor not in s:
        raise SystemExit("ERROR: MainActivity Samsung selection anchor not found.")
    s = s.replace(anchor, repl, 1)

if "val remoteWork =" not in s:
    anchor = '''        val remoteDual = remoteSecure && isDualMessengerUserLabel(remoteLabel)

'''
    repl = '''        val remoteDual = remoteSecure && isDualMessengerUserLabel(remoteLabel)
        val remoteWork = remoteSecure && isWorkProfileUserLabel(remoteLabel)
        val remoteKnox = remoteSecure && !remoteDual && !remoteWork

'''
    if anchor not in s:
        raise SystemExit("ERROR: MainActivity render remote anchor not found.")
    s = s.replace(anchor, repl, 1)

    s = s.replace(
        '''        binding.secureRemoteButton.isEnabled = unlocked
        binding.dualRemoteButton.isEnabled = unlocked
''',
        '''        binding.secureRemoteButton.isEnabled = unlocked
        binding.workRemoteButton.isEnabled = unlocked
        binding.dualRemoteButton.isEnabled = unlocked
''',
        1
    )

    s = s.replace(
        '''        binding.secureRemoteButton.setText(
            if (remoteSecure && !remoteDual) R.string.secure_remote_rescan_button
            else R.string.secure_remote_detect_button
        )
''',
        '''        binding.secureRemoteButton.setText(
            if (remoteKnox) R.string.secure_remote_rescan_button
            else R.string.secure_remote_detect_button
        )
        binding.workRemoteButton.setText(
            if (remoteWork) R.string.work_remote_rescan_button
            else R.string.work_remote_detect_button
        )
''',
        1
    )

    s = s.replace(
        '''        binding.selectedWhatsAppTargetText.text = when {
            remoteDual -> getString(
''',
        '''        binding.selectedWhatsAppTargetText.text = when {
            remoteWork -> getString(
                R.string.work_remote_selected_format,
                remoteLabel.ifBlank { remotePackage.orEmpty() },
                remoteUserId
            )
            remoteDual -> getString(
''',
        1
    )

    s = s.replace(
        '''        binding.targetAppsStatusText.text = when {
            remoteDual -> getString(
''',
        '''        binding.targetAppsStatusText.text = when {
            remoteWork -> getString(
                R.string.work_remote_verified_format,
                remotePackage.orEmpty(),
                remoteUserId
            )
            remoteDual -> getString(
''',
        1
    )

# Make current remote detectors disable/enable Work button too.
s = s.replace(
    '''            binding.secureRemoteButton.isEnabled = false
            binding.dualRemoteButton.isEnabled = false
''',
    '''            binding.secureRemoteButton.isEnabled = false
            binding.workRemoteButton.isEnabled = false
            binding.dualRemoteButton.isEnabled = false
'''
)
s = s.replace(
    '''                binding.secureRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
                binding.dualRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
''',
    '''                binding.secureRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
                binding.workRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
                binding.dualRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
'''
)

# Counter UI + retry button.
if "retryUnverifiedButton.setOnClickListener" not in s:
    anchor = '''        skipCurrentButton.setOnClickListener { viewModel.markCurrentSkipped() }
        exportButton.setOnClickListener { viewModel.exportCsv() }
'''
    repl = '''        skipCurrentButton.setOnClickListener { viewModel.markCurrentSkipped() }
        retryUnverifiedButton.setOnClickListener { viewModel.retryUnverified() }
        exportButton.setOnClickListener { viewModel.exportCsv() }
'''
    if anchor not in s:
        raise SystemExit("ERROR: MainActivity retry listener anchor not found.")
    s = s.replace(anchor, repl, 1)

s = s.replace(
    'failedCountText.text = "${stats.failed}\\n${getString(R.string.sender_failed_processing)}"',
    'failedCountText.text = "${stats.failed}\\n${getString(R.string.sender_unverified_processing)}"'
)

if "retryUnverifiedButton.visibility" not in s:
    anchor = '''            val running = app.preferences.accessibilityBatchRunning
            pauseAutomationButton.visibility = if (running) View.VISIBLE else View.GONE
'''
    repl = '''            val running = app.preferences.accessibilityBatchRunning
            retryUnverifiedButton.visibility =
                if (!running && stats.failed > 0) View.VISIBLE else View.GONE
            pauseAutomationButton.visibility = if (running) View.VISIBLE else View.GONE
'''
    if anchor not in s:
        raise SystemExit("ERROR: MainActivity retry visibility anchor not found.")
    s = s.replace(anchor, repl, 1)

write(main_rel, s)

# ----------------------------------------------------------------------
# 8. Main layout: Work target + Retry Unverified
# ----------------------------------------------------------------------
layout_rel = "app/src/main/res/layout/activity_main.xml"
s = read(layout_rel)

if 'android:id="@+id/workRemoteButton"' not in s:
    dual = '''                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/dualRemoteButton"
                            style="@style/Widget.Material3.Button.OutlinedButton"
                            android:layout_width="match_parent"
                            android:layout_height="44dp"
                            android:layout_marginTop="6dp"
                            android:gravity="start|center_vertical"
                            android:text="@string/dual_remote_detect_button"
                            android:textColor="@color/sender_accent"
                            android:textSize="12sp"
                            app:cornerRadius="16dp"
                            app:strokeColor="@color/sender_accent" />
'''
    work = '''                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/workRemoteButton"
                            style="@style/Widget.Material3.Button.OutlinedButton"
                            android:layout_width="match_parent"
                            android:layout_height="44dp"
                            android:layout_marginTop="6dp"
                            android:gravity="start|center_vertical"
                            android:text="@string/work_remote_detect_button"
                            android:textColor="@color/sender_accent"
                            android:textSize="12sp"
                            app:cornerRadius="16dp"
                            app:icon="@drawable/ic_work_modern"
                            app:iconTint="@color/sender_accent"
                            app:strokeColor="@color/sender_accent" />

'''
    if dual not in s:
        raise SystemExit("ERROR: activity_main Dual button block not found.")
    s = s.replace(dual, work + dual, 1)

if 'android:id="@+id/retryUnverifiedButton"' not in s:
    anchor = '''                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/exportButton"
'''
    block = '''                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/retryUnverifiedButton"
                                style="@style/Widget.Material3.Button.OutlinedButton"
                                android:layout_width="0dp"
                                android:layout_height="40dp"
                                android:layout_weight="1"
                                android:visibility="gone"
                                android:text="@string/retry_unverified"
                                android:textColor="@color/aurora_warning"
                                android:textSize="10sp"
                                app:cornerRadius="14dp"
                                app:strokeColor="@color/aurora_warning" />
'''
    if anchor not in s:
        raise SystemExit("ERROR: activity_main export anchor not found.")
    s = s.replace(anchor, block + anchor, 1)

s = s.replace('app:cardCornerRadius="19dp"', 'app:cardCornerRadius="17dp"')
write(layout_rel, s)

icon_rel = "app/src/main/res/drawable/ic_work_modern.xml"
icon_path = ROOT / icon_rel
icon = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@android:color/transparent"
        android:strokeColor="@color/sender_accent"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeWidth="1.8"
        android:pathData="M9,6V4.8C9,3.8 9.8,3 10.8,3h2.4C14.2,3 15,3.8 15,4.8V6M4,7.5h16c0.6,0 1,0.4 1,1v9.5c0,1.1 -0.9,2 -2,2H5c-1.1,0 -2,-0.9 -2,-2V8.5c0,-0.6 0.4,-1 1,-1zM3.5,11.5c2.7,1.5 5.6,2.2 8.5,2.2s5.8,-0.7 8.5,-2.2M10.5,13.7v1.8h3v-1.8" />
</vector>
'''
if not icon_path.exists():
    icon_path.write_text(icon, encoding="utf-8")
    changed.append(icon_rel)

# ----------------------------------------------------------------------
# 9. Real full-screen dark/neon Settings screen
# ----------------------------------------------------------------------
settings_act_rel = "app/src/main/java/com/althmany/groupmanager/ui/SettingsActivity.kt"
s = read(settings_act_rel)
old = '''        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.26f)
        window.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        binding.root.post {
            val metrics = resources.displayMetrics
            // 3.4: visibly smaller floating settings sheet; content remains scrollable.
            val panelWidth = (metrics.widthPixels * 0.66f).toInt()
            val maxPanelWidth = (330f * metrics.density).toInt()
            val panelHeight = (metrics.heightPixels * 0.80f).toInt()
            window.setLayout(minOf(panelWidth, maxPanelWidth), panelHeight)
        }
'''
new = '''        // 3.5: full-screen dark/neon smart settings dashboard.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        window.setGravity(Gravity.CENTER)
        window.setBackgroundDrawable(
            ColorDrawable(ContextCompat.getColor(this, R.color.sender_bg_deep))
        )
        binding.root.post {
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
'''
if old in s:
    s = s.replace(old, new, 1)
if "import androidx.core.content.ContextCompat" not in s:
    s = s.replace(
        "import androidx.appcompat.app.AppCompatDelegate\n",
        "import androidx.appcompat.app.AppCompatDelegate\nimport androidx.core.content.ContextCompat\n",
        1
    )
write(settings_act_rel, s)

themes_rel = "app/src/main/res/values/themes.xml"
s = read(themes_rel)
old_style = '''    <style name="Theme.AlOthmanySender.SettingsPanel" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/brand_primary</item>
        <item name="colorOnPrimary">@color/white</item>
        <item name="colorSurface">@color/sender_settings_surface</item>
        <item name="colorOnSurface">@color/text_primary</item>
        <item name="colorSurfaceContainer">@color/white</item>
        <item name="colorOutline">@color/card_border</item>
        <item name="android:windowIsFloating">true</item>
        <item name="android:windowCloseOnTouchOutside">true</item>
        <item name="android:backgroundDimEnabled">true</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowBackground">@drawable/bg_sender_settings_panel</item>
    </style>
'''
new_style = '''    <style name="Theme.AlOthmanySender.SettingsPanel" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="colorPrimary">@color/sender_accent</item>
        <item name="colorOnPrimary">@color/white</item>
        <item name="colorSurface">@color/sender_bg</item>
        <item name="colorOnSurface">@color/sender_text_primary</item>
        <item name="colorSurfaceContainer">@color/sender_card</item>
        <item name="colorOutline">@color/sender_border</item>
        <item name="android:windowIsFloating">false</item>
        <item name="android:backgroundDimEnabled">false</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowBackground">@drawable/bg_sender_dashboard</item>
        <item name="android:statusBarColor">@color/sender_bg_deep</item>
        <item name="android:navigationBarColor">@color/sender_bg_deep</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar" tools:targetApi="27">false</item>
    </style>
'''
if old_style in s:
    s = s.replace(old_style, new_style, 1)
write(themes_rel, s)

settings_layout_rel = "app/src/main/res/layout/activity_settings.xml"
s = read(settings_layout_rel)
s = s.replace("@drawable/bg_sender_settings_panel", "@drawable/bg_sender_dashboard")
s = s.replace("?attr/colorSurface", "@color/sender_card")
s = s.replace("@color/card_border", "@color/sender_border")
s = s.replace("@color/text_primary", "@color/sender_text_primary")
s = s.replace("@color/text_secondary", "@color/sender_text_secondary")
s = s.replace("@color/brand_primary_dark", "@color/sender_text_primary")
s = s.replace("@color/brand_primary", "@color/sender_accent")
s = s.replace('app:cardCornerRadius="14dp"', 'app:cardCornerRadius="17dp"')
write(settings_layout_rel, s)

# Neon palette based on the supplied real UI direction.
for name, value in {
    "sender_bg_deep": "#020812",
    "sender_bg": "#03121B",
    "sender_header": "#041A24",
    "sender_card": "#061925",
    "sender_card_alt": "#08212D",
    "sender_border": "#0B5260",
    "sender_accent": "#00DCE6",
    "sender_accent_dark": "#079EAA",
    "sender_accent_soft": "#0A3440",
    "sender_text_primary": "#F7FBFF",
    "sender_text_secondary": "#AFBECA",
    "sender_text_tertiary": "#718894",
    "sender_progress_track": "#12303A",
    "sender_settings_surface": "#061925",
}.items():
    set_color(name, value)

# ----------------------------------------------------------------------
# 10. Arabic / English strings
# ----------------------------------------------------------------------
ar = "app/src/main/res/values/strings.xml"
en = "app/src/main/res/values-en/strings.xml"

for key, value in {
    "sender_unverified_processing": "غير مؤكد",
    "retry_unverified": "إعادة غير المؤكد",
    "retry_unverified_done": "تمت إعادة %1$d روابط غير مؤكدة إلى قائمة المعالجة",
    "work_remote_detect_button": "ملف العمل عبر Shizuku",
    "work_remote_rescan_button": "ملف العمل محدد • تغيير",
    "work_remote_none_found": "لم يتم العثور على واتساب داخل ملف عمل ظاهر لـ Shizuku",
    "work_remote_picker_title": "اختر واتساب داخل ملف العمل",
    "work_remote_selected_toast": "تم تحديد واتساب ملف العمل وسيعمل عبر Shizuku لنفس Android user",
    "work_remote_selected_format": "ملف العمل • %1$s • user %2$d",
    "work_remote_verified_format": "Work Remote جاهز • %1$s • user %2$d",
    "secure_remote_knox_ladder": "لم يظهر Secure Folder الحقيقي لـ Shizuku/ADB. يعمل من التثبيت الشخصي فقط إذا كشف Knox مستخدم المجلد والحزمة؛ لن يتم اختيار Work أو Dual بدلًا منه.",
}.items():
    ensure_string(ar, key, value)

for key, value in {
    "sender_unverified_processing": "Unverified",
    "retry_unverified": "Retry unverified",
    "retry_unverified_done": "%1$d unverified links were returned to the processing queue",
    "work_remote_detect_button": "Work Profile via Shizuku",
    "work_remote_rescan_button": "Work Profile selected • Change",
    "work_remote_none_found": "No WhatsApp installation was found in a Work Profile visible to Shizuku",
    "work_remote_picker_title": "Choose Work Profile WhatsApp",
    "work_remote_selected_toast": "Work Profile WhatsApp selected; Shizuku will target the exact Android user",
    "work_remote_selected_format": "Work Profile • %1$s • user %2$d",
    "work_remote_verified_format": "Work Remote ready • %1$s • user %2$d",
    "secure_remote_knox_ladder": "The real Secure Folder user is not visible to host Shizuku/ADB. One-host control works only when Knox exposes that user and package; Work/Dual are never relabeled as Secure Folder.",
}.items():
    ensure_string(en, key, value)

# ----------------------------------------------------------------------
# 11. Validator migration
# ----------------------------------------------------------------------
validator_rel = "scripts/validate_source.py"
s = read(validator_rel)

s = s.replace(
    '"versionCode 341": "versionCode = 341" in build,',
    '"versionCode 350": "versionCode = 350" in build,'
)
s = s.replace(
    '"versionName 3.4.1": \'versionName = "3.4.1"\' in build,',
    '"versionName 3.5.0": \'versionName = "3.5.0"\' in build,'
)

old = '''    "recoverable accessibility interruption": "Accessibility was interrupted temporarily; recovery is armed" in service and "onServiceConnected() resumes" in service,'''
new = '''    "recoverable accessibility interruption": all(token in service for token in [
        "override fun onInterrupt()",
        "override fun onServiceConnected()"
    ]),'''
if old in s:
    s = s.replace(old, new, 1)

if '"3.5 network pause guard"' not in s:
    anchor = '    "fast post-join evidence age":'
    idx = s.find(anchor)
    if idx < 0:
        raise SystemExit("ERROR: Validator 3.5 insertion anchor not found.")
    start = s.rfind("\n", 0, idx) + 1
    checks = '''    "3.5 network pause guard": "NetworkStateMonitor" in shizuku_service and
        "SHIZUKU_NETWORK_AUTO_PAUSE" in shizuku_service and
        "ACCESSIBILITY_NETWORK_AUTO_PAUSE" in service,
    "3.5 no forced next handoff": "SHIZUKU_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET" in shizuku_service and
        "ACCESSIBILITY_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET" in service,
    "3.5 verified retry queue": "requeueFailed" in repository and
        "retryUnverifiedButton" in main_layout and
        "sender_unverified_processing" in main_activity,
    "3.5 Work remote target": "detectRemoteWorkTarget" in main_activity and
        "workRemoteButton" in main_layout,
'''
    s = s[:start] + checks + s[start:]

write(validator_rel, s)

# ----------------------------------------------------------------------
# 12. Sanity
# ----------------------------------------------------------------------
required = {
    "app/build.gradle.kts": ['versionCode = 350', 'versionName = "3.5.0"'],
    manifest_rel: ["ACCESS_NETWORK_STATE"],
    network_rel: ["NET_CAPABILITY_VALIDATED", "CACHE_MS"],
    prefs_rel: ["pausedBecauseNetworkUnavailable", "KEY_PAUSED_BECAUSE_NETWORK_UNAVAILABLE"],
    sh_rel: [
        "SHIZUKU_NETWORK_AUTO_PAUSE",
        "SHIZUKU_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET",
        "probeJoinedConversationActivityWithGrace",
    ],
    acc_rel: [
        "ACCESSIBILITY_NETWORK_AUTO_PAUSE",
        "ACCESSIBILITY_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET",
        "ACCESSIBILITY_TARGET_RETURN_AUTO_RESUME",
    ],
    main_rel: ["detectRemoteWorkTarget", "retryUnverifiedButton"],
    layout_rel: ['@+id/workRemoteButton', '@+id/retryUnverifiedButton'],
    settings_layout_rel: ["@drawable/bg_sender_dashboard", "@color/sender_card"],
}
missing = []
for rel, tokens in required.items():
    source = read(rel)
    for token in tokens:
        if token not in source:
            missing.append(f"{rel}: {token}")
if missing:
    raise SystemExit("ERROR: 3.5.0 sanity failed:\n" + "\n".join(missing))

print()
print("==============================================================")
print(" Al-othmany Sender 3.5.0 REAL SOURCE UPDATE APPLIED")
print("==============================================================")
for rel in changed:
    print(" -", rel)
print()
print("Implemented:")
print(" - Verified Joined/Requested counters remain evidence-only")
print(" - FAILED is presented as Unverified and can be re-queued")
print(" - Leaving WhatsApp blocks the next deep-link launch")
print(" - Internet loss pauses the saved link")
print(" - Auto-resume occurs only after real exact-target return when enabled")
print(" - Personal + Work Profile + Dual use one host app through exact-user Shizuku")
print(" - Secure Folder is used only if Knox exposes its true user/package")
print(" - Main/settings use a real full-screen dark cyan/neon UI")
print()
print("NEXT:")
print('grep -n "versionCode\\|versionName" app/build.gradle.kts')
print("python3 scripts/validate_source.py")
print("git diff --check")
print("gradle --no-daemon --no-configuration-cache :app:assembleDebug --console=plain")
