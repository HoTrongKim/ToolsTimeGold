package com.autoregistershift.automation

enum class SlotSelection {
    ACCESSIBILITY_NODE,
    SEMANTIC_COORDINATE_FALLBACK,
    WAIT_FOR_NEXT_REFRESH
}

object SlotSelectionPolicy {
    fun choose(
        hasDetectedSlot: Boolean,
        hasTimeRangeSignal: Boolean,
        fallbackEnabled: Boolean
    ): SlotSelection = when {
        hasDetectedSlot -> SlotSelection.ACCESSIBILITY_NODE
        hasTimeRangeSignal && fallbackEnabled -> SlotSelection.SEMANTIC_COORDINATE_FALLBACK
        else -> SlotSelection.WAIT_FOR_NEXT_REFRESH
    }
}
