package com.althmany.groupmanager.util

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the display awake only while the user-started automation session is active.
 * It does not grant input/accessibility privileges and does not cross Android/Knox boundaries.
 */
object AutomationScreenAwakeGuard {
    private const val SAFETY_TIMEOUT_MS = 12L * 60L * 60L * 1_000L

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    @Synchronized
    fun sync(context: Context, keepAwake: Boolean) {
        if (!keepAwake) {
            release()
            return
        }

        val current = wakeLock
        if (current?.isHeld == true) return

        val powerManager =
            context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = current ?: powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "ALthmany:AutomationScreenAwake"
        ).apply {
            setReferenceCounted(false)
            wakeLock = this
        }

        if (!lock.isHeld) {
            lock.acquire(SAFETY_TIMEOUT_MS)
        }
    }

    @Synchronized
    fun release() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        wakeLock = null
    }
}
