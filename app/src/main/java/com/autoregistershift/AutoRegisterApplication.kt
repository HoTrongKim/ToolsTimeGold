package com.autoregistershift

import android.app.Application
import com.autoregistershift.automation.AutomationController

class AutoRegisterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AutomationController.initialize(this)
    }
}
