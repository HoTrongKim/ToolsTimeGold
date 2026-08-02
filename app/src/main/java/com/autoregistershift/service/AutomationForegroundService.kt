package com.autoregistershift.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.autoregistershift.MainActivity
import com.autoregistershift.R
import com.autoregistershift.automation.AutomationController
import com.autoregistershift.automation.StateSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AutomationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification(AutomationController.state.value))
        observer = scope.launch {
            AutomationController.state.collectLatest {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(it))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> AutomationController.pause()
            ACTION_STOP -> AutomationController.stop()
            null -> AutomationController.restoreIfNeeded(applicationContext)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Trạng thái tự động đăng ký ca",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo cố định khi người dùng chạy công cụ"
                setShowBadge(false)
            }
        )
    }

    private fun notification(state: StateSnapshot) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Auto Register Shift")
            .setContentText(
                "${state.message} • Làm mới ${state.refreshCount} • " +
                    "Thành công ${state.successCount} • Đã đặt ${state.fullCount}"
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                "Tạm dừng",
                actionPendingIntent(ACTION_PAUSE, 1)
            )
            .addAction(
                0,
                "Dừng",
                actionPendingIntent(ACTION_STOP, 2)
            )
            .build()

    private fun actionPendingIntent(action: String, requestCode: Int) =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AutomationForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        const val ACTION_START = "com.autoregistershift.START"
        const val ACTION_PAUSE = "com.autoregistershift.PAUSE"
        const val ACTION_STOP = "com.autoregistershift.STOP"
        private const val CHANNEL_ID = "automation_status"
        private const val NOTIFICATION_ID = 1001
    }
}
