package com.autoregistershift.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { ACTIVITY, SUCCESS, ERROR }

data class LogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val message: String
) {
    fun displayText(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
        return "[$time] $message"
    }
}
