package com.althmany.groupmanager.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager

enum class ProfileKind {
    OWNER,
    MANAGED_WORK,
    SAMSUNG_ISOLATED,
    SECONDARY
}

data class ProfileEnvironmentState(
    val profileHandle: String,
    val profileKey: String,
    val userSerial: Long,
    val managedProfile: Boolean,
    val secondaryProfile: Boolean,
    val samsungDevice: Boolean,
    val kind: ProfileKind
) {
    /**
     * AUTO is deliberately guarded in isolated profiles. An explicit package keeps Android from
     * silently resolving an invitation in another user-facing WhatsApp choice.
     */
    val requiresExplicitAutoTarget: Boolean
        get() = secondaryProfile

    val isLikelySecureFolder: Boolean
        get() = kind == ProfileKind.SAMSUNG_ISOLATED
}

/**
 * Describes only the Android profile in which this AL-thmany process is running.
 *
 * Android/Knox isolation is intentionally respected: AL-thmany never attempts cross-profile
 * control. To automate WhatsApp in Secure Folder or a Work Profile, install AL-thmany and enable
 * its Accessibility service inside that same profile. PackageManager and explicit Intents then
 * resolve against the profile-local WhatsApp installation.
 */
object ProfileEnvironment {
    fun current(context: Context): ProfileEnvironmentState {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val handle = Process.myUserHandle()
        val handleText = handle.toString()
        val serial = runCatching { userManager?.getSerialNumberForUser(handle) ?: -1L }
            .getOrDefault(-1L)
        val managed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { userManager?.isManagedProfile == true }.getOrDefault(false)
        } else false
        // minSdk is 26, so isSystemUser is available. Keep the UserHandle text only as a
        // defensive fallback for unusual vendor implementations where UserManager is missing.
        val systemUser = runCatching { userManager?.isSystemUser }.getOrNull()
        val secondary = managed || when (systemUser) {
            true -> false
            false -> true
            null -> handleText != "UserHandle{0}"
        }
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val kind = when {
            managed -> ProfileKind.MANAGED_WORK
            samsung && secondary -> ProfileKind.SAMSUNG_ISOLATED
            secondary -> ProfileKind.SECONDARY
            else -> ProfileKind.OWNER
        }
        val stableIdentity = if (serial >= 0L) serial.toString() else handleText
        return ProfileEnvironmentState(
            profileHandle = handleText,
            profileKey = "${kind.name}:$stableIdentity",
            userSerial = serial,
            managedProfile = managed,
            secondaryProfile = secondary,
            samsungDevice = samsung,
            kind = kind
        )
    }
}
