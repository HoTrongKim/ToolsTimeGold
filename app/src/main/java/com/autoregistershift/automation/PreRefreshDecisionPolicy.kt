package com.autoregistershift.automation

enum class PreRefreshDecision {
    WAIT_FOR_CURRENT_LOADING,
    USE_VISIBLE_SLOT,
    REFRESH
}

object PreRefreshDecisionPolicy {
    fun choose(
        loading: Boolean,
        detectedSlot: Boolean,
        visibleTimeRange: Boolean,
        fallbackEnabled: Boolean
    ): PreRefreshDecision = when {
        loading -> PreRefreshDecision.WAIT_FOR_CURRENT_LOADING
        detectedSlot || (visibleTimeRange && fallbackEnabled) ->
            PreRefreshDecision.USE_VISIBLE_SLOT
        else -> PreRefreshDecision.REFRESH
    }
}
