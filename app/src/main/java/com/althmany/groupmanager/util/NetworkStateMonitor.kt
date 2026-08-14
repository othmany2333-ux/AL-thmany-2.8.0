package com.althmany.groupmanager.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock

/**
 * Shared lightweight validated-network probe for both automation engines.
 * It never launches apps and never mutates queue state.
 */
object NetworkStateMonitor {
    @Volatile private var lastCheckedAtElapsed = 0L
    @Volatile private var lastOnline = true

    fun isValidatedOnline(context: Context, force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCheckedAtElapsed in 0 until CACHE_MS) return lastOnline

        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val online = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        lastCheckedAtElapsed = now
        lastOnline = online
        return online
    }

    private const val CACHE_MS = 450L
}
