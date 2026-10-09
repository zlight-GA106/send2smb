package com.zlight.sendtosmb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zlight.sendtosmb.ui.UiState
import com.zlight.sendtosmb.ui.formatSpeed
import kotlinx.coroutines.*

class BackgroundTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model get() = (application as SendToSmbApplication).explorer
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "后台文件传输", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(model.state.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SendToSMB:transfer").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SendToSMB:transfer").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: Exception) {
            model.onBackgroundServiceFailed(error)
            stopSelf()
            return
        }
        scope.launch {
            // One update per second, including stalled transfers; no notification flood on small files.
            while (isActive) {
                val state = model.state.value
                if (!state.backgroundTransfers || !state.transferBusy) {
                    stopSelf()
                    break
                }
                notifications.notify(NOTIFICATION, notification(state))
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            model.stopTransfers("已取消全部传输")
            stopSelf()
        }
        // A killed process cannot recover transient SAF grants or safely resume partial SMB writes.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        model.stopTransfers("系统后台传输时限已到，请回到应用重新发起未完成的任务")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        model.onBackgroundServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(state: UiState): Notification {
        val active = state.transfers.filter { it.status == "running" || it.status == "queued" }
        val running = active.firstOrNull { it.status == "running" }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("showTransfers", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, BackgroundTransferService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_transfer_notification)
            .setContentTitle("SendToSMB · ${active.size} 个传输任务")
            .setContentText("${running?.name ?: "正在准备传输"} · 平均速度 ${formatSpeed(state.averageBytesPerSecond)}")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, if (running != null && running.total > 0) (running.done.toDouble() / running.total * 100).toInt().coerceIn(0, 100) else 0,
                running == null || running.total <= 0)
            .addAction(0, "取消全部", cancel)
            .build()
    }

    companion object {
        private const val CHANNEL = "background_transfers"
        private const val NOTIFICATION = 10
        private const val CANCEL = "com.zlight.sendtosmb.CANCEL_TRANSFERS"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, BackgroundTransferService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, BackgroundTransferService::class.java)) }
    }
}
