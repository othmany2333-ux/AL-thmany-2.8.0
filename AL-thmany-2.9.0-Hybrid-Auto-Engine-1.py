#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()
BUILD = ROOT / "app/build.gradle.kts"
POLICY = ROOT / "app/src/main/java/com/althmany/groupmanager/domain/NativeProfileEnginePolicy.kt"
MAIN = ROOT / "app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt"
REG = ROOT / "scripts/PureKotlinRegressionMain.kt"
VALIDATOR = ROOT / "scripts/validate_source.py"

for p in (BUILD, POLICY, MAIN, REG, VALIDATOR):
    if not p.exists():
        raise SystemExit(f"ERROR: missing {p}. Run this from repository root.")

# 1) Version -> 2.9.0 (accept current 2.8.8 or optional local 2.8.9)
s = BUILD.read_text(encoding="utf-8")
s = re.sub(r'versionCode\s*=\s*(?:288|289)\b', 'versionCode = 290', s, count=1)
s = re.sub(r'versionName\s*=\s*"2\.8\.(?:8|9)"', 'versionName = "2.9.0"', s, count=1)
if 'versionCode = 290' not in s or 'versionName = "2.9.0"' not in s:
    raise SystemExit("ERROR: expected project version 2.8.8 or 2.8.9")
BUILD.write_text(s, encoding="utf-8")

# 2) Hybrid backend policy
s = POLICY.read_text(encoding="utf-8")

old_policy = '''        if (requested == AutomationBackend.ACCESSIBILITY) {
            if (accessibilityLocalReady) {
                return NativeEngineDecision(AutomationBackend.ACCESSIBILITY, NativeEngineSetupAction.NONE, "ACCESSIBILITY_LOCAL_READY")
            }
            if (profileClass == NativeProfileClass.MANAGED_WORK && workPolicyBlocksSelf && selfCanManageWorkPolicy) {
                return NativeEngineDecision(null, NativeEngineSetupAction.APPLY_WORK_ACCESSIBILITY_POLICY, "WORK_POLICY_FIX_REQUIRED")
            }
            return NativeEngineDecision(AutomationBackend.ACCESSIBILITY, NativeEngineSetupAction.ENABLE_LOCAL_ACCESSIBILITY, "LOCAL_ACCESSIBILITY_ENABLE_REQUIRED")
        }'''

new_policy = '''        if (requested == AutomationBackend.ACCESSIBILITY) {
            if (accessibilityLocalReady) {
                return NativeEngineDecision(
                    AutomationBackend.ACCESSIBILITY,
                    NativeEngineSetupAction.NONE,
                    "ACCESSIBILITY_LOCAL_READY"
                )
            }

            // Hybrid failover: if Accessibility is enabled/stale but not locally bound,
            // use the already-authorized persistent Shizuku UiAutomation engine.
            if (shizukuReady) {
                return NativeEngineDecision(
                    AutomationBackend.SHIZUKU,
                    NativeEngineSetupAction.NONE,
                    "ACCESSIBILITY_UNBOUND_SHIZUKU_FALLBACK"
                )
            }

            if (profileClass == NativeProfileClass.MANAGED_WORK &&
                workPolicyBlocksSelf &&
                selfCanManageWorkPolicy
            ) {
                return NativeEngineDecision(
                    null,
                    NativeEngineSetupAction.APPLY_WORK_ACCESSIBILITY_POLICY,
                    "WORK_POLICY_FIX_REQUIRED"
                )
            }

            return NativeEngineDecision(
                AutomationBackend.ACCESSIBILITY,
                NativeEngineSetupAction.ENABLE_LOCAL_ACCESSIBILITY,
                "LOCAL_ACCESSIBILITY_ENABLE_REQUIRED"
            )
        }'''

if old_policy in s:
    s = s.replace(old_policy, new_policy, 1)
elif "ACCESSIBILITY_UNBOUND_SHIZUKU_FALLBACK" not in s:
    raise SystemExit("ERROR: NativeProfileEnginePolicy Accessibility branch not found")

POLICY.write_text(s, encoding="utf-8")

# 3) MainActivity hybrid readiness + visible test indicator
s = MAIN.read_text(encoding="utf-8")

s = s.replace(
    'val serviceEnabled = readiness.systemEnabled && readiness.localServiceConnected',
    'val serviceEnabled = isAnyAutomationEngineReady()'
)
s = s.replace(
    'val serviceEnabled = readiness.systemEnabled\n        val permissionConfigured = readiness.systemEnabled',
    'val serviceEnabled = isAnyAutomationEngineReady()\n        val permissionConfigured = readiness.systemEnabled'
)

old_button = '''        setupServiceButton.setOnClickListener {
            val readiness = AccessibilityStatus.readiness(this@MainActivity)
            if (readiness.systemEnabled) {
                waitForLocalAccessibilityBind(allowQueuedContinuation = false, startAfterBind = false)
            } else {
                showOneTimeSetupDialog(false)
            }
        }'''

new_button = '''        setupServiceButton.setOnClickListener {
            val readiness = AccessibilityStatus.readiness(this@MainActivity)
            when {
                isAnyAutomationEngineReady() -> renderRuntimeState()
                readiness.systemEnabled ->
                    waitForLocalAccessibilityBind(
                        allowQueuedContinuation = false,
                        startAfterBind = false
                    )
                else -> showOneTimeSetupDialog(false)
            }
        }'''

if old_button in s:
    s = s.replace(old_button, new_button, 1)

s = s.replace(
    'renderReadiness(AccessibilityStatus.isQuickJoinServiceConnectedLocally(this))',
    'renderReadiness(isAnyAutomationEngineReady())'
)
s = s.replace(
    'renderReadiness(AccessibilityStatus.isQuickJoinServiceEnabled(this))',
    'renderReadiness(isAnyAutomationEngineReady())'
)

helper_anchor = '''    private fun renderRuntimeState() = with(binding) {'''
helper_code = '''    private fun isAnyAutomationEngineReady(): Boolean {
        val accessibilityReady =
            AccessibilityStatus.isQuickJoinServiceConnectedLocally(this@MainActivity)
        if (accessibilityReady) return true
        return runCatching { ShizukuBridge.status().ready }.getOrDefault(false)
    }

'''

if "private fun isAnyAutomationEngineReady()" not in s:
    if helper_anchor not in s:
        raise SystemExit("ERROR: renderRuntimeState anchor not found")
    s = s.replace(helper_anchor, helper_code + helper_anchor, 1)

old_runnable = '''        if (decision.runnable) {
            if (requested == AutomationBackend.SHIZUKU && decision.backend == AutomationBackend.ACCESSIBILITY) {'''

new_runnable = '''        if (decision.runnable) {
            if (decision.backend == AutomationBackend.SHIZUKU &&
                requested != AutomationBackend.SHIZUKU
            ) {
                Toast.makeText(
                    this,
                    "المحرك المختار تلقائيًا: Shizuku السريع",
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (requested == AutomationBackend.SHIZUKU && decision.backend == AutomationBackend.ACCESSIBILITY) {'''

if old_runnable in s:
    s = s.replace(old_runnable, new_runnable, 1)
elif 'المحرك المختار تلقائيًا: Shizuku السريع' not in s:
    raise SystemExit("ERROR: resolveAutomationBackendForStart runnable branch not found")

MAIN.write_text(s, encoding="utf-8")

# 4) Regression tests
s = REG.read_text(encoding="utf-8")

test_anchor = '''    expect("native router prefers local accessibility", AutomationBackend.ACCESSIBILITY, nativePersonal.backend)

'''

test_block = '''    expect("native router prefers local accessibility", AutomationBackend.ACCESSIBILITY, nativePersonal.backend)

    val nativePersonalShizukuFallback = NativeProfileEnginePolicy.choose(
        requested = AutomationBackend.ACCESSIBILITY,
        profileClass = NativeProfileClass.OWNER,
        accessibilityLocalReady = false,
        shizukuReady = true,
        selfCanManageWorkPolicy = false,
        workPolicyBlocksSelf = false
    )
    expect(
        "explicit accessibility uses shizuku when accessibility is unbound",
        AutomationBackend.SHIZUKU,
        nativePersonalShizukuFallback.backend
    )
    expect(
        "hybrid shizuku fallback needs no setup dialog",
        NativeEngineSetupAction.NONE,
        nativePersonalShizukuFallback.setupAction
    )

'''

if "nativePersonalShizukuFallback" not in s:
    if test_anchor not in s:
        raise SystemExit("ERROR: nativePersonal regression anchor not found")
    s = s.replace(test_anchor, test_block, 1)

REG.write_text(s, encoding="utf-8")

# 5) Validator version + invariant
s = VALIDATOR.read_text(encoding="utf-8")

s = re.sub(
    r'"versionCode (?:288|289)": "versionCode = (?:288|289)" in build,',
    '"versionCode 290": "versionCode = 290" in build,',
    s,
    count=1
)
s = re.sub(
    r'"versionName 2\.8\.(?:8|9)": \'versionName = "2\.8\.(?:8|9)"\' in build,',
    '"versionName 2.9.0": \'versionName = "2.9.0"\' in build,',
    s,
    count=1
)

policy_read_anchor = 'profile_control_policy = (JAVA / "com/althmany/groupmanager/domain/ProfileControlPolicy.kt").read_text(encoding="utf-8")'
native_read_line = 'native_profile_engine_policy = (JAVA / "com/althmany/groupmanager/domain/NativeProfileEnginePolicy.kt").read_text(encoding="utf-8")'
if native_read_line not in s:
    if policy_read_anchor not in s:
        raise SystemExit("ERROR: validator profile policy anchor not found")
    s = s.replace(policy_read_anchor, policy_read_anchor + "\n" + native_read_line, 1)

checks_anchor = "checks = {"
hybrid_check = '    "Hybrid auto engine failover 2.9.0": "ACCESSIBILITY_UNBOUND_SHIZUKU_FALLBACK" in native_profile_engine_policy and "isAnyAutomationEngineReady" in main_activity and "المحرك المختار تلقائيًا: Shizuku السريع" in main_activity,\n'
if '"Hybrid auto engine failover 2.9.0"' not in s:
    if checks_anchor not in s:
        raise SystemExit("ERROR: validator checks anchor not found")
    s = s.replace(checks_anchor, checks_anchor + "\n" + hybrid_check, 1)

VALIDATOR.write_text(s, encoding="utf-8")

print("AL-thmany 2.9.0 HYBRID AUTO ENGINE APPLIED")
print("")
print("Selection:")
print(" - Accessibility local READY -> Accessibility")
print(" - Accessibility unbound + Shizuku READY -> Shizuku")
print(" - Shizuku unavailable + Accessibility local READY -> Accessibility")
print(" - AUTO both READY -> Accessibility")
print(" - AUTO only Shizuku READY -> Shizuku")
print("")
print("UNCHANGED:")
print(" - 2.8.8 speed timings")
print(" - Work/Shizuku X + Back + verify")
print(" - 0ms inter-link handoff")
print("")
print("NEXT:")
print("  python3 scripts/validate_source.py")
print("  grep -nE 'versionCode|versionName' app/build.gradle.kts")
