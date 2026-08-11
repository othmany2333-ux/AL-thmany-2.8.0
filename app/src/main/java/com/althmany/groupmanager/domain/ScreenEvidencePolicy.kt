package com.althmany.groupmanager.domain

/**
 * Resolves contradictory Accessibility evidence before the Android adapter acts on it.
 *
 * WhatsApp can keep stale nodes alive for a short time while replacing an invitation sheet.
 * Instead of trusting whichever label happens to be visited first, the adapter summarizes the
 * visible screen and briefly holds a decision when mutually incompatible signals coexist.
 */
enum class ScreenEvidenceConflict {
    NONE,
    MULTIPLE_TERMINAL_STATES,
    MULTIPLE_POSITIVE_ACTIONS,
    TERMINAL_WITH_POSITIVE_ACTION,
    SURFACE_OVERLAP
}

data class ScreenEvidenceSummary(
    val terminalEvidenceCount: Int,
    val positiveActionCount: Int,
    val conversationSurface: Boolean,
    val homeSurface: Boolean
)

object ScreenEvidencePolicy {
    /** One contradictory frame is treated as transition noise; a repeated frame may be resolved. */
    const val CONFLICT_STABLE_SCANS = 2

    fun conflict(summary: ScreenEvidenceSummary): ScreenEvidenceConflict = when {
        summary.conversationSurface && summary.homeSurface -> ScreenEvidenceConflict.SURFACE_OVERLAP
        summary.terminalEvidenceCount > 1 -> ScreenEvidenceConflict.MULTIPLE_TERMINAL_STATES
        summary.terminalEvidenceCount > 0 && summary.positiveActionCount > 0 ->
            ScreenEvidenceConflict.TERMINAL_WITH_POSITIVE_ACTION
        summary.positiveActionCount > 1 -> ScreenEvidenceConflict.MULTIPLE_POSITIVE_ACTIONS
        else -> ScreenEvidenceConflict.NONE
    }

    fun shouldHold(conflict: ScreenEvidenceConflict, stableScans: Int): Boolean =
        conflict != ScreenEvidenceConflict.NONE && stableScans < CONFLICT_STABLE_SCANS

    /**
     * Once Join/Request has been pressed, ignore unrelated stale positive controls. The only
     * different positive control that may legitimately follow is a confirmation button.
     */
    fun actionAllowedWhilePending(
        pendingAction: AccessibilityJoinAction?,
        visibleAction: AccessibilityJoinAction
    ): Boolean {
        if (pendingAction == null) return true
        if (visibleAction == AccessibilityJoinAction.CONFIRM) return true
        return visibleAction == pendingAction
    }
}
