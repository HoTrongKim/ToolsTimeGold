package com.autoregistershift.automation

/**
 * Retries a registration click only when the target app still exposes the
 * register button and has not entered a loading state.
 *
 * The retry count is deliberately capped at two even if the general network
 * retry setting is higher. This keeps the recovery useful without turning a
 * slow response into repeated blind clicks.
 */
class RegistrationAcknowledgementPolicy(
    maximumRetries: Int,
    private val firstRetryDelayMs: Long = 350,
    private val nextRetryDelayMs: Long = 450
) {
    val maximumRetries: Int = maximumRetries.coerceIn(0, MAX_ACKNOWLEDGEMENT_RETRIES)

    var retryCount: Int = 0
        private set

    private var lastAttemptAtMs: Long? = null

    fun recordInitialClick(nowMs: Long) {
        retryCount = 0
        lastAttemptAtMs = nowMs
    }

    fun shouldRetry(
        nowMs: Long,
        registerButtonVisible: Boolean,
        loading: Boolean
    ): Boolean {
        val lastAttempt = lastAttemptAtMs ?: return false
        if (!registerButtonVisible || loading || retryCount >= maximumRetries) return false
        val requiredDelay = if (retryCount == 0) firstRetryDelayMs else nextRetryDelayMs
        return nowMs - lastAttempt >= requiredDelay
    }

    fun recordRetry(nowMs: Long): Boolean {
        if (retryCount >= maximumRetries || lastAttemptAtMs == null) return false
        retryCount++
        lastAttemptAtMs = nowMs
        return true
    }

    companion object {
        const val MAX_ACKNOWLEDGEMENT_RETRIES = 2
    }
}
