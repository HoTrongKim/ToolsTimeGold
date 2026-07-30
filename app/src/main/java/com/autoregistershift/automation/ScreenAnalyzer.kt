package com.autoregistershift.automation

import com.autoregistershift.model.AppSettings
import com.autoregistershift.util.TimeRegex

interface UiNodeSnapshot {
    val text: String
    val clickable: Boolean
    val loading: Boolean
    val children: List<UiNodeSnapshot>
}

data class ScreenAnalysis(
    val noSlots: Boolean,
    val slotRanges: List<Pair<String, String>>,
    val hasRegisterButton: Boolean,
    val success: Boolean,
    val full: Boolean,
    val networkError: Boolean,
    val loading: Boolean
)

class ScreenAnalyzer {
    fun analyze(root: UiNodeSnapshot, settings: AppSettings): ScreenAnalysis {
        val nodes = flatten(root)
        val allText = nodes.joinToString(" ") { it.text }
        val slots = nodes.filter { it.clickable }.mapNotNull { node ->
            val times = TimeRegex.findAll(subtreeText(node)).distinct()
            if (times.size >= 2) times[0] to times[1] else null
        }.distinct()
        return ScreenAnalysis(
            noSlots = settings.noSlotTexts.any { allText.contains(it, ignoreCase = true) },
            slotRanges = slots,
            hasRegisterButton = settings.registerButtonTexts.any { allText.contains(it, ignoreCase = true) },
            success = settings.successTexts.any { allText.contains(it, ignoreCase = true) },
            full = settings.fullTexts.any { allText.contains(it, ignoreCase = true) },
            networkError = settings.networkErrorTexts.any { allText.contains(it, ignoreCase = true) },
            loading = nodes.any { it.loading } ||
                settings.loadingTexts.any { allText.contains(it, ignoreCase = true) }
        )
    }

    private fun flatten(root: UiNodeSnapshot): List<UiNodeSnapshot> {
        val result = mutableListOf<UiNodeSnapshot>()
        val queue = ArrayDeque<UiNodeSnapshot>()
        queue += root
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result += node
            node.children.forEach(queue::addLast)
        }
        return result
    }

    private fun subtreeText(root: UiNodeSnapshot): String =
        flatten(root).joinToString(" ") { it.text }
}
