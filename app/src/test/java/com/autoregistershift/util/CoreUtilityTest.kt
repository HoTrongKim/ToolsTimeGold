package com.autoregistershift.util

import com.autoregistershift.automation.AutomationStopToken
import com.autoregistershift.automation.DuplicateGuard
import com.autoregistershift.automation.RegistrationAcknowledgementPolicy
import com.autoregistershift.automation.RefreshContentSettlePolicy
import com.autoregistershift.automation.RetryPolicy
import com.autoregistershift.model.ShiftAttemptStatus
import com.autoregistershift.model.ShiftHistoryEntry
import com.autoregistershift.model.CoordinatePoint
import com.autoregistershift.model.AppSettings
import com.autoregistershift.model.RefreshSpeedPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreUtilityTest {
    @Test
    fun convertsRatioAndClampsValues() {
        assertEquals(540, CoordinateConverter.toReal(.5f, 1080))
        assertEquals(0, CoordinateConverter.toReal(-1f, 1080))
        assertEquals(1080, CoordinateConverter.toReal(2f, 1080))
        assertEquals(.5f, CoordinateConverter.toRatio(960f, 1920), .0001f)
    }

    @Test
    fun detectsOnlyValid24HourTimes() {
        assertEquals(listOf("07:05", "23:59"), TimeRegex.findAll("Ca 07:05–23:59"))
        assertTrue(TimeRegex.findAll("24:00 9:30 12:60").isEmpty())
    }

    @Test
    fun duplicateGuardHonorsCooldownAndPermanentSuccess() {
        val entries = listOf(
            ShiftHistoryEntry("A", ShiftAttemptStatus.ERROR, 10_000),
            ShiftHistoryEntry("B", ShiftAttemptStatus.SUCCESS, 1_000)
        )
        assertTrue(DuplicateGuard.shouldSkip(entries, "A", 30_000, 20_000))
        assertFalse(DuplicateGuard.shouldSkip(entries, "A", 30_000, 50_001))
        assertTrue(DuplicateGuard.shouldSkip(entries, "B", 30_000, 99_999))
        assertFalse(DuplicateGuard.shouldSkip(entries, "C", 30_000, 20_000))
    }

    @Test
    fun retryStopsAtConfiguredMaximum() {
        val retry = RetryPolicy(3)
        assertTrue(retry.recordRetry())
        assertTrue(retry.recordRetry())
        assertTrue(retry.recordRetry())
        assertFalse(retry.recordRetry())
        assertEquals(3, retry.retryCount)
    }

    @Test
    fun registrationClickRetryWaitsForAcknowledgementAndNeverSpams() {
        val retry = RegistrationAcknowledgementPolicy(
            maximumRetries = 5,
            firstRetryDelayMs = 550,
            nextRetryDelayMs = 700
        )
        retry.recordInitialClick(1_000)

        assertEquals(2, retry.maximumRetries)
        assertFalse(retry.shouldRetry(1_549, registerButtonVisible = true, loading = false))
        assertFalse(retry.shouldRetry(1_700, registerButtonVisible = true, loading = true))
        assertFalse(retry.shouldRetry(1_700, registerButtonVisible = false, loading = false))

        assertTrue(retry.shouldRetry(1_550, registerButtonVisible = true, loading = false))
        assertTrue(retry.recordRetry(1_550))
        assertFalse(retry.shouldRetry(2_249, registerButtonVisible = true, loading = false))

        assertTrue(retry.shouldRetry(2_250, registerButtonVisible = true, loading = false))
        assertTrue(retry.recordRetry(2_250))
        assertFalse(retry.shouldRetry(4_000, registerButtonVisible = true, loading = false))
        assertFalse(retry.recordRetry(4_000))
    }

    @Test
    fun emergencyStopIsImmediateAndIdempotent() {
        val token = AutomationStopToken()
        assertTrue(token.isActive)
        token.stop()
        token.stop()
        assertFalse(token.isActive)
    }

    @Test
    fun defaultRegisterPointMatchesBottomButtonInRealScreen() {
        val register = CoordinatePoint.defaults.first { it.id == "register" }
        assertEquals(.50f, register.xRatio, .0001f)
        assertEquals(.91f, register.yRatio, .0001f)
        assertTrue(register.enabled)
    }

    @Test
    fun fastRefreshPresetUsesFastestRateAllowedByTargetProtection() {
        val fast = RefreshSpeedPreset.FAST.applyTo(AppSettings())
        assertEquals(1_000L, fast.refreshIntervalMs)
        assertEquals(300L, fast.waitAfterSwipeMs)
        assertEquals(35, fast.maxRefreshesPerMinute)
        assertEquals(100L, fast.clickDebounceMs)
        assertEquals(350L, fast.refreshSwipeDurationMs)
        assertTrue(RefreshSpeedPreset.FAST.matches(fast))
    }

    @Test
    fun refreshSettlePolicyIsFastForSlotsButWaitsForLoadingAndEventBursts() {
        val policy = RefreshContentSettlePolicy(minimumWaitMs = 300)

        assertFalse(
            policy.isReady(200, loading = true, contentChanged = true, quietForMs = 200, priorityContentVisible = true)
        )
        assertFalse(
            policy.isReady(100, loading = false, contentChanged = true, quietForMs = 100, priorityContentVisible = true)
        )
        assertTrue(
            policy.isReady(120, loading = false, contentChanged = true, quietForMs = 80, priorityContentVisible = true)
        )
        assertFalse(
            policy.isReady(300, loading = false, contentChanged = true, quietForMs = 40, priorityContentVisible = false)
        )
        assertTrue(
            policy.isReady(300, loading = false, contentChanged = false, quietForMs = 0, priorityContentVisible = false)
        )
    }
}
