package com.autoregistershift.automation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.autoregistershift.data.LogRepository
import com.autoregistershift.data.SettingsRepository
import com.autoregistershift.data.ShiftHistoryRepository
import com.autoregistershift.model.LogLevel
import com.autoregistershift.service.AutomationForegroundService
import com.autoregistershift.service.FloatingOverlayService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AutomationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()
    private val stopping = AtomicBoolean(false)
    private val _state = MutableStateFlow(StateSnapshot())
    val state = _state.asStateFlow()
    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val uiEvents = _uiEvents.asSharedFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var engine: AutomationEngine? = null
    @Volatile private var automationJob: Job? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun startOrResume(context: Context) {
        initialize(context)
        val existing = engine
        if (existing?.isPaused == true) {
            existing.resume()
            return
        }
        if (automationJob?.isActive == true) return

        scope.launch {
            commandMutex.withLock {
                if (automationJob?.isActive == true) return@withLock
                val app = appContext ?: return@withLock
                val settingsRepository = SettingsRepository(app)
                val settings = settingsRepository.settings.first()
                val logs = LogRepository(app)
                if (settings.targetPackage.isBlank()) {
                    _state.value = StateSnapshot(
                        state = AutomationState.ERROR,
                        message = "Chưa cấu hình package ứng dụng mục tiêu"
                    )
                    logs.add("Không thể bắt đầu: chưa cấu hình package mục tiêu", LogLevel.ERROR)
                    return@withLock
                }
                stopping.set(false)
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, AutomationForegroundService::class.java)
                        .setAction(AutomationForegroundService.ACTION_START)
                )
                if (settings.showOverlay) {
                    app.startService(Intent(app, FloatingOverlayService::class.java))
                }
                val created = AutomationEngine(
                    context = app,
                    settings = settings,
                    logs = logs,
                    history = ShiftHistoryRepository(app),
                    onSnapshot = { _state.value = it },
                    onTerminalStop = { stop() }
                )
                engine = created
                automationJob = scope.launch {
                    try {
                        created.run()
                    } finally {
                        if (engine === created) engine = null
                    }
                }
            }
        }
    }

    fun pause() {
        engine?.pause()
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        engine?.stop()
        automationJob?.cancel()
        automationJob = null
        engine = null
        _state.value = _state.value.copy(
            state = AutomationState.STOPPED,
            enteredAtMs = System.currentTimeMillis(),
            message = "Đã dừng"
        )
        appContext?.let { app ->
            app.stopService(Intent(app, FloatingOverlayService::class.java))
            app.stopService(Intent(app, AutomationForegroundService::class.java))
        }
    }

    fun onUiEvent(packageName: String) {
        _uiEvents.tryEmit(packageName)
    }

    fun onAccessibilityConnectionChanged(enabled: Boolean) {
        if (!enabled && automationJob?.isActive == true) {
            _state.value = _state.value.copy(
                state = AutomationState.WAITING_FOR_TARGET_APP,
                enteredAtMs = System.currentTimeMillis(),
                message = "Đang chờ dịch vụ Trợ năng"
            )
        }
    }
}
