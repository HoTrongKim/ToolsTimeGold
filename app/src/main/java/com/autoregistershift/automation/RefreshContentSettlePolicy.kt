package com.autoregistershift.automation

/**
 * Lets a refreshed screen continue as soon as useful content is stable while
 * still refusing to act during loading or a burst of content-change events.
 */
class RefreshContentSettlePolicy(
    private val minimumWaitMs: Long,
    private val priorityContentWaitMs: Long = 120,
    private val quietPeriodMs: Long = 80
) {
    fun isReady(
        elapsedMs: Long,
        loading: Boolean,
        contentChanged: Boolean,
        quietForMs: Long,
        priorityContentVisible: Boolean
    ): Boolean {
        if (loading) return false
        if (priorityContentVisible &&
            contentChanged &&
            elapsedMs >= priorityContentWaitMs.coerceAtMost(minimumWaitMs) &&
            quietForMs >= quietPeriodMs
        ) {
            return true
        }
        if (elapsedMs < minimumWaitMs) return false
        return !contentChanged || quietForMs >= quietPeriodMs
    }
}
