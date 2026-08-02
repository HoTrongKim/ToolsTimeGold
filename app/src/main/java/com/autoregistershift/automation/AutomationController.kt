package com.autoregistershift.automation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.autoregistershift.data.LogRepository
import com.autoregistershift.data.AutomationSessionStore
import com.autoregistershift.data.SettingsRepository
import com.autoregistershift.data.ShiftHistoryRepository
import com.autoregistershift.model.LogLevel
import com.autoregistershift.model.RefreshSpeedPreset
import com.autoregistershift.service.AutomationForegroundService
import com.autoregistershift.service.AutoRegisterAccessibilityService
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
            AutomationSessionStore(context.applicationContext).markRunning()
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
                AutomationSessionStore(app).markRunning()
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
        appContext?.let { AutomationSessionStore(it).markPaused() }
        engine?.pause()
    }

    fun setRefreshSpeed(preset: RefreshSpeedPreset) {
        engine?.updateRefreshSpeed(preset)
        val app = appContext ?: return
        scope.launch {
            SettingsRepository(app).update { preset.applyTo(it) }
            LogRepository(app).add("Đã đổi tốc độ: ${preset.label}")
        }
    }

    fun setContinuousMode(enabled: Boolean) {
        engine?.updateContinuousMode(enabled)
        val app = appContext ?: return
        scope.launch {
            SettingsRepository(app).update { current ->
                current.copy(
                    continuousMode = enabled,
                    stopAfterSuccess = if (enabled) false else current.stopAfterSuccess
                )
            }
            LogRepository(app).add(
                if (enabled) "Đã bật chế độ chạy liên tục 24/7" else "Đã tắt chế độ chạy liên tục"
            )
        }
    }

    fun restoreIfNeeded(context: Context) {
        initialize(context)
        val session = AutomationSessionStore(context.applicationContext)
        if (session.shouldRun && !session.isPaused && automationJob?.isActive != true) {
            startOrResume(context.applicationContext)
        }
    }

    fun stop() {
        val app = appContext
        app?.let { AutomationSessionStore(it).markStopped() }
        if (stopping.compareAndSet(false, true)) {
            engine?.stop()
            automationJob?.cancel()
            automationJob = null
            engine = null
            _state.value = _state.value.copy(
                state = AutomationState.STOPPED,
                enteredAtMs = System.currentTimeMillis(),
                message = "Đã dừng"
            )
        }
        app?.let { application ->
            application.stopService(Intent(application, FloatingOverlayService::class.java))
            application.stopService(Intent(application, AutomationForegroundService::class.java))
        }
    }

    fun enterBankingMode(context: Context) {
        initialize(context)
        val accessibilityService = AutoRegisterAccessibilityService.instance
        stop()
        accessibilityService?.disableForBankingMode()
        _state.value = StateSnapshot(
            state = AutomationState.STOPPED,
            message = "Chế độ ngân hàng • đã tắt tool, nút nổi và Trợ năng"
        )
    }

    fun onUiEvent(packageName: String) {
        _uiEvents.tryEmit(packageName)
    }

    fun onAccessibilityConnectionChanged(enabled: Boolean) {
        if (!enabled) stop()
    }
}
