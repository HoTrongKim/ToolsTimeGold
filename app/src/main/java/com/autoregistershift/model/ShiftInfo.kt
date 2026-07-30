package com.autoregistershift.model

data class ShiftInfo(
    val date: String,
    val startTime: String,
    val endTime: String,
    val area: String = "",
    val content: String
) {
    val identifier: String
        get() = listOf(date, startTime, endTime, area.ifBlank { content }).joinToString("|")
}

enum class ShiftAttemptStatus {
    ATTEMPTED, SUCCESS, FULL, ERROR, SKIPPED
}

data class ShiftHistoryEntry(
    val identifier: String,
    val status: ShiftAttemptStatus,
    val timestampMs: Long
)
