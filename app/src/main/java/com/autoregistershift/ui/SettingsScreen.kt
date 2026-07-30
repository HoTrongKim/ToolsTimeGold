package com.autoregistershift.ui

import android.content.Intent
import android.content.pm.ResolveInfo
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autoregistershift.data.SettingsRepository
import com.autoregistershift.model.AppSettings
import com.autoregistershift.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val stored by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    var draft by remember { mutableStateOf(stored) }
    var showPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(stored) { draft = stored }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Cấu hình", style = MaterialTheme.typography.headlineSmall)

            SectionCard("Ứng dụng mục tiêu") {
                OutlinedTextField(
                    value = draft.targetPackage,
                    onValueChange = { draft = draft.copy(targetPackage = it.trim()) },
                    label = { Text("Package name") },
                    supportingText = { Text("Ví dụ: com.example.shiftapp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Chọn từ ứng dụng đã cài")
                }
            }

            SectionCard("Chuỗi nhận diện") {
                ListEditor("Màn hình danh sách", draft.scheduleScreenTexts) {
                    draft = draft.copy(scheduleScreenTexts = it)
                }
                ListEditor("Trang chi tiết", draft.detailScreenTexts) {
                    draft = draft.copy(detailScreenTexts = it)
                }
                ListEditor("Nút đăng ký", draft.registerButtonTexts) {
                    draft = draft.copy(registerButtonTexts = it)
                }
                ListEditor("Không có ca", draft.noSlotTexts) {
                    draft = draft.copy(noSlotTexts = it)
                }
                ListEditor("Đăng ký thành công", draft.successTexts) {
                    draft = draft.copy(successTexts = it)
                }
                ListEditor("Ca đã đầy", draft.fullTexts) {
                    draft = draft.copy(fullTexts = it)
                }
                ListEditor("Lỗi mạng", draft.networkErrorTexts) {
                    draft = draft.copy(networkErrorTexts = it)
                }
                ListEditor("Đang tải", draft.loadingTexts) {
                    draft = draft.copy(loadingTexts = it)
                }
                ListEditor("Bắt buộc dừng: CAPTCHA/OTP/xác minh", draft.prohibitedTexts) {
                    draft = draft.copy(prohibitedTexts = it)
                }
            }

            SectionCard("Thời gian (mili giây)") {
                LongEditor("Khoảng nghỉ giữa lần làm mới", draft.refreshIntervalMs) {
                    draft = draft.copy(refreshIntervalMs = it)
                }
                LongEditor("Chờ sau khi vuốt", draft.waitAfterSwipeMs) {
                    draft = draft.copy(waitAfterSwipeMs = it)
                }
                LongEditor("Chờ sau khi mở ca", draft.waitAfterOpenSlotMs) {
                    draft = draft.copy(waitAfterOpenSlotMs = it)
                }
                LongEditor("Timeout kết quả đăng ký", draft.registrationTimeoutMs) {
                    draft = draft.copy(registrationTimeoutMs = it)
                }
                LongEditor("Khoảng cách tối thiểu giữa click", draft.clickDebounceMs) {
                    draft = draft.copy(clickDebounceMs = it)
                }
                LongEditor("Cooldown cùng một ca", draft.shiftCooldownMs) {
                    draft = draft.copy(shiftCooldownMs = it)
                }
                LongEditor("Thời gian vuốt làm mới", draft.refreshSwipeDurationMs) {
                    draft = draft.copy(refreshSwipeDurationMs = it.coerceIn(400, 700))
                }
                LongEditor("Thời gian vuốt tải thêm", draft.loadSwipeDurationMs) {
                    draft = draft.copy(loadSwipeDurationMs = it.coerceIn(350, 600))
                }
            }

            SectionCard("Giới hạn an toàn") {
                IntEditor("Số lần thử lại khi lỗi", draft.maxRetry) {
                    draft = draft.copy(maxRetry = it)
                }
                IntEditor("Số ca đăng ký tối đa", draft.maxRegistrations) {
                    draft = draft.copy(maxRegistrations = it)
                }
                IntEditor("Thời gian chạy tối đa (phút)", draft.maxRunMinutes) {
                    draft = draft.copy(maxRunMinutes = it)
                }
                IntEditor("Số click tối đa mỗi phút", draft.maxClicksPerMinute) {
                    draft = draft.copy(maxClicksPerMinute = it)
                }
                IntEditor("Số refresh tối đa mỗi phút", draft.maxRefreshesPerMinute) {
                    draft = draft.copy(maxRefreshesPerMinute = it)
                }
                IntEditor("Số lần giao diện không xác định", draft.maxUnknownScreens) {
                    draft = draft.copy(maxUnknownScreens = it)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Hủy") }
                Button(
                    onClick = {
                        if (draft.targetPackage.isBlank()) {
                            Toast.makeText(context, "Package không được để trống", Toast.LENGTH_LONG).show()
                        } else {
                            scope.launch {
                                repository.update { draft.sanitized() }
                                Toast.makeText(context, "Đã lưu cấu hình", Toast.LENGTH_SHORT).show()
                                onBack()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Lưu") }
            }
        }
    }

    if (showPicker) {
        PackagePicker(
            onDismiss = { showPicker = false },
            onSelected = {
                draft = draft.copy(targetPackage = it)
                showPicker = false
            }
        )
    }
}

@Composable
private fun ListEditor(label: String, values: List<String>, onChange: (List<String>) -> Unit) {
    OutlinedTextField(
        value = values.joinToString("\n"),
        onValueChange = { text ->
            onChange(text.lineSequence().map(String::trim).filter(String::isNotBlank).toList())
        },
        label = { Text(label) },
        supportingText = { Text("Mỗi dòng là một chuỗi") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LongEditor(label: String, value: Long, onChange: (Long) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { it.toLongOrNull()?.let(onChange) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun IntEditor(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { it.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PackagePicker(onDismiss: () -> Unit, onSelected: (String) -> Unit) {
    val context = LocalContext.current
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chọn ứng dụng mục tiêu") },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                apps.forEach { app: ResolveInfo ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(app.activityInfo.packageName) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(app.loadLabel(context.packageManager).toString())
                        Text(
                            app.activityInfo.packageName,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}

private fun AppSettings.sanitized() = copy(
    refreshIntervalMs = refreshIntervalMs.coerceAtLeast(500),
    waitAfterSwipeMs = waitAfterSwipeMs.coerceAtLeast(200),
    waitAfterOpenSlotMs = waitAfterOpenSlotMs.coerceAtLeast(200),
    registrationTimeoutMs = registrationTimeoutMs.coerceAtLeast(1_000),
    maxRetry = maxRetry.coerceIn(0, 10),
    clickDebounceMs = clickDebounceMs.coerceAtLeast(250),
    shiftCooldownMs = shiftCooldownMs.coerceAtLeast(1_000),
    maxRegistrations = maxRegistrations.coerceAtLeast(1),
    maxRunMinutes = maxRunMinutes.coerceAtLeast(1),
    maxClicksPerMinute = maxClicksPerMinute.coerceIn(1, 120),
    maxRefreshesPerMinute = maxRefreshesPerMinute.coerceIn(1, 60),
    maxUnknownScreens = maxUnknownScreens.coerceIn(1, 20)
)
