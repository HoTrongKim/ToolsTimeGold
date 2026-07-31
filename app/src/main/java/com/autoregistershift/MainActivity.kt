package com.autoregistershift

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.autoregistershift.ui.CoordinateSetupScreen
import com.autoregistershift.ui.LogScreen
import com.autoregistershift.ui.MainScreen
import com.autoregistershift.ui.SettingsScreen
import com.autoregistershift.ui.theme.AutoRegisterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedDestination = intent.getStringExtra(EXTRA_DESTINATION)
            ?.takeIf { it in setOf("main", "settings", "coordinates", "logs") }
            ?: "main"
        setContent {
            AutoRegisterTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = requestedDestination) {
                    composable("main") {
                        MainScreen(
                            onSettings = { navController.navigate("settings") },
                            onCoordinates = { navController.navigate("coordinates") },
                            onLogs = { navController.navigate("logs") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = navController::popBackStack)
                    }
                    composable("coordinates") {
                        CoordinateSetupScreen(onBack = navController::popBackStack)
                    }
                    composable("logs") {
                        LogScreen(onBack = navController::popBackStack)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
    }
}
