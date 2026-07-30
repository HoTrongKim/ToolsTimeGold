package com.autoregistershift.service

import android.accessibilityservice.AccessibilityService
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoregistershift.automation.AutomationController
import com.autoregistershift.automation.GestureController
import com.autoregistershift.util.CoordinateConverter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AutoRegisterAccessibilityService : AccessibilityService() {
    private val gestureMutex = Mutex()
    private var lastEventAt = 0L
    private val connected = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected.set(true)
        instance = this
        AutomationController.onAccessibilityConnectionChanged(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type !in monitoredEvents) return
        val now = System.currentTimeMillis()
        if (now - lastEventAt < EVENT_DEBOUNCE_MS) return
        lastEventAt = now
        AutomationController.onUiEvent(event.packageName?.toString().orEmpty())
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected.set(false)
        if (instance === this) instance = null
        AutomationController.onAccessibilityConnectionChanged(false)
        super.onDestroy()
    }

    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    fun currentPackage(): String? =
        rootInActiveWindow?.packageName?.toString()

    fun displaySize(): Pair<Int, Int> {
        val metrics: DisplayMetrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    suspend fun clickRatio(xRatio: Float, yRatio: Float, requiredPackage: String): Boolean {
        if (requiredPackage.isBlank() || currentPackage() != requiredPackage) return false
        val (width, height) = displaySize()
        return GestureController(this).click(
            CoordinateConverter.toReal(xRatio, width).toFloat(),
            CoordinateConverter.toReal(yRatio, height).toFloat()
        )
    }

    suspend fun <T> withGestureLock(block: suspend () -> T): T = gestureMutex.withLock { block() }

    companion object {
        @Volatile
        var instance: AutoRegisterAccessibilityService? = null
            private set

        private const val EVENT_DEBOUNCE_MS = 150L
        private val monitoredEvents = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        )

        fun isEnabled(): Boolean = instance != null
    }
}
