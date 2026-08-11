package com.althmany.groupmanager.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.althmany.groupmanager.domain.ProfileControlPolicy
import com.althmany.groupmanager.util.ProfileAccessibilityRuntime

data class AccessibilityReadiness(
    val systemEnabled: Boolean,
    val localServiceConnected: Boolean,
    val profileKey: String,
    val heartbeatAgeMs: Long?
)

object AccessibilityStatus {
    fun readiness(context: Context): AccessibilityReadiness {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(context, QuickJoinAccessibilityService::class.java)
        val managerEnabled = runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info -> serviceComponent(info) == expected }
        }.getOrDefault(false)
        // Some Samsung builds show the exact service as Running but temporarily return an empty
        // enabled-service list to the freshly updated Activity process. The secure value is still
        // scoped to this Android user/profile and is used only as configured-state evidence.
        val secureSettingEnabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == expected }
        }.getOrDefault(false)
        // A live callback from the service process is stronger evidence than either a stale secure
        // setting or a briefly lagging AccessibilityManager list. The service and activities share
        // this process (no android:process override), so this flag cannot come from another Android
        // user/profile or from a previously installed AL-thmany package.
        val processConnected = QuickJoinAccessibilityService.isRuntimeConnected()
        val runtime = ProfileAccessibilityRuntime.snapshot(context)
        val localConnected = ProfileControlPolicy.isLocalConnectionAlive(
            processCallbackConnected = processConnected,
            profileHeartbeatConnected = runtime.localServiceConnected
        )
        val systemEnabled = managerEnabled || secureSettingEnabled || localConnected
        return AccessibilityReadiness(
            systemEnabled = systemEnabled,
            localServiceConnected = localConnected,
            profileKey = runtime.profileKey,
            heartbeatAgeMs = runtime.heartbeatAgeMs
        )
    }

    /** Android reports this exact service enabled, or its live connection callback has arrived. */
    fun isQuickJoinServiceEnabled(context: Context): Boolean = readiness(context).systemEnabled

    /**
     * Stronger profile-safe readiness check. This requires the live service instance in the same
     * app process and Android profile as this activity; the heartbeat remains diagnostic metadata.
     */
    fun isQuickJoinServiceConnectedLocally(context: Context): Boolean =
        readiness(context).let { it.systemEnabled && it.localServiceConnected }

    private fun serviceComponent(info: AccessibilityServiceInfo): ComponentName? {
        val serviceInfo = info.resolveInfo?.serviceInfo ?: return null
        val packageName = serviceInfo.packageName?.takeIf { it.isNotBlank() } ?: return null
        val rawName = serviceInfo.name?.takeIf { it.isNotBlank() } ?: return null
        val className = when {
            rawName.startsWith('.') -> packageName + rawName
            '.' !in rawName -> "$packageName.$rawName"
            else -> rawName
        }
        return ComponentName(packageName, className)
    }
}
