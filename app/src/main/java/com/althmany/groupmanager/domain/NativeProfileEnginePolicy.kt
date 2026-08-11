package com.althmany.groupmanager.domain

import com.althmany.groupmanager.model.AutomationBackend

/**
 * Pure routing policy for the native multi-profile engine.
 *
 * It never assumes cross-profile access. It chooses only a backend that can run from the Android
 * profile where AL-thmany is currently executing, or returns an explicit setup action.
 */
enum class NativeProfileClass {
    OWNER,
    MANAGED_WORK,
    SAMSUNG_ISOLATED,
    SECONDARY
}

enum class NativeEngineSetupAction {
    NONE,
    ENABLE_LOCAL_ACCESSIBILITY,
    APPLY_WORK_ACCESSIBILITY_POLICY,
    START_OR_AUTHORIZE_SHIZUKU,
    BLOCKED_BY_PROFILE_POLICY
}

data class NativeEngineDecision(
    val backend: AutomationBackend?,
    val setupAction: NativeEngineSetupAction,
    val reasonCode: String
) {
    val runnable: Boolean get() = backend != null && setupAction == NativeEngineSetupAction.NONE
}

object NativeProfileEnginePolicy {
    fun choose(
        requested: AutomationBackend,
        profileClass: NativeProfileClass,
        accessibilityLocalReady: Boolean,
        shizukuReady: Boolean,
        selfCanManageWorkPolicy: Boolean,
        workPolicyBlocksSelf: Boolean
    ): NativeEngineDecision {
        if (requested == AutomationBackend.ACCESSIBILITY) {
            if (accessibilityLocalReady) {
                return NativeEngineDecision(AutomationBackend.ACCESSIBILITY, NativeEngineSetupAction.NONE, "ACCESSIBILITY_LOCAL_READY")
            }
            if (profileClass == NativeProfileClass.MANAGED_WORK && workPolicyBlocksSelf && selfCanManageWorkPolicy) {
                return NativeEngineDecision(null, NativeEngineSetupAction.APPLY_WORK_ACCESSIBILITY_POLICY, "WORK_POLICY_FIX_REQUIRED")
            }
            return NativeEngineDecision(AutomationBackend.ACCESSIBILITY, NativeEngineSetupAction.ENABLE_LOCAL_ACCESSIBILITY, "LOCAL_ACCESSIBILITY_ENABLE_REQUIRED")
        }

        if (requested == AutomationBackend.SHIZUKU) {
            if (shizukuReady) {
                return NativeEngineDecision(AutomationBackend.SHIZUKU, NativeEngineSetupAction.NONE, "SHIZUKU_TRANSPORT_READY")
            }
            if (accessibilityLocalReady) {
                return NativeEngineDecision(AutomationBackend.ACCESSIBILITY, NativeEngineSetupAction.NONE, "SHIZUKU_NOT_READY_ACCESSIBILITY_FALLBACK")
            }
            return NativeEngineDecision(null, NativeEngineSetupAction.START_OR_AUTHORIZE_SHIZUKU, "SHIZUKU_SETUP_REQUIRED")
        }

        // AUTO: prefer a proven profile-local Accessibility connection. In isolated Samsung
        // profiles, prefer Shizuku next because global Accessibility settings commonly resolve to
        // another profile. Shizuku still performs its own package/user/UI preflight before acting.
        if (accessibilityLocalReady) {
            return NativeEngineDecision(AutomationBackend.ACCESSIBILITY, NativeEngineSetupAction.NONE, "AUTO_ACCESSIBILITY_LOCAL")
        }
        if (shizukuReady) {
            return NativeEngineDecision(AutomationBackend.SHIZUKU, NativeEngineSetupAction.NONE, "AUTO_SHIZUKU_NATIVE_FALLBACK")
        }
        if (profileClass == NativeProfileClass.MANAGED_WORK && workPolicyBlocksSelf && selfCanManageWorkPolicy) {
            return NativeEngineDecision(null, NativeEngineSetupAction.APPLY_WORK_ACCESSIBILITY_POLICY, "AUTO_WORK_POLICY_FIX_REQUIRED")
        }
        if (profileClass == NativeProfileClass.SAMSUNG_ISOLATED) {
            return NativeEngineDecision(null, NativeEngineSetupAction.START_OR_AUTHORIZE_SHIZUKU, "AUTO_SECURE_FOLDER_NEEDS_LOCAL_BACKEND")
        }
        if (profileClass == NativeProfileClass.MANAGED_WORK && selfCanManageWorkPolicy) {
            return NativeEngineDecision(null, NativeEngineSetupAction.APPLY_WORK_ACCESSIBILITY_POLICY, "AUTO_WORK_CONTROLLER_CAN_PREPARE_ACCESSIBILITY")
        }
        return NativeEngineDecision(AutomationBackend.ACCESSIBILITY, NativeEngineSetupAction.ENABLE_LOCAL_ACCESSIBILITY, "AUTO_LOCAL_ACCESSIBILITY_SETUP")
    }
}
