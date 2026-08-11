package com.althmany.groupmanager.domain

/**
 * Persisted stages for the disk-backed invitation queue with 1000-link explicit run windows.
 * The stage is intentionally explicit so the app can recover after activity recreation
 * and provide a useful diagnostic instead of silently guessing what happened.
 */
enum class AutomationStage {
    IDLE,
    SCHEDULED,
    OPENING_LINK,
    WAITING_FOR_WHATSAPP,
    LOOKING_FOR_PREVIEW,
    LOOKING_FOR_JOIN,
    VERIFYING_RESULT,
    WAITING_BEFORE_NEXT,
    PAUSED,
    COMPLETED,
    STOPPED
}

enum class AutomationStopReason {
    NONE,
    USER_STOPPED,
    BATCH_LIMIT_REACHED,
    SESSION_COMPLETE,
    SESSION_CHANGED,
    SERVICE_DISABLED,
    TARGET_UNSUPPORTED,
    OPEN_FAILED,
    BROWSER_FALLBACK,
    UNKNOWN_SCREEN,
    ACTION_TIMEOUT,
    RESTRICTED_SCREEN,
    RUNTIME_CIRCUIT_BREAKER
}

enum class AutomationScreenKind {
    PREVIEW_ACTION,
    JOIN_ACTION,
    REQUEST_ACTION,
    ALREADY_MEMBER,
    REQUEST_SUBMITTED,
    GROUP_FULL,
    INVALID_OR_EXPIRED,
    REMOVED_OR_BANNED,
    GENERIC_FAILURE,
    RESTRICTED,
    LOADING,
    UNKNOWN
}

enum class AutomationCommand {
    CLICK_PREVIEW,
    CLICK_JOIN,
    CLICK_REQUEST,
    COMPLETE_JOINED,
    COMPLETE_REQUESTED,
    COMPLETE_ALREADY_MEMBER,
    COMPLETE_GROUP_FULL,
    COMPLETE_INVALID,
    COMPLETE_REMOVED,
    COMPLETE_FAILED,
    WAIT,
    RETRY,
    STOP_RESTRICTED,
    STOP_UNKNOWN
}

data class AutomationDecision(
    val command: AutomationCommand,
    val nextStage: AutomationStage,
    val diagnostic: String
)

/** Pure decision logic; Android node traversal and clicking stay in the service. */
object AutomationDecisionEngine {
    const val TRANSIENT_SCREEN_GRACE_MS = 2_500L
    const val CLICK_RETRY_AFTER_MS = 7_500L
    const val RESULT_INFERENCE_AFTER_MS = 3_500L
    const val UNKNOWN_SCREEN_TIMEOUT_MS = 25_000L
    const val MAX_ACTION_RETRIES = 2

    fun decide(
        stage: AutomationStage,
        screen: AutomationScreenKind,
        stageAgeMs: Long,
        retryCount: Int,
        pendingAction: AccessibilityJoinAction?
    ): AutomationDecision {
        when (screen) {
            AutomationScreenKind.ALREADY_MEMBER -> return AutomationDecision(
                AutomationCommand.COMPLETE_ALREADY_MEMBER,
                AutomationStage.WAITING_BEFORE_NEXT,
                "Already a member"
            )
            AutomationScreenKind.REQUEST_SUBMITTED -> return AutomationDecision(
                AutomationCommand.COMPLETE_REQUESTED,
                AutomationStage.WAITING_BEFORE_NEXT,
                "Join request submitted"
            )
            AutomationScreenKind.GROUP_FULL -> return AutomationDecision(
                AutomationCommand.COMPLETE_GROUP_FULL,
                AutomationStage.WAITING_BEFORE_NEXT,
                "Group is full"
            )
            AutomationScreenKind.INVALID_OR_EXPIRED -> return AutomationDecision(
                AutomationCommand.COMPLETE_INVALID,
                AutomationStage.WAITING_BEFORE_NEXT,
                "Invite link is invalid or expired"
            )
            AutomationScreenKind.REMOVED_OR_BANNED -> return AutomationDecision(
                AutomationCommand.COMPLETE_REMOVED,
                AutomationStage.WAITING_BEFORE_NEXT,
                "The account was removed from this group or community"
            )
            AutomationScreenKind.GENERIC_FAILURE -> return AutomationDecision(
                AutomationCommand.COMPLETE_FAILED,
                AutomationStage.WAITING_BEFORE_NEXT,
                "WhatsApp could not complete the group join"
            )
            AutomationScreenKind.RESTRICTED -> return AutomationDecision(
                AutomationCommand.STOP_RESTRICTED,
                AutomationStage.STOPPED,
                "WhatsApp displayed a restriction or retry-later screen"
            )
            AutomationScreenKind.LOADING -> return AutomationDecision(
                AutomationCommand.WAIT,
                AutomationStage.WAITING_FOR_WHATSAPP,
                "WhatsApp is still loading the invitation"
            )
            else -> Unit
        }

        return when (screen) {
            AutomationScreenKind.PREVIEW_ACTION -> {
                if (stage == AutomationStage.LOOKING_FOR_JOIN &&
                    stageAgeMs < CLICK_RETRY_AFTER_MS
                ) {
                    AutomationDecision(
                        AutomationCommand.WAIT,
                        AutomationStage.LOOKING_FOR_JOIN,
                        "Waiting for group preview to open"
                    )
                } else if (retryCount < MAX_ACTION_RETRIES) {
                    AutomationDecision(
                        AutomationCommand.CLICK_PREVIEW,
                        AutomationStage.LOOKING_FOR_JOIN,
                        "Opening group preview"
                    )
                } else {
                    AutomationDecision(
                        AutomationCommand.STOP_UNKNOWN,
                        AutomationStage.STOPPED,
                        "View group action remained visible after retries"
                    )
                }
            }
            AutomationScreenKind.JOIN_ACTION -> {
                if (stage != AutomationStage.VERIFYING_RESULT) {
                    AutomationDecision(
                        AutomationCommand.CLICK_JOIN,
                        AutomationStage.VERIFYING_RESULT,
                        "Pressing Join group"
                    )
                } else if (stageAgeMs < CLICK_RETRY_AFTER_MS) {
                    AutomationDecision(
                        AutomationCommand.WAIT,
                        AutomationStage.VERIFYING_RESULT,
                        "Waiting for join result"
                    )
                } else if (retryCount < MAX_ACTION_RETRIES) {
                    AutomationDecision(
                        AutomationCommand.CLICK_JOIN,
                        AutomationStage.VERIFYING_RESULT,
                        "Retrying Join group"
                    )
                } else {
                    AutomationDecision(
                        AutomationCommand.STOP_UNKNOWN,
                        AutomationStage.STOPPED,
                        "Join action remained visible after retries"
                    )
                }
            }
            AutomationScreenKind.REQUEST_ACTION -> {
                if (stage != AutomationStage.VERIFYING_RESULT) {
                    AutomationDecision(
                        AutomationCommand.CLICK_REQUEST,
                        AutomationStage.VERIFYING_RESULT,
                        "Pressing Request to join"
                    )
                } else if (stageAgeMs < CLICK_RETRY_AFTER_MS) {
                    AutomationDecision(
                        AutomationCommand.WAIT,
                        AutomationStage.VERIFYING_RESULT,
                        "Waiting for request result"
                    )
                } else if (retryCount < MAX_ACTION_RETRIES) {
                    AutomationDecision(
                        AutomationCommand.CLICK_REQUEST,
                        AutomationStage.VERIFYING_RESULT,
                        "Retrying Request to join"
                    )
                } else {
                    AutomationDecision(
                        AutomationCommand.STOP_UNKNOWN,
                        AutomationStage.STOPPED,
                        "Request action remained visible after retries"
                    )
                }
            }
            AutomationScreenKind.UNKNOWN -> decideUnknown(
                stage = stage,
                stageAgeMs = stageAgeMs,
                retryCount = retryCount,
                pendingAction = pendingAction
            )
            else -> AutomationDecision(
                AutomationCommand.WAIT,
                stage,
                "Waiting for WhatsApp"
            )
        }
    }

    private fun decideUnknown(
        stage: AutomationStage,
        stageAgeMs: Long,
        retryCount: Int,
        pendingAction: AccessibilityJoinAction?
    ): AutomationDecision {
        if (stage == AutomationStage.VERIFYING_RESULT &&
            stageAgeMs >= RESULT_INFERENCE_AFTER_MS
        ) {
            return when (pendingAction) {
                // A Request-to-join result must have explicit pending/request-sent evidence.
                // The supplied WhatsApp screens show that explanatory admin-approval text can
                // appear before the request is submitted, so disappearance alone is not proof.
                AccessibilityJoinAction.REQUEST -> AutomationDecision(
                    AutomationCommand.WAIT,
                    stage,
                    "Waiting for explicit request-sent or pending-approval evidence"
                )
                AccessibilityJoinAction.JOIN -> AutomationDecision(
                    AutomationCommand.WAIT,
                    stage,
                    "Join was pressed; waiting for a stable post-action screen before recording success"
                )
                else -> AutomationDecision(
                    AutomationCommand.WAIT,
                    stage,
                    "Waiting for a verifiable result"
                )
            }
        }

        if (stageAgeMs < TRANSIENT_SCREEN_GRACE_MS) {
            return AutomationDecision(
                AutomationCommand.WAIT,
                stage,
                "Waiting for WhatsApp screen to settle"
            )
        }

        if (retryCount < MAX_ACTION_RETRIES) {
            return AutomationDecision(
                AutomationCommand.RETRY,
                AutomationStage.LOOKING_FOR_PREVIEW,
                "Retrying screen detection"
            )
        }

        if (stageAgeMs < UNKNOWN_SCREEN_TIMEOUT_MS) {
            return AutomationDecision(
                AutomationCommand.WAIT,
                stage,
                "Waiting for a known group-invitation screen"
            )
        }

        return AutomationDecision(
            AutomationCommand.STOP_UNKNOWN,
            AutomationStage.STOPPED,
            "No known group-invitation action appeared before timeout"
        )
    }
}
