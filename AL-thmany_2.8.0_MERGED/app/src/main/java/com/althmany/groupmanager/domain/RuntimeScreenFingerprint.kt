package com.althmany.groupmanager.domain

/** Stable, allocation-light fingerprint used to recognize repeated WhatsApp screen snapshots. */
object RuntimeScreenFingerprint {
    fun calculate(labels: Sequence<CharSequence?>, stateTokens: Sequence<String> = emptySequence()): Long {
        var hash = FNV_OFFSET_BASIS
        val normalized = labels
            .mapNotNull { it?.toString()?.trim()?.lowercase()?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
        for (value in normalized) hash = mix(hash, value)
        for (token in stateTokens.sorted()) hash = mix(hash, "#${token.lowercase()}")
        return hash
    }

    private fun mix(start: Long, text: String): Long {
        var hash = start
        for (char in text) {
            hash = hash xor char.code.toLong()
            hash *= FNV_PRIME
        }
        hash = hash xor 0xffL
        hash *= FNV_PRIME
        return hash
    }

    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
}
