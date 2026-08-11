package com.althmany.groupmanager.domain

/** Compile-time switches for the Smart Runtime. They are explicit so regressions can isolate features. */
object RuntimeFeatureFlags {
    const val CONFIDENCE_ENGINE = true
    const val SCREEN_FINGERPRINT = true
    const val CIRCUIT_BREAKER = true
    const val DIAGNOSTIC_JOURNAL = true
}
