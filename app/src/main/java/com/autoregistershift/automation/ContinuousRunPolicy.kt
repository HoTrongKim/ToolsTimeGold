package com.autoregistershift.automation

object ContinuousRunPolicy {
    fun maximumRunReached(
        continuousMode: Boolean,
        elapsedMs: Long,
        maximumMinutes: Int
    ): Boolean = !continuousMode &&
        elapsedMs >= maximumMinutes.coerceAtLeast(1) * 60_000L

    fun maximumRegistrationsReached(
        continuousMode: Boolean,
        successCount: Int,
        maximumRegistrations: Int
    ): Boolean = !continuousMode && successCount >= maximumRegistrations.coerceAtLeast(1)

    fun shouldStopForUnknownScreen(
        continuousMode: Boolean,
        consecutiveUnknownScreens: Int,
        maximumUnknownScreens: Int
    ): Boolean = !continuousMode &&
        consecutiveUnknownScreens >= maximumUnknownScreens.coerceAtLeast(1)
}
