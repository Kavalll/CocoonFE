package com.thorfeeds.catalog.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.thorfeeds.catalog.R
import com.thorfeeds.catalog.ThorFeedsApp
import com.thorfeeds.catalog.core.ByteFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val graph = ThorFeedsApp.graph(this)
        scope.launch {
            graph.downloads.snapshot.collect { state ->
                if (state == null || !started) return@collect
                val notification = buildNotification(state)
                val manager = getSystemService(NotificationManager::class.java)
                if (state.terminal) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    manager.notify(NOTIFICATION_ID, notification)
                    releaseLock()
                    started = false
                    stopSelf()
                } else {
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                ThorFeedsApp.graph(this).downloads.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val releaseId = intent.getStringExtra(EXTRA_RELEASE_ID)
                val placeholder = placeholderNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        placeholder,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, placeholder)
                }
                started = true
                acquireLock()
                if (!releaseId.isNullOrBlank()) {
                    ThorFeedsApp.graph(this).downloads.start(releaseId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Game downloads and mirror progress"
        manager.createNotificationChannel(channel)
    }

    private fun placeholderNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Thor Feeds")
            .setContentText("Starting download")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun buildNotification(state: DownloadSnapshot): Notification {
        val text = when (state.phase) {
            DownloadPhase.Completed -> state.message ?: "Download complete"
            DownloadPhase.Failed -> state.message ?: "Download failed"
            DownloadPhase.Cancelled -> state.message ?: "Download cancelled"
            DownloadPhase.Extracting -> {
                val amount = if (state.totalBytes != null && state.totalBytes > 0) {
                    "${ByteFormat.format(state.bytesRead)} of ${ByteFormat.format(state.totalBytes)}"
                } else if (state.bytesRead > 0) {
                    ByteFormat.format(state.bytesRead)
                } else {
                    "Starting"
                }
                "Extracting ${state.fileName ?: "archive"} · $amount"
            }
            else -> {
                val mirror = "Mirror ${state.mirrorIndex + 1} of ${state.mirrorCount} · ${state.mirrorLabel}"
                val amount = if (state.totalBytes != null && state.totalBytes > 0) {
                    "${ByteFormat.format(state.bytesRead)} of ${ByteFormat.format(state.totalBytes)}"
                } else if (state.bytesRead > 0) {
                    ByteFormat.format(state.bytesRead)
                } else {
                    "Connecting"
                }
                "$mirror · $amount"
            }
        }
        val percent = if (state.totalBytes != null && state.totalBytes > 0) {
            ((state.bytesRead * 100) / state.totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(state.canonicalTitle)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(!state.terminal)
        if (!state.terminal) {
            builder.addAction(R.drawable.ic_stat_download, "Cancel", cancel)
            val indeterminate = state.phase == DownloadPhase.Connecting || state.totalBytes == null
            builder.setProgress(100, percent, indeterminate)
        }
        return builder.build()
    }

    private fun acquireLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThorFeeds:download").apply {
            setReferenceCounted(false)
            try {
                acquire(MAX_LOCK_MS)
            } catch (_: Exception) {
                // The download still runs while the process is in the foreground.
            }
        }
    }

    private fun releaseLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
            // Already released.
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.thorfeeds.catalog.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.thorfeeds.catalog.DOWNLOAD_CANCEL"
        const val EXTRA_RELEASE_ID = "release_id"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 41
        private const val MAX_LOCK_MS = 24 * 60 * 60 * 1000L
    }
}
