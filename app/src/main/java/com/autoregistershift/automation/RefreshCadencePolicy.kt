package com.autoregistershift.automation

/** Tính phần thời gian còn lại để hai lần bắt đầu vuốt cách nhau đúng một chu kỳ. */
object RefreshCadencePolicy {
    fun remainingWaitMs(
        nowMs: Long,
        lastRefreshStartedAtMs: Long,
        intervalMs: Long
    ): Long {
        val safeInterval = intervalMs.coerceAtLeast(1)
        if (lastRefreshStartedAtMs <= 0) return safeInterval
        return (lastRefreshStartedAtMs + safeInterval - nowMs).coerceAtLeast(0)
    }
}
