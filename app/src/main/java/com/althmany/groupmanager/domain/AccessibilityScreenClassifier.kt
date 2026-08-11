package com.althmany.groupmanager.domain

/**
 * Text-only screen classification used by the Android Accessibility adapter.
 *
 * Classification is deliberately order-independent: WhatsApp may expose accessibility nodes in
 * a different traversal order across releases/devices. We collect all evidence first, then apply
 * a fixed priority so a stale success/button label cannot hide a restriction or terminal error.
 */
object AccessibilityScreenClassifier {
    fun classify(labels: Sequence<CharSequence?>): AutomationScreenKind {
        var restricted = false
        var groupFull = false
        var invalidOrExpired = false
        var removedOrBanned = false
        var genericFailure = false
        var alreadyMember = false
        var requestSubmitted = false
        var cancelRequestSeen = false
        var loading = false
        var hasPreview = false
        var hasJoin = false
        var hasRequest = false

        val observed = labels.toList()
        observed.forEach { label ->
            if (AccessibilityJoinMatcher.isRestricted(label)) restricted = true
            when (AccessibilityJoinMatcher.failureType(label)) {
                AccessibilityFailureType.GROUP_FULL -> groupFull = true
                AccessibilityFailureType.INVALID_OR_EXPIRED -> invalidOrExpired = true
                AccessibilityFailureType.REMOVED_OR_BANNED -> removedOrBanned = true
                AccessibilityFailureType.GENERIC -> genericFailure = true
                null -> Unit
            }
            if (AccessibilityJoinMatcher.isAlreadyMember(label)) alreadyMember = true
            if (AccessibilityJoinMatcher.isRequestSubmitted(label)) requestSubmitted = true
            if (AccessibilityJoinMatcher.isCancelRequest(label)) cancelRequestSeen = true
            if (AccessibilityJoinMatcher.isLoading(label)) loading = true

            when (AccessibilityJoinMatcher.actionType(label)) {
                AccessibilityJoinAction.REQUEST -> hasRequest = true
                AccessibilityJoinAction.JOIN -> hasJoin = true
                AccessibilityJoinAction.PREVIEW -> hasPreview = true
                AccessibilityJoinAction.CONFIRM, null -> Unit
            }
        }

        // WhatsApp frequently splits one terminal sentence into several accessibility nodes.
        // Run one aggregate semantic pass across the whole visible screen before deciding.
        when (AccessibilityJoinMatcher.failureTypeAcross(observed.asSequence())) {
            AccessibilityFailureType.GROUP_FULL -> groupFull = true
            AccessibilityFailureType.INVALID_OR_EXPIRED -> invalidOrExpired = true
            AccessibilityFailureType.REMOVED_OR_BANNED -> removedOrBanned = true
            AccessibilityFailureType.GENERIC -> genericFailure = true
            null -> Unit
        }
        if (AccessibilityJoinMatcher.isRequestSubmittedAcross(observed.asSequence())) {
            requestSubmitted = true
        }

        // A visible Cancel request control is terminal evidence only when WhatsApp is not also
        // offering a fresh Request-to-join action. This mirrors the Android adapter and protects
        // the pre-request approval screen from being misclassified.
        if (cancelRequestSeen && !hasRequest) requestSubmitted = true

        return when {
            restricted -> AutomationScreenKind.RESTRICTED
            removedOrBanned -> AutomationScreenKind.REMOVED_OR_BANNED
            invalidOrExpired -> AutomationScreenKind.INVALID_OR_EXPIRED
            groupFull -> AutomationScreenKind.GROUP_FULL
            genericFailure -> AutomationScreenKind.GENERIC_FAILURE
            requestSubmitted -> AutomationScreenKind.REQUEST_SUBMITTED
            alreadyMember -> AutomationScreenKind.ALREADY_MEMBER
            loading -> AutomationScreenKind.LOADING
            hasRequest -> AutomationScreenKind.REQUEST_ACTION
            hasJoin -> AutomationScreenKind.JOIN_ACTION
            hasPreview -> AutomationScreenKind.PREVIEW_ACTION
            else -> AutomationScreenKind.UNKNOWN
        }
    }
}
