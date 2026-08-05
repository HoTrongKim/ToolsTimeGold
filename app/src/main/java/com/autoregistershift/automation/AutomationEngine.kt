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
import com.autoregistershift.service.FloatingOverlayService
import com.autoregistershift.util.CoordinateConverter
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    private val refreshIntervalMs = FIXED_REFRESH_INTERVAL_MS
    private val waitAfterSwipeMs = settings.waitAfterSwipeMs
    private val maxRefreshesPerMinute = settings.maxRefreshesPerMinute
    private val clickDebounceMs = settings.clickDebounceMs
    private val refreshSwipeDurationMs = settings.refreshSwipeDurationMs
    @Volatile private var continuousMode = settings.continuousMode
    private val clickLimiter = RateLimiter(limit = { settings.maxClicksPerMinute })
    private val refreshLimiter = RateLimiter(limit = { maxRefreshesPerMinute })
    private var refreshCount = 0
    private var successCount = 0
    private var fullCount = 0
    private var unknownScreenCount = 0
    private var forceRefreshBeforeNextSelection = false
    private var lastClickAt = 0L
    private var lastRefreshStartedAtMs = 0L
    private val startedAt = System.currentTimeMillis()

    val isPaused: Boolean get() = paused.get()

    fun updateContinuousMode(enabled: Boolean) {
        continuousMode = enabled
        publish(if (enabled) "Chế độ 24/7 đang bật" else "Chế độ 24/7 đã tắt")
    }

    suspend fun run() {
        transition(AutomationState.WAITING_FOR_TARGET_APP, "Tool bắt đầu")
        logs.add("Tool bắt đầu${if (continuousMode) " • chế độ 24/7" else ""}")
        try {
            while (stopToken.isActive) {
                try {
                    runAutomationLoop()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    logs.add(
                        "Lỗi runtime: ${error.message ?: error::class.java.simpleName}",
                        LogLevel.ERROR
                    )
                    transition(
                        AutomationState.ERROR,
                        "Có lỗi • ${if (continuousMode) "đang tự phục hồi" else "đã dừng"}"
                    )
                    if (!continuousMode) {
                        stopToken.stop()
                        break
                    }
                    delay(1_500)
                    logs.add("Đã tự phục hồi vòng chạy", LogLevel.ACTIVITY)
                }
            }
        } catch (_: CancellationException) {
            // Stop là tức thời: mọi delay và gesture đang chờ đều bị hủy.
        } finally {
            stopToken.stop()
        }
    }

    private suspend fun runAutomationLoop() {
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
                if (continuousMode && isDetail(service)) {
                    logs.add("Phát hiện còn ở trang chi tiết • tự quay lại danh sách")
                    returnToList(service)
                    delay(250)
                    continue
                }
                if (!isScheduleScreen(root)) {
                    unknownScreenCount++
                    transition(
                        AutomationState.WAITING_FOR_SCHEDULE_SCREEN,
                        "Đang chờ đúng màn hình đăng ký ca"
                    )
                    if (ContinuousRunPolicy.shouldStopForUnknownScreen(
                            continuousMode,
                            unknownScreenCount,
                            settings.maxUnknownScreens
                        )
                    ) {
                        failAndStop("Giao diện không xác định quá nhiều lần")
                        break
                    }
                    if (continuousMode && unknownScreenCount == settings.maxUnknownScreens) {
                        logs.addRoutine("Chế độ 24/7 đang chờ người dùng quay lại màn hình danh sách")
                    }
                    unknownScreenCount = unknownScreenCount.coerceAtMost(settings.maxUnknownScreens)
                    delay(700)
                    continue
                }
                unknownScreenCount = 0

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
                    fallbackEnabled = fallbackEnabled,
                    allDetectedSlotsProcessed = detected.isNotEmpty() && selected == null,
                    forceRefresh = forceRefreshBeforeNextSelection
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
                            waitForRefreshWindow(service, reactToVisibleSlot = false)
                            continue
                        }
                        waitForData(service, contentSequenceBeforeRefresh)
                        forceRefreshBeforeNextSelection = false
                    }
                }
                if (!canAct(service)) continue

                transition(AutomationState.CHECKING_SLOTS, "Đang tìm ca")
                slotRoot = service.currentRoot()
                if (finder.hasLoading(slotRoot, settings.loadingTexts)) {
                    logs.addRoutine("Tool đang chạy • danh sách đang tải")
                    waitForRefreshWindow(service, reactToVisibleSlot = true)
                    continue
                }
                detected = finder.detectShifts(slotRoot, height)
                selected = detected.firstOrNull {
                    !history.shouldSkip(it.shift.identifier, settings.shiftCooldownMs)
                }
                if (detected.isNotEmpty() && selected == null) {
                    transition(AutomationState.CHECKING_SLOTS, "Ca cũ đã xử lý • tiếp tục làm mới")
                    logs.addRoutine(
                        "Chỉ còn ca cũ đã thành công/đã được đặt hết • không click lại"
                    )
                    waitForRefreshWindow(service, reactToVisibleSlot = false)
                    continue
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
                    logs.addRoutine("Tool đang chạy ổn định • $message • đã làm mới $refreshCount lần")
                    waitForRefreshWindow(service, reactToVisibleSlot = true)
                    continue
                }
                val shift = selected?.shift ?: fallbackShift(slotRoot)
                if (history.shouldSkip(shift.identifier, settings.shiftCooldownMs)) {
                    forceRefreshBeforeNextSelection = true
                    transition(AutomationState.CHECKING_SLOTS, "Ca cũ đã xử lý • bắt buộc làm mới")
                    logs.addRoutine("Ca tọa độ dự phòng là ca cũ • không click lại, tiếp tục làm mới")
                    waitForRefreshWindow(service, reactToVisibleSlot = false)
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
                service.clearRecentEventTexts(settings.targetPackage)
                val registrationEventStartedAt = System.currentTimeMillis()
                if (!clickRegisterButton(service, registerNode)) {
                    logs.add("Không thể bấm nút đăng ký", LogLevel.ERROR)
                    history.record(shift.identifier, ShiftAttemptStatus.ERROR)
                    returnToList(service)
                    continue
                }
                logs.add("Đã bấm đăng ký")
                val result = awaitRegistrationResult(service, registrationEventStartedAt)
                when (result) {
                    RegistrationResult.SUCCESS -> {
                        successCount++
                        history.record(shift.identifier, ShiftAttemptStatus.SUCCESS)
                        forceRefreshBeforeNextSelection = true
                        logs.add("Đăng ký thành công", LogLevel.SUCCESS)
                        notifySuccess()
                        transition(AutomationState.CHECKING_RESULT, "Đăng ký thành công")
                        if (settings.stopAfterSuccess ||
                            ContinuousRunPolicy.maximumRegistrationsReached(
                                continuousMode,
                                successCount,
                                settings.maxRegistrations
                            )
                        ) {
                            stop()
                            onTerminalStop()
                            break
                        }
                        if (settings.autoReturnToList && !returnToList(service)) {
                            logs.add(
                                "Đăng ký thành công nhưng chưa xác nhận được màn hình danh sách; đang chờ phục hồi",
                                LogLevel.ERROR
                            )
                        }
                    }
                    RegistrationResult.FULL -> {
                        fullCount++
                        history.record(shift.identifier, ShiftAttemptStatus.FULL)
                        forceRefreshBeforeNextSelection = true
                        logs.add("Ca đã được đặt hết • đánh dấu để không đăng ký lại")
                        transition(
                            AutomationState.CHECKING_RESULT,
                            "Ca đã được đặt hết • quay lại làm mới"
                        )
                        delay(250)
                        returnToList(service)
                    }
                    RegistrationResult.NETWORK_ERROR -> {
                        history.record(shift.identifier, ShiftAttemptStatus.ERROR)
                        forceRefreshBeforeNextSelection = true
                        logs.add("Lỗi mạng sau khi đã hết số lần thử lại", LogLevel.ERROR)
                        returnToList(service)
                    }
                    RegistrationResult.UNKNOWN -> {
                        history.record(shift.identifier, ShiftAttemptStatus.ERROR)
                        forceRefreshBeforeNextSelection = true
                        logs.add("Không xác định được kết quả đăng ký", LogLevel.ERROR)
                        returnToList(service)
                    }
                }
                waitForRefreshWindow(service, reactToVisibleSlot = false)
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
            logs.addRoutine("Tool đang chạy • đã đạt giới hạn làm mới an toàn mỗi phút")
            delay(FIXED_REFRESH_INTERVAL_MS)
            return false
        }
        val start = point("refresh_start") ?: return false
        val end = point("refresh_end") ?: return false
        if (!start.enabled || !end.enabled || !canAct(service)) return false
        val (width, height) = service.displaySize()
        lastRefreshStartedAtMs = System.currentTimeMillis()
        val ok = GestureController(service).swipe(
            CoordinateConverter.toReal(start.xRatio, width).toFloat(),
            CoordinateConverter.toReal(start.yRatio, height).toFloat(),
            CoordinateConverter.toReal(end.xRatio, width).toFloat(),
            CoordinateConverter.toReal(end.yRatio, height).toFloat(),
            refreshSwipeDurationMs
        )
        if (ok) {
            refreshCount++
            logs.addRoutine(
                "Tool đang chạy ổn định • đã làm mới $refreshCount lần • thành công $successCount ca"
            )
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
            delay(15)
        }
    }

    /**
     * Giữ nhịp vuốt 500 ms nhưng không ngủ mù. Nếu Accessibility báo giao diện
     * mục tiêu vừa thay đổi và một ca chưa xử lý xuất hiện, vòng chờ kết thúc ngay.
     */
    private suspend fun waitForRefreshWindow(
        service: AutoRegisterAccessibilityService,
        reactToVisibleSlot: Boolean
    ) {
        val now = System.currentTimeMillis()
        val remainingWaitMs = RefreshCadencePolicy.remainingWaitMs(
            nowMs = now,
            lastRefreshStartedAtMs = lastRefreshStartedAtMs,
            intervalMs = refreshIntervalMs
        )
        if (remainingWaitMs <= 0) return
        val deadline = now + remainingWaitMs
        while (stopToken.isActive) {
            waitWhilePaused()
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return
            val targetEvent = withTimeoutOrNull(remaining) {
                AutomationController.uiEvents.first { it == settings.targetPackage }
            } ?: return
            if (!reactToVisibleSlot || targetEvent != settings.targetPackage ||
                service.currentPackage() != settings.targetPackage || !deviceReady()
            ) {
                continue
            }

            val root = service.currentRoot()
            if (!isScheduleScreen(root) || finder.hasLoading(root, settings.loadingTexts)) continue
            val (_, screenHeight) = service.displaySize()
            var availableSlot = false
            for (detected in finder.detectShifts(root, screenHeight)) {
                if (!history.shouldSkip(detected.shift.identifier, settings.shiftCooldownMs)) {
                    availableSlot = true
                    break
                }
            }
            if (RefreshWakePolicy.shouldWake(
                    contentEventForTarget = true,
                    targetAppActive = true,
                    scheduleScreen = true,
                    loading = false,
                    unprocessedSlotVisible = availableSlot
                )
            ) {
                transition(AutomationState.CHECKING_SLOTS, "Phát hiện ca • phản ứng ngay")
                logs.add("Accessibility báo có ca mới • bỏ qua thời gian chờ làm mới")
                return
            }
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
            delay(15)
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
                delay(15)
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
        service: AutoRegisterAccessibilityService,
        registrationEventStartedAt: Long
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
            val transientEventText = service.recentEventText(
                settings.targetPackage,
                registrationEventStartedAt
            )
            when (val result = resultDetector.detect(root, settings, transientEventText)) {
                RegistrationResult.NETWORK_ERROR -> {
                    if (!networkRetry.recordRetry()) return result
                    logs.add(
                        "Lỗi mạng, thử lại ${networkRetry.retryCount}/${settings.maxRetry}",
                        LogLevel.ERROR
                    )
                    delay(REGISTRATION_NETWORK_RETRY_MS)
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
                    delay(25)
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

    private suspend fun returnToList(service: AutoRegisterAccessibilityService): Boolean {
        transition(AutomationState.RETURNING_TO_LIST, "Đang quay lại danh sách")
        repeat(MAX_RETURN_ATTEMPTS) { attempt ->
            if (!stopToken.isActive || paused.get() || !deviceReady()) return false
            if (service.currentPackage() != settings.targetPackage) return false
            val root = service.currentRoot()
            if (isScheduleScreen(root)) {
                logs.add("Đã xác nhận quay lại danh sách ca")
                return true
            }
            if (finder.containsAny(root, settings.prohibitedTexts)) return false

            val backAccepted = GestureController(service).back()
            if (!backAccepted) {
                point("back_fallback")?.takeIf { it.enabled }?.let { safeClickRatio(service, it) }
            }

            val waitStartedAt = System.currentTimeMillis()
            while (stopToken.isActive &&
                System.currentTimeMillis() - waitStartedAt < RETURN_CONFIRM_TIMEOUT_MS
            ) {
                waitWhilePaused()
                if (service.currentPackage() != settings.targetPackage) return false
                if (isScheduleScreen(service.currentRoot())) {
                    logs.add("Đã xác nhận quay lại danh sách ca")
                    return true
                }
                delay(80)
            }
            if (attempt < MAX_RETURN_ATTEMPTS - 1) {
                logs.add("Chưa về danh sách • thử Back lại ${attempt + 2}/$MAX_RETURN_ATTEMPTS")
            }
        }
        logs.add("Không xác nhận được màn hình danh sách sau khi Back", LogLevel.ERROR)
        return false
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
        return service.currentPackage() == settings.targetPackage && ScreenKindPolicy.isDetail(
            hasDetailMarker = finder.containsAny(root, settings.detailScreenTexts),
            hasRegisterButton = finder.containsAny(root, settings.registerButtonTexts)
        )
    }

    private fun isScheduleScreen(root: android.view.accessibility.AccessibilityNodeInfo?): Boolean =
        ScreenKindPolicy.isSchedule(
            hasScheduleMarker = finder.containsAny(root, settings.scheduleScreenTexts),
            hasNoSlotMarker = finder.containsAny(root, settings.noSlotTexts),
            hasDetailMarker = finder.containsAny(root, settings.detailScreenTexts),
            hasRegisterButton = finder.containsAny(root, settings.registerButtonTexts)
        )

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

    private fun maximumRunReached(): Boolean = ContinuousRunPolicy.maximumRunReached(
        continuousMode = continuousMode,
        elapsedMs = System.currentTimeMillis() - startedAt,
        maximumMinutes = settings.maxRunMinutes
    )

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
                successCount = successCount,
                fullCount = fullCount
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

    companion object {
        private const val FIXED_REFRESH_INTERVAL_MS = 500L
        private const val REGISTRATION_NETWORK_RETRY_MS = 350L
        private const val MAX_RETURN_ATTEMPTS = 3
        private const val RETURN_CONFIRM_TIMEOUT_MS = 1_100L
    }
}
