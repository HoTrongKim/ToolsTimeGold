package com.autoregistershift.service

import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.autoregistershift.automation.AutomationController
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
    private lateinit var status: TextView
    private lateinit var counters: TextView

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        activeInstance = this
        scope.launch {
            val appSettings = SettingsRepository(applicationContext).settings.first()
            createOverlay(appSettings)
            AutomationController.state.collectLatest { snapshot ->
                status.text = snapshot.message
                counters.text = "↻ ${snapshot.refreshCount}   ✓ ${snapshot.successCount}"
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
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            minimumWidth = (270 * density).toInt()
            setPadding(
                (10 * density).toInt(),
                (8 * density).toInt(),
                (10 * density).toInt(),
                (10 * density).toInt()
            )
            elevation = 12 * density
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(Color.argb(245, 29, 34, 49))
                setStroke((2 * density).toInt(), Color.rgb(91, 119, 230))
            }
        }
        val title = TextView(this).apply {
            text = "↕  AUTO SHIFT  •  KÉO ĐỂ DI CHUYỂN"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8, (10 * density).toInt(), 8, (10 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.rgb(49, 75, 165))
            }
        }
        status = TextView(this).apply {
            text = "Đã dừng"
            setTextColor(Color.rgb(215, 225, 255))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(4, (10 * density).toInt(), 4, (3 * density).toInt())
        }
        counters = TextView(this).apply {
            text = "↻ 0   ✓ 0"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(4, (2 * density).toInt(), 4, (8 * density).toInt())
        }
        val presets = RefreshSpeedPreset.entries
        var selectedPreset = presets.firstOrNull { it.matches(appSettings) }
            ?: RefreshSpeedPreset.BALANCED
        var windowParams: WindowManager.LayoutParams? = null
        lateinit var speedOptions: LinearLayout
        lateinit var actions: LinearLayout
        val speedButton = Button(this).apply {
            text = "${selectedPreset.label}  ▼"
            textSize = 12f
            setTextColor(Color.WHITE)
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(Color.rgb(55, 68, 112))
            layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f)
            setOnClickListener {
                val opening = speedOptions.visibility != View.VISIBLE
                speedOptions.visibility = if (opening) View.VISIBLE else View.GONE
                actions.visibility = if (opening) View.INVISIBLE else View.VISIBLE
                repeat(actions.childCount) { index ->
                    actions.getChildAt(index).isEnabled = !opening
                }
                panel.post {
                    windowParams?.let { params ->
                        clampPosition(params, panel)
                        runCatching { windowManager.updateViewLayout(panel, params) }
                    }
                }
            }
        }
        speedOptions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            presets.forEach { preset ->
                addView(speedOptionButton(preset.label) {
                    selectedPreset = preset
                    speedButton.text = "${preset.label}  ▼"
                    speedOptions.visibility = View.GONE
                    actions.visibility = View.VISIBLE
                    repeat(actions.childCount) { index ->
                        actions.getChildAt(index).isEnabled = true
                    }
                    AutomationController.setRefreshSpeed(preset)
                    panel.post {
                        windowParams?.let { params ->
                            clampPosition(params, panel)
                            runCatching { windowManager.updateViewLayout(panel, params) }
                        }
                    }
                })
            }
        }
        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, (8 * density).toInt())
            addView(TextView(context).apply {
                text = "Tốc độ"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(4, 0, (10 * density).toInt(), 0)
            })
            addView(speedButton)
        }
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(largeButton("Chạy", Color.rgb(20, 145, 88)) {
                AutomationController.startOrResume(applicationContext)
            })
            addView(largeButton("Tạm dừng", Color.rgb(188, 125, 20)) {
                AutomationController.pause()
            })
            addView(largeButton("Dừng", Color.rgb(190, 55, 62)) {
                AutomationController.stop()
            })
        }
        panel.addView(title)
        panel.addView(status)
        panel.addView(counters)
        panel.addView(speedRow)
        panel.addView(speedOptions)
        panel.addView(actions)

        val position = getSharedPreferences(POSITION_PREFERENCES, MODE_PRIVATE)
        val (screenWidth, screenHeight) = screenSize()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (appSettings.keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (position.getFloat(KEY_X_RATIO, .03f) * screenWidth).toInt()
            y = (position.getFloat(KEY_Y_RATIO, .12f) * screenHeight).toInt()
        }
        windowParams = params
        overlayParams = params
        addDragBehavior(title, params, panel)
        windowManager.addView(panel, params)
        overlay = panel
        panel.post {
            clampPosition(params, panel)
            runCatching { windowManager.updateViewLayout(panel, params) }
        }
    }

    private fun largeButton(label: String, color: Int, action: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minimumHeight = (50 * resources.displayMetrics.density).toInt()
            backgroundTintList = ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(
                0,
                (52 * resources.displayMetrics.density).toInt(),
                1f
            ).apply {
                val margin = (3 * resources.displayMetrics.density).toInt()
                setMargins(margin, 0, margin, 0)
            }
            setOnClickListener { action() }
        }

    private fun speedOptionButton(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(Color.rgb(43, 52, 79))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * resources.displayMetrics.density).toInt()
            ).apply {
                val margin = (2 * resources.displayMetrics.density).toInt()
                setMargins(margin, margin, margin, margin)
            }
            setOnClickListener { action() }
        }

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

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }

    companion object {
        @Volatile
        private var activeInstance: FloatingOverlayService? = null

        private const val POSITION_PREFERENCES = "floating_overlay_position"
        private const val KEY_X_RATIO = "x_ratio"
        private const val KEY_Y_RATIO = "y_ratio"

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
