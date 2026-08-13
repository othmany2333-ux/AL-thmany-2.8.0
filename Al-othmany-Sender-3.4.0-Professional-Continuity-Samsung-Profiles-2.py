#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Al-othmany Sender 3.4.0
# Professional Continuity + Accurate Counters + Samsung Profiles
#
# Base: 3.3.0
#
# Main changes:
# - Long-run Shizuku recovery for exit=137 / killed uiautomator.
# - Prevent UI-tree failures from falsely triggering user-exit pause.
# - Proactive UiAutomation refresh every 100 processed links.
# - Strict Request result flow: explicit pending evidence -> X -> Back fallback -> next.
# - Recognize post-click admin-approval variant only when REQUEST was actually pressed.
# - Accurate Joined / Requested counters based on result evidence.
# - Existing-member links no longer inflate Joined.
# - Accessibility visual fallback no longer invents REQUESTED when action type is unknown.
# - Samsung DUAL_APP remote target support.
# - Multi-source Secure Folder discovery via pm/cmd user/dumpsys user + Knox markers.
# - Smaller Settings panel.
# - Keep-screen-awake support preserved.
# - User-exit pause remains; Auto Resume is no longer forcibly enabled on every run.

from __future__ import annotations

from pathlib import Path
import re

ROOT = Path.cwd()
if not (ROOT / "app").is_dir():
    raise SystemExit("ERROR: Run this patch from the Android repository root.")

changed: list[str] = []

def fp(rel: str) -> Path:
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"ERROR: Missing required file: {rel}")
    return p

def read(rel: str) -> str:
    return fp(rel).read_text(encoding="utf-8")

def write(rel: str, text: str) -> None:
    p = fp(rel)
    old = p.read_text(encoding="utf-8")
    if old != text:
        p.write_text(text, encoding="utf-8")
        changed.append(rel)

def replace_once(rel: str, old: str, new: str, *, already: str | None = None) -> None:
    s = read(rel)
    if already and already in s:
        return
    count = s.count(old)
    if count != 1:
        raise SystemExit(
            f"ERROR: Expected exactly one anchor in {rel}, found {count}.\n"
            f"ANCHOR:\n{old[:900]}"
        )
    write(rel, s.replace(old, new, 1))

def add_resources(rel: str, marker: str, snippet: str) -> None:
    s = read(rel)
    if marker in s:
        return
    if "</resources>" not in s:
        raise SystemExit(f"ERROR: Invalid resources XML: {rel}")
    write(rel, s.replace("</resources>", snippet.rstrip() + "\n</resources>", 1))

# ---------------------------------------------------------------------------
# 1) Version 3.4.0
# ---------------------------------------------------------------------------
build_rel = "app/build.gradle.kts"
s = read(build_rel)
s2 = re.sub(r'versionCode\s*=\s*330\b', 'versionCode = 340', s, count=1)
s2 = re.sub(r'versionName\s*=\s*"3\.3\.0"', 'versionName = "3.4.0"', s2, count=1)
if s2 == s:
    if 'versionCode = 340' not in s or 'versionName = "3.4.0"' not in s:
        raise SystemExit("ERROR: This patch expects Al-othmany Sender 3.3.0.")
else:
    write(build_rel, s2)

# ---------------------------------------------------------------------------
# 2) Smaller Settings panel. Keep existing Keep-Awake switch and all controls.
# ---------------------------------------------------------------------------
settings_rel = "app/src/main/java/com/althmany/groupmanager/ui/SettingsActivity.kt"
replace_once(
    settings_rel,
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
    '''        window.setDimAmount(0.26f)
        window.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        binding.root.post {
            val metrics = resources.displayMetrics
            // 3.4: visibly smaller floating settings sheet; content remains scrollable.
            val panelWidth = (metrics.widthPixels * 0.66f).toInt()
            val maxPanelWidth = (330f * metrics.density).toInt()
            val panelHeight = (metrics.heightPixels * 0.80f).toInt()
            window.setLayout(minOf(panelWidth, maxPanelWidth), panelHeight)
        }
''',
    already="metrics.widthPixels * 0.66f"
)

settings_layout_rel = "app/src/main/res/layout/activity_settings.xml"
layout = read(settings_layout_rel)
layout2 = layout.replace('android:padding="10dp"', 'android:padding="8dp"')
layout2 = layout2.replace('android:layout_marginTop="8dp"', 'android:layout_marginTop="6dp"')
write(settings_layout_rel, layout2)

# ---------------------------------------------------------------------------
# 3) Samsung Dual Messenger button + resources.
# ---------------------------------------------------------------------------
main_layout_rel = "app/src/main/res/layout/activity_main.xml"
layout = read(main_layout_rel)
if 'android:id="@+id/dualRemoteButton"' not in layout:
    m = re.search(
        r'(<com\.google\.android\.material\.button\.MaterialButton\s+'
        r'android:id="@\+id/secureRemoteButton"[\s\S]*?/>)',
        layout
    )
    if not m:
        raise SystemExit("ERROR: secureRemoteButton block not found.")
    dual_button = r'''

                        <com.google.android.material.button.MaterialButton
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
                            app:strokeColor="@color/sender_accent" />'''
    layout = layout[:m.end()] + dual_button + layout[m.end():]
    write(main_layout_rel, layout)

ar = r'''
    <!-- Al-othmany Sender 3.4 Samsung profiles -->
    <string name="dual_remote_detect_button">Samsung Dual Messenger عبر Shizuku</string>
    <string name="dual_remote_rescan_button">Dual Messenger محدد • تغيير</string>
    <string name="dual_remote_picker_title">اختر WhatsApp داخل Dual Messenger</string>
    <string name="dual_remote_none_found">لم يتم العثور على DUAL_APP يحتوي WhatsApp. فعّل Samsung Dual Messenger لواتساب أولًا.</string>
    <string name="dual_remote_selected_format">Dual Messenger • %1$s • user %2$d</string>
    <string name="dual_remote_verified_format">Dual Remote جاهز • %1$s • user %2$d</string>
    <string name="dual_remote_selected_toast">تم تحديد WhatsApp داخل Samsung Dual Messenger. سيتم قفل الجولة على نفس Android user.</string>
    <string name="secure_remote_knox_ladder">لم يظهر Secure Folder الحقيقي عبر ADB/Shizuku الخارجي. تم فحص pm/cmd user/dumpsys user ولم يتم اختيار Work أو Island أو Dual بالخطأ. البديل غير الجذري هو تشغيل نفس Al-othmany Sender داخل Secure Folder مع Accessibility المحلية إذا سمحت Samsung بها.</string>
'''
en = r'''
    <!-- Al-othmany Sender 3.4 Samsung profiles -->
    <string name="dual_remote_detect_button">Samsung Dual Messenger via Shizuku</string>
    <string name="dual_remote_rescan_button">Dual Messenger selected • Change</string>
    <string name="dual_remote_picker_title">Choose WhatsApp in Dual Messenger</string>
    <string name="dual_remote_none_found">No DUAL_APP user containing WhatsApp was found. Enable Samsung Dual Messenger for WhatsApp first.</string>
    <string name="dual_remote_selected_format">Dual Messenger • %1$s • user %2$d</string>
    <string name="dual_remote_verified_format">Dual Remote ready • %1$s • user %2$d</string>
    <string name="dual_remote_selected_toast">WhatsApp inside Samsung Dual Messenger was selected. The run will be locked to that exact Android user.</string>
    <string name="secure_remote_knox_ladder">The real Secure Folder was not visible through host ADB/Shizuku. pm/cmd user/dumpsys user were checked and Work, Island, or Dual were not selected by mistake. The non-root fallback is the same Al-othmany Sender APK inside Secure Folder using local Accessibility if Samsung allows it.</string>
'''
add_resources("app/src/main/res/values/strings.xml", 'name="dual_remote_detect_button"', ar)
add_resources("app/src/main/res/values-en/strings.xml", 'name="dual_remote_detect_button"', en)

# ---------------------------------------------------------------------------
# 4) MainActivity:
#    Dual user selector, stronger Secure discovery, user-controlled resume.
# ---------------------------------------------------------------------------
main_rel = "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt"

main = read(main_rel)
while "app.preferences.clearRemoteSecureTarget()\n            app.preferences.clearRemoteSecureTarget()" in main:
    main = main.replace(
        "app.preferences.clearRemoteSecureTarget()\n            app.preferences.clearRemoteSecureTarget()",
        "app.preferences.clearRemoteSecureTarget()",
        1
    )
while "app.preferences.clearRemoteSecureTarget()\n                    app.preferences.clearRemoteSecureTarget()" in main:
    main = main.replace(
        "app.preferences.clearRemoteSecureTarget()\n                    app.preferences.clearRemoteSecureTarget()",
        "app.preferences.clearRemoteSecureTarget()",
        1
    )
write(main_rel, main)

replace_once(
    main_rel,
    '''        binding.secureRemoteButton.setOnClickListener { detectRemoteSecureTarget() }
        binding.secureRemoteButton.setOnLongClickListener {
            if (app.preferences.remoteSecureFolderEnabled) {
                app.preferences.clearRemoteSecureTarget()
                renderInstalledTargets()
                toast(R.string.secure_remote_cleared_toast)
            }
            true
        }
''',
    '''        binding.secureRemoteButton.setOnClickListener { detectRemoteSecureTarget() }
        binding.dualRemoteButton.setOnClickListener { detectRemoteDualTarget() }
        binding.secureRemoteButton.setOnLongClickListener {
            if (app.preferences.remoteSecureFolderEnabled) {
                app.preferences.clearRemoteSecureTarget()
                renderInstalledTargets()
                toast(R.string.secure_remote_cleared_toast)
            }
            true
        }
        binding.dualRemoteButton.setOnLongClickListener {
            if (app.preferences.remoteSecureFolderEnabled) {
                app.preferences.clearRemoteSecureTarget()
                renderInstalledTargets()
                toast(R.string.secure_remote_cleared_toast)
            }
            true
        }
''',
    already="binding.dualRemoteButton.setOnClickListener"
)

replace_once(
    main_rel,
    '''        val remoteSecure = app.preferences.hasValidRemoteSecureTarget()
        val remoteUserId = app.preferences.remoteSecureAndroidUserId
        val remotePackage = app.preferences.remoteSecureWhatsAppPackage
''',
    '''        val remoteSecure = app.preferences.hasValidRemoteSecureTarget()
        val remoteUserId = app.preferences.remoteSecureAndroidUserId
        val remotePackage = app.preferences.remoteSecureWhatsAppPackage
        val remoteLabel = app.preferences.remoteSecureUserLabel.orEmpty()
        val remoteDual = remoteSecure && isDualMessengerUserLabel(remoteLabel)
''',
    already="val remoteDual = remoteSecure && isDualMessengerUserLabel"
)

replace_once(
    main_rel,
    '''        binding.secureRemoteButton.isEnabled = unlocked
        binding.secureRemoteButton.setText(
            if (remoteSecure) R.string.secure_remote_rescan_button
            else R.string.secure_remote_detect_button
        )
        binding.communityTraversalSwitch.isEnabled = unlocked
''',
    '''        binding.secureRemoteButton.isEnabled = unlocked
        binding.dualRemoteButton.isEnabled = unlocked
        binding.secureRemoteButton.setText(
            if (remoteSecure && !remoteDual) R.string.secure_remote_rescan_button
            else R.string.secure_remote_detect_button
        )
        binding.dualRemoteButton.setText(
            if (remoteDual) R.string.dual_remote_rescan_button
            else R.string.dual_remote_detect_button
        )
        binding.communityTraversalSwitch.isEnabled = unlocked
''',
    already="binding.dualRemoteButton.setText("
)

replace_once(
    main_rel,
    '''        binding.selectedWhatsAppTargetText.text = when {
            remoteSecure -> getString(
                R.string.secure_remote_selected_format,
                app.preferences.remoteSecureUserLabel ?: remotePackage.orEmpty(),
                remoteUserId
            )
''',
    '''        binding.selectedWhatsAppTargetText.text = when {
            remoteDual -> getString(
                R.string.dual_remote_selected_format,
                remoteLabel.ifBlank { remotePackage.orEmpty() },
                remoteUserId
            )
            remoteSecure -> getString(
                R.string.secure_remote_selected_format,
                remoteLabel.ifBlank { remotePackage.orEmpty() },
                remoteUserId
            )
''',
    already="R.string.dual_remote_selected_format"
)

replace_once(
    main_rel,
    '''        binding.targetAppsStatusText.text = when {
            remoteSecure -> getString(
                R.string.secure_remote_verified_format,
                remotePackage.orEmpty(),
                remoteUserId
            )
''',
    '''        binding.targetAppsStatusText.text = when {
            remoteDual -> getString(
                R.string.dual_remote_verified_format,
                remotePackage.orEmpty(),
                remoteUserId
            )
            remoteSecure -> getString(
                R.string.secure_remote_verified_format,
                remotePackage.orEmpty(),
                remoteUserId
            )
''',
    already="R.string.dual_remote_verified_format"
)

main = read(main_rel)
pattern = re.compile(
    r'''    private data class RemoteSecureCandidate\(
        val userId: Int,
        val userName: String,
        val packageName: String
    \)

    private fun detectRemoteSecureTarget\(\) \{
[\s\S]*?
    private fun selectRemoteSecureTarget\(candidate: RemoteSecureCandidate\) \{
[\s\S]*?
    \}

(?=    private fun installedMark)'''
)
m = pattern.search(main)
if m:
    replacement = r'''    private data class RemoteSecureCandidate(
        val userId: Int,
        val userName: String,
        val packageName: String,
        val secureMarker: Boolean = false
    )

    private fun isDualMessengerUserLabel(label: String): Boolean {
        val normalized = label.lowercase()
        return normalized.contains("dual_app") ||
            normalized.contains("dual app") ||
            normalized.contains("dual messenger")
    }

    private suspend fun discoverRemoteWhatsAppCandidates(): List<RemoteSecureCandidate> {
        val userResult = withContext(Dispatchers.IO) {
            ShizukuBridge.execute(
                this@MainActivity,
                "{ pm list users 2>/dev/null; cmd user list 2>/dev/null; dumpsys user 2>/dev/null; }",
                6_000
            )
        }
        if (!userResult.success && userResult.output.isBlank()) return emptyList()

        val userRegex = Regex("UserInfo\\{([0-9]+):([^:}]*)")
        val users = userRegex.findAll(userResult.output)
            .mapNotNull { match ->
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                id to match.groupValues[2].trim().ifBlank { "Android user $id" }
            }
            .distinctBy { it.first }
            .toList()

        val hostUserId = android.os.Process.myUid() / 100000
        val packages = listOf("com.whatsapp", "com.whatsapp.w4b", "com.whatsapp2")
        val found = mutableListOf<RemoteSecureCandidate>()

        for ((userId, userName) in users) {
            if (userId == hostUserId) continue

            val markerResult = withContext(Dispatchers.IO) {
                ShizukuBridge.execute(
                    this@MainActivity,
                    "pm list packages --user $userId 2>/dev/null | " +
                        "grep -E 'package:com\\.samsung\\.knox\\.securefolder' | head -n1",
                    2_500
                )
            }
            val secureMarker = markerResult.output.contains("samsung", ignoreCase = true) &&
                markerResult.output.contains("knox", ignoreCase = true)

            for (packageName in packages) {
                val packageResult = withContext(Dispatchers.IO) {
                    ShizukuBridge.execute(
                        this@MainActivity,
                        "pm list packages --user $userId $packageName 2>/dev/null",
                        2_500
                    )
                }
                val exactPresent = packageResult.output.lineSequence()
                    .map(String::trim)
                    .any { it == "package:$packageName" }
                if (exactPresent) {
                    found += RemoteSecureCandidate(
                        userId = userId,
                        userName = userName,
                        packageName = packageName,
                        secureMarker = secureMarker
                    )
                }
            }
        }
        return found.distinctBy { "${it.userId}:${it.packageName}" }
    }

    private fun detectRemoteSecureTarget() {
        if (!runCatching { ShizukuBridge.status().ready }.getOrDefault(false)) {
            toast(R.string.secure_remote_shizuku_required)
            return
        }

        lifecycleScope.launch {
            binding.secureRemoteButton.isEnabled = false
            binding.dualRemoteButton.isEnabled = false
            try {
                val candidates = discoverRemoteWhatsAppCandidates()
                val secure = candidates.filter { candidate ->
                    val name = candidate.userName.lowercase()
                    val secureName = listOf(
                        "secure", "knox", "folder", "مجلد", "آمن", "امن"
                    ).any(name::contains)
                    val knownNonSecure = listOf(
                        "island", "work", "managed", "dual_app", "dual app",
                        "dual messenger", "clone", "cloned"
                    ).any(name::contains)
                    !knownNonSecure && (secureName || candidate.secureMarker)
                }

                // 3.4: never relabel Work/Island/Dual Messenger as Secure Folder.
                if (secure.isEmpty()) {
                    app.preferences.clearRemoteSecureTarget()
                    toast(R.string.secure_remote_knox_ladder)
                    renderInstalledTargets()
                    return@launch
                }
                showRemoteSamsungPicker(
                    titleRes = R.string.secure_remote_picker_title,
                    candidates = secure,
                    dual = false
                )
            } finally {
                binding.secureRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
                binding.dualRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
            }
        }
    }

    private fun detectRemoteDualTarget() {
        if (!runCatching { ShizukuBridge.status().ready }.getOrDefault(false)) {
            toast(R.string.secure_remote_shizuku_required)
            return
        }

        lifecycleScope.launch {
            binding.secureRemoteButton.isEnabled = false
            binding.dualRemoteButton.isEnabled = false
            try {
                val dual = discoverRemoteWhatsAppCandidates().filter {
                    isDualMessengerUserLabel(it.userName)
                }
                if (dual.isEmpty()) {
                    toast(R.string.dual_remote_none_found)
                    return@launch
                }
                showRemoteSamsungPicker(
                    titleRes = R.string.dual_remote_picker_title,
                    candidates = dual,
                    dual = true
                )
            } finally {
                binding.secureRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
                binding.dualRemoteButton.isEnabled =
                    !app.preferences.accessibilityBatchRunning && !app.preferences.hasScheduledStart
            }
        }
    }

    private fun showRemoteSamsungPicker(
        titleRes: Int,
        candidates: List<RemoteSecureCandidate>,
        dual: Boolean
    ) {
        if (candidates.size == 1) {
            selectRemoteSamsungTarget(candidates.first(), dual)
            return
        }
        val labels = candidates.map { candidate ->
            "${candidate.userName}  •  user ${candidate.userId}\n${candidate.packageName}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setItems(labels) { _, which ->
                selectRemoteSamsungTarget(candidates[which], dual)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun selectRemoteSamsungTarget(candidate: RemoteSecureCandidate, dual: Boolean) {
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
        toast(
            if (dual) R.string.dual_remote_selected_toast
            else R.string.secure_remote_selected_toast
        )
    }

'''
    main = main[:m.start()] + replacement + main[m.end():]
    write(main_rel, main)
elif "discoverRemoteWhatsAppCandidates" not in main:
    raise SystemExit("ERROR: Remote Secure discovery block not found in MainActivity.")

replace_once(
    main_rel,
    '''        app.preferences.runtimeShadowMode = false
        app.preferences.fastHandsFreeMode = true
        app.preferences.autoResumeCurrentRun = true
        app.preferences.restrictionHandlingMode = RestrictionHandlingMode.SKIP_AND_CONTINUE
''',
    '''        app.preferences.runtimeShadowMode = false
        app.preferences.fastHandsFreeMode =
            RuntimeSpeedProfilePolicy.isFast(app.preferences.runtimeSpeedMode)
        // 3.4: do not force Auto Resume. The user's switch controls automatic resume.
        app.preferences.restrictionHandlingMode = RestrictionHandlingMode.SKIP_AND_CONTINUE
''',
    already="// 3.4: do not force Auto Resume."
)

replace_once(
    main_rel,
    '''        resumeLastRunButton.visibility =
            if (!running && !scheduled && canContinueQueuedBatch()) View.VISIBLE else View.GONE
        resumeLastRunButton.isEnabled = !running && !scheduled
''',
    '''        resumeLastRunButton.visibility =
            if (!scheduled && ((running && paused) || (!running && canContinueQueuedBatch()))) {
                View.VISIBLE
            } else {
                View.GONE
            }
        resumeLastRunButton.isEnabled = !scheduled && (paused || !running)
''',
    already="((running && paused) || (!running && canContinueQueuedBatch()))"
)

# ---------------------------------------------------------------------------
# 5) Evidence-based counters.
# ---------------------------------------------------------------------------
session_rules_rel = "app/src/main/java/com/althmany/groupmanager/domain/SessionRules.kt"
rules = read(session_rules_rel)
old_stats = '''    fun stats(links: List<GroupLink>): SessionStats = SessionStats(
        total = links.size,
        pending = links.count { it.status == LinkStatus.PENDING },
        opened = links.count { it.status == LinkStatus.OPENED },
        joined = links.count { it.status == LinkStatus.JOINED },
        requested = links.count { it.status == LinkStatus.REQUESTED },
        skipped = links.count { it.status == LinkStatus.SKIPPED },
        failed = links.count { it.status == LinkStatus.FAILED }
    )
'''
new_stats = '''    fun stats(links: List<GroupLink>): SessionStats {
        val joined = links.count {
            it.status == LinkStatus.JOINED &&
                it.resultCode in setOf(
                    com.althmany.groupmanager.model.LinkResultCode.JOIN_ACTION_COMPLETED,
                    com.althmany.groupmanager.model.LinkResultCode.MANUAL_JOINED
                )
        }
        val requested = links.count {
            it.status == LinkStatus.REQUESTED &&
                it.resultCode == com.althmany.groupmanager.model.LinkResultCode.REQUEST_SENT
        }
        val alreadyMember = links.count {
            it.status == LinkStatus.JOINED &&
                it.resultCode == com.althmany.groupmanager.model.LinkResultCode.ALREADY_MEMBER
        }
        val skipped = links.count { it.status == LinkStatus.SKIPPED } + alreadyMember
        val unverifiedTerminal = links.count {
            (it.status == LinkStatus.JOINED &&
                it.resultCode !in setOf(
                    com.althmany.groupmanager.model.LinkResultCode.JOIN_ACTION_COMPLETED,
                    com.althmany.groupmanager.model.LinkResultCode.MANUAL_JOINED,
                    com.althmany.groupmanager.model.LinkResultCode.ALREADY_MEMBER
                )) ||
                (it.status == LinkStatus.REQUESTED &&
                    it.resultCode != com.althmany.groupmanager.model.LinkResultCode.REQUEST_SENT)
        }
        return SessionStats(
            total = links.size,
            pending = links.count { it.status == LinkStatus.PENDING },
            opened = links.count { it.status == LinkStatus.OPENED },
            joined = joined,
            requested = requested,
            skipped = skipped,
            failed = links.count { it.status == LinkStatus.FAILED } + unverifiedTerminal
        )
    }
'''
if "val unverifiedTerminal = links.count" not in rules:
    if old_stats not in rules:
        raise SystemExit("ERROR: SessionRules.stats anchor not found.")
    write(session_rules_rel, rules.replace(old_stats, new_stats, 1))

db_rel = "app/src/main/java/com/althmany/groupmanager/data/GroupLinkDatabase.kt"
db = read(db_rel)
old_query = '''            SELECT COUNT(*) AS total_count,
                   SUM(CASE WHEN status = '${LinkStatus.PENDING.name}' THEN 1 ELSE 0 END) AS pending_count,
                   SUM(CASE WHEN status = '${LinkStatus.OPENED.name}' THEN 1 ELSE 0 END) AS opened_count,
                   SUM(CASE WHEN status = '${LinkStatus.JOINED.name}' THEN 1 ELSE 0 END) AS joined_count,
                   SUM(CASE WHEN status = '${LinkStatus.REQUESTED.name}' THEN 1 ELSE 0 END) AS requested_count,
                   SUM(CASE WHEN status = '${LinkStatus.SKIPPED.name}' THEN 1 ELSE 0 END) AS skipped_count,
                   SUM(CASE WHEN status = '${LinkStatus.FAILED.name}' THEN 1 ELSE 0 END) AS failed_count
            FROM links
'''
new_query = '''            SELECT COUNT(*) AS total_count,
                   SUM(CASE WHEN status = '${LinkStatus.PENDING.name}' THEN 1 ELSE 0 END) AS pending_count,
                   SUM(CASE WHEN status = '${LinkStatus.OPENED.name}' THEN 1 ELSE 0 END) AS opened_count,
                   SUM(CASE WHEN status = '${LinkStatus.JOINED.name}'
                             AND result_code IN ('${LinkResultCode.JOIN_ACTION_COMPLETED.name}', '${LinkResultCode.MANUAL_JOINED.name}')
                            THEN 1 ELSE 0 END) AS joined_count,
                   SUM(CASE WHEN status = '${LinkStatus.REQUESTED.name}'
                             AND result_code = '${LinkResultCode.REQUEST_SENT.name}'
                            THEN 1 ELSE 0 END) AS requested_count,
                   SUM(CASE WHEN status = '${LinkStatus.SKIPPED.name}'
                             OR (status = '${LinkStatus.JOINED.name}' AND result_code = '${LinkResultCode.ALREADY_MEMBER.name}')
                            THEN 1 ELSE 0 END) AS skipped_count,
                   SUM(CASE WHEN status = '${LinkStatus.FAILED.name}'
                             OR (status = '${LinkStatus.JOINED.name}' AND
                                 (result_code IS NULL OR result_code NOT IN (
                                     '${LinkResultCode.JOIN_ACTION_COMPLETED.name}',
                                     '${LinkResultCode.MANUAL_JOINED.name}',
                                     '${LinkResultCode.ALREADY_MEMBER.name}'
                                 )))
                             OR (status = '${LinkStatus.REQUESTED.name}' AND
                                 (result_code IS NULL OR result_code != '${LinkResultCode.REQUEST_SENT.name}'))
                            THEN 1 ELSE 0 END) AS failed_count
            FROM links
'''
if "result_code IN ('${LinkResultCode.JOIN_ACTION_COMPLETED.name}'" not in db:
    if old_query not in db:
        raise SystemExit("ERROR: querySessionStats SQL anchor not found.")
    db = db.replace(old_query, new_query, 1)

old_summary = '''                SUM(CASE WHEN l.status = '${LinkStatus.JOINED.name}' THEN 1 ELSE 0 END) AS joined_count,
                SUM(CASE WHEN l.status = '${LinkStatus.REQUESTED.name}' THEN 1 ELSE 0 END) AS requested_count,
                SUM(CASE WHEN l.status = '${LinkStatus.SKIPPED.name}' THEN 1 ELSE 0 END) AS skipped_count,
                SUM(CASE WHEN l.status = '${LinkStatus.FAILED.name}' THEN 1 ELSE 0 END) AS failed_count
'''
new_summary = '''                SUM(CASE WHEN l.status = '${LinkStatus.JOINED.name}'
                          AND l.result_code IN ('${LinkResultCode.JOIN_ACTION_COMPLETED.name}', '${LinkResultCode.MANUAL_JOINED.name}')
                         THEN 1 ELSE 0 END) AS joined_count,
                SUM(CASE WHEN l.status = '${LinkStatus.REQUESTED.name}'
                          AND l.result_code = '${LinkResultCode.REQUEST_SENT.name}'
                         THEN 1 ELSE 0 END) AS requested_count,
                SUM(CASE WHEN l.status = '${LinkStatus.SKIPPED.name}'
                          OR (l.status = '${LinkStatus.JOINED.name}' AND l.result_code = '${LinkResultCode.ALREADY_MEMBER.name}')
                         THEN 1 ELSE 0 END) AS skipped_count,
                SUM(CASE WHEN l.status = '${LinkStatus.FAILED.name}'
                          OR (l.status = '${LinkStatus.JOINED.name}' AND
                              (l.result_code IS NULL OR l.result_code NOT IN (
                                  '${LinkResultCode.JOIN_ACTION_COMPLETED.name}',
                                  '${LinkResultCode.MANUAL_JOINED.name}',
                                  '${LinkResultCode.ALREADY_MEMBER.name}'
                              )))
                          OR (l.status = '${LinkStatus.REQUESTED.name}' AND
                              (l.result_code IS NULL OR l.result_code != '${LinkResultCode.REQUEST_SENT.name}'))
                         THEN 1 ELSE 0 END) AS failed_count
'''
if "l.result_code IN ('${LinkResultCode.JOIN_ACTION_COMPLETED.name}'" not in db:
    if old_summary not in db:
        raise SystemExit("ERROR: session summary counter SQL anchor not found.")
    db = db.replace(old_summary, new_summary, 1)
write(db_rel, db)

# ---------------------------------------------------------------------------
# 6) Accessibility precision.
# ---------------------------------------------------------------------------
acc_rel = "app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt"
acc = read(acc_rel)

if "private var accessibilityVisualExpectedAction" not in acc:
    anchor = '''    private var accessibilityVisualActionTappedAtElapsed = 0L
    private var accessibilityVisualTapAttempts = 0
'''
    repl = '''    private var accessibilityVisualActionTappedAtElapsed = 0L
    private var accessibilityVisualTapAttempts = 0
    private var accessibilityVisualExpectedAction: AccessibilityJoinAction? = null
'''
    if anchor not in acc:
        raise SystemExit("ERROR: Accessibility visual state anchor not found.")
    acc = acc.replace(anchor, repl, 1)

old_request_submitted = '''        val requestSubmitted = strongRequestSubmitted ||
            (cancelRequestSeen && action != AccessibilityJoinAction.REQUEST)
        if (requestSubmitted) terminalEvidenceKinds += "REQUEST_SUBMITTED"
'''
new_request_submitted = '''        val pendingRequestWasPressed =
            app.preferences.accessibilityPendingLinkId > 0L &&
                app.preferences.accessibilityPendingAction == AccessibilityJoinAction.REQUEST.name
        val postClickApprovalVariant =
            pendingRequestWasPressed &&
                requestApprovalNoticeSeen &&
                action != AccessibilityJoinAction.REQUEST

        val requestSubmitted = strongRequestSubmitted ||
            (cancelRequestSeen && action != AccessibilityJoinAction.REQUEST) ||
            postClickApprovalVariant
        if (requestSubmitted) terminalEvidenceKinds += "REQUEST_SUBMITTED"
'''
if "val postClickApprovalVariant =" not in acc:
    if old_request_submitted not in acc:
        raise SystemExit("ERROR: Accessibility requestSubmitted anchor not found.")
    acc = acc.replace(old_request_submitted, new_request_submitted, 1)

if "val requestApprovalNoticeSeen: Boolean" not in acc:
    acc = acc.replace(
        '''            requestSubmitted = requestSubmitted,
            failure = failure,
''',
        '''            requestSubmitted = requestSubmitted,
            requestApprovalNoticeSeen = requestApprovalNoticeSeen,
            failure = failure,
''',
        1
    )
    acc = acc.replace(
        '''        val alreadyMember: Boolean,
        val requestSubmitted: Boolean,
        val failure: AccessibilityFailureType?,
''',
        '''        val alreadyMember: Boolean,
        val requestSubmitted: Boolean,
        val requestApprovalNoticeSeen: Boolean,
        val failure: AccessibilityFailureType?,
''',
        1
    )

if "accessibilityVisualExpectedAction = when" not in acc:
    section_start = acc.find("private suspend fun maybeHandleAccessibilityVisualFallback")
    if section_start < 0:
        raise SystemExit("ERROR: Accessibility visual fallback function not found.")
    anchor = '''            accessibilityVisualActionTappedAtElapsed = 0L
            accessibilityVisualTapAttempts = 0
        }
'''
    idx = acc.find(anchor, section_start)
    if idx < 0:
        raise SystemExit("ERROR: Accessibility visual reset anchor not found.")
    repl = '''            accessibilityVisualActionTappedAtElapsed = 0L
            accessibilityVisualTapAttempts = 0
            accessibilityVisualExpectedAction = when {
                screen.requestApprovalNoticeSeen -> AccessibilityJoinAction.REQUEST
                else -> readPendingAction(current)
            }
        }
'''
    acc = acc[:idx] + acc[idx:].replace(anchor, repl, 1)

old_visual_final = '''                // The protected positive control disappeared. If WhatsApp did not expose enough
                // semantics to distinguish Request from Join, mirror the Shizuku compatibility
                // lane: treat the non-conversation result as a submitted/pending request, close
                // the surface once, and keep the queue moving.
                accessibilityVisualActionTappedAtElapsed = 0L
                val backSent = withContext(Dispatchers.Main.immediate) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                if (backSent) delay(exitSettleDelayMs())
                completeAndAdvance(
                    current,
                    LinkStatus.REQUESTED,
                    LinkResultCode.REQUEST_SENT,
                    "Visual Join/Request control disappeared; no conversation semantics were exposed, so the result was recorded as pending/requested and the queue continued",
                    fastAdvance = true,
                    surfaceAlreadyExited = true
                )
                return@launch
'''
new_visual_final = '''                // The protected positive control disappeared. Do not invent a successful result.
                // REQUESTED is allowed only when the pre-click screen identified the request path.
                accessibilityVisualActionTappedAtElapsed = 0L
                val expected = accessibilityVisualExpectedAction ?: readPendingAction(current)
                val backSent = withContext(Dispatchers.Main.immediate) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                if (backSent) delay(exitSettleDelayMs())
                if (expected == AccessibilityJoinAction.REQUEST) {
                    completeAndAdvance(
                        current,
                        LinkStatus.REQUESTED,
                        LinkResultCode.REQUEST_SENT,
                        "Known Request visual action disappeared; request recorded and queue continued",
                        fastAdvance = true,
                        surfaceAlreadyExited = backSent
                    )
                } else {
                    completeAndAdvance(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.UNKNOWN_SCREEN,
                        "Visual positive action disappeared without verified Join/Request evidence; not counted as a random success",
                        fastAdvance = true,
                        surfaceAlreadyExited = backSent
                    )
                }
                return@launch
'''
if "not counted as a random success" not in acc:
    if old_visual_final not in acc:
        raise SystemExit("ERROR: Accessibility unsafe visual-result block not found.")
    acc = acc.replace(old_visual_final, new_visual_final, 1)

acc = acc.replace(
    'current, LinkStatus.JOINED, LinkResultCode.ALREADY_MEMBER, "Already a member",',
    'current, LinkStatus.SKIPPED, LinkResultCode.ALREADY_MEMBER, "Already a member",'
)
write(acc_rel, acc)

# ---------------------------------------------------------------------------
# 7) Shizuku request precision + long-run self-heal.
# ---------------------------------------------------------------------------
shizuku_rel = "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"
sh = read(shizuku_rel)

if "commandDumpKillRecoveryAttempts" not in sh:
    anchor = '''    private var consecutiveDumpFailures = 0
    private var consecutiveAmbiguousActions = 0
'''
    repl = '''    private var consecutiveDumpFailures = 0
    private var commandDumpKillRecoveryAttempts = 0
    private var commandDumpSuppressedUntilElapsed = 0L
    private var lastPeriodicUiRefreshProcessed = 0
    private var consecutiveAmbiguousActions = 0
'''
    if anchor not in sh:
        raise SystemExit("ERROR: Shizuku dump state anchor not found.")
    sh = sh.replace(anchor, repl, 1)

old_request = '''        if (pending == AccessibilityJoinAction.REQUEST &&
            (snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED ||
                snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isCancelRequest) ||
                AccessibilityJoinMatcher.isRequestSubmittedAcross(snapshot.labels.asSequence()))
        ) {
'''
new_request = '''        val pendingApprovalVariant =
            pending == AccessibilityJoinAction.REQUEST &&
                snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isRequestApprovalNotice) &&
                snapshot.screenKind != AutomationScreenKind.REQUEST_ACTION

        if (pending == AccessibilityJoinAction.REQUEST &&
            (snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED ||
                snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isCancelRequest) ||
                AccessibilityJoinMatcher.isRequestSubmittedAcross(snapshot.labels.asSequence()) ||
                pendingApprovalVariant)
        ) {
'''
if "val pendingApprovalVariant =" not in sh:
    if old_request not in sh:
        raise SystemExit("ERROR: Shizuku request terminal anchor not found.")
    sh = sh.replace(old_request, new_request, 1)

sh = sh.replace(
    '''"pending=REQUEST; screen=${snapshot.screenKind.name}; cancel=${snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isCancelRequest)}; directNext=true"''',
    '''"pending=REQUEST; screen=${snapshot.screenKind.name}; cancel=${snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isCancelRequest)}; approvalVariant=$pendingApprovalVariant; directNext=true"'''
)

if "requestPendingOverride: Boolean = false" not in sh:
    sh = sh.replace(
        '''    private fun findResultSafeCloseNode(
        snapshot: ShizukuUiSnapshot,
        targetPackage: String
    ): ShizukuUiNode? {
''',
        '''    private fun findResultSafeCloseNode(
        snapshot: ShizukuUiSnapshot,
        targetPackage: String,
        requestPendingOverride: Boolean = false
    ): ShizukuUiNode? {
''',
        1
    )
    sh = sh.replace(
        '''                val requestSheetCorner =
                    snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED &&
''',
        '''                val requestSheetCorner =
                    (snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED ||
                        requestPendingOverride) &&
''',
        1
    )

old_close_call = '''        // 1) Prefer WhatsApp's actual X/Close.
        val close = findResultSafeCloseNode(snapshot, targetPackage)
'''
new_close_call = '''        // 1) Prefer WhatsApp's actual X/Close.
        val requestPendingSurface =
            snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED ||
                (readPendingAction(current) == AccessibilityJoinAction.REQUEST &&
                    snapshot.labels.asSequence().any(AccessibilityJoinMatcher::isRequestApprovalNotice) &&
                    snapshot.screenKind != AutomationScreenKind.REQUEST_ACTION)
        val close = findResultSafeCloseNode(snapshot, targetPackage, requestPendingSurface)
'''
if "val requestPendingSurface =" not in sh:
    if old_close_call not in sh:
        raise SystemExit("ERROR: Shizuku result close call anchor not found.")
    sh = sh.replace(old_close_call, new_close_call, 1)

# Change only the request fallback inside dismissKnownResultSurface, not unrelated checks.
dismiss_start = sh.find("private suspend fun dismissKnownResultSurface")
if dismiss_start < 0:
    raise SystemExit("ERROR: dismissKnownResultSurface not found.")
request_if = "        if (snapshot.screenKind == AutomationScreenKind.REQUEST_SUBMITTED) {\n"
req_idx = sh.find(request_if, dismiss_start)
if req_idx >= 0 and "if (requestPendingSurface)" not in sh[dismiss_start:req_idx+300]:
    sh = sh[:req_idx] + sh[req_idx:].replace(
        request_if,
        "        if (requestPendingSurface) {\n",
        1
    )

command_anchor = '''        var result = ShizukuBridge.execute(
            this,
            "rm -f /data/local/tmp/althmany_ui.xml; " +
'''
if "SHIZUKU_COMMAND_DUMP_COOLDOWN" not in sh:
    if command_anchor not in sh:
        raise SystemExit("ERROR: Shizuku command dump anchor not found.")
    guard = '''        val commandDumpNow = SystemClock.elapsedRealtime()
        if (commandDumpNow < commandDumpSuppressedUntilElapsed) {
            runtimeDiagnostic(
                current,
                "SHIZUKU_COMMAND_DUMP_COOLDOWN",
                "remainingMs=${commandDumpSuppressedUntilElapsed - commandDumpNow}; " +
                    "persistent/visual/Back recovery preferred over repeatedly spawning killed uiautomator"
            )
            return handleDumpFailure(
                current,
                targetPackage,
                "command dump temporarily suppressed after exit=137; mode=COMMAND_COOLDOWN"
            )
        }

'''
    sh = sh.replace(command_anchor, guard + command_anchor, 1)

pattern = re.compile(
    r'''    private suspend fun handleDumpFailure\(
        current: GroupLink,
        targetPackage: String,
        detail: String
    \): ShizukuUiSnapshot\? \{
[\s\S]*?
    \}

(?=    private suspend fun attemptUnknownRecoveryOnce)'''
)
m = pattern.search(sh)
if m:
    new_func = r'''    private suspend fun handleDumpFailure(
        current: GroupLink,
        targetPackage: String,
        detail: String
    ): ShizukuUiSnapshot? {
        if (emptyDumpStartedAt == 0L) emptyDumpStartedAt = System.currentTimeMillis()
        consecutiveDumpFailures += 1
        val age = System.currentTimeMillis() - emptyDumpStartedAt
        val commandKilled =
            detail.contains("exit=137", ignoreCase = true) ||
                detail.contains("Killed", ignoreCase = true)

        runtimeDiagnostic(
            current,
            "SHIZUKU_UI_DUMP_EMPTY",
            "ageMs=$age; count=$consecutiveDumpFailures; $detail"
        )

        if (commandKilled) {
            commandDumpSuppressedUntilElapsed =
                SystemClock.elapsedRealtime() + COMMAND_DUMP_KILL_COOLDOWN_MS
            if (commandDumpKillRecoveryAttempts < 1) {
                commandDumpKillRecoveryAttempts += 1
                val recovered = ShizukuBridge.fastResetUiAutomation(this)
                runtimeDiagnostic(
                    current,
                    "SHIZUKU_UI_DUMP_KILL_SELF_HEAL",
                    "attempt=$commandDumpKillRecoveryAttempts; recovered=$recovered; " +
                        "cooldownMs=$COMMAND_DUMP_KILL_COOLDOWN_MS; exactUser=${cachedAndroidUserId ?: -1}"
                )
                if (recovered) {
                    fastUiMode = FastUiMode.UNKNOWN
                    fastUiFailureCount = 0
                    fastUiSessionRecoveryAttempts = 0
                    lastFastEventSequence = 0L
                    currentLaunchEventBaseline = 0L
                    emptyDumpStartedAt = 0L
                    consecutiveDumpFailures = 0
                    armFastEventSequence(targetPackage)
                    delay(60L)
                    return null
                }
            }
        }

        if (age < ShizukuContinuityPolicy.UI_TREE_ADVANCE_AFTER_MS &&
            consecutiveDumpFailures < ShizukuContinuityPolicy.MAX_UI_TREE_FAILURES
        ) {
            return null
        }

        val pending = readPendingAction(current)

        if (pending == AccessibilityJoinAction.JOIN &&
            probeJoinedConversationActivity(targetPackage, current)
        ) {
            exitConversationBeforeDirectHandoff(targetPackage, current)
            emptyDumpStartedAt = 0L
            consecutiveDumpFailures = 0
            commandDumpKillRecoveryAttempts = 0
            completeCurrent(
                current,
                LinkStatus.JOINED,
                LinkResultCode.JOIN_ACTION_COMPLETED,
                "UI hierarchy unavailable, but exact-user WhatsApp Conversation proved Join success"
            )
            return null
        }

        // UI-tree failure is NOT user-exit evidence. The outer automation loop owns stable
        // foreground-loss confirmation and is the only path allowed to auto-pause the run.
        val userId = cachedAndroidUserId ?: resolveAndroidUserId(targetPackage)
        val exactForeground =
            (userId != null && foregroundLeaseValid(targetPackage, userId)) ||
                isTargetForeground(targetPackage, forceProbe = true)

        if (!exactForeground) {
            runtimeDiagnostic(
                current,
                "SHIZUKU_UI_TREE_FOREGROUND_DEFER",
                "ageMs=$age; count=$consecutiveDumpFailures; treeFailureIsNotUserExit=true; " +
                    "deferToStableForegroundController=true"
            )
            emptyDumpStartedAt = 0L
            consecutiveDumpFailures = 0
            commandDumpKillRecoveryAttempts = 0
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
        }
        runtimeDiagnostic(
            current,
            "SHIZUKU_UI_TREE_BACK_HANDOFF",
            "back=$backSent; persistentReset=$reset; pending=${pending?.name ?: "NONE"}; " +
                "exactForeground=true; pause=false; next=true"
        )

        emptyDumpStartedAt = 0L
        consecutiveDumpFailures = 0
        commandDumpKillRecoveryAttempts = 0
        completeCurrent(
            current,
            LinkStatus.FAILED,
            LinkResultCode.UNKNOWN_SCREEN,
            if (backSent) {
                "Unreadable WhatsApp surface dismissed by safe Back; result left unverified and next link opened"
            } else {
                "WhatsApp UI remained unreadable; result left unverified and next link opened without a random success count"
            }
        )
        return null
    }

'''
    sh = sh[:m.start()] + new_func + sh[m.end():]
elif "SHIZUKU_UI_TREE_BACK_HANDOFF" not in sh:
    raise SystemExit("ERROR: Shizuku handleDumpFailure function not found.")

reset_split = sh.split("private fun resetPerLinkEvidence()", 1)
if len(reset_split) != 2:
    raise SystemExit("ERROR: resetPerLinkEvidence not found.")
if "commandDumpKillRecoveryAttempts = 0" not in reset_split[1][:1000]:
    anchor = '''        emptyDumpStartedAt = 0L
        consecutiveDumpFailures = 0
        consecutiveAmbiguousActions = 0
'''
    repl = '''        emptyDumpStartedAt = 0L
        consecutiveDumpFailures = 0
        commandDumpKillRecoveryAttempts = 0
        consecutiveAmbiguousActions = 0
'''
    if anchor not in sh:
        raise SystemExit("ERROR: Shizuku resetPerLinkEvidence anchor not found.")
    sh = sh.replace(anchor, repl, 1)

periodic_anchor = '''        updateNotification(getString(R.string.shizuku_service_completed_link, state.processed))
        resetPerLinkEvidence()

        if (state.limitReached) {
'''
if "SHIZUKU_PERIODIC_UI_REFRESH" not in sh:
    periodic_repl = '''        updateNotification(getString(R.string.shizuku_service_completed_link, state.processed))
        resetPerLinkEvidence()

        if (state.processed > 0 &&
            state.processed % PERIODIC_UI_REFRESH_EVERY == 0 &&
            lastPeriodicUiRefreshProcessed != state.processed
        ) {
            lastPeriodicUiRefreshProcessed = state.processed
            val refreshed = ShizukuBridge.fastResetUiAutomation(this)
            runtimeDiagnostic(
                current,
                "SHIZUKU_PERIODIC_UI_REFRESH",
                "processed=${state.processed}; refreshed=$refreshed; preventiveLongRunMaintenance=true"
            )
            if (refreshed) {
                fastUiMode = FastUiMode.UNKNOWN
                fastUiFailureCount = 0
                fastUiSessionRecoveryAttempts = 0
                lastFastEventSequence = 0L
                currentLaunchEventBaseline = 0L
                commandDumpSuppressedUntilElapsed = 0L
            }
        }

        if (state.limitReached) {
'''
    if periodic_anchor not in sh:
        raise SystemExit("ERROR: Shizuku periodic refresh anchor not found.")
    sh = sh.replace(periodic_anchor, periodic_repl, 1)

if "COMMAND_DUMP_KILL_COOLDOWN_MS" not in sh.split("companion object", 1)[-1]:
    anchor = '''        private const val FAST_UI_SESSION_RECOVERY_MAX = 1
        private const val USER_EXIT_LAUNCH_GRACE_MS = 650L
'''
    repl = '''        private const val FAST_UI_SESSION_RECOVERY_MAX = 1
        private const val COMMAND_DUMP_KILL_COOLDOWN_MS = 12_000L
        private const val PERIODIC_UI_REFRESH_EVERY = 100
        private const val USER_EXIT_LAUNCH_GRACE_MS = 650L
'''
    if anchor not in sh:
        raise SystemExit("ERROR: Shizuku constants anchor not found.")
    sh = sh.replace(anchor, repl, 1)

sh = sh.replace(
    "LinkStatus.JOINED, LinkResultCode.ALREADY_MEMBER",
    "LinkStatus.SKIPPED, LinkResultCode.ALREADY_MEMBER"
)
write(shizuku_rel, sh)

policy_rel = "app/src/main/java/com/althmany/groupmanager/domain/ShizukuContinuityPolicy.kt"
policy = read(policy_rel)
if "UI_TREE_ADVANCE_AFTER_MS = 2_200L" not in policy:
    if "UI_TREE_ADVANCE_AFTER_MS = 1_600L" not in policy:
        raise SystemExit("ERROR: Unexpected Shizuku UI-tree timeout.")
    write(
        policy_rel,
        policy.replace("UI_TREE_ADVANCE_AFTER_MS = 1_600L", "UI_TREE_ADVANCE_AFTER_MS = 2_200L", 1)
    )

# ---------------------------------------------------------------------------
# 8) Validator.
# ---------------------------------------------------------------------------
validator_rel = "scripts/validate_source.py"
v = read(validator_rel)
v = v.replace(
    '"versionCode 330": "versionCode = 330" in build,',
    '"versionCode 340": "versionCode = 340" in build,'
)
v = v.replace(
    '"versionName 3.3.0": \'versionName = "3.3.0"\' in build,',
    '"versionName 3.4.0": \'versionName = "3.4.0"\' in build,'
)

if '"3.4 long-run Shizuku recovery"' not in v:
    anchor = '''    "3.3 evidence counter repair": "visualExpectedAction" in shizuku_service and
        "not counted as a false request" in shizuku_service and
        "Fast watchdog recovered a verified exact-user WhatsApp conversation after Join" in shizuku_service,
'''
    extra = anchor + '''    "3.4 long-run Shizuku recovery": all(token in shizuku_service for token in [
        "SHIZUKU_UI_DUMP_KILL_SELF_HEAL",
        "SHIZUKU_COMMAND_DUMP_COOLDOWN",
        "SHIZUKU_UI_TREE_BACK_HANDOFF",
        "SHIZUKU_PERIODIC_UI_REFRESH",
        "treeFailureIsNotUserExit=true"
    ]) and "UI_TREE_ADVANCE_AFTER_MS = 2_200L" in shizuku_continuity,
    "3.4 accurate counters": "val unverifiedTerminal = links.count" in
        (JAVA / "com/althmany/groupmanager/domain/SessionRules.kt").read_text(encoding="utf-8") and
        "result_code IN" in database and
        "not counted as a random success" in service,
    "3.4 Dual Messenger remote user": "dualRemoteButton" in main_layout and
        "detectRemoteDualTarget" in main_activity and
        "DUAL_APP" in main_activity,
    "3.4 Secure multi-source discovery": "pm list users" in main_activity and
        "cmd user list" in main_activity and
        "dumpsys user" in main_activity and
        "secureMarker" in main_activity,
    "3.4 pending approval post-click": "postClickApprovalVariant" in service and
        "pendingApprovalVariant" in shizuku_service,
'''
    if anchor not in v:
        raise SystemExit("ERROR: Validator 3.3 insertion anchor not found.")
    v = v.replace(anchor, extra, 1)
write(validator_rel, v)

# ---------------------------------------------------------------------------
# 9) Static sanity.
# ---------------------------------------------------------------------------
checks = {
    build_rel: ['versionCode = 340', 'versionName = "3.4.0"'],
    settings_rel: ["metrics.widthPixels * 0.66f", "metrics.heightPixels * 0.80f"],
    main_layout_rel: ['android:id="@+id/dualRemoteButton"'],
    main_rel: [
        "detectRemoteDualTarget",
        "discoverRemoteWhatsAppCandidates",
        "cmd user list",
        "dumpsys user",
        "secureMarker",
        "do not force Auto Resume",
        "((running && paused) || (!running && canContinueQueuedBatch()))",
    ],
    session_rules_rel: ["val unverifiedTerminal = links.count"],
    db_rel: ["result_code IN", "LinkResultCode.ALREADY_MEMBER.name"],
    acc_rel: [
        "postClickApprovalVariant",
        "requestApprovalNoticeSeen: Boolean",
        "accessibilityVisualExpectedAction",
        "not counted as a random success",
    ],
    shizuku_rel: [
        "SHIZUKU_UI_DUMP_KILL_SELF_HEAL",
        "SHIZUKU_COMMAND_DUMP_COOLDOWN",
        "SHIZUKU_UI_TREE_BACK_HANDOFF",
        "SHIZUKU_PERIODIC_UI_REFRESH",
        "pendingApprovalVariant",
        "requestPendingOverride",
    ],
    policy_rel: ["UI_TREE_ADVANCE_AFTER_MS = 2_200L"],
    validator_rel: ["versionCode 340", "versionName 3.4.0", "3.4 long-run Shizuku recovery"],
}

missing: list[str] = []
for rel, tokens in checks.items():
    source = read(rel)
    for token in tokens:
        if token not in source:
            missing.append(f"{rel}: {token}")
if missing:
    raise SystemExit("ERROR: Post-patch sanity failed:\n" + "\n".join(missing))

print()
print("============================================================")
print(" Al-othmany Sender 3.4.0 professional patch applied")
print("============================================================")
print("Changed files:")
for rel in changed:
    print(" -", rel)
print()
print("Key fixes:")
print(" - Long-run Shizuku exit=137 self-heal + command-dump cooldown")
print(" - Periodic UiAutomation refresh every 100 processed links")
print(" - UI-tree failure never directly triggers user-exit pause")
print(" - Request/Admin-approval variant -> X/Back -> next")
print(" - Evidence-based Joined/Requested counters; Already Member excluded from Joined")
print(" - Unknown visual result is never invented as REQUESTED")
print(" - Samsung DUAL_APP remote user support")
print(" - Secure Folder discovery from pm/cmd user/dumpsys + Knox markers")
print(" - Smaller Settings panel; Keep-Awake preserved")
print(" - Manual pause/resume preference respected; Resume button visible while paused")
print()
print("NEXT:")
print('  grep -n "versionCode\\|versionName" app/build.gradle.kts')
print("  python3 scripts/validate_source.py")
print("  git diff --check")
print()
print("If all pass:")
print("  git add .")
print('  git commit -m "Al-othmany Sender 3.4.0 professional continuity Samsung profiles"')
print("  git push origin main")
