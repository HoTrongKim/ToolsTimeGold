package com.autoregistershift.service

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FloatingOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private lateinit var status: TextView
    private lateinit var counters: TextView

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        scope.launch {
            val appSettings = SettingsRepository(applicationContext).settings.first()
            createOverlay(appSettings.keepScreenOn)
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
        scope.cancel()
        super.onDestroy()
    }

    private fun createOverlay(keepScreenOn: Boolean) {
        if (overlay != null) return
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.argb(235, 35, 40, 55))
            }
        }
        val title = TextView(this).apply {
            text = "Auto Shift"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Đã dừng"
            setTextColor(Color.rgb(215, 225, 255))
            textSize = 11f
            gravity = Gravity.CENTER
        }
        counters = TextView(this).apply {
            text = "↻ 0   ✓ 0"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(smallButton("Start") { AutomationController.startOrResume(applicationContext) })
            addView(smallButton("Pause") { AutomationController.pause() })
            addView(smallButton("Stop") { AutomationController.stop() })
        }
        panel.addView(title)
        panel.addView(status)
        panel.addView(counters)
        panel.addView(actions)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (110 * density).toInt()
        }
        addDragBehavior(title, params, panel)
        windowManager.addView(panel, params)
        overlay = panel
    }

    private fun smallButton(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 9f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(6, 0, 6, 0)
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
                    windowManager.updateViewLayout(panel, params)
                    true
                }
                else -> false
            }
        }
    }
}
