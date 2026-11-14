package io.github.smartalarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

class NightLogService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "io.github.smartalarm.START"
        const val ACTION_STOP = "io.github.smartalarm.STOP"
        const val ACTION_FLUSH = "io.github.smartalarm.FLUSH"
        const val EXTRA_MODE = "mode"
        const val EXTRA_NOWAKELOCK = "nowakelock"
        const val MODE_ECO = "eco"
        const val MODE_FULL = "full"

        private const val CHANNEL_ID = "probe"
        private const val NOTIFICATION_ID = 42
        private const val BUCKET_MS = 30_000L
        private const val HEARTBEAT_MS = 60_000L
        private const val HEARTBEAT_REQUEST = 415
        private const val HR_ON_MS = 40_000L
        private const val HR_OFF_ECO_MS = 260_000L
        private const val HR_OFF_FULL_MS = 20_000L
        private const val GRAVITY = 9.80665
        private const val CSV_HEADER =
            "ts,epoch_ms,mode,battery_pct,charging,batt_temp_c,screen_on,doze,hr_bpm,hr_age_s,acc_n,acc_mean,acc_std,acc_p2p,moves,hr_samples,uptime_s,hr_source,saw_asleep,alarm_state"

        @Volatile var isRunning = false
        @Volatile var currentPath: String = ""
    }

    private lateinit var sm: SensorManager
    private var accel: Sensor? = null
    private var hr: Sensor? = null
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private var writer: BufferedWriter? = null
    private var mode = MODE_ECO
    private var startedAt = 0L

    private val lock = Any()
    private var accN = 0
    private var accSum = 0.0
    private var accSumSq = 0.0
    private var accMin = Double.MAX_VALUE
    private var accMax = -Double.MAX_VALUE
    private var moves = 0
    private var hrBpm = -1
    private var hrAt = 0L
    private var hrSamples = 0
    private var hrRegistered = false
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastBattery = intent
        }
    }
    private var lastBattery: Intent? = null

    private var bucketFailures = 0

    private val bucket: Runnable = object : Runnable {
        override fun run() {
            try {
                writeBucket()
                bucketFailures = 0
            } catch (t: Throwable) {
                // Раньше любой сбой в бакете делал stopSelf() — ночь умирала молча.
                // Теперь одиночный сбой только логируем и продолжаем; сдаёмся
                // лишь после серии из 10 подряд.
                bucketFailures++
                errorLog("bucket failed ($bucketFailures)", t)
                if (bucketFailures >= 10) {
                    Prefs.setRunning(this@NightLogService, false)
                    stopSelf()
                    return
                }
            }
            handler.postDelayed(this, BUCKET_MS)
        }
    }

    private val hrOn: Runnable = object : Runnable {
        override fun run() {
            val sensor = hr
            if (sensor == null) {
                writeRaw("# TYPE_HEART_RATE отсутствует")
                return
            }
            hrRegistered = sm.registerListener(this@NightLogService, sensor, 1_000_000, 0, handler)
            if (!hrRegistered) writeRaw("# HR register FAILED")
            handler.postDelayed(hrOff, HR_ON_MS)
        }
    }

    private val hrOff: Runnable = object : Runnable {
        override fun run() {
            if (hrRegistered) {
                sm.unregisterListener(this@NightLogService, hr)
                hrRegistered = false
            }
            handler.postDelayed(hrOn, if (mode == MODE_FULL) HR_OFF_FULL_MS else HR_OFF_ECO_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppCtx.init(this)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        hr = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        handlerThread = HandlerThread("probe-sensors").also { it.start() }
        handler = Handler(handlerThread.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startedAt = System.currentTimeMillis()
        if (intent?.action == ACTION_STOP) {
            Prefs.setRunning(this, false)
            // Критично: снимаем passive-мониторинг Health Services, иначе зелёный
            // PPG-датчик продолжает мерить пульс ~1 раз в секунду после остановки лога
            // (наблюдали 16+ минут непрерывного свечения после «стоп»).
            val ctx = applicationContext
            Thread {
                try {
                    HealthPassive.stop(ctx)
                } catch (_: Throwable) {
                }
                stopSelf()
            }.start()
            return START_NOT_STICKY
        }
        // Heartbeat: система разбудила приложение — сбрасываем бакет немедленно.
        // Если сервис почему-то не был запущен, дальше пойдёт обычный старт сессии.
        if (intent?.action == ACTION_FLUSH && isRunning) {
            handler.post { writeBucket() }
            return START_STICKY
        }
        if (isRunning) return START_STICKY

        try {
            mode = intent?.getStringExtra(EXTRA_MODE) ?: Prefs.mode(this)
            Prefs.setMode(this, mode)
            Prefs.setRunning(this, true)

            if (!startInForeground("Старт…")) {
                errorLog("не удалось войти в foreground, останавливаюсь", null)
                Prefs.setRunning(this, false)
                stopSelf()
                return START_NOT_STICKY
            }

            val noWake = intent?.getBooleanExtra(EXTRA_NOWAKELOCK, Prefs.noWake(this)) ?: Prefs.noWake(this)
            Prefs.setNoWake(this, noWake)
            if (!noWake) acquireWakeLock()
            openLog()
            writeRaw("# start mode=$mode wakelock=${!noWake} device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            writeRaw("# perms body=" + granted(android.Manifest.permission.BODY_SENSORS) +
                " bodyBg=" + granted(android.Manifest.permission.BODY_SENSORS_BACKGROUND) +
                " notif=" + granted(android.Manifest.permission.POST_NOTIFICATIONS))
            startBatteryReceiver()

            val ok = accel?.let { sm.registerListener(this, it, 40_000, 5_000_000, handler) } == true
            writeRaw("# accel register=$ok hrSensor=${hr?.name ?: "none"}")
            if (!ok) {
                startInForeground("Акселерометр недоступен")
            }

            handler.post(bucket)
            handler.post(hrOn)
            AlarmLogic.restoreFromPrefs(this)
            scheduleHeartbeat()
            Thread {
                val r = HealthPassive.start(this)
                writeRaw("# passive: " + r.replace("\n", " | "))
            }.start()
            isRunning = true
        } catch (t: Throwable) {
            errorLog("onStartCommand упал", t)
            Prefs.setRunning(this, false)
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun granted(p: String): Boolean =
        checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        Prefs.setRunning(this, false)
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
        sm.unregisterListener(this)
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (_: Throwable) {}
            batteryReceiverRegistered = false
        }
        finishSummary()
        cancelHeartbeat()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ---------- heartbeat: страховка от suspend ----------

    private fun heartbeatPi(): PendingIntent = PendingIntent.getBroadcast(
        this, HEARTBEAT_REQUEST,
        Intent(this, HeartbeatReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Точный alarm каждые 60 с. Нужен потому, что при suspend SoC
     * `Handler.postDelayed` (uptime) приходит с опозданием до минут,
     * а покрытие ночи падало до 21%.
     */
    private fun scheduleHeartbeat() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + HEARTBEAT_MS
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, heartbeatPi())
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, heartbeatPi())
            }
        } catch (t: Throwable) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, heartbeatPi())
            } catch (t2: Throwable) {
                errorLog("heartbeat schedule failed", t2)
            }
        }
    }

    private fun cancelHeartbeat() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(heartbeatPi())
        } catch (_: Throwable) {
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Probe", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, ProbeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OPW2 Probe: $mode")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_probe)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }

    private fun startInForeground(text: String): Boolean {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                return true
            } catch (t: Throwable) {
                errorLog("startForeground(health) не удался", t)
            }
        }
        return try {
            startForeground(NOTIFICATION_ID, n)
            true
        } catch (t: Throwable) {
            errorLog("startForeground(plain) не удался", t)
            false
        }
    }

    private fun errorLog(msg: String, t: Throwable?) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            if (!dir.exists()) dir.mkdirs()
            val sb = StringBuilder()
            sb.append(Instant.now()).append(" ").append(msg).append('\n')
            if (t != null) {
                sb.append(t.toString()).append('\n')
                sb.append(t.stackTraceToString()).append('\n')
            }
            File(dir, "probe_error.txt").appendText(sb.toString())
        } catch (_: Throwable) {
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opw2probe:night").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun startBatteryReceiver() {
        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun logFile(): File {
        val dir = getExternalFilesDir(null) ?: filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "probe_${mode}_${System.currentTimeMillis()}.csv")
    }

    private fun openLog() {
        val f = logFile()
        currentPath = f.absolutePath
        Prefs.setPath(this, currentPath)
        writer = BufferedWriter(FileWriter(f, true))
        writer?.write(CSV_HEADER)
        writer?.newLine()
        writer?.flush()
    }

    private fun writeRaw(line: String) {
        synchronized(lock) {
            try {
                writer?.write(line)
                writer?.newLine()
                writer?.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeBucket() {
        val now = System.currentTimeMillis()
        val b = lastBattery
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tempC = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
            .let { if (it == Int.MIN_VALUE) -999.0 else it / 10.0 }
        val powerMgr = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = powerMgr.isInteractive
        val doze = powerMgr.isDeviceIdleMode

        val n: Int
        val mean: Double
        val std: Double
        val p2p: Double
        val mv: Int
        val hrs: Int
        synchronized(lock) {
            n = accN
            mean = if (n > 0) accSum / n else 0.0
            val var0 = if (n > 0) (accSumSq / n) - mean * mean else 0.0
            std = if (var0 > 0) sqrt(var0) else 0.0
            p2p = if (n > 0) accMax - accMin else 0.0
            mv = moves
            hrs = hrSamples
            accN = 0; accSum = 0.0; accSumSq = 0.0
            accMin = Double.MAX_VALUE; accMax = -Double.MAX_VALUE
            moves = 0; hrSamples = 0
        }

        // P0: в CSV пишем ЕДИНЫЙ HR (свой сенсор ИЛИ passive Health Services).
        // Раньше здесь был только свой сенсор, который на OPW2 отдаёт 0,
        // и колонка выглядела мёртвой даже когда passive HR реально шёл.
        val hsHr = HealthState.freshHr()
        val hb = hsHr?.toInt() ?: -1
        val hAge = if (hsHr != null) (now - HealthState.hrTs) / 1000 else -1

        val iso = Instant.ofEpochMilli(now).toString()
        // P0: диагностируемость ночи — откуда HR, подтверждён ли сон, состояние будильника
        val hrSrc = HealthState.sourceNow()
        val sawAsleep = AlarmLogic.sawAsleepNow()
        val alarmState = AlarmLogic.stateLabel()
        val line = listOf(
            iso, now, mode, pct, charging, tempC, screenOn, doze,
            hb, hAge, n, fmt(mean), fmt(std), fmt(p2p), mv, hrs,
            SystemClock.elapsedRealtime() / 1000,
            hrSrc, sawAsleep, alarmState,
        ).joinToString(",")
        writeRaw(line)
        AlarmLogic.onBucket(this, n, mv, std, p2p)
        startInForeground("${if (pct >= 0) "$pct%" else "?"} · HR $hb · acc $n")
        scheduleHeartbeat()
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)

    private fun finishSummary() {
        val w = writer
        val path = currentPath
        try {
            if (w != null) {
                w.write("# end ${Instant.now()} uptime_s=${SystemClock.elapsedRealtime() / 1000}")
                w.newLine()
                w.flush()
                w.close()
            }
        } catch (_: Throwable) {
        }
        writer = null
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "summary.txt").appendText(
                "mode=$mode start=$startedAt end=${System.currentTimeMillis()} " +
                    "dur_min=${(System.currentTimeMillis() - startedAt) / 60000} file=$path\n"
            )
        } catch (_: Throwable) {
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                val m = sqrt(x * x + y * y + z * z)
                val moving = abs(m - GRAVITY) > 1.5
                synchronized(lock) {
                    accN++
                    accSum += m
                    accSumSq += m * m
                    if (m < accMin) accMin = m
                    if (m > accMax) accMax = m
                    if (moving) moves++
                }
            }
            Sensor.TYPE_HEART_RATE -> {
                val v = event.values.firstOrNull() ?: return
                if (v <= 0f) return
                val ts = System.currentTimeMillis()
                synchronized(lock) {
                    hrBpm = v.toInt()
                    hrAt = ts
                    hrSamples++
                }
                // P0: свой сенсор теперь тоже питает HealthState.
                // Раньше запись была только локальной, а AlarmLogic читал HealthState,
                // который заполнялся лишь passive-путём Health Services (на OPW2 он молчит).
                HealthState.updateHr(v, ts, "SENSOR", "sensor")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

object Prefs {
    private const val NAME = "probe"
    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun setRunning(c: Context, v: Boolean) = sp(c).edit().putBoolean("running", v).apply()
    fun isRunning(c: Context) = sp(c).getBoolean("running", false)
    fun setMode(c: Context, v: String) = sp(c).edit().putString("mode", v).apply()
    fun mode(c: Context) = sp(c).getString("mode", NightLogService.MODE_ECO) ?: NightLogService.MODE_ECO
    fun setPath(c: Context, v: String) = sp(c).edit().putString("path", v).apply()
    fun path(c: Context) = sp(c).getString("path", "") ?: ""
    fun setNoWake(c: Context, v: Boolean) = sp(c).edit().putBoolean("nowake", v).apply()
    fun noWake(c: Context) = sp(c).getBoolean("nowake", false)
    fun setAlarmAt(c: Context, v: Long) = sp(c).edit().putLong("alarm_at", v).apply()
    fun alarmAt(c: Context) = sp(c).getLong("alarm_at", 0L)
    fun setWindowMin(c: Context, v: Int) = sp(c).edit().putInt("window_min", v).apply()
    fun windowMin(c: Context) = sp(c).getInt("window_min", 30)
    fun setTargetHour(c: Context, v: Int) = sp(c).edit().putInt("target_h", v).apply()
    fun targetHour(c: Context) = sp(c).getInt("target_h", 7)
    fun setTargetMinute(c: Context, v: Int) = sp(c).edit().putInt("target_m", v).apply()
    fun targetMinute(c: Context) = sp(c).getInt("target_m", 0)
}
