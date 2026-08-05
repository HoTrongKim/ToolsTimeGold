package com.autoregistershift.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.autoregistershift.service.AutoRegisterAccessibilityService
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class GestureController(
    private val service: AutoRegisterAccessibilityService
) {
    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean =
        service.withGestureLock {
            val target = NodeFinder().clickable(node) ?: return@withGestureLock false
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

    suspend fun click(x: Float, y: Float): Boolean = service.withGestureLock {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 1, 50)
    }

    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = service.withGestureLock {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        dispatch(path, 0, durationMs.coerceIn(100, 2_000))
    }

    fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    private suspend fun dispatch(path: Path, delayMs: Long, durationMs: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, delayMs, durationMs))
                .build()
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
}
