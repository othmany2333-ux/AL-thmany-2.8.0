package com.althmany.groupmanager.domain

/**
 * Pure replay helper used by regression tests and future diagnostic tooling.
 * It lets recorded/synthetic screen sequences be evaluated without Android Accessibility APIs.
 */
data class RuntimeReplayFrame(
    val screen: AutomationScreenKind,
    val elapsedMs: Long,
    val retryCount: Int = 0,
    val pendingAction: AccessibilityJoinAction? = null
)

data class RuntimeReplayStep(
    val frame: RuntimeReplayFrame,
    val decision: AutomationDecision
)

object RuntimeReplayEngine {
    fun replay(
        initialStage: AutomationStage,
        frames: List<RuntimeReplayFrame>
    ): List<RuntimeReplayStep> {
        var stage = initialStage
        return frames.map { frame ->
            val decision = AutomationDecisionEngine.decide(
                stage = stage,
                screen = frame.screen,
                stageAgeMs = frame.elapsedMs,
                retryCount = frame.retryCount,
                pendingAction = frame.pendingAction
            )
            stage = decision.nextStage
            RuntimeReplayStep(frame, decision)
        }
    }
}
