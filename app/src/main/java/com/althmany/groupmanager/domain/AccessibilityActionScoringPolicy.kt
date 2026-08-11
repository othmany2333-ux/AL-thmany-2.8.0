package com.althmany.groupmanager.domain

/** Explainable ranking for positive WhatsApp controls when multiple nodes describe the same button. */
object AccessibilityActionScoringPolicy {
    fun score(
        action: AccessibilityJoinAction,
        requestApprovalNoticeSeen: Boolean,
        clickable: Boolean,
        clickableParent: Boolean,
        buttonClass: Boolean,
        hasViewId: Boolean,
        textLabel: Boolean,
        inviteContext: Boolean,
        adequateBounds: Boolean
    ): Int {
        var score = priority(action) * 100
        if (requestApprovalNoticeSeen) {
            if (action == AccessibilityJoinAction.REQUEST) score += 90
            if (action == AccessibilityJoinAction.JOIN) score -= 35
        }
        if (clickable) score += 35
        if (clickableParent) score += 18
        if (buttonClass) score += 20
        if (hasViewId) score += 12
        if (textLabel) score += 15
        if (inviteContext) score += 10
        if (adequateBounds) score += 10 else score -= 30
        return score
    }

    private fun priority(action: AccessibilityJoinAction): Int = when (action) {
        AccessibilityJoinAction.CONFIRM -> 4
        AccessibilityJoinAction.REQUEST -> 3
        AccessibilityJoinAction.JOIN -> 2
        AccessibilityJoinAction.PREVIEW -> 1
    }
}
