package com.althmany.groupmanager.domain

/**
 * Deterministic confidence layer between Accessibility evidence and an irreversible UI action.
 *
 * The score is not machine learning. It is an explainable confidence model that combines context,
 * stability and conflicts so one stale accessibility node cannot trigger an action by itself.
 */
enum class RuntimeConfidenceBand {
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH
}

data class RuntimeAssessment(
    val score: Int,
    val band: RuntimeConfidenceBand,
    val safeToAct: Boolean,
    val reason: String
)

object RuntimeIntelligencePolicy {
    const val MIN_ACTION_CONFIDENCE = 78
    const val MIN_TERMINAL_CONFIDENCE = 76

    fun assessAction(
        action: AccessibilityJoinAction,
        inviteContext: Boolean,
        loading: Boolean,
        positiveActionCount: Int,
        candidateScoreMargin: Int = 0,
        terminalEvidenceCount: Int,
        conflict: ScreenEvidenceConflict,
        stableActionScans: Int,
        stableScreenScans: Int,
        pendingAction: AccessibilityJoinAction?
    ): RuntimeAssessment {
        val matureConflict = stableScreenScans >= 3
        var score = 38
        if (inviteContext) score += 24
        if (stableActionScans >= InvitationStabilityPolicy.ACTION_STABLE_SCANS) score += 18
        if (stableScreenScans >= 2) score += 8 else score -= 6
        if (positiveActionCount == 1) score += 10
        if (positiveActionCount > 1 && candidateScoreMargin >= 45) score += 18
        if (terminalEvidenceCount == 0) score += 8
        if (conflict == ScreenEvidenceConflict.NONE) score += 8
        if (pendingAction == null || pendingAction == action || action == AccessibilityJoinAction.CONFIRM) score += 8

        if (loading) score -= 100
        if (positiveActionCount > 1 && candidateScoreMargin < 45) score -= if (matureConflict) 6 else 18
        if (terminalEvidenceCount > 0) score -= 30
        if (conflict != ScreenEvidenceConflict.NONE) score -= if (matureConflict) 8 else 28
        if (pendingAction != null && pendingAction != action && action != AccessibilityJoinAction.CONFIRM) score -= 45

        score = score.coerceIn(0, 100)
        return RuntimeAssessment(
            score = score,
            band = band(score),
            safeToAct = score >= MIN_ACTION_CONFIDENCE,
            reason = "action=${action.name}; invite=$inviteContext; actionStable=$stableActionScans; screenStable=$stableScreenScans; " +
                "positive=$positiveActionCount; margin=$candidateScoreMargin; terminal=$terminalEvidenceCount; conflict=${conflict.name}"
        )
    }

    fun assessTerminal(
        loading: Boolean,
        terminalEvidenceCount: Int,
        positiveActionCount: Int,
        conflict: ScreenEvidenceConflict,
        stableOutcomeScans: Int,
        stableScreenScans: Int
    ): RuntimeAssessment {
        val matureTerminal = stableOutcomeScans >= 3
        var score = 50
        if (terminalEvidenceCount == 1) score += 18
        if (stableOutcomeScans >= AdaptiveInteractionPolicy.OUTCOME_STABLE_SCANS) score += 18
        if (stableScreenScans >= 2) score += 8 else score -= 6
        if (positiveActionCount == 0) score += 8
        if (conflict == ScreenEvidenceConflict.NONE) score += 8

        if (loading) score -= 100
        if (terminalEvidenceCount > 1) score -= 18
        if (matureTerminal) score += 20
        if (positiveActionCount > 0) score -= if (matureTerminal) 8 else 22
        if (conflict != ScreenEvidenceConflict.NONE) score -= if (matureTerminal) 8 else 24

        score = score.coerceIn(0, 100)
        return RuntimeAssessment(
            score = score,
            band = band(score),
            safeToAct = score >= MIN_TERMINAL_CONFIDENCE,
            reason = "terminal=$terminalEvidenceCount; outcomeStable=$stableOutcomeScans; screenStable=$stableScreenScans; " +
                "positive=$positiveActionCount; conflict=${conflict.name}"
        )
    }

    private fun band(score: Int): RuntimeConfidenceBand = when {
        score >= 92 -> RuntimeConfidenceBand.VERY_HIGH
        score >= 78 -> RuntimeConfidenceBand.HIGH
        score >= 55 -> RuntimeConfidenceBand.MEDIUM
        else -> RuntimeConfidenceBand.LOW
    }
}
