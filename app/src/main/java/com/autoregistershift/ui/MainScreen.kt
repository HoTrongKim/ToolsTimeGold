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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autoregistershift.BuildConfig
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
    var showBankingModeDialog by remember { mutableStateOf(false) }
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
    val toolActive = runtime.state !in setOf(
        AutomationState.IDLE,
        AutomationState.STOPPED,
        AutomationState.ERROR
    )
    val permissionsReady = accessibilityEnabled && overlayEnabled && notificationsEnabled

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Auto Register Shift",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "v${BuildConfig.VERSION_NAME} • Xử lý hoàn toàn trên thiết bị",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .64f)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "⚡ 500 ms",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            RuntimeDashboard(
                message = runtime.message,
                active = toolActive,
                paused = runtime.state == AutomationState.PAUSED,
                refreshCount = runtime.refreshCount,
                successCount = runtime.successCount,
                fullCount = runtime.fullCount
            )

            SectionCard("Điều khiển nhanh") {
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
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (runtime.state == AutomationState.PAUSED) "▶ Tiếp tục" else "▶ Bắt đầu") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = AutomationController::pause,
                        modifier = Modifier.weight(1f)
                    ) { Text("Ⅱ Tạm dừng") }
                    OutlinedButton(
                        onClick = AutomationController::stop,
                        modifier = Modifier.weight(1f)
                    ) { Text("■ Dừng") }
                }
            }

            SectionCard("Quyền truy cập") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PermissionBadge("Trợ năng", accessibilityEnabled, Modifier.weight(1f))
                    PermissionBadge("Nút nổi", overlayEnabled, Modifier.weight(1f))
                    PermissionBadge("Thông báo", notificationsEnabled, Modifier.weight(1f))
                }
                Text(
                    if (permissionsReady) "Thiết bị đã sẵn sàng để chạy tự động."
                    else "Hãy cấp đủ các quyền còn thiếu trước khi bắt đầu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (permissionsReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Trợ năng") }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Nút nổi") }
                }
                if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled) {
                    OutlinedButton(
                        onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cấp quyền thông báo") }
                }
            }

            SectionCard("Nhịp hoạt động") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TimingPill(
                        symbol = "↻",
                        value = "0,5 giây",
                        caption = "Làm mới",
                        modifier = Modifier.weight(1f)
                    )
                    TimingPill(
                        symbol = "⚡",
                        value = "Tức thì",
                        caption = "Khi thấy ca",
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Tool vuốt xuống mỗi 0,5 giây. Nếu Accessibility phát hiện ca trong lúc chờ, tool mở và đăng ký ngay mà không đợi hết chu kỳ.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SectionCard("Cấu hình và nhật ký") {
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
            }

            SectionCard("Chế độ vận hành") {
                ToggleRow("Chạy liên tục 24/7 và tự phục hồi", settings.continuousMode) {
                    AutomationController.setContinuousMode(it)
                }
                Text(
                    "Khi bật, giới hạn thời gian/số ca được bỏ qua và màn hình được giữ sáng. Tool vẫn ngừng click khi khóa máy hoặc rời ứng dụng mục tiêu.",
                    style = MaterialTheme.typography.bodySmall
                )
                ToggleRow("Đăng ký tất cả ca xuất hiện", settings.registerAll) {
                    scope.launch { repository.update { value -> value.copy(registerAll = it) } }
                }
                ToggleRow("Dừng sau khi đăng ký thành công", settings.stopAfterSuccess) {
                    if (it && settings.continuousMode) AutomationController.setContinuousMode(false)
                    scope.launch {
                        repository.update { value ->
                            value.copy(
                                stopAfterSuccess = it,
                                continuousMode = if (it) false else value.continuousMode
                            )
                        }
                    }
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

            SectionCard("An toàn khi chuyển khoản") {
                Text(
                    "Chế độ ngân hàng sẽ dừng tool, gỡ nút nổi và tắt dịch vụ Trợ năng trước khi bạn chuyển khoản.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = { showBankingModeDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Tắt tool và vào chế độ ngân hàng") }
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
                ) { Text("Quản lý quyền nút nổi") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            repository.reset()
                            Toast.makeText(context, "Đã khôi phục mặc định", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Khôi phục toàn bộ cấu hình mặc định") }
            }
        }
    }

    if (showBankingModeDialog) {
        AlertDialog(
            onDismissRequest = { showBankingModeDialog = false },
            title = { Text("Bật chế độ ngân hàng?") },
            text = {
                Text(
                    "Automation sẽ dừng, nút nổi biến mất và quyền Trợ năng của Auto Register Shift sẽ được tắt."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBankingModeDialog = false
                        AutomationController.enterBankingMode(context)
                        Toast.makeText(
                            context,
                            "Đã tắt tool, nút nổi và Trợ năng. Bạn có thể mở ứng dụng ngân hàng.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                ) { Text("Tắt và tiếp tục") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBankingModeDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }
}

@Composable
private fun RuntimeDashboard(
    message: String,
    active: Boolean,
    paused: Boolean,
    refreshCount: Int,
    successCount: Int,
    fullCount: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Trạng thái vận hành", style = MaterialTheme.typography.titleMedium)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = when {
                        paused -> MaterialTheme.colorScheme.errorContainer
                        active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surface
                    }
                ) {
                    Text(
                        when {
                            paused -> "TẠM DỪNG"
                            active -> "ĐANG CHẠY"
                            else -> "ĐÃ DỪNG"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (active && !paused) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
            Text(message, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DashboardMetric("↻", refreshCount.toString(), "Làm mới", Modifier.weight(1f))
                DashboardMetric("✓", successCount.toString(), "Thành công", Modifier.weight(1f))
                DashboardMetric("⊘", fullCount.toString(), "Đã đặt", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DashboardMetric(
    symbol: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .72f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$symbol $value", fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PermissionBadge(label: String, granted: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (granted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (granted) "✓" else "!", fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TimingPill(
    symbol: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("$symbol  $value", fontWeight = FontWeight.Bold)
            Text(
                caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f)
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, positive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, modifier = Modifier.weight(0.3f))
        Text(
            value,
            modifier = Modifier.weight(0.7f),
            textAlign = TextAlign.End,
            color = if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
        )
    }
}
