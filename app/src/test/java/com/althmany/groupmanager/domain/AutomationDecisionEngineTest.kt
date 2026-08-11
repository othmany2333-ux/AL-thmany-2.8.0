package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationDecisionEngineTest {
    @Test
    fun explicitJoinActionProducesClickDecision() {
        val decision = AutomationDecisionEngine.decide(
            stage = AutomationStage.LOOKING_FOR_JOIN,
            screen = AutomationScreenKind.JOIN_ACTION,
            stageAgeMs = 1_000,
            retryCount = 0,
            pendingAction = null
        )

        assertEquals(AutomationCommand.CLICK_JOIN, decision.command)
        assertEquals(AutomationStage.VERIFYING_RESULT, decision.nextStage)
    }

    @Test
    fun disappearanceAfterJoinWaitsForStableServiceEvidence() {
        val decision = AutomationDecisionEngine.decide(
            stage = AutomationStage.VERIFYING_RESULT,
            screen = AutomationScreenKind.UNKNOWN,
            stageAgeMs = AutomationDecisionEngine.RESULT_INFERENCE_AFTER_MS + 1,
            retryCount = 1,
            pendingAction = AccessibilityJoinAction.JOIN
        )

        assertEquals(AutomationCommand.WAIT, decision.command)
    }

    @Test
    fun loadingScreenNeverTriggersClickOrStop() {
        val decision = AutomationDecisionEngine.decide(
            stage = AutomationStage.WAITING_FOR_WHATSAPP,
            screen = AutomationScreenKind.LOADING,
            stageAgeMs = 50_000,
            retryCount = 2,
            pendingAction = null
        )
        assertEquals(AutomationCommand.WAIT, decision.command)
        assertEquals(AutomationStage.WAITING_FOR_WHATSAPP, decision.nextStage)
    }

    @Test
    fun restrictionStopsInsteadOfClickingAnythingElse() {
        val decision = AutomationDecisionEngine.decide(
            stage = AutomationStage.LOOKING_FOR_JOIN,
            screen = AutomationScreenKind.RESTRICTED,
            stageAgeMs = 2_000,
            retryCount = 0,
            pendingAction = null
        )

        assertEquals(AutomationCommand.STOP_RESTRICTED, decision.command)
    }

    @Test
    fun unknownScreenRetriesThenWaitsThenStops() {
        val retry = AutomationDecisionEngine.decide(
            stage = AutomationStage.LOOKING_FOR_PREVIEW,
            screen = AutomationScreenKind.UNKNOWN,
            stageAgeMs = AutomationDecisionEngine.TRANSIENT_SCREEN_GRACE_MS + 1,
            retryCount = 0,
            pendingAction = null
        )
        val wait = AutomationDecisionEngine.decide(
            stage = AutomationStage.LOOKING_FOR_PREVIEW,
            screen = AutomationScreenKind.UNKNOWN,
            stageAgeMs = 10_000,
            retryCount = AutomationDecisionEngine.MAX_ACTION_RETRIES,
            pendingAction = null
        )
        val stop = AutomationDecisionEngine.decide(
            stage = AutomationStage.LOOKING_FOR_PREVIEW,
            screen = AutomationScreenKind.UNKNOWN,
            stageAgeMs = AutomationDecisionEngine.UNKNOWN_SCREEN_TIMEOUT_MS + 1,
            retryCount = AutomationDecisionEngine.MAX_ACTION_RETRIES,
            pendingAction = null
        )

        assertEquals(AutomationCommand.RETRY, retry.command)
        assertEquals(AutomationCommand.WAIT, wait.command)
        assertEquals(AutomationCommand.STOP_UNKNOWN, stop.command)
    }

    @Test
    fun previewActionIsBoundedAfterTwoAttempts() {
        val wait = AutomationDecisionEngine.decide(
            stage = AutomationStage.LOOKING_FOR_JOIN,
            screen = AutomationScreenKind.PREVIEW_ACTION,
            stageAgeMs = 1_000,
            retryCount = 1,
            pendingAction = null
        )
        val stop = AutomationDecisionEngine.decide(
            stage = AutomationStage.LOOKING_FOR_JOIN,
            screen = AutomationScreenKind.PREVIEW_ACTION,
            stageAgeMs = AutomationDecisionEngine.CLICK_RETRY_AFTER_MS + 1,
            retryCount = AutomationDecisionEngine.MAX_ACTION_RETRIES,
            pendingAction = null
        )

        assertEquals(AutomationCommand.WAIT, wait.command)
        assertEquals(AutomationCommand.STOP_UNKNOWN, stop.command)
    }

    @Test
    fun firstJoinActionGetsFreshAttemptEvenAfterPreviewRetries() {
        val decision = AutomationDecisionEngine.decide(
            stage = AutomationStage.LOOKING_FOR_JOIN,
            screen = AutomationScreenKind.JOIN_ACTION,
            stageAgeMs = 9_000,
            retryCount = AutomationDecisionEngine.MAX_ACTION_RETRIES,
            pendingAction = null
        )

        assertEquals(AutomationCommand.CLICK_JOIN, decision.command)
    }
    @Test
    fun requestDisappearanceAloneDoesNotCreateFalseRequestedResult() {
        val decision = AutomationDecisionEngine.decide(
            stage = AutomationStage.VERIFYING_RESULT,
            screen = AutomationScreenKind.UNKNOWN,
            stageAgeMs = AutomationDecisionEngine.RESULT_INFERENCE_AFTER_MS + 1,
            retryCount = 1,
            pendingAction = AccessibilityJoinAction.REQUEST
        )
        assertEquals(AutomationCommand.WAIT, decision.command)
    }

}
