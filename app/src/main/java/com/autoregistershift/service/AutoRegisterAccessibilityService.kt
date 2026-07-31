package com.autoregistershift.service

import android.accessibilityservice.AccessibilityService
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoregistershift.automation.AutomationController
import com.autoregistershift.automation.GestureController
import com.autoregistershift.util.CoordinateConverter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AutoRegisterAccessibilityService : AccessibilityService() {
    private val gestureMutex = Mutex()
    private var lastEventAt = 0L
    private val connected = AtomicBoolean(false)
    private val contentSequences = ConcurrentHashMap<String, AtomicLong>()
    private val lastContentEventAt = ConcurrentHashMap<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected.set(true)
        instance = this
        AutomationController.onAccessibilityConnectionChanged(true)
        AutomationController.restoreIfNeeded(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type !in monitoredEvents) return
        val now = System.currentTimeMillis()
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isNotBlank() && type in contentChangeEvents) {
            contentSequences.computeIfAbsent(eventPackage) { AtomicLong() }.incrementAndGet()
            lastContentEventAt[eventPackage] = now
        }
        if (now - lastEventAt < EVENT_DEBOUNCE_MS) return
        lastEventAt = now
        AutomationController.onUiEvent(eventPackage)
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

    fun contentChangeSequence(packageName: String): Long =
        contentSequences[packageName]?.get() ?: 0L

    fun lastContentEventAtMs(packageName: String): Long =
        lastContentEventAt[packageName] ?: 0L

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
        private val contentChangeEvents = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )

        fun isEnabled(): Boolean = instance != null
    }
}
