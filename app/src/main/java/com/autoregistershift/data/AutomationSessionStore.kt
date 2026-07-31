package com.autoregistershift.data

import android.content.Context

/** Persists only the user's run intent so a sticky foreground service can recover after process death. */
class AutomationSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val shouldRun: Boolean
        get() = preferences.getBoolean(KEY_SHOULD_RUN, false)

    val isPaused: Boolean
        get() = preferences.getBoolean(KEY_PAUSED, false)

    fun markRunning() {
        preferences.edit().putBoolean(KEY_SHOULD_RUN, true).putBoolean(KEY_PAUSED, false).apply()
    }

    fun markPaused() {
        preferences.edit().putBoolean(KEY_SHOULD_RUN, true).putBoolean(KEY_PAUSED, true).apply()
    }

    fun markStopped() {
        preferences.edit().putBoolean(KEY_SHOULD_RUN, false).putBoolean(KEY_PAUSED, false).apply()
    }

    companion object {
        private const val FILE_NAME = "automation_session"
        private const val KEY_SHOULD_RUN = "should_run"
        private const val KEY_PAUSED = "paused"
    }
}
