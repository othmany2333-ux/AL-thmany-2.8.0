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
    const val MIN_CUSTOM_SCAN_MS = 4
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
            eventScanMs = 8L,
            stableScanMs = 18L,
            fallbackPollMs = 50L,
            postTapWaitMs = 28L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 36L,
            gestureDurationMs = 12L,
            watchdogIntervalMs = 24L,
            unknownRecoveryAfterMs = 650L,
            actionRetryAfterMs = 70L
        )
        RuntimeSpeedMode.TURBO -> RuntimeSpeedProfile(
            eventScanMs = 5L,
            stableScanMs = 10L,
            fallbackPollMs = 28L,
            postTapWaitMs = 16L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 24L,
            gestureDurationMs = 10L,
            watchdogIntervalMs = 16L,
            unknownRecoveryAfterMs = 400L,
            actionRetryAfterMs = 45L
        )
        RuntimeSpeedMode.MAX -> RuntimeSpeedProfile(
            eventScanMs = 4L,
            stableScanMs = 8L,
            fallbackPollMs = 20L,
            postTapWaitMs = 12L,
            interLinkDelayMs = 0L,
            clickThrottleMs = 20L,
            gestureDurationMs = 10L,
            watchdogIntervalMs = 12L,
            unknownRecoveryAfterMs = 320L,
            actionRetryAfterMs = 35L
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
                clickThrottleMs = maxOf(20L, scan * 3L),
                gestureDurationMs = maxOf(10L, minOf(45L, scan.toLong())),
                watchdogIntervalMs = maxOf(12L, scan * 2L),
                unknownRecoveryAfterMs = maxOf(320L, post * 8L),
                actionRetryAfterMs = maxOf(35L, post * 2L)
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
