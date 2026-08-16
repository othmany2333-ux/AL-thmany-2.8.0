#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java"
TESTS = ROOT / "app/src/test/java"
errors: list[str] = []
warnings: list[str] = []


def parse_xml(path: Path) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except Exception as exc:
        errors.append(f"Invalid XML {path.relative_to(ROOT)}: {exc}")
        return None


# 1) XML well-formedness and known-bad attribute regression guard.
xml_files = sorted(RES.rglob("*.xml"))
for path in xml_files:
    parse_xml(path)
    text = path.read_text(encoding="utf-8", errors="ignore")
    for forbidden in ("trackActiveTint", "trackInactiveTint"):
        if forbidden in text:
            errors.append(
                f"Unsupported Material Slider attribute {forbidden} in {path.relative_to(ROOT)}"
            )

# 2) Collect default resources.
strings: set[str] = set()
colors: set[str] = set()
dimens: set[str] = set()
styles: set[str] = set()
seen_resources: dict[tuple[str, str], Path] = {}
for path in sorted((RES / "values").glob("*.xml")):
    root = parse_xml(path)
    if root is None:
        continue
    for element in root:
        name = element.get("name")
        if not name:
            continue
        key = (element.tag, name)
        previous = seen_resources.get(key)
        if previous is not None:
            errors.append(
                f"Duplicate default resource {element.tag}/{name}: "
                f"{previous.name} and {path.name}"
            )
        else:
            seen_resources[key] = path
        if element.tag == "string":
            strings.add(name)
        elif element.tag == "color":
            colors.add(name)
        elif element.tag == "dimen":
            dimens.add(name)
        elif element.tag == "style":
            styles.add(name)

drawables = {path.stem for path in (RES / "drawable").glob("*")}
layouts = {path.stem for path in (RES / "layout").glob("*.xml")}
menus = {path.stem for path in (RES / "menu").glob("*.xml")}
xml_resources = {path.stem for path in (RES / "xml").glob("*.xml")}

# 3) Referenced resource existence.
patterns = {
    "string": (re.compile(r"(?:@string/|R\.string\.)([A-Za-z0-9_]+)"), strings),
    "color": (re.compile(r"(?:@color/|R\.color\.)([A-Za-z0-9_]+)"), colors),
    "dimen": (re.compile(r"(?:@dimen/|R\.dimen\.)([A-Za-z0-9_]+)"), dimens),
    "drawable": (re.compile(r"(?:@drawable/|R\.drawable\.)([A-Za-z0-9_]+)"), drawables),
    "layout": (re.compile(r"(?:@layout/|R\.layout\.)([A-Za-z0-9_]+)"), layouts),
    "menu": (re.compile(r"(?:@menu/|R\.menu\.)([A-Za-z0-9_]+)"), menus),
    "xml": (re.compile(r"(?:@xml/|R\.xml\.)([A-Za-z0-9_]+)"), xml_resources),
}
source_files = [*RES.rglob("*.xml"), *JAVA.rglob("*.kt"), *TESTS.rglob("*.kt")]
for kind, (pattern, available) in patterns.items():
    refs: set[str] = set()
    for path in source_files:
        refs.update(pattern.findall(path.read_text(encoding="utf-8", errors="ignore")))
    for missing in sorted(refs - available):
        # Built-in Android resources are not part of app resources.
        if missing.startswith("android_"):
            continue
        errors.append(f"Missing {kind} resource: {missing}")

# 4) Arabic/English catalog parity.
def string_names(path: Path) -> set[str]:
    root = parse_xml(path)
    if root is None:
        return set()
    return {
        item.get("name")
        for item in root
        if item.tag == "string" and item.get("name")
    }

ar_strings = string_names(RES / "values/strings.xml")
en_strings = string_names(RES / "values-en/strings.xml")
for value in sorted(ar_strings - en_strings):
    errors.append(f"Missing English string: {value}")
for value in sorted(en_strings - ar_strings):
    errors.append(f"Missing Arabic string: {value}")

# 5) ViewBinding IDs referenced by the two main screens.
for activity_name, layout_name in (
    ("MainActivity", "activity_main"),
    ("SettingsActivity", "activity_settings"),
):
    kotlin_path = JAVA / f"com/althmany/groupmanager/ui/{activity_name}.kt"
    layout_path = RES / f"layout/{layout_name}.xml"
    layout_ids = set(
        re.findall(r"@\+id/([A-Za-z0-9_]+)", layout_path.read_text(encoding="utf-8"))
    )
    binding_refs = set(
        re.findall(r"\bbinding\.([A-Za-z][A-Za-z0-9_]*)", kotlin_path.read_text(encoding="utf-8"))
    )
    for value in sorted(binding_refs - layout_ids - {"root"}):
        errors.append(f"Missing ViewBinding ID for {activity_name}: {value}")

# 6) Manifest and automation-policy invariants.
build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
service_config = (RES / "xml/accessibility_service_config.xml").read_text(encoding="utf-8")
policy = (JAVA / "com/althmany/groupmanager/domain/AutomationPolicy.kt").read_text(encoding="utf-8")
matcher = (JAVA / "com/althmany/groupmanager/domain/AccessibilityJoinMatcher.kt").read_text(encoding="utf-8")
service = (JAVA / "com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt").read_text(encoding="utf-8")
adaptive_policy = (JAVA / "com/althmany/groupmanager/domain/AdaptiveInteractionPolicy.kt").read_text(encoding="utf-8")
terminal_escape_policy = (JAVA / "com/althmany/groupmanager/domain/TerminalEscapePolicy.kt").read_text(encoding="utf-8")
evidence_policy = (JAVA / "com/althmany/groupmanager/domain/ScreenEvidencePolicy.kt").read_text(encoding="utf-8")
runtime_confidence = (JAVA / "com/althmany/groupmanager/domain/RuntimeIntelligencePolicy.kt").read_text(encoding="utf-8")
runtime_coordinator = (JAVA / "com/althmany/groupmanager/domain/RuntimeDecisionCoordinator.kt").read_text(encoding="utf-8")
runtime_fingerprint = (JAVA / "com/althmany/groupmanager/domain/RuntimeScreenFingerprint.kt").read_text(encoding="utf-8")
runtime_circuit = (JAVA / "com/althmany/groupmanager/domain/RuntimeCircuitBreaker.kt").read_text(encoding="utf-8")
continuous_handoff = (JAVA / "com/althmany/groupmanager/domain/ContinuousHandoffPolicy.kt").read_text(encoding="utf-8")
runtime_idempotency = (JAVA / "com/althmany/groupmanager/domain/RuntimeIdempotencyGuard.kt").read_text(encoding="utf-8")
runtime_cadence = (JAVA / "com/althmany/groupmanager/domain/RuntimeCadencePolicy.kt").read_text(encoding="utf-8")
runtime_speed_profile = (JAVA / "com/althmany/groupmanager/domain/RuntimeSpeedProfile.kt").read_text(encoding="utf-8")
runtime_recovery = (JAVA / "com/althmany/groupmanager/domain/RuntimeRecoveryPolicy.kt").read_text(encoding="utf-8")
runtime_diagnostics = (JAVA / "com/althmany/groupmanager/util/RuntimeDiagnosticStore.kt").read_text(encoding="utf-8")
foreground_policy = (JAVA / "com/althmany/groupmanager/domain/ForegroundTargetPolicy.kt").read_text(encoding="utf-8")
database = (JAVA / "com/althmany/groupmanager/data/GroupLinkDatabase.kt").read_text(encoding="utf-8")
repository = (JAVA / "com/althmany/groupmanager/data/GroupLinkRepository.kt").read_text(encoding="utf-8")
settings_layout = (RES / "layout/activity_settings.xml").read_text(encoding="utf-8")
main_layout = (RES / "layout/activity_main.xml").read_text(encoding="utf-8")
profile_environment = (JAVA / "com/althmany/groupmanager/util/ProfileEnvironment.kt").read_text(encoding="utf-8")
profile_control_policy = (JAVA / "com/althmany/groupmanager/domain/ProfileControlPolicy.kt").read_text(encoding="utf-8")
native_profile_engine_policy = (JAVA / "com/althmany/groupmanager/domain/NativeProfileEnginePolicy.kt").read_text(encoding="utf-8")
profile_accessibility_runtime = (JAVA / "com/althmany/groupmanager/util/ProfileAccessibilityRuntime.kt").read_text(encoding="utf-8")
accessibility_status = (JAVA / "com/althmany/groupmanager/accessibility/AccessibilityStatus.kt").read_text(encoding="utf-8")
accessibility_settings_launcher = (JAVA / "com/althmany/groupmanager/util/AccessibilitySettingsLauncher.kt").read_text(encoding="utf-8")
whatsapp_launcher = (JAVA / "com/althmany/groupmanager/util/WhatsAppLauncher.kt").read_text(encoding="utf-8")
preferences_source = (JAVA / "com/althmany/groupmanager/data/AppPreferences.kt").read_text(encoding="utf-8")
community_matcher = (JAVA / "com/althmany/groupmanager/domain/CommunityTraversalMatcher.kt").read_text(encoding="utf-8")
community_policy = (JAVA / "com/althmany/groupmanager/domain/CommunityTraversalPolicy.kt").read_text(encoding="utf-8")
main_activity = (JAVA / "com/althmany/groupmanager/ui/MainActivity.kt").read_text(encoding="utf-8")
shizuku_service = (JAVA / "com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt").read_text(encoding="utf-8")
shizuku_shell_service = (JAVA / "com/althmany/groupmanager/shizuku/ShizukuShellUserService.kt").read_text(encoding="utf-8")
shizuku_persistent = (JAVA / "com/althmany/groupmanager/shizuku/PersistentUiAutomationBridge.kt").read_text(encoding="utf-8")
shizuku_bridge_source = (JAVA / "com/althmany/groupmanager/shizuku/ShizukuBridge.kt").read_text(encoding="utf-8")
shizuku_aidl_source = (ROOT / "app/src/main/aidl/com/althmany/groupmanager/shizuku/IShizukuShellService.aidl").read_text(encoding="utf-8")
shizuku_parser = (JAVA / "com/althmany/groupmanager/domain/ShizukuUiDumpParser.kt").read_text(encoding="utf-8")
visual_action_policy = (JAVA / "com/althmany/groupmanager/domain/VisualActionButtonPolicy.kt").read_text(encoding="utf-8")
shizuku_policy = (JAVA / "com/althmany/groupmanager/domain/ShizukuRuntimePolicy.kt").read_text(encoding="utf-8")
hybrid_policy = (JAVA / "com/althmany/groupmanager/domain/HybridBackendPolicy.kt").read_text(encoding="utf-8")
fast_exit_policy = (JAVA / "com/althmany/groupmanager/domain/ConversationFastExitPolicy.kt").read_text(encoding="utf-8")
shizuku_fast_policy = (JAVA / "com/althmany/groupmanager/domain/ShizukuFastUiPolicy.kt").read_text(encoding="utf-8")
shizuku_continuity = (JAVA / "com/althmany/groupmanager/domain/ShizukuContinuityPolicy.kt").read_text(encoding="utf-8")
shizuku_launch_policy = (JAVA / "com/althmany/groupmanager/domain/ShizukuLaunchPolicy.kt").read_text(encoding="utf-8")
group_app = (JAVA / "com/althmany/groupmanager/GroupManagerApp.kt").read_text(encoding="utf-8")

checks = {
    "3.0 five speed modes": all(token in runtime_speed_profile for token in ["STABLE", "FAST", "TURBO", "MAX", "CUSTOM", "eventScanMs", "postTapWaitMs", "interLinkDelayMs"]),
    "3.0 professional link phases": all(token in runtime_speed_profile for token in ["OPENING", "PREVIEW", "ACTION_READY", "ACTION_TAPPED", "VERIFYING", "EXITING", "ADVANCING"]),
    "3.0 Smart Exit controller": all(token in runtime_speed_profile for token in ["SAFE_CLOSE", "TERMINAL_ACK", "SAFE_CANCEL", "BACK", "DIRECT_NEXT_DEEP_LINK"]) and "isSafeDialogCancel" in matcher,
    "3.0 persistent resume state": all(token in preferences_source for token in ["runtimeLockedAndroidUserId", "runtimeCurrentLinkId", "runtimeCurrentLinkUrl", "runtimeActionExecuted", "runtimeRecoveryReopenAttempts", "lastCompletedLinkPosition"]),
    "3.0 restriction user policy": "RestrictionHandlingMode" in runtime_speed_profile and "continueOnRestrictionSwitch" in main_activity and "restrictionHandlingMode" in preferences_source,
    "3.0 compact dashboard": all(token in main_layout for token in ["speedModeToggleGroup", "customSpeedControls", "resumeLastRunButton", "advancedSettingsButton", "advancedSmartCard", "advancedScheduleCard"]),
    "Hybrid auto engine failover 2.9.0": "ACCESSIBILITY_UNBOUND_SHIZUKU_FALLBACK" in native_profile_engine_policy and "isAnyAutomationEngineReady" in main_activity and "المحرك المختار تلقائيًا: Shizuku السريع" in main_activity,

    "hybrid Shizuku to Accessibility fallback": "accessibilityMayTakeOver" in hybrid_policy and "chooseForStart" in hybrid_policy,
    "conversation fast exit policy": "settleMs" in fast_exit_policy and "shouldAttemptBack" in fast_exit_policy,
    "profile-key launch continuity": "expectedProfileKey" in whatsapp_launcher,
    "AL-thmany namespace": 'namespace = "com.althmany.groupmanager"' in build,
    "AL-thmany application id": 'applicationId = "com.althmany.groupmanager"' in build,
    "Al-othmany Sender stable package": 'applicationId = "com.althmany.groupmanager"' in build and "Theme.AlOthmanySender.Main" in manifest,
    "3.2 Remote Secure host-Shizuku lane": all(token in preferences_source for token in [
        "remoteSecureFolderEnabled", "remoteSecureAndroidUserId", "remoteSecureWhatsAppPackage", "setRemoteSecureTarget"
    ]) and all(token in shizuku_service for token in [
        "SHIZUKU_REMOTE_SECURE_USER_READY", "Remote Secure target could not be verified"
    ]) and "secureRemoteButton" in main_layout,
    "3.2 request-sheet close rescue": "looksLikeRequestSheetCloseCandidate" in service and "requestSheetCorner" in shizuku_service,
    "3.3 screen awake runtime": "keepScreenAwake" in preferences_source and
        (JAVA / "com/althmany/groupmanager/util/AutomationScreenAwakeGuard.kt").exists() and
        "keepScreenAwakeSwitch" in settings_layout and
        "AutomationScreenAwakeGuard" in service and
        "AutomationScreenAwakeGuard" in shizuku_service,
    "3.3 request X Back continuity": "fastExitPendingRequestSurface(screen: ScreenInspection): Boolean" in service and
        "X/Back handoff opened the next invitation" in service and
        "REQUEST_SENT_SHEET_BACK_FALLBACK" in shizuku_service,
    "3.3 secure classifier": "never relabel Work/Island/Dual Messenger as Secure Folder" in main_activity and
        ("secure_remote_knox_hidden" in main_activity or "secure_remote_knox_ladder" in main_activity),
    "3.3 evidence counter repair": "visualExpectedAction" in shizuku_service and
        "not counted as REQUESTED" in shizuku_service and
        "Fast watchdog recovered a verified exact-user WhatsApp conversation after Join" in shizuku_service,
    "3.4 long-run Shizuku recovery": all(token in shizuku_service for token in [
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
        "dual_app" in main_activity.lower(),
    "3.4 Secure multi-source discovery": "pm list users" in main_activity and
        "cmd user list" in main_activity and
        "dumpsys user" in main_activity and
        "secureMarker" in main_activity,
    "3.4 pending approval post-click": "postClickApprovalVariant" in service and
        "pendingApprovalVariant" in shizuku_service,
    "3.4.1 cooldown is non-recursive": "realCommandKill: Boolean = false" in shizuku_service and
        "No UI dump ran during cooldown" in shizuku_service,
    "3.4.1 verification grace": all(token in shizuku_service for token in [
        "DUMP_FAILURE_MIN_INTERVAL_MS",
        "POST_ACTION_RESULT_GRACE_MS",
        "VERIFY_RESULT_POLL_MS",
        "age < ShizukuContinuityPolicy.UI_TREE_ADVANCE_AFTER_MS ||",
        "readPendingAction(current) ?: visualExpectedAction"
    ]),
    "3.4.1 UserService hard recovery": "restartUserService" in shizuku_bridge_source and
        "SHIZUKU_USER_SERVICE_RESTART" in shizuku_service,
    "3.5 user-exit pause and exact-target resume": all(token in service for token in [
        "ACCESSIBILITY_TARGET_RETURN_WAITING_MANUAL_RESUME",
        "ACCESSIBILITY_TARGET_RETURN_AUTO_RESUME",
        "ACCESSIBILITY_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET"
    ]) and all(token in shizuku_service for token in [
        "SHIZUKU_TARGET_RETURN_AUTO_RESUME",
        "SHIZUKU_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET"
    ]),
    "3.4.1 Secure persona discovery": "dumpsys persona" in main_activity and
        "personaIdRegex" in main_activity,
    "versionCode 357": "versionCode = 357" in build,
    "versionName 3.5.0": 'versionName = "3.5.0"' in build,
    "3.0.2 Shizuku shell visual recovery": all(token in shizuku_shell_service for token in [
        "/system/bin/screencap", "SHELL_SCREENCAP", "VisualActionButtonPolicy", "BitmapFactory.decodeByteArray"
    ]),
    "3.0.3 Shizuku user-exit pause resume": all(token in shizuku_service for token in [
        "pauseAfterStableForegroundLoss", "tryAutoResumeAfterUserReturn", "SHIZUKU_USER_EXIT_AUTO_PAUSE", "SHIZUKU_USER_RETURN_AUTO_RESUME"
    ]) and "app.preferences.autoPauseOutsideWhatsApp = true" in main_activity,
    "3.0.3 repeat-run persistent UI reset": all(token in shizuku_service for token in [
        "SHIZUKU_FAST_UI_RUN_RESET", "SHIZUKU_FAST_UI_SELF_HEAL", "fastResetUiAutomation"
    ]) and "resetForNewRun" in shizuku_persistent and "fastResetUiAutomation" in shizuku_bridge_source and "fastResetUiAutomation" in shizuku_aidl_source,
    "3.0.4 leave WhatsApp pauses without force reopen": "currentLaunchSawTargetForeground" in shizuku_service and "shouldAutoPauseForUserExit" in shizuku_service and "USER_EXIT_CONFIRM_MS = 90L" in shizuku_service,
    "3.0.4 reliable scroll gestures": "isSafeSwipeBounds" in shizuku_policy and "GESTURE_DURATION_MS = 72L" in shizuku_fast_policy and "dispatchReliableScrollGesture" in service,
    "3.0.4 Excel picker support": "spreadsheetml.sheet" in main_activity and "application/vnd.ms-excel" in main_activity,
    "Accessibility permission gate 2.6.4": all(token in main_activity for token in ["permissionConfigured", "waitForLocalAccessibilityBind", "shouldPromptAccessibilitySetup", "accessibility_enabled_but_not_bound"]),
    "Accessibility setup debounce policy 2.6.4": all(token in profile_control_policy for token in ["ACCESSIBILITY_SETUP_CONFIRM_READS = 3", "ACCESSIBILITY_RECONNECT_WAIT_MS = 4_000L", "ACCESSIBILITY_RECONNECT_POLL_MS = 100L", "shouldPromptAccessibilitySetup"]),
    "Accessibility Samsung live readiness 2.7.4": all(token in accessibility_status for token in ["isRuntimeConnected", "secureSettingEnabled", "isLocalConnectionAlive", "profileHeartbeatConnected = runtime.localServiceConnected"]) and all(token in service for token in ["override fun onCreate()", "PROFILE_SERVICE_EVENT_RECOVERED"]),
    "Accessibility 2.4.4-style enabled start 2.8.8": "isQuickJoinServiceEnabled(this@MainActivity)" in main_activity and "binding.root.postDelayed" in main_activity,
    "Accessibility direct gesture action repair 2.7.4": all(token in service for token in ["gestureFirst = gestureFirst", "SHADOW_AUTO_DISABLED", "attemptOrdinal", "dispatchSemanticGesture"]),
    "Accessibility visual fallback 2.7.5": all(token in service for token in ["maybeHandleAccessibilityVisualFallback", "ACCESSIBILITY_VISUAL_ACTION_TAP", "captureWidePositiveActionBounds", "VisualActionButtonPolicy", "DIRECT_CONVERSATION_STABLE_SCANS"]) and 'android:canTakeScreenshot="true"' in service_config,
    "executable runtime migration 2.7.2": "applyExecutableRuntimeMigration" in preferences_source and "applyExecutableRuntimeMigration" in group_app and "app.preferences.runtimeShadowMode = false" in main_activity,
    "large disk-backed queue limit": "MAX_LINKS_PER_SESSION = 1_000_000" in policy,
    "full-session explicit run window": "BATCH_SIZE = MAX_LINKS_PER_SESSION" in policy,
    "sub-second unified handoff control": all(token in policy for token in ["MAX_INTER_LINK_DELAY_MS = 10_000", "INTER_LINK_DELAY_STEP_MS = 100", "clampInterLinkDelayMs"]) and "interLinkDelayMs" in preferences_source,
    "Accessibility service manifest": "QuickJoinAccessibilityService" in manifest,
    "profile accessibility policy inspector": (ROOT / "app/src/main/java/com/althmany/groupmanager/util/ProfileAccessibilityPolicyInspector.kt").exists(),
    "native multi-profile router": (ROOT / "app/src/main/java/com/althmany/groupmanager/util/NativeProfileEngineRouter.kt").exists() and (ROOT / "app/src/main/java/com/althmany/groupmanager/domain/NativeProfileEnginePolicy.kt").exists(),
    "optional Work Profile DPC receiver": "WorkProfileAdminReceiver" in manifest and (ROOT / "app/src/main/java/com/althmany/groupmanager/admin/WorkProfileAdminReceiver.kt").exists(),
    "Work Profile accessibility policy controller": (ROOT / "app/src/main/java/com/althmany/groupmanager/util/WorkProfileController.kt").exists(),
    "native profile engine UI": "nativeProfileEngineStatusText" in settings_layout and "applyWorkAccessibilityPolicyButton" in settings_layout,
    "three accessibility settings routes": all(token in (ROOT / "app/src/main/java/com/althmany/groupmanager/util/AccessibilitySettingsLauncher.kt").read_text(encoding="utf-8") for token in ["openServiceDetails", "openAccessibilityList", "openCurrentProfileAppInfo", "component.flattenToString()"]),
    "Shizuku API dependency": 'dev.rikka.shizuku:api:13.1.5' in build and 'dev.rikka.shizuku:provider:13.1.5' in build,
    "Shizuku provider manifest": "rikka.shizuku.ShizukuProvider" in manifest,
    "Shizuku foreground automation service": "ShizukuAutomationService" in manifest and "foregroundServiceType=\"specialUse\"" in manifest,
    "Shizuku shell user service": (JAVA / "com/althmany/groupmanager/shizuku/ShizukuShellUserService.kt").exists(),
    "persistent Shizuku UiAutomation bridge": (JAVA / "com/althmany/groupmanager/shizuku/PersistentUiAutomationBridge.kt").exists() and "fastSnapshot" in shizuku_service and "SHIZUKU_FAST_UI_ACTIVE" in shizuku_service,
    "persistent Shizuku direct input": "fastTap" in shizuku_service and "fastBack" in shizuku_service and "fastClickNode" in shizuku_service and "cachedClickableNodes" in (JAVA / "com/althmany/groupmanager/shizuku/PersistentUiAutomationBridge.kt").read_text(encoding="utf-8"),
    "Shizuku compact parity event frame": "waitAndSnapshot" in shizuku_service and "EVENT_TREE_COALESCE_MS = 0L" in shizuku_fast_policy and "EVENT_SCAN_MS = 12L" in shizuku_fast_policy and "STABLE_SCAN_MS = 30L" in shizuku_fast_policy and "FALLBACK_POLL_MS = 80L" in shizuku_fast_policy,
    "Shizuku 3.1 reliable cadence preset": all(token in shizuku_fast_policy for token in ["CLICK_THROTTLE_MS = 60L", "GESTURE_DURATION_MS = 72L", "RESULT_ANALYSIS_FALLBACK_MS = 72L", "ACTION_RETRY_AFTER_MS = 95L", "POST_JOIN_MIN_EVIDENCE_MS = 30L", "NON_LOADING_WATCHDOG_MS = 1_000L", "UNKNOWN_TIMEOUT_MS = 2_000L", "LOADING_TIMEOUT_MS = 20_000L", "USER_INSTANT_ADVANCE_SETTLE_MS = 0L"]),
    "Shizuku 2.7.1 continuity policy": all(token in shizuku_continuity for token in ["FOREGROUND_REOPEN_AFTER_MS = 260L", "MAX_FOREGROUND_REOPEN_ATTEMPTS = 1", "FOREGROUND_ADVANCE_AFTER_MS = 1_100L", "NO_ROOT_ADVANCE_AFTER_MS = 1_200L", "UI_TREE_ADVANCE_AFTER_MS = 2_200L", "DIRECT_CONVERSATION_MIN_AGE_MS = 35L"]),
    "Shizuku 2.7.1 transition fast path": all(token in shizuku_service for token in ["SHIZUKU_FAST_UI_ARMED", "resultCommitExecuting", "armFastEventSequence", "forceResolvedActivity = true", "window=skipped-fast", "sequence > currentLaunchEventBaseline"]) and "TARGET_HIDDEN_FOREGROUND_REPROBE_MS = 260L" in shizuku_continuity,
    "Shizuku Work Profile compatibility repair 2.7.3": all(token in shizuku_service for token in ["SHIZUKU_PROFILE_COMPAT_PROBE", "SHIZUKU_PROFILE_COMPAT_ACTIVE", "profileCompatibilityProbe", "fastUiMode = FastUiMode.DISABLED"]) and all(token in shizuku_continuity for token in ["PROFILE_COMPAT_COMMAND_PROBE_AFTER_MS = 180L", "MAX_PROFILE_COMPAT_COMMAND_PROBES = 2", "shouldProbeProfileCompatibleTree"]),
    "Shizuku visual Work action repair 2.7.4": all(token in shizuku_service for token in ["handleVisualProfileFallback", "SHIZUKU_VISUAL_ACTION_TAP", "fixedCoordinate=false", "dismissVisualActionSurface"]) and all(token in visual_action_policy for token in ["findWidePositiveAction", "MIN_BUTTON_WIDTH_PERCENT = 52", "isWhatsAppGreen"]),
    "Shizuku semantic launch repair 2.7.2": all(token in shizuku_service for token in ["ShizukuLaunchPolicy.launchAccepted", "resolveDeepLinkActivity", "startDeepLink", "retriedResolved"]) and all(token in shizuku_launch_policy for token in ["exitCode != 0", "unable to resolve intent", "permission denial"]),
    "Shizuku 2.7 adaptive fast compatibility": all(token in shizuku_service for token in ["fastUiMode = FastUiMode.UNKNOWN", "/system/bin/uiautomator dump --compressed", "COMMAND_DUMP_COMPAT_RETRY_MS = 25L", "SHIZUKU_UI_TREE_BACK_HANDOFF", "pause=false; next=true"]),
    "Shizuku exact-user activity join proof": all(token in shizuku_service for token in ["probeJoinedConversationActivity", "SHIZUKU_ACTIVITY_JOIN_PROOF", "ShizukuActivityProofPolicy", "ACTIVITY_PROBE_TIMEOUT_MS", "ACTIVITY_PROBE_ATTEMPTS = 4", "ACTIVITY_PROBE_RETRY_MS = 25L"]) and (JAVA / "com/althmany/groupmanager/domain/ShizukuActivityProofPolicy.kt").exists(),
    "Shizuku 2.6.2 direct conversation event guard": all(token in shizuku_service for token in ["currentLaunchEventBaseline", "currentLaunchSawTargetEvent", "SHIZUKU_DIRECT_CONVERSATION_HANDOFF", "LinkResultCode.ALREADY_MEMBER"]),
    "Shizuku 2.6.2 no-stall continuity": all(token in shizuku_service for token in ["SHIZUKU_FOREGROUND_CONTINUITY_ADVANCE", "SHIZUKU_AMBIGUOUS_CONTINUITY_ADVANCE", "SHIZUKU_UNKNOWN_CONTINUITY_ADVANCE", "SHIZUKU_NO_ROOT_CONTINUITY_ADVANCE", "SHIZUKU_UI_TREE_BACK_HANDOFF", "SHIZUKU_INPUT_CONTINUITY_RECOVERY", "SHIZUKU_RUNTIME_RECONNECT_WAIT"]),
    "Shizuku 2.6.2 request terminal direct handoff": all(token in shizuku_service for token in ["SHIZUKU_REQUEST_SUBMITTED_HANDOFF", "SHIZUKU_REQUEST_TERMINAL_PROBE", "REQUEST_TERMINAL_PROBE_MIN_AGE_MS", "REQUEST_TERMINAL_PROBE_COOLDOWN_MS"]),
    "Shizuku direct conversation close and handoff": "SHIZUKU_FAST_CONVERSATION_HANDOFF" in shizuku_service and "dismissKnownResultSurface" in shizuku_service,
    "Shizuku foreground timeout guard": "FOREGROUND_WAIT_STOP_MS" in shizuku_service,
    "profile-aware Shizuku foreground detector": "topResumedActivity" in shizuku_service and "u$userId" in shizuku_service and "lastForegroundProbeSummary" in shizuku_service,
    "Shizuku semantic compact/XML parser": (JAVA / "com/althmany/groupmanager/domain/ShizukuUiDumpParser.kt").exists() and "__AL_FAST_COMPACT__=" in shizuku_parser and "parseCompact" in shizuku_parser,
    "Shizuku package-aware action scoring": "actionSelection" in shizuku_parser and "MIN_SCORE_MARGIN" in shizuku_policy,
    "Shizuku adaptive action consensus": "ACTION_CONSENSUS_SCANS = 2" in shizuku_policy and "actionConsensusScans" in shizuku_policy and "SHIZUKU_FAST_ACTION" in shizuku_service,
    "Shizuku fast foreground lease": "FOREGROUND_LEASE_MS" in shizuku_policy and "foregroundLeaseValid" in shizuku_service,
    "Shizuku direct handoff": "SHIZUKU_DIRECT_HANDOFF" in shizuku_service and "DirectAdvanceState" in shizuku_service and "Opening next invitation directly" in shizuku_service,
    "Shizuku successful join closes before next": all(token in shizuku_service for token in ["findResultSafeCloseNode", "SHIZUKU_RESULT_X", "SHIZUKU_RESULT_BACK", "exitConversationBeforeDirectHandoff"]),
    "Shizuku launch no wait": "am start --user $userId -a android.intent.action.VIEW" in shizuku_service and "SHIZUKU_DIRECT_LAUNCH" in shizuku_service,
    "Shizuku throttled result mirror": "RESULT_MIRROR_SYNC_EVERY = 10" in shizuku_service,
    "Shizuku safe display-bounds gate": "isSafeTapBounds" in shizuku_policy and "resolveDisplaySize" in shizuku_service,
    "Shizuku capability preflight before queue": "preflightRuntime" in shizuku_service and shizuku_service.find("preflightRuntime(targetPackage)") < shizuku_service.find("loadAutomationCurrent"),
    "Shizuku exact current-profile UID lock": "Process.myUid()" in shizuku_service and "pm list packages -U --user" in shizuku_service and "uid:${processUid}" in shizuku_service,
    "Shizuku connection heartbeat": "runtimeHeartbeat" in shizuku_service and "CAPABILITY_RECHECK_MS" in shizuku_policy,
    "Shizuku unknown-state continuity advance": "SHIZUKU_UNKNOWN_CONTINUITY_ADVANCE" in shizuku_service and "without guessing" in shizuku_service.lower(),
    "Shizuku community traversal": "communityGroupCandidates" in shizuku_parser and "SHIZUKU_COMMUNITY_GROUP_OPEN" in shizuku_service and "CommunityTraversalStage.PROCESSING_GROUP" in shizuku_service,
    "Shizuku community no-progress bound": "COMMUNITY_NO_PROGRESS_LIMIT = 3" in shizuku_service,
    "hybrid engine selector": "automationBackendRadioGroup" in settings_layout and "AutomationBackend.SHIZUKU" in main_activity,
    "WhatsApp personal package": "com.whatsapp" in whatsapp_launcher,
    "WhatsApp Business package": "com.whatsapp.w4b" in whatsapp_launcher,
    "cloned WhatsApp package": "com.whatsapp2" in whatsapp_launcher,
    "Samsung resolver packages": "com.android.intentresolver" in service and "com.samsung.android.app.sharelive" in service,
    "start control": "startAutomationButton" in main_layout,
    "fast hands-free control": "fastHandsFreeSwitch" in main_layout,
    "automatic resume control": "autoResumeSwitch" in main_layout and "autoResumeCurrentRun" in preferences_source,
    "auto pause outside WhatsApp control": "autoPauseOutsideWhatsAppSwitch" in main_layout and "autoPauseOutsideWhatsApp" in preferences_source,
    "return to dashboard control": "returnToAppSwitch" in main_layout and "returnToAppOnRunComplete" in service,
    "dynamic WhatsApp app picker": "chooseInstalledWhatsAppButton" in main_layout and "discoverWhatsAppApps" in whatsapp_launcher,
    "profile-local work/secure support": (JAVA / "com/althmany/groupmanager/util/ProfileEnvironment.kt").exists() and "profileSupportText" in main_layout,
    "profile-local Accessibility heartbeat": "SERVICE_HEARTBEAT_FRESH_MS = 12_000L" in profile_control_policy and "recordServiceConnected" in profile_accessibility_runtime and "localServiceConnected" in accessibility_status,
    "profile-specific Accessibility detail launcher": "android.settings.ACCESSIBILITY_DETAILS_SETTINGS" in accessibility_settings_launcher and "Intent.EXTRA_COMPONENT_NAME" in accessibility_settings_launcher,
    "profile control diagnostics UI": "profileControlDiagnosticText" in settings_layout and "profile_control_diagnostic_format" in (RES / "values/strings.xml").read_text(encoding="utf-8"),
    "secondary-profile root preservation": "PROFILE_UI_TREE_BLOCKED" in service and "current link preserved" in service.lower(),
    "managed-profile API 30 guard": "Build.VERSION_CODES.R" in profile_environment,
    "profile kind classification": all(token in profile_environment for token in ("MANAGED_WORK", "SAMSUNG_ISOLATED", "SECONDARY", "isSystemUser")),
    "strict profile-local target validation": "validateTarget" in whatsapp_launcher and "requiresExplicitAutoTarget" in whatsapp_launcher and "strictProfileTarget" in whatsapp_launcher,
    "runtime package+profile target lock": "runtimeLockedWhatsAppPackage" in preferences_source and "runtimeLockedProfileKey" in preferences_source and "lockRuntimeTarget" in preferences_source and "lockedProfile != profile.profileKey" in service,
    "profile-local target test control": "testWhatsAppTargetButton" in main_layout and "testSelectedWhatsAppTarget" in main_activity,
    "isolated-profile auto target guard": "profile_explicit_target_required" in (RES / "values/strings.xml").read_text(encoding="utf-8") and "requiresExplicitAutoTarget" in main_activity,
    "scheduled target validation before queue mutation": service.find("val targetValidation = WhatsAppLauncher.validateTarget") < service.find("val launchState = withContext(Dispatchers.IO)"),
    "community traversal UI control": "communityTraversalSwitch" in main_layout and "communityTraversalEnabled" in preferences_source,
    "community traversal matcher": all(token in community_matcher for token in ("isCommunityHomeAcross", "looksLikeGroupRow", "isAnnouncement", "isBlocked", "stableGroupKey")),
    "community traversal bounded policy": all(token in community_policy for token in ("MAX_GROUPS_PER_COMMUNITY = 512", "MAX_SCROLL_ATTEMPTS = 80", "MAX_RETURN_BACK_STEPS = 3", "GROUP_OPEN_TIMEOUT_MS")),
    "community traversal state machine": all(token in service for token in ("maybeHandleCommunityTraversal", "handleCommunityHome", "completeCommunitySubgroupAndContinue", "finishCommunityTraversal", "CommunityTraversalStage.OPENING_GROUP", "CommunityTraversalStage.RETURNING_TO_COMMUNITY")),
    "community subgroup exactly-once tracking": "if (safeKey in current)" in preferences_source and "KEY_COMMUNITY_PROCESSED_GROUP_KEYS" in preferences_source,
    "community destructive-row exclusion": all(token in community_matcher for token in ("announcements", "add group", "manage groups", "leave community", "report", "delete", "remove", "block")),
    "schedule controls": all(
        item in main_layout
        for item in ("startModeToggleGroup", "startDelaySlider", "chooseDateButton", "chooseClockButton")
    ),
    "community matcher": "join community" in matcher and "انضمام إلى المجتمع" in matcher,
    "request matcher": "request to join" in matcher and "طلب الانضمام" in matcher,
    "verified terminal escape retries": "FAST_MAX_EXIT_STEPS = 3" in service and "FAST_TERMINAL_SETTLE_MS = 18L" in runtime_cadence,
    "terminal acknowledgement priority": "terminalSurface && inspection.terminalAcknowledgementNode != null" in service,
    "blocked action guard": "isBlockedAction" in matcher,
    "gesture fallback": "dispatchGesture" in service,
    "automatic next-link flow": "Opening next invitation" in service and "user-selected speed" in service,
    "automatic invitation exit": "exitInvitationSurface" in service and "isSafeClose" in matcher,
    "user-controlled sub-second next-link delay": "MIN_INTER_LINK_DELAY_MS = 0" in policy and "MAX_INTER_LINK_DELAY_MS = 10_000" in policy and "INTER_LINK_DELAY_STEP_MS = 100" in policy and "MIN_ACTION_TIMEOUT_SECONDS = 1" in policy and "MAX_ACTION_TIMEOUT_SECONDS = 60" in policy,
    "unified professional interface": "unified_smart_title" in main_layout and "unified_live_title" in main_layout,
    "restriction stop": "RESTRICTED" in service,
    "request-pending skip": "تم إرسال الطلب وفي انتظار موافقة المشرف" in matcher,
    "pre-request approval guard": "isRequestApprovalNotice" in matcher and "isCancelRequest" in matcher,
    "compact community join": "انضمام للمجتمع" in matcher,
    "reset-link skip": "تمت إعادة تعيين رابط الدعوة" in matcher,
    "removed-account skip": "REMOVED_OR_BANNED" in matcher and "REMOVED_OR_BANNED" in service,
    "safe next-link inside WhatsApp": "Opening next invitation" in service and "GLOBAL_ACTION_BACK" in service,
    "destructive-id precedence": "cancel_join_button" in (TESTS / "com/althmany/groupmanager/domain/AccessibilityJoinMatcherTest.kt").read_text(encoding="utf-8") and "isDestructiveNormalized" in matcher,
    "12.6 precision fusion targets": "AccessibilityInviteTarget" in matcher and "targetType" in matcher,
    "terminal acknowledgement separated from actions": "terminalAcknowledgementLabels" in matcher and "isTerminalAcknowledgement" in matcher,
    "current removed screenshot wording": "حيث قد تمت إزالتك منها" in matcher,
    "split terminal screen aggregation": "failureTypeAcross" in matcher and "aggregateFailure" in service,
    "split request screen aggregation": "isRequestSubmittedAcross" in matcher and "isRequestSubmittedAcross(visibleLabels.asSequence())" in service,
    "generic denial terminal escape": "لا يمكنك الانضمام إلى هذه المجموعة" in matcher and "AccessibilityFailureType.GENERIC" in matcher,
    "order-independent classifier": "Classification is deliberately order-independent" in (JAVA / "com/althmany/groupmanager/domain/AccessibilityScreenClassifier.kt").read_text(encoding="utf-8"),
    "request result requires evidence": "waiting for explicit pending/request-sent evidence" in service,
    "bounded safe ancestor click": "isSafeActionContainer" in service and "MAX_PARENT_DEPTH = 5" in service,
    "queued batch continuation": "canContinueQueuedBatch" in main_activity and "autopilot_continue_next_batch" in (RES / "values/strings.xml").read_text(encoding="utf-8"),
    "loading-state recognition": "isLoading" in matcher and "RuntimeDirective.WAIT_LOADING" in service and "handleLoading(current)" in service,
    "no close while loading": "waiting without closing or reopening it" in service,
    "stable action evidence": "ACTION_STABLE_SCANS" in (JAVA / "com/althmany/groupmanager/domain/InvitationStabilityPolicy.kt").read_text(encoding="utf-8") and "shouldClickStableAction" in service,
    "join disappearance is not instant success": "waiting for a stable post-action screen" in service,
    "same-link relaunch disabled": "MAX_UNKNOWN_RELAUNCHES = 0" in (JAVA / "com/althmany/groupmanager/domain/InvitationStabilityPolicy.kt").read_text(encoding="utf-8") and "relaunchCurrentInvitation" not in service,
    "adaptive repeated outcome evidence": "OUTCOME_STABLE_SCANS = 2" in adaptive_policy and "handleStableTerminalEvidence" in service,
    "serialized accessibility scan coalescing": "scanPending" in service and "Coalesce Accessibility event bursts" in service,
    "lightweight current-link runtime lookup": "loadAutomationCurrent" in service and "loadCurrentOpened" in (JAVA / "com/althmany/groupmanager/data/GroupLinkDatabase.kt").read_text(encoding="utf-8"),
    "throttled result mirror sync": "RESULT_MIRROR_SYNC_EVERY = 10" in service and "syncResultMirrorIfNeeded" in service,
    "bounded dashboard link rendering": "UI_LINK_WINDOW_SIZE = 120" in main_activity and "loadDashboardSnapshot" in database,
    "debounced input analysis": "INPUT_ANALYSIS_DEBOUNCE_MS = 180L" in main_activity,
    "adaptive home-search advance without reopen": "isWhatsAppHomeSurface" in matcher and "HOME_SURFACE_STABLE_SCANS = 3" in adaptive_policy and "TURBO_HOME_ADVANCE_AFTER_MS = 250L" in adaptive_policy and "shouldAdvanceFromHome" in service,
    "joined conversation return": "isConversationComposer" in matcher and "isConversationAction" in matcher and "conversationSurface" in service and "returnFromJoinedConversationAndAdvance" in service,
    "continuous handoff watchdog": "scheduleContinuousHandoffWatchdog" in service and "TURBO_NON_LOADING_HARD_LIMIT_MS = 1_000L" in continuous_handoff,
    "fast joined-conversation continuity": "ConversationFastExitPolicy.settleMs" in service and "surfaceAlreadyExited = true" in service,
    "request-pending immediate handoff": "inspection.requestSubmitted" in service and "Join request is pending; X/Back handoff opened the next invitation" in service,
    "structural failures continue instead of parking run": "CIRCUIT_BREAKER_CONTINUE" in service and "consecutiveRuntimeFailures = 0" in service,
    "single-scan conversation confirmation": "CONVERSATION_STABLE_SCANS = 1" in (JAVA / "com/althmany/groupmanager/domain/InvitationStabilityPolicy.kt").read_text(encoding="utf-8"),
    "input link de-duplication": "putIfAbsent" in (JAVA / "com/althmany/groupmanager/domain/WhatsAppLinkParser.kt").read_text(encoding="utf-8"),
    "post-loading settle guard": "RECENT_LOADING_SETTLE_MS" in adaptive_policy and "shouldWaitAfterLoading" in service,
    "stable unknown evidence": "UNKNOWN_STABLE_SCANS_BEFORE_FAILURE" in adaptive_policy and "unknownIsStableEnough" in service,
    "context conflict guard": "TERMINAL_WITH_POSITIVE_ACTION" in evidence_policy and "shouldHoldConflictingEvidence" in service,
    "multiple positive-action conflict guard": "MULTIPLE_POSITIVE_ACTIONS" in evidence_policy,
    "strong WhatsApp home evidence": "isWhatsAppHomeTab" in matcher and "homeTabEvidenceCount >= 2" in service,
    "pending action consistency guard": "actionAllowedWhilePending" in evidence_policy and "Ignoring an unrelated stale invitation control" in service,
    "instant terminal precedence over stale loading": runtime_coordinator.find("screen.immediateTerminal") < runtime_coordinator.find("screen.loading"),
    "terminal escape policy": "TerminalEscapeMode.IMMEDIATE" in terminal_escape_policy and "bypassInterLinkDelay" in terminal_escape_policy,
    "terminal escape bypasses user delay": "terminalEscapeAdvance" in service and "Terminal Escape: opening the next invitation immediately" in service,
    "smart runtime confidence engine": "MIN_ACTION_CONFIDENCE" in runtime_confidence and "assessAction" in service and "assessTerminal" in service,
    "runtime confidence backward-compatible margin": "candidateScoreMargin: Int = 0" in runtime_confidence,
    "screen fingerprint stability": "FNV_OFFSET_BASIS" in runtime_fingerprint and "updateScreenFingerprintStability" in service,
    "runtime circuit breaker diagnostics": "MAX_CONSECUTIVE_RUNTIME_FAILURES = 6" in runtime_circuit and "RuntimeFeatureFlags.CIRCUIT_BREAKER" in service,
    "runtime decision coordinator": "RuntimeDecisionCoordinator.decide" in service and "STOP_RESTRICTED" in runtime_coordinator,
    "single-flight action executor": "actionExecuting" in service and "compareAndSet(false, true)" in service,
    "exactly-once result commit guard": "resultCommitExecuting" in service and "resultCommitExecuting.compareAndSet(false, true)" in service,
    "event-noise reduction": "RELEVANT_EVENT_TYPES" in service and 'android:notificationTimeout="20"' in service_config and "typeViewFocused" not in service_config,
    "indexed runtime queue": "index_links_session_status_position" in database and "DATABASE_VERSION = 5" in database,
    "lightweight dashboard database snapshot": "loadActiveDashboardSnapshot" in repository and "querySessionStats" in database,
    "shadow observation mode": "runtimeShadowMode" in preferences_source and "runtimeShadowSwitch" in settings_layout,
    "diagnostic journal controls": "runtimeDiagnosticJournal" in preferences_source and "shareRuntimeDiagnosticsButton" in settings_layout,
    "runtime replay regression helper": (JAVA / "com/althmany/groupmanager/domain/RuntimeReplayEngine.kt").exists(),
    "watchdog policy": (JAVA / "com/althmany/groupmanager/domain/RuntimeWatchdogPolicy.kt").exists() and "RuntimeWatchdogPolicy.assess" in service,
    "idempotency guard": "shouldAllow" in runtime_idempotency and "recordSuccess" in runtime_idempotency and "idempotencyGuard.shouldAllow" in service,
    "privacy-safe rotating diagnostics": "MAX_BYTES = 256 * 1024L" in runtime_diagnostics and "never stores message contents" in runtime_diagnostics.lower(),
    "runtime micro-benchmark": (ROOT / "scripts/run_runtime_benchmarks.sh").exists() and (ROOT / "scripts/RuntimeBenchmarkMain.kt").exists(),
    "live runtime health line": (JAVA / "com/althmany/groupmanager/util/RuntimeHealthMonitor.kt").exists() and "runtime_health_format" in (RES / "values/strings.xml").read_text(encoding="utf-8") and "RuntimeHealthMonitor.snapshot" in main_activity,
    "adaptive event-first cadence": all(token in runtime_speed_profile for token in ("eventScanMs", "stableScanMs", "fallbackPollMs", "clickThrottleMs")) and all(token in service for token in ("runtimeSpeed()", "speed.eventScanMs", "speed.stableScanMs", "runtimeSpeed().fallbackPollMs")),
    "missing-root self recovery": "handleUnavailableRoot" in service and "FAST_ROOT_UNAVAILABLE_TIMEOUT_MS = 2_500L" in runtime_recovery,
    "recoverable accessibility interruption": all(token in service for token in [
        "override fun onInterrupt()",
        "Do not destroy the persisted run state here",
        "override fun onServiceConnected()"
    ]),
    "actionable stalled-screen recovery": "shouldAdvanceStalledUnknown" in runtime_recovery and "Self-recovery advanced an inert WhatsApp screen" in service,
    "stable post-action force advance": "MIN_STABLE_SCANS_FOR_FORCE_ADVANCE = 2" in continuous_handoff and "stableWatchScans" in service,
    "auto-pause outside WhatsApp default": "getBoolean(KEY_AUTO_PAUSE_OUTSIDE_WHATSAPP, true)" in preferences_source,
    "zero-delay instant handoff": "USER_INSTANT_ADVANCE_SETTLE_MS = 0L" in service,
    "3.5 network pause guard": "NetworkStateMonitor" in shizuku_service and
        "SHIZUKU_NETWORK_AUTO_PAUSE" in shizuku_service and
        "ACCESSIBILITY_NETWORK_AUTO_PAUSE" in service,
    "3.5 no forced next handoff": "SHIZUKU_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET" in shizuku_service and
        "ACCESSIBILITY_NEXT_HANDOFF_PAUSED_OUTSIDE_TARGET" in service,
    "3.5 verified retry queue": "requeueFailed" in repository and
        "retryUnverifiedButton" in main_layout and
        "sender_unverified_processing" in main_activity,
    "3.5 Work remote target": "detectRemoteWorkTarget" in main_activity and
        "workRemoteButton" in main_layout,
    "fast post-join evidence age": "FAST_POST_JOIN_MIN_EVIDENCE_AGE_MS = 35L" in (JAVA / "com/althmany/groupmanager/domain/InvitationStabilityPolicy.kt").read_text(encoding="utf-8"),
    "bounded fast semantic invite scroll": "ACTION_SCROLL_FORWARD" in service and "MAX_INVITE_SCROLL_ATTEMPTS = 2" in service and "FAST_SCROLL" in service,
    "zero-delay enables fast runtime": "delayMs == AutomationPolicy.FAST_INTER_LINK_DELAY_MS" in main_activity,
    "fast foreground departure guard": "OUTSIDE_TARGET_CONFIRM_MS = 140L" in foreground_policy and "RECENT_TARGET_GRACE_MS = 120L" in foreground_policy and "scheduleAutoPauseOutsideTarget" in service,
    "persistent runtime notification refresh": "refreshAutomationNotification(force = true" in service and "NOTIFICATION_REFRESH_MIN_MS = 750L" in service,
    "compact installed-WhatsApp picker": 'android:id="@+id/targetToggleGroup"' in main_layout and 'android:visibility="gone"' in main_layout and "chooseInstalledWhatsAppButton" in main_layout,
}
for label, ok in checks.items():
    if not ok:
        errors.append(f"Check failed: {label}")

# 7) Workflow safety and build artifact checks.
workflow = (ROOT / ".github/workflows/android-ci.yml").read_text(encoding="utf-8")
if "name: AL-thmany Android CI" not in workflow:
    errors.append("Workflow name is not AL-thmany Android CI")
if "name: al-thmany-debug-apk" not in workflow:
    errors.append("Workflow APK artifact is not al-thmany-debug-apk")

for required in (
    "workflow_dispatch:",
    "python3 scripts/validate_source.py",
    "bash scripts/compile_pure_kotlin.sh",
    "bash scripts/run_pure_kotlin_regressions.sh",
    "bash scripts/run_runtime_benchmarks.sh",
    "sdk install kotlin 2.3.21",
    "testDebugUnitTest",
    "lintDebug",
    "assembleDebug",
    "actions/upload-artifact@v4",
):
    if required not in workflow:
        errors.append(f"Workflow missing: {required}")

if errors:
    print("SOURCE VALIDATION FAILED")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)

print("SOURCE VALIDATION PASSED")
for label in checks:
    print(f"- {label}")
print(f"- XML files: {len(xml_files)}")
print(f"- Arabic strings: {len(ar_strings)}")
print(f"- English strings: {len(en_strings)}")
if warnings:
    print("WARNINGS")
    for warning in warnings:
        print(f"- {warning}")
