package com.autoregistershift.util

import com.autoregistershift.automation.AutomationStopToken
import com.autoregistershift.automation.DuplicateGuard
import com.autoregistershift.automation.RetryPolicy
import com.autoregistershift.model.ShiftAttemptStatus
import com.autoregistershift.model.ShiftHistoryEntry
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
    fun emergencyStopIsImmediateAndIdempotent() {
        val token = AutomationStopToken()
        assertTrue(token.isActive)
        token.stop()
        token.stop()
        assertFalse(token.isActive)
    }
}
