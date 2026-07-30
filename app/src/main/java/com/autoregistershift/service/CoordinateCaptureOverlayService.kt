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
import android.widget.Toast
import com.autoregistershift.data.SettingsRepository
import com.autoregistershift.util.CoordinateConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CoordinateCaptureOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var marker: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Chưa có quyền hiển thị nút nổi", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        val pointId = intent?.getStringExtra(EXTRA_POINT_ID) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        marker?.let { runCatching { windowManager.removeView(it) } }
        marker = null
        scope.launch {
            val repository = SettingsRepository(applicationContext)
            val settings = repository.settings.first()
            val point = settings.coordinates.firstOrNull { it.id == pointId } ?: return@launch stopSelf()
            showMarker(point.name, point.xRatio, point.yRatio) { xRatio, yRatio ->
                scope.launch {
                    repository.update { current ->
                        current.copy(
                            coordinates = current.coordinates.map {
                                if (it.id == pointId) it.copy(xRatio = xRatio, yRatio = yRatio) else it
                            }
                        )
                    }
                    Toast.makeText(applicationContext, "Đã lưu ${point.name}", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        marker?.let { runCatching { windowManager.removeView(it) } }
        marker = null
        scope.cancel()
        super.onDestroy()
    }

    private fun showMarker(
        name: String,
        xRatio: Float,
        yRatio: Float,
        onSave: (Float, Float) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val (screenWidth, screenHeight) = screenSize()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8, 5, 8, 5)
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.argb(230, 28, 33, 48))
                setStroke((2 * density).toInt(), Color.rgb(108, 140, 255))
            }
        }
        val label = TextView(this).apply {
            text = "$name\nKéo dấu ⊕ đến vị trí"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        val crosshair = TextView(this).apply {
            text = "⊕"
            setTextColor(Color.rgb(255, 190, 60))
            textSize = 42f
            gravity = Gravity.CENTER
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Lưu"
                textSize = 10f
                isAllCaps = false
                setOnClickListener {
                    val centerX = params.x + panel.width / 2f
                    val centerY = params.y + panel.height / 2f
                    onSave(
                        CoordinateConverter.toRatio(centerX, screenWidth),
                        CoordinateConverter.toRatio(centerY, screenHeight)
                    )
                }
            })
            addView(Button(context).apply {
                text = "Hủy"
                textSize = 10f
                isAllCaps = false
                setOnClickListener { stopSelf() }
            })
        }
        panel.addView(label)
        panel.addView(crosshair)
        panel.addView(actions)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = CoordinateConverter.toReal(xRatio, screenWidth) - (70 * density).toInt()
            y = CoordinateConverter.toReal(yRatio, screenHeight) - (70 * density).toInt()
        }
        addDragBehavior(crosshair, panel)
        windowManager.addView(panel, params)
        marker = panel
    }

    private lateinit var params: WindowManager.LayoutParams

    private fun addDragBehavior(handle: View, panel: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(panel, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun screenSize(): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }

    companion object {
        const val EXTRA_POINT_ID = "point_id"
    }
}
