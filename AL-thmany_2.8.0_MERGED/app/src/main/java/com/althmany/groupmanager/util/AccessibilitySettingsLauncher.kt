package com.althmany.groupmanager.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.althmany.groupmanager.accessibility.QuickJoinAccessibilityService

/**
 * Safe settings routes for profile troubleshooting. Android/Samsung may redirect a managed-profile
 * caller to the parent Settings app; these helpers do not try to bypass that isolation. The three
 * explicit routes let the user distinguish service-detail routing, the general Accessibility list,
 * and the app-info page that is normally scoped to the current installed app instance.
 */
object AccessibilitySettingsLauncher {
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    fun openServiceDetails(context: Context): Boolean {
        val component = ComponentName(context, QuickJoinAccessibilityService::class.java)
        val detailIntent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
            // Android's own AccessibilityManagerService supplies the flattened component string.
            // Passing the Parcelable ComponentName can resolve Settings but lose the target page
            // on Samsung/AOSP variants, leaving the user in the generic list instead.
            putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
        }
        return launchIfResolvable(context, detailIntent)
    }

    fun openAccessibilityList(context: Context): Boolean =
        launchIfResolvable(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openCurrentProfileAppInfo(context: Context): Boolean = launchIfResolvable(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    )

    /**
     * Backward-compatible best effort: exact service details, then the documented Accessibility
     * list, then the current app-info page.
     */
    fun open(context: Context): Boolean =
        openServiceDetails(context) || openAccessibilityList(context) || openCurrentProfileAppInfo(context)

    private fun launchIfResolvable(context: Context, intent: Intent): Boolean = runCatching {
        if (context.packageManager.resolveActivity(intent, 0) == null) return@runCatching false
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
