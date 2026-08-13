#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Al-othmany Sender 3.2.0
# Secure Remote + Request-X + Speed Control + Counter + Community + Compact UI patch
#
# Apply from repository root:
#   python3 Al-othmany-Sender-3.2.0-Secure-Speed-Precision.py
#
# The patch preserves Personal/Work engines. Secure Folder Remote is opt-in,
# uses the already-running host Shizuku, and stops safely if Knox blocks access.

from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path.cwd()
if not (ROOT / "app").is_dir():
    raise SystemExit("ERROR: Run this file from the Android repository root.")

changed: list[str] = []
notes: list[str] = []

def path(rel: str) -> Path:
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"ERROR: Missing required file: {rel}")
    return p

def read(rel: str) -> str:
    return path(rel).read_text(encoding="utf-8")

def write(rel: str, text: str) -> None:
    p = path(rel)
    old = p.read_text(encoding="utf-8")
    if old != text:
        p.write_text(text, encoding="utf-8")
        changed.append(rel)

def replace_once(rel: str, old: str, new: str, *, already: str | None = None) -> None:
    s = read(rel)
    if already and already in s:
        notes.append(f"already applied: {rel}")
        return
    count = s.count(old)
    if count != 1:
        raise SystemExit(
            f"ERROR: Expected exactly one anchor in {rel}, found {count}.\n"
            f"ANCHOR:\n{old[:500]}"
        )
    write(rel, s.replace(old, new, 1))

def add_resources(rel: str, marker: str, snippet: str) -> None:
    s = read(rel)
    if marker in s:
        notes.append(f"resources already applied: {rel}")
        return
    if "</resources>" not in s:
        raise SystemExit(f"ERROR: Invalid resources file: {rel}")
    write(rel, s.replace("</resources>", snippet.rstrip() + "\n</resources>", 1))

# ---------------------------------------------------------------------------
# 1) Version 3.2.0
# ---------------------------------------------------------------------------
build_rel = "app/build.gradle.kts"
s = read(build_rel)
s2 = re.sub(r'versionCode\s*=\s*310\b', 'versionCode = 320', s, count=1)
s2 = re.sub(r'versionName\s*=\s*"3\.1\.0"', 'versionName = "3.2.0"', s2, count=1)
if s2 == s:
    if 'versionCode = 320' not in s or 'versionName = "3.2.0"' not in s:
        raise SystemExit("ERROR: Could not upgrade build.gradle.kts from 3.1.0 to 3.2.0")
else:
    write(build_rel, s2)

# ---------------------------------------------------------------------------
# 2) Remote Secure Folder preferences.
# ---------------------------------------------------------------------------
prefs_rel = "app/src/main/java/com/althmany/groupmanager/data/AppPreferences.kt"

prefs_block = r'''
    /**
     * Remote Secure Folder target.
     *
     * Al-othmany stays in the host Android user where Shizuku is available.
     * The Shizuku engine may target one explicitly selected secondary/Knox user.
     * The target is never guessed during automation and is re-verified by the service.
     */
    var remoteSecureFolderEnabled: Boolean
        get() = preferences.getBoolean(KEY_REMOTE_SECURE_ENABLED, false)
        private set(value) = preferences.edit().putBoolean(KEY_REMOTE_SECURE_ENABLED, value).apply()

    var remoteSecureAndroidUserId: Int
        get() = preferences.getInt(KEY_REMOTE_SECURE_USER_ID, -1)
        private set(value) = preferences.edit().putInt(KEY_REMOTE_SECURE_USER_ID, value).apply()

    var remoteSecureWhatsAppPackage: String?
        get() = preferences.getString(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE, null)
        private set(value) = preferences.edit()
            .apply {
                if (value.isNullOrBlank()) remove(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE)
                else putString(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE, value)
            }
            .apply()

    var remoteSecureUserLabel: String?
        get() = preferences.getString(KEY_REMOTE_SECURE_USER_LABEL, null)
        private set(value) = preferences.edit()
            .apply {
                if (value.isNullOrBlank()) remove(KEY_REMOTE_SECURE_USER_LABEL)
                else putString(KEY_REMOTE_SECURE_USER_LABEL, value.take(80))
            }
            .apply()

    fun setRemoteSecureTarget(userId: Int, packageName: String, label: String?) {
        require(userId >= 0) { "Remote Secure user id must be non-negative." }
        require(packageName in setOf("com.whatsapp", "com.whatsapp.w4b", "com.whatsapp2")) {
            "Unsupported Remote Secure WhatsApp package."
        }
        preferences.edit()
            .putBoolean(KEY_REMOTE_SECURE_ENABLED, true)
            .putInt(KEY_REMOTE_SECURE_USER_ID, userId)
            .putString(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE, packageName)
            .putString(KEY_REMOTE_SECURE_USER_LABEL, label?.take(80).orEmpty())
            .remove(KEY_RUNTIME_LOCKED_ANDROID_USER_ID)
            .remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            .remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            .apply()
    }

    fun clearRemoteSecureTarget() {
        preferences.edit()
            .putBoolean(KEY_REMOTE_SECURE_ENABLED, false)
            .remove(KEY_REMOTE_SECURE_USER_ID)
            .remove(KEY_REMOTE_SECURE_WHATSAPP_PACKAGE)
            .remove(KEY_REMOTE_SECURE_USER_LABEL)
            .remove(KEY_RUNTIME_LOCKED_ANDROID_USER_ID)
            .remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            .remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            .apply()
    }

    fun hasValidRemoteSecureTarget(): Boolean =
        remoteSecureFolderEnabled &&
            remoteSecureAndroidUserId >= 0 &&
            remoteSecureWhatsAppPackage in setOf("com.whatsapp", "com.whatsapp.w4b", "com.whatsapp2")

'''

prefs_anchor = '    var strictProfileTargeting: Boolean\n'
s = read(prefs_rel)
if "fun setRemoteSecureTarget(" not in s:
    idx = s.find(prefs_anchor)
    if idx < 0:
        raise SystemExit("ERROR: AppPreferences strictProfileTargeting anchor not found")
    write(prefs_rel, s[:idx] + prefs_block + s[idx:])

replace_once(
    prefs_rel,
    '        private const val KEY_STRICT_PROFILE_TARGETING = "strict_profile_targeting"\n',
    '''        private const val KEY_REMOTE_SECURE_ENABLED = "remote_secure_enabled"
        private const val KEY_REMOTE_SECURE_USER_ID = "remote_secure_user_id"
        private const val KEY_REMOTE_SECURE_WHATSAPP_PACKAGE = "remote_secure_whatsapp_package"
        private const val KEY_REMOTE_SECURE_USER_LABEL = "remote_secure_user_label"
        private const val KEY_STRICT_PROFILE_TARGETING = "strict_profile_targeting"
''',
    already='private const val KEY_REMOTE_SECURE_ENABLED = "remote_secure_enabled"'
)

# ---------------------------------------------------------------------------
# 3) Add Secure Remote button and compact the dashboard.
# ---------------------------------------------------------------------------
layout_rel = "app/src/main/res/layout/activity_main.xml"
layout = read(layout_rel)
if 'android:id="@+id/secureRemoteButton"' not in layout:
    pattern = r'(<com\.google\.android\.material\.button\.MaterialButton\s+android:id="@\+id/testWhatsAppTargetButton"[\s\S]*?/>)'
    m = re.search(pattern, layout)
    if not m:
        raise SystemExit("ERROR: testWhatsAppTargetButton view not found")
    secure_button = r'''

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/secureRemoteButton"
                            style="@style/Widget.Material3.Button.OutlinedButton"
                            android:layout_width="match_parent"
                            android:layout_height="46dp"
                            android:layout_marginTop="6dp"
                            android:gravity="start|center_vertical"
                            android:text="@string/secure_remote_detect_button"
                            android:textColor="@color/sender_accent"
                            android:textSize="12sp"
                            app:cornerRadius="16dp"
                            app:icon="@drawable/ic_settings_modern"
                            app:iconTint="@color/sender_accent"
                            app:strokeColor="@color/sender_accent" />'''
    layout = layout[:m.end()] + secure_button + layout[m.end():]

layout = layout.replace('android:layout_height="92dp"', 'android:layout_height="84dp"', 1)
layout = layout.replace('android:paddingHorizontal="12dp"', 'android:paddingHorizontal="10dp"', 1)
layout = layout.replace('android:paddingTop="12dp"', 'android:paddingTop="8dp"', 1)
layout = layout.replace('app:cardCornerRadius="22dp"', 'app:cardCornerRadius="19dp"')
write(layout_rel, layout)

# ---------------------------------------------------------------------------
# 4) Arabic + English resources.
# ---------------------------------------------------------------------------
ar = r'''
    <!-- Al-othmany Sender 3.2 Secure Remote -->
    <string name="secure_remote_detect_button">المجلد الآمن عبر Shizuku الخارجي</string>
    <string name="secure_remote_rescan_button">المجلد الآمن محدد • تغيير</string>
    <string name="secure_remote_shizuku_required">شغّل Shizuku خارج المجلد الآمن وامنح Al-othmany الإذن أولًا.</string>
    <string name="secure_remote_none_found">لم يتم العثور على WhatsApp في مستخدم Android آخر. تأكد أنه مثبت داخل المجلد الآمن.</string>
    <string name="secure_remote_picker_title">اختر مستخدم المجلد الآمن</string>
    <string name="secure_remote_selected_format">المجلد الآمن • %1$s • user %2$d</string>
    <string name="secure_remote_verified_format">Secure Remote جاهز • %1$s • user %2$d</string>
    <string name="secure_remote_selected_toast">تم تحديد واتساب المجلد الآمن. هذه الجولة ستستخدم Shizuku الخارجي فقط.</string>
    <string name="secure_remote_clear_action">إلغاء وضع المجلد الآمن</string>
    <string name="secure_remote_cleared_toast">تم إلغاء وضع المجلد الآمن.</string>
    <string name="sender_session_counter_format">%1$d / %2$d</string>
'''
en = r'''
    <!-- Al-othmany Sender 3.2 Secure Remote -->
    <string name="secure_remote_detect_button">Secure Folder via host Shizuku</string>
    <string name="secure_remote_rescan_button">Secure Folder selected • Change</string>
    <string name="secure_remote_shizuku_required">Start Shizuku outside Secure Folder and authorize Al-othmany first.</string>
    <string name="secure_remote_none_found">No WhatsApp installation was found in another Android user. Make sure it is installed inside Secure Folder.</string>
    <string name="secure_remote_picker_title">Choose Secure Folder user</string>
    <string name="secure_remote_selected_format">Secure Folder • %1$s • user %2$d</string>
    <string name="secure_remote_verified_format">Secure Remote ready • %1$s • user %2$d</string>
    <string name="secure_remote_selected_toast">Secure Folder WhatsApp selected. This run will use host Shizuku only.</string>
    <string name="secure_remote_clear_action">Disable Secure Folder mode</string>
    <string name="secure_remote_cleared_toast">Secure Folder mode disabled.</string>
    <string name="sender_session_counter_format">%1$d / %2$d</string>
'''
add_resources("app/src/main/res/values/strings.xml", 'name="secure_remote_detect_button"', ar)
add_resources("app/src/main/res/values-en/strings.xml", 'name="secure_remote_detect_button"', en)

# ---------------------------------------------------------------------------
# 5) MainActivity: discovery, exact target locking, speed control and counter.
# ---------------------------------------------------------------------------
main_rel = "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt"

replace_once(
    main_rel,
    '''        binding.chooseInstalledWhatsAppButton.setOnClickListener { showInstalledWhatsAppPicker() }
        binding.testWhatsAppTargetButton.setOnClickListener { testSelectedWhatsAppTarget() }
        binding.communityTraversalSwitch.isChecked = app.preferences.communityTraversalEnabled
''',
    '''        binding.chooseInstalledWhatsAppButton.setOnClickListener { showInstalledWhatsAppPicker() }
        binding.testWhatsAppTargetButton.setOnClickListener { testSelectedWhatsAppTarget() }
        binding.secureRemoteButton.setOnClickListener { detectRemoteSecureTarget() }
        binding.secureRemoteButton.setOnLongClickListener {
            if (app.preferences.remoteSecureFolderEnabled) {
                app.preferences.clearRemoteSecureTarget()
                renderInstalledTargets()
                toast(R.string.secure_remote_cleared_toast)
            }
            true
        }
        binding.communityTraversalSwitch.isChecked = app.preferences.communityTraversalEnabled
''',
    already="binding.secureRemoteButton.setOnClickListener { detectRemoteSecureTarget() }"
)

# Changing a local target cancels the remote target.
s = read(main_rel)
old = '''            app.preferences.selectedWhatsAppPackage = null
            app.preferences.selectedWhatsAppLabel = null
            viewModel.setPreferredTarget(target)
'''
new = '''            app.preferences.clearRemoteSecureTarget()
            app.preferences.selectedWhatsAppPackage = null
            app.preferences.selectedWhatsAppLabel = null
            viewModel.setPreferredTarget(target)
'''
if old in s:
    write(main_rel, s.replace(old, new, 1))

# Explicit Start now respects the selected speed profile instead of silently forcing FAST.
replace_once(
    main_rel,
    '''        app.preferences.autoPauseOutsideWhatsApp = true
        app.preferences.interLinkDelayMs = AutomationPolicy.FAST_INTER_LINK_DELAY_MS
        app.preferences.accessibilityActionTimeoutSeconds = AutomationPolicy.FAST_ACTION_TIMEOUT_SECONDS
''',
    '''        app.preferences.autoPauseOutsideWhatsApp = true
        // 3.2: the selected RuntimeSpeedProfile is the single cadence source.
        app.preferences.interLinkDelayMs = app.preferences.runtimeSpeedProfile().interLinkDelayMs.toInt()
        app.preferences.accessibilityActionTimeoutSeconds = AutomationPolicy.FAST_ACTION_TIMEOUT_SECONDS
''',
    already="// 3.2: the selected RuntimeSpeedProfile is the single cadence source."
)

# Secure Remote always uses the host Shizuku backend.
replace_once(
    main_rel,
    '''    private fun resolveAutomationBackendForStart(): AutomationBackend? {
        val requested = app.preferences.automationBackend
''',
    '''    private fun resolveAutomationBackendForStart(): AutomationBackend? {
        if (app.preferences.hasValidRemoteSecureTarget()) {
            if (runCatching { ShizukuBridge.status().ready }.getOrDefault(false)) {
                return AutomationBackend.SHIZUKU
            }
            toast(R.string.secure_remote_shizuku_required)
            startActivity(Intent(this, SettingsActivity::class.java))
            return null
        }

        val requested = app.preferences.automationBackend
''',
    already="if (app.preferences.hasValidRemoteSecureTarget())"
)

# Readiness in Secure Remote means Shizuku, never local Accessibility.
replace_once(
    main_rel,
    '''    private fun isAnyAutomationEngineReady(): Boolean {
        val accessibilityReady =
            AccessibilityStatus.isQuickJoinServiceConnectedLocally(this@MainActivity)
''',
    '''    private fun isAnyAutomationEngineReady(): Boolean {
        if (app.preferences.hasValidRemoteSecureTarget()) {
            return runCatching { ShizukuBridge.status().ready }.getOrDefault(false)
        }
        val accessibilityReady =
            AccessibilityStatus.isQuickJoinServiceConnectedLocally(this@MainActivity)
''',
    already="return runCatching { ShizukuBridge.status().ready }.getOrDefault(false)\n        }\n        val accessibilityReady"
)

replace_once(
    main_rel,
    '''    private fun isSelectedTargetAvailable(target: PreferredTarget): Boolean =
        WhatsAppLauncher.validateTarget(
            this,
            target,
            app.preferences.selectedWhatsAppPackage
        ).valid
''',
    '''    private fun isSelectedTargetAvailable(target: PreferredTarget): Boolean {
        if (app.preferences.hasValidRemoteSecureTarget()) return true
        return WhatsAppLauncher.validateTarget(
            this,
            target,
            app.preferences.selectedWhatsAppPackage
        ).valid
    }
''',
    already="if (app.preferences.hasValidRemoteSecureTarget()) return true"
)

replace_once(
    main_rel,
    '''    private fun lockValidatedRuntimeTarget(): Boolean {
        val validation = WhatsAppLauncher.validateTarget(
''',
    '''    private fun lockValidatedRuntimeTarget(): Boolean {
        if (app.preferences.hasValidRemoteSecureTarget()) {
            val userId = app.preferences.remoteSecureAndroidUserId
            val packageName = app.preferences.remoteSecureWhatsAppPackage ?: return false
            app.preferences.lockRuntimeTarget(packageName, "REMOTE_SECURE:u$userId")
            return app.preferences.lockRuntimeAndroidUserId(userId)
        }

        val validation = WhatsAppLauncher.validateTarget(
''',
    already='"REMOTE_SECURE:u$userId"'
)

remote_methods = r'''
    private data class RemoteSecureCandidate(
        val userId: Int,
        val userName: String,
        val packageName: String
    )

    private fun detectRemoteSecureTarget() {
        if (!runCatching { ShizukuBridge.status().ready }.getOrDefault(false)) {
            toast(R.string.secure_remote_shizuku_required)
            return
        }

        lifecycleScope.launch {
            binding.secureRemoteButton.isEnabled = false
            try {
                val usersResult = withContext(Dispatchers.IO) {
                    ShizukuBridge.execute(this@MainActivity, "pm list users", 3_000)
                }
                if (!usersResult.success) {
                    toast(R.string.secure_remote_none_found)
                    return@launch
                }

                val userRegex = Regex("UserInfo\\{([0-9]+):([^:}]*)")
                val userNames = userRegex.findAll(usersResult.output)
                    .mapNotNull { match ->
                        val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                        id to match.groupValues[2].trim()
                    }
                    .toMap()

                val scan = listOf(
                    "for u in \$(pm list users | sed -n 's/.*UserInfo{\\([0-9][0-9]*\\):.*/\\1/p'); do",
                    "for p in com.whatsapp com.whatsapp.w4b com.whatsapp2; do",
                    "pm list packages --user \$u \$p 2>/dev/null | grep -qx \"package:\$p\" && echo \"\$u|\$p\";",
                    "done; done"
                ).joinToString(" ")

                val packageResult = withContext(Dispatchers.IO) {
                    ShizukuBridge.execute(this@MainActivity, scan, 6_000)
                }
                val hostUserId = android.os.Process.myUid() / 100000
                val candidates = packageResult.output.lineSequence()
                    .map(String::trim)
                    .mapNotNull { line ->
                        val parts = line.split('|')
                        if (parts.size != 2) return@mapNotNull null
                        val userId = parts[0].toIntOrNull() ?: return@mapNotNull null
                        val packageName = parts[1]
                        if (userId == hostUserId ||
                            packageName !in setOf("com.whatsapp", "com.whatsapp.w4b", "com.whatsapp2")
                        ) return@mapNotNull null
                        RemoteSecureCandidate(
                            userId = userId,
                            userName = userNames[userId].orEmpty().ifBlank { "Android user $userId" },
                            packageName = packageName
                        )
                    }
                    .distinctBy { "${it.userId}:${it.packageName}" }
                    .toList()

                if (candidates.isEmpty()) {
                    toast(R.string.secure_remote_none_found)
                    return@launch
                }

                val strongSecure = candidates.filter { candidate ->
                    val name = candidate.userName.lowercase()
                    listOf("secure", "knox", "folder", "مجلد", "آمن", "امن").any(name::contains)
                }
                val choices = if (strongSecure.isNotEmpty()) strongSecure else candidates

                if (choices.size == 1) {
                    selectRemoteSecureTarget(choices.first())
                } else {
                    val labels = choices.map { candidate ->
                        "${candidate.userName}  •  user ${candidate.userId}\n${candidate.packageName}"
                    }.toTypedArray()
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.secure_remote_picker_title)
                        .setItems(labels) { _, which ->
                            selectRemoteSecureTarget(choices[which])
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } finally {
                binding.secureRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
            }
        }
    }

    private fun selectRemoteSecureTarget(candidate: RemoteSecureCandidate) {
        app.preferences.setRemoteSecureTarget(
            candidate.userId,
            candidate.packageName,
            candidate.userName
        )
        app.preferences.selectedWhatsAppPackage = candidate.packageName
        app.preferences.selectedWhatsAppLabel = candidate.userName
        viewModel.setPreferredTarget(PreferredTarget.AUTO)
        bindingTargetSelection = true
        binding.targetToggleGroup.check(R.id.targetAutoButton)
        bindingTargetSelection = false
        renderInstalledTargets()
        toast(R.string.secure_remote_selected_toast)
    }

'''
s = read(main_rel)
if "private data class RemoteSecureCandidate(" not in s:
    anchor = "    private fun installedMark(installed: Boolean): String =\n"
    idx = s.find(anchor)
    if idx < 0:
        raise SystemExit("ERROR: installedMark anchor not found in MainActivity")
    write(main_rel, s[:idx] + remote_methods + s[idx:])

# Add remote variables inside renderInstalledTargets().
s = read(main_rel)
render_anchor = '''        val validation = WhatsAppLauncher.validateTarget(
            this,
            app.preferences.preferredTarget,
            app.preferences.selectedWhatsAppPackage
        )

'''
if "val remoteSecure = app.preferences.hasValidRemoteSecureTarget()" not in s:
    if render_anchor not in s:
        raise SystemExit("ERROR: renderInstalledTargets validation anchor not found")
    s = s.replace(
        render_anchor,
        render_anchor + '''        val remoteSecure = app.preferences.hasValidRemoteSecureTarget()
        val remoteUserId = app.preferences.remoteSecureAndroidUserId
        val remotePackage = app.preferences.remoteSecureWhatsAppPackage

''',
        1
    )
    write(main_rel, s)

replace_once(
    main_rel,
    '        binding.testWhatsAppTargetButton.isEnabled = unlocked && validation.packageName != null\n',
    '''        binding.testWhatsAppTargetButton.isEnabled =
            unlocked && !remoteSecure && validation.packageName != null
        binding.secureRemoteButton.isEnabled = unlocked
        binding.secureRemoteButton.setText(
            if (remoteSecure) R.string.secure_remote_rescan_button
            else R.string.secure_remote_detect_button
        )
''',
    already="binding.secureRemoteButton.setText("
)

replace_once(
    main_rel,
    '''        binding.selectedWhatsAppTargetText.text = when {
            !selectedPackage.isNullOrBlank() -> getString(
''',
    '''        binding.selectedWhatsAppTargetText.text = when {
            remoteSecure -> getString(
                R.string.secure_remote_selected_format,
                app.preferences.remoteSecureUserLabel ?: remotePackage.orEmpty(),
                remoteUserId
            )
            !selectedPackage.isNullOrBlank() -> getString(
''',
    already="R.string.secure_remote_selected_format"
)

replace_once(
    main_rel,
    '''        binding.targetAppsStatusText.visibility = android.view.View.VISIBLE
        binding.targetAppsStatusText.text = if (validation.valid) {
            getString(R.string.profile_target_verified, validation.packageName ?: "")
        } else {
            getString(R.string.profile_target_not_verified)
        }
''',
    '''        binding.targetAppsStatusText.visibility = android.view.View.VISIBLE
        binding.targetAppsStatusText.text = when {
            remoteSecure -> getString(
                R.string.secure_remote_verified_format,
                remotePackage.orEmpty(),
                remoteUserId
            )
            validation.valid -> getString(R.string.profile_target_verified, validation.packageName ?: "")
            else -> getString(R.string.profile_target_not_verified)
        }
''',
    already="R.string.secure_remote_verified_format"
)

# Local picker choices clear Remote Secure.
s = read(main_rel)
s = s.replace(
    '''                    app.preferences.selectedWhatsAppPackage = null
                    app.preferences.selectedWhatsAppLabel = null
                    viewModel.setPreferredTarget(PreferredTarget.AUTO)
''',
    '''                    app.preferences.clearRemoteSecureTarget()
                    app.preferences.selectedWhatsAppPackage = null
                    app.preferences.selectedWhatsAppLabel = null
                    viewModel.setPreferredTarget(PreferredTarget.AUTO)
''',
    1
)
s = s.replace(
    '''                app.preferences.selectedWhatsAppPackage = target.packageName
                app.preferences.selectedWhatsAppLabel = target.label
''',
    '''                app.preferences.clearRemoteSecureTarget()
                app.preferences.selectedWhatsAppPackage = target.packageName
                app.preferences.selectedWhatsAppLabel = target.label
''',
    1
)
write(main_rel, s)

# Visible session counter is now completed / total.
replace_once(
    main_rel,
    '''            val stats = snapshot.stats
            sessionProgressIndicator.progress = stats.progressPercent
''',
    '''            val stats = snapshot.stats
            linkCountText.text = getString(
                R.string.sender_session_counter_format,
                stats.completed,
                stats.total
            )
            sessionProgressIndicator.progress = stats.progressPercent
''',
    already="R.string.sender_session_counter_format"
)

replace_once(
    main_rel,
    '''        } else if (!showSession) {
            sessionCard.visibility = View.GONE
        }
        idleCard.visibility = if (snapshot == null) View.VISIBLE else View.GONE
''',
    '''        } else if (!showSession) {
            sessionCard.visibility = View.GONE
            linkCountText.text = getString(
                R.string.autopilot_detected_links_format,
                detectedLinkCount,
                AutomationPolicy.MAX_LINKS_PER_SESSION
            )
        }
        idleCard.visibility = if (snapshot == null) View.VISIBLE else View.GONE
''',
    already="sessionCard.visibility = View.GONE\n            linkCountText.text = getString("
)

# ---------------------------------------------------------------------------
# 6) Shizuku Remote Secure resolution and safe no-fallback behavior.
# ---------------------------------------------------------------------------
shizuku_rel = "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"

replace_once(
    shizuku_rel,
    '''    private suspend fun resolveAndroidUserId(targetPackage: String): Int? {
        if (cachedTargetPackage == targetPackage && cachedAndroidUserId != null) return cachedAndroidUserId
        val appPackage = BuildConfig.APPLICATION_ID
''',
    '''    private suspend fun resolveAndroidUserId(targetPackage: String): Int? {
        val prefs = app.preferences
        val remoteRequested = prefs.hasValidRemoteSecureTarget() &&
            prefs.remoteSecureWhatsAppPackage == targetPackage
        if (cachedTargetPackage == targetPackage && cachedAndroidUserId != null &&
            (!remoteRequested || cachedAndroidUserId == prefs.remoteSecureAndroidUserId)
        ) return cachedAndroidUserId
        val appPackage = BuildConfig.APPLICATION_ID
''',
    already="val remoteRequested = prefs.hasValidRemoteSecureTarget()"
)

remote_resolver_block = r'''
        if (remoteRequested) {
            val remoteUserId = prefs.remoteSecureAndroidUserId
            val verify = ShizukuBridge.execute(
                this,
                "pm list packages --user $remoteUserId ${shellQuote(targetPackage)} 2>/dev/null",
                2_500
            )
            val exactPackagePresent = verify.success && verify.output.lineSequence()
                .map(String::trim)
                .any { it == "package:$targetPackage" }
            if (!exactPackagePresent) {
                RuntimeDiagnosticStore.append(
                    this,
                    "SHIZUKU_REMOTE_SECURE_PACKAGE_MISSING",
                    "user=$remoteUserId; target=$targetPackage; exit=${verify.exitCode}"
                )
                return null
            }
            if (!prefs.lockRuntimeAndroidUserId(remoteUserId)) {
                RuntimeDiagnosticStore.append(
                    this,
                    "SHIZUKU_REMOTE_SECURE_USER_MISMATCH",
                    "expected=${prefs.runtimeLockedAndroidUserId}; requested=$remoteUserId; target=$targetPackage"
                )
                return null
            }
            if (cachedTargetPackage != targetPackage || cachedAndroidUserId != remoteUserId) {
                cachedResolvedActivityUserId = null
                cachedResolvedActivityTargetPackage = null
                cachedResolvedActivityName = null
            }
            cachedTargetPackage = targetPackage
            cachedAndroidUserId = remoteUserId
            RuntimeDiagnosticStore.append(
                this,
                "SHIZUKU_REMOTE_SECURE_USER_READY",
                "hostUid=$processUid; remoteUser=$remoteUserId; target=$targetPackage; capability=package-visible"
            )
            return remoteUserId
        }

'''
s = read(shizuku_rel)
if '"SHIZUKU_REMOTE_SECURE_USER_READY"' not in s:
    anchor = '''        if (!PACKAGE_NAME.matches(appPackage) || !PACKAGE_NAME.matches(targetPackage) || processUid <= 0) return null

        // Do not choose the first Android user'''
    if anchor not in s:
        raise SystemExit("ERROR: resolveAndroidUserId remote insertion anchor not found")
    s = s.replace(
        anchor,
        '''        if (!PACKAGE_NAME.matches(appPackage) || !PACKAGE_NAME.matches(targetPackage) || processUid <= 0) return null

''' + remote_resolver_block + '''        // Do not choose the first Android user''',
        1
    )
    write(shizuku_rel, s)

replace_once(
    shizuku_rel,
    '''    private suspend fun preflightRuntime(targetPackage: String): Boolean {
        if (!ShizukuBridge.status().ready || !ShizukuBridge.ensureBound(this)) {
            if (fallbackToAccessibility("Shizuku is not running, permission is missing, or UserService could not bind")) return false
            stopRun(AutomationStopReason.SERVICE_DISABLED, "Shizuku is not running, permission is missing, or UserService could not bind")
            return false
        }
''',
    '''    private suspend fun preflightRuntime(targetPackage: String): Boolean {
        if (!ShizukuBridge.status().ready || !ShizukuBridge.ensureBound(this)) {
            if (app.preferences.hasValidRemoteSecureTarget()) {
                stopRun(
                    AutomationStopReason.SERVICE_DISABLED,
                    "Remote Secure requires the host Shizuku service; local Accessibility fallback is intentionally disabled"
                )
                return false
            }
            if (fallbackToAccessibility("Shizuku is not running, permission is missing, or UserService could not bind")) return false
            stopRun(AutomationStopReason.SERVICE_DISABLED, "Shizuku is not running, permission is missing, or UserService could not bind")
            return false
        }
''',
    already="Remote Secure requires the host Shizuku service"
)

replace_once(
    shizuku_rel,
    '''        if (userId == null) {
            stopRun(
                AutomationStopReason.TARGET_UNSUPPORTED,
                "Shizuku cannot find AL-thmany and the selected WhatsApp package in the same Android user/profile"
            )
            return false
        }
''',
    '''        if (userId == null) {
            stopRun(
                AutomationStopReason.TARGET_UNSUPPORTED,
                if (app.preferences.hasValidRemoteSecureTarget()) {
                    "Remote Secure target could not be verified. Knox may block this Android user/package from the host Shizuku shell."
                } else {
                    "Shizuku cannot find AL-thmany and the selected WhatsApp package in the same Android user/profile"
                }
            )
            return false
        }
''',
    already="Remote Secure target could not be verified."
)

# User-selected speed controls scroll duration.
replace_once(
    shizuku_rel,
    '''        val persistent = fastUiMode == FastUiMode.ACTIVE &&
            ShizukuBridge.fastSwipe(this, x, startY, x, endY, ShizukuFastUiPolicy.GESTURE_DURATION_MS.toInt())
        val shell = if (!persistent) {
            ShizukuBridge.execute(this, "input swipe $x $startY $x $endY 160", 3_000)
        } else null
''',
    '''        val selectedGestureMs = runtimeSpeed().gestureDurationMs.coerceIn(8L, 72L)
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
''',
    already="val selectedGestureMs = runtimeSpeed().gestureDurationMs"
)

# Request-sent bottom sheet X may be much lower than the physical top of the screen.
old_geometry = '''                val nearTopCorner =
                    (b.centerX <= displayWidth * 28 / 100 ||
                     b.centerX >= displayWidth * 72 / 100) &&
                    b.centerY <= displayHeight * 32 / 100
                val compact = w in 1..(displayWidth * 18 / 100).coerceAtLeast(1) &&
                    h in 1..(displayHeight * 12 / 100).coerceAtLeast(1)
                val roughlySquare = w <= h * 2 && h <= w * 2
                nearTopCorner && compact && roughlySquare
'''
new_geometry = '''                val nearTopCorner =
                    (b.centerX <= displayWidth * 28 / 100 ||
                     b.centerX >= displayWidth * 72 / 100) &&
                    b.centerY <= displayHeight * 32 / 100
                val requestSheetCorner =
                    snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED &&
                    (b.centerX <= displayWidth * 20 / 100 ||
                     b.centerX >= displayWidth * 80 / 100) &&
                    b.centerY in (displayHeight * 26 / 100)..(displayHeight * 78 / 100) &&
                    (node.clickable ||
                     node.className.contains("Image", ignoreCase = true) ||
                     node.resourceId.contains("close", ignoreCase = true) ||
                     node.resourceId.contains("dismiss", ignoreCase = true))
                val compact = w in 1..(displayWidth * 18 / 100).coerceAtLeast(1) &&
                    h in 1..(displayHeight * 12 / 100).coerceAtLeast(1)
                val requestCompact = w in 1..(displayWidth * 14 / 100).coerceAtLeast(1) &&
                    h in 1..(displayHeight * 9 / 100).coerceAtLeast(1)
                val roughlySquare = w <= h * 2 && h <= w * 2
                (nearTopCorner && compact && roughlySquare) ||
                    (requestSheetCorner && requestCompact && roughlySquare)
'''
replace_once(
    shizuku_rel,
    old_geometry,
    new_geometry,
    already="val requestSheetCorner ="
)

# ---------------------------------------------------------------------------
# 7) Accessibility: request-sheet X rescue + selected scroll cadence.
# ---------------------------------------------------------------------------
acc_rel = "app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt"

replace_once(
    acc_rel,
    '''        if ((inviteContext || terminalEvidenceKinds.isNotEmpty()) &&
            !conversationSurface && closeNode == null
        ) {
            closeNode = nodes.asSequence()
                .map { it.node }
                .filter { it.isVisibleToUser && it.isEnabled }
                .filter(::looksLikeTopRightCloseCandidate)
                .maxByOrNull(::visualCloseCandidateScore)
        }
''',
    '''        if ((inviteContext || terminalEvidenceKinds.isNotEmpty()) &&
            !conversationSurface && closeNode == null
        ) {
            val requestTerminal = "REQUEST_SUBMITTED" in terminalEvidenceKinds
            closeNode = nodes.asSequence()
                .map { it.node }
                .filter { it.isVisibleToUser && it.isEnabled }
                .filter { node ->
                    looksLikeTopRightCloseCandidate(node) ||
                        (requestTerminal && looksLikeRequestSheetCloseCandidate(node))
                }
                .maxByOrNull(::visualCloseCandidateScore)
        }
''',
    already="looksLikeRequestSheetCloseCandidate(node)"
)

request_close_func = r'''
    /**
     * Rescue for WhatsApp's request-sent / waiting-for-admin bottom sheet.
     * The X can sit halfway down the physical display. Wide controls are rejected,
     * therefore "Cancel request" can never be selected by this geometry path.
     */
    private fun looksLikeRequestSheetCloseCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        val imageLike = node.className?.toString()?.contains("Image", ignoreCase = true) == true
        val id = node.viewIdResourceName.orEmpty()
        val closeId = id.contains("close", ignoreCase = true) || id.contains("dismiss", ignoreCase = true)
        if (!node.isClickable && node.parent?.isClickable != true && !imageLike && !closeId) return false

        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val nearEdge = bounds.centerX() <= (width * 0.20f).toInt() ||
            bounds.centerX() >= (width * 0.80f).toInt()
        val inSheetTopBand = bounds.centerY() in
            (height * 0.26f).toInt()..(height * 0.78f).toInt()
        val maxWidth = minOf((width * 0.14f).toInt(), dpToPx(96))
        val maxHeight = minOf((height * 0.09f).toInt(), dpToPx(96))
        val compact = bounds.width() in dpToPx(16)..maxWidth &&
            bounds.height() in dpToPx(16)..maxHeight
        val roughlySquare =
            bounds.width() <= bounds.height() * 2 &&
                bounds.height() <= bounds.width() * 2
        return nearEdge && inSheetTopBand && compact && roughlySquare
    }

'''
s = read(acc_rel)
if "private fun looksLikeRequestSheetCloseCandidate(" not in s:
    anchor = "    private fun visualCloseCandidateScore(node: AccessibilityNodeInfo): Int {\n"
    idx = s.find(anchor)
    if idx < 0:
        raise SystemExit("ERROR: Accessibility visualCloseCandidateScore anchor not found")
    write(acc_rel, s[:idx] + request_close_func + s[idx:])

replace_once(
    acc_rel,
    '        val duration = maxOf(72L, runtimeSpeed().gestureDurationMs)\n',
    '        val duration = runtimeSpeed().gestureDurationMs.coerceIn(48L, 96L)\n',
    already="runtimeSpeed().gestureDurationMs.coerceIn(48L, 96L)"
)

# ---------------------------------------------------------------------------
# 8) Community continuity remains bounded but handles larger communities.
# ---------------------------------------------------------------------------
community_rel = "app/src/main/java/com/althmany/groupmanager/domain/CommunityTraversalPolicy.kt"
s = read(community_rel)
s2 = s.replace("const val MAX_GROUPS_PER_COMMUNITY = 256", "const val MAX_GROUPS_PER_COMMUNITY = 512")
s2 = s2.replace("const val MAX_SCROLL_ATTEMPTS = 40", "const val MAX_SCROLL_ATTEMPTS = 80")
s2 = s2.replace("const val GROUP_OPEN_TIMEOUT_MS = 8_000L", "const val GROUP_OPEN_TIMEOUT_MS = 6_000L")
s2 = s2.replace("const val RETURN_TIMEOUT_MS = 6_000L", "const val RETURN_TIMEOUT_MS = 4_500L")
write(community_rel, s2)

# ---------------------------------------------------------------------------
# 9) Smaller white settings side panel.
# ---------------------------------------------------------------------------
settings_rel = "app/src/main/java/com/althmany/groupmanager/ui/SettingsActivity.kt"
replace_once(
    settings_rel,
    '''        window.setDimAmount(0.46f)
        window.setGravity(Gravity.RIGHT)
        binding.root.post {
            val metrics = resources.displayMetrics
            val phoneWidth = (metrics.widthPixels * 0.92f).toInt()
            val maxPanelWidth = (560f * metrics.density).toInt()
            window.setLayout(minOf(phoneWidth, maxPanelWidth), ViewGroup.LayoutParams.MATCH_PARENT)
        }
''',
    '''        window.setDimAmount(0.38f)
        window.setGravity(Gravity.RIGHT)
        binding.root.post {
            val metrics = resources.displayMetrics
            val phoneWidth = (metrics.widthPixels * 0.84f).toInt()
            val maxPanelWidth = (480f * metrics.density).toInt()
            window.setLayout(minOf(phoneWidth, maxPanelWidth), ViewGroup.LayoutParams.MATCH_PARENT)
        }
''',
    already="metrics.widthPixels * 0.84f"
)

# ---------------------------------------------------------------------------
# 10) Validator: version + 3.2 invariants.
# ---------------------------------------------------------------------------
validator_rel = "scripts/validate_source.py"
validator = read(validator_rel)
validator = validator.replace(
    '"versionCode 310": "versionCode = 310" in build,',
    '"versionCode 320": "versionCode = 320" in build,'
)
validator = validator.replace(
    '"versionName 3.1.0": \'versionName = "3.1.0"\' in build,',
    '"versionName 3.2.0": \'versionName = "3.2.0"\' in build,'
)
validator = validator.replace(
    '"community traversal bounded policy": all(token in community_policy for token in ("MAX_GROUPS_PER_COMMUNITY = 256", "MAX_SCROLL_ATTEMPTS = 40", "MAX_RETURN_BACK_STEPS = 3", "GROUP_OPEN_TIMEOUT_MS")),',
    '"community traversal bounded policy": all(token in community_policy for token in ("MAX_GROUPS_PER_COMMUNITY = 512", "MAX_SCROLL_ATTEMPTS = 80", "MAX_RETURN_BACK_STEPS = 3", "GROUP_OPEN_TIMEOUT_MS")),'
)
if '"3.2 Remote Secure host-Shizuku lane"' not in validator:
    needle = '''    "Al-othmany Sender stable package": 'applicationId = "com.althmany.groupmanager"' in build and "Theme.AlOthmanySender.Main" in manifest,
'''
    insertion = needle + '''    "3.2 Remote Secure host-Shizuku lane": all(token in preferences_source for token in [
        "remoteSecureFolderEnabled", "remoteSecureAndroidUserId", "remoteSecureWhatsAppPackage", "setRemoteSecureTarget"
    ]) and all(token in shizuku_service for token in [
        "SHIZUKU_REMOTE_SECURE_USER_READY", "Remote Secure target could not be verified"
    ]) and "secureRemoteButton" in main_layout,
    "3.2 request-sheet close rescue": "looksLikeRequestSheetCloseCandidate" in service and "requestSheetCorner" in shizuku_service,
'''
    if needle not in validator:
        raise SystemExit("ERROR: validator stable package anchor not found")
    validator = validator.replace(needle, insertion, 1)
write(validator_rel, validator)

# ---------------------------------------------------------------------------
# 11) Source-level sanity.
# ---------------------------------------------------------------------------
required = {
    "app/build.gradle.kts": ['versionCode = 320', 'versionName = "3.2.0"'],
    prefs_rel: ["setRemoteSecureTarget", "hasValidRemoteSecureTarget", "KEY_REMOTE_SECURE_USER_ID"],
    main_rel: [
        "secureRemoteButton", "detectRemoteSecureTarget", "REMOTE_SECURE:u$userId",
        "R.string.sender_session_counter_format"
    ],
    shizuku_rel: [
        "SHIZUKU_REMOTE_SECURE_USER_READY", "requestSheetCorner",
        "selectedGestureMs = runtimeSpeed().gestureDurationMs"
    ],
    acc_rel: ["looksLikeRequestSheetCloseCandidate", 'requestTerminal = "REQUEST_SUBMITTED"'],
    layout_rel: ['android:id="@+id/secureRemoteButton"'],
    settings_rel: ["metrics.widthPixels * 0.84f"],
}
missing: list[str] = []
for rel, tokens in required.items():
    text = read(rel)
    for token in tokens:
        if token not in text:
            missing.append(f"{rel}: {token}")
if missing:
    raise SystemExit("ERROR: Post-patch sanity failed:\n" + "\n".join(missing))

print()
print("============================================================")
print(" Al-othmany Sender 3.2.0 patch applied successfully")
print("============================================================")
print("Changed files:")
for rel in changed:
    print(" -", rel)
print()
print("Key upgrades:")
print(" - Remote Secure Folder via host Shizuku + explicit Android user selection")
print(" - No local Accessibility fallback while Remote Secure is selected")
print(" - Request-sent bottom-sheet X rescue in Shizuku and Accessibility")
print(" - Stable/Fast/Turbo/MAX/Custom selection is respected")
print(" - Faster selected-speed scrolling")
print(" - Session counter = completed / total")
print(" - Larger/faster bounded community traversal")
print(" - Smaller settings side panel and lighter dashboard")
print()
print("NEXT:")
print("  python3 scripts/validate_source.py")
print("  ./gradlew :app:assembleDebug")
print()
print("If both pass:")
print("  git add .")
print('  git commit -m "Al-othmany Sender 3.2.0 Secure Remote speed precision"')
print("  git push origin main")
