package com.autoregistershift.util

import com.autoregistershift.automation.AutomationStopToken
import com.autoregistershift.automation.ContinuousRunPolicy
import com.autoregistershift.automation.DuplicateGuard
import com.autoregistershift.automation.RegistrationAcknowledgementPolicy
import com.autoregistershift.automation.RefreshContentSettlePolicy
import com.autoregistershift.automation.RefreshCadencePolicy
import com.autoregistershift.automation.RefreshWakePolicy
import com.autoregistershift.automation.RetryPolicy
import com.autoregistershift.model.ShiftAttemptStatus
import com.autoregistershift.model.ShiftHistoryEntry
import com.autoregistershift.model.CoordinatePoint
import com.autoregistershift.model.AppSettings
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
            ShiftHistoryEntry("B", ShiftAttemptStatus.SUCCESS, 1_000),
            ShiftHistoryEntry("FULL", ShiftAttemptStatus.FULL, 1_000)
        )
        assertTrue(DuplicateGuard.shouldSkip(entries, "A", 30_000, 20_000))
        assertFalse(DuplicateGuard.shouldSkip(entries, "A", 30_000, 50_001))
        assertTrue(DuplicateGuard.shouldSkip(entries, "B", 30_000, 99_999))
        assertTrue(DuplicateGuard.shouldSkip(entries, "FULL", 30_000, 99_999))
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
        val retry = RegistrationAcknowledgementPolicy(maximumRetries = 5)
        retry.recordInitialClick(1_000)

        assertEquals(2, retry.maximumRetries)
        assertFalse(retry.shouldRetry(1_349, registerButtonVisible = true, loading = false))
        assertFalse(retry.shouldRetry(1_400, registerButtonVisible = true, loading = true))
        assertFalse(retry.shouldRetry(1_400, registerButtonVisible = false, loading = false))

        assertTrue(retry.shouldRetry(1_350, registerButtonVisible = true, loading = false))
        assertTrue(retry.recordRetry(1_350))
        assertFalse(retry.shouldRetry(1_799, registerButtonVisible = true, loading = false))

        assertTrue(retry.shouldRetry(1_800, registerButtonVisible = true, loading = false))
        assertTrue(retry.recordRetry(1_800))
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
    fun continuousModeIgnoresRuntimeAndRegistrationLimits() {
        assertFalse(ContinuousRunPolicy.maximumRunReached(true, 10_000_000, 1))
        assertFalse(ContinuousRunPolicy.maximumRegistrationsReached(true, 999, 1))
        assertFalse(ContinuousRunPolicy.shouldStopForUnknownScreen(true, 999, 5))

        assertTrue(ContinuousRunPolicy.maximumRunReached(false, 60_000, 1))
        assertTrue(ContinuousRunPolicy.maximumRegistrationsReached(false, 2, 2))
        assertTrue(ContinuousRunPolicy.shouldStopForUnknownScreen(false, 5, 5))
    }

    @Test
    fun aSecondDistinctShiftIsNotBlockedByFirstShiftSuccess() {
        val entries = listOf(
            ShiftHistoryEntry("2026-07-31|10:00|14:00|Khu 1", ShiftAttemptStatus.SUCCESS, 1_000)
        )
        assertFalse(
            DuplicateGuard.shouldSkip(
                entries,
                "2026-07-31|14:00|17:00|Khu 1",
                30_000,
                2_000
            )
        )
    }

    @Test
    fun defaultRegisterPointMatchesBottomButtonInRealScreen() {
        val register = CoordinatePoint.defaults.first { it.id == "register" }
        assertEquals(.50f, register.xRatio, .0001f)
        assertEquals(.91f, register.yRatio, .0001f)
        assertTrue(register.enabled)
    }

    @Test
    fun defaultTimingUsesHalfSecondRefreshAndFastSlotHandling() {
        val timing = AppSettings()
        assertEquals(500L, timing.refreshIntervalMs)
        assertEquals(100L, timing.waitAfterSwipeMs)
        assertEquals(120, timing.maxRefreshesPerMinute)
        assertEquals(60L, timing.clickDebounceMs)
        assertEquals(120L, timing.refreshSwipeDurationMs)
    }

    @Test
    fun refreshWaitOnlyWakesForAvailableSlotInTargetSchedule() {
        assertTrue(RefreshWakePolicy.shouldWake(true, true, true, false, true))
        assertFalse(RefreshWakePolicy.shouldWake(false, true, true, false, true))
        assertFalse(RefreshWakePolicy.shouldWake(true, false, true, false, true))
        assertFalse(RefreshWakePolicy.shouldWake(true, true, true, true, true))
        assertFalse(RefreshWakePolicy.shouldWake(true, true, true, false, false))
    }

    @Test
    fun refreshCadenceIncludesSwipeAndDataWaitInsideHalfSecond() {
        assertEquals(500L, RefreshCadencePolicy.remainingWaitMs(10_000, 0, 500))
        assertEquals(280L, RefreshCadencePolicy.remainingWaitMs(10_220, 10_000, 500))
        assertEquals(0L, RefreshCadencePolicy.remainingWaitMs(10_501, 10_000, 500))
    }

    @Test
    fun refreshSettlePolicyIsFastForSlotsButWaitsForLoadingAndEventBursts() {
        val policy = RefreshContentSettlePolicy(minimumWaitMs = 300)

        assertFalse(
            policy.isReady(200, loading = true, contentChanged = true, quietForMs = 200, priorityContentVisible = true)
        )
        assertFalse(
            policy.isReady(20, loading = false, contentChanged = true, quietForMs = 100, priorityContentVisible = true)
        )
        assertTrue(
            policy.isReady(30, loading = false, contentChanged = true, quietForMs = 20, priorityContentVisible = true)
        )
        assertTrue(
            policy.isReady(30, loading = false, contentChanged = false, quietForMs = 0, priorityContentVisible = true)
        )
        assertFalse(
            policy.isReady(300, loading = false, contentChanged = true, quietForMs = 10, priorityContentVisible = false)
        )
        assertTrue(
            policy.isReady(300, loading = false, contentChanged = false, quietForMs = 0, priorityContentVisible = false)
        )
    }
}
