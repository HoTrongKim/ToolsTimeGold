package com.autoregistershift.automation

/**
 * Lets a refreshed screen continue as soon as useful content is stable while
 * still refusing to act during loading or a burst of content-change events.
 */
class RefreshContentSettlePolicy(
    private val minimumWaitMs: Long,
    private val priorityContentWaitMs: Long = 30,
    private val quietPeriodMs: Long = 20
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
            elapsedMs >= priorityContentWaitMs.coerceAtMost(minimumWaitMs) &&
            (!contentChanged || quietForMs >= quietPeriodMs)
        ) {
            return true
        }
        if (elapsedMs < minimumWaitMs) return false
        return !contentChanged || quietForMs >= quietPeriodMs
    }
}
