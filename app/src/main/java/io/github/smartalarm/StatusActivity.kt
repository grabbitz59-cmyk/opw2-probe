package io.github.smartalarm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.wear.ambient.AmbientLifecycleObserver
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Раздел 3 — «ПУЛЬТ» (`docs/design-v2.md` §3.4): три строки-леджера и две служебные строки.
 *
 * Экран ничего не изображает «в целом»: он отвечает на три вопроса настройки (окно, повтор,
 * отложить) и сообщает два факта телеметрии (батарея/лог, подъём). Каждая строка — леджер:
 * подпись слева, значение справа, волосяная линейка. Тап по строке меняет значение детентом
 * (+ хаптик `tick`), карточек, фонов и главного действия нет.
 *
 * Источники данных не изменились: `Prefs` (окно/повтор/отложить/цель/фаза), [NightLogService],
 * `BatteryManager`, `PackageManager`, проверки `BODY_SENSORS` и уведомлений.
 *
 * Геометрия: каждая полоса ограничена не «вписанным квадратом», а окружностью обода
 * (`r = Ui.BEZEL_IN = 188`), см. [fitWidth]; ширины полос — из таблицы §3.4.
 */
class StatusActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var swipe: SectionSwipe
    private lateinit var indexView: SectionIndexView
    private lateinit var titleView: TextView
    private lateinit var versionView: TextView
    private lateinit var windowRow: LedgerRow
    private lateinit var repeatRow: LedgerRow
    private lateinit var snoozeRow: LedgerRow
    private lateinit var divider: View
    private lateinit var telemetry1: TelemetryLine
    private lateinit var telemetry2: TelemetryLine

    private var ambient: AmbientLifecycleObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(applicationContext)
        buildUi()
        setupAmbient()
        // Отладка AOD: adb shell am start -n io.github.smartalarm/.StatusActivity --ez force_ambient true
        if (intent?.getBooleanExtra("force_ambient", false) == true) {
            handler.postDelayed({ applyAmbient(true) }, 400L)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::swipe.isInitialized) swipe.reset()
        // Значения перечитываются при каждом возврате: экран 1 их меняет, а строки живут долго.
        refresh()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::swipe.isInitialized) swipe.handle(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** `CLEAR_TOP | SINGLE_TOP` переиспользует экземпляр: сбрасываем сдвиг и перечитываем Prefs. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::swipe.isInitialized) swipe.reset()
        refresh()
    }

    // ---------------------------------------------------------------- вёрстка

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Ui.bg) }

        indexView = SectionIndexView(this).apply { active = Sections.PULT }
        // Полоса индикатора берётся из токенов (Ui.INDEX_TOP = 60): на y 42 риски уже в ободе.
        root.addView(
            indexView,
            band(Ui.INDEX_TOP.toInt(), (Ui.INDEX_BOTTOM - Ui.INDEX_TOP).toInt(), INDEX_W),
        )

        titleView = Ui.label(this, "ПУЛЬТ", Ui.SZ_TITLE, Ui.ember, Ui.LS_TITLE, caps = true).apply {
            gravity = Gravity.CENTER
        }
        root.addView(titleView, band(TITLE_TOP, TITLE_H, TITLE_MAX_W))

        // Версия — мелкая гравировка справа в полосе заголовка (отдельной строки под неё нет).
        versionView = Ui.label(this, "", VERSION_PX, Ui.paper3, 0f, caps = true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        root.addView(versionView, versionParams())

        windowRow = LedgerRow(this).apply {
            label = "ОКНО"
            isActive = true
            setOnRowTap { cycleWindow() }
        }
        root.addView(windowRow, band(ROW1_TOP, ROW_H, ROW_W))

        repeatRow = LedgerRow(this).apply {
            label = "ПОВТОР"
            isActive = true
            setOnRowTap { cycleRepeat() }
        }
        root.addView(repeatRow, band(ROW2_TOP, ROW_H, ROW_W))

        snoozeRow = LedgerRow(this).apply {
            label = "ОТЛОЖИТЬ"
            isActive = true
            showDivider = false
            setOnRowTap { cycleSnooze() }
        }
        root.addView(snoozeRow, band(ROW3_TOP, ROW_H, ROW_W))

        divider = View(this).apply { setBackgroundColor(Ui.rule) }
        root.addView(divider, band(DIVIDER_TOP, DIVIDER_H, ROW_W))

        telemetry1 = TelemetryLine(this).apply {
            sizePx = Ui.SZ_TELEMETRY_SMALL
            tracking = Ui.LS_TELEMETRY
            color = Ui.paper3
            // Телеметрия — только сообщает (R3: высота 26 px < 72 px, поэтому не тапается).
            // Запрос разрешений делает рычаг на экране 1: на «Пульте» второго действия нет.
            isClickable = false
        }
        root.addView(telemetry1, band(TELE1_TOP, TELE_H, TELE1_W))

        telemetry2 = TelemetryLine(this).apply {
            sizePx = Ui.SZ_TELEMETRY_SMALL
            tracking = Ui.LS_TELEMETRY
            color = Ui.paper3
        }
        root.addView(telemetry2, band(TELE2_TOP, TELE_H, TELE2_W))

        setContentView(root)
        swipe = SectionSwipe(this, Sections.PULT, root)
        refresh()
    }

    /**
     * Полоса экрана: `topMargin` в px, ширина урезана окружностью обода.
     * `Ui.CY` и `Ui.BEZEL_IN` — единственный источник правды по вертикали/радиусу.
     */
    private fun band(topPx: Int, heightPx: Int, capPx: Float): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = topPx
        lp.width = fitWidth(topPx, heightPx, capPx)
        return lp
    }

    /**
     * Максимальная ширина полосы: не шире `capPx` и такая, чтобы все четыре угла лежали
     * внутри обода (`r ≤ Ui.BEZEL_IN`, а не `SAFE_R`: шкала живёт в annulus 188…208).
     */
    private fun fitWidth(topPx: Int, heightPx: Int, capPx: Float): Int {
        val dy = maxOf(abs(topPx - Ui.CY), abs((topPx + heightPx) - Ui.CY))
        if (dy >= Ui.BEZEL_IN) return 1
        val fit = 2f * sqrt(Ui.BEZEL_IN * Ui.BEZEL_IN - dy * dy)
        return minOf(capPx, fit).toInt().coerceAtLeast(1)
    }

    /** Правый край полосы на окружности `r = Ui.BEZEL_IN`. */
    private fun rightEdge(topPx: Int, heightPx: Int): Float {
        val dy = maxOf(abs(topPx - Ui.CY), abs((topPx + heightPx) - Ui.CY))
        if (dy >= Ui.BEZEL_IN) return Ui.CX
        return Ui.CX + sqrt(Ui.BEZEL_IN * Ui.BEZEL_IN - dy * dy)
    }

    /** Версия справа: правый край прижат к границе обода, текст — по правому краю. */
    private fun versionParams(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(VERSION_W, VERSION_H)
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.topMargin = VERSION_TOP
        lp.leftMargin = (rightEdge(VERSION_TOP, VERSION_H) - VERSION_W).toInt().coerceAtLeast(0)
        return lp
    }

    // ---------------------------------------------------------------- значения

    private fun refresh() {
        if (!::windowRow.isInitialized) return

        windowRow.value = "±${Prefs.windowMin(this)} МИН"

        repeatRow.value = repeatLabel(Prefs.repeatDays(this))

        snoozeRow.value = "${Prefs.snoozeMin(this)} МИН"

        // Телеметрия 1: права датчиков важнее — без них запись бессмысленна.
        val sensors = sensorsGranted()
        telemetry1.color = if (sensors) Ui.paper3 else Ui.signalStop
        telemetry1.text = if (!sensors) {
            "НЕТ ПРАВ · ДАТЧИКИ"
        } else {
            val battery = batteryPct()
            val bat = if (battery in 0..100) "$battery%" else "—"
            "БАТ $bat · ЛОГ ${if (NightLogService.isRunning) "ИДЁТ" else "СТОП"}"
        }

        // Телеметрия 2: во сколько подъём и в какой фазе разбудили.
        val phase = shortPhase(Prefs.lastWakePhase(this))
        telemetry2.text = if (phase.isEmpty()) "ПОДЪЁМ —" else "${wakeHm()} · $phase"

        versionView.text = displayVersion()
    }

    /** «ВЫКЛ» / «БУДНИ» / «ВСЕ ДНИ» — `RepeatDays.label` плюс короткая форма для «все дни». */
    private fun repeatLabel(mask: Int): String = when (mask) {
        RepeatDays.NONE -> "ВЫКЛ"
        RepeatDays.WEEKDAYS -> "БУДНИ"
        RepeatDays.ALL -> "ВСЕ ДНИ"
        else -> RepeatDays.label(mask).uppercase()
    }

    /** `Prefs.lastWakePhase` хранит длинную подпись («лёгкий сон») — здесь нужен код. */
    private fun shortPhase(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val low = value.lowercase()
        return when {
            low.startsWith("глубок") -> "ГЛУБ"
            low.startsWith("лёгк") || low.startsWith("легк") -> "ЛЁГК"
            low.startsWith("бодрств") -> "БОДР"
            low.startsWith("не определ") -> ""
            else -> value.uppercase()
        }
    }

    /** Время подъёма: взведённое (`alarm_at`), иначе сохранённая цель. Формат — один знак часа. */
    private fun wakeHm(): String {
        val at = Prefs.alarmAt(this)
        if (at > 0L) {
            val zoned = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            return String.format(Locale.US, "%d:%02d", zoned.hour, zoned.minute)
        }
        return String.format(Locale.US, "%d:%02d", Prefs.targetHour(this), Prefs.targetMinute(this))
    }

    /** Версия без служебного суффикса сборки: `1.69-ui` → `1.69`. */
    private fun displayVersion(): String =
        appVersion().trim().substringBefore('-').uppercase()

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun batteryPct(): Int = try {
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) {
        -1
    }

    // ---------------------------------------------------------------- права

    private fun sensorsGranted(): Boolean =
        checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

    /** Проверка уведомлений осталась источником правды: без неё ночь не покажет сигнал. */
    private fun notificationsEnabled(): Boolean = try {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.areNotificationsEnabled()
    } catch (_: Throwable) {
        true
    }

    private fun missingPerms(): List<String> {
        val wanted = mutableListOf<String>()
        if (!sensorsGranted()) wanted.add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled()) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return wanted
    }

    private fun requestMissingPerms() {
        val missing = missingPerms()
        if (missing.isEmpty()) return
        // У Activity нет своего performHapticFeedback — вибрируем через decorView.
        window?.decorView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        permLauncher.launch(missing.toTypedArray())
    }

    // ---------------------------------------------------------------- действия строк

    /** Окно пробуждения: 15 → 30 → 45 → 60 мин (`Prefs.windowMin`). */
    private fun cycleWindow() {
        val next = when (Prefs.windowMin(this)) {
            15 -> 30
            30 -> 45
            45 -> 60
            else -> 15
        }
        Prefs.setWindowMin(this, next)
        refresh()
    }

    /** Повтор по дням: выкл → будни → все дни (`RepeatDays.nextMask`). */
    private fun cycleRepeat() {
        val next = RepeatDays.nextMask(Prefs.repeatDays(this))
        Prefs.setRepeatDays(this, next)
        refresh()
    }

    /** Отложить: 5 → 10 → 15 мин (`Prefs.snoozeMin`). */
    private fun cycleSnooze() {
        val next = when (Prefs.snoozeMin(this)) {
            5 -> 10
            10 -> 15
            15 -> 5
            else -> 10
        }
        Prefs.setSnoozeMin(this, next)
        refresh()
    }

    // ---------------------------------------------------------------- ambient

    /**
     * AOD (`docs/design-v2.md` §3.4): фон `bg_ambient`, из элементов остаётся только заголовок
     * притушенным `paper_3`; строки, линейка и телеметрия скрыты — экран не выжигает AMOLED.
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
                }

                override fun onExitAmbient() {
                    applyAmbient(false)
                }
            },
        )
    }

    private fun applyAmbient(on: Boolean) {
        if (!::root.isInitialized) return
        root.background = ColorDrawable(if (on) Ui.bgAmbient else Ui.bg)
        titleView.setTextColor(if (on) Ui.paper3 else Ui.ember)
        val visibility = if (on) View.INVISIBLE else View.VISIBLE
        indexView.visibility = visibility
        versionView.visibility = visibility
        windowRow.visibility = visibility
        repeatRow.visibility = visibility
        snoozeRow.visibility = visibility
        divider.visibility = visibility
        telemetry1.visibility = visibility
        telemetry2.visibility = visibility
        if (!on) refresh()
    }

    private companion object {
        // Полосы экрана 3 — docs/design-v2.md §3.4 (с поправкой на обод r = 188).
        const val INDEX_W = 64f

        const val TITLE_TOP = 96
        const val TITLE_H = 28
        const val TITLE_MAX_W = 300f

        const val VERSION_TOP = 100
        const val VERSION_H = 22
        const val VERSION_W = 120
        const val VERSION_PX = 20f

        const val ROW1_TOP = 132
        const val ROW2_TOP = 204
        const val ROW3_TOP = 276
        const val ROW_H = 72
        const val ROW_W = 296f

        const val DIVIDER_TOP = 348
        const val DIVIDER_H = 1

        const val TELE_H = 26
        const val TELE1_TOP = 350
        const val TELE1_W = 240f
        const val TELE2_TOP = 374
        const val TELE2_W = 170f
    }
}

/**
 * Служебная строка телеметрии: canvas-текст по центру, размер и трекинг — токенами v2.
 *
 * Узкие полосы внизу круга (240 и 170 px) не терпят переносов и обрезки, поэтому строка
 * сама поджимает себя по горизонтали, если набранный текст шире полосы: подпись остаётся
 * целой и не залезает в обод.
 */
private class TelemetryLine @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var text: String = ""
        set(v) {
            field = v
            invalidate()
        }

    var sizePx: Float = Ui.SZ_TELEMETRY_SMALL
        set(v) {
            field = v
            invalidate()
        }

    var tracking: Float = Ui.LS_TELEMETRY
        set(v) {
            field = v
            invalidate()
        }

    var color: Int = Ui.paper3
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.condensed()
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty() || width <= 0 || height <= 0) return
        paint.color = color
        paint.textSize = sizePx
        paint.letterSpacing = tracking
        paint.textScaleX = 1f
        val natural = paint.measureText(text)
        val available = width - 2f * PAD_PX
        if (natural > available && natural > 0f) {
            paint.textScaleX = (available / natural).coerceAtLeast(MIN_SCALE_X)
        }
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, width / 2f, baseline, paint)
    }

    private companion object {
        const val PAD_PX = 2f

        /** Предел поджатия: ниже него текст стал бы нечитаемым — лучше обрезать полосу. */
        const val MIN_SCALE_X = 0.72f
    }
}
