package com.althmany.groupmanager.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.althmany.groupmanager.accessibility.AccessibilityStatus
import com.althmany.groupmanager.accessibility.QuickJoinAccessibilityService

/**
 * Best-effort, read-only inspection of Accessibility availability inside the current Android
 * profile. This never changes Device Policy and never attempts to bypass a profile owner / Knox.
 *
 * Android intentionally restricts the permitted-accessibility allowlist API to a device/profile
 * owner. AL-thmany is a normal app, so when it is not the owner we report that the exact allowlist
 * cannot be read instead of guessing that a policy is blocking the service.
 */
enum class AccessibilityPolicyVisibility {
    NOT_MANAGED,
    ALL_ALLOWED,
    ALLOWLIST_INCLUDES_APP,
    BLOCKED_BY_POLICY,
    QUERY_NOT_AVAILABLE_TO_STANDARD_APP
}

enum class ProfileAccessibilityActivationState {
    READY,
    SERVICE_COMPONENT_NOT_VISIBLE,
    PARENT_ACCESSIBILITY_TOGGLE_REQUIRED,
    ENABLED_SETTING_PRESENT_BUT_MANAGER_DISABLED,
    LOCAL_SERVICE_NOT_CONNECTED,
    WAITING_FOR_LOCAL_BIND
}

data class ProfileAccessibilityPolicySnapshot(
    val profileKey: String,
    val managedProfile: Boolean,
    val serviceComponentVisible: Boolean,
    val secureEnabledSettingContainsService: Boolean?,
    val managerReportsEnabled: Boolean,
    val localServiceConnected: Boolean,
    val policyVisibility: AccessibilityPolicyVisibility,
    val policyManagerPackage: String?,
    val activationState: ProfileAccessibilityActivationState
)

object ProfileAccessibilityPolicyInspector {
    fun inspect(context: Context): ProfileAccessibilityPolicySnapshot {
        val profile = ProfileEnvironment.current(context)
        val component = ComponentName(context, QuickJoinAccessibilityService::class.java)
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val readiness = AccessibilityStatus.readiness(context)

        val managerEnabled = runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info: AccessibilityServiceInfo ->
                    val serviceInfo = info.resolveInfo.serviceInfo
                    serviceInfo.packageName == context.packageName &&
                        serviceInfo.name == QuickJoinAccessibilityService::class.java.name
                }
        }.getOrDefault(false)

        val serviceVisible = runCatching {
            manager.installedAccessibilityServiceList.any { info: AccessibilityServiceInfo ->
                val serviceInfo = info.resolveInfo.serviceInfo
                serviceInfo.packageName == context.packageName &&
                    serviceInfo.name == QuickJoinAccessibilityService::class.java.name
            }
        }.getOrDefault(false)

        val rawSettingContains = runCatching {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return@runCatching false
            enabled.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it == component }
        }.getOrNull()

        val policyProbe = probePolicy(context, profile.managedProfile)

        val activationState = when {
            readiness.systemEnabled && readiness.localServiceConnected ->
                ProfileAccessibilityActivationState.READY
            !serviceVisible ->
                ProfileAccessibilityActivationState.SERVICE_COMPONENT_NOT_VISIBLE
            rawSettingContains == true && !managerEnabled ->
                ProfileAccessibilityActivationState.ENABLED_SETTING_PRESENT_BUT_MANAGER_DISABLED
            managerEnabled && !readiness.localServiceConnected ->
                ProfileAccessibilityActivationState.LOCAL_SERVICE_NOT_CONNECTED
            profile.managedProfile && !managerEnabled ->
                ProfileAccessibilityActivationState.PARENT_ACCESSIBILITY_TOGGLE_REQUIRED
            else -> ProfileAccessibilityActivationState.WAITING_FOR_LOCAL_BIND
        }

        return ProfileAccessibilityPolicySnapshot(
            profileKey = profile.profileKey,
            managedProfile = profile.managedProfile,
            serviceComponentVisible = serviceVisible,
            secureEnabledSettingContainsService = rawSettingContains,
            managerReportsEnabled = managerEnabled,
            localServiceConnected = readiness.localServiceConnected,
            policyVisibility = policyProbe.first,
            policyManagerPackage = policyProbe.second,
            activationState = activationState
        )
    }

    private fun probePolicy(
        context: Context,
        managedProfile: Boolean
    ): Pair<AccessibilityPolicyVisibility, String?> {
        if (!managedProfile) return AccessibilityPolicyVisibility.NOT_MANAGED to null

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return AccessibilityPolicyVisibility.QUERY_NOT_AVAILABLE_TO_STANDARD_APP to null
        val managerPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { dpm.devicePolicyManagementRoleHolderPackage }.getOrNull()
        } else null

        val selfIsOwner = runCatching {
            dpm.isProfileOwnerApp(context.packageName) || dpm.isDeviceOwnerApp(context.packageName)
        }.getOrDefault(false)
        if (!selfIsOwner) {
            return AccessibilityPolicyVisibility.QUERY_NOT_AVAILABLE_TO_STANDARD_APP to managerPackage
        }

        val ownAdmin = runCatching {
            dpm.activeAdmins?.firstOrNull { it.packageName == context.packageName }
        }.getOrNull()
            ?: return AccessibilityPolicyVisibility.QUERY_NOT_AVAILABLE_TO_STANDARD_APP to managerPackage

        val permitted = runCatching { dpm.getPermittedAccessibilityServices(ownAdmin) }
            .getOrElse {
                return AccessibilityPolicyVisibility.QUERY_NOT_AVAILABLE_TO_STANDARD_APP to managerPackage
            }
        val visibility = when {
            permitted == null -> AccessibilityPolicyVisibility.ALL_ALLOWED
            context.packageName in permitted -> AccessibilityPolicyVisibility.ALLOWLIST_INCLUDES_APP
            else -> AccessibilityPolicyVisibility.BLOCKED_BY_POLICY
        }
        return visibility to managerPackage
    }
}
