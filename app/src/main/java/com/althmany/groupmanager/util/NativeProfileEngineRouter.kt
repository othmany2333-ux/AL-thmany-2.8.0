package com.althmany.groupmanager.util

import android.content.Context
import com.althmany.groupmanager.accessibility.AccessibilityStatus
import com.althmany.groupmanager.domain.NativeEngineDecision
import com.althmany.groupmanager.domain.NativeProfileClass
import com.althmany.groupmanager.domain.NativeProfileEnginePolicy
import com.althmany.groupmanager.model.AutomationBackend
import com.althmany.groupmanager.shizuku.ShizukuBridge

data class NativeProfileEngineSnapshot(
    val profileKey: String,
    val profileClass: NativeProfileClass,
    val accessibilityLocalReady: Boolean,
    val shizukuReady: Boolean,
    val workController: WorkProfileControllerSnapshot,
    val decision: NativeEngineDecision
)

object NativeProfileEngineRouter {
    fun inspect(context: Context, requested: AutomationBackend): NativeProfileEngineSnapshot {
        val profile = ProfileEnvironment.current(context)
        val work = WorkProfileController.snapshot(context)
        val accessibilityReady = AccessibilityStatus.isQuickJoinServiceConnectedLocally(context)
        val shizukuReady = runCatching { ShizukuBridge.status().ready }.getOrDefault(false)
        val profileClass = when (profile.kind) {
            ProfileKind.OWNER -> NativeProfileClass.OWNER
            ProfileKind.MANAGED_WORK -> NativeProfileClass.MANAGED_WORK
            ProfileKind.SAMSUNG_ISOLATED -> NativeProfileClass.SAMSUNG_ISOLATED
            ProfileKind.SECONDARY -> NativeProfileClass.SECONDARY
        }
        val workBlocksSelf = work.accessibilityPolicyState == WorkAccessibilityPolicyState.SELF_PROFILE_OWNER_APP_BLOCKED
        val decision = NativeProfileEnginePolicy.choose(
            requested = requested,
            profileClass = profileClass,
            accessibilityLocalReady = accessibilityReady,
            shizukuReady = shizukuReady,
            selfCanManageWorkPolicy = work.canManageAccessibilityPolicy,
            workPolicyBlocksSelf = workBlocksSelf
        )
        return NativeProfileEngineSnapshot(
            profileKey = profile.profileKey,
            profileClass = profileClass,
            accessibilityLocalReady = accessibilityReady,
            shizukuReady = shizukuReady,
            workController = work,
            decision = decision
        )
    }
}
