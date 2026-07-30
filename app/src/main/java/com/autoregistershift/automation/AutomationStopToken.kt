package com.autoregistershift.automation

import java.util.concurrent.atomic.AtomicBoolean

class AutomationStopToken {
    private val active = AtomicBoolean(true)
    val isActive: Boolean get() = active.get()
    fun stop() {
        active.set(false)
    }
}
