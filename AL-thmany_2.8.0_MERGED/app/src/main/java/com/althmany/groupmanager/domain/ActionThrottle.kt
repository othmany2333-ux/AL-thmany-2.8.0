package com.althmany.groupmanager.domain

/** Thread-safe guard against accidental double taps and duplicate launches. */
class ActionThrottle(private val minimumIntervalMillis: Long) {
    init {
        require(minimumIntervalMillis >= 0)
    }

    private var lastAcceptedAt: Long? = null

    @Synchronized
    fun tryAcquire(nowMillis: Long): Boolean {
        val last = lastAcceptedAt
        if (last != null && nowMillis - last < minimumIntervalMillis) return false
        lastAcceptedAt = nowMillis
        return true
    }

    @Synchronized
    fun reset() {
        lastAcceptedAt = null
    }
}
