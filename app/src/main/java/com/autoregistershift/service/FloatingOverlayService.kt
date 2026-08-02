package com.autoregistershift.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.autoregistershift.MainActivity
import com.autoregistershift.automation.AutomationController
import com.autoregistershift.automation.AutomationState
import com.autoregistershift.automation.StateSnapshot
import com.autoregistershift.data.SettingsRepository
import com.autoregistershift.model.AppSettings
import com.autoregistershift.model.RefreshSpeedPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var currentSettings = AppSettings()
    private var currentSnapshot = StateSnapshot()

    private lateinit var statusText: TextView
    private lateinit var stateText: TextView
    private lateinit var statusDot: TextView
    private lateinit var refreshMetric: TextView
    private lateinit var successMetric: TextView
    private lateinit var fullMetric: TextView
    private lateinit var continuousButton: TextView
    private lateinit var compactSummary: TextView

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        activeInstance = this
        scope.launch {
            val repository = SettingsRepository(applicationContext)
            currentSettings = repository.settings.first()
            createOverlay(currentSettings)
            launch {
                AutomationController.state.collectLatest(::renderSnapshot)
            }
            launch {
                repository.settings.collectLatest {
                    currentSettings = it
                    renderContinuousMode(it.continuousMode)
                    updateKeepScreenFlag(it.keepScreenOn || it.continuousMode)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        overlayParams = null
        if (activeInstance === this) activeInstance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun createOverlay(appSettings: AppSettings) {
        if (overlay != null) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            minimumWidth = dp(304)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            elevation = dp(18).toFloat()
            background = rounded(PANEL_COLOR, 20f, BORDER_COLOR, 1)
        }

        val expandedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(1), dp(2), dp(7))
        }
        val dragHandle = label("⋮⋮", 21f, TEXT_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(6), dp(8), dp(6))
        }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("AUTO SHIFT", 15f, Color.WHITE).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = .08f
            })
            addView(label("TRỢ LÝ ĐĂNG KÝ CA", 9f, TEXT_MUTED).apply { letterSpacing = .05f })
        }
        compactSummary = label("", 11f, TEXT_MUTED).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val modeBadge = label("24/7", 10f, ACCENT_GREEN).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(Color.rgb(18, 66, 52), 10f)
        }
        val collapseButton = label("—", 20f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(3), dp(7), dp(5))
            background = rounded(Color.rgb(35, 47, 68), 10f)
        }
        header.addView(dragHandle)
        header.addView(titleBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(compactSummary, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(modeBadge)
        header.addView(collapseButton)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(CARD_COLOR, 15f, CARD_BORDER, 1)
        }
        statusDot = label("●", 18f, TEXT_MUTED).apply { gravity = Gravity.CENTER }
        val statusCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), 0, 0, 0)
        }
        statusText = label("Đã dừng", 13f, Color.WHITE).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
        }
        stateText = label("SẴN SÀNG", 9f, TEXT_MUTED).apply { letterSpacing = .06f }
        statusCopy.addView(statusText)
        statusCopy.addView(stateText)
        statusCard.addView(statusDot)
        statusCard.addView(statusCopy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        refreshMetric = metric("0", "LÀM MỚI")
        successMetric = metric("0", "THÀNH CÔNG")
        val selectedPreset = RefreshSpeedPreset.entries.firstOrNull { it.matches(appSettings) }
            ?: RefreshSpeedPreset.BALANCED
        fullMetric = metric("0", "CA ĐÃ ĐẶT")
        metrics.addView(refreshMetric, weightedMetricParams())
        metrics.addView(successMetric, weightedMetricParams(dp(5)))
        metrics.addView(fullMetric, weightedMetricParams(dp(5)))

        continuousButton = actionButton("24/7  •  ĐANG BẬT", Color.rgb(18, 104, 73)) {
            AutomationController.setContinuousMode(!currentSettings.continuousMode)
        }

        val speedSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        lateinit var speedOptions: LinearLayout
        val speedButton = actionButton(
            "Tốc độ  •  ${selectedPreset.label}   ▾",
            Color.rgb(42, 58, 89)
        ) {
            speedOptions.visibility = if (speedOptions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            panel.post { updateAndClamp(panel) }
        }
        speedOptions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
            RefreshSpeedPreset.entries.forEach { preset ->
                addView(actionButton(preset.label, Color.rgb(31, 43, 66)) {
                    AutomationController.setRefreshSpeed(preset)
                    speedButton.text = "Tốc độ  •  ${preset.label}   ▾"
                    speedOptions.visibility = View.GONE
                    panel.post { updateAndClamp(panel) }
                }, fullWidthParams(dp(42), topMargin = dp(3)))
            }
        }
        speedSection.addView(speedButton, fullWidthParams(dp(44)))
        speedSection.addView(speedOptions)

        val primaryActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(actionButton("▶  Chạy", Color.rgb(17, 137, 86)) {
                AutomationController.startOrResume(applicationContext)
            }, weightedButtonParams())
            addView(actionButton("Ⅱ  Tạm dừng", Color.rgb(177, 112, 22)) {
                AutomationController.pause()
            }, weightedButtonParams(dp(5)))
            addView(actionButton("■  Dừng", Color.rgb(178, 54, 64)) {
                AutomationController.stop()
            }, weightedButtonParams())
        }

        val utilities = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
            addView(actionButton("Mở ứng dụng", Color.rgb(34, 48, 72)) {
                openTargetApplication()
            }, weightedButtonParams())
            addView(actionButton("Xem nhật ký", Color.rgb(34, 48, 72)) {
                startActivity(
                    Intent(applicationContext, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_DESTINATION, "logs")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }, weightedButtonParams(dp(5)))
        }
        val bankingModeButton = actionButton(
            "Chế độ ngân hàng  •  Tắt toàn bộ tool",
            Color.rgb(78, 57, 112)
        ) {
            AutomationController.enterBankingMode(applicationContext)
        }

        expandedContent.addView(statusCard)
        expandedContent.addView(metrics)
        expandedContent.addView(continuousButton, fullWidthParams(dp(43)))
        expandedContent.addView(speedSection)
        expandedContent.addView(primaryActions)
        expandedContent.addView(utilities)
        expandedContent.addView(bankingModeButton, fullWidthParams(dp(43), topMargin = dp(7)))
        panel.addView(header)
        panel.addView(expandedContent)

        var expanded = true
        collapseButton.setOnClickListener {
            expanded = !expanded
            expandedContent.visibility = if (expanded) View.VISIBLE else View.GONE
            titleBlock.visibility = if (expanded) View.VISIBLE else View.GONE
            compactSummary.visibility = if (expanded) View.GONE else View.VISIBLE
            modeBadge.visibility = if (expanded) View.VISIBLE else View.GONE
            collapseButton.text = if (expanded) "—" else "+"
            panel.minimumWidth = if (expanded) dp(304) else dp(244)
            panel.post { updateAndClamp(panel) }
        }

        val position = getSharedPreferences(POSITION_PREFERENCES, MODE_PRIVATE)
        val (screenWidth, screenHeight) = screenSize()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (appSettings.keepScreenOn || appSettings.continuousMode) {
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                } else {
                    0
                }),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (position.getFloat(KEY_X_RATIO, .03f) * screenWidth).toInt()
            y = (position.getFloat(KEY_Y_RATIO, .10f) * screenHeight).toInt()
        }
        overlayParams = params
        addDragBehavior(dragHandle, params, panel)
        windowManager.addView(panel, params)
        overlay = panel
        renderContinuousMode(appSettings.continuousMode)
        renderSnapshot(AutomationController.state.value)
        panel.post { updateAndClamp(panel) }
    }

    private fun renderSnapshot(snapshot: StateSnapshot) {
        if (!::statusText.isInitialized) return
        currentSnapshot = snapshot
        statusText.text = snapshot.message
        val isFullResult = snapshot.message.contains("được đặt hết", ignoreCase = true) ||
            snapshot.message.contains("ca cũ", ignoreCase = true)
        stateText.text = when {
            isFullResult -> "BỎ QUA CA CŨ • BẮT BUỘC LÀM MỚI"
            snapshot.message.contains("thành công", ignoreCase = true) -> "ĐĂNG KÝ THÀNH CÔNG"
            else -> stateLabel(snapshot.state)
        }
        refreshMetric.text = "${snapshot.refreshCount}\nLÀM MỚI"
        successMetric.text = "${snapshot.successCount}\nTHÀNH CÔNG"
        fullMetric.text = "${snapshot.fullCount}\nCA ĐÃ ĐẶT"
        compactSummary.text =
            "AUTO  ↻${snapshot.refreshCount}  ✓${snapshot.successCount}  ⊘${snapshot.fullCount}"
        statusDot.setTextColor(if (isFullResult) ACCENT_AMBER else stateColor(snapshot.state))
    }

    private fun renderContinuousMode(enabled: Boolean) {
        if (!::continuousButton.isInitialized) return
        continuousButton.text = if (enabled) {
            "24/7  •  ĐANG BẬT  •  TỰ PHỤC HỒI"
        } else {
            "24/7  •  ĐANG TẮT"
        }
        continuousButton.background = rounded(
            if (enabled) Color.rgb(18, 104, 73) else Color.rgb(55, 65, 82),
            13f
        )
    }

    private fun openTargetApplication() {
        val target = currentSettings.targetPackage
        val launchIntent = target.takeIf(String::isNotBlank)?.let(packageManager::getLaunchIntentForPackage)
        if (launchIntent == null) {
            statusText.text = "Không tìm thấy ứng dụng mục tiêu"
            return
        }
        startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun stateLabel(state: AutomationState): String = when (state) {
        AutomationState.IDLE, AutomationState.STOPPED -> "SẴN SÀNG"
        AutomationState.PAUSED -> "ĐANG TẠM DỪNG"
        AutomationState.ERROR -> "CẦN KIỂM TRA"
        AutomationState.WAITING_FOR_TARGET_APP -> "CHỜ ỨNG DỤNG MỤC TIÊU"
        AutomationState.WAITING_FOR_SCHEDULE_SCREEN -> "CHỜ MÀN HÌNH DANH SÁCH"
        AutomationState.REFRESHING, AutomationState.WAITING_FOR_DATA -> "ĐANG LÀM MỚI"
        AutomationState.CHECKING_SLOTS -> "ĐANG QUÉT CA"
        AutomationState.OPENING_SLOT, AutomationState.WAITING_FOR_DETAIL -> "ĐANG MỞ CA"
        AutomationState.FINDING_REGISTER_BUTTON -> "ĐANG TÌM NÚT ĐĂNG KÝ"
        AutomationState.REGISTERING -> "ĐANG ĐĂNG KÝ"
        AutomationState.CHECKING_RESULT -> "ĐANG KIỂM TRA KẾT QUẢ"
        AutomationState.RETURNING_TO_LIST -> "ĐANG QUAY LẠI DANH SÁCH"
    }

    private fun stateColor(state: AutomationState): Int = when (state) {
        AutomationState.ERROR -> ACCENT_RED
        AutomationState.PAUSED -> ACCENT_AMBER
        AutomationState.IDLE, AutomationState.STOPPED -> TEXT_MUTED
        AutomationState.CHECKING_RESULT -> ACCENT_BLUE
        else -> ACCENT_GREEN
    }

    private fun metric(value: String, caption: String) = label("$value\n$caption", 12f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setLineSpacing(0f, .92f)
        setPadding(dp(4), dp(7), dp(4), dp(7))
        background = rounded(Color.rgb(24, 35, 54), 12f)
    }

    private fun actionButton(label: String, color: Int, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = rounded(color, 13f)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null && strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun weightedMetricParams(startMargin: Int = 0) = LinearLayout.LayoutParams(0, dp(55), 1f).apply {
        marginStart = startMargin
    }

    private fun weightedButtonParams(startMargin: Int = 0) = LinearLayout.LayoutParams(0, dp(47), 1f).apply {
        marginStart = startMargin
    }

    private fun fullWidthParams(height: Int, topMargin: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        height
    ).apply { this.topMargin = topMargin }

    private fun addDragBehavior(handle: View, params: WindowManager.LayoutParams, panel: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    clampPosition(params, panel)
                    windowManager.updateViewLayout(panel, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampPosition(params, panel)
                    savePosition(params)
                    true
                }
                else -> true
            }
        }
    }

    private fun updateAndClamp(panel: View) {
        val params = overlayParams ?: return
        clampPosition(params, panel)
        runCatching { windowManager.updateViewLayout(panel, params) }
    }

    private fun clampPosition(params: WindowManager.LayoutParams, panel: View) {
        val (screenWidth, screenHeight) = screenSize()
        params.x = params.x.coerceIn(0, (screenWidth - panel.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - panel.height).coerceAtLeast(0))
    }

    private fun savePosition(params: WindowManager.LayoutParams) {
        val (screenWidth, screenHeight) = screenSize()
        getSharedPreferences(POSITION_PREFERENCES, MODE_PRIVATE).edit()
            .putFloat(KEY_X_RATIO, params.x.toFloat() / screenWidth.coerceAtLeast(1))
            .putFloat(KEY_Y_RATIO, params.y.toFloat() / screenHeight.coerceAtLeast(1))
            .apply()
    }

    private fun updateKeepScreenFlag(enabled: Boolean) {
        val view = overlay ?: return
        val params = overlayParams ?: return
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var activeInstance: FloatingOverlayService? = null

        private const val POSITION_PREFERENCES = "floating_overlay_position"
        private const val KEY_X_RATIO = "x_ratio"
        private const val KEY_Y_RATIO = "y_ratio"
        private val PANEL_COLOR = Color.rgb(14, 22, 36)
        private val CARD_COLOR = Color.rgb(20, 31, 50)
        private val BORDER_COLOR = Color.rgb(52, 77, 116)
        private val CARD_BORDER = Color.rgb(39, 57, 87)
        private val TEXT_MUTED = Color.rgb(151, 166, 190)
        private val ACCENT_GREEN = Color.rgb(64, 220, 151)
        private val ACCENT_BLUE = Color.rgb(91, 154, 255)
        private val ACCENT_AMBER = Color.rgb(245, 178, 65)
        private val ACCENT_RED = Color.rgb(255, 95, 109)

        suspend fun setAutomatedGesturePassthrough(enabled: Boolean) {
            activeInstance?.updateTouchPassthrough(enabled)
        }
    }

    private suspend fun updateTouchPassthrough(enabled: Boolean) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            val view = overlay ?: return@withContext
            val params = overlayParams ?: return@withContext
            params.flags = if (enabled) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }
}
