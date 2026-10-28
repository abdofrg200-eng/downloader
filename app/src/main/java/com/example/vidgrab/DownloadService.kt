package com.example.vidgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service: بتخلي التحميل يكمّل حتى لو قفلت التطبيق أو الشاشة،
 * وبتعرض إشعار فيه نسبة التقدم وزر إلغاء.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> DownloadEngine.cancel()

            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    DownloadEngine.reset()
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (job?.isActive == true) return START_NOT_STICKY

                val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
                val height = intent.getIntExtra(EXTRA_HEIGHT, -1).takeIf { it > 0 }
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "VidGrab"

                createChannel()
                startForeground(
                    NOTIF_ONGOING,
                    buildOngoing(title, 0f),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                acquireWakeLock()

                job = scope.launch {
                    val observer = launch {
                        DownloadEngine.state.collect { s ->
                            if (s.active) notifyOngoing(title, s.progress)
                        }
                    }
                    DownloadEngine.run(applicationContext, url, audioOnly, height)
                    observer.cancel()

                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    notifyDone(DownloadEngine.state.value.message ?: "انتهى")
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- الإشعارات ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "التحميلات", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildOngoing(title: String, progress: Float): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val pct = (progress * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (pct > 0) "$pct%" else "جاري التجهيز...")
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "إلغاء", cancelIntent)
            .build()
    }

    private fun notifyOngoing(title: String, progress: Float) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ONGOING, buildOngoing(title, progress))
    }

    private fun notifyDone(text: String) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("VidGrab")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
    }

    // ---------- WakeLock عشان التحميل ميقفش لما الشاشة تقفل ----------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VidGrab:download").apply {
            acquire(3 * 60 * 60 * 1000L) // حد أقصى 3 ساعات
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.example.vidgrab.START"
        const val ACTION_CANCEL = "com.example.vidgrab.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_AUDIO_ONLY = "audio_only"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_TITLE = "title"

        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_DONE = 2
    }
}
