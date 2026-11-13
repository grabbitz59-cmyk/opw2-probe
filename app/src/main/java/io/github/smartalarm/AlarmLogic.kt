package io.github.smartalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.File
import java.time.Instant

object AlarmLogic {

    @Volatile
    var targetAt: Long = 0
        private set

    @Volatile
    var windowMs: Long = 30 * 60_000L
        private set

    @Volatile
    var triggered: Boolean = false
        private set

    private val hrSamples = ArrayList<Pair<Long, Float>>()
    private val lock = Any()

    fun arm(ctx: Context, targetAtMs: Long, windowMin: Int) {
        targetAt = targetAtMs
        windowMs = windowMin * 60_000L
        triggered = false
        synchronized(lock) { hrSamples.clear() }
        Prefs.setAlarmAt(ctx, targetAtMs)
        Prefs.setWindowMin(ctx, windowMin)
        scheduleFallback(ctx, targetAtMs)
        log(ctx, "arm target=${Instant.ofEpochMilli(targetAtMs)} window=${windowMin}m")
    }

    fun disarm(ctx: Context) {
        targetAt = 0
        triggered = false
        Prefs.setAlarmAt(ctx, 0)
        cancelFallback(ctx)
        log(ctx, "disarm")
    }

    fun restoreFromPrefs(ctx: Context) {
        val at = Prefs.alarmAt(ctx)
        if (at > System.currentTimeMillis()) {
            arm(ctx, at, Prefs.windowMin(ctx))
        }
    }

    private fun scheduleFallback(ctx: Context, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 77,
            Intent(ctx, AlarmTestReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi)
            log(ctx, "fallback setAlarmClock ok")
        } catch (t: Throwable) {
            log(ctx, "setAlarmClock failed: $t")
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
                log(ctx, "fallback plain set ok")
            } catch (t2: Throwable) {
                log(ctx, "fallback failed: $t2")
            }
        }
    }

    private fun cancelFallback(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 77,
            Intent(ctx, AlarmTestReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
    }

    fun onBucket(ctx: Context, moves: Int, std: Double, p2p: Double) {
        val now = System.currentTimeMillis()
        HealthState.freshHr()?.let { hr ->
            synchronized(lock) {
                hrSamples.add(now to hr)
                hrSamples.removeAll { now - it.first > 45 * 60_000L }
            }
        }

        if (targetAt <= 0 || triggered) return
        if (now >= targetAt) {
            trigger(ctx, "deadline")
            return
        }
        if (now < targetAt - windowMs) return

        val base = baseline()
        val hr = HealthState.freshHr()
        val motion = moves >= 6 || std > 0.10 || p2p > 1.2
        val hrRise = hr != null && base != null && (hr - base) >= 3.0f
        val state = HealthState.freshestate(20 * 60_000L)
        val awake = state != null && state != "USER_ACTIVITY_ASLEEP"

        if (motion && (hrRise || awake)) {
            trigger(ctx, "smart moves=$moves std=$std hr=$hr base=$base state=$state")
        }
    }

    fun onArousalSignal(ctx: Context, why: String) {
        if (targetAt <= 0 || triggered) return
        val now = System.currentTimeMillis()
        if (now < targetAt - windowMs || now >= targetAt) return
        trigger(ctx, "state $why")
    }

    private fun baseline(): Float? {
        val vals = synchronized(lock) { hrSamples.map { it.second } }
        if (vals.size < 8) return null
        val sorted = vals.sorted()
        val idx = (sorted.size * 0.2).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun trigger(ctx: Context, reason: String) {
        triggered = true
        log(ctx, "TRIGGER $reason")
        try {
            ctx.sendBroadcast(Intent(ctx, AlarmTestReceiver::class.java))
        } catch (t: Throwable) {
            log(ctx, "trigger broadcast failed: $t")
        }
    }

    fun log(ctx: Context, msg: String) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            if (!dir.exists()) dir.mkdirs()
            File(dir, "alarm_debug.txt").appendText("${Instant.now()} $msg\n")
        } catch (_: Throwable) {
        }
    }
}
