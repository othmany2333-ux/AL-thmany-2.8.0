package com.althmany.groupmanager.domain

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
