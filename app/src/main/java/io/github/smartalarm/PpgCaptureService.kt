package io.github.smartalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/**
 * Захват сырого PPG на 60 секунд в foreground-сервисе.
 *
 * Почему сервис, а не broadcast: `RawPpg.capture` держит сенсоры минуту, а receiver
 * без `goAsync()` живёт ~10 секунд — систему это не устраивало, и запись обрывалась
 * на 12-й секунде (600 событий вместо 3000, файл ровно 160 КБ). Из 12 секунд
 * получалось 10–12 ударов, чего не хватает даже на один стабильный RR-ряд.
 */
class PpgCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 43
        const val CAPTURE_MS = 60_000L
        private const val REPORT = "report_PpgRaw.txt"
    }

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PPG capture", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PPG: запись 60 с")
            .setContentText("не снимайте часы и не двигайте рукой")
            .setSmallIcon(R.drawable.ic_probe)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            writeReport("не удалось войти в foreground: $t")
            stopSelf()
            return START_NOT_STICKY
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opw2:ppg").apply {
            setReferenceCounted(false)
            acquire(CAPTURE_MS + 30_000L)
        }
        val appCtx = applicationContext
        worker = Thread {
            val sm = appCtx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val dir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
            val report = try {
                RawPpg.capture(sm, dir, CAPTURE_MS)
            } catch (t: Throwable) {
                "capture failed: $t"
            }
            writeReport(report)
            try {
                if (wl.isHeld) wl.release()
            } catch (_: Throwable) {
            }
            stopSelf()
        }.also { it.start() }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker?.interrupt()
        super.onDestroy()
    }

    private fun writeReport(text: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            if (!dir.exists()) dir.mkdirs()
            File(dir, REPORT).writeText(text)
        } catch (_: Throwable) {
        }
    }
}
