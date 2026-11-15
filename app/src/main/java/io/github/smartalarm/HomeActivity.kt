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
    private lateinit var content: FrameLayout
    private lateinit var swipe: SectionSwipe

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
        // Ambient/AOD не поддерживаем: это приложение (будильник + лог сна), а не циферблат.
        // Экран просто гаснет по системному таймауту — выжигать AMOLED нечем, «тихого» режима нет.
    }

    override fun onResume() {
        super.onResume()
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        // Возвращаемся на место после slide-перехода между разделами
        rootFrame.translationX = 0f
        rootFrame.alpha = 1f
        if (::swipe.isInitialized) swipe.reset()
        refresh()
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor(Ui.BG_TOP), Color.parseColor(Ui.BG_BOTTOM)),
            )
        }
        rootFrame = root

        // Градиент остаётся на неподвижном root, а за пальцем едет прозрачный слой с контентом.
        // Иначе при свайпе уезжал и фон, и с края открывался чёрный прямоугольник окна — это
        // читалось как рывок/мигание.
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        dial = DialView(this).apply {
            setOnClickListener { openSetup() }
            setOnLongClickListener {
                // Долгое нажатие по циферблату — история ночи (вторая страница карусели)
                openStats()
                true
            }
        }
        content.addView(dial, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        statusView = TextView(this).apply {
            textSize = 11f
            typeface = Ui.labelTypeface()
            letterSpacing = 0.12f
            setTextColor(Ui.textSecondary)
            gravity = Gravity.CENTER
            setSingleLine(true)
        }
        content.addView(statusView, overlay(topPx = STATUS_TOP, heightPx = STATUS_H))

        windowPill = TextView(this).apply {
            text = "окно ±$windowMin мин"
            textSize = 11f
            typeface = Ui.labelTypeface()
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            setTextColor(Ui.textSecondary)
            background = pillBackground()
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
        content.addView(windowPill, overlay(topPx = PILL_TOP, heightPx = PILL_H))

        cta = TextView(this).apply {
            textSize = 13f
            typeface = Ui.labelTypeface()
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTextColor(Ui.onAccent)
            background = ctaBackground(false)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onPrimary()
            }
        }
        content.addView(cta, overlay(topPx = CTA_TOP, heightPx = CTA_H))

        // Листание разделов: свайп влево открывает историю ночи (стабильный проверенный путь).
        // Карусель (CarouselPager) оставлена в проекте, но выключена: после первого перехода
        // пейджер перестаёт получать жесты (см. трассировку carousel в alarm_debug.txt).
        // Следующий заход — на ViewPager2, где доставка касаний решена платформой.
        setContentView(root)
        swipe = SectionSwipe(this, Sections.HOME, content)
        content.post { applyGeometry() }
    }

    // ---------- листание разделов (R11–R13): циклический свайп 1 → 2 → 3 → 1 ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::swipe.isInitialized) swipe.handle(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Долгое нажатие на циферблате — тот же переход вперёд, что и свайп влево. */
    private fun openStats() {
        val intent = Intent(this, StatsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        // exit = 0: окно не двигаем второй раз, его контент уводит наш сдвиг (см. SwipeSections).
        overridePendingTransition(Sections.animEnter(true), 0)
    }

    private fun overlay(topPx: Int, heightPx: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = topPx
        }

    /** Ширины по геометрии круга: потолок из дизайна, но не шире, чем позволяет кольцо. */
    private fun applyGeometry() {
        fun cap(view: TextView, maxW: Float) {
            val lp = view.layoutParams as FrameLayout.LayoutParams
            val top = lp.topMargin.toFloat()
            val bottom = top + lp.height
            val safe = ringSafeWidth(top, bottom)
            lp.width = minOf(maxW, if (safe > 0f) safe else maxW).toInt()
            view.layoutParams = lp
        }
        cap(statusView, W_STATUS)
        cap(windowPill, W_PILL)
        cap(cta, W_CTA)
    }

    /**
     * Ширина, при которой полоса `top…bottom` ещё **не заезжает на кольцо**.
     *
     * В v1 капсула окна шириной 392 px и кнопка 222 px ложились концами на дугу кольца
     * (внутренний край кольца ≈ 190 px от центра), из-за чего центр выглядел «налезающим»
     * на кольцо. Считаем по радиусу [RING_INNER_R] = 186 px: так между контентом и дугой
     * остаётся зазор ~4 px, и кольцо видно по всей окружности.
     */
    private fun ringSafeWidth(top: Float, bottom: Float): Float {
        val dy = maxOf(kotlin.math.abs(top - CY), kotlin.math.abs(bottom - CY))
        if (dy >= RING_INNER_R) return 0f
        return 2f * kotlin.math.sqrt(RING_INNER_R * RING_INNER_R - dy * dy)
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

    /**
     * Синхронизация с системным будильником часов.
     *
     * Если на часах есть «чужой» будильник (собственное приложение часов, а при включённой
     * синхронизации в паре телефон↔часы — будильник телефона) и наш ещё не взведён,
     * берём его время как цель умного будильника.
     */
    private fun adoptSystemAlarm() {
        val info = SystemAlarm.read(this) ?: return
        if (info.isOurs || isArmed()) return
        val cal = Calendar.getInstance().apply { timeInMillis = info.triggerAt }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        if (h == hour && m == minute) return
        hour = h
        minute = m
        Prefs.setTargetHour(this, h)
        Prefs.setTargetMinute(this, m)
        AlarmLogic.log(
            this,
            "synced target from system alarm " +
                String.format(Locale.US, "%02d:%02d", h, m) + " (${info.creator})",
        )
    }

    private fun refresh() {
        adoptSystemAlarm()
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
                "НЕТ ПРАВ: " + missing.joinToString(" · ") { it.substringAfterLast('.') }
            running -> "ЗАПИСЬ · ${diffMin / 60}Ч ${diffMin % 60}М · ${batteryPct()}%"
            armed -> "${if (tomorrow) "ЗАВТРА" else "СЕГОДНЯ"} · ${diffMin / 60}Ч ${diffMin % 60}М"
            else -> "НЕ ВЗВЕДЁН · ${batteryPct()}%"
        }

        val repeat = Prefs.repeatDays(this)
        windowPill.text = if (repeat == RepeatDays.NONE) "окно ±$windowMin"
        else "±$windowMin · ${RepeatDays.label(repeat)}"

        when {
            armed && running -> {
                cta.text = "ОСТАНОВИТЬ"
                cta.background = ctaBackground(danger = true)
                cta.setTextColor(Ui.text)
            }
            armed -> {
                cta.text = "НАЧАТЬ ЗАПИСЬ"
                cta.background = ctaBackground(danger = false)
                cta.setTextColor(Ui.onAccent)
            }
            else -> {
                cta.text = "СПАТЬ"
                cta.background = ctaBackground(danger = false)
                cta.setTextColor(Ui.onAccent)
            }
        }
    }

    /** Капсула: тёмная поверхность с тонкой обводкой. */
    private fun pillBackground(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 30f
            setColor(Color.parseColor(Ui.SURFACE_HI))
            setStroke(2, Color.parseColor(Ui.STROKE_HI))
        }

    /** Главная кнопка: градиент акцента (или красный для «остановить»). */
    private fun ctaBackground(danger: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            if (danger) {
                intArrayOf(Color.parseColor("#FF7A7A"), Color.parseColor(Ui.DANGER_DIM))
            } else {
                intArrayOf(Color.parseColor(Ui.ACCENT_FROM), Color.parseColor(Ui.ACCENT_TO))
            },
        ).apply { cornerRadius = 48f }

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

    private companion object {
        /**
         * Вертикаль центрального блока — компактнее, чем в v1: блок (статус → капсула окна →
         * кнопка) собран в 190…388 px, чтобы целиком лежать внутри кольца и не заезжать на дугу.
         * В v1 капсула стояла 244…304 шириной 392, и её концы ложились на кольцо.
         */
        const val STATUS_TOP = 192
        const val STATUS_H = 32
        const val PILL_TOP = 232
        const val PILL_H = 52
        const val CTA_TOP = 292
        const val CTA_H = 96

        /** Ширины-потолки: фактическая ширина дополнительно урезается окружностью кольца. */
        const val W_STATUS = 340f
        const val W_PILL = 320f
        const val W_CTA = 205f

        /** Центр экрана и внутренний радиус кольца (233 − 24 − толщина 16 − зазор 7). */
        const val CY = 233f
        const val RING_INNER_R = 186f
    }
}