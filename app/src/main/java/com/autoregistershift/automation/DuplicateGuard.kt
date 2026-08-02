package com.autoregistershift.automation

import com.autoregistershift.model.ShiftAttemptStatus
import com.autoregistershift.model.ShiftHistoryEntry

object DuplicateGuard {
    fun shouldSkip(
        entries: Collection<ShiftHistoryEntry>,
        identifier: String,
        cooldownMs: Long,
        nowMs: Long
    ): Boolean {
        val latest = entries.filter { it.identifier == identifier }.maxByOrNull { it.timestampMs }
            ?: return false
        return latest.status == ShiftAttemptStatus.SUCCESS ||
            latest.status == ShiftAttemptStatus.FULL ||
            nowMs - latest.timestampMs < cooldownMs
    }
}
