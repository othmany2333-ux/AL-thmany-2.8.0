package com.althmany.groupmanager.util

import android.content.Context
import com.althmany.groupmanager.domain.ProfileControlCapability
import com.althmany.groupmanager.domain.ProfileControlPolicy

/**
 * Profile-local Accessibility heartbeat and UI-control diagnostics.
 *
 * SharedPreferences are isolated per Android user/profile, so a heartbeat written by the personal
 * Accessibility service cannot satisfy a Work Profile or Secure Folder copy of AL-thmany. This is
 * intentional and is the key guard against false "Accessibility enabled" positives across profiles.
 * Only package names and engine metadata are stored; no invite URLs, message text, contacts or
 * WhatsApp labels are recorded.
 */
data class ProfileAccessibilitySnapshot(
    val profileKey: String,
    val localServiceConnected: Boolean,
    val heartbeatAgeMs: Long?,
    val lastEventPackage: String?,
    val lastEventAgeMs: Long?,
    val rootAvailable: Boolean,
    val rootPackage: String?,
    val rootAgeMs: Long?
) {
    fun capability(systemEnabled: Boolean, targetPackage: String? = null): ProfileControlCapability {
        val expected = targetPackage?.takeIf { it.isNotBlank() }
        return ProfileControlPolicy.classify(
            systemEnabled = systemEnabled,
            localServiceConnected = localServiceConnected,
            targetEventAgeMs = lastEventAgeMs,
            rootAgeMs = rootAgeMs,
            rootAvailable = rootAvailable,
            targetEventMatches = expected == null || expected == lastEventPackage,
            rootPackageMatches = expected == null || expected == rootPackage
        )
    }
}

object ProfileAccessibilityRuntime {
    private const val PREFS = "profile_accessibility_runtime_v232"
    private const val KEY_PROFILE = "profile_key"
    private const val KEY_CONNECTED = "service_connected"
    private const val KEY_HEARTBEAT = "heartbeat_wall"
    private const val KEY_EVENT_PACKAGE = "event_package"
    private const val KEY_EVENT_WALL = "event_wall"
    private const val KEY_ROOT_AVAILABLE = "root_available"
    private const val KEY_ROOT_PACKAGE = "root_package"
    private const val KEY_ROOT_WALL = "root_wall"
    private const val HEARTBEAT_WRITE_MIN_MS = 1_500L

    @Volatile private var lastHeartbeatWriteWall = 0L

    fun recordServiceConnected(context: Context) {
        val now = System.currentTimeMillis()
        val profile = ProfileEnvironment.current(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILE, profile.profileKey)
            .putBoolean(KEY_CONNECTED, true)
            .putLong(KEY_HEARTBEAT, now)
            .apply()
        lastHeartbeatWriteWall = now
    }

    fun heartbeat(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHeartbeatWriteWall < HEARTBEAT_WRITE_MIN_MS) return
        val profile = ProfileEnvironment.current(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILE, profile.profileKey)
            .putBoolean(KEY_CONNECTED, true)
            .putLong(KEY_HEARTBEAT, now)
            .apply()
        lastHeartbeatWriteWall = now
    }

    fun recordEvent(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        heartbeat(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EVENT_PACKAGE, packageName)
            .putLong(KEY_EVENT_WALL, System.currentTimeMillis())
            .apply()
    }

    fun recordRoot(context: Context, available: Boolean, packageName: String?) {
        heartbeat(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ROOT_AVAILABLE, available)
            .putLong(KEY_ROOT_WALL, System.currentTimeMillis())
            .apply {
                if (packageName.isNullOrBlank()) remove(KEY_ROOT_PACKAGE)
                else putString(KEY_ROOT_PACKAGE, packageName)
            }
            .apply()
    }

    fun markDisconnected(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONNECTED, false)
            .apply()
    }

    fun snapshot(context: Context): ProfileAccessibilitySnapshot {
        val now = System.currentTimeMillis()
        val profile = ProfileEnvironment.current(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedProfile = prefs.getString(KEY_PROFILE, null)
        val heartbeat = prefs.getLong(KEY_HEARTBEAT, 0L).takeIf { it > 0L }
        val heartbeatAge = heartbeat?.let { (now - it).coerceAtLeast(0L) }
        val connected = prefs.getBoolean(KEY_CONNECTED, false) &&
            storedProfile == profile.profileKey &&
            heartbeatAge != null &&
            heartbeatAge <= ProfileControlPolicy.SERVICE_HEARTBEAT_FRESH_MS

        val eventWall = prefs.getLong(KEY_EVENT_WALL, 0L).takeIf { it > 0L }
        val rootWall = prefs.getLong(KEY_ROOT_WALL, 0L).takeIf { it > 0L }
        return ProfileAccessibilitySnapshot(
            profileKey = profile.profileKey,
            localServiceConnected = connected,
            heartbeatAgeMs = heartbeatAge,
            lastEventPackage = prefs.getString(KEY_EVENT_PACKAGE, null),
            lastEventAgeMs = eventWall?.let { (now - it).coerceAtLeast(0L) },
            rootAvailable = prefs.getBoolean(KEY_ROOT_AVAILABLE, false),
            rootPackage = prefs.getString(KEY_ROOT_PACKAGE, null),
            rootAgeMs = rootWall?.let { (now - it).coerceAtLeast(0L) }
        )
    }
}
