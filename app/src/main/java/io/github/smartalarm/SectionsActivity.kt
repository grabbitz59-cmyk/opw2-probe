package io.github.smartalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Единственная Activity приложения: три раздела — три страницы в одном окне.
 *
 * Почему так, а не три Activity: замерено на часах — окно новой Activity появляется через
 * 183–551 мс, и это время уходит из того же главного потока, который рисует анимацию свайпа.
 * Любая «сглаживающая» надстройка поверх этого упиралась в замирание. Здесь свайп двигает
 * готовые View: ни создания Activity, ни инфляции, ни пересоздания состояния.
 *
 * Разделы циклические: `1 → 2 → 3 → 1`, страницы расставлены по кругу — текущая в центре,
 * следующая справа, предыдущая слева. После доводки слоты пересчитываются.
 */
class SectionsActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var strip: FrameLayout
    private lateinit var dots: PageDotsView

    private lateinit var homePage: HomePage
    private lateinit var pages: List<SectionPage>

    /** 0..2 — текущая страница. */
    private var current = 0

    private var dragging = false
    private var decided = false
    private var downX = 0f
    private var downY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (current == 0) homePage.refresh()
            handler.postDelayed(this, 5_000L)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        homePage.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(applicationContext)
        buildUi()
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onResume() {
        super.onResume()
        homePage.refresh()
        pages[current].onShown()
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ------------------------------------------------------------------ каркас и страницы

    private fun buildUi() {
        root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor(Ui.BG_TOP), Color.parseColor(Ui.BG_BOTTOM)),
            )
        }
        // Полоса из трёх страниц: её и двигает палец.
        strip = FrameLayout(this)
        root.addView(strip, FrameLayout.LayoutParams(MATCH, MATCH))

        homePage = HomePage()
        pages = listOf(homePage, NightPage(this), PultPage(this))
        pages.forEach { p ->
            strip.addView(p.root, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        // Индикатор разделов — один на всё приложение, поверх страниц (в v1 он был в каждой).
        dots = PageDotsView(this).apply { setSection(1, 3) }
        root.addView(
            dots,
            FrameLayout.LayoutParams(64, 20).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = Ui.DOTS_TOP_PX
            },
        )

        strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSlots() }
        setContentView(root)
    }

    /** Слот страницы: 0 — текущая, +1 — следующая (справа), −1 — предыдущая (слева). */
    private fun slotOf(index: Int): Int {
        val rel = ((index - current) % 3 + 3) % 3
        return if (rel == 2) -1 else rel
    }

    private fun layoutSlots() {
        val w = root.width.toFloat()
        if (w <= 0f) return
        pages.forEachIndexed { i, page ->
            page.root.translationX = slotOf(i) * w
        }
        updateDots()
    }

    private fun updateDots() {
        dots.visibility = if (current == 0) View.INVISIBLE else View.VISIBLE
        dots.setSection(current + 1, 3)
    }

    // ------------------------------------------------------------------ свайп

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handleSwipe(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Свайп из центральной зоны: следуем за пальцем, на отпускании доводим до соседней страницы.
     * Края отданы системе (старт ближе 70 px к краю не перехватываем), порог — 90 px.
     */
    private fun handleSwipe(ev: MotionEvent) {
        val w = root.width.toFloat()
        if (w <= 0f) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                decided = false
                strip.animate().cancel()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!decided) {
                    val ddx = ev.x - downX
                    if (abs(ddx) < SLOP_PX) return
                    if (abs(ddx) <= abs(ev.y - downY) * SLOPE) return
                    decided = true
                    dragging = true
                }
                if (dragging) strip.translationX = (ev.x - downX).coerceIn(-w, w)
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val dx = ev.x - downX
                    if (abs(dx) >= COMMIT_PX) settle(forward = dx < 0f) else settleBack()
                }
                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settleBack()
                dragging = false
            }
        }
    }

    private fun settle(forward: Boolean) {
        val w = root.width.toFloat().coerceAtLeast(1f)
        strip.animate()
            .translationX(if (forward) -w else w)
            .setDuration(SETTLE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                current = if (forward) (current + 1) % 3 else (current + 2) % 3
                strip.translationX = 0f
                layoutSlots()
                pages[current].onShown()
                if (current == 0) homePage.refresh()
            }
            .start()
    }

    private fun settleBack() {
        strip.animate()
            .translationX(0f)
            .setDuration(BACK_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /** Переход в раздел по индексу 1..3 (долгое нажатие на циферблате, кнопки на страницах). */
    fun goToSection(section: Int) {
        val target = (section - 1).coerceIn(0, 2)
        if (target == current) return
        current = target
        strip.translationX = 0f
        layoutSlots()
        pages[current].onShown()
    }

    // ------------------------------------------------------------------ сервисы и разрешения

    /** Есть ли все нужные разрешения; если нет — запрашиваем и возвращаем false. */
    fun ensurePermissions(): Boolean {
        val missing = missingPerms()
        if (missing.isEmpty()) return true
        permLauncher.launch(missing.toTypedArray())
        return false
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

    fun missingPermNames(): List<String> = missingPerms()

    /** Взвести будильник и запустить ночной лог (главное действие экрана 1). */
    fun startNight(hour: Int, minute: Int, windowMin: Int) {
        val at = targetEpoch(hour, minute)
        Prefs.setTargetHour(this, hour)
        Prefs.setTargetMinute(this, minute)
        Prefs.setWindowMin(this, windowMin)
        AlarmLogic.arm(this, at, windowMin)
        AlarmLogic.log(
            this,
            "home arm " + String.format(Locale.US, "%02d:%02d", hour, minute) + " w=$windowMin",
        )
        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_START
            putExtra(NightLogService.EXTRA_MODE, NightLogService.MODE_ECO)
            putExtra(NightLogService.EXTRA_NOWAKELOCK, false)
        }
        try {
            ContextCompat.startForegroundService(this, svc)
        } catch (_: Throwable) {
            return
        }
        handler.postDelayed({
            if (!NightLogService.isRunning) AlarmLogic.log(this, "night log did not start")
            homePage.refresh()
        }, 2_500L)
    }

    /** Снять будильник и остановить ночной лог. */
    fun stopNight() {
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
        handler.postDelayed({ homePage.refresh() }, 1_200L)
    }

    fun isNightRunning(): Boolean = NightLogService.isRunning

    fun isArmed(): Boolean {
        val live = AlarmLogic.targetAt
        val stored = Prefs.alarmAt(this)
        val at = if (live > 0) live else stored
        return at > System.currentTimeMillis()
    }

    fun targetEpoch(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 60_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun batteryPct(): Int = try {
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) {
        -1
    }

    fun openTimeEditor() {
        startActivity(Intent(this, AlarmSetupActivity::class.java))
    }

    /** Подхват «чужого» системного будильника, если наш не взведён (поведение v1). */
    fun adoptSystemAlarm(): Pair<Int, Int>? {
        val info = SystemAlarm.read(this) ?: return null
        if (info.isOurs || isArmed()) return null
        val cal = Calendar.getInstance().apply { timeInMillis = info.triggerAt }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        AlarmLogic.log(
            this,
            "synced target from system alarm " +
                String.format(Locale.US, "%02d:%02d", h, m) + " (${info.creator})",
        )
        return h to m
    }

    // ------------------------------------------------------------------ страница 1 «Будильник»

    /** Экран 1: кольцо, время цели, статус, капсула окна/повтора, кнопка. Вид v1. */
    private inner class HomePage : SectionPage(this@SectionsActivity) {

        private lateinit var dial: DialView
        private lateinit var statusView: TextView
        private lateinit var pill: TextView
        private lateinit var cta: TextView

        private var hour = Prefs.targetHour(this@SectionsActivity)
        private var minute = Prefs.targetMinute(this@SectionsActivity)
        private var windowMin = Prefs.windowMin(this@SectionsActivity)

        override val root: View = FrameLayout(this@SectionsActivity).apply {
            // Фон рисует хост (градиент), страница прозрачная: при свайпе фон стоит на месте,
            // иначе с края открывался чёрный прямоугольник окна.
            buildPage(this)
        }

        override val section: Int get() = 1

        private fun buildPage(parent: FrameLayout) {
            dial = DialView(host).apply {
                setOnClickListener { host.openTimeEditor() }
                setOnLongClickListener {
                    host.goToSection(2)
                    true
                }
            }
            parent.addView(dial, FrameLayout.LayoutParams(MATCH, MATCH))

            statusView = TextView(host).apply {
                textSize = 11f
                typeface = Ui.labelTypeface()
                letterSpacing = 0.12f
                setTextColor(Ui.textSecondary)
                gravity = Gravity.CENTER
                setSingleLine(true)
            }
            parent.addView(statusView, band(STATUS_TOP, STATUS_H))

            pill = TextView(host).apply {
                textSize = 11f
                typeface = Ui.labelTypeface()
                letterSpacing = 0.04f
                gravity = Gravity.CENTER
                setTextColor(Ui.textSecondary)
                background = pillBackground()
                setOnClickListener {
                    windowMin = when (windowMin) { 15 -> 30; 30 -> 45; 45 -> 60; else -> 15 }
                    Prefs.setWindowMin(host, windowMin)
                    refresh()
                }
                // Долгое нажатие — повтор по дням (выкл → будни → все дни):
                // на экране остаётся одно главное действие — кнопка.
                setOnLongClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    Prefs.setRepeatDays(host, RepeatDays.nextMask(Prefs.repeatDays(host)))
                    refresh()
                    true
                }
            }
            parent.addView(pill, band(PILL_TOP, PILL_H))

            cta = TextView(host).apply {
                textSize = 13f
                typeface = Ui.labelTypeface()
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                setTextColor(Ui.onAccent)
                background = ctaBackground(false)
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    onPrimary()
                }
            }
            parent.addView(cta, band(CTA_TOP, CTA_H))

            parent.post { capWidths() }
        }

        private fun band(top: Int, height: Int): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(MATCH, height).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = top
            }

        /** Ширины: потолок из дизайна, но не шире, чем позволяет внутренний край кольца. */
        private fun capWidths() {
            fun cap(view: TextView, maxW: Float) {
                val lp = view.layoutParams as FrameLayout.LayoutParams
                val top = lp.topMargin.toFloat()
                val safe = ringSafeWidth(top, top + lp.height)
                lp.width = minOf(maxW, if (safe > 0f) safe else maxW).toInt()
                view.layoutParams = lp
            }
            cap(statusView, W_STATUS)
            cap(pill, W_PILL)
            cap(cta, W_CTA)
        }

        private fun ringSafeWidth(top: Float, bottom: Float): Float {
            val dy = maxOf(abs(top - CY), abs(bottom - CY))
            if (dy >= RING_INNER_R) return 0f
            return 2f * sqrt(RING_INNER_R * RING_INNER_R - dy * dy)
        }

        fun refresh() {
            host.adoptSystemAlarm()?.let { (h, m) ->
                if (h != hour || m != minute) {
                    hour = h
                    minute = m
                }
            }
            val armed = host.isArmed()
            val running = host.isNightRunning()
            val target = if (armed) {
                AlarmLogic.targetAt.takeIf { it > 0 } ?: Prefs.alarmAt(host)
            } else {
                host.targetEpoch(hour, minute)
            }
            val diffMin = ((target - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
            val missing = host.missingPermNames()

            dial.timeLabel = String.format(Locale.US, "%02d:%02d", hour, minute)
            dial.armed = armed
            dial.running = running
            dial.targetAt = target

            statusView.setTextColor(if (missing.isEmpty()) Ui.textSecondary else Ui.danger)
            statusView.text = when {
                missing.isNotEmpty() ->
                    "НЕТ ПРАВ: " + missing.joinToString(" · ") { it.substringAfterLast('.') }
                running -> "ЗАПИСЬ · ${diffMin / 60}Ч ${diffMin % 60}М · ${host.batteryPct()}%"
                armed -> "${if (isTomorrow(target)) "ЗАВТРА" else "СЕГОДНЯ"} · ${diffMin / 60}Ч ${diffMin % 60}М"
                else -> "НЕ ВЗВЕДЁН · ${host.batteryPct()}%"
            }

            val repeat = Prefs.repeatDays(host)
            pill.text = if (repeat == RepeatDays.NONE) "окно ±$windowMin мин"
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

        private fun isTomorrow(target: Long): Boolean =
            Calendar.getInstance().apply { timeInMillis = target }.get(Calendar.DAY_OF_YEAR) !=
                Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

        private fun onPrimary() {
            if (host.isArmed() && host.isNightRunning()) {
                host.stopNight()
                return
            }
            if (!host.ensurePermissions()) return
            host.startNight(hour, minute, windowMin)
        }

        private fun pillBackground(): GradientDrawable = GradientDrawable().apply {
            cornerRadius = 26f
            setColor(Color.parseColor(Ui.SURFACE_HI))
            setStroke(2, Color.parseColor(Ui.STROKE_HI))
        }

        private fun ctaBackground(danger: Boolean): GradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (danger) {
                intArrayOf(Color.parseColor("#FF7A7A"), Color.parseColor(Ui.DANGER_DIM))
            } else {
                intArrayOf(Color.parseColor(Ui.ACCENT_FROM), Color.parseColor(Ui.ACCENT_TO))
            },
        ).apply { cornerRadius = 48f }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** Порог, с которого жест считается горизонтальным свайпом. */
        const val SLOP_PX = 24f

        /** Длина жеста для перехода. */
        const val COMMIT_PX = 90f

        /** Горизонталь должна быть заметно больше вертикали. */
        const val SLOPE = 1.4f

        /** Доводка страницы (полностью на своём потоке — Activity не создаётся). */
        const val SETTLE_MS = 220L
        const val BACK_MS = 140L

        // Полосы страницы 1 (px при 466): центр собран компактно внутри кольца.
        const val STATUS_TOP = 192
        const val STATUS_H = 32
        const val PILL_TOP = 232
        const val PILL_H = 52
        const val CTA_TOP = 292
        const val CTA_H = 96

        const val W_STATUS = 340f
        const val W_PILL = 320f
        const val W_CTA = 205f

        const val CY = 233f
        const val RING_INNER_R = 186f
    }
}
