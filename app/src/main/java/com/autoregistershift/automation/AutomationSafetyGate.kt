package com.autoregistershift.automation

object AutomationSafetyGate {
    fun canInteract(
        currentPackage: String?,
        targetPackage: String,
        deviceReady: Boolean,
        prohibitedStepDetected: Boolean
    ): Boolean = targetPackage.isNotBlank() &&
        currentPackage == targetPackage &&
        deviceReady &&
        !prohibitedStepDetected
}
