package com.althmany.groupmanager.util

/**
 * Process-local health snapshot for the dashboard. It deliberately stores only engine metadata,
 * never WhatsApp labels, invite URLs, contact names or message contents.
 */
data class RuntimeHealthSnapshot(
    val updatedAtElapsedMs: Long,
    val fingerprint: Long,
    val stableScreenScans: Int,
    val directive: String,
    val action: String,
    val conflict: String,
    val confidence: Int?,
    val confidenceBand: String?,
    val watchdog: String
)

object RuntimeHealthMonitor {
    @Volatile
    private var latest: RuntimeHealthSnapshot? = null

    fun updateScreen(
        nowElapsedMs: Long,
        fingerprint: Long,
        stableScreenScans: Int,
        directive: String,
        action: String,
        conflict: String
    ) {
        val previous = latest
        latest = RuntimeHealthSnapshot(
            updatedAtElapsedMs = nowElapsedMs,
            fingerprint = fingerprint,
            stableScreenScans = stableScreenScans,
            directive = directive,
            action = action,
            conflict = conflict,
            confidence = previous?.confidence,
            confidenceBand = previous?.confidenceBand,
            watchdog = previous?.watchdog ?: "HEALTHY"
        )
    }

    fun updateConfidence(nowElapsedMs: Long, score: Int, band: String) {
        val previous = latest ?: return
        latest = previous.copy(
            updatedAtElapsedMs = nowElapsedMs,
            confidence = score.coerceIn(0, 100),
            confidenceBand = band
        )
    }

    fun updateWatchdog(nowElapsedMs: Long, state: String) {
        val previous = latest ?: return
        latest = previous.copy(updatedAtElapsedMs = nowElapsedMs, watchdog = state)
    }

    fun snapshot(): RuntimeHealthSnapshot? = latest

    fun clear() {
        latest = null
    }
}
