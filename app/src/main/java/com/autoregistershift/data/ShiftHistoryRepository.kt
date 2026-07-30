package com.autoregistershift.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.autoregistershift.automation.DuplicateGuard
import com.autoregistershift.model.ShiftAttemptStatus
import com.autoregistershift.model.ShiftHistoryEntry
import kotlinx.coroutines.flow.first

class ShiftHistoryRepository(private val context: Context) {
    private val key = stringSetPreferencesKey("shift_history")

    suspend fun record(identifier: String, status: ShiftAttemptStatus, nowMs: Long = System.currentTimeMillis()) {
        context.appDataStore.edit { preferences ->
            val entries = preferences[key].orEmpty().mapNotNull(::decode)
                .filterNot { it.identifier == identifier }
                .sortedByDescending { it.timestampMs }
                .take(199)
                .map(::encode)
                .toMutableSet()
            entries += encode(ShiftHistoryEntry(identifier, status, nowMs))
            preferences[key] = entries
        }
    }

    suspend fun shouldSkip(identifier: String, cooldownMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val entries = context.appDataStore.data.first()[key].orEmpty().mapNotNull(::decode)
        return DuplicateGuard.shouldSkip(entries, identifier, cooldownMs, nowMs)
    }

    suspend fun clear() {
        context.appDataStore.edit { it.remove(key) }
    }

    private fun encode(value: ShiftHistoryEntry) =
        "${value.timestampMs}\t${value.status.name}\t${value.identifier.replace("\t", " ")}"

    private fun decode(raw: String): ShiftHistoryEntry? {
        val parts = raw.split('\t', limit = 3)
        if (parts.size != 3) return null
        return ShiftHistoryEntry(
            identifier = parts[2],
            status = runCatching { ShiftAttemptStatus.valueOf(parts[1]) }.getOrNull() ?: return null,
            timestampMs = parts[0].toLongOrNull() ?: return null
        )
    }
}
