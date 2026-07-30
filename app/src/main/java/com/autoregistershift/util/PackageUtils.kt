package com.autoregistershift.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.autoregistershift.service.AutoRegisterAccessibilityService

object PackageUtils {
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AutoRegisterAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun canLaunch(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)
    }
}
