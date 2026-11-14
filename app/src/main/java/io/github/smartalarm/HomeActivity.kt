package io.github.smartalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale

/**
 * Главный экран по скиллу wear-os-ui:
 * кольцо прогресса на весь экран, время в центре, под ним — статус, окно и одно действие.
 *
 * Широкие элементы стоят в средней полосе: у верхней и нижней дуги ширина круга падает
 * почти до нуля (R1–R2). Вторичный экран (история ночи) — long-press по циферблату.
 */
class HomeActivity : ComponentActivity() {

    private lateinit var dial: DialView
    private lateinit var statusView: TextView
    private lateinit var windowPill: TextView
    private lateinit var cta: TextView

    private var hour = 7
    private var minute = 0
    private var windowMin = 30

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5_000L)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh()
        if (missingPerms().isEmpty()) statusView.text = "разрешения выданы"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(applicationContext)
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        refresh()
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.bg) }

        dial = DialView(this).apply {
            setOnClickListener { openSetup() }
            setOnLongClickListener {
                startActivity(Intent(this@HomeActivity, StatsActivity::class.java))
                true
            }
        }
        root.addView(dial, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        statusView = TextView(this).apply {
            textSize = 10f
            typeface = Ui.labelTypeface()
            setTextColor(Ui.textSecondary)
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        root.addView(statusView, overlay(topPx = 216, heightPx = 26))

        windowPill = TextView(this).apply {
            text = "окно ±$windowMin мин"
            textSize = 11f
            typeface = Ui.labelTypeface()
            gravity = Gravity.CENTER
            setTextColor(Ui.textSecondary)
            setBackgroundResource(R.drawable.bg_pill_off)
            setOnClickListener {
                windowMin = when (windowMin) { 15 -> 30; 30 -> 45; 45 -> 60; else -> 15 }
                Prefs.setWindowMin(this@HomeActivity, windowMin)
                refresh()
            }
        }
        root.addView(windowPill, overlay(topPx = 250, heightPx = 56))

        cta = TextView(this).apply {
            textSize = 12f
            typeface = Ui.labelTypeface()
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#04211D"))
            setBackgroundResource(R.drawable.bg_cta)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onPrimary()
            }
        }
        root.addView(cta, overlay(topPx = 314, heightPx = 88))

        setContentView(root)
        root.post { applyGeometry(root) }
    }

    private fun overlay(topPx: Int, heightPx: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = topPx
        }

    /** Ширины по геометрии круга (R1–R2): берём фактические границы строки. */
    private fun applyGeometry(root: FrameLayout) {
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        fun cap(view: TextView, maxW: Float) {
            val lp = view.layoutParams as FrameLayout.LayoutParams
            val top = lp.topMargin.toFloat()
            val bottom = top + lp.height
            val safe = RoundScreen.safeRowWidth(top, bottom, sw, sh)
            lp.width = minOf(maxW, if (safe > 0f) safe else maxW).toInt()
            view.layoutParams = lp
        }
        cap(statusView, 420f)
        cap(windowPill, 340f)
        cap(cta, 240f)
    }

    // ---------- состояние ----------

    private fun refresh() {
        val armed = isArmed()
        val running = NightLogService.isRunning
        val target = currentTarget()

        dial.timeLabel = String.format(Locale.US, "%02d:%02d", hour, minute)
        dial.armed = armed
        dial.running = running
        dial.targetAt = target

        val diffMin = ((target - System.currentTimeMillis()) / 60_000).toInt().coerceAtLeast(0)
        val tomorrow = Calendar.getInstance().apply { timeInMillis = target }
            .get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

        val missing = missingPerms()
        statusView.setTextColor(if (missing.isEmpty()) Ui.textSecondary else Ui.danger)
        statusView.text = when {
            missing.isNotEmpty() ->
                "нет прав: " + missing.joinToString(", ") { it.substringAfterLast('.') }
            running -> "идёт запись · ${diffMin / 60}ч ${diffMin % 60}м · ${batteryPct()}%"
            armed -> "${if (tomorrow) "завтра" else "сегодня"} · ${diffMin / 60}ч ${diffMin % 60}м"
            else -> "не взведён · ${batteryPct()}%"
        }

        windowPill.text = "окно ±$windowMin мин"

        if (armed && running) {
            cta.text = "ОСТАНОВИТЬ"
            cta.setBackgroundResource(R.drawable.bg_cta_stop)
            cta.setTextColor(Ui.text)
        } else if (armed) {
            cta.text = "ЗАПУСТИТЬ ЗАПИСЬ"
            cta.setBackgroundResource(R.drawable.bg_cta)
            cta.setTextColor(Color.parseColor("#04211D"))
        } else {
            cta.text = "СПАТЬ"
            cta.setBackgroundResource(R.drawable.bg_cta)
            cta.setTextColor(Color.parseColor("#04211D"))
        }
    }

    private fun currentTarget(): Long {
        val stored = Prefs.alarmAt(this)
        val live = AlarmLogic.targetAt
        val at = if (live > 0) live else stored
        return if (at > System.currentTimeMillis()) at else targetEpoch()
    }

    private fun isArmed(): Boolean {
        val live = AlarmLogic.targetAt
        val stored = Prefs.alarmAt(this)
        val at = if (live > 0) live else stored
        return at > System.currentTimeMillis()
    }

    private fun targetEpoch(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 60_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun missingPerms(): List<String> {
        val wanted = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.BODY_SENSORS_BACKGROUND)
        return wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun batteryPct(): Int = try {
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) {
        -1
    }

    // ---------- действия ----------

    private fun onPrimary() {
        if (isArmed() && NightLogService.isRunning) {
            stopNight()
            return
        }
        val missing = missingPerms()
        if (missing.isNotEmpty()) {
            statusView.setTextColor(Ui.danger)
            statusView.text = "сначала разрешения"
            permLauncher.launch(missing.toTypedArray())
            return
        }

        val at = targetEpoch()
        Prefs.setTargetHour(this, hour)
        Prefs.setTargetMinute(this, minute)
        Prefs.setWindowMin(this, windowMin)
        AlarmLogic.arm(this, at, windowMin)
        AlarmLogic.log(this, "home arm ${String.format(Locale.US, "%02d:%02d", hour, minute)} w=$windowMin")

        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_START
            putExtra(NightLogService.EXTRA_MODE, NightLogService.MODE_ECO)
            putExtra(NightLogService.EXTRA_NOWAKELOCK, false)
        }
        try {
            ContextCompat.startForegroundService(this, svc)
            statusView.text = "запускаю ночь…"
        } catch (t: Throwable) {
            statusView.text = "не удалось: ${t.javaClass.simpleName}"
            return
        }

        handler.postDelayed({
            if (!NightLogService.isRunning) statusView.text = "лог НЕ запустился — long-press → история"
            refresh()
        }, 2_500L)
    }

    private fun stopNight() {
        AlarmLogic.disarm(this)
        try {
            startService(Intent(this, NightLogService::class.java).apply {
                action = NightLogService.ACTION_STOP
            })
        } catch (_: Throwable) {
        }
        val ctx = applicationContext
        Thread {
            try {
                HealthPassive.stop(ctx)
            } catch (_: Throwable) {
            }
        }.start()
        handler.postDelayed({ refresh() }, 1_200L)
    }

    private fun openSetup() {
        startActivity(Intent(this, AlarmSetupActivity::class.java))
    }
}
