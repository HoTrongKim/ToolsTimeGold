package com.autoregistershift.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.autoregistershift.model.LogEntry
import com.autoregistershift.model.LogLevel
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LogRepository(private val context: Context) {
    private val key = stringPreferencesKey("activity_logs")
    private val lastRoutineLogAt = AtomicLong(0L)

    val logs: Flow<List<LogEntry>> = context.appDataStore.data.map { preferences ->
        decode(preferences[key].orEmpty())
    }

    suspend fun add(message: String, level: LogLevel = LogLevel.ACTIVITY) {
        val entry = encode(LogEntry(System.currentTimeMillis(), level, message))
        context.appDataStore.edit { preferences ->
            val lines = preferences[key].orEmpty().lineSequence()
                .filter(String::isNotBlank).toMutableList()
            lines += entry
            preferences[key] = lines.takeLast(500).joinToString("\n")
        }
    }

    /**
     * Routine refresh messages are sampled so a 24/7 run cannot overwrite
     * registration errors and successes within a few minutes.
     */
    suspend fun addRoutine(message: String, nowMs: Long = System.currentTimeMillis()) {
        while (true) {
            val previous = lastRoutineLogAt.get()
            if (nowMs - previous < ROUTINE_LOG_INTERVAL_MS) return
            if (lastRoutineLogAt.compareAndSet(previous, nowMs)) {
                add(message)
                return
            }
        }
    }

    suspend fun clear() {
        context.appDataStore.edit { it.remove(key) }
    }

    private fun encode(entry: LogEntry): String {
        val safe = URLEncoder.encode(entry.message, StandardCharsets.UTF_8.name())
        return "${entry.timestampMs}\t${entry.level.name}\t$safe"
    }

    private fun decode(raw: String): List<LogEntry> = raw.lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        LogEntry(
            timestampMs = parts[0].toLongOrNull() ?: return@mapNotNull null,
            level = runCatching { LogLevel.valueOf(parts[1]) }.getOrDefault(LogLevel.ACTIVITY),
            message = runCatching {
                URLDecoder.decode(parts[2], StandardCharsets.UTF_8.name())
            }.getOrDefault(parts[2])
        )
    }.toList()

    companion object {
        private const val ROUTINE_LOG_INTERVAL_MS = 5 * 60_000L
    }
}
