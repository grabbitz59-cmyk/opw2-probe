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
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
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
    private lateinit var rootFrame: FrameLayout
    private lateinit var pager: CarouselPager
    private var ambient: AmbientLifecycleObserver? = null

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

    private val screenW: Float get() = resources.displayMetrics.widthPixels.toFloat()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(applicationContext)
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        buildUi()
        setupAmbient()
        // Отладка: adb shell am start -n io.github.smartalarm/.HomeActivity --ez force_ambient true
        // Позволяет увидеть ambient-отрисовку даже при выключенном AOD на устройстве.
        if (intent?.getBooleanExtra("force_ambient", false) == true) {
            handler.postDelayed({ applyAmbient(true) }, 400L)
        }
    }

    /**
     * Ambient (AOD) по скиллу wear-os-ui, R9: в покое остаются только время и кольцо,
     * без кнопок, градиента и пульсации — так экран не выжигает AMOLED.
     */
    private fun setupAmbient() {
        ambient = AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(details: AmbientLifecycleObserver.AmbientDetails) {
                    applyAmbient(true)
                }

                override fun onUpdateAmbient() {
                    refresh()
                    dial.invalidate()
                }

                override fun onExitAmbient() {
                    applyAmbient(false)
                }
            },
        )
    }

    private fun applyAmbient(on: Boolean) {
        dial.setDimmed(on)
        statusView.visibility = if (on) View.INVISIBLE else View.VISIBLE
        windowPill.visibility = if (on) View.INVISIBLE else View.VISIBLE
        cta.visibility = if (on) View.INVISIBLE else View.VISIBLE
        rootFrame.setBackgroundColor(if (on) android.graphics.Color.BLACK else Ui.bg)
        if (!on) dial.animateRing()
    }

    override fun onResume() {
        super.onResume()
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        // Возвращаемся на место после слайд-перехода (leftover translation)
        rootFrame.translationX = 0f
        rootFrame.alpha = 1f
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
        rootFrame = root

        dial = DialView(this).apply {
            setOnClickListener { openSetup() }
            setOnLongClickListener {
                // Долгое нажатие по циферблату — история ночи (вторая страница карусели)
                openStats()
                true
            }
        }
        root.addView(dial, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        statusView = TextView(this).apply {
            textSize = 13f
            typeface = Ui.labelTypeface()
            setTextColor(Ui.textSecondary)
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        root.addView(statusView, overlay(topPx = 206, heightPx = 34))

        windowPill = TextView(this).apply {
            text = "окно ±$windowMin мин"
            textSize = 13f
            typeface = Ui.labelTypeface()
            gravity = Gravity.CENTER
            setTextColor(Ui.textSecondary)
            setBackgroundResource(R.drawable.bg_pill_off)
            setOnClickListener {
                windowMin = when (windowMin) { 15 -> 30; 30 -> 45; 45 -> 60; else -> 15 }
                Prefs.setWindowMin(this@HomeActivity, windowMin)
                refresh()
            }
            // Долгое нажатие — повтор по дням (выкл → будни → все дни).
            // Отдельная кнопка не нужна: экран по скиллу держит одно главное действие.
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val next = RepeatDays.nextMask(Prefs.repeatDays(this@HomeActivity))
                Prefs.setRepeatDays(this@HomeActivity, next)
                refresh()
                true
            }
        }
        root.addView(windowPill, overlay(topPx = 250, heightPx = 56))

        cta = TextView(this).apply {
            textSize = 15f
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

        // Индикатор разделов: 1/3 — мы на «Будильнике»
        val dots = PageDotsView(this).apply { setPage(0, 3) }
        root.addView(
            dots,
            FrameLayout.LayoutParams(
                (90 * resources.displayMetrics.density).toInt(),
                (18 * resources.displayMetrics.density).toInt(),
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            },
        )

        // Листание разделов: свайп влево открывает историю ночи (стабильный проверенный путь).
        // Карусель (CarouselPager) оставлена в проекте, но выключена: после первого перехода
        // пейджер перестаёт получать жесты (см. трассировку carousel в alarm_debug.txt).
        // Следующий заход — на ViewPager2, где доставка касаний решена платформой.
        setContentView(root)
        root.post { applyGeometry(root) }
    }

    // ---------- листание разделов (R11–R12) ----------
    private var swipeDownX = 0f
    private var swipeDownY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
            }

            MotionEvent.ACTION_UP -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (swipeDownX > 90f && dx <= -90f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f) {
                    openStats()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Открыть историю ночи отдельным экраном. */
    private fun openStats() {
        startActivity(Intent(this, StatsActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun overlay(topPx: Int, heightPx: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = topPx
        }

    /** Ширины по геометрии круга нашей модели (R1–R2). */
    private fun applyGeometry(root: FrameLayout) {
        fun cap(view: TextView, maxW: Float) {
            val lp = view.layoutParams as FrameLayout.LayoutParams
            val top = lp.topMargin.toFloat()
            val bottom = top + lp.height
            val safe = RoundScreen.safeRowWidth(top, bottom)
            lp.width = minOf(maxW, if (safe > 0f) safe else maxW).toInt()
            view.layoutParams = lp
        }
        cap(statusView, 420f)
        cap(windowPill, 340f)
        cap(cta, 240f)
    }


    private fun snapBack() {
        rootFrame.animate().translationX(0f).alpha(1f).setDuration(160L).start()
    }

    /**
     * Циклическая прокрутка: с главного в историю можно уйти в ЛЮБУЮ сторону —
     * свайп влево или вправо одинаково перелистывает на следующую «страницу».
     */
    private fun openStatsWithSlide(toLeft: Boolean) {
        val target = if (toLeft) -screenW else screenW
        rootFrame.animate()
            .translationX(target)
            .alpha(0.35f)
            .setDuration(140L)
            .withEndAction {
                startActivity(Intent(this, StatsActivity::class.java))
                if (toLeft) {
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                } else {
                    overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
                }
                rootFrame.translationX = 0f
                rootFrame.alpha = 1f
            }
            .start()
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

        val repeat = Prefs.repeatDays(this)
        windowPill.text = if (repeat == RepeatDays.NONE) "окно ±$windowMin мин"
        else "окно ±$windowMin · ${RepeatDays.label(repeat)}"

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
        dial.animateRing()
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

    /**
     * Страховка для карусели: если жест не дошёл (например, его съела система),
     * кнопка/жест «назад» с истории возвращает на главный, а не закрывает приложение.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}