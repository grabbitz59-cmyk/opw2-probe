package io.github.smartalarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.wear.ambient.AmbientLifecycleObserver
import java.util.Locale
import kotlin.math.abs

/**
 * Третий раздел карусели — «Статус»: сводка о том, что настроено и что доступно.
 *
 * Визуальный язык — v1: градиентный фон [Ui.BG_TOP]→[Ui.BG_BOTTOM], карточки-плашки
 * ([Ui.SURFACE], скругление 12 dp, обводка 2 px [Ui.STROKE]), точка-индикатор акцентом
 * и три точки разделов внизу. Геометрия — исправленная (v2-фиксы, без v2-визуала):
 *
 *  - только средняя полоса круга: заголовок 96…124 px, прокручиваемая область 136…362 px,
 *    индикатор разделов 408…428 px (центр точек 418). Все ряды шириной
 *    `min(360, RoundScreen.safeRowWidth(..))`, посчитанной по видимой части строки, поэтому
 *    углы карточек не выходят за безопасный радиус 209 px ни при каком скролле;
 *  - строка = 72 px (36 dp) — тап-зона всей строки проходит требование R3 (≥ 36 dp);
 *  - первые три строки видны без прокрутки, последняя строка после прокрутки заканчивается
 *    на y = 360 px, то есть не упирается в нижнюю дугу круга;
 *  - ambient (AOD): чёрный фон, притушённый заголовок, остальное скрыто.
 *
 * Кегли — как в v1 (20 px подпись/значение, 22 px заголовок), но не мельче 18 px.
 * Единицы ниже — пиксели устройства (OPW2: 466×466, 320 dpi, 1 dp = 2 px): так они совпадают
 * с токенами [Ui] и [RoundScreen], которые тоже заданы в px.
 */
class StatusActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private lateinit var dots: PageDotsView
    private lateinit var swipe: SectionSwipe

    /** Карточки-строки в порядке добавления: по индексу считается их y на экране. */
    private val cards = ArrayList<View>()

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupAmbient()
        swipe = SectionSwipe(this, Sections.PULT, content)
        // Отладка ambient: adb shell am start -n io.github.smartalarm/.StatusActivity \
        //   --ez force_ambient true
        // Позволяет увидеть AOD-отрисовку при выключенном AOD на устройстве.
        if (intent?.getBooleanExtra("force_ambient", false) == true) {
            handler.postDelayed({ applyAmbient(true) }, 400L)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::swipe.isInitialized) swipe.reset()
        // Статус — «живой» экран: при возврате перечитываем настройки, батарею и права.
        if (::column.isInitialized) refreshRows()
    }

    // ---------- ambient (AOD) ----------

    /**
     * Ambient по скиллу wear-os-ui (R9): в покое остаются только чёрный фон и притушённый
     * заголовок — ни карточек, ни индикатора, чтобы AMOLED не выжигал области.
     */
    private fun setupAmbient() {
        val observer = AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(details: AmbientLifecycleObserver.AmbientDetails) {
                    applyAmbient(true)
                }

                override fun onUpdateAmbient() {
                    // В ambient контент скрыт — обновлять нечего.
                }

                override fun onExitAmbient() {
                    applyAmbient(false)
                }
            },
        )
        // Observer живёт в lifecycle, отдельное поле не нужно: отписка произойдёт сама.
        lifecycle.addObserver(observer)
    }

    private fun applyAmbient(on: Boolean) {
        if (on) {
            // Сбрасываем остаток свайпа, чтобы в ambient ничего не было смещено.
            content.translationX = 0f
            content.alpha = 1f
        }
        root.background = if (on) {
            ColorDrawable(Color.BLACK)
        } else {
            screenBackground()
        }
        titleView.setTextColor(if (on) Ui.textTertiary else Ui.accent)
        titleView.alpha = if (on) 0.85f else 1f
        scroll.visibility = if (on) View.INVISIBLE else View.VISIBLE
        dots.visibility = if (on) View.INVISIBLE else View.VISIBLE
    }

    // ---------- навигация по кругу ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::swipe.isInitialized) swipe.handle(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------- вёрстка ----------

    private fun buildUi() {
        root = FrameLayout(this).apply { background = screenBackground() }
        content = FrameLayout(this)
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Заголовок: 96…124 px — в полосе, где ширина круга ещё ~316 px.
        titleView = TextView(this).apply {
            text = "СТАТУС"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TITLE_TEXT_PX)
            typeface = Ui.labelTypeface()
            letterSpacing = 0.16f
            setTextColor(Ui.accent)
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
        }
        content.addView(
            titleView,
            // Ширина ограничена ещё и явным TITLE_W: safeRowWidth на полосе 98…126 даёт 320 px,
            // и углы бокса выходили на 209.3 (замерено на устройстве). 312 px → угол 206.3.
            band(minOf(rowWidth(TITLE_TOP, TITLE_TOP + TITLE_H), TITLE_W), TITLE_TOP, TITLE_H),
        )

        // Прокручиваемая область 136…362 px. Сама вьюха прозрачная и на всю ширину экрана,
        // но каждой строке ширина считается по её видимой части (см. updateRowWidths),
        // а минимально возможная ширина в этой полосе — 328 px, поэтому видимые пиксели
        // карточек всегда внутри безопасного радиуса.
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 0)
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)
            // Нижний отступ: после прокрутки вниз последняя строка заканчивается на y ≈ 360,
            // а не упирается в нижнюю дугу круга.
            setPadding(0, 0, 0, dp(1))
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnScrollChangeListener { _, _, _, _, _ -> updateRowWidths() }
        }
        content.addView(scroll, band(ViewGroup.LayoutParams.MATCH_PARENT, SCROLL_TOP, SCROLL_H))

        // Индикатор разделов: полоса 408…428 px, центр точек 418 (полоса 412…424 требований),
        // ширина 64 px, по центру экрана.
        dots = PageDotsView(this).apply {
            setSection(Sections.PULT, 3)
        }
        content.addView(dots, band(DOTS_W, DOTS_TOP, DOTS_H))

        setContentView(root)
        buildRows()
    }

    /** Полоса по вертикали + центрирование по горизонтали, всё в px. */
    private fun band(widthPx: Int, topPx: Int, heightPx: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(widthPx, heightPx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = topPx
        }

    /** Ширина ряда по геометрии круга: `min(360, safeRowWidth)`. */
    private fun rowWidth(topPx: Int, bottomPx: Int): Int {
        val safe = RoundScreen.safeRowWidth(topPx.toFloat(), bottomPx.toFloat()).toInt()
        return if (safe <= 0) MAX_ROW_W else minOf(MAX_ROW_W, safe)
    }

    // ---------- строки ----------

    private fun buildRows() {
        column.removeAllViews()
        cards.clear()
        val rows = rowData()
        for (index in rows.indices) {
            val card = makeRow(rows[index].first, rows[index].second)
            cards.add(card)
            column.addView(
                card,
                LinearLayout.LayoutParams(MAX_ROW_W, ROW_H).apply {
                    topMargin = if (index == 0) 0 else ROW_GAP
                },
            )
        }
        scroll.post { updateRowWidths() }
    }

    /** Перечитать данные и пересобрать строки, сохранив позицию прокрутки. */
    private fun refreshRows() {
        val scrollY = scroll.scrollY
        buildRows()
        scroll.post {
            scroll.scrollTo(0, scrollY)
            updateRowWidths()
        }
    }

    /**
     * Пересчёт ширины каждой карточки по её текущему положению на экране: `safeRowWidth`
     * берётся только по видимой части строки, поэтому карточка никогда не выходит за круг
     * во время прокрутки. Шаг квантования [WIDTH_STEP] убирает лишние переразметки.
     */
    private fun updateRowWidths() {
        if (cards.isEmpty() || !::scroll.isInitialized) return
        val viewTop = SCROLL_TOP.toFloat()
        val viewBottom = (SCROLL_TOP + SCROLL_H).toFloat()
        val scrollY = scroll.scrollY
        for (index in cards.indices) {
            val card = cards[index]
            val top = viewTop + index * PITCH - scrollY
            val bottom = top + ROW_H
            if (bottom <= viewTop || top >= viewBottom) continue
            val visibleTop = maxOf(top, viewTop)
            val visibleBottom = minOf(bottom, viewBottom)
            val safe = RoundScreen.safeRowWidth(visibleTop, visibleBottom).toInt()
            val width = if (safe <= 0) MAX_ROW_W else minOf(MAX_ROW_W, safe)
            val lp = card.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (abs(lp.width - width) < WIDTH_STEP) continue
            lp.width = width
            card.layoutParams = lp
        }
    }

    /**
     * Данные строк (источники не менялись с v1).
     * «Последний подъём» показывается только если фаза известна — тогда строк 7, иначе 8.
     */
    private fun rowData(): List<Pair<String, String>> {
        val time = String.format(
            Locale.US, "%02d:%02d", Prefs.targetHour(this), Prefs.targetMinute(this),
        )
        val armed = Prefs.alarmAt(this) > System.currentTimeMillis()
        val mask = Prefs.repeatDays(this)
        val repeat = (if (mask == RepeatDays.NONE) "выкл" else RepeatDays.label(mask)) +
            " · окно ±${Prefs.windowMin(this)} мин"
        val battery = batteryPct()

        val rows = ArrayList<Pair<String, String>>(8)
        rows.add("Будильник" to if (armed) "$time · взведён" else "$time · не взведён")
        rows.add("Повтор" to repeat)
        rows.add("Отложить" to "${Prefs.snoozeMin(this)} мин")
        rows.add("Ночной лог" to if (NightLogService.isRunning) "идёт" else "остановлен")
        val lastWake = Prefs.lastWakePhase(this)
        if (lastWake.isNotEmpty()) rows.add("Последний подъём" to lastWake)
        rows.add("Батарея" to if (battery in 0..100) "$battery%" else "н/д")
        rows.add("Разрешения" to permissionsText())
        rows.add("Версия" to appVersion())
        return rows
    }

    /** Карточка-плашка v1: точка акцентом, подпись слева, значение справа; высота 36 dp. */
    private fun makeRow(key: String, value: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(7), 0)
            background = rowBackground()
            // Строки v1 — только информация (настроек нет), поэтому НЕ clickable: раньше здесь
            // висел OnClickListener, который лишь перерисовывал те же значения — тап выглядел
            // «мёртвым» (проверено на устройстве: в a11y-дереве clickable=false и нет эффекта).
            // Высота 72 px (36 dp) оставлена для читаемости и на случай будущего действия.
            isClickable = false
            isFocusable = false
        }
        card.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Ui.accent)
                }
                layoutParams = LinearLayout.LayoutParams(DOT_PX, DOT_PX).apply {
                    rightMargin = dp(4)
                }
            },
        )
        card.addView(
            TextView(this).apply {
                text = key
                setTextSize(TypedValue.COMPLEX_UNIT_PX, TEXT_PX)
                typeface = Ui.bodyTypeface()
                setTextColor(Ui.textSecondary)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isSingleLine = true
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            TextView(this).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_PX, TEXT_PX)
                typeface = Ui.labelTypeface()
                setTextColor(Ui.text)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                // Длинное значение («датчики ✓ · уведомления ✓») переносится на вторую строку
                // внутри тех же 72 px — текст не мельче 18 px не нужен.
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        return card
    }

    /** Плашка: [Ui.SURFACE] с обводкой 2 px и скруглением 12 dp; нажатие — [Ui.SURFACE_HI]. */
    private fun rowBackground(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), cardDrawable(pressed = true))
        addState(intArrayOf(), cardDrawable(pressed = false))
    }

    private fun cardDrawable(pressed: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(if (pressed) Ui.surfaceHi else Ui.surface)
        setStroke(2, if (pressed) Ui.strokeHi else Ui.stroke)
    }

    // ---------- данные ----------

    private fun permissionsText(): String {
        val sensors = if (has(Manifest.permission.BODY_SENSORS)) "датчики ✓" else "датчики ✗"
        val notifications = if (notificationsEnabled()) "уведомления ✓" else "уведомления ✗"
        return "$sensors · $notifications"
    }

    private fun notificationsEnabled(): Boolean = try {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
    } catch (_: Throwable) {
        false
    }

    private fun batteryPct(): Int = try {
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) {
        -1
    }

    private fun has(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    // ---------- фон и единицы ----------

    /** Фон экрана: глубокий вертикальный градиент из токенов v1. */
    private fun screenBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            Color.parseColor(Ui.BG_TOP),
            Color.parseColor(Ui.BG_BOTTOM),
        ),
    )

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    /** Метрики экрана в px (OPW2: 466×466, 1 dp = 2 px). */
    private companion object {
        /** Полоса заголовка: 96…124 px. */
        const val TITLE_TOP = 98
        const val TITLE_H = 28
        const val TITLE_W = 312
        const val TITLE_TEXT_PX = 22f

        /** Прокручиваемая область: 136…362 px (226 px). */
        const val SCROLL_TOP = 136
        const val SCROLL_H = 226

        /** Строка 36 dp = 72 px, зазор 2 dp = 4 px, шаг 76 px. */
        const val ROW_H = 72
        const val ROW_GAP = 4
        const val PITCH = ROW_H + ROW_GAP

        /** Максимальная ширина ряда и шаг уточнения при прокрутке. */
        const val MAX_ROW_W = 360
        const val WIDTH_STEP = 6

        /** Кегли v1: подпись/значение 20 px (не мельче 18 px), заголовок 22 px caps. */
        const val TEXT_PX = 20f
        const val DOT_PX = 12

        /** Индикатор разделов: полоса 408…428 px (центр точек 418), ширина 64 px. */
        const val DOTS_W = 64
        const val DOTS_H = 20
        const val DOTS_TOP = Ui.DOTS_TOP_PX
    }
}
