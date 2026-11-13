package io.github.smartalarm

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object AlarmPlayer {
    private var vibrator: Vibrator? = null
    private var tone: ToneGenerator? = null

    @Volatile
    var active: Boolean = false
        private set

    fun start(ctx: Context) {
        stopVibrationOnly()
        stopTone()
        active = true
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            // Паттерн с паузами: у OPW2 нет регулировки амплитуды
            // (hapticChannelMaxAmplitude=0.0), поэтому будить приходится рисунком.
            val pattern = longArrayOf(0, 700, 300, 700, 300, 1100, 400, 700)
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            vibrator = v
        } catch (_: Throwable) {
        }
        startTone(ctx)
    }

    /**
     * Тон на канале ALARM. На OPW2 есть динамик (`android.hardware.audio.output`),
     * так что вибрация — не единственный способ разбудить. Если спикера нет или поток
     * занят, молча остаёмся на вибрации.
     */
    private fun startTone(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
            tone = ToneGenerator(AudioManager.STREAM_ALARM, 100).apply {
                startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, -1)
            }
        } catch (_: Throwable) {
            tone = null
        }
    }

    private fun stopVibrationOnly() {
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null
    }

    private fun stopTone() {
        try {
            tone?.stopTone()
        } catch (_: Throwable) {
        }
        try {
            tone?.release()
        } catch (_: Throwable) {
        }
        tone = null
    }

    fun stop(ctx: Context) {
        active = false
        stopVibrationOnly()
        stopTone()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(ALARM_NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    const val ALARM_NOTIFICATION_ID = 777
}
