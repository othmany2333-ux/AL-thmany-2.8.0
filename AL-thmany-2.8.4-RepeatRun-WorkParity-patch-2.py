#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()

MAIN = ROOT / "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt"
A11Y = ROOT / "app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt"
SHIZUKU = ROOT / "app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt"
GRADLE = ROOT / "app/build.gradle.kts"
VALIDATOR = ROOT / "scripts/validate_source.py"

for p in (MAIN, A11Y, SHIZUKU, GRADLE, VALIDATOR):
    if not p.exists():
        raise SystemExit(f"ERROR: missing {p}\nRun this script from the repository root.")

def replace_once(text: str, old: str, new: str, label: str, already: str | None = None) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if already and already in text:
        print(f"SKIP: {label} already applied")
        return text
    raise SystemExit(f"ERROR: anchor not found for {label}")

# ============================================================
# 1) VERSION -> 2.8.4
# ============================================================
gradle = GRADLE.read_text(encoding="utf-8")
gradle = re.sub(r'versionCode\s*=\s*28[234]', 'versionCode = 284', gradle, count=1)
gradle = re.sub(r'versionName\s*=\s*"2\.8\.[234]"', 'versionName = "2.8.4"', gradle, count=1)
if 'versionCode = 284' not in gradle or 'versionName = "2.8.4"' not in gradle:
    raise SystemExit("ERROR: could not set app version 2.8.4")
GRADLE.write_text(gradle, encoding="utf-8")

validator = VALIDATOR.read_text(encoding="utf-8")
validator = re.sub(
    r'"versionCode 28[234]":\s*"versionCode = 28[234]" in build,',
    '"versionCode 284": "versionCode = 284" in build,',
    validator,
    count=1
)
validator = re.sub(
    r'"versionName 2\.8\.[234]":\s*\'versionName = "2\.8\.[234]"\' in build,',
    '"versionName 2.8.4": \'versionName = "2.8.4"\' in build,',
    validator,
    count=1
)
VALIDATOR.write_text(validator, encoding="utf-8")

# ============================================================
# 2) PERSONAL / ACCESSIBILITY:
#    - live service instance
#    - direct immediate scan kick
#    - clear stale instance on unbind/destroy
# ============================================================
a11y = A11Y.read_text(encoding="utf-8")

a11y = replace_once(
    a11y,
    '''    override fun onCreate() {
        super.onCreate()
        // Samsung may instantiate the enabled service before delivering onServiceConnected() to''',
    '''    override fun onCreate() {
        super.onCreate()
        liveInstance = this
        // Samsung may instantiate the enabled service before delivering onServiceConnected() to''',
    "Accessibility live instance onCreate",
    "liveInstance = this"
)

a11y = replace_once(
    a11y,
    '''    override fun onServiceConnected() {
        super.onServiceConnected()
        runtimeConnected = true''',
    '''    override fun onServiceConnected() {
        super.onServiceConnected()
        liveInstance = this
        runtimeConnected = true''',
    "Accessibility live instance onServiceConnected",
    '''override fun onServiceConnected() {
        super.onServiceConnected()
        liveInstance = this'''
)

a11y = replace_once(
    a11y,
    '''    override fun onUnbind(intent: Intent?): Boolean {
        runtimeConnected = false
        ProfileAccessibilityRuntime.markDisconnected(this)
        return super.onUnbind(intent)
    }''',
    '''    override fun onUnbind(intent: Intent?): Boolean {
        runtimeConnected = false
        if (liveInstance === this) liveInstance = null
        ProfileAccessibilityRuntime.markDisconnected(this)
        return super.onUnbind(intent)
    }''',
    "Accessibility clear live instance onUnbind",
    "if (liveInstance === this) liveInstance = null"
)

old_destroy = '''    override fun onDestroy() {
        runtimeConnected = false
        ProfileAccessibilityRuntime.markDisconnected(this)'''
new_destroy = '''    override fun onDestroy() {
        runtimeConnected = false
        if (liveInstance === this) liveInstance = null
        ProfileAccessibilityRuntime.markDisconnected(this)'''
if old_destroy in a11y:
    a11y = a11y.replace(old_destroy, new_destroy, 1)
elif new_destroy not in a11y:
    raise SystemExit("ERROR: Accessibility onDestroy anchor not found")

companion_old = '''    companion object {
        @Volatile private var runtimeConnected = false

        /** True only after Android has connected this exact installed service in this app process. */
        fun isRuntimeConnected(): Boolean = runtimeConnected
'''
companion_new = '''    companion object {
        @Volatile private var runtimeConnected = false
        @Volatile private var liveInstance: QuickJoinAccessibilityService? = null

        /** True only after Android has connected this exact installed service in this app process. */
        fun isRuntimeConnected(): Boolean = runtimeConnected

        /**
         * Same-process kick used after an explicit ACTION_VIEW launch.
         * This makes a second/third run independent of whether WhatsApp emits a fresh event.
         */
        fun requestImmediateScan(): Boolean {
            val instance = liveInstance ?: return false
            if (!runtimeConnected) return false
            instance.lastScanAt = 0L
            instance.requestScan()
            return true
        }
'''
if companion_old in a11y:
    a11y = a11y.replace(companion_old, companion_new, 1)
elif "fun requestImmediateScan(): Boolean" not in a11y:
    raise SystemExit("ERROR: Accessibility companion anchor not found")

A11Y.write_text(a11y, encoding="utf-8")

# ============================================================
# 3) MAIN ACTIVITY:
#    - never start personal Accessibility from stale ON state
#    - require live local service
#    - kick service after WhatsApp launch
#    - clear finished-run input lock so repeat run is allowed
# ============================================================
main = MAIN.read_text(encoding="utf-8")

import_anchor = "import com.althmany.groupmanager.accessibility.AccessibilityStatus\n"
if "import com.althmany.groupmanager.accessibility.QuickJoinAccessibilityService\n" not in main:
    if import_anchor not in main:
        raise SystemExit("ERROR: MainActivity Accessibility import anchor not found")
    main = main.replace(
        import_anchor,
        import_anchor + "import com.althmany.groupmanager.accessibility.QuickJoinAccessibilityService\n",
        1
    )

resume_old = '''                ProfileControlPolicy.mayStartWhileServiceBinds(readiness.systemEnabled) -> {'''
resume_new = '''                ProfileControlPolicy.mayStartWhileServiceBinds(readiness.systemEnabled) &&
                    readiness.localServiceConnected -> {'''
if resume_old in main:
    main = main.replace(resume_old, resume_new, 1)

# Both new-session and queued-continuation starts must require the actual live service.
main = main.replace(
    "if (!readiness.systemEnabled) {",
    "if (!readiness.systemEnabled || !readiness.localServiceConnected) {"
)

# Clear stale same-input auto-start lock when a completed run returns to the dashboard.
onresume_anchor = '''        viewModel.refresh()
        renderRuntimeState()

        if (pendingAutoStartAfterSettings) {'''
onresume_repl = '''        viewModel.refresh()
        renderRuntimeState()

        if (!app.preferences.accessibilityBatchRunning &&
            app.preferences.automationStopReason == AutomationStopReason.SESSION_COMPLETE
        ) {
            lastAutomaticInputHash = null
        }

        if (pendingAutoStartAfterSettings) {'''
if onresume_anchor in main:
    main = main.replace(onresume_anchor, onresume_repl, 1)

# Explicit finish intent cleanup.
control_anchor = '''    private fun handleControlIntent(intent: Intent?) {
        when (intent?.action) {'''
control_repl = '''    private fun handleControlIntent(intent: Intent?) {
        val finishReason = intent?.getStringExtra("automation_finish_reason")
        if (!finishReason.isNullOrBlank()) {
            lastAutomaticInputHash = null
            pendingAutoStartAfterSettings = false
            pendingQueueContinuationAfterSettings = false
            accessibilityReconnectJob?.cancel()
            intent.removeExtra("automation_finish_reason")
        }

        when (intent?.action) {'''
if control_anchor in main:
    main = main.replace(control_anchor, control_repl, 1)

launch_anchor = '''        if (supported) {
            app.preferences.markAutomationLaunched()
            viewModel.onLaunchResult(event.linkId, success = true, browserFallback = false)
            return
        }'''
launch_repl = '''        if (supported) {
            app.preferences.markAutomationLaunched()
            if (app.preferences.runtimeAutomationBackend == AutomationBackend.ACCESSIBILITY) {
                QuickJoinAccessibilityService.requestImmediateScan()
            }
            viewModel.onLaunchResult(event.linkId, success = true, browserFallback = false)
            return
        }'''
if launch_anchor in main:
    main = main.replace(launch_anchor, launch_repl, 1)
elif "QuickJoinAccessibilityService.requestImmediateScan()" not in main:
    raise SystemExit("ERROR: MainActivity launch kick anchor not found")

MAIN.write_text(main, encoding="utf-8")

# ============================================================
# 4) WORK / SHIZUKU PARITY:
#    X -> Back -> verify -> bounded second Back -> next link
# ============================================================
sh = SHIZUKU.read_text(encoding="utf-8")

# Result path gets the snapshot so it can identify X / conversation.
if "dismissKnownResultSurface(targetPackage, current)" in sh:
    sh = sh.replace(
        "dismissKnownResultSurface(targetPackage, current)",
        "dismissKnownResultSurface(targetPackage, current, snapshot)"
    )

# Direct-to-existing-conversation path currently advanced without leaving chat.
old_direct = '''            completeCurrent(
                current,
                LinkStatus.JOINED,
                LinkResultCode.ALREADY_MEMBER,
                "Invite deep link resolved directly to a stable group conversation after a new WhatsApp event; treated as already a member and advanced immediately"
            )'''
new_direct = '''            dismissKnownResultSurface(targetPackage, current, snapshot)
            completeCurrent(
                current,
                LinkStatus.JOINED,
                LinkResultCode.ALREADY_MEMBER,
                "Invite deep link resolved directly to a stable group conversation after a new WhatsApp event; exited the conversation before direct handoff"
            )'''
if old_direct in sh:
    sh = sh.replace(old_direct, new_direct, 1)

# Command compatibility path also must leave Conversation before next deep link.
old_command = '''                        if (action == AccessibilityJoinAction.JOIN &&
                            snapshot.inviteTarget != AccessibilityInviteTarget.COMMUNITY &&
                            probeJoinedConversationActivity(targetPackage, current)
                        ) {
                            completeCurrent(
                                current,
                                LinkStatus.JOINED,
                                LinkResultCode.JOIN_ACTION_COMPLETED,
                                "Exact-user WhatsApp Conversation activity verified after Join; next invitation opened directly"
                            )
                        }'''
new_command = '''                        if (action == AccessibilityJoinAction.JOIN &&
                            snapshot.inviteTarget != AccessibilityInviteTarget.COMMUNITY &&
                            probeJoinedConversationActivity(targetPackage, current)
                        ) {
                            exitConversationBeforeDirectHandoff(targetPackage, current)
                            completeCurrent(
                                current,
                                LinkStatus.JOINED,
                                LinkResultCode.JOIN_ACTION_COMPLETED,
                                "Exact-user WhatsApp Conversation activity verified after Join; exited before next invitation"
                            )
                        }'''
if old_command in sh:
    sh = sh.replace(old_command, new_command, 1)

old_fn = '''    /** Close the confirmed request sheet / joined conversation before launching the next link. */
    private suspend fun dismissKnownResultSurface(targetPackage: String, current: GroupLink): Boolean {
        if (!isTargetForeground(targetPackage, forceProbe = true)) return false
        waitInputCooldown()
        val persistent = ShizukuBridge.fastBack(this)
        val shell = if (!persistent) ShizukuBridge.execute(this, "input keyevent 4", 2_500) else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        runtimeDiagnostic(
            current,
            "SHIZUKU_RESULT_SURFACE_CLOSE",
            "success=$success; persistent=$persistent; exit=${shell?.exitCode ?: -1}; nextDirect=true"
        )
        if (success && ACTION_SETTLE_MS > 0L) delay(ACTION_SETTLE_MS)
        return success
    }
'''

new_fn = r'''    /**
     * Work/Secure parity with Accessibility:
     * X when present -> Back on conversation -> verify -> one bounded second Back -> next link.
     */
    private fun findResultSafeCloseNode(
        snapshot: ShizukuUiSnapshot,
        targetPackage: String
    ): ShizukuUiNode? {
        val semantic = snapshot.nodes.asSequence()
            .filter { node ->
                node.enabled && node.bounds?.valid == true && node.belongsTo(targetPackage)
            }
            .filter { node -> node.labels().any(AccessibilityJoinMatcher::isSafeClose) }
            .sortedWith(
                compareByDescending<ShizukuUiNode> { it.clickable }
                    .thenBy { it.bounds?.top ?: Int.MAX_VALUE }
                    .thenByDescending { it.bounds?.centerX ?: 0 }
            )
            .firstOrNull()
        if (semantic != null) return semantic

        // Some Work-profile WhatsApp sheets expose the X as an unlabeled clickable image.
        // Geometry fallback is allowed only on a verified invitation surface.
        if (!snapshot.inviteContext || displayWidth <= 0 || displayHeight <= 0) return null
        return snapshot.nodes.asSequence()
            .filter { node ->
                node.enabled && node.clickable && node.bounds?.valid == true &&
                    node.belongsTo(targetPackage)
            }
            .filter { node ->
                val b = node.bounds ?: return@filter false
                val w = b.right - b.left
                val h = b.bottom - b.top
                val nearTopRight = b.centerX >= displayWidth * 72 / 100 &&
                    b.centerY <= displayHeight * 32 / 100
                val compact = w in 1..(displayWidth * 18 / 100).coerceAtLeast(1) &&
                    h in 1..(displayHeight * 12 / 100).coerceAtLeast(1)
                val roughlySquare = w <= h * 2 && h <= w * 2
                nearTopRight && compact && roughlySquare
            }
            .minByOrNull { node ->
                val b = node.bounds!!
                (displayWidth - b.centerX).coerceAtLeast(0) + b.centerY.coerceAtLeast(0)
            }
    }

    private suspend fun quickResultSnapshot(targetPackage: String): ShizukuUiSnapshot? {
        val fast = ShizukuBridge.fastSnapshot(this, targetPackage, FAST_UI_MAX_NODES)
        if (!fast.success) return null
        val snapshot = ShizukuUiDumpParser.parse(fast.xml)
        if (snapshot.nodes.none { it.packageName == targetPackage }) return null
        return snapshot
    }

    private suspend fun pressResultBack(
        targetPackage: String,
        current: GroupLink,
        purpose: String
    ): Boolean {
        if (!isTargetForeground(targetPackage, forceProbe = true)) return false
        waitInputCooldown()
        val persistent = ShizukuBridge.fastBack(this)
        val shell = if (!persistent) {
            ShizukuBridge.execute(this, "input keyevent 4", 2_500)
        } else null
        lastInputAtElapsed = SystemClock.elapsedRealtime()
        val success = persistent || shell?.success == true
        runtimeDiagnostic(
            current,
            "SHIZUKU_RESULT_BACK",
            "$purpose; success=$success; persistent=$persistent; exit=${shell?.exitCode ?: -1}"
        )
        if (success) armFastBurst(FAST_ACTION_BURST_MS)
        return success
    }

    private suspend fun dismissKnownResultSurface(
        targetPackage: String,
        current: GroupLink,
        initialSnapshot: ShizukuUiSnapshot
    ): Boolean {
        if (!isTargetForeground(targetPackage, forceProbe = true)) return false
        var snapshot = initialSnapshot

        // 1) Prefer WhatsApp's actual X/Close.
        val close = findResultSafeCloseNode(snapshot, targetPackage)
        if (close?.bounds != null) {
            val closed = tapNode(close.bounds, targetPackage, current, "RESULT_SAFE_CLOSE")
            runtimeDiagnostic(
                current,
                "SHIZUKU_RESULT_X",
                "found=true; clicked=$closed; semantic=${close.labels().any(AccessibilityJoinMatcher::isSafeClose)}"
            )
            if (closed) {
                delay(ShizukuFastUiPolicy.TERMINAL_ESCAPE_SETTLE_MS)
                val after = quickResultSnapshot(targetPackage)
                if (after == null) return true
                snapshot = after
                if (!snapshot.inviteContext && !snapshot.conversationSurface) return true
            }
        }

        // 2) Conversation/result fallback: at most two verified Back attempts.
        repeat(2) { attempt ->
            if (!snapshot.inviteContext && !snapshot.conversationSurface) return true
            if (!pressResultBack(targetPackage, current, "RESULT_EXIT_${attempt + 1}")) return false

            val settle = if (fastUiMode == FastUiMode.ACTIVE) {
                ShizukuFastUiPolicy.STABLE_SCAN_MS
            } else {
                maxOf(ACTION_SETTLE_MS, ShizukuFastUiPolicy.NORMAL_EXIT_SETTLE_MS)
            }
            if (settle > 0L) delay(settle)

            val after = quickResultSnapshot(targetPackage)
            if (after == null) return true
            snapshot = after
            if (!snapshot.inviteContext && !snapshot.conversationSurface) return true
        }

        runtimeDiagnostic(
            current,
            "SHIZUKU_RESULT_EXIT_BOUNDED",
            "surface remained after X/Back recovery; next exact-user deep link will replace it"
        )
        return false
    }

    private suspend fun exitConversationBeforeDirectHandoff(
        targetPackage: String,
        current: GroupLink
    ): Boolean {
        repeat(2) { attempt ->
            if (!pressResultBack(targetPackage, current, "CONVERSATION_EXIT_${attempt + 1}")) return false

            val settle = if (fastUiMode == FastUiMode.ACTIVE) {
                ShizukuFastUiPolicy.STABLE_SCAN_MS
            } else {
                maxOf(ACTION_SETTLE_MS, ShizukuFastUiPolicy.NORMAL_EXIT_SETTLE_MS)
            }
            if (settle > 0L) delay(settle)

            val after = quickResultSnapshot(targetPackage)
            if (after != null && !after.conversationSurface && !after.inviteContext) return true

            if (after == null && !probeJoinedConversationActivity(targetPackage, current)) return true
        }

        runtimeDiagnostic(
            current,
            "SHIZUKU_CONVERSATION_EXIT_BOUNDED",
            "conversation remained after two Back attempts; direct next-link launch remains armed"
        )
        return false
    }
'''

if old_fn in sh:
    sh = sh.replace(old_fn, new_fn, 1)
elif "private fun findResultSafeCloseNode(" not in sh:
    raise SystemExit("ERROR: old Shizuku result-surface function anchor not found")

SHIZUKU.write_text(sh, encoding="utf-8")

print("============================================================")
print("AL-thmany 2.8.4 Repeat-Run + Work Parity patch APPLIED")
print("============================================================")
print("Personal:")
print("  - requires live local Accessibility service")
print("  - direct same-process scan kick after each WhatsApp launch")
print("  - completed-run state no longer blocks repeating the same input")
print("Work/Shizuku:")
print("  - X/Close detection")
print("  - verified Back from conversation")
print("  - bounded second Back")
print("  - direct conversation paths exit before next link")
print("")
print("NEXT:")
print("  python3 scripts/validate_source.py")
print("  gradle --no-daemon --stacktrace testDebugUnitTest assembleDebug")
