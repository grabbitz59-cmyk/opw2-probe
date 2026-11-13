package io.github.smartalarm

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object AlarmPlayer {
    private var vibrator: Vibrator? = null

    @Volatile
    var active: Boolean = false
        private set

    fun start(ctx: Context) {
        stopVibrationOnly()
        active = true
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 800, 350, 800, 350, 1200, 350, 800)
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            vibrator = v
        } catch (_: Throwable) {
            active = false
        }
    }

    private fun stopVibrationOnly() {
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null
    }

    fun stop(ctx: Context) {
        active = false
        stopVibrationOnly()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(ALARM_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    const val ALARM_NOTIFICATION_ID = 777
}
