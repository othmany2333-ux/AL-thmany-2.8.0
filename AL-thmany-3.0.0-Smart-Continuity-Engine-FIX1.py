#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path.cwd()

def p(rel: str) -> Path:
    return ROOT / rel

BUILD = p("app/build.gradle.kts")
PREFS = p("app/src/main/java/com/althmany/groupmanager/data/AppPreferences.kt")
STATE = p("app/src/main/java/com/althmany/groupmanager/domain/AutomationState.kt")
MATCHER = p("app/src/main/java/com/althmany/groupmanager/domain/AccessibilityJoinMatcher.kt")
QUICK = p("app/src/main/java/com/althmany/groupmanager/accessibility/QuickJoinAccessibilityService.kt")
SHIZUKU = p("app/src/main/java/com/althmany/groupmanager/shizuku/ShizukuAutomationService.kt")
MAIN = p("app/src/main/java/com/althmany/groupmanager/ui/MainActivity.kt")
LAYOUT = p("app/src/main/res/layout/activity_main.xml")
STR_AR = p("app/src/main/res/values/strings.xml")
STR_EN = p("app/src/main/res/values-en/strings.xml")
RUN_REG = p("scripts/run_pure_kotlin_regressions.sh")
REG = p("scripts/PureKotlinRegressionMain.kt")
VALIDATOR = p("scripts/validate_source.py")
SPEED_FILE = p("app/src/main/java/com/althmany/groupmanager/domain/RuntimeSpeedProfile.kt")

required = [
    BUILD, PREFS, STATE, MATCHER, QUICK, SHIZUKU, MAIN, LAYOUT,
    STR_AR, STR_EN, RUN_REG, REG, VALIDATOR
]
for path in required:
    if not path.exists():
        raise SystemExit(f"ERROR: missing {path}. Run from repository root.")

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")

def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"ERROR: anchor not found: {label}")
    return text.replace(old, new, 1)

def insert_before(text: str, anchor: str, content: str, label: str) -> str:
    if content.strip() in text:
        return text
    if anchor not in text:
        raise SystemExit(f"ERROR: anchor not found: {label}")
    return text.replace(anchor, content + anchor, 1)

def add_strings(path: Path, items: list[tuple[str, str]]) -> None:
    text = read(path)
    for key, value in items:
        if f'name="{key}"' not in text:
            text = text.replace(
                "</resources>",
                f'    <string name="{key}">{value}</string>\n</resources>',
                1
            )
    write(path, text)

# ============================================================
# 0) Require 2.9.0 and move to 3.0.0
# ============================================================
build = read(BUILD)
if 'versionCode = 300' not in build:
    if 'versionCode = 290' not in build or 'versionName = "2.9.0"' not in build:
        raise SystemExit(
            "ERROR: Smart Continuity 3.0.0 expects AL-thmany 2.9.0. "
            "Do not apply the old 2.9.1 patch first."
        )
    build = build.replace("versionCode = 290", "versionCode = 300", 1)
    build = build.replace('versionName = "2.9.0"', 'versionName = "3.0.0"', 1)
write(BUILD, build)

# ============================================================
# 1) New unified speed / phase / restriction policies
# ============================================================
speed_source = r'''package com.althmany.groupmanager.domain

enum class RuntimeSpeedMode {
    STABLE,
    FAST,
    TURBO,
    MAX,
    CUSTOM
}

enum class RestrictionHandlingMode {
    STOP_RUN,
    SKIP_AND_CONTINUE
}

enum class LinkRuntimePhase {
    OPENING,
    PREVIEW,
    ACTION_READY,
    ACTION_TAPPED,
    VERIFYING,
    EXITING,
    ADVANCING
}

enum class SmartResultClass {
    JOINED,
    REQUESTED,
    ALREADY_MEMBER,
    GROUP_FULL,
    INVALID,
    REMOVED,
    RESTRICTED,
    FAILED,
    UNKNOWN
}

data class RuntimeSpeedProfile(
    val eventScanMs: Long,
    val stableScanMs: Long,
    val fallbackPollMs: Long,
    val postTapWaitMs: Long,
    val interLinkDelayMs: Long,
    val clickThrottleMs: Long,
    val gestureDurationMs: Long,
    val watchdogIntervalMs: Long,
    val unknownRecoveryAfterMs: Long,
    val actionRetryAfterMs: Long
)

object RuntimeSpeedProfilePolicy {
    const val MIN_CUSTOM_SCAN_MS = 6
    const val MAX_CUSTOM_SCAN_MS = 250
    const val MIN_CUSTOM_POST_TAP_MS = 0
    const val MAX_CUSTOM_POST_TAP_MS = 1_000
    const val MIN_CUSTOM_INTER_LINK_MS = 0
    const val MAX_CUSTOM_INTER_LINK_MS = 10_000

    fun resolve(
        mode: RuntimeSpeedMode,
        customScanMs: Int = 12,
        customPostTapMs: Int = 55,
        customInterLinkMs: Int = 0
    ): RuntimeSpeedProfile = when (mode) {
        RuntimeSpeedMode.STABLE -> RuntimeSpeedProfile(
            eventScanMs = 30L,
            stableScanMs = 70L,
            fallbackPollMs = 160L,
            postTapWaitMs = 110L,
            interLinkDelayMs = 250L,
            clickThrottleMs = 90L,
            gestureDurationMs = 24L,
            watchdogIntervalMs = 90L,
            unknownRecoveryAfterMs = 1_600L,
            actionRetryAfterMs = 260L
        )
        RuntimeSpeedMode.FAST -> RuntimeSpeedProfile(
            eventScanMs = 14L,
            stableScanMs = 36L,
            fallbackPollMs = 95L,
            postTapWaitMs = 70L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 60L,
            gestureDurationMs = 18L,
            watchdogIntervalMs = 40L,
            unknownRecoveryAfterMs = 1_000L,
            actionRetryAfterMs = 120L
        )
        RuntimeSpeedMode.TURBO -> RuntimeSpeedProfile(
            eventScanMs = 9L,
            stableScanMs = 22L,
            fallbackPollMs = 65L,
            postTapWaitMs = 38L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 44L,
            gestureDurationMs = 14L,
            watchdogIntervalMs = 28L,
            unknownRecoveryAfterMs = 700L,
            actionRetryAfterMs = 90L
        )
        RuntimeSpeedMode.MAX -> RuntimeSpeedProfile(
            eventScanMs = 6L,
            stableScanMs = 14L,
            fallbackPollMs = 40L,
            postTapWaitMs = 22L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 30L,
            gestureDurationMs = 10L,
            watchdogIntervalMs = 20L,
            unknownRecoveryAfterMs = 500L,
            actionRetryAfterMs = 70L
        )
        RuntimeSpeedMode.CUSTOM -> {
            val scan = customScanMs.coerceIn(MIN_CUSTOM_SCAN_MS, MAX_CUSTOM_SCAN_MS)
            val post = customPostTapMs.coerceIn(MIN_CUSTOM_POST_TAP_MS, MAX_CUSTOM_POST_TAP_MS)
            val next = customInterLinkMs.coerceIn(MIN_CUSTOM_INTER_LINK_MS, MAX_CUSTOM_INTER_LINK_MS)
            RuntimeSpeedProfile(
                eventScanMs = scan.toLong(),
                stableScanMs = (scan * 2L).coerceIn(scan.toLong(), 500L),
                fallbackPollMs = (scan * 5L).coerceIn(30L, 1_000L),
                postTapWaitMs = post.toLong(),
                interLinkDelayMs = next.toLong(),
                clickThrottleMs = maxOf(28L, scan * 3L),
                gestureDurationMs = maxOf(10L, minOf(45L, scan.toLong())),
                watchdogIntervalMs = maxOf(20L, scan * 2L),
                unknownRecoveryAfterMs = maxOf(500L, post * 8L),
                actionRetryAfterMs = maxOf(70L, post * 2L)
            )
        }
    }

    fun isFast(mode: RuntimeSpeedMode): Boolean =
        mode != RuntimeSpeedMode.STABLE
}

object SmartResultClassifier {
    fun fromResultCode(name: String): SmartResultClass = when (name) {
        "JOIN_ACTION_COMPLETED", "MANUAL_JOINED" -> SmartResultClass.JOINED
        "REQUEST_SENT" -> SmartResultClass.REQUESTED
        "ALREADY_MEMBER" -> SmartResultClass.ALREADY_MEMBER
        "GROUP_FULL" -> SmartResultClass.GROUP_FULL
        "INVALID_OR_EXPIRED" -> SmartResultClass.INVALID
        "REMOVED_OR_BANNED" -> SmartResultClass.REMOVED
        "RESTRICTED" -> SmartResultClass.RESTRICTED
        "UNKNOWN_SCREEN", "ACTION_TIMEOUT" -> SmartResultClass.UNKNOWN
        else -> SmartResultClass.FAILED
    }
}

enum class SmartExitStep {
    SAFE_CLOSE,
    TERMINAL_ACK,
    SAFE_CANCEL,
    BACK,
    DIRECT_NEXT_DEEP_LINK
}

object SmartExitControllerPolicy {
    val ORDER = listOf(
        SmartExitStep.SAFE_CLOSE,
        SmartExitStep.TERMINAL_ACK,
        SmartExitStep.SAFE_CANCEL,
        SmartExitStep.BACK,
        SmartExitStep.DIRECT_NEXT_DEEP_LINK
    )

    const val MAX_BACK_ATTEMPTS = 2
    const val MAX_REOPEN_ATTEMPTS_PER_LINK = 1
    const val MAX_ACTION_ATTEMPTS = 3
}
'''
write(SPEED_FILE, speed_source)

# ============================================================
# 2) AppPreferences persisted runtime state
# ============================================================
prefs = read(PREFS)

imports_anchor = '''import com.althmany.groupmanager.domain.CommunityTraversalStage
'''
imports_new = '''import com.althmany.groupmanager.domain.CommunityTraversalStage
import com.althmany.groupmanager.domain.LinkRuntimePhase
import com.althmany.groupmanager.domain.RestrictionHandlingMode
import com.althmany.groupmanager.domain.RuntimeSpeedMode
import com.althmany.groupmanager.domain.RuntimeSpeedProfile
import com.althmany.groupmanager.domain.RuntimeSpeedProfilePolicy
import com.althmany.groupmanager.domain.SmartResultClassifier
'''
prefs = replace_once(prefs, imports_anchor, imports_new, "AppPreferences runtime imports")

fast_pref_anchor = '''    /** Faster event-first scanning while preserving the user-selected inter-link delay. */
    var fastHandsFreeMode: Boolean
        get() = preferences.getBoolean(KEY_FAST_HANDS_FREE_MODE, true)
        set(value) = preferences.edit().putBoolean(KEY_FAST_HANDS_FREE_MODE, value).apply()
'''
speed_pref_block = fast_pref_anchor + r'''
    var runtimeSpeedMode: RuntimeSpeedMode
        get() = enumValueOrDefault(
            preferences.getString(KEY_RUNTIME_SPEED_MODE, null),
            RuntimeSpeedMode.FAST
        )
        set(value) = preferences.edit().putString(KEY_RUNTIME_SPEED_MODE, value.name).apply()

    var customScanMs: Int
        get() = preferences.getInt(KEY_CUSTOM_SCAN_MS, 12)
            .coerceIn(
                RuntimeSpeedProfilePolicy.MIN_CUSTOM_SCAN_MS,
                RuntimeSpeedProfilePolicy.MAX_CUSTOM_SCAN_MS
            )
        set(value) = preferences.edit()
            .putInt(
                KEY_CUSTOM_SCAN_MS,
                value.coerceIn(
                    RuntimeSpeedProfilePolicy.MIN_CUSTOM_SCAN_MS,
                    RuntimeSpeedProfilePolicy.MAX_CUSTOM_SCAN_MS
                )
            )
            .apply()

    var customPostTapMs: Int
        get() = preferences.getInt(KEY_CUSTOM_POST_TAP_MS, 55)
            .coerceIn(
                RuntimeSpeedProfilePolicy.MIN_CUSTOM_POST_TAP_MS,
                RuntimeSpeedProfilePolicy.MAX_CUSTOM_POST_TAP_MS
            )
        set(value) = preferences.edit()
            .putInt(
                KEY_CUSTOM_POST_TAP_MS,
                value.coerceIn(
                    RuntimeSpeedProfilePolicy.MIN_CUSTOM_POST_TAP_MS,
                    RuntimeSpeedProfilePolicy.MAX_CUSTOM_POST_TAP_MS
                )
            )
            .apply()

    var customInterLinkMs: Int
        get() = preferences.getInt(KEY_CUSTOM_INTER_LINK_MS, 0)
            .coerceIn(
                RuntimeSpeedProfilePolicy.MIN_CUSTOM_INTER_LINK_MS,
                RuntimeSpeedProfilePolicy.MAX_CUSTOM_INTER_LINK_MS
            )
        set(value) = preferences.edit()
            .putInt(
                KEY_CUSTOM_INTER_LINK_MS,
                value.coerceIn(
                    RuntimeSpeedProfilePolicy.MIN_CUSTOM_INTER_LINK_MS,
                    RuntimeSpeedProfilePolicy.MAX_CUSTOM_INTER_LINK_MS
                )
            )
            .apply()

    fun runtimeSpeedProfile(): RuntimeSpeedProfile =
        RuntimeSpeedProfilePolicy.resolve(
            runtimeSpeedMode,
            customScanMs,
            customPostTapMs,
            customInterLinkMs
        )

    var restrictionHandlingMode: RestrictionHandlingMode
        get() = enumValueOrDefault(
            preferences.getString(KEY_RESTRICTION_HANDLING_MODE, null),
            RestrictionHandlingMode.SKIP_AND_CONTINUE
        )
        set(value) = preferences.edit()
            .putString(KEY_RESTRICTION_HANDLING_MODE, value.name)
            .apply()

    var runtimeLockedAndroidUserId: Int
        get() = preferences.getInt(KEY_RUNTIME_LOCKED_ANDROID_USER_ID, -1)
        private set(value) = preferences.edit()
            .putInt(KEY_RUNTIME_LOCKED_ANDROID_USER_ID, value)
            .apply()

    fun lockRuntimeAndroidUserId(userId: Int): Boolean {
        if (userId < 0) return false
        val current = runtimeLockedAndroidUserId
        if (current >= 0 && current != userId) return false
        runtimeLockedAndroidUserId = userId
        return true
    }

    var runtimeLinkPhase: LinkRuntimePhase
        get() = enumValueOrDefault(
            preferences.getString(KEY_RUNTIME_LINK_PHASE, null),
            LinkRuntimePhase.OPENING
        )
        private set(value) = preferences.edit().putString(KEY_RUNTIME_LINK_PHASE, value.name).apply()

    var runtimeCurrentLinkId: Long
        get() = preferences.getLong(KEY_RUNTIME_CURRENT_LINK_ID, -1L)
        private set(value) = preferences.edit().putLong(KEY_RUNTIME_CURRENT_LINK_ID, value).apply()

    var runtimeCurrentLinkPosition: Int
        get() = preferences.getInt(KEY_RUNTIME_CURRENT_LINK_POSITION, -1)
        private set(value) = preferences.edit().putInt(KEY_RUNTIME_CURRENT_LINK_POSITION, value).apply()

    var runtimeCurrentLinkUrl: String?
        get() = preferences.getString(KEY_RUNTIME_CURRENT_LINK_URL, null)
        private set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_RUNTIME_CURRENT_LINK_URL)
            else putString(KEY_RUNTIME_CURRENT_LINK_URL, value.take(512))
        }.apply()

    var runtimeLinkStartedAt: Long
        get() = preferences.getLong(KEY_RUNTIME_LINK_STARTED_AT, 0L)
        private set(value) = preferences.edit().putLong(KEY_RUNTIME_LINK_STARTED_AT, value).apply()

    var runtimeActionExecuted: String?
        get() = preferences.getString(KEY_RUNTIME_ACTION_EXECUTED, null)
        private set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_RUNTIME_ACTION_EXECUTED)
            else putString(KEY_RUNTIME_ACTION_EXECUTED, value.take(80))
        }.apply()

    var runtimeActionAttemptCount: Int
        get() = preferences.getInt(KEY_RUNTIME_ACTION_ATTEMPT_COUNT, 0).coerceAtLeast(0)
        private set(value) = preferences.edit()
            .putInt(KEY_RUNTIME_ACTION_ATTEMPT_COUNT, value.coerceAtLeast(0))
            .apply()

    var runtimeRecoveryReopenAttempts: Int
        get() = preferences.getInt(KEY_RUNTIME_RECOVERY_REOPEN_ATTEMPTS, 0).coerceAtLeast(0)
        private set(value) = preferences.edit()
            .putInt(KEY_RUNTIME_RECOVERY_REOPEN_ATTEMPTS, value.coerceAtLeast(0))
            .apply()

    var runtimeLastEngineState: String?
        get() = preferences.getString(KEY_RUNTIME_LAST_ENGINE_STATE, null)
        set(value) = preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_RUNTIME_LAST_ENGINE_STATE)
            else putString(KEY_RUNTIME_LAST_ENGINE_STATE, value.take(120))
        }.apply()

    var lastCompletedLinkPosition: Int
        get() = preferences.getInt(KEY_LAST_COMPLETED_LINK_POSITION, -1)
        private set(value) = preferences.edit()
            .putInt(KEY_LAST_COMPLETED_LINK_POSITION, value)
            .apply()

    fun beginRuntimeLink(linkId: Long, position: Int, url: String, engine: String) {
        if (runtimeCurrentLinkId != linkId) {
            preferences.edit()
                .putLong(KEY_RUNTIME_CURRENT_LINK_ID, linkId)
                .putInt(KEY_RUNTIME_CURRENT_LINK_POSITION, position)
                .putString(KEY_RUNTIME_CURRENT_LINK_URL, url.take(512))
                .putLong(KEY_RUNTIME_LINK_STARTED_AT, System.currentTimeMillis())
                .putString(KEY_RUNTIME_LINK_PHASE, LinkRuntimePhase.OPENING.name)
                .remove(KEY_RUNTIME_ACTION_EXECUTED)
                .putInt(KEY_RUNTIME_ACTION_ATTEMPT_COUNT, 0)
                .putInt(KEY_RUNTIME_RECOVERY_REOPEN_ATTEMPTS, 0)
                .putString(KEY_RUNTIME_LAST_ENGINE_STATE, engine.take(120))
                .apply()
        } else {
            runtimeLastEngineState = engine
        }
    }

    fun markRuntimePhase(phase: LinkRuntimePhase, engineState: String? = null) {
        preferences.edit().apply {
            putString(KEY_RUNTIME_LINK_PHASE, phase.name)
            if (!engineState.isNullOrBlank()) {
                putString(KEY_RUNTIME_LAST_ENGINE_STATE, engineState.take(120))
            }
        }.apply()
    }

    fun recordRuntimeAction(action: String, engine: String) {
        preferences.edit()
            .putString(KEY_RUNTIME_ACTION_EXECUTED, action.take(80))
            .putInt(KEY_RUNTIME_ACTION_ATTEMPT_COUNT, runtimeActionAttemptCount + 1)
            .putString(KEY_RUNTIME_LINK_PHASE, LinkRuntimePhase.ACTION_TAPPED.name)
            .putString(KEY_RUNTIME_LAST_ENGINE_STATE, engine.take(120))
            .apply()
    }

    fun canReopenCurrentLinkOnce(linkId: Long): Boolean =
        runtimeCurrentLinkId == linkId &&
            runtimeRecoveryReopenAttempts <
                com.althmany.groupmanager.domain.SmartExitControllerPolicy.MAX_REOPEN_ATTEMPTS_PER_LINK

    fun recordRecoveryReopen(linkId: Long): Boolean {
        if (!canReopenCurrentLinkOnce(linkId)) return false
        runtimeRecoveryReopenAttempts += 1
        markRuntimePhase(LinkRuntimePhase.OPENING, "RECOVERY_REOPEN")
        return true
    }

    fun buildRuntimeAuditDetail(
        detail: String,
        resultCodeName: String,
        backend: String
    ): String {
        val elapsed = if (runtimeLinkStartedAt > 0L) {
            (System.currentTimeMillis() - runtimeLinkStartedAt).coerceAtLeast(0L)
        } else 0L
        val resultClass = SmartResultClassifier.fromResultCode(resultCodeName)
        return buildString {
            append(detail.take(900))
            append(" | resultClass=").append(resultClass.name)
            append(" | attempts=").append(runtimeActionAttemptCount)
            append(" | elapsedMs=").append(elapsed)
            append(" | backend=").append(backend)
            append(" | package=").append(runtimeLockedWhatsAppPackage.orEmpty())
            append(" | userId=").append(runtimeLockedAndroidUserId)
            append(" | profile=").append(runtimeLockedProfileKey.orEmpty())
            append(" | phase=").append(runtimeLinkPhase.name)
        }.take(1_600)
    }

    fun finishRuntimeLink(position: Int, engineState: String) {
        preferences.edit()
            .putInt(KEY_LAST_COMPLETED_LINK_POSITION, position)
            .putString(KEY_RUNTIME_LINK_PHASE, LinkRuntimePhase.ADVANCING.name)
            .putString(KEY_RUNTIME_LAST_ENGINE_STATE, engineState.take(120))
            .apply()
    }
'''
prefs = replace_once(prefs, fast_pref_anchor, speed_pref_block, "speed/runtime preferences")

lock_anchor = '''    fun lockRuntimeTarget(packageName: String, profileKey: String) {
        preferences.edit()
            .putString(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE, packageName)
            .putString(KEY_RUNTIME_LOCKED_PROFILE_KEY, profileKey)
            .apply()
    }
'''
lock_new = '''    fun lockRuntimeTarget(packageName: String, profileKey: String) {
        preferences.edit()
            .putString(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE, packageName)
            .putString(KEY_RUNTIME_LOCKED_PROFILE_KEY, profileKey)
            .remove(KEY_RUNTIME_LOCKED_ANDROID_USER_ID)
            .apply()
    }
'''
prefs = replace_once(prefs, lock_anchor, lock_new, "runtime user lock reset")

clear_anchor = '''    fun clearRuntimeTargetLock() {
        preferences.edit()
            .remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            .remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            .apply()
    }
'''
clear_new = '''    fun clearRuntimeTargetLock() {
        preferences.edit()
            .remove(KEY_RUNTIME_LOCKED_WHATSAPP_PACKAGE)
            .remove(KEY_RUNTIME_LOCKED_PROFILE_KEY)
            .remove(KEY_RUNTIME_LOCKED_ANDROID_USER_ID)
            .apply()
    }
'''
prefs = replace_once(prefs, clear_anchor, clear_new, "clear runtime user lock")

key_anchor = '''        private const val KEY_FAST_HANDS_FREE_MODE = "fast_hands_free_mode"'''
key_block = key_anchor + r'''
        private const val KEY_RUNTIME_SPEED_MODE = "runtime_speed_mode"
        private const val KEY_CUSTOM_SCAN_MS = "custom_scan_ms"
        private const val KEY_CUSTOM_POST_TAP_MS = "custom_post_tap_ms"
        private const val KEY_CUSTOM_INTER_LINK_MS = "custom_inter_link_ms"
        private const val KEY_RESTRICTION_HANDLING_MODE = "restriction_handling_mode"
        private const val KEY_RUNTIME_LOCKED_ANDROID_USER_ID = "runtime_locked_android_user_id"
        private const val KEY_RUNTIME_LINK_PHASE = "runtime_link_phase"
        private const val KEY_RUNTIME_CURRENT_LINK_ID = "runtime_current_link_id"
        private const val KEY_RUNTIME_CURRENT_LINK_POSITION = "runtime_current_link_position"
        private const val KEY_RUNTIME_CURRENT_LINK_URL = "runtime_current_link_url"
        private const val KEY_RUNTIME_LINK_STARTED_AT = "runtime_link_started_at"
        private const val KEY_RUNTIME_ACTION_EXECUTED = "runtime_action_executed"
        private const val KEY_RUNTIME_ACTION_ATTEMPT_COUNT = "runtime_action_attempt_count"
        private const val KEY_RUNTIME_RECOVERY_REOPEN_ATTEMPTS = "runtime_recovery_reopen_attempts"
        private const val KEY_RUNTIME_LAST_ENGINE_STATE = "runtime_last_engine_state"
        private const val KEY_LAST_COMPLETED_LINK_POSITION = "last_completed_link_position"'''
prefs = replace_once(prefs, key_anchor, key_block, "runtime preference keys")
write(PREFS, prefs)

# ============================================================
# 3) Safer exit matcher: generic Cancel only, never Cancel Request
# ============================================================
matcher = read(MATCHER)
safe_exit_anchor = '''    private val cancelRequestLabels = normalizedSetOf(
        "cancel request", "withdraw request", "cancel join request",
        "إلغاء الطلب", "الغاء الطلب", "إلغاء طلب الانضمام", "الغاء طلب الانضمام"
    )
'''
safe_exit_block = safe_exit_anchor + r'''
    private val genericDialogCancelLabels = normalizedSetOf(
        "cancel", "dismiss", "close dialog",
        "إلغاء", "الغاء", "إغلاق", "اغلاق"
    )
'''
matcher = replace_once(matcher, safe_exit_anchor, safe_exit_block, "generic safe dialog cancel labels")

helper_anchor = '''    fun isCancelRequest(label: CharSequence?): Boolean {'''
helper_code = r'''    fun isSafeDialogCancel(value: CharSequence?): Boolean {
        val normalized = normalize(value)
        if (normalized.isBlank()) return false
        if (cancelRequestLabels.any { normalized == it || normalized.contains(it) }) return false
        return genericDialogCancelLabels.any { normalized == it }
    }

'''
matcher = insert_before(matcher, helper_anchor, helper_code, "safe dialog cancel helper")
write(MATCHER, matcher)

# ============================================================
# 4) Compact dashboard controls + advanced sections
# ============================================================
add_strings(STR_AR, [
    ("runtime_speed_title", "سرعة المحرك"),
    ("speed_stable", "Stable"),
    ("speed_fast", "Fast"),
    ("speed_turbo", "Turbo"),
    ("speed_max", "MAX"),
    ("speed_custom", "Custom"),
    ("custom_scan_title", "Scan"),
    ("custom_post_tap_title", "بعد الضغط"),
    ("custom_next_title", "بين الروابط"),
    ("runtime_ms_format", "%1$dms"),
    ("advanced_settings", "الإعدادات المتقدمة"),
    ("hide_advanced_settings", "إخفاء الإعدادات المتقدمة"),
    ("resume_last_run", "استئناف من آخر رابط"),
    ("continue_on_restriction", "تخطي Restriction والاستمرار"),
    ("continue_on_restriction_hint", "إذا أوقفته، تتوقف الجولة عند Restriction. لا يتم تجاوز قيود واتساب في الحالتين."),
])
add_strings(STR_EN, [
    ("runtime_speed_title", "Engine speed"),
    ("speed_stable", "Stable"),
    ("speed_fast", "Fast"),
    ("speed_turbo", "Turbo"),
    ("speed_max", "MAX"),
    ("speed_custom", "Custom"),
    ("custom_scan_title", "Scan"),
    ("custom_post_tap_title", "After tap"),
    ("custom_next_title", "Between links"),
    ("runtime_ms_format", "%1$dms"),
    ("advanced_settings", "Advanced settings"),
    ("hide_advanced_settings", "Hide advanced settings"),
    ("resume_last_run", "Resume from last link"),
    ("continue_on_restriction", "Skip Restriction and continue"),
    ("continue_on_restriction_hint", "When off, the run stops on Restriction. WhatsApp restrictions are never bypassed."),
])

layout = read(LAYOUT)

smart_card_old = '''                <!-- Smart controls -->
                <com.google.android.material.card.MaterialCardView
                    android:layout_width="match_parent"'''
smart_card_new = '''                <!-- Smart controls -->
                <com.google.android.material.card.MaterialCardView
                    android:id="@+id/advancedSmartCard"
                    android:visibility="gone"
                    android:layout_width="match_parent"'''
layout = replace_once(layout, smart_card_old, smart_card_new, "advanced smart card")

schedule_card_old = '''                <!-- Scheduling -->
                <com.google.android.material.card.MaterialCardView
                    android:layout_width="match_parent"'''
schedule_card_new = '''                <!-- Scheduling -->
                <com.google.android.material.card.MaterialCardView
                    android:id="@+id/advancedScheduleCard"
                    android:visibility="gone"
                    android:layout_width="match_parent"'''
layout = replace_once(layout, schedule_card_old, schedule_card_new, "advanced schedule card")

runtime_card = r'''
                <!-- Compact runtime controls: speed + resume + advanced -->
                <com.google.android.material.card.MaterialCardView
                    android:id="@+id/runtimeControlCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="6dp"
                    app:cardBackgroundColor="@color/surface_card"
                    app:cardCornerRadius="18dp"
                    app:cardElevation="2dp"
                    app:strokeColor="@color/aurora_border"
                    app:strokeWidth="1dp">

                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:padding="10dp">

                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="@string/runtime_speed_title"
                            android:textColor="@color/nebula_primary_dark"
                            android:textSize="15sp"
                            android:textStyle="bold" />

                        <com.google.android.material.button.MaterialButtonToggleGroup
                            android:id="@+id/speedModeToggleGroup"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="5dp"
                            app:selectionRequired="true"
                            app:singleSelection="true">

                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/speedStableButton"
                                style="@style/Widget.Material3.Button.OutlinedButton"
                                android:layout_width="0dp"
                                android:layout_height="38dp"
                                android:layout_weight="1"
                                android:minWidth="0dp"
                                android:paddingHorizontal="1dp"
                                android:text="@string/speed_stable"
                                android:textSize="8sp" />

                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/speedFastButton"
                                style="@style/Widget.Material3.Button.OutlinedButton"
                                android:layout_width="0dp"
                                android:layout_height="38dp"
                                android:layout_weight="1"
                                android:minWidth="0dp"
                                android:paddingHorizontal="1dp"
                                android:text="@string/speed_fast"
                                android:textSize="8sp" />

                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/speedTurboButton"
                                style="@style/Widget.Material3.Button.OutlinedButton"
                                android:layout_width="0dp"
                                android:layout_height="38dp"
                                android:layout_weight="1"
                                android:minWidth="0dp"
                                android:paddingHorizontal="1dp"
                                android:text="@string/speed_turbo"
                                android:textSize="8sp" />

                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/speedMaxButton"
                                style="@style/Widget.Material3.Button.OutlinedButton"
                                android:layout_width="0dp"
                                android:layout_height="38dp"
                                android:layout_weight="1"
                                android:minWidth="0dp"
                                android:paddingHorizontal="1dp"
                                android:text="@string/speed_max"
                                android:textSize="8sp" />

                            <com.google.android.material.button.MaterialButton
                                android:id="@+id/speedCustomButton"
                                style="@style/Widget.Material3.Button.OutlinedButton"
                                android:layout_width="0dp"
                                android:layout_height="38dp"
                                android:layout_weight="1"
                                android:minWidth="0dp"
                                android:paddingHorizontal="1dp"
                                android:text="@string/speed_custom"
                                android:textSize="8sp" />
                        </com.google.android.material.button.MaterialButtonToggleGroup>

                        <LinearLayout
                            android:id="@+id/customSpeedControls"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="4dp"
                            android:orientation="vertical"
                            android:visibility="gone">

                            <LinearLayout
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:orientation="horizontal">
                                <TextView
                                    android:layout_width="0dp"
                                    android:layout_height="wrap_content"
                                    android:layout_weight="1"
                                    android:text="@string/custom_scan_title"
                                    android:textColor="@color/text_secondary"
                                    android:textSize="10sp" />
                                <TextView
                                    android:id="@+id/customScanValueText"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="12ms"
                                    android:textColor="@color/nebula_primary_dark"
                                    android:textSize="10sp"
                                    android:textStyle="bold" />
                            </LinearLayout>

                            <com.google.android.material.slider.Slider
                                android:id="@+id/customScanSlider"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:stepSize="2"
                                android:value="12"
                                android:valueFrom="6"
                                android:valueTo="250" />

                            <LinearLayout
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:orientation="horizontal">
                                <TextView
                                    android:layout_width="0dp"
                                    android:layout_height="wrap_content"
                                    android:layout_weight="1"
                                    android:text="@string/custom_post_tap_title"
                                    android:textColor="@color/text_secondary"
                                    android:textSize="10sp" />
                                <TextView
                                    android:id="@+id/customPostTapValueText"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="55ms"
                                    android:textColor="@color/nebula_primary_dark"
                                    android:textSize="10sp"
                                    android:textStyle="bold" />
                            </LinearLayout>

                            <com.google.android.material.slider.Slider
                                android:id="@+id/customPostTapSlider"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:stepSize="5"
                                android:value="55"
                                android:valueFrom="0"
                                android:valueTo="1000" />

                            <LinearLayout
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:orientation="horizontal">
                                <TextView
                                    android:layout_width="0dp"
                                    android:layout_height="wrap_content"
                                    android:layout_weight="1"
                                    android:text="@string/custom_next_title"
                                    android:textColor="@color/text_secondary"
                                    android:textSize="10sp" />
                                <TextView
                                    android:id="@+id/customInterLinkValueText"
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:text="0ms"
                                    android:textColor="@color/nebula_primary_dark"
                                    android:textSize="10sp"
                                    android:textStyle="bold" />
                            </LinearLayout>

                            <com.google.android.material.slider.Slider
                                android:id="@+id/customInterLinkSlider"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:stepSize="50"
                                android:value="0"
                                android:valueFrom="0"
                                android:valueTo="10000" />
                        </LinearLayout>

                        <com.google.android.material.materialswitch.MaterialSwitch
                            android:id="@+id/compactAutoResumeSwitch"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="2dp"
                            android:text="@string/auto_resume_title"
                            android:textColor="@color/text_primary"
                            android:textSize="11sp" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/resumeLastRunButton"
                            style="@style/Widget.Material3.Button.OutlinedButton"
                            android:layout_width="match_parent"
                            android:layout_height="40dp"
                            android:layout_marginTop="2dp"
                            android:text="@string/resume_last_run"
                            android:textSize="10sp"
                            android:visibility="gone" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/advancedSettingsButton"
                            style="@style/Widget.Material3.Button.TextButton"
                            android:layout_width="match_parent"
                            android:layout_height="38dp"
                            android:layout_marginTop="2dp"
                            android:text="@string/advanced_settings"
                            android:textSize="10sp" />
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

'''
layout = insert_before(layout, '''                <!-- Smart controls -->''', runtime_card, "runtime compact card")

restriction_block = r'''
                        <LinearLayout
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="6dp"
                            android:orientation="vertical">

                            <com.google.android.material.materialswitch.MaterialSwitch
                                android:id="@+id/continueOnRestrictionSwitch"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:text="@string/continue_on_restriction"
                                android:textColor="@color/text_primary"
                                android:textSize="12sp" />

                            <TextView
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:text="@string/continue_on_restriction_hint"
                                android:textColor="@color/text_tertiary"
                                android:textSize="9sp" />
                        </LinearLayout>

'''
layout = insert_before(
    layout,
    '''                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="8dp"
                            android:text="@string/unified_safe_note"''',
    restriction_block,
    "restriction advanced control"
)

layout = layout.replace('android:layout_height="96dp"', 'android:layout_height="82dp"', 1)
layout = layout.replace('app:cardCornerRadius="22dp"', 'app:cardCornerRadius="18dp"')
write(LAYOUT, layout)

# ============================================================
# 5) MainActivity controls
# ============================================================
main = read(MAIN)

main_import_anchor = '''import com.althmany.groupmanager.domain.ProfileControlPolicy
'''
main_import_new = '''import com.althmany.groupmanager.domain.ProfileControlPolicy
import com.althmany.groupmanager.domain.RestrictionHandlingMode
import com.althmany.groupmanager.domain.RuntimeSpeedMode
import com.althmany.groupmanager.domain.RuntimeSpeedProfilePolicy
'''
main = replace_once(main, main_import_anchor, main_import_new, "MainActivity speed imports")

field_anchor = '''    private var detectedLinkCount: Int = 0
'''
field_new = '''    private var detectedLinkCount: Int = 0
    private var advancedSettingsVisible = false
    private var runtimeSpeedBinding = false
    private var autoResumeKickPending = false
'''
main = replace_once(main, field_anchor, field_new, "MainActivity runtime fields")

timing_anchor = '''    private fun configureTimingControls() = with(binding) {
        delaySlider.value = app.preferences.interLinkDelayMs.toFloat()
'''
timing_new = '''    private fun configureTimingControls() = with(binding) {
        configureRuntimeSpeedControls()

        delaySlider.value = app.preferences.interLinkDelayMs.toFloat()
'''
main = replace_once(main, timing_anchor, timing_new, "MainActivity speed config call")

method_anchor = '''    private fun showDatePicker() {
'''
speed_methods = r'''    private fun configureRuntimeSpeedControls() = with(binding) {
        runtimeSpeedBinding = true
        speedModeToggleGroup.check(
            when (app.preferences.runtimeSpeedMode) {
                RuntimeSpeedMode.STABLE -> R.id.speedStableButton
                RuntimeSpeedMode.FAST -> R.id.speedFastButton
                RuntimeSpeedMode.TURBO -> R.id.speedTurboButton
                RuntimeSpeedMode.MAX -> R.id.speedMaxButton
                RuntimeSpeedMode.CUSTOM -> R.id.speedCustomButton
            }
        )
        customScanSlider.value = app.preferences.customScanMs.toFloat()
        customPostTapSlider.value = app.preferences.customPostTapMs.toFloat()
        customInterLinkSlider.value = app.preferences.customInterLinkMs.toFloat()
        runtimeSpeedBinding = false

        speedModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || runtimeSpeedBinding) return@addOnButtonCheckedListener
            app.preferences.runtimeSpeedMode = when (checkedId) {
                R.id.speedStableButton -> RuntimeSpeedMode.STABLE
                R.id.speedTurboButton -> RuntimeSpeedMode.TURBO
                R.id.speedMaxButton -> RuntimeSpeedMode.MAX
                R.id.speedCustomButton -> RuntimeSpeedMode.CUSTOM
                else -> RuntimeSpeedMode.FAST
            }
            applyRuntimeSpeedCompatibilityValues()
            renderRuntimeSpeedControls()
        }

        customScanSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            app.preferences.customScanMs = value.toInt()
            customScanValueText.text = getString(
                R.string.runtime_ms_format,
                app.preferences.customScanMs
            )
        }
        customPostTapSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            app.preferences.customPostTapMs = value.toInt()
            customPostTapValueText.text = getString(
                R.string.runtime_ms_format,
                app.preferences.customPostTapMs
            )
        }
        customInterLinkSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            app.preferences.customInterLinkMs = value.toInt()
            app.preferences.interLinkDelayMs = app.preferences.customInterLinkMs
            customInterLinkValueText.text = getString(
                R.string.runtime_ms_format,
                app.preferences.customInterLinkMs
            )
            delaySlider.value = app.preferences.interLinkDelayMs.toFloat()
            delayValueText.text = formatInterLinkDelay(app.preferences.interLinkDelayMs)
        }

        renderRuntimeSpeedControls()
    }

    private fun applyRuntimeSpeedCompatibilityValues() {
        val profile = app.preferences.runtimeSpeedProfile()
        app.preferences.fastHandsFreeMode =
            RuntimeSpeedProfilePolicy.isFast(app.preferences.runtimeSpeedMode)
        app.preferences.interLinkDelayMs = profile.interLinkDelayMs.toInt()
        binding.fastHandsFreeSwitch.isChecked = app.preferences.fastHandsFreeMode
        binding.delaySlider.value = app.preferences.interLinkDelayMs.toFloat()
        binding.delayValueText.text = formatInterLinkDelay(app.preferences.interLinkDelayMs)
    }

    private fun renderRuntimeSpeedControls() = with(binding) {
        val custom = app.preferences.runtimeSpeedMode == RuntimeSpeedMode.CUSTOM
        customSpeedControls.visibility = if (custom) View.VISIBLE else View.GONE
        customScanValueText.text = getString(
            R.string.runtime_ms_format,
            app.preferences.customScanMs
        )
        customPostTapValueText.text = getString(
            R.string.runtime_ms_format,
            app.preferences.customPostTapMs
        )
        customInterLinkValueText.text = getString(
            R.string.runtime_ms_format,
            app.preferences.customInterLinkMs
        )
    }

    private fun renderAdvancedSettings() = with(binding) {
        advancedSmartCard.visibility = if (advancedSettingsVisible) View.VISIBLE else View.GONE
        advancedScheduleCard.visibility = if (advancedSettingsVisible) View.VISIBLE else View.GONE
        advancedSettingsButton.setText(
            if (advancedSettingsVisible) R.string.hide_advanced_settings
            else R.string.advanced_settings
        )
    }

'''
main = insert_before(main, method_anchor, speed_methods, "runtime speed methods")

action_anchor = '''        startAutomationButton.setOnClickListener { startAutomaticRun(allowQueuedContinuation = true) }
'''
action_block = '''        startAutomationButton.setOnClickListener { startAutomaticRun(allowQueuedContinuation = true) }
        resumeLastRunButton.setOnClickListener { startAutomaticRun(allowQueuedContinuation = true) }
        advancedSettingsButton.setOnClickListener {
            advancedSettingsVisible = !advancedSettingsVisible
            renderAdvancedSettings()
        }

        compactAutoResumeSwitch.isChecked = app.preferences.autoResumeCurrentRun
        compactAutoResumeSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.autoResumeCurrentRun = checked
            if (autoResumeSwitch.isChecked != checked) autoResumeSwitch.isChecked = checked
        }

        continueOnRestrictionSwitch.isChecked =
            app.preferences.restrictionHandlingMode == RestrictionHandlingMode.SKIP_AND_CONTINUE
        continueOnRestrictionSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.restrictionHandlingMode =
                if (checked) RestrictionHandlingMode.SKIP_AND_CONTINUE
                else RestrictionHandlingMode.STOP_RUN
        }

        renderAdvancedSettings()
'''
main = replace_once(main, action_anchor, action_block, "MainActivity compact actions")

old_auto_resume = '''        autoResumeSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.autoResumeCurrentRun = checked
            autoResumeHintText.setText(
'''
new_auto_resume = '''        autoResumeSwitch.setOnCheckedChangeListener { _, checked ->
            app.preferences.autoResumeCurrentRun = checked
            if (compactAutoResumeSwitch.isChecked != checked) {
                compactAutoResumeSwitch.isChecked = checked
            }
            autoResumeHintText.setText(
'''
main = replace_once(main, old_auto_resume, new_auto_resume, "auto resume synchronization")

render_anchor = '''        val controlsUnlocked = !running && !scheduled
        startAutomationButton.isEnabled = (!running || paused) && !scheduled
'''
render_new = '''        val controlsUnlocked = !running && !scheduled
        startAutomationButton.isEnabled = (!running || paused) && !scheduled
        resumeLastRunButton.visibility =
            if (!running && !scheduled && canContinueQueuedBatch()) View.VISIBLE else View.GONE
        resumeLastRunButton.isEnabled = !running && !scheduled
        compactAutoResumeSwitch.isEnabled = true
'''
main = replace_once(main, render_anchor, render_new, "resume render state")

onresume_anchor = '''        if (pendingAutoStartAfterSettings &&
            AccessibilityStatus.isQuickJoinServiceEnabled(this@MainActivity)
        ) {
'''
onresume_code = r'''        if (app.preferences.autoResumeCurrentRun && !autoResumeKickPending) {
            val recoverable = app.preferences.automationStopReason in setOf(
                AutomationStopReason.SERVICE_DISABLED,
                AutomationStopReason.TARGET_UNSUPPORTED,
                AutomationStopReason.OPEN_FAILED,
                AutomationStopReason.BROWSER_FALLBACK,
                AutomationStopReason.UNKNOWN_SCREEN,
                AutomationStopReason.ACTION_TIMEOUT,
                AutomationStopReason.RUNTIME_CIRCUIT_BREAKER
            )
            if (recoverable && !app.preferences.accessibilityBatchRunning &&
                !app.preferences.hasScheduledStart && isAnyAutomationEngineReady()
            ) {
                autoResumeKickPending = true
                binding.root.postDelayed({
                    autoResumeKickPending = false
                    if (canContinueQueuedBatch() && !app.preferences.accessibilityBatchRunning) {
                        startAutomaticRun(allowQueuedContinuation = true)
                    }
                }, 350L)
            }
        }

'''
main = insert_before(main, onresume_anchor, onresume_code, "safe on-resume continuation")
write(MAIN, main)

# ============================================================
# 6) Accessibility engine
# ============================================================
quick = read(QUICK)

quick_import_anchor = '''import com.althmany.groupmanager.domain.RuntimeWatchdogState
'''
quick_import_new = '''import com.althmany.groupmanager.domain.RuntimeWatchdogState
import com.althmany.groupmanager.domain.LinkRuntimePhase
import com.althmany.groupmanager.domain.RestrictionHandlingMode
'''
quick = replace_once(quick, quick_import_anchor, quick_import_new, "Accessibility new runtime imports")

app_getter_anchor = '''    private val app: GroupManagerApp
        get() = application as GroupManagerApp
'''
runtime_helpers = app_getter_anchor + r'''

    private fun runtimeSpeed() = app.preferences.runtimeSpeedProfile()

    private fun currentProcessAndroidUserId(): Int =
        (android.os.Process.myUid() / 100_000).coerceAtLeast(0)

    private fun lockCurrentProcessUserOrReject(current: GroupLink): Boolean {
        val userId = currentProcessAndroidUserId()
        val ok = app.preferences.lockRuntimeAndroidUserId(userId)
        if (!ok) {
            runtimeDiagnostic(
                current,
                "ANDROID_USER_MISMATCH",
                "expected=${app.preferences.runtimeLockedAndroidUserId}; actual=$userId"
            )
        }
        return ok
    }
'''
quick = replace_once(quick, app_getter_anchor, runtime_helpers, "Accessibility runtime helpers")

quick = quick.replace(
    'delay(RuntimeCadencePolicy.pollIntervalMs(app.preferences.fastHandsFreeMode))',
    'delay(runtimeSpeed().fallbackPollMs)'
)

scan_block_old = '''                    val minScanInterval = RuntimeCadencePolicy.minScanIntervalMs(
                        fast = app.preferences.fastHandsFreeMode,
                        stableScreenScans = stableScreenFingerprintScans
                    )
'''
scan_block_new = '''                    val speed = runtimeSpeed()
                    val minScanInterval =
                        if (stableScreenFingerprintScans >= RuntimeCadencePolicy.STABLE_SCREEN_RELAX_AFTER_SCANS) {
                            speed.stableScanMs
                        } else {
                            speed.eventScanMs
                        }
'''
quick = replace_once(quick, scan_block_old, scan_block_new, "Accessibility event scan profile")

current_anchor = '''        val current = withContext(Dispatchers.IO) { loadCurrentLinkOrStop() } ?: return
        ensureTrackingFor(current.id)
'''
current_new = '''        val current = withContext(Dispatchers.IO) { loadCurrentLinkOrStop() } ?: return
        app.preferences.beginRuntimeLink(
            current.id,
            current.position,
            current.url,
            "ACCESSIBILITY"
        )
        if (!lockCurrentProcessUserOrReject(current)) {
            completeAndAdvance(
                current,
                LinkStatus.FAILED,
                LinkResultCode.UNKNOWN_SCREEN,
                "Android user/profile identity changed during the run; skipped without clicking",
                fastAdvance = true,
                surfaceAlreadyExited = true
            )
            return
        }
        ensureTrackingFor(current.id)
'''
quick = replace_once(quick, current_anchor, current_new, "Accessibility persisted link start")

inspect_anchor = '''        val screen = inspectScreen(root)
        updateScreenFingerprintStability(screen)
'''
inspect_new = '''        val screen = inspectScreen(root)
        app.preferences.markRuntimePhase(
            when {
                screen.loading -> LinkRuntimePhase.OPENING
                screen.action == AccessibilityJoinAction.PREVIEW -> LinkRuntimePhase.PREVIEW
                screen.action != null -> LinkRuntimePhase.ACTION_READY
                readPendingAction(current) != null -> LinkRuntimePhase.VERIFYING
                else -> app.preferences.runtimeLinkPhase
            },
            "ACCESSIBILITY:${screen.action?.name ?: "SCREEN"}"
        )
        updateScreenFingerprintStability(screen)
'''
quick = replace_once(quick, inspect_anchor, inspect_new, "Accessibility phase observation")

restricted_old = '''            RuntimeDirective.STOP_RESTRICTED -> {
                resetAdaptiveEvidence()
                resetConflictEvidence()
                withContext(Dispatchers.IO) {
                    app.repository.markStatus(
                        current.id, LinkStatus.FAILED, LinkResultCode.RESTRICTED,
                        "WhatsApp displayed a restriction or retry-later screen"
                    )
                }
                GroupJoinerResultStore.sync(this, app.repository.loadActiveSnapshot())
                stopBatch(AutomationStopReason.RESTRICTED_SCREEN, "Restriction screen detected")
            }
'''
restricted_new = '''            RuntimeDirective.STOP_RESTRICTED -> {
                resetAdaptiveEvidence()
                resetConflictEvidence()
                if (app.preferences.restrictionHandlingMode == RestrictionHandlingMode.STOP_RUN) {
                    withContext(Dispatchers.IO) {
                        app.repository.markStatus(
                            current.id,
                            LinkStatus.FAILED,
                            LinkResultCode.RESTRICTED,
                            app.preferences.buildRuntimeAuditDetail(
                                "WhatsApp displayed a restriction/retry-later screen",
                                LinkResultCode.RESTRICTED.name,
                                "ACCESSIBILITY"
                            )
                        )
                    }
                    stopBatch(
                        AutomationStopReason.RESTRICTED_SCREEN,
                        "Restriction detected; user policy is Stop run"
                    )
                } else {
                    completeAndAdvance(
                        current,
                        LinkStatus.FAILED,
                        LinkResultCode.RESTRICTED,
                        "Restriction recorded for this link; no bypass attempted",
                        fastAdvance = true,
                        terminalEscapeAdvance = true
                    )
                }
            }
'''
quick = replace_once(quick, restricted_old, restricted_new, "Accessibility restriction mode")

terminal_old = '''    private fun hasTerminalEvidence(screen: ScreenInspection): Boolean =
        screen.failure != null || screen.requestSubmitted || screen.alreadyMember
'''
terminal_new = '''    private fun hasTerminalEvidence(screen: ScreenInspection): Boolean =
        screen.failure != null ||
            screen.requestSubmitted ||
            screen.alreadyMember ||
            (screen.restricted &&
                app.preferences.restrictionHandlingMode == RestrictionHandlingMode.SKIP_AND_CONTINUE)
'''
quick = replace_once(quick, terminal_old, terminal_new, "restriction terminal evidence")

quick = quick.replace(
    'RuntimeCadencePolicy.clickThrottleMs(app.preferences.fastHandsFreeMode)',
    'runtimeSpeed().clickThrottleMs'
)
quick = quick.replace(
    'RuntimeCadencePolicy.gestureDurationMs(app.preferences.fastHandsFreeMode)',
    'runtimeSpeed().gestureDurationMs'
)
quick = quick.replace(
    'RuntimeCadencePolicy.resultInferenceDelayMs(app.preferences.fastHandsFreeMode)',
    'runtimeSpeed().postTapWaitMs'
)
quick = quick.replace(
    'RuntimeCadencePolicy.exitSettleMs(app.preferences.fastHandsFreeMode)',
    'runtimeSpeed().postTapWaitMs.coerceIn(6L, 120L)'
)
quick = quick.replace(
    'ContinuousHandoffPolicy.watchIntervalMs(app.preferences.fastHandsFreeMode)',
    'runtimeSpeed().watchdogIntervalMs'
)

click_success_anchor = '''            recordActionAttempt(current.id, action, node)
            runtimeDiagnostic(current, "CLICK", "Executed ${action.name}; attempt=$actionAttempts")
'''
click_success_new = '''            recordActionAttempt(current.id, action, node)
            app.preferences.recordRuntimeAction(action.name, "ACCESSIBILITY")
            app.preferences.markRuntimePhase(
                LinkRuntimePhase.ACTION_TAPPED,
                "ACCESSIBILITY:${action.name}"
            )
            runtimeDiagnostic(current, "CLICK", "Executed ${action.name}; attempt=$actionAttempts")
'''
quick = replace_once(quick, click_success_anchor, click_success_new, "Accessibility action persistence")

quick = quick.replace(
    'private const val DIRECT_CONVERSATION_STABLE_SCANS = 2',
    'private const val DIRECT_CONVERSATION_STABLE_SCANS = 1'
)
quick = quick.replace(
    'private const val DIRECT_CONVERSATION_FAST_MIN_AGE_MS = 180L',
    'private const val DIRECT_CONVERSATION_FAST_MIN_AGE_MS = 60L'
)

exit_back_anchor = '''            // Normal fallback: Android Back. Coordinate close is reserved for the last verified
            // invite attempt and never used on a normal conversation.
'''
safe_cancel_code = r'''            val safeCancel = collectVisibleNodes(root)
                .firstOrNull { item ->
                    item.node.isVisibleToUser &&
                        item.node.isEnabled &&
                        sequenceOf(
                            item.text,
                            item.description,
                            item.hint,
                            item.viewId
                        ).any(AccessibilityJoinMatcher::isSafeDialogCancel)
                }
                ?.node
            if (terminalSurface && safeCancel != null) {
                val cancelled = withContext(Dispatchers.Main.immediate) {
                    clickNodeParentOrGesture(safeCancel, allowSafeClose = true)
                }
                if (cancelled) {
                    delay(terminalDismissSettleDelayMs())
                    return@repeat
                }
            }

'''
quick = insert_before(quick, exit_back_anchor, safe_cancel_code, "Accessibility safe cancel exit")

unknown_final_anchor = '''        // Never reopen the same invite URL. If it never appears, wait for the conservative
        // timeout and then move on. Input parsing also de-duplicates canonical invite URLs.
        if (age >= effectiveTimeoutMs &&
            AdaptiveInteractionPolicy.unknownIsStableEnough(unknownStableScans)
        ) {
            completeAndAdvance(
                current,
                LinkStatus.FAILED,
                LinkResultCode.UNKNOWN_SCREEN,
                "No known group or community invitation control appeared before the conservative timeout"
            )
        }
'''
unknown_final_new = r'''        if (age >= minOf(effectiveTimeoutMs, runtimeSpeed().unknownRecoveryAfterMs) &&
            AdaptiveInteractionPolicy.unknownIsStableEnough(unknownStableScans)
        ) {
            if (pending == null && attemptUnknownDeepLinkRecovery(current)) return

            completeAndAdvance(
                current,
                LinkStatus.FAILED,
                LinkResultCode.UNKNOWN_SCREEN,
                "Unknown screen remained after bounded Scan/Exit/Back/one-reopen recovery",
                fastAdvance = true
            )
        }
'''
quick = replace_once(quick, unknown_final_anchor, unknown_final_new, "Accessibility Unknown recovery")

recovery_anchor = '''    private suspend fun handleUnavailableRoot(current: GroupLink) {
'''
recovery_helper = r'''    private suspend fun attemptUnknownDeepLinkRecovery(current: GroupLink): Boolean {
        if (!app.preferences.recordRecoveryReopen(current.id)) return false

        app.preferences.markRuntimePhase(
            LinkRuntimePhase.EXITING,
            "ACCESSIBILITY:UNKNOWN_RECOVERY"
        )
        exitInvitationSurface()

        val destination = withContext(Dispatchers.Main.immediate) {
            WhatsAppLauncher.launch(
                this@QuickJoinAccessibilityService,
                current.url,
                app.preferences.preferredTarget,
                app.preferences.runtimeLockedWhatsAppPackage
                    ?: app.preferences.selectedWhatsAppPackage,
                strictProfileTarget = app.preferences.strictProfileTargeting,
                expectedProfileKey = app.preferences.runtimeLockedProfileKey
            )
        }

        return when (destination) {
            LaunchDestination.PERSONAL,
            LaunchDestination.BUSINESS,
            LaunchDestination.CLONED,
            LaunchDestination.SELECTED,
            LaunchDestination.DUAL_CHOOSER -> {
                app.preferences.markAutomationLaunched()
                app.preferences.markRuntimePhase(
                    LinkRuntimePhase.OPENING,
                    "ACCESSIBILITY:RECOVERY_REOPENED"
                )
                lastScanAt = 0L
                requestScan()
                true
            }
            else -> false
        }
    }

'''
quick = insert_before(quick, recovery_anchor, recovery_helper, "Accessibility Deep Link recovery helper")

mark_status_anchor = '''                app.repository.markStatus(current.id, status, resultCode, detail)
                invalidateRuntimeCache()
'''
mark_status_new = '''                app.preferences.markRuntimePhase(
                    LinkRuntimePhase.EXITING,
                    "ACCESSIBILITY:${resultCode.name}"
                )
                val auditedDetail = app.preferences.buildRuntimeAuditDetail(
                    detail,
                    resultCode.name,
                    "ACCESSIBILITY"
                )
                app.repository.markStatus(current.id, status, resultCode, auditedDetail)
                app.preferences.finishRuntimeLink(
                    current.position,
                    "ACCESSIBILITY:${resultCode.name}"
                )
                invalidateRuntimeCache()
'''
quick = replace_once(quick, mark_status_anchor, mark_status_new, "Accessibility audit result")

delay_anchor = '''        val delayMs = if (terminalEscapeAdvance) 0 else app.preferences.interLinkDelayMs
'''
delay_new = '''        app.preferences.markRuntimePhase(
            LinkRuntimePhase.ADVANCING,
            "ACCESSIBILITY:NEXT"
        )
        val configuredDelay = app.preferences.runtimeSpeedProfile().interLinkDelayMs.toInt()
        val delayMs = if (terminalEscapeAdvance) 0 else configuredDelay
'''
quick = replace_once(quick, delay_anchor, delay_new, "Accessibility profile interlink delay")

limit_anchor = '''        if (state.limitReached) {
            finishBatch(AutomationStopReason.BATCH_LIMIT_REACHED, "Run window completed; queued links remain available for the next explicit batch")
            return
        }
'''
limit_new = '''        if (state.limitReached) {
            if (app.preferences.autoResumeCurrentRun && state.next != null) {
                app.preferences.accessibilityProcessedCount = 0
                runtimeDiagnostic(
                    current,
                    "AUTO_BATCH_CONTINUE",
                    "1000-link internal window completed; Auto Resume continues the same disk-backed queue"
                )
            } else {
                finishBatch(
                    AutomationStopReason.BATCH_LIMIT_REACHED,
                    "Run window completed; queued links remain resumable"
                )
                return
            }
        }
'''
quick = replace_once(quick, limit_anchor, limit_new, "Accessibility queue continuity >1000")
write(QUICK, quick)

# ============================================================
# 7) Shizuku engine
# ============================================================
sh = read(SHIZUKU)

sh_import_anchor = '''import com.althmany.groupmanager.domain.AutomationStopReason
'''
sh_import_new = '''import com.althmany.groupmanager.domain.AutomationStopReason
import com.althmany.groupmanager.domain.LinkRuntimePhase
import com.althmany.groupmanager.domain.RestrictionHandlingMode
'''
sh = replace_once(sh, sh_import_anchor, sh_import_new, "Shizuku runtime imports")

sh_app_anchor = '''    private val app: GroupManagerApp get() = application as GroupManagerApp
'''
sh_helpers = sh_app_anchor + r'''
    private fun runtimeSpeed() = app.preferences.runtimeSpeedProfile()
'''
sh = replace_once(sh, sh_app_anchor, sh_helpers, "Shizuku speed helper")

sh_current_anchor = '''            if (!prefs.communityTraversalActive &&
                (prefs.automationStage == AutomationStage.OPENING_LINK ||
                    prefs.automationStage == AutomationStage.WAITING_BEFORE_NEXT)
            ) {
'''
sh_current_new = '''            prefs.beginRuntimeLink(
                current.id,
                current.position,
                current.url,
                "SHIZUKU"
            )

            if (!prefs.communityTraversalActive &&
                (prefs.automationStage == AutomationStage.OPENING_LINK ||
                    prefs.automationStage == AutomationStage.WAITING_BEFORE_NEXT)
            ) {
'''
sh = replace_once(sh, sh_current_anchor, sh_current_new, "Shizuku persisted link start")

sh = sh.replace(
    'if (fastUiMode != FastUiMode.ACTIVE) delay(OPEN_SETTLE_MS)',
    'if (fastUiMode != FastUiMode.ACTIVE) delay(runtimeSpeed().postTapWaitMs.coerceAtMost(OPEN_SETTLE_MS))'
)
sh = sh.replace(
    'delay(FOREGROUND_RECHECK_MS)',
    'delay(runtimeSpeed().stableScanMs)'
)
sh = sh.replace(
    'if (fastUiMode != FastUiMode.ACTIVE) delay(DUMP_RETRY_MS)',
    'if (fastUiMode != FastUiMode.ACTIVE) delay(runtimeSpeed().stableScanMs)'
)
sh = sh.replace(
    'if (fastUiMode != FastUiMode.ACTIVE) delay(SCAN_INTERVAL_MS)',
    'if (fastUiMode != FastUiMode.ACTIVE) delay(runtimeSpeed().eventScanMs)'
)

user_id_anchor = '''        cachedTargetPackage = targetPackage
        cachedAndroidUserId = id
        return id
'''
user_id_new = '''        if (id != null && !app.preferences.lockRuntimeAndroidUserId(id)) {
            RuntimeDiagnosticStore.append(
                this,
                "SHIZUKU_ANDROID_USER_MISMATCH",
                "expected=${app.preferences.runtimeLockedAndroidUserId}; actual=$id; target=$targetPackage"
            )
            return null
        }
        cachedTargetPackage = targetPackage
        cachedAndroidUserId = id
        return id
'''
sh = replace_once(sh, user_id_anchor, user_id_new, "Shizuku userId persistence")

snapshot_anchor = '''        val prefs = app.preferences
        updateSnapshotStability(snapshot)
'''
snapshot_new = '''        val prefs = app.preferences
        prefs.markRuntimePhase(
            when {
                snapshot.screenKind == AutomationScreenKind.LOADING -> LinkRuntimePhase.OPENING
                snapshot.screenKind == AutomationScreenKind.PREVIEW_ACTION -> LinkRuntimePhase.PREVIEW
                snapshot.screenKind in setOf(
                    AutomationScreenKind.JOIN_ACTION,
                    AutomationScreenKind.REQUEST_ACTION
                ) -> LinkRuntimePhase.ACTION_READY
                readPendingAction(current) != null -> LinkRuntimePhase.VERIFYING
                else -> prefs.runtimeLinkPhase
            },
            "SHIZUKU:${snapshot.screenKind.name}"
        )
        updateSnapshotStability(snapshot)
'''
sh = replace_once(sh, snapshot_anchor, snapshot_new, "Shizuku phase observation")

restricted_anchor = '''        if (snapshot.screenKind == AutomationScreenKind.RESTRICTED) {
            withContext(Dispatchers.IO) {
                app.repository.markStatus(
                    current.id,
                    LinkStatus.FAILED,
                    LinkResultCode.RESTRICTED,
                    "WhatsApp displayed a restriction/retry-later screen while Shizuku was active"
                )
            }
            stopRun(AutomationStopReason.RESTRICTED_SCREEN, "Restriction screen detected; no bypass attempted")
            return true
        }
'''
restricted_new = '''        if (snapshot.screenKind == AutomationScreenKind.RESTRICTED) {
            if (prefs.restrictionHandlingMode == RestrictionHandlingMode.STOP_RUN) {
                withContext(Dispatchers.IO) {
                    app.repository.markStatus(
                        current.id,
                        LinkStatus.FAILED,
                        LinkResultCode.RESTRICTED,
                        prefs.buildRuntimeAuditDetail(
                            "WhatsApp displayed a restriction/retry-later screen; no bypass attempted",
                            LinkResultCode.RESTRICTED.name,
                            "SHIZUKU"
                        )
                    )
                }
                stopRun(
                    AutomationStopReason.RESTRICTED_SCREEN,
                    "Restriction detected; user policy is Stop run"
                )
                return true
            }

            completeTerminalResult(
                current,
                LinkStatus.FAILED,
                LinkResultCode.RESTRICTED,
                "Restriction recorded for this link; no bypass attempted",
                snapshot,
                targetPackage
            )
            return false
        }
'''
sh = replace_once(sh, restricted_anchor, restricted_new, "Shizuku restriction mode")

restricted_cmd_anchor = '''            AutomationCommand.STOP_RESTRICTED -> {
                stopRun(AutomationStopReason.RESTRICTED_SCREEN, decision.diagnostic)
                return true
            }
'''
restricted_cmd_new = '''            AutomationCommand.STOP_RESTRICTED -> {
                if (prefs.restrictionHandlingMode == RestrictionHandlingMode.STOP_RUN) {
                    stopRun(AutomationStopReason.RESTRICTED_SCREEN, decision.diagnostic)
                    return true
                }
                completeTerminalResult(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.RESTRICTED,
                    "${decision.diagnostic}; recorded and continued",
                    snapshot,
                    targetPackage
                )
                return false
            }
'''
sh = replace_once(sh, restricted_cmd_anchor, restricted_cmd_new, "Shizuku restriction decision")

tap_success_anchor = '''        consecutiveInputFailures = 0
        armFastBurst()
        return true
'''
tap_success_new = '''        consecutiveInputFailures = 0
        app.preferences.recordRuntimeAction(purpose, "SHIZUKU")
        app.preferences.markRuntimePhase(
            LinkRuntimePhase.ACTION_TAPPED,
            "SHIZUKU:$purpose"
        )
        armFastBurst()
        return true
'''
sh = replace_once(sh, tap_success_anchor, tap_success_new, "Shizuku action persistence")

ack_back_anchor = '''        // 2) Conversation/result fallback: at most two verified Back attempts.
        repeat(2) { attempt ->
'''
smart_exit_block = r'''        val safeCancel = snapshot.nodes.asSequence()
            .filter { node ->
                node.enabled &&
                    node.bounds?.valid == true &&
                    node.belongsTo(targetPackage)
            }
            .firstOrNull { node ->
                node.labels().any(AccessibilityJoinMatcher::isSafeDialogCancel)
            }
        if (safeCancel?.bounds != null) {
            val dismissed = tapNode(
                safeCancel.bounds,
                targetPackage,
                current,
                "RESULT_SAFE_CANCEL"
            )
            if (dismissed) {
                delay(runtimeSpeed().postTapWaitMs.coerceIn(6L, 120L))
                val afterCancel = quickResultSnapshot(targetPackage)
                if (afterCancel == null) return true
                snapshot = afterCancel
                if (!snapshot.inviteContext && !snapshot.conversationSurface) return true
            }
        }

        // 3) Conversation/result fallback: bounded verified Back attempts.
        repeat(com.althmany.groupmanager.domain.SmartExitControllerPolicy.MAX_BACK_ATTEMPTS) { attempt ->
'''
sh = replace_once(sh, ack_back_anchor, smart_exit_block, "Shizuku safe Cancel/Back controller")

result_surface_anchor = '''        if (!subgroup) {
            if (snapshot.inviteContext || snapshot.conversationSurface) {
                dismissKnownResultSurface(targetPackage, current, snapshot)
            }
            completeCurrent(current, status, resultCode, detail)
            return
        }
'''
result_surface_new = '''        if (!subgroup) {
            val terminalSurface = snapshot.screenKind in setOf(
                AutomationScreenKind.REQUEST_SUBMITTED,
                AutomationScreenKind.ALREADY_MEMBER,
                AutomationScreenKind.GROUP_FULL,
                AutomationScreenKind.INVALID_OR_EXPIRED,
                AutomationScreenKind.REMOVED_OR_BANNED,
                AutomationScreenKind.GENERIC_FAILURE,
                AutomationScreenKind.RESTRICTED
            )
            if (snapshot.inviteContext || snapshot.conversationSurface || terminalSurface) {
                prefs.markRuntimePhase(LinkRuntimePhase.EXITING, "SHIZUKU:SMART_EXIT")
                dismissKnownResultSurface(targetPackage, current, snapshot)
            }
            completeCurrent(current, status, resultCode, detail)
            return
        }
'''
sh = replace_once(sh, result_surface_anchor, result_surface_new, "Shizuku terminal exit coverage")

fast_frame_start = '''    private fun fastFrameTimeoutMs(current: GroupLink): Long {
        val pending = readPendingAction(current)
        return when {
'''
fast_frame_new = '''    private fun fastFrameTimeoutMs(current: GroupLink): Long {
        val pending = readPendingAction(current)
        val speed = runtimeSpeed()
        return when {
'''
sh = replace_once(sh, fast_frame_start, fast_frame_new, "Shizuku fast-frame speed local")
sh = sh.replace(
    'minOf(ShizukuFastUiPolicy.WATCHDOG_INTERVAL_MS, untilResultFallback)',
    'minOf(speed.watchdogIntervalMs, untilResultFallback)'
)
sh = sh.replace(
    'SystemClock.elapsedRealtime() <= fastBurstUntilElapsed -> ShizukuFastUiPolicy.STABLE_SCAN_MS',
    'SystemClock.elapsedRealtime() <= fastBurstUntilElapsed -> speed.stableScanMs'
)
sh = sh.replace(
    'stableSnapshotScans < ShizukuFastUiPolicy.STABLE_SCANS_BEFORE_FALLBACK_POLL -> ShizukuFastUiPolicy.STABLE_SCAN_MS',
    'stableSnapshotScans < ShizukuFastUiPolicy.STABLE_SCANS_BEFORE_FALLBACK_POLL -> speed.stableScanMs'
)
sh = sh.replace(
    'else -> ShizukuFastUiPolicy.FALLBACK_POLL_MS',
    'else -> speed.fallbackPollMs'
)

post_join_anchor = '''    private fun postJoinMinEvidenceMs(): Long =
        if (fastUiMode == FastUiMode.ACTIVE) ShizukuFastUiPolicy.POST_JOIN_MIN_EVIDENCE_MS else POST_JOIN_MIN_MS
'''
post_join_new = '''    private fun postJoinMinEvidenceMs(): Long =
        if (fastUiMode == FastUiMode.ACTIVE) {
            runtimeSpeed().postTapWaitMs.coerceAtLeast(12L)
        } else POST_JOIN_MIN_MS
'''
sh = replace_once(sh, post_join_anchor, post_join_new, "Shizuku post action speed")
sh = sh.replace(
    'if (pendingAgeMs() < ShizukuFastUiPolicy.ACTION_RETRY_AFTER_MS) return false',
    'if (pendingAgeMs() < runtimeSpeed().actionRetryAfterMs) return false'
)

cooldown_anchor = '''        val cooldown = ShizukuRuntimePolicy.inputCooldownMs(fastUiMode == FastUiMode.ACTIVE)
        val wait = cooldown - elapsed
'''
cooldown_new = '''        val cooldown = if (fastUiMode == FastUiMode.ACTIVE) {
            runtimeSpeed().clickThrottleMs
        } else {
            ShizukuRuntimePolicy.inputCooldownMs(false)
        }
        val wait = cooldown - elapsed
'''
sh = replace_once(sh, cooldown_anchor, cooldown_new, "Shizuku click throttle profile")

stop_unknown_anchor = '''            AutomationCommand.STOP_UNKNOWN -> {
                runtimeDiagnostic(current, "SHIZUKU_UNKNOWN_CONTINUITY_ADVANCE", decision.diagnostic)
                completeCurrent(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.UNKNOWN_SCREEN,
                    "${decision.diagnostic}. Continuity mode advanced without guessing or pressing an unsafe action"
                )
                return false
            }
'''
stop_unknown_new = '''            AutomationCommand.STOP_UNKNOWN -> {
                if (attemptUnknownRecoveryOnce(current, targetPackage, snapshot)) {
                    return false
                }
                runtimeDiagnostic(current, "SHIZUKU_UNKNOWN_CONTINUITY_ADVANCE", decision.diagnostic)
                completeCurrent(
                    current,
                    LinkStatus.FAILED,
                    LinkResultCode.UNKNOWN_SCREEN,
                    "${decision.diagnostic}. Scan/Exit/Back/one-reopen recovery exhausted; skipped safely"
                )
                return false
            }
'''
sh = replace_once(sh, stop_unknown_anchor, stop_unknown_new, "Shizuku Unknown recovery command")

sh_recovery_anchor = '''    private suspend fun beginCommunityTraversalAfterJoin(
'''
sh_recovery_helper = r'''    private suspend fun attemptUnknownRecoveryOnce(
        current: GroupLink,
        targetPackage: String,
        snapshot: ShizukuUiSnapshot
    ): Boolean {
        if (readPendingAction(current) != null) return false
        if (!app.preferences.recordRecoveryReopen(current.id)) return false

        app.preferences.markRuntimePhase(
            LinkRuntimePhase.EXITING,
            "SHIZUKU:UNKNOWN_RECOVERY"
        )
        if (snapshot.inviteContext || snapshot.conversationSurface) {
            dismissKnownResultSurface(targetPackage, current, snapshot)
        }

        val reopened = openInvitation(
            current,
            targetPackage,
            forceResolvedActivity = true
        )
        if (reopened) {
            app.preferences.markAutomationLaunched()
            app.preferences.markRuntimePhase(
                LinkRuntimePhase.OPENING,
                "SHIZUKU:RECOVERY_REOPENED"
            )
            runtimeDiagnostic(
                current,
                "SHIZUKU_UNKNOWN_REOPEN",
                "one bounded exact-user/package Deep Link recovery dispatched"
            )
        }
        return reopened
    }

'''
sh = insert_before(sh, sh_recovery_anchor, sh_recovery_helper, "Shizuku Unknown recovery helper")

dump_stop_anchor = '''            stopRun(
                AutomationStopReason.TARGET_UNSUPPORTED,
                "Shizuku could not obtain a WhatsApp UI hierarchy after compatibility recovery. The current link was preserved and was not marked failed."
            )
'''
dump_stop_new = '''            completeCurrent(
                current,
                LinkStatus.FAILED,
                LinkResultCode.UNKNOWN_SCREEN,
                "Shizuku UI hierarchy remained unavailable after bounded recovery; link recorded UNKNOWN and queue continued"
            )
'''
sh = replace_once(sh, dump_stop_anchor, dump_stop_new, "Shizuku UI-tree no queue stop")

sh_mark_anchor = '''                app.repository.markStatus(current.id, status, resultCode, detail)
                prefs.clearAccessibilityPending()
'''
sh_mark_new = '''                prefs.markRuntimePhase(
                    LinkRuntimePhase.EXITING,
                    "SHIZUKU:${resultCode.name}"
                )
                val auditedDetail = prefs.buildRuntimeAuditDetail(
                    detail,
                    resultCode.name,
                    "SHIZUKU"
                )
                app.repository.markStatus(current.id, status, resultCode, auditedDetail)
                prefs.finishRuntimeLink(
                    current.position,
                    "SHIZUKU:${resultCode.name}"
                )
                prefs.clearAccessibilityPending()
'''
sh = replace_once(sh, sh_mark_anchor, sh_mark_new, "Shizuku result audit")

sh_delay_anchor = '''        val waitMs = prefs.interLinkDelayMs.toLong()
'''
sh_delay_new = '''        prefs.markRuntimePhase(LinkRuntimePhase.ADVANCING, "SHIZUKU:NEXT")
        val waitMs = prefs.runtimeSpeedProfile().interLinkDelayMs
'''
sh = replace_once(sh, sh_delay_anchor, sh_delay_new, "Shizuku profile interlink delay")

sh_limit_anchor = '''        if (state.limitReached) {
            completeRun(AutomationStopReason.BATCH_LIMIT_REACHED, "Shizuku explicit-run limit reached")
            return
        }
'''
sh_limit_new = '''        if (state.limitReached) {
            if (prefs.autoResumeCurrentRun && state.next != null) {
                prefs.accessibilityProcessedCount = 0
                runtimeDiagnostic(
                    current,
                    "SHIZUKU_AUTO_BATCH_CONTINUE",
                    "1000-link internal window completed; Auto Resume continues queued links"
                )
            } else {
                completeRun(
                    AutomationStopReason.BATCH_LIMIT_REACHED,
                    "Shizuku run window reached; queued links remain resumable"
                )
                return
            }
        }
'''
sh = replace_once(sh, sh_limit_anchor, sh_limit_new, "Shizuku queue continuity >1000")
write(SHIZUKU, sh)

# ============================================================
# 8) Regression + validator
# ============================================================
run_reg = read(RUN_REG)
if "RuntimeSpeedProfile.kt" not in run_reg:
    run_reg = run_reg.replace(
        '  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeCadencePolicy.kt" \\\n',
        '  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeCadencePolicy.kt" \\\n'
        '  "$ROOT/app/src/main/java/com/althmany/groupmanager/domain/RuntimeSpeedProfile.kt" \\\n',
        1
    )
write(RUN_REG, run_reg)

reg = read(REG)
reg_anchor = '''    expect("stable fast screen relaxes cadence", 30L, RuntimeCadencePolicy.minScanIntervalMs(true, 8))
'''
reg_tests = reg_anchor + r'''    val maxSpeed = RuntimeSpeedProfilePolicy.resolve(RuntimeSpeedMode.MAX)
    expect("MAX event scan", 6L, maxSpeed.eventScanMs)
    expect("MAX stable scan", 14L, maxSpeed.stableScanMs)
    expect("MAX fallback poll", 40L, maxSpeed.fallbackPollMs)
    expect("MAX post tap", 22L, maxSpeed.postTapWaitMs)
    expect("MAX next link", 0L, maxSpeed.interLinkDelayMs)

    val customSpeed = RuntimeSpeedProfilePolicy.resolve(
        RuntimeSpeedMode.CUSTOM,
        customScanMs = 18,
        customPostTapMs = 75,
        customInterLinkMs = 0
    )
    expect("Custom event scan", 18L, customSpeed.eventScanMs)
    expect("Custom post tap", 75L, customSpeed.postTapWaitMs)
    expect("Custom zero next", 0L, customSpeed.interLinkDelayMs)
'''
reg = replace_once(reg, reg_anchor, reg_tests, "3.0 speed regressions")
write(REG, reg)

validator = read(VALIDATOR)
validator = validator.replace(
    '"versionCode 290": "versionCode = 290" in build,',
    '"versionCode 300": "versionCode = 300" in build,'
)
validator = validator.replace(
    '"versionName 2.9.0": \'versionName = "2.9.0"\' in build,',
    '"versionName 3.0.0": \'versionName = "3.0.0"\' in build,'
)

runtime_speed_read_anchor = '''runtime_cadence = (JAVA / "com/althmany/groupmanager/domain/RuntimeCadencePolicy.kt").read_text(encoding="utf-8")
'''
runtime_speed_read_new = runtime_speed_read_anchor + '''runtime_speed_profile = (JAVA / "com/althmany/groupmanager/domain/RuntimeSpeedProfile.kt").read_text(encoding="utf-8")
'''
validator = replace_once(
    validator,
    runtime_speed_read_anchor,
    runtime_speed_read_new,
    "validator RuntimeSpeedProfile source"
)

checks_anchor = '''checks = {
'''
checks_new = '''checks = {
    "3.0 five speed modes": all(token in runtime_speed_profile for token in ["STABLE", "FAST", "TURBO", "MAX", "CUSTOM", "eventScanMs", "postTapWaitMs", "interLinkDelayMs"]),
    "3.0 professional link phases": all(token in runtime_speed_profile for token in ["OPENING", "PREVIEW", "ACTION_READY", "ACTION_TAPPED", "VERIFYING", "EXITING", "ADVANCING"]),
    "3.0 Smart Exit controller": all(token in runtime_speed_profile for token in ["SAFE_CLOSE", "TERMINAL_ACK", "SAFE_CANCEL", "BACK", "DIRECT_NEXT_DEEP_LINK"]) and "isSafeDialogCancel" in matcher,
    "3.0 persistent resume state": all(token in preferences_source for token in ["runtimeLockedAndroidUserId", "runtimeCurrentLinkId", "runtimeCurrentLinkUrl", "runtimeActionExecuted", "runtimeRecoveryReopenAttempts", "lastCompletedLinkPosition"]),
    "3.0 restriction user policy": "RestrictionHandlingMode" in runtime_speed_profile and "continueOnRestrictionSwitch" in main_activity and "restrictionHandlingMode" in preferences_source,
    "3.0 compact dashboard": all(token in main_layout for token in ["speedModeToggleGroup", "customSpeedControls", "resumeLastRunButton", "advancedSettingsButton", "advancedSmartCard", "advancedScheduleCard"]),
'''
validator = replace_once(validator, checks_anchor, checks_new, "validator 3.0 checks")
write(VALIDATOR, validator)

print("AL-thmany 3.0.0 SMART CONTINUITY ENGINE APPLIED")
print("")
print("Implemented:")
print(" - Stable / Fast / Turbo / MAX / Custom")
print(" - Custom Scan / Post-Tap / Inter-Link controls")
print(" - 0ms next-link handoff")
print(" - Smart Exit: X/Close -> OK/Done -> safe Cancel -> Back -> next Deep Link")
print(" - Restriction: STOP or SKIP+CONTINUE")
print(" - Unknown: Scan -> Exit/Back -> one reopen -> UNKNOWN -> next")
print(" - Event-driven scan; polling remains fallback")
print(" - Persisted phases OPENING..ADVANCING")
print(" - Persisted package/userId/profile/current link/action/attempt/recovery/engine")
print(" - Result audit detail with attempts/time/backend")
print(" - Auto Resume + Resume from last link")
print(" - Auto continuation across internal 1000-link windows")
print(" - Compact main UI + Advanced Settings")
print("")
print("NEXT:")
print(" python3 scripts/validate_source.py")
print(" grep -nE 'versionCode|versionName' app/build.gradle.kts")
