package com.althmany.groupmanager.domain

/**
 * Small in-memory ledger that suppresses duplicate successful actions caused by repeated
 * Accessibility events, while still allowing a deliberate retry after the configured window.
 */
class RuntimeIdempotencyGuard(private val maxEntries: Int = 64) {
    private val lastSuccessAt = LinkedHashMap<String, Long>()

    fun shouldAllow(key: String, nowMs: Long, suppressionMs: Long): Boolean {
        val previous = lastSuccessAt[key] ?: return true
        return nowMs - previous >= suppressionMs.coerceAtLeast(0L)
    }

    fun recordSuccess(key: String, nowMs: Long) {
        lastSuccessAt[key] = nowMs
        while (lastSuccessAt.size > maxEntries.coerceAtLeast(1)) {
            val oldest = lastSuccessAt.entries.firstOrNull()?.key ?: break
            lastSuccessAt.remove(oldest)
        }
    }

    fun clear() = lastSuccessAt.clear()
}
