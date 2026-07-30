package com.autoregistershift.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autoregistershift.data.LogRepository
import com.autoregistershift.model.LogLevel
import com.autoregistershift.ui.components.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { LogRepository(context.applicationContext) }
    val logs by repository.logs.collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by remember { mutableStateOf<LogLevel?>(null) }
    var exportContent by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val visible = logs.filter { filter == null || it.level == filter }
    val text = visible.joinToString("\n") { it.displayText() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(exportContent)
                }
            }.onSuccess {
                Toast.makeText(context, "Đã xuất nhật ký", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Không thể xuất: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Nhật ký", style = MaterialTheme.typography.headlineSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Tất cả") })
                FilterChip(
                    selected = filter == LogLevel.SUCCESS,
                    onClick = { filter = LogLevel.SUCCESS },
                    label = { Text("Thành công") }
                )
                FilterChip(
                    selected = filter == LogLevel.ERROR,
                    onClick = { filter = LogLevel.ERROR },
                    label = { Text("Lỗi") }
                )
            }
            SectionCard("${visible.size} mục") {
                if (visible.isEmpty()) {
                    Text("Chưa có nhật ký.")
                } else {
                    visible.asReversed().forEach { entry ->
                        Text(
                            entry.displayText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = when (entry.level) {
                                LogLevel.SUCCESS -> MaterialTheme.colorScheme.secondary
                                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                LogLevel.ACTIVITY -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Auto Register Shift log", text))
                        Toast.makeText(context, "Đã sao chép", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Sao chép") }
                OutlinedButton(
                    onClick = {
                        exportContent = text
                        val suffix = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        exportLauncher.launch("auto-register-shift-$suffix.txt")
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Xuất TXT") }
                OutlinedButton(
                    onClick = { scope.launch { repository.clear() } },
                    modifier = Modifier.weight(1f)
                ) { Text("Xóa") }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Quay lại") }
        }
    }
}
