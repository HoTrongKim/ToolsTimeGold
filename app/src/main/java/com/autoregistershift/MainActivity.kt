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
        setContent {
            AutoRegisterTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
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
}
