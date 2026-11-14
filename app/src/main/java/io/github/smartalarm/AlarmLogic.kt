package io.github.smartalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.File
import java.time.Instant
import java.util.Calendar
import java.util.Locale

/**
 * Логика «умного» будильника.
 *
 * P0-правки относительно 1.8:
 *  - HR берётся из HealthState, куда теперь пишет и собственный сенсор (source=sensor),
 *    а не только passive Health Services;
 *  - baseline считается по медиане УНИКАЛЬНЫХ значений за 45 мин (дубли больше не занижают базу);
 *  - порог подъёма HR поднят до 8 bpm (3 bpm — внутри шума оптического датчика);
 *  - «движение» определяется с гистерезисом и требует реальных порогов, а не OR по шуму;
 *  - onArousalSignal больше не требует обязательного sawAsleep: достаточно 20 минут тишины.
 */
object AlarmLogic {

    private const val HR_MAX_AGE_MS = 300_000L            // насколько старый HR ещё считается свежим
    private const val MOTION_HOLD_MS = 90_000L            // движение «свежее» в течение 90 с

    // Пороги и математика — в [ArousalMath] (чистый объект, покрыт JVM-тестами).

    @Volatile
    var targetAt: Long = 0
        private set

    @Volatile
    var windowMs: Long = 30 * 60_000L
        private set

    @Volatile
    var triggered: Boolean = false
        private set

    private val lock = Any()
    private val hrSamples = ArrayList<Pair<Long, Float>>()
    private var lastHrValue = -1f
    private var lastHrAt = 0L
    private var sawAsleep = false
    private var tick = 0
    private var lastMotionTs = System.currentTimeMillis()
    private var lastTriggerReason = ""
    private var firedAt = 0L

    /**
     * Атомарный дедуп срабатывания: и дедлайн из сервиса, и AlarmManager приходят
     * примерно в один момент. Раньше это давало две вибрации и две записи в лог.
     */
    fun markFired(): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (now - firedAt < 60_000L) {
            false
        } else {
            firedAt = now
            true
        }
    }

    // ---------- снимки состояния для CSV и UI ----------

    fun sawAsleepNow(): Boolean = synchronized(lock) { sawAsleep }

    fun tickCount(): Int = synchronized(lock) { tick }

    fun quietMinutesNow(): Long =
        synchronized(lock) { (System.currentTimeMillis() - lastMotionTs) / 60_000L }

    fun lastTriggerReason(): String = synchronized(lock) { lastTriggerReason }

    /** idle | armed | triggered — компактно пишется в CSV */
    fun stateLabel(): String = when {
        triggered -> "triggered"
        targetAt > 0 -> "armed"
        else -> "idle"
    }

    fun targetClock(): String {
        val at = targetAt
        if (at <= 0) return "--:--"
        val c = Calendar.getInstance().apply { timeInMillis = at }
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // ---------- взвод / снятие ----------

    fun arm(ctx: Context, targetAtMs: Long, windowMin: Int) {
        synchronized(lock) {
            targetAt = targetAtMs
            windowMs = windowMin * 60_000L
            triggered = false
            hrSamples.clear()
            lastHrValue = -1f
            lastHrAt = 0L
            sawAsleep = false
            tick = 0
            lastMotionTs = System.currentTimeMillis()
            lastTriggerReason = ""
        }
        Prefs.setAlarmAt(ctx, targetAtMs)
        Prefs.setWindowMin(ctx, windowMin)
        scheduleFallback(ctx, targetAtMs)
        log(ctx, "arm target=${Instant.ofEpochMilli(targetAtMs)} window=${windowMin}m")
    }

    fun disarm(ctx: Context) {
        synchronized(lock) {
            targetAt = 0
            triggered = false
            lastTriggerReason = ""
        }
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

    // ---------- fallback через AlarmManager ----------

    private fun fallbackPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 77,
        Intent(ctx, AlarmTestReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 78,
        Intent(ctx, AlarmActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleFallback(ctx: Context, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = fallbackPi(ctx)
        try {
            // showIntent — именно activity: иначе системный UI не показывает будильник
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi(ctx)), pi)
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
        am.cancel(fallbackPi(ctx))
    }

    // ---------- основной цикл: один бакет сенсоров ----------

    fun onBucket(ctx: Context, n: Int, moves: Int, std: Double, p2p: Double) {
        val now = System.currentTimeMillis()

        val fresh = HealthState.freshHr(HR_MAX_AGE_MS)
        if (fresh != null) addSample(now, fresh)

        if (ArousalMath.isMotion(n, moves, std, p2p)) {
            synchronized(lock) { lastMotionTs = now }
        }

        if (targetAt <= 0 || triggered) return
        if (now >= targetAt) {
            trigger(ctx, "deadline")
            return
        }
        if (now < targetAt - windowMs) return

        val base = baseline(now)
        val hr = fresh
        val hrRise = ArousalMath.hrRise(hr, base)
        val asleepSeen = ensureAsleep(ctx, now)

        val sysState = HealthState.freshestate(20 * 60_000L)
        val sysAwake = asleepSeen && sysState != null && sysState != "USER_ACTIVITY_ASLEEP"

        val t = synchronized(lock) { tick++ }
        if (t % 10 == 0) {
            log(
                ctx,
                "tick hr=$hr base=$base rise=$hrRise n=$n moves=$moves std=${fmt(std)} " +
                    "p2p=${fmt(p2p)} quiet=${quietMinutesNow()}m state=$sysState " +
                    "asleep_seen=$asleepSeen src=${HealthState.sourceNow()}",
            )
        }

        val motionFresh = synchronized(lock) { now - lastMotionTs < MOTION_HOLD_MS }
        if (motionFresh && (hrRise || sysAwake)) {
            trigger(
                ctx,
                "smart hr=$hr base=$base rise=$hrRise awake=$sysAwake " +
                    "moves=$moves std=${fmt(std)} src=${HealthState.sourceNow()}",
            )
        }
    }

    /** Возвращает true, если сон уже подтверждён. */
    private fun ensureAsleep(ctx: Context, now: Long): Boolean {
        if (synchronized(lock) { sawAsleep }) return true
        val quiet = quietMinutesNow()
        val uniq = synchronized(lock) {
            ArousalMath.windowValues(hrSamples, now)
        }
        if (!ArousalMath.asleepInferred(quiet, uniq.size)) return false
        synchronized(lock) { sawAsleep = true }
        log(ctx, "inferred asleep (quiet ${quiet}m, hr samples ${uniq.size})")
        return true
    }

    fun onAsleep(ctx: Context) {
        val first = synchronized(lock) {
            if (sawAsleep) false else { sawAsleep = true; true }
        }
        if (first) log(ctx, "asleep detected (health services)")
    }

    /**
     * Сигнал от Health Services о смене активности.
     * Раньше здесь стоял жёсткий `if (!sawAsleep) return`, из-за чего сигнал
     * AWAKE игнорировался, если косвенный детект сна не сработал.
     */
    fun onArousalSignal(ctx: Context, why: String) {
        if (targetAt <= 0 || triggered) return
        val now = System.currentTimeMillis()
        if (now < targetAt - windowMs || now >= targetAt) return

        val asleepSeen = synchronized(lock) { sawAsleep }
        val quiet = quietMinutesNow()
        if (!ArousalMath.arousalAllowed(asleepSeen, quiet)) {
            log(ctx, "arousal ignored (no sleep evidence, quiet=${quiet}m): $why")
            return
        }
        trigger(ctx, "state $why")
    }

    // ---------- HR-база ----------

    private fun addSample(now: Long, hr: Float) {
        synchronized(lock) {
            // дубликаты одного и того же passive-значения не должны надувать выборку
            if (!ArousalMath.shouldAddSample(lastHrValue, lastHrAt, hr, now)) return
            lastHrValue = hr
            lastHrAt = now
            hrSamples.add(now to hr)
            hrSamples.removeAll { now - it.first > ArousalMath.BASELINE_WINDOW_MS }
        }
    }

    /** Медиана уникальных значений за окно — устойчива к дублям и выбросам. */
    private fun baseline(now: Long): Float? = synchronized(lock) {
        ArousalMath.baseline(hrSamples, now)
    }

    // ---------- срабатывание ----------

    private fun trigger(ctx: Context, reason: String) {
        val go = synchronized(lock) {
            if (triggered) false else { triggered = true; lastTriggerReason = reason; true }
        }
        if (!go) return
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

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
