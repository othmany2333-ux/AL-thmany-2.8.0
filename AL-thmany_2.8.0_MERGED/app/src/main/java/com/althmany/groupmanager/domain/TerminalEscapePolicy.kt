package com.althmany.groupmanager.domain

/**
 * Priority policy for terminal WhatsApp invitation outcomes.
 *
 * Specific, irreversible outcomes such as a reset invite, full group, removal, an already
 * submitted request, or already-membership are safe to route immediately because there is no
 * useful Join/Request action left for the current link. Generic denials stay on the verified
 * path so one vague/stale accessibility label cannot skip a valid invitation. Restrictions are
 * never escaped: they stop the run at the coordinator level.
 */
enum class TerminalEscapeMode {
    NONE,
    VERIFIED,
    IMMEDIATE
}

data class TerminalEscapeDecision(
    val mode: TerminalEscapeMode,
    val reason: String
) {
    val immediate: Boolean get() = mode == TerminalEscapeMode.IMMEDIATE
    val bypassInterLinkDelay: Boolean get() = immediate
}

object TerminalEscapePolicy {
    fun assess(
        failure: AccessibilityFailureType?,
        requestSubmitted: Boolean,
        alreadyMember: Boolean,
        restricted: Boolean
    ): TerminalEscapeDecision {
        if (restricted) return TerminalEscapeDecision(
            TerminalEscapeMode.NONE,
            "restriction screens are global-stop conditions"
        )

        return when {
            failure == AccessibilityFailureType.INVALID_OR_EXPIRED -> TerminalEscapeDecision(
                TerminalEscapeMode.IMMEDIATE,
                "invite is reset, expired, invalid, revoked, or unavailable"
            )
            failure == AccessibilityFailureType.REMOVED_OR_BANNED -> TerminalEscapeDecision(
                TerminalEscapeMode.IMMEDIATE,
                "account was removed or banned from the target"
            )
            failure == AccessibilityFailureType.GROUP_FULL -> TerminalEscapeDecision(
                TerminalEscapeMode.IMMEDIATE,
                "group/community is full or at its member limit"
            )
            requestSubmitted -> TerminalEscapeDecision(
                TerminalEscapeMode.IMMEDIATE,
                "join request is already submitted/pending"
            )
            alreadyMember -> TerminalEscapeDecision(
                TerminalEscapeMode.IMMEDIATE,
                "account is already a member"
            )
            failure == AccessibilityFailureType.GENERIC -> TerminalEscapeDecision(
                TerminalEscapeMode.VERIFIED,
                "generic denial still requires the normal confidence path"
            )
            else -> TerminalEscapeDecision(TerminalEscapeMode.NONE, "no terminal evidence")
        }
    }
}
