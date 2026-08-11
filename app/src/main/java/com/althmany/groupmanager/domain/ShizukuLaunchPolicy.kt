package com.althmany.groupmanager.domain

/** Pure validation for ActivityManager deep-link launch output. */
object ShizukuLaunchPolicy {
    private val failureMarkers = listOf(
        "error:",
        "exception occurred",
        "securityexception",
        "permission denial",
        "unable to resolve intent",
        "activity class does not exist",
        "no activity found to handle intent"
    )

    /**
     * `am start` can return exit code 0 while printing a semantic launch error. Accept only a clean
     * result; benign messages such as "intent delivered to currently running activity" stay valid.
     */
    fun launchAccepted(exitCode: Int, output: String): Boolean {
        if (exitCode != 0) return false
        val normalized = output.lowercase()
        return failureMarkers.none(normalized::contains)
    }
}
