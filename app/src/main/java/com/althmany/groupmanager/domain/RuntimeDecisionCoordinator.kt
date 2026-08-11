package com.althmany.groupmanager.domain

/** High-level priority coordinator. Android-specific traversal/clicking remains outside this class. */
enum class RuntimeDirective {
    STOP_RESTRICTED,
    WAIT_LOADING,
    WAIT_CONFLICT,
    HANDLE_TERMINAL,
    HANDLE_ACTION,
    HANDLE_UNKNOWN
}

data class RuntimeObservedScreen(
    val restricted: Boolean,
    val loading: Boolean,
    val conflict: ScreenEvidenceConflict,
    val hasTerminalEvidence: Boolean,
    val hasAction: Boolean,
    val immediateTerminal: Boolean = false
)

object RuntimeDecisionCoordinator {
    fun decide(screen: RuntimeObservedScreen, conflictShouldHold: Boolean): RuntimeDirective = when {
        screen.restricted -> RuntimeDirective.STOP_RESTRICTED
        // Specific terminal results outrank stale Loading/action nodes. This is the Instant
        // Terminal Router fast path; restrictions still always win above it.
        screen.immediateTerminal -> RuntimeDirective.HANDLE_TERMINAL
        screen.loading -> RuntimeDirective.WAIT_LOADING
        screen.conflict != ScreenEvidenceConflict.NONE && conflictShouldHold -> RuntimeDirective.WAIT_CONFLICT
        screen.hasTerminalEvidence -> RuntimeDirective.HANDLE_TERMINAL
        screen.hasAction -> RuntimeDirective.HANDLE_ACTION
        else -> RuntimeDirective.HANDLE_UNKNOWN
    }
}
