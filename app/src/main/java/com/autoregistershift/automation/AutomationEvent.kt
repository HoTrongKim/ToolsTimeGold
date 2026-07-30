package com.autoregistershift.automation

sealed interface AutomationEvent {
    data object Start : AutomationEvent
    data object Pause : AutomationEvent
    data object Resume : AutomationEvent
    data object Stop : AutomationEvent
    data class UiChanged(val packageName: String) : AutomationEvent
    data class Failure(val reason: String) : AutomationEvent
}
