package com.autoregistershift.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.autoregistershift.model.ShiftInfo
import com.autoregistershift.util.TimeRegex
import java.time.LocalDate

class NodeFinder {
    fun allNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return result
    }

    fun nodeText(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ").trim()

    fun subtreeText(node: AccessibilityNodeInfo, maxNodes: Int = 40): String =
        allNodes(node).take(maxNodes).joinToString(" ") { nodeText(it) }.trim()

    fun containsAny(root: AccessibilityNodeInfo?, candidates: List<String>): Boolean {
        if (candidates.none(String::isNotBlank)) return false
        return allNodes(root).any { node ->
            val text = nodeText(node)
            candidates.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
        }
    }

    fun findByTexts(root: AccessibilityNodeInfo?, candidates: List<String>): AccessibilityNodeInfo? =
        allNodes(root).firstOrNull { node ->
            val text = nodeText(node)
            candidates.any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
        }

    fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        repeat(6) {
            if (current?.isClickable == true && current.isEnabled) return current
            current = current?.parent
        }
        return null
    }

    fun hasLoading(root: AccessibilityNodeInfo?, loadingTexts: List<String>): Boolean =
        allNodes(root).any {
            it.className?.toString()?.contains("ProgressBar", ignoreCase = true) == true ||
                loadingTexts.any { marker -> nodeText(it).contains(marker, ignoreCase = true) }
        }

    fun hasTimeRange(root: AccessibilityNodeInfo?): Boolean =
        allNodes(root).any { TimeRegex.findAll(nodeText(it)).distinct().size >= 2 }

    fun detectShifts(root: AccessibilityNodeInfo?, screenHeight: Int): List<DetectedShift> {
        val nodes = allNodes(root)
        val candidates = nodes.mapNotNull { node ->
            val clickable = clickable(node)
            val target = clickable ?: node
            val bounds = Rect().also(target::getBoundsInScreen)
            if (bounds.isEmpty || bounds.centerY() < screenHeight * .12 || bounds.centerY() > screenHeight * .94) {
                return@mapNotNull null
            }
            val content = if (clickable != null) subtreeText(clickable) else nodeText(node)
            val times = TimeRegex.findAll(content).distinct()
            if (times.size < 2) return@mapNotNull null
            val info = ShiftInfo(
                date = LocalDate.now().toString(),
                startTime = times[0],
                endTime = times[1],
                content = content.take(180)
            )
            DetectedShift(info, target, bounds)
        }
        return candidates.distinctBy { it.shift.identifier }
    }
}

data class DetectedShift(
    val shift: ShiftInfo,
    val node: AccessibilityNodeInfo,
    val bounds: Rect
)
