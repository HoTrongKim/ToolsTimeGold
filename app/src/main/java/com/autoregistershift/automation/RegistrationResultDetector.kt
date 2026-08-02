package com.autoregistershift.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.autoregistershift.model.AppSettings

enum class RegistrationResult { SUCCESS, FULL, NETWORK_ERROR, UNKNOWN }

class RegistrationResultDetector(private val nodeFinder: NodeFinder = NodeFinder()) {
    fun detect(
        root: AccessibilityNodeInfo?,
        settings: AppSettings,
        transientEventText: String = ""
    ): RegistrationResult {
        val eventResult = detectText(transientEventText, settings)
        return when {
            nodeFinder.containsAny(root, settings.successTexts) -> RegistrationResult.SUCCESS
            nodeFinder.containsAny(root, settings.fullTexts) -> RegistrationResult.FULL
            nodeFinder.containsAny(root, settings.networkErrorTexts) -> RegistrationResult.NETWORK_ERROR
            eventResult != RegistrationResult.UNKNOWN -> eventResult
            else -> RegistrationResult.UNKNOWN
        }
    }

    fun detectText(text: String, settings: AppSettings): RegistrationResult = when {
        settings.successTexts.any { it.isNotBlank() && text.contains(it, ignoreCase = true) } ->
            RegistrationResult.SUCCESS
        settings.fullTexts.any { it.isNotBlank() && text.contains(it, ignoreCase = true) } ->
            RegistrationResult.FULL
        settings.networkErrorTexts.any { it.isNotBlank() && text.contains(it, ignoreCase = true) } ->
            RegistrationResult.NETWORK_ERROR
        else -> RegistrationResult.UNKNOWN
    }
}
