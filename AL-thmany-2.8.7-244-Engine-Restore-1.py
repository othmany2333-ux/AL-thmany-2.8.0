#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
MAIN = ROOT / 'app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt'
GRADLE = ROOT / 'app/build.gradle.kts'
VALIDATOR = ROOT / 'scripts/validate_source.py'

for p in (MAIN, GRADLE, VALIDATOR):
    if not p.exists():
        raise SystemExit(f'ERROR: missing {p}. Run this from the repository root.')

# ------------------------------------------------------------
# Version -> 2.8.7
# ------------------------------------------------------------
gradle = GRADLE.read_text(encoding='utf-8')
gradle = re.sub(r'versionCode\s*=\s*285', 'versionCode = 287', gradle, count=1)
gradle = re.sub(r'versionName\s*=\s*"2\.8\.5"', 'versionName = "2.8.7"', gradle, count=1)
if 'versionCode = 287' not in gradle or 'versionName = "2.8.7"' not in gradle:
    raise SystemExit('ERROR: could not set version 2.8.7')
GRADLE.write_text(gradle, encoding='utf-8')

validator = VALIDATOR.read_text(encoding='utf-8')
validator = validator.replace(
    '"versionCode 285": "versionCode = 285" in build,',
    '"versionCode 287": "versionCode = 287" in build,'
)
validator = validator.replace(
    '"versionName 2.8.5": \'versionName = "2.8.5"\' in build,',
    '"versionName 2.8.7": \'versionName = "2.8.7"\' in build,'
)
VALIDATOR.write_text(validator, encoding='utf-8')

main = MAIN.read_text(encoding='utf-8')

# ------------------------------------------------------------
# 1) Restore 2.4.4 onResume startup semantics.
#    Do not wait for the profile heartbeat/local callback before starting.
#    Android's enabled-service state is enough to launch WhatsApp, whose
#    Accessibility event then activates/self-heals the engine.
# ------------------------------------------------------------
old_resume = '''        if (pendingAutoStartAfterSettings) {
            val continueQueuedBatch = pendingQueueContinuationAfterSettings
            val readiness = AccessibilityStatus.readiness(this@MainActivity)
            when {
                ProfileControlPolicy.mayStartWhileServiceBinds(readiness.systemEnabled) &&
                    readiness.localServiceConnected -> {
                    pendingAutoStartAfterSettings = false
                    pendingQueueContinuationAfterSettings = false
                    // Do not deadlock on Samsung's delayed service callback. The enabled service
                    // receives the WhatsApp window event and self-heals its runtime connection.
                    binding.root.postDelayed({ startAutomaticRun(allowQueuedContinuation = continueQueuedBatch) }, 180L)
                }
                else -> {
                    // Do not reopen the setup dialog from one transient negative read after an
                    // APK update/resume. The bind gate confirms a genuinely disabled service.
                    waitForLocalAccessibilityBind(continueQueuedBatch, startAfterBind = true)
                }
            }
        }'''

new_resume = '''        // 2.4.4 engine semantics: once Android reports the service configured, do not
        // deadlock the run waiting for a profile-local heartbeat. Opening WhatsApp is the
        // authoritative activation event; QuickJoinAccessibilityService self-heals on that event.
        if (pendingAutoStartAfterSettings &&
            AccessibilityStatus.isQuickJoinServiceEnabled(this)
        ) {
            val continueQueuedBatch = pendingQueueContinuationAfterSettings
            pendingAutoStartAfterSettings = false
            pendingQueueContinuationAfterSettings = false
            binding.root.postDelayed(
                { startAutomaticRun(allowQueuedContinuation = continueQueuedBatch) },
                120L
            )
        }'''

if old_resume in main:
    main = main.replace(old_resume, new_resume, 1)
elif '2.4.4 engine semantics' not in main:
    raise SystemExit('ERROR: onResume 2.8.5 anchor not found')

# ------------------------------------------------------------
# 2) Restore the 2.4.4 setup button behavior: settings is the
#    explicit source of truth; do not spin in a local-bind wait loop.
# ------------------------------------------------------------
old_setup = '''        setupServiceButton.setOnClickListener {
            val readiness = AccessibilityStatus.readiness(this@MainActivity)
            if (readiness.systemEnabled) {
                waitForLocalAccessibilityBind(allowQueuedContinuation = false, startAfterBind = false)
            } else {
                showOneTimeSetupDialog(false)
            }
        }'''
new_setup = '''        setupServiceButton.setOnClickListener {
            // Same interaction model as 2.4.4: open the one-time Android setup instead of
            // trapping the dashboard in a local-service reconnect loop.
            showOneTimeSetupDialog(false)
        }'''
if old_setup in main:
    main = main.replace(old_setup, new_setup, 1)
elif 'Same interaction model as 2.4.4' not in main:
    raise SystemExit('ERROR: setupServiceButton anchor not found')

# ------------------------------------------------------------
# 3) Start always restores JOIN mode + Fast preset like 2.4.4.
# ------------------------------------------------------------
old_start_head = '''    private fun startAutomaticRun(allowQueuedContinuation: Boolean = false) {
        // Pressing Start is an explicit instruction to execute. Never let a developer-only Shadow
        // preference inherited from an older build suppress every Join while the UI says running.
        app.preferences.runtimeShadowMode = false
'''
new_start_head = '''    private fun startAutomaticRun(allowQueuedContinuation: Boolean = false) {
        // 2.4.4 normal-run contract: Start always means JOIN mode and Fast preset.
        app.preferences.linkScanModeEnabled = false
        app.preferences.fastHandsFreeMode = true
        applyFastHandsFreePreset()

        // Pressing Start is an explicit instruction to execute. Never let a developer-only Shadow
        // preference inherited from an older build suppress every Join while the UI says running.
        app.preferences.runtimeShadowMode = false
'''
if old_start_head in main:
    main = main.replace(old_start_head, new_start_head, 1)
elif '2.4.4 normal-run contract' not in main:
    raise SystemExit('ERROR: startAutomaticRun head anchor not found')

# ------------------------------------------------------------
# 4) Queued-run Accessibility gate: 2.4.4 checks only whether the
#    service is enabled/configured, never localServiceConnected.
# ------------------------------------------------------------
old_queue_gate = '''            if (backend == AutomationBackend.ACCESSIBILITY) {
                val readiness = AccessibilityStatus.readiness(this@MainActivity)
                if (!readiness.systemEnabled || !readiness.localServiceConnected) {
                    pendingAutoStartAfterSettings = true
                    pendingQueueContinuationAfterSettings = true
                    app.preferences.accessibilityQuickJoin = true
                    waitForLocalAccessibilityBind(allowQueuedContinuation = true, startAfterBind = true)
                    return
                }
            }'''
new_queue_gate = '''            if (backend == AutomationBackend.ACCESSIBILITY &&
                !AccessibilityStatus.isQuickJoinServiceEnabled(this)
            ) {
                pendingAutoStartAfterSettings = true
                pendingQueueContinuationAfterSettings = true
                app.preferences.accessibilityQuickJoin = true
                showOneTimeSetupDialog(true)
                return
            }'''
if old_queue_gate in main:
    main = main.replace(old_queue_gate, new_queue_gate, 1)
elif 'pendingQueueContinuationAfterSettings = true\n                app.preferences.accessibilityQuickJoin = true\n                showOneTimeSetupDialog(true)' not in main:
    raise SystemExit('ERROR: queued Accessibility gate anchor not found')

# ------------------------------------------------------------
# 5) New-run Accessibility gate: same 2.4.4 rule.
# ------------------------------------------------------------
old_new_gate = '''        if (backend == AutomationBackend.ACCESSIBILITY) {
            val readiness = AccessibilityStatus.readiness(this@MainActivity)
            if (!readiness.systemEnabled || !readiness.localServiceConnected) {
                pendingAutoStartAfterSettings = true
                pendingQueueContinuationAfterSettings = false
                app.preferences.accessibilityQuickJoin = true
                waitForLocalAccessibilityBind(allowQueuedContinuation = false, startAfterBind = true)
                return
            }
        }'''
new_new_gate = '''        if (backend == AutomationBackend.ACCESSIBILITY &&
            !AccessibilityStatus.isQuickJoinServiceEnabled(this)
        ) {
            pendingAutoStartAfterSettings = true
            pendingQueueContinuationAfterSettings = false
            app.preferences.accessibilityQuickJoin = true
            showOneTimeSetupDialog(true)
            return
        }'''
if old_new_gate in main:
    main = main.replace(old_new_gate, new_new_gate, 1)
elif 'pendingQueueContinuationAfterSettings = false\n            app.preferences.accessibilityQuickJoin = true\n            showOneTimeSetupDialog(true)' not in main:
    raise SystemExit('ERROR: new-run Accessibility gate anchor not found')

# ------------------------------------------------------------
# 6) Dashboard readiness mirrors 2.4.4: configured/enabled is ready.
#    Keep local heartbeat as diagnostics only, not a hard blocker.
# ------------------------------------------------------------
old_render = '''        val readiness = AccessibilityStatus.readiness(this@MainActivity)
        val serviceEnabled = readiness.systemEnabled && readiness.localServiceConnected
        val permissionConfigured = readiness.systemEnabled'''
new_render = '''        val readiness = AccessibilityStatus.readiness(this@MainActivity)
        // 2.4.4 behavior: system-configured Accessibility is sufficient to run.
        // localServiceConnected remains diagnostic and self-heals on the WhatsApp event.
        val serviceEnabled = readiness.systemEnabled
        val permissionConfigured = readiness.systemEnabled'''
if old_render in main:
    main = main.replace(old_render, new_render, 1)
elif '2.4.4 behavior: system-configured Accessibility is sufficient to run.' not in main:
    raise SystemExit('ERROR: renderRuntimeState readiness anchor not found')

MAIN.write_text(main, encoding='utf-8')

print('=============================================================')
print('AL-thmany 2.8.7 — 2.4.4 Engine Restore APPLIED')
print('=============================================================')
print('Personal / Accessibility:')
print('  - no localServiceConnected hard gate before opening WhatsApp')
print('  - Start restores JOIN mode + Fast preset')
print('  - WhatsApp event activates/self-heals Accessibility runtime')
print('  - second/third runs are not trapped in reconnect waiting')
print('  - current 2.8.5 X / Back / direct handoff logic is preserved')
print('Work / Shizuku:')
print('  - current Work parity improvements remain preserved')
print('  - no Knox/DPC boundary bypass attempted')
print('')
print('NEXT: python3 scripts/validate_source.py')
