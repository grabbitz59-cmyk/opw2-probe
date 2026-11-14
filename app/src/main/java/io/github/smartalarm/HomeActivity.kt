package io.github.smartalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
 * Минимальный продуктовый экран: одно решение — «во сколько» и «разбуди в окне».
 *
 * Всё диагностическое (35 кнопок) осталось в [ProbeActivity] и открывается по «Диагностика».
 * Вёрстка рассчитана на круглый 466×466 при плотности 320 dpi (233 dp): тап-зоны крупные,
 * нижний ряд не упирается в дугу экрана.
 */
class HomeActivity : ComponentActivity() {

    private lateinit var timeView: TextView
    private lateinit var subView: TextView
    private lateinit var statusView: TextView
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
        if (missingPerms().isEmpty()) statusView.text = "разрешения выданы · можно ложиться"
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
            setBackgroundColor(bg)
            // нижний отступ держит контент внутри круглого экрана
            setPadding(dp(14), dp(8), dp(14), dp(16))
        }

        timeView = TextView(this).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            textSize = 34f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            setOnClickListener { openSetup() }
        }
        root.addView(timeView, lp())

        subView = TextView(this).apply {
            setTextColor(Color.parseColor("#80CBC4"))
            textSize = 10f
            gravity = Gravity.CENTER
        }
        root.addView(subView, lp())

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (w in intArrayOf(15, 30, 45, 60)) {
            val chip = TextView(this).apply {
                text = "$w"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener {
                    windowMin = w
                    Prefs.setWindowMin(this@HomeActivity, w)
                    refresh()
                }
            }
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) },
            )
            chips[w] = chip
        }
        root.addView(chipRow, lp(top = 5))

        primary = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#04211D"))
            setPadding(0, dp(13), 0, dp(13))
            setOnClickListener { onPrimary() }
        }
        root.addView(primary, lp(top = 5))

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 9f
            gravity = Gravity.CENTER
        }
        root.addView(statusView, lp(top = 4))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(
            small("Ночь") {
                startActivity(Intent(this@HomeActivity, StatsActivity::class.java))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(3) },
        )
        bottom.addView(
            small("Тест") { testAlarm() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(3) },
        )
        bottom.addView(
            small("Диагн.") {
                startActivity(Intent(this@HomeActivity, ProbeActivity::class.java))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(bottom, lp(top = 4))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(root)
        })
    }

    private fun small(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#B0BEC5"))
        setBackgroundResource(R.drawable.bg_chip_off)
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int = 2) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    // ---------- состояние ----------

    private fun refresh() {
        timeView.text = String.format(Locale.US, "%02d:%02d", hour, minute)

        val at = targetEpoch()
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val now = Calendar.getInstance()
        val tomorrow = cal.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR)
        val diffMin = ((at - now.timeInMillis) / 60000).toInt().coerceAtLeast(0)
        subView.text = buildString {
            append(if (tomorrow) "завтра" else "сегодня")
            append(" через ${diffMin / 60}ч ${diffMin % 60}м")
            append(" · окно ±$windowMin")
        }

        for ((w, chip) in chips) {
            val on = w == windowMin
            chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            chip.setTextColor(if (on) Color.parseColor("#04211D") else Color.parseColor("#CFD8DC"))
        }

        val armed = isArmed()
        val running = NightLogService.isRunning
        if (armed && running) {
            primary.text = "ОСТАНОВИТЬ НОЧЬ"
            primary.setBackgroundResource(R.drawable.bg_cta_stop)
            primary.setTextColor(Color.WHITE)
        } else {
            primary.text = "СОХРАНИТЬ И СПАТЬ"
            primary.setBackgroundResource(R.drawable.bg_cta)
            primary.setTextColor(Color.parseColor("#04211D"))
        }

        val missing = missingPerms()
        statusView.setTextColor(Color.parseColor(if (missing.isEmpty()) "#90A4AE" else "#FF8A65"))
        statusView.text = when {
            missing.isNotEmpty() -> "нет прав: " + missing.joinToString(", ") { it.substringAfterLast('.') }
            else -> buildString {
                append(if (running) "лог идёт" else "лог выкл")
                append(" · HR ").append(HealthState.sourceNow())
                append(" · ").append(batteryPct()).append('%')
            }
        }
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
            statusView.setTextColor(Color.parseColor("#FF8A65"))
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
            statusView.setTextColor(Color.parseColor("#90A4AE"))
            statusView.text = "запускаю ночь…"
        } catch (t: Throwable) {
            statusView.setTextColor(Color.parseColor("#FF8A65"))
            statusView.text = "не удалось запустить: ${t.javaClass.simpleName}"
            return
        }

        // Честный статус: не рапортовать успех, если сервис не поднялся
        handler.postDelayed({
            if (!NightLogService.isRunning) {
                statusView.setTextColor(Color.parseColor("#FF8A65"))
                statusView.text = "лог НЕ запустился — открой Диагностику"
            }
            refresh()
        }, 2_500L)
    }

    private fun stopNight() {
        AlarmLogic.disarm(this)
        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_STOP
        }
        try {
            startService(svc)
        } catch (_: Throwable) {
        }
        // Страховка: даже если сервис уже не запущен, снимаем passive-мониторинг,
        // иначе PPG-датчик (зелёный диод) продолжает мерить пульс.
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
        statusView.text = "тестовый будильник через 2 сек"
    }

    private fun openSetup() {
        startActivity(Intent(this, AlarmSetupActivity::class.java))
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private val bg = Color.parseColor("#0A0E10")
}
