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
import com.autoregistershift.model.RefreshSpeedPreset
import com.autoregistershift.model.ShiftAttemptStatus
import com.autoregistershift.model.ShiftInfo
import com.autoregistershift.service.AutoRegisterAccessibilityService
import com.autoregistershift.service.FloatingOverlayService
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
    @Volatile private var refreshIntervalMs = settings.refreshIntervalMs
    @Volatile private var waitAfterSwipeMs = settings.waitAfterSwipeMs
    @Volatile private var maxRefreshesPerMinute = settings.maxRefreshesPerMinute
    @Volatile private var clickDebounceMs = settings.clickDebounceMs
    @Volatile private var refreshSwipeDurationMs = settings.refreshSwipeDurationMs
    private val clickLimiter = RateLimiter(limit = { settings.maxClicksPerMinute })
    private val refreshLimiter = RateLimiter(limit = { maxRefreshesPerMinute })
    private var refreshCount = 0
    private var successCount = 0
    private var unknownScreenCount = 0
    private var lastClickAt = 0L
    private val startedAt = System.currentTimeMillis()

    val isPaused: Boolean get() = paused.get()

    fun updateRefreshSpeed(preset: RefreshSpeedPreset) {
        refreshIntervalMs = preset.refreshIntervalMs
        waitAfterSwipeMs = preset.waitAfterSwipeMs
        maxRefreshesPerMinute = preset.maxRefreshesPerMinute
        clickDebounceMs = preset.clickDebounceMs
        refreshSwipeDurationMs = preset.refreshSwipeDurationMs
    }

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

                val (_, height) = service.displaySize()
                var slotRoot = root
                var detected = finder.detectShifts(slotRoot, height)
                var selected = detected.firstOrNull {
                    !history.shouldSkip(it.shift.identifier, settings.shiftCooldownMs)
                }
                val fallbackEnabled = point("first_slot")?.enabled == true
                when (PreRefreshDecisionPolicy.choose(
                    loading = finder.hasLoading(slotRoot, settings.loadingTexts),
                    detectedSlot = selected != null,
                    visibleTimeRange = finder.hasTimeRange(slotRoot),
                    fallbackEnabled = fallbackEnabled
                )) {
                    PreRefreshDecision.WAIT_FOR_CURRENT_LOADING -> {
                        waitForData(
                            service,
                            service.contentChangeSequence(settings.targetPackage)
                        )
                    }
                    PreRefreshDecision.USE_VISIBLE_SLOT -> {
                        logs.add("Ca đã hiển thị; bỏ qua vuốt làm mới")
                    }
                    PreRefreshDecision.REFRESH -> {
                        val contentSequenceBeforeRefresh =
                            service.contentChangeSequence(settings.targetPackage)
                        if (!refresh(service)) {
                            delay(refreshIntervalMs)
                            continue
                        }
                        waitForData(service, contentSequenceBeforeRefresh)
                    }
                }
                if (!canAct(service)) continue

                transition(AutomationState.CHECKING_SLOTS, "Đang tìm ca")
                slotRoot = service.currentRoot()
                if (finder.hasLoading(slotRoot, settings.loadingTexts)) {
                    logs.add("Danh sách vẫn đang tải; chưa thao tác")
                    delay(refreshIntervalMs)
                    continue
                }
                detected = finder.detectShifts(slotRoot, height)
                selected = detected.firstOrNull {
                    !history.shouldSkip(it.shift.identifier, settings.shiftCooldownMs)
                }
                val selection = SlotSelectionPolicy.choose(
                    hasDetectedSlot = selected != null,
                    hasTimeRangeSignal = finder.hasTimeRange(slotRoot),
                    fallbackEnabled = fallbackEnabled
                )
                if (selection == SlotSelection.WAIT_FOR_NEXT_REFRESH) {
                    val message = if (finder.containsAny(slotRoot, settings.noSlotTexts)) {
                        "Chưa có ca"
                    } else {
                        "Chưa phát hiện thẻ ca; tiếp tục làm mới"
                    }
                    logs.add(message)
                    delay(refreshIntervalMs)
                    continue
                }
                val shift = selected?.shift ?: fallbackShift(slotRoot)
                if (history.shouldSkip(shift.identifier, settings.shiftCooldownMs)) {
                    logs.add("Ca vừa được xử lý, đang trong thời gian cooldown")
                    delay(refreshIntervalMs)
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
                val registerPointEnabled = point("register")?.enabled == true
                if (registerNode == null && (!isDetail(service) || !registerPointEnabled)) {
                    logs.add("Không tìm thấy nút đăng ký", LogLevel.ERROR)
                    history.record(shift.identifier, ShiftAttemptStatus.SKIPPED)
                    returnToList(service)
                    continue
                }

                transition(AutomationState.REGISTERING, "Đang đăng ký")
                if (!clickRegisterButton(service, registerNode)) {
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
                delay(refreshIntervalMs)
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
            refreshSwipeDurationMs
        )
        if (ok) {
            refreshCount++
            logs.add("Đang làm mới danh sách")
            publish()
        }
        return ok
    }

    private suspend fun waitForData(
        service: AutoRegisterAccessibilityService,
        contentSequenceBeforeRefresh: Long
    ) {
        transition(AutomationState.WAITING_FOR_DATA, "Đang chờ dữ liệu")
        val timeout = max(waitAfterSwipeMs, 5_000)
        val started = System.currentTimeMillis()
        val settlePolicy = RefreshContentSettlePolicy(waitAfterSwipeMs)
        while (stopToken.isActive && System.currentTimeMillis() - started < timeout) {
            waitWhilePaused()
            if (!canAct(service)) return
            val now = System.currentTimeMillis()
            val root = service.currentRoot()
            val lastContentEventAt = service.lastContentEventAtMs(settings.targetPackage)
            val quietForMs = if (lastContentEventAt == 0L) {
                Long.MAX_VALUE
            } else {
                (now - lastContentEventAt).coerceAtLeast(0)
            }
            if (settlePolicy.isReady(
                    elapsedMs = now - started,
                    loading = finder.hasLoading(root, settings.loadingTexts),
                    contentChanged = service.contentChangeSequence(settings.targetPackage) >
                        contentSequenceBeforeRefresh,
                    quietForMs = quietForMs,
                    priorityContentVisible = finder.hasTimeRange(root)
                )
            ) {
                return
            }
            delay(40)
        }
    }

    private suspend fun openSlot(
        service: AutoRegisterAccessibilityService,
        detected: DetectedShift?
    ): Boolean {
        transition(AutomationState.OPENING_SLOT, "Đang mở ca")
        if (detected != null) {
            if (safeClickNode(service, detected.node)) {
                if (waitForDetail(service)) return true
            }
            if (safeClickScreenPoint(service, detected.bounds.centerX().toFloat(), detected.bounds.centerY().toFloat())) {
                if (waitForDetail(service)) return true
            }
        }
        for (id in listOf("first_slot", "slot_fallback")) {
            val coordinate = point(id) ?: continue
            if (!coordinate.enabled || !safeClickRatio(service, coordinate)) continue
            if (waitForDetail(service)) return true
        }
        return false
    }

    private suspend fun waitForDetail(service: AutoRegisterAccessibilityService): Boolean {
        val started = System.currentTimeMillis()
        val timeoutMs = settings.waitAfterOpenSlotMs.coerceAtLeast(500)
        while (stopToken.isActive && System.currentTimeMillis() - started < timeoutMs) {
            waitWhilePaused()
            if (!canAct(service, allowNonSchedule = true)) return false
            if (isDetail(service)) return true
            delay(50)
        }
        return isDetail(service)
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
            val started = System.currentTimeMillis()
            while (stopToken.isActive && System.currentTimeMillis() - started < 600) {
                finder.findByTexts(service.currentRoot(), settings.registerButtonTexts)?.let { return it }
                delay(50)
            }
        }
        return finder.findByTexts(service.currentRoot(), settings.registerButtonTexts)
    }

    private suspend fun clickRegisterButton(
        service: AutoRegisterAccessibilityService,
        node: android.view.accessibility.AccessibilityNodeInfo?
    ): Boolean {
        if (node != null) {
            if (safeClickNode(service, node)) {
                logs.add("Đã click nút đăng ký bằng Accessibility")
                return true
            }
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            if (!bounds.isEmpty &&
                safeClickScreenPoint(service, bounds.centerX().toFloat(), bounds.centerY().toFloat())
            ) {
                logs.add("Đã click tâm vùng chữ đăng ký")
                return true
            }
        }
        val fallback = point("register")
        if (fallback?.enabled == true && isDetail(service) && safeClickRatio(service, fallback)) {
            logs.add("Đã click điểm đăng ký dự phòng")
            return true
        }
        return false
    }

    private suspend fun awaitRegistrationResult(
        service: AutoRegisterAccessibilityService
    ): RegistrationResult {
        transition(AutomationState.CHECKING_RESULT, "Đang kiểm tra kết quả")
        val networkRetry = RetryPolicy(settings.maxRetry)
        val acknowledgement = RegistrationAcknowledgementPolicy(settings.maxRetry)
        val started = System.currentTimeMillis()
        acknowledgement.recordInitialClick(started)
        var lastPublishedSecond = -1L
        while (stopToken.isActive && System.currentTimeMillis() - started < settings.registrationTimeoutMs) {
            waitWhilePaused()
            if (!canAct(service, allowNonSchedule = true)) return RegistrationResult.UNKNOWN
            val root = service.currentRoot()
            when (val result = resultDetector.detect(root, settings)) {
                RegistrationResult.NETWORK_ERROR -> {
                    if (!networkRetry.recordRetry()) return result
                    logs.add(
                        "Lỗi mạng, thử lại ${networkRetry.retryCount}/${settings.maxRetry}",
                        LogLevel.ERROR
                    )
                    delay(refreshIntervalMs)
                    val button = finder.findByTexts(service.currentRoot(), settings.registerButtonTexts)
                    if (button != null && isDetail(service)) {
                        retryRegisterButton(service)
                    }
                }
                RegistrationResult.UNKNOWN -> {
                    val now = System.currentTimeMillis()
                    val elapsed = now - started
                    if (elapsed >= 500 && isScheduleScreen(root)) {
                        logs.add("Ứng dụng đã tự quay lại danh sách sau khi đăng ký")
                        return RegistrationResult.SUCCESS
                    }

                    val registerButton = finder.findByTexts(root, settings.registerButtonTexts)
                    if (acknowledgement.shouldRetry(
                            nowMs = now,
                            registerButtonVisible = registerButton != null && isDetail(service),
                            loading = finder.hasLoading(root, settings.loadingTexts)
                        ) &&
                        acknowledgement.recordRetry(now)
                    ) {
                        val retryNumber = acknowledgement.retryCount
                        transition(
                            AutomationState.REGISTERING,
                            "Nút chưa phản hồi • thử lại $retryNumber/${acknowledgement.maximumRetries}"
                        )
                        logs.add(
                            "Nút đăng ký vẫn còn; bấm xác nhận lại " +
                                "$retryNumber/${acknowledgement.maximumRetries}"
                        )
                        val clicked = retryRegisterButton(service)
                        transition(
                            AutomationState.CHECKING_RESULT,
                            if (clicked) {
                                "Đã bấm lại • đang chờ xác nhận"
                            } else {
                                "Chưa thể bấm lại • tiếp tục kiểm tra"
                            }
                        )
                        if (!clicked) {
                            logs.add("Lần bấm xác nhận lại không thực hiện được", LogLevel.ERROR)
                        }
                        continue
                    }

                    val elapsedSecond = elapsed / 1_000
                    if (elapsedSecond != lastPublishedSecond) {
                        lastPublishedSecond = elapsedSecond
                        publish("Đang chờ phản hồi • ${elapsedSecond + 1}s")
                    }
                    delay(80)
                }
                else -> return result
            }
        }
        publish("Hết thời gian chờ kết quả đăng ký")
        return RegistrationResult.UNKNOWN
    }

    private suspend fun retryRegisterButton(
        service: AutoRegisterAccessibilityService
    ): Boolean {
        if (!isDetail(service) ||
            resultDetector.detect(service.currentRoot(), settings) != RegistrationResult.UNKNOWN
        ) {
            return false
        }
        val node = finder.findByTexts(service.currentRoot(), settings.registerButtonTexts)
        if (node != null) {
            val target = finder.clickable(node) ?: node
            val bounds = android.graphics.Rect().also(target::getBoundsInScreen)
            if (!bounds.isEmpty && isDetail(service) &&
                safeClickScreenPoint(
                    service,
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat()
                )
            ) {
                logs.add("Đã bấm lại bằng gesture tại nút đăng ký")
                return true
            }
            if (isDetail(service) && safeClickNode(service, node)) {
                logs.add("Đã bấm lại nút đăng ký bằng Accessibility")
                return true
            }
        }
        val fallback = point("register")
        if (fallback?.enabled == true && isDetail(service) && safeClickRatio(service, fallback)) {
            logs.add("Đã bấm lại điểm đăng ký dự phòng")
            return true
        }
        return false
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
        FloatingOverlayService.setAutomatedGesturePassthrough(true)
        return try {
            delay(24)
            service.clickRatio(point.xRatio, point.yRatio, settings.targetPackage).also {
                if (it) lastClickAt = System.currentTimeMillis()
            }
        } finally {
            FloatingOverlayService.setAutomatedGesturePassthrough(false)
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
        FloatingOverlayService.setAutomatedGesturePassthrough(true)
        return try {
            delay(24)
            GestureController(service).click(x, y).also {
                if (it) lastClickAt = System.currentTimeMillis()
            }
        } finally {
            FloatingOverlayService.setAutomatedGesturePassthrough(false)
        }
    }

    private suspend fun debounceClick() {
        val remaining = clickDebounceMs - (System.currentTimeMillis() - lastClickAt)
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
        startTime = finder.visibleNodes(root).asSequence()
            .flatMap { com.autoregistershift.util.TimeRegex.findAll(finder.nodeText(it)).asSequence() }
            .firstOrNull() ?: "unknown",
        endTime = finder.visibleNodes(root).asSequence()
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
