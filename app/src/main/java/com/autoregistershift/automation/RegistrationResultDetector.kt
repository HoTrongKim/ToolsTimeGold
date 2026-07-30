package com.autoregistershift.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.autoregistershift.model.AppSettings

enum class RegistrationResult { SUCCESS, FULL, NETWORK_ERROR, UNKNOWN }

class RegistrationResultDetector(private val nodeFinder: NodeFinder = NodeFinder()) {
    fun detect(root: AccessibilityNodeInfo?, settings: AppSettings): RegistrationResult = when {
        nodeFinder.containsAny(root, settings.successTexts) -> RegistrationResult.SUCCESS
        nodeFinder.containsAny(root, settings.fullTexts) -> RegistrationResult.FULL
        nodeFinder.containsAny(root, settings.networkErrorTexts) -> RegistrationResult.NETWORK_ERROR
        else -> RegistrationResult.UNKNOWN
    }
}
