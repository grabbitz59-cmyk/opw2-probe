package io.github.smartalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File

class AlarmTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "alarm_fired.txt").appendText("fired=$now\n")
        } catch (_: Throwable) {
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opw2probe:alarm")
        wl.acquire(60_000L)

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("alarmtest", "Alarm test", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
            val n: Notification = Notification.Builder(context, "alarmtest")
                .setContentTitle("ТЕСТ БУДИЛЬНИКА")
                .setContentText("Сработал в ${java.time.Instant.ofEpochMilli(now)}")
                .setSmallIcon(R.drawable.ic_probe)
                .setAutoCancel(true)
                .build()
            nm.notify(777, n)
        } catch (_: Throwable) {
        }

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 700, 300, 700, 300, 700, 300, 700, 300, 1200)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Throwable) {
        }
    }
}
