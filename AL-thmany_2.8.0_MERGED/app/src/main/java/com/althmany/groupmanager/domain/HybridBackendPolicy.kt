package com.althmany.groupmanager.domain

import com.althmany.groupmanager.model.AutomationBackend

/** Deterministic backend arbitration. A dead Shizuku runtime must never disable a live Accessibility service. */
object HybridBackendPolicy {
    fun chooseForStart(
        requested: AutomationBackend,
        accessibilityEnabled: Boolean,
        shizukuReady: Boolean
    ): AutomationBackend? = when (requested) {
        AutomationBackend.ACCESSIBILITY -> AutomationBackend.ACCESSIBILITY
        AutomationBackend.SHIZUKU -> when {
            shizukuReady -> AutomationBackend.SHIZUKU
            accessibilityEnabled -> AutomationBackend.ACCESSIBILITY
            else -> null
        }
        AutomationBackend.AUTO -> when {
            accessibilityEnabled -> AutomationBackend.ACCESSIBILITY
            shizukuReady -> AutomationBackend.SHIZUKU
            else -> AutomationBackend.ACCESSIBILITY
        }
    }

    fun accessibilityMayTakeOver(runtime: AutomationBackend, shizukuReady: Boolean): Boolean =
        runtime == AutomationBackend.ACCESSIBILITY ||
            (runtime == AutomationBackend.SHIZUKU && !shizukuReady)
}
