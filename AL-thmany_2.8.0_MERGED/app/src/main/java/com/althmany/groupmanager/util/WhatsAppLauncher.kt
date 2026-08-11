package com.althmany.groupmanager.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import com.althmany.groupmanager.model.PreferredTarget

/** A WhatsApp-capable app visible inside the same Android profile as AL-thmany. */
data class InstalledWhatsAppApp(
    val packageName: String,
    val label: String,
    val official: Boolean,
    val canHandleInvite: Boolean = true
)

data class WhatsAppTargetValidation(
    val packageName: String?,
    val profileKey: String,
    val installedInCurrentProfile: Boolean,
    val resolvesInviteInCurrentProfile: Boolean,
    val explicitTargetRequired: Boolean,
    val valid: Boolean,
    val diagnostic: String
)

enum class LaunchDestination {
    PERSONAL,
    BUSINESS,
    CLONED,
    SELECTED,
    DUAL_CHOOSER,
    BROWSER,
    NONE
}

/**
 * Profile-aware WhatsApp launcher.
 *
 * Every explicit package check and start is executed through the current profile's Context. That
 * is the supported Android/Knox boundary: Secure Folder and Work Profile require AL-thmany to be
 * installed in that same profile. The launcher never uses hidden cross-profile APIs.
 */
object WhatsAppLauncher {
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val WHATSAPP_CLONED_PACKAGE = "com.whatsapp2"

    private const val SAMPLE_INVITE = "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv"
    private const val DISCOVERY_CACHE_MS = 5_000L
    @Volatile private var cachedDiscoveryAt = 0L
    @Volatile private var cachedProfileKey: String? = null
    @Volatile private var cachedApps: List<InstalledWhatsAppApp> = emptyList()

    fun launch(
        context: Context,
        url: String,
        preferredTarget: PreferredTarget,
        selectedPackage: String? = null,
        strictProfileTarget: Boolean = true,
        expectedProfileKey: String? = null
    ): LaunchDestination {
        val uri = Uri.parse(url)
        val profile = ProfileEnvironment.current(context)
        if (!expectedProfileKey.isNullOrBlank() && expectedProfileKey != profile.profileKey) {
            return LaunchDestination.NONE
        }
        val resolvedPackage = resolveTargetPackage(context, preferredTarget, selectedPackage)

        // In Work/Secure/secondary profiles AUTO without an explicit package is intentionally
        // rejected. This prevents an Android resolver from silently handing the link to an
        // unintended personal-profile choice.
        if (strictProfileTarget && profile.requiresExplicitAutoTarget &&
            preferredTarget == PreferredTarget.AUTO && selectedPackage.isNullOrBlank()
        ) {
            return LaunchDestination.NONE
        }

        if (!resolvedPackage.isNullOrBlank()) {
            val selectedIntent = inviteIntent(uri, resolvedPackage)
            if (canResolve(context, selectedIntent) && startSafely(context, selectedIntent)) {
                return destinationForPackage(resolvedPackage, selectedPackage != null)
            }
            return LaunchDestination.NONE
        }

        // The chooser path is kept only for the owner profile and only when the user explicitly
        // selected the legacy CLONED target. Isolated profiles should always use an explicit app.
        if (preferredTarget == PreferredTarget.CLONED && !profile.secondaryProfile) {
            return launchDualMessengerResolver(context, uri)
        }

        if (strictProfileTarget) return LaunchDestination.NONE

        val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return if (canResolve(context, browserIntent) && startSafely(context, browserIntent)) {
            LaunchDestination.BROWSER
        } else {
            LaunchDestination.NONE
        }
    }

    /** Resolve exactly one package before a run so the Accessibility service can target-lock it. */
    fun resolveTargetPackage(
        context: Context,
        preferredTarget: PreferredTarget,
        selectedPackage: String? = null
    ): String? {
        selectedPackage?.takeIf { it.isNotBlank() }?.let { packageName ->
            return packageName.takeIf { canHandleInvite(context, it) }
        }
        return when (preferredTarget) {
            PreferredTarget.PERSONAL -> WHATSAPP_PACKAGE.takeIf { canHandleInvite(context, it) }
            PreferredTarget.BUSINESS -> WHATSAPP_BUSINESS_PACKAGE.takeIf { canHandleInvite(context, it) }
            PreferredTarget.CLONED -> WHATSAPP_CLONED_PACKAGE.takeIf { canHandleInvite(context, it) }
            PreferredTarget.AUTO -> {
                val profile = ProfileEnvironment.current(context)
                if (profile.requiresExplicitAutoTarget) null
                else discoverWhatsAppApps(context).firstOrNull { it.canHandleInvite }?.packageName
            }
            PreferredTarget.BROWSER -> null
        }
    }

    fun validateTarget(
        context: Context,
        preferredTarget: PreferredTarget,
        selectedPackage: String? = null
    ): WhatsAppTargetValidation {
        val profile = ProfileEnvironment.current(context)
        val explicitRequired = profile.requiresExplicitAutoTarget &&
            preferredTarget == PreferredTarget.AUTO && selectedPackage.isNullOrBlank()
        val packageName = resolveTargetPackage(context, preferredTarget, selectedPackage)
        val installed = packageName?.let { isInstalled(context, it) } == true
        val resolves = packageName?.let { canHandleInvite(context, it) } == true
        val valid = !explicitRequired && installed && resolves
        val diagnostic = when {
            explicitRequired -> "Explicit WhatsApp selection is required inside this Android profile"
            packageName == null -> "No WhatsApp target could be resolved in the current Android profile"
            !installed -> "Selected WhatsApp package is not installed in the current Android profile"
            !resolves -> "Selected WhatsApp package cannot handle invite links in the current Android profile"
            else -> "Target verified in ${profile.profileKey}: $packageName"
        }
        return WhatsAppTargetValidation(
            packageName = packageName,
            profileKey = profile.profileKey,
            installedInCurrentProfile = installed,
            resolvesInviteInCurrentProfile = resolves,
            explicitTargetRequired = explicitRequired,
            valid = valid,
            diagnostic = diagnostic
        )
    }

    /** Opens only the selected WhatsApp app's launcher activity; no link, message or content. */
    fun testSelectedTarget(context: Context, packageName: String): Boolean {
        if (!isInstalled(context, packageName)) return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return startSafely(context, launchIntent)
    }

    /**
     * Discovers WhatsApp-capable apps visible to this exact profile. Results are cached per profile
     * key so owner-profile discovery can never be reused by a Secure Folder/Work process.
     */
    fun discoverWhatsAppApps(context: Context, forceRefresh: Boolean = false): List<InstalledWhatsAppApp> {
        val now = android.os.SystemClock.elapsedRealtime()
        val profileKey = ProfileEnvironment.current(context).profileKey
        if (!forceRefresh && cachedApps.isNotEmpty() && cachedProfileKey == profileKey &&
            now - cachedDiscoveryAt < DISCOVERY_CACHE_MS
        ) {
            return cachedApps
        }
        val pm = context.packageManager
        val byPackage = linkedMapOf<String, InstalledWhatsAppApp>()

        fun consider(resolveInfo: ResolveInfo) {
            val packageName = resolveInfo.activityInfo?.packageName.orEmpty()
            if (packageName.isBlank()) return
            val label = runCatching { resolveInfo.loadLabel(pm)?.toString().orEmpty() }.getOrDefault("")
            if (!looksLikeWhatsApp(packageName, label)) return
            val handlesInvite = canHandleInvite(context, packageName)
            byPackage[packageName] = InstalledWhatsAppApp(
                packageName = packageName,
                label = label.ifBlank { packageName },
                official = packageName == WHATSAPP_PACKAGE || packageName == WHATSAPP_BUSINESS_PACKAGE,
                canHandleInvite = handlesInvite
            )
        }

        val inviteQuery = Intent(Intent.ACTION_VIEW, Uri.parse(SAMPLE_INVITE)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching { pm.queryIntentActivities(inviteQuery, 0) }.getOrDefault(emptyList()).forEach(::consider)

        val launcherQuery = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        runCatching { pm.queryIntentActivities(launcherQuery, 0) }.getOrDefault(emptyList()).forEach(::consider)

        listOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE, WHATSAPP_CLONED_PACKAGE).forEach { packageName ->
            if (!isInstalled(context, packageName) || byPackage.containsKey(packageName)) return@forEach
            val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return@forEach
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(packageName)
            byPackage[packageName] = InstalledWhatsAppApp(
                packageName = packageName,
                label = label,
                official = packageName == WHATSAPP_PACKAGE || packageName == WHATSAPP_BUSINESS_PACKAGE,
                canHandleInvite = canHandleInvite(context, packageName)
            )
        }

        val result = byPackage.values
            .filter { it.canHandleInvite }
            .sortedWith(
                compareByDescending<InstalledWhatsAppApp> { it.official }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
        cachedApps = result
        cachedProfileKey = profileKey
        cachedDiscoveryAt = now
        return result
    }

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun canHandleInvite(context: Context, packageName: String): Boolean =
        canResolve(context, inviteIntent(Uri.parse(SAMPLE_INVITE), packageName))

    fun selectedPackageAvailable(context: Context, packageName: String?): Boolean =
        packageName.isNullOrBlank() || (isInstalled(context, packageName) && canHandleInvite(context, packageName))

    fun isDiscoveredWhatsAppPackage(context: Context, packageName: String): Boolean =
        discoverWhatsAppApps(context).any { it.packageName == packageName }

    /** Best-effort capability check for Samsung Dual Messenger in the current Android profile. */
    fun supportsDualMessenger(context: Context): Boolean =
        isInstalled(context, WHATSAPP_CLONED_PACKAGE) ||
            (Build.MANUFACTURER.equals("samsung", ignoreCase = true) && isInstalled(context, WHATSAPP_PACKAGE))

    private fun looksLikeWhatsApp(packageName: String, label: String): Boolean {
        val packageLower = packageName.lowercase()
        val labelLower = label.lowercase()
        return packageName == WHATSAPP_PACKAGE ||
            packageName == WHATSAPP_BUSINESS_PACKAGE ||
            packageName == WHATSAPP_CLONED_PACKAGE ||
            "whatsapp" in packageLower ||
            "whatsapp" in labelLower ||
            "واتساب" in labelLower
    }

    private fun destinationForPackage(packageName: String, selected: Boolean): LaunchDestination = when {
        selected -> LaunchDestination.SELECTED
        packageName == WHATSAPP_PACKAGE -> LaunchDestination.PERSONAL
        packageName == WHATSAPP_BUSINESS_PACKAGE -> LaunchDestination.BUSINESS
        packageName == WHATSAPP_CLONED_PACKAGE -> LaunchDestination.CLONED
        else -> LaunchDestination.SELECTED
    }

    private fun inviteIntent(uri: Uri, packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    private fun launchDualMessengerResolver(context: Context, uri: Uri): LaunchDestination {
        if (!supportsDualMessenger(context)) return LaunchDestination.NONE

        val baseIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (!canResolve(context, baseIntent)) return LaunchDestination.NONE

        val chooser = Intent.createChooser(baseIntent, "Samsung Dual Messenger WhatsApp").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (startSafely(context, chooser)) LaunchDestination.DUAL_CHOOSER else LaunchDestination.NONE
    }

    private fun canResolve(context: Context, intent: Intent): Boolean =
        runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)

    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
