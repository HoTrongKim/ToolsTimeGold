package com.autoregistershift.automation

class RateLimiter(
    private val limit: () -> Int,
    private val windowMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val current = now()
        while (timestamps.isNotEmpty() && current - timestamps.first() >= windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= limit().coerceAtLeast(1)) return false
        timestamps.addLast(current)
        return true
    }

    @Synchronized
    fun clear() = timestamps.clear()
}
