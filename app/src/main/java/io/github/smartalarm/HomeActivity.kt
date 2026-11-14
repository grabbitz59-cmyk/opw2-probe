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
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale

/**
 * Главный экран: круглый циферблат с кольцом прогресса до подъёма,
 * капсулы выбора окна, одна акцентная кнопка и три сервисные кнопки внизу.
 *
 * Логика та же, что была в 1.16 — изменена только подача: круг вместо списка строк,
 * тонкий шрифт времени, градиентный акцент и честный статус в центре.
 */
class HomeActivity : ComponentActivity() {

    private lateinit var dial: DialView
    private lateinit var primary: TextView
    private val chips = LinkedHashMap<Int, TextView>()

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
        if (missingPerms().isEmpty()) dial.status = "разрешения выданы"
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.bg)
            setPadding(dp(10), dp(7), dp(10), dp(9))
        }

        dial = DialView(this).apply {
            setOnClickListener { openSetup() }
        }
        root.addView(
            dial,
            LinearLayout.LayoutParams(dp(112), dp(112)),
        )

        root.addView(capsuleRow(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(5) })

        primary = TextView(this).apply {
            text = "СОХРАНИТЬ И СПАТЬ"
            textSize = 12f
            typeface = Ui.labelTypeface()
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#04211D"))
            setBackgroundResource(R.drawable.bg_cta)
            setOnClickListener { onPrimary() }
        }
        root.addView(primary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38),
        ).apply { topMargin = dp(6) })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(
            mini("Ночь") { startActivity(Intent(this@HomeActivity, StatsActivity::class.java)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(3) },
        )
        bottom.addView(
            mini("Тест") { testAlarm() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(3) },
        )
        bottom.addView(
            mini("Диагн.") { startActivity(Intent(this@HomeActivity, ProbeActivity::class.java)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(bottom, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(5) })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Ui.bg)
            addView(root)
        })
    }

    private fun capsuleRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (w in intArrayOf(15, 30, 45, 60)) {
            val pill = TextView(this).apply {
                text = "$w"
                textSize = 12f
                typeface = Ui.labelTypeface()
                gravity = Gravity.CENTER
                setOnClickListener {
                    windowMin = w
                    Prefs.setWindowMin(this@HomeActivity, w)
                    refresh()
                }
            }
            row.addView(
                pill,
                LinearLayout.LayoutParams(0, dp(26), 1f).apply { marginEnd = dp(3) },
            )
            chips[w] = pill
        }
        return row
    }

    private fun mini(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        typeface = Ui.labelTypeface()
        gravity = Gravity.CENTER
        setTextColor(Ui.textSecondary)
        setBackgroundResource(R.drawable.bg_pill_off)
        setPadding(0, dp(7), 0, dp(7))
        setOnClickListener { onClick() }
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

        val diffMin = (((target - System.currentTimeMillis()) / 60_000).toInt()).coerceAtLeast(0)
        val tomorrow = Calendar.getInstance().apply { timeInMillis = target }
            .get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        dial.subtitle = when {
            running -> "запись идёт · ±$windowMin мин"
            armed -> "${if (tomorrow) "завтра" else "сегодня"} ${diffMin / 60}ч ${diffMin % 60}м"
            else -> "не взведён · ±$windowMin мин"
        }

        val missing = missingPerms()
        dial.status = when {
            missing.isNotEmpty() -> "нет прав: " + missing.joinToString(", ") { it.substringAfterLast('.') }
            else -> buildString {
                append(if (running) "LOG ON" else "LOG OFF")
                append(" · HR ").append(HealthState.sourceNow())
                append(" · ").append(batteryPct()).append('%')
            }
        }

        for ((w, pill) in chips) {
            val on = w == windowMin
            pill.setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
            pill.setTextColor(if (on) Color.parseColor("#04211D") else Ui.textSecondary)
        }

        if (armed && running) {
            primary.text = "ОСТАНОВИТЬ НОЧЬ"
            primary.setBackgroundResource(R.drawable.bg_cta_stop)
            primary.setTextColor(Ui.text)
        } else if (armed) {
            primary.text = "ВЗВЕДЁН · ЗАПУСТИТЬ ЗАПИСЬ"
            primary.setBackgroundResource(R.drawable.bg_cta)
            primary.setTextColor(Color.parseColor("#04211D"))
        } else {
            primary.text = "СОХРАНИТЬ И СПАТЬ"
            primary.setBackgroundResource(R.drawable.bg_cta)
            primary.setTextColor(Color.parseColor("#04211D"))
        }
    }

    private fun currentTarget(): Long {
        val stored = Prefs.alarmAt(this)
        val live = AlarmLogic.targetAt
        val at = if (live > 0) live else stored
        return if (at > System.currentTimeMillis()) at else targetEpoch()
    }

    private fun isArmed(): Boolean = currentTargetStrict() > System.currentTimeMillis()

    private fun currentTargetStrict(): Long {
        val live = AlarmLogic.targetAt
        val stored = Prefs.alarmAt(this)
        return if (live > 0) live else stored
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
            dial.status = "сначала разрешения"
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
            dial.status = "запускаю ночь…"
            refresh()
        } catch (t: Throwable) {
            dial.status = "не удалось запустить: ${t.javaClass.simpleName}"
            return
        }

        // Честный статус: не рапортовать успех, если сервис не поднялся
        handler.postDelayed({
            if (!NightLogService.isRunning) dial.status = "лог НЕ запустился — открой Диагностику"
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

    private fun testAlarm() {
        sendBroadcast(Intent(this, AlarmTestReceiver::class.java))
        dial.status = "тестовый будильник через 2 сек"
    }

    private fun openSetup() {
        startActivity(Intent(this, AlarmSetupActivity::class.java))
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
