package com.althmany.groupmanager.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.althmany.groupmanager.admin.WorkProfileAdminReceiver

enum class WorkAccessibilityPolicyState {
    NOT_WORK_PROFILE,
    EXTERNAL_PROFILE_OWNER,
    SELF_PROFILE_OWNER_ALL_ALLOWED,
    SELF_PROFILE_OWNER_APP_ALLOWED,
    SELF_PROFILE_OWNER_APP_BLOCKED,
    SELF_DEVICE_OWNER,
    UNKNOWN
}

data class WorkProfileControllerSnapshot(
    val profileKey: String,
    val managedProfile: Boolean,
    val adminActive: Boolean,
    val selfProfileOwner: Boolean,
    val selfDeviceOwner: Boolean,
    val policyManagerPackage: String?,
    val permittedAccessibilityPackages: List<String>?,
    val accessibilityPolicyState: WorkAccessibilityPolicyState
) {
    val canManageAccessibilityPolicy: Boolean get() = selfProfileOwner || selfDeviceOwner
}

data class WorkPolicyApplyResult(
    val changed: Boolean,
    val success: Boolean,
    val messageCode: String
)

/**
 * Profile-owner assistance for Work Profile only. This does not enable Accessibility itself.
 * Android still requires the user to turn the service on. If another DPC owns an existing work
 * profile, AL-thmany reports that fact and does not attempt to replace or bypass it.
 */
object WorkProfileController {
    fun snapshot(context: Context): WorkProfileControllerSnapshot {
        val profile = ProfileEnvironment.current(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(context, WorkProfileAdminReceiver::class.java)
        val active = runCatching { dpm?.isAdminActive(admin) == true }.getOrDefault(false)
        val profileOwner = runCatching { dpm?.isProfileOwnerApp(context.packageName) == true }.getOrDefault(false)
        val deviceOwner = runCatching { dpm?.isDeviceOwnerApp(context.packageName) == true }.getOrDefault(false)
        val managerPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { dpm?.devicePolicyManagementRoleHolderPackage }.getOrNull()
        } else null

        var permitted: List<String>? = null
        val state = when {
            !profile.managedProfile -> WorkAccessibilityPolicyState.NOT_WORK_PROFILE
            profileOwner || deviceOwner -> {
                val read = runCatching { dpm?.getPermittedAccessibilityServices(admin) }
                if (read.isFailure) {
                    WorkAccessibilityPolicyState.UNKNOWN
                } else {
                    permitted = read.getOrNull()
                    when {
                        deviceOwner && !profileOwner -> WorkAccessibilityPolicyState.SELF_DEVICE_OWNER
                        permitted == null -> WorkAccessibilityPolicyState.SELF_PROFILE_OWNER_ALL_ALLOWED
                        context.packageName in permitted.orEmpty() -> WorkAccessibilityPolicyState.SELF_PROFILE_OWNER_APP_ALLOWED
                        else -> WorkAccessibilityPolicyState.SELF_PROFILE_OWNER_APP_BLOCKED
                    }
                }
            }
            else -> WorkAccessibilityPolicyState.EXTERNAL_PROFILE_OWNER
        }

        return WorkProfileControllerSnapshot(
            profileKey = profile.profileKey,
            managedProfile = profile.managedProfile,
            adminActive = active,
            selfProfileOwner = profileOwner,
            selfDeviceOwner = deviceOwner,
            policyManagerPackage = managerPackage,
            permittedAccessibilityPackages = permitted,
            accessibilityPolicyState = state
        )
    }

    fun ensureSelfAccessibilityPermitted(context: Context): WorkPolicyApplyResult {
        val profile = ProfileEnvironment.current(context)
        if (!profile.managedProfile) {
            return WorkPolicyApplyResult(false, false, "NOT_WORK_PROFILE")
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return WorkPolicyApplyResult(false, false, "DEVICE_POLICY_SERVICE_UNAVAILABLE")
        if (!dpm.isProfileOwnerApp(context.packageName) && !dpm.isDeviceOwnerApp(context.packageName)) {
            return WorkPolicyApplyResult(false, false, "EXTERNAL_PROFILE_OWNER")
        }
        val admin = ComponentName(context, WorkProfileAdminReceiver::class.java)
        val permitted = runCatching { dpm.getPermittedAccessibilityServices(admin) }
            .getOrElse { return WorkPolicyApplyResult(false, false, "POLICY_READ_DENIED") }
        if (permitted == null) {
            return WorkPolicyApplyResult(false, true, "ALL_ACCESSIBILITY_ALREADY_ALLOWED")
        }
        if (context.packageName in permitted) {
            return WorkPolicyApplyResult(false, true, "AL_THMANY_ALREADY_ALLOWED")
        }
        val updated = permitted.toMutableSet().apply { add(context.packageName) }.toMutableList()
        val applied = runCatching { dpm.setPermittedAccessibilityServices(admin, updated) }.getOrDefault(false)
        return if (applied) {
            WorkPolicyApplyResult(true, true, "AL_THMANY_ADDED_TO_ACCESSIBILITY_ALLOWLIST")
        } else {
            WorkPolicyApplyResult(false, false, "POLICY_UPDATE_REJECTED")
        }
    }
}
