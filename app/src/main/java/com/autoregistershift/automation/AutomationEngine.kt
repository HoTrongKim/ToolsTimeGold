package com.autoregistershift.automation

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.autoregistershift.data.LogRepository
import com.autoregistershift.data.ShiftHistoryRepository
import com.autoregistershift.model.AppSettings
import com.autoregistershift.model.CoordinatePoint
import com.autoregistershift.model.LogLevel
import com.autoregistershift.model.ShiftAttemptStatus
import com.autoregistershift.model.ShiftInfo
import com.autoregistershift.service.AutoRegisterAccessibilityService
import com.autoregistershift.util.CoordinateConverter
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class AutomationEngine(
    private val context: Context,
    private val settings: AppSettings,
    private val logs: LogRepository,
    private val history: ShiftHistoryRepository,
    private val onSnapshot: (StateSnapshot) -> Unit,
    private val onTerminalStop: () -> Unit
) {
    private val stopToken = AutomationStopToken()
    private val paused = AtomicBoolean(false)
    private val stateMachine = StateMachine()
    private val finder = NodeFinder()
    private val resultDetector = RegistrationResultDetector(finder)
    private val clickLimiter = RateLimiter(limit = { settings.maxClicksPerMinute })
    private val refreshLimiter = RateLimiter(limit = { settings.maxRefreshesPerMinute })
    private var refreshCount = 0
    private var successCount = 0
    private var unknownScreenCount = 0
    private var lastClickAt = 0L
    private val startedAt = System.currentTimeMillis()

    val isPaused: Boolean get() = paused.get()

    suspend fun run() {
        try {
            transition(AutomationState.WAITING_FOR_TARGET_APP, "Tool bắt đầu")
            logs.add("Tool bắt đầu")
            while (stopToken.isActive) {
                currentCoroutineContext().ensureActive()
                waitWhilePaused()
                if (maximumRunReached()) {
                    failAndStop("Đã đạt thời gian chạy tối đa")
                    break
                }
                val service = AutoRegisterAccessibilityService.instance
                if (service == null) {
                    transition(AutomationState.WAITING_FOR_TARGET_APP, "Đang chờ dịch vụ Trợ năng")
                    delay(500)
                    continue
                }
                if (!deviceReady()) {
                    transition(AutomationState.WAITING_FOR_TARGET_APP, "Thiết bị đang khóa hoặc màn hình tắt")
                    delay(500)
                    continue
                }
                if (service.currentPackage() != settings.targetPackage) {
                    transition(AutomationState.WAITING_FOR_TARGET_APP, "Đang chờ ứng dụng mục tiêu")
                    delay(500)
                    continue
                }

                val root = service.currentRoot()
                if (finder.containsAny(root, settings.prohibitedTexts)) {
                    failAndStop("Phát hiện CAPTCHA/OTP/xác minh; tool đã dừng")
                    break
                }
                if (!isScheduleScreen(root)) {
                    unknownScreenCount++
                    transition(
                        AutomationState.WAITING_FOR_SCHEDULE_SCREEN,
                        "Đang chờ đúng màn hình đăng ký ca"
                    )
                    if (unknownScreenCount >= settings.maxUnknownScreens) {
                        failAndStop("Giao diện không xác định quá nhiều lần")
                        break
                    }
                    delay(700)
                    continue
                }
                unknownScreenCount = 0
                logs.add("Đã nhận diện màn hình đăng ký")
                if (!refresh(service)) {
                    delay(settings.refreshIntervalMs)
                    continue
                }
                waitForData(service)
                if (!canAct(service)) continue

                transition(AutomationState.CHECKING_SLOTS, "Đang tìm ca")
                val refreshedRoot = service.currentRoot()
                if (finder.hasLoading(refreshedRoot, settings.loadingTexts)) {
                    logs.add("Danh sách vẫn đang tải; chưa thao tác")
                    delay(settings.refreshIntervalMs)
                    continue
                }
                val (_, height) = service.displaySize()
                val detected = finder.detectShifts(refreshedRoot, height)
                val selected = detected.firstOrNull {
                    !history.shouldSkip(it.shift.identifier, settings.shiftCooldownMs)
                }
                val selection = SlotSelectionPolicy.choose(
                    hasDetectedSlot = selected != null,
                    hasTimeRangeSignal = finder.hasTimeRange(refreshedRoot),
                    fallbackEnabled = point("first_slot")?.enabled == true
                )
                if (selection == SlotSelection.WAIT_FOR_NEXT_REFRESH) {
                    val message = if (finder.containsAny(refreshedRoot, settings.noSlotTexts)) {
                        "Chưa có ca"
                    } else {
                        "Chưa phát hiện thẻ ca; tiếp tục làm mới"
                    }
                    logs.add(message)
                    delay(settings.refreshIntervalMs)
                    continue
                }
                val shift = selected?.shift ?: fallbackShift(refreshedRoot)
                if (history.shouldSkip(shift.identifier, settings.shiftCooldownMs)) {
                    logs.add("Ca vừa được xử lý, đang trong thời gian cooldown")
                    delay(settings.refreshIntervalMs)
                    continue
                }
                logs.add("Phát hiện ca ${shift.startTime}–${shift.endTime}")
                history.record(shift.identifier, ShiftAttemptStatus.ATTEMPTED)

                val opened = openSlot(service, selected)
                if (!opened) {
                    logs.add("Không mở được trang chi tiết", LogLevel.ERROR)
                    history.record(shift.identifier, ShiftAttemptStatus.ERROR)
                    returnToList(service)
                    continue
                }
                logs.add("Đã mở trang chi tiết")
                val registerNode = findRegisterButton(service)
                if (registerNode == null) {
                    logs.add("Không tìm thấy nút đăng ký", LogLevel.ERROR)
                    history.record(shift.identifier, ShiftAttemptStatus.SKIPPED)
                    returnToList(service)
                    continue
                }

                transition(AutomationState.REGISTERING, "Đang đăng ký")
                if (!safeClickNode(service, registerNode)) {
                    logs.add("Không thể bấm nút đăng ký", LogLevel.ERROR)
                    history.record(shift.identifier, ShiftAttemptStatus.ERROR)
                    returnToList(service)
                    continue
                }
                logs.add("Đã bấm đăng ký")
                val result = awaitRegistrationResult(service)
                when (result) {
                    RegistrationResult.SUCCESS -> {
                        successCount++
                        history.record(shift.identifier, ShiftAttemptStatus.SUCCESS)
                        logs.add("Đăng ký thành công", LogLevel.SUCCESS)
                        notifySuccess()
                        transition(AutomationState.CHECKING_RESULT, "Đăng ký thành công")
                        if (settings.stopAfterSuccess || successCount >= settings.maxRegistrations) {
                            stop()
                            onTerminalStop()
                            break
                        }
                    if (settings.autoReturnToList) returnToList(service)
                    }
                    RegistrationResult.FULL -> {
                        history.record(shift.identifier, ShiftAttemptStatus.FULL)
                        logs.add("Ca đã đầy")
                        closeDialogIfConfigured(service)
                        returnToList(service)
                    }
                    RegistrationResult.NETWORK_ERROR -> {
                        history.record(shift.identifier, ShiftAttemptStatus.ERROR)
                        logs.add("Lỗi mạng sau khi đã hết số lần thử lại", LogLevel.ERROR)
                        returnToList(service)
                    }
                    RegistrationResult.UNKNOWN -> {
                        history.record(shift.identifier, ShiftAttemptStatus.ERROR)
                        logs.add("Không xác định được kết quả đăng ký", LogLevel.ERROR)
                        returnToList(service)
                    }
                }
                delay(settings.refreshIntervalMs)
            }
        } catch (_: CancellationException) {
            // Stop là tức thời: mọi delay và callback đang chờ đều bị hủy ở đây.
        } catch (error: Throwable) {
            logs.add("Lỗi: ${error.message ?: error::class.java.simpleName}", LogLevel.ERROR)
            transition(AutomationState.ERROR, "Có lỗi: ${error.message ?: "không xác định"}")
        } finally {
            stopToken.stop()
        }
    }

    fun pause() {
        if (!stopToken.isActive) return
        paused.set(true)
        transition(AutomationState.PAUSED, "Đã tạm dừng")
    }

    fun resume() {
        if (!stopToken.isActive) return
        paused.set(false)
        transition(AutomationState.WAITING_FOR_TARGET_APP, "Đang tiếp tục")
    }

    fun stop() {
        stopToken.stop()
        paused.set(false)
        transition(AutomationState.STOPPED, "Đã dừng")
    }

    private suspend fun refresh(service: AutoRegisterAccessibilityService): Boolean {
        transition(AutomationState.REFRESHING, "Đang làm mới")
        if (!refreshLimiter.tryAcquire()) {
            logs.add("Đã đạt giới hạn làm mới mỗi phút; đang chờ", LogLevel.ERROR)
            delay(3_000)
            return false
        }
        val start = point("refresh_start") ?: return false
        val end = point("refresh_end") ?: return false
        if (!start.enabled || !end.enabled || !canAct(service)) return false
        val (width, height) = service.displaySize()
        val ok = GestureController(service).swipe(
            CoordinateConverter.toReal(start.xRatio, width).toFloat(),
            CoordinateConverter.toReal(start.yRatio, height).toFloat(),
            CoordinateConverter.toReal(end.xRatio, width).toFloat(),
            CoordinateConverter.toReal(end.yRatio, height).toFloat(),
            settings.refreshSwipeDurationMs
        )
        if (ok) {
            refreshCount++
            logs.add("Đang làm mới danh sách")
            publish()
        }
        return ok
    }

    private suspend fun waitForData(service: AutoRegisterAccessibilityService) {
        transition(AutomationState.WAITING_FOR_DATA, "Đang chờ dữ liệu")
        val timeout = max(settings.waitAfterSwipeMs, 5_000)
        val start = System.currentTimeMillis()
        delay(settings.waitAfterSwipeMs.coerceAtMost(timeout))
        while (
            stopToken.isActive &&
            System.currentTimeMillis() - start < timeout &&
            finder.hasLoading(service.currentRoot(), settings.loadingTexts)
        ) {
            waitWhilePaused()
            delay(200)
        }
    }

    private suspend fun openSlot(
        service: AutoRegisterAccessibilityService,
        detected: DetectedShift?
    ): Boolean {
        transition(AutomationState.OPENING_SLOT, "Đang mở ca")
        if (detected != null) {
            if (safeClickNode(service, detected.node)) {
                delay(settings.waitAfterOpenSlotMs)
                if (isDetail(service)) return true
            }
            if (safeClickScreenPoint(service, detected.bounds.centerX().toFloat(), detected.bounds.centerY().toFloat())) {
                delay(settings.waitAfterOpenSlotMs)
                if (isDetail(service)) return true
            }
        }
        for (id in listOf("first_slot", "slot_fallback")) {
            val coordinate = point(id) ?: continue
            if (!coordinate.enabled || !safeClickRatio(service, coordinate)) continue
            delay(settings.waitAfterOpenSlotMs)
            if (isDetail(service)) return true
        }
        return false
    }

    private suspend fun findRegisterButton(
        service: AutoRegisterAccessibilityService
    ): android.view.accessibility.AccessibilityNodeInfo? {
        transition(AutomationState.FINDING_REGISTER_BUTTON, "Đang tìm nút đăng ký")
        finder.findByTexts(service.currentRoot(), settings.registerButtonTexts)?.let { return it }
        val start = point("load_start")
        val end = point("load_end")
        if (start?.enabled == true && end?.enabled == true && canAct(service)) {
            val (width, height) = service.displaySize()
            GestureController(service).swipe(
                CoordinateConverter.toReal(start.xRatio, width).toFloat(),
                CoordinateConverter.toReal(start.yRatio, height).toFloat(),
                CoordinateConverter.toReal(end.xRatio, width).toFloat(),
                CoordinateConverter.toReal(end.yRatio, height).toFloat(),
                settings.loadSwipeDurationMs
            )
            delay(600)
        }
        return finder.findByTexts(service.currentRoot(), settings.registerButtonTexts)
    }

    private suspend fun awaitRegistrationResult(
        service: AutoRegisterAccessibilityService
    ): RegistrationResult {
        transition(AutomationState.CHECKING_RESULT, "Đang kiểm tra kết quả")
        val retry = RetryPolicy(settings.maxRetry)
        val started = System.currentTimeMillis()
        while (stopToken.isActive && System.currentTimeMillis() - started < settings.registrationTimeoutMs) {
            waitWhilePaused()
            if (!canAct(service, allowNonSchedule = true)) return RegistrationResult.UNKNOWN
            val root = service.currentRoot()
            when (val result = resultDetector.detect(root, settings)) {
                RegistrationResult.NETWORK_ERROR -> {
                    if (!retry.recordRetry()) return result
                    logs.add("Lỗi mạng, thử lại ${retry.retryCount}/${settings.maxRetry}", LogLevel.ERROR)
                    delay(settings.refreshIntervalMs)
                    val button = finder.findByTexts(service.currentRoot(), settings.registerButtonTexts)
                    if (button != null) safeClickNode(service, button)
                }
                RegistrationResult.UNKNOWN -> {
                    if (System.currentTimeMillis() - started >= 500 && isScheduleScreen(root)) {
                        logs.add("Ứng dụng đã tự quay lại danh sách sau khi đăng ký")
                        return RegistrationResult.SUCCESS
                    }
                    delay(200)
                }
                else -> return result
            }
        }
        return RegistrationResult.UNKNOWN
    }

    private suspend fun returnToList(service: AutoRegisterAccessibilityService) {
        transition(AutomationState.RETURNING_TO_LIST, "Đang quay lại danh sách")
        if (!canAct(service, allowNonSchedule = true)) return
        if (isScheduleScreen(service.currentRoot())) {
            logs.add("Đã ở màn hình danh sách; không thực hiện Back")
            return
        }
        if (!GestureController(service).back()) {
            point("back_fallback")?.takeIf { it.enabled }?.let { safeClickRatio(service, it) }
        }
        delay(700)
    }

    private suspend fun closeDialogIfConfigured(service: AutoRegisterAccessibilityService) {
        val close = point("close_dialog") ?: return
        if (close.enabled) safeClickRatio(service, close)
    }

    private suspend fun safeClickNode(
        service: AutoRegisterAccessibilityService,
        node: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        if (!canAct(service, allowNonSchedule = true) || !clickLimiter.tryAcquire()) return false
        debounceClick()
        if (!stopToken.isActive || paused.get()) return false
        return GestureController(service).clickNode(node).also {
            if (it) lastClickAt = System.currentTimeMillis()
        }
    }

    private suspend fun safeClickRatio(
        service: AutoRegisterAccessibilityService,
        point: CoordinatePoint
    ): Boolean {
        if (!canAct(service, allowNonSchedule = true) || !clickLimiter.tryAcquire()) return false
        debounceClick()
        if (!stopToken.isActive || paused.get()) return false
        return service.clickRatio(point.xRatio, point.yRatio, settings.targetPackage).also {
            if (it) lastClickAt = System.currentTimeMillis()
        }
    }

    private suspend fun safeClickScreenPoint(
        service: AutoRegisterAccessibilityService,
        x: Float,
        y: Float
    ): Boolean {
        if (!canAct(service, allowNonSchedule = true) || !clickLimiter.tryAcquire()) return false
        debounceClick()
        if (!stopToken.isActive || paused.get()) return false
        return GestureController(service).click(x, y).also {
            if (it) lastClickAt = System.currentTimeMillis()
        }
    }

    private suspend fun debounceClick() {
        val remaining = settings.clickDebounceMs - (System.currentTimeMillis() - lastClickAt)
        if (remaining > 0) delay(remaining)
    }

    private fun isDetail(service: AutoRegisterAccessibilityService): Boolean {
        val root = service.currentRoot()
        return service.currentPackage() == settings.targetPackage &&
            (finder.containsAny(root, settings.detailScreenTexts) ||
                finder.containsAny(root, settings.registerButtonTexts))
    }

    private fun isScheduleScreen(root: android.view.accessibility.AccessibilityNodeInfo?): Boolean =
        finder.containsAny(root, settings.scheduleScreenTexts) &&
            !finder.containsAny(root, settings.registerButtonTexts)

    private fun canAct(
        service: AutoRegisterAccessibilityService,
        allowNonSchedule: Boolean = false
    ): Boolean {
        if (!stopToken.isActive || paused.get() || !deviceReady()) return false
        val root = service.currentRoot()
        if (!AutomationSafetyGate.canInteract(
                currentPackage = service.currentPackage(),
                targetPackage = settings.targetPackage,
                deviceReady = deviceReady(),
                prohibitedStepDetected = finder.containsAny(root, settings.prohibitedTexts)
            )
        ) return false
        return allowNonSchedule || isScheduleScreen(root)
    }

    private suspend fun waitWhilePaused() {
        while (stopToken.isActive && paused.get()) delay(150)
    }

    private fun maximumRunReached(): Boolean =
        System.currentTimeMillis() - startedAt >= settings.maxRunMinutes.coerceAtLeast(1) * 60_000L

    private fun deviceReady(): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun point(id: String): CoordinatePoint? = settings.coordinates.firstOrNull { it.id == id }

    private fun fallbackShift(root: android.view.accessibility.AccessibilityNodeInfo?) = ShiftInfo(
        date = LocalDate.now().toString(),
        startTime = finder.allNodes(root).asSequence()
            .flatMap { com.autoregistershift.util.TimeRegex.findAll(finder.nodeText(it)).asSequence() }
            .firstOrNull() ?: "unknown",
        endTime = finder.allNodes(root).asSequence()
            .flatMap { com.autoregistershift.util.TimeRegex.findAll(finder.nodeText(it)).asSequence() }
            .drop(1).firstOrNull() ?: "unknown",
        content = "semantic-coordinate:${point("first_slot")?.xRatio}:${point("first_slot")?.yRatio}"
    )

    private suspend fun failAndStop(message: String) {
        logs.add(message, LogLevel.ERROR)
        transition(AutomationState.ERROR, message)
        stopToken.stop()
        onTerminalStop()
    }

    private fun transition(state: AutomationState, message: String) {
        stateMachine.force(state)
        publish(message)
    }

    private fun publish(message: String? = null) {
        val previous = currentMessage
        if (message != null) currentMessage = message
        onSnapshot(
            StateSnapshot(
                state = stateMachine.state,
                enteredAtMs = stateMachine.enteredAtMs,
                message = message ?: previous,
                refreshCount = refreshCount,
                successCount = successCount
            )
        )
    }

    private var currentMessage: String = "Đã dừng"

    private fun notifySuccess() {
        if (settings.soundOnSuccess) {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply {
                startTone(ToneGenerator.TONE_PROP_ACK, 500)
                release()
            }
        }
        if (settings.vibrateOnSuccess) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
