package com.autoregistershift.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autoregistershift.data.SettingsRepository
import com.autoregistershift.model.AppSettings
import com.autoregistershift.model.CoordinatePoint
import com.autoregistershift.service.AutoRegisterAccessibilityService
import com.autoregistershift.service.CoordinateCaptureOverlayService
import com.autoregistershift.ui.components.SectionCard
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun CoordinateSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Cấu hình tọa độ", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tọa độ được lưu theo phần trăm màn hình. Nhấn “Đặt” để mở ứng dụng mục tiêu, rồi kéo dấu ⊕ và lưu.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (!Settings.canDrawOverlays(context)) {
                Text(
                    "Cần cấp quyền nút nổi trước khi đặt tọa độ.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            settings.coordinates.forEach { point ->
                CoordinateCard(
                    point = point,
                    onToggle = { enabled ->
                        scope.launch {
                            repository.update { current ->
                                current.copy(coordinates = current.coordinates.replace(point.id) {
                                    it.copy(enabled = enabled)
                                })
                            }
                        }
                    },
                    onCapture = {
                        if (!Settings.canDrawOverlays(context)) {
                            Toast.makeText(context, "Chưa có quyền nút nổi", Toast.LENGTH_LONG).show()
                            return@CoordinateCard
                        }
                        if (settings.targetPackage.isBlank()) {
                            Toast.makeText(context, "Hãy chọn package mục tiêu trước", Toast.LENGTH_LONG).show()
                            return@CoordinateCard
                        }
                        context.startService(
                            Intent(context, CoordinateCaptureOverlayService::class.java)
                                .putExtra(CoordinateCaptureOverlayService.EXTRA_POINT_ID, point.id)
                        )
                        val launchIntent = context.packageManager
                            .getLaunchIntentForPackage(settings.targetPackage)
                        if (launchIntent != null) {
                            context.startActivity(launchIntent)
                        } else {
                            Toast.makeText(context, "Không mở được ứng dụng mục tiêu", Toast.LENGTH_LONG).show()
                        }
                    },
                    onTest = {
                        val service = AutoRegisterAccessibilityService.instance
                        if (service == null) {
                            Toast.makeText(context, "Dịch vụ Trợ năng chưa hoạt động", Toast.LENGTH_LONG).show()
                        } else {
                            scope.launch {
                                val ok = service.clickRatio(
                                    point.xRatio,
                                    point.yRatio,
                                    settings.targetPackage
                                )
                                Toast.makeText(
                                    context,
                                    if (ok) "Đã gửi click thử" else "Chỉ thử được khi ứng dụng mục tiêu đang mở",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onReset = {
                        val default = CoordinatePoint.defaults.first { it.id == point.id }
                        scope.launch {
                            repository.update { current ->
                                current.copy(coordinates = current.coordinates.replace(point.id) { default })
                            }
                        }
                    }
                )
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Quay lại") }
        }
    }
}

@Composable
private fun CoordinateCard(
    point: CoordinatePoint,
    onToggle: (Boolean) -> Unit,
    onCapture: () -> Unit,
    onTest: () -> Unit,
    onReset: () -> Unit
) {
    SectionCard(point.name) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "X ${String.format(Locale.US, "%.1f%%", point.xRatio * 100)} • " +
                    "Y ${String.format(Locale.US, "%.1f%%", point.yRatio * 100)}"
            )
            Switch(checked = point.enabled, onCheckedChange = onToggle)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(onClick = onCapture, modifier = Modifier.weight(1f)) { Text("Đặt") }
            OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f)) { Text("Thử") }
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Đặt lại") }
        }
    }
}

private inline fun List<CoordinatePoint>.replace(
    id: String,
    transform: (CoordinatePoint) -> CoordinatePoint
): List<CoordinatePoint> = map { if (it.id == id) transform(it) else it }
