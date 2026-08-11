package com.althmany.groupmanager.domain

/**
 * Continuity rules for SHIZUKU_FAST_UI_ACTIVE.
 *
 * The policy never turns ambiguous UI into a guessed click. Instead it performs bounded recovery
 * and advances the current link with an explicit failure result so one bad screen cannot stall the
 * remaining queue. WhatsApp restriction/retry-later remains a hard stop and structural profile or
 * permission failures are never bypassed.
 */
object ShizukuContinuityPolicy {
    const val FOREGROUND_REOPEN_AFTER_MS = 260L
    const val FOREGROUND_RECOVERY_SETTLE_MS = 18L
    const val MAX_FOREGROUND_REOPEN_ATTEMPTS = 1
    const val FOREGROUND_ADVANCE_AFTER_MS = 1_100L
    // If a healthy persistent UiAutomation frame still belongs to AL-thmany/system UI after an
    // exact-user deep-link launch, expire the optimistic foreground lease and re-prove WhatsApp.
    // This keeps transition frames out of the command-dump fallback without masking a failed launch.
    const val TARGET_HIDDEN_FOREGROUND_REPROBE_MS = 260L

    // Samsung Work Profile can keep the persistent shell UiAutomation connection attached to the
    // owner Launcher even while the exact-user WhatsApp activity is visibly foreground. Probe the
    // standard `uiautomator dump` path quickly instead of waiting on a root that cannot switch
    // profiles. The command dump must still expose exact target-package nodes before any tap.
    const val PROFILE_COMPAT_COMMAND_PROBE_AFTER_MS = 180L
    const val MAX_PROFILE_COMPAT_COMMAND_PROBES = 2

    const val NO_ROOT_ADVANCE_AFTER_MS = 1_200L
    const val UI_TREE_ADVANCE_AFTER_MS = 1_600L
    const val MAX_UI_TREE_FAILURES = 8
    const val RUNTIME_RECOVERY_POLL_MS = 240L

    const val DIRECT_CONVERSATION_MIN_AGE_MS = 35L
    const val DIRECT_CONVERSATION_MAX_AGE_MS = 3_000L
    const val DIRECT_CONVERSATION_STABLE_SCANS = 1

    fun isDirectConversationResolution(
        stage: AutomationStage,
        launchAgeMs: Long,
        stableScans: Int
    ): Boolean =
        stage in setOf(
            AutomationStage.WAITING_FOR_WHATSAPP,
            AutomationStage.LOOKING_FOR_PREVIEW,
            AutomationStage.LOOKING_FOR_JOIN
        ) &&
            launchAgeMs in DIRECT_CONVERSATION_MIN_AGE_MS..DIRECT_CONVERSATION_MAX_AGE_MS &&
            stableScans >= DIRECT_CONVERSATION_STABLE_SCANS

    fun shouldProbeProfileCompatibleTree(hiddenAgeMs: Long, probesUsed: Int): Boolean =
        hiddenAgeMs >= PROFILE_COMPAT_COMMAND_PROBE_AFTER_MS &&
            probesUsed in 0 until MAX_PROFILE_COMPAT_COMMAND_PROBES
}
