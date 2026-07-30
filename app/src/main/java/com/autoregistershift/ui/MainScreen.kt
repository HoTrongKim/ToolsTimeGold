package com.autoregistershift.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autoregistershift.automation.AutomationController
import com.autoregistershift.automation.AutomationState
import com.autoregistershift.data.SettingsRepository
import com.autoregistershift.ui.components.SectionCard
import com.autoregistershift.ui.components.ToggleRow
import com.autoregistershift.util.PackageUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onSettings: () -> Unit,
    onCoordinates: () -> Unit,
    onLogs: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = com.autoregistershift.model.AppSettings())
    val runtime by AutomationController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    @Suppress("UNUSED_VARIABLE")
    val refreshToken = permissionRefresh
    val accessibilityEnabled = PackageUtils.isAccessibilityEnabled(context)
    val overlayEnabled = Settings.canDrawOverlays(context)
    val notificationsEnabled = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionRefresh++ }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Auto Register Shift", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tự động hóa cục bộ bằng Trợ năng. Tool không vượt CAPTCHA, OTP hoặc bước xác minh.",
                style = MaterialTheme.typography.bodyMedium
            )

            SectionCard("Trạng thái") {
                StatusLine("Trợ năng", if (accessibilityEnabled) "Đã bật" else "Chưa bật", accessibilityEnabled)
                StatusLine("Nút nổi", if (overlayEnabled) "Đã cấp quyền" else "Chưa cấp quyền", overlayEnabled)
                StatusLine("Thông báo", if (notificationsEnabled) "Đã cấp quyền" else "Chưa cấp quyền", notificationsEnabled)
                StatusLine("Tool", runtime.message, runtime.state !in setOf(AutomationState.ERROR, AutomationState.STOPPED))
                Text("Làm mới: ${runtime.refreshCount} • Đăng ký thành công: ${runtime.successCount}")
            }

            SectionCard("Quyền cần cấp") {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cấp quyền Trợ năng") }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cấp quyền nút nổi") }
                if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled) {
                    OutlinedButton(
                        onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cấp quyền thông báo") }
                }
            }

            SectionCard("Điều khiển") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            when {
                                !accessibilityEnabled -> Toast.makeText(
                                    context, "Hãy bật quyền Trợ năng trước", Toast.LENGTH_LONG
                                ).show()
                                settings.targetPackage.isBlank() -> Toast.makeText(
                                    context, "Hãy chọn ứng dụng mục tiêu", Toast.LENGTH_LONG
                                ).show()
                                else -> AutomationController.startOrResume(context)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Bắt đầu") }
                    OutlinedButton(
                        onClick = AutomationController::pause,
                        modifier = Modifier.weight(1f)
                    ) { Text("Tạm dừng") }
                }
                Button(
                    onClick = AutomationController::stop,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Dừng hoàn toàn") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                        Text("Cài đặt")
                    }
                    OutlinedButton(onClick = onCoordinates, modifier = Modifier.weight(1f)) {
                        Text("Điểm click")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val current = repository.settings.first()
                                val message = when {
                                    current.targetPackage.isBlank() -> "Chưa nhập package mục tiêu"
                                    !PackageUtils.canLaunch(context, current.targetPackage) ->
                                        "Không tìm thấy ứng dụng có thể mở: ${current.targetPackage}"
                                    current.scheduleScreenTexts.isEmpty() -> "Thiếu chữ nhận diện màn hình"
                                    current.registerButtonTexts.isEmpty() -> "Thiếu chữ nhận diện nút đăng ký"
                                    else -> "Cấu hình cơ bản hợp lệ"
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Kiểm tra") }
                    OutlinedButton(onClick = onLogs, modifier = Modifier.weight(1f)) {
                        Text("Nhật ký")
                    }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            repository.reset()
                            Toast.makeText(context, "Đã khôi phục mặc định", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Khôi phục mặc định") }
            }

            SectionCard("Tùy chọn nhanh") {
                ToggleRow("Đăng ký tất cả ca xuất hiện", settings.registerAll) {
                    scope.launch { repository.update { value -> value.copy(registerAll = it) } }
                }
                ToggleRow("Dừng sau khi đăng ký thành công", settings.stopAfterSuccess) {
                    scope.launch { repository.update { value -> value.copy(stopAfterSuccess = it) } }
                }
                ToggleRow("Tự quay lại danh sách", settings.autoReturnToList) {
                    scope.launch { repository.update { value -> value.copy(autoReturnToList = it) } }
                }
                ToggleRow("Phát âm thanh khi thành công", settings.soundOnSuccess) {
                    scope.launch { repository.update { value -> value.copy(soundOnSuccess = it) } }
                }
                ToggleRow("Rung khi thành công", settings.vibrateOnSuccess) {
                    scope.launch { repository.update { value -> value.copy(vibrateOnSuccess = it) } }
                }
                ToggleRow("Hiện nút nổi", settings.showOverlay) {
                    scope.launch { repository.update { value -> value.copy(showOverlay = it) } }
                }
                ToggleRow("Giữ màn hình sáng khi đang chạy", settings.keepScreenOn) {
                    scope.launch { repository.update { value -> value.copy(keepScreenOn = it) } }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, positive: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            value,
            color = if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
        )
    }
}
