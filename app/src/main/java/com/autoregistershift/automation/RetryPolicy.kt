package com.autoregistershift.automation

class RetryPolicy(private val maximumRetries: Int) {
    var retryCount: Int = 0
        private set

    fun canRetry(): Boolean = retryCount < maximumRetries.coerceAtLeast(0)

    fun recordRetry(): Boolean {
        if (!canRetry()) return false
        retryCount++
        return true
    }

    fun reset() {
        retryCount = 0
    }
}
