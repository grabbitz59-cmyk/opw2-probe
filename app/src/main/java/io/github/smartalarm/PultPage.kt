package io.github.smartalarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.BatteryManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/**
 * Третья страница карусели — «Статус»: сводка о том, что настроено и что доступно.
 *
 * Раньше это был отдельный `StatusActivity` (третий раздел); теперь — страница внутри одного
 * окна [SectionsActivity], поэтому свайп двигает вьюхи, а не создаёт Activity (создание окна
 * блокировало поток анимации на 183–551 мс). Вся вёрстка и координаты перенесены из
 * `StatusActivity` без изменений — меняется только обвязка (нет `onCreate`/`onResume`/
 * `dispatchTouchEvent`/`SectionSwipe`/`finish()`); перечитывание данных по-прежнему происходит
 * при каждом показе страницы, через [onShown].
 *
 * Визуальный язык — v1: карточки-плашки ([Ui.SURFACE], скругление 12 dp, обводка 2 px
 * [Ui.STROKE]), точка-индикатор акцентом и три точки разделов внизу. Геометрия — исправленная
 * (v2-фиксы, без v2-визуала):
 *
 *  - только средняя полоса круга: заголовок 98…126 px, прокручиваемая область 136…362 px.
 *    Все ряды шириной `min(360, RoundScreen.safeRowWidth(..))`, посчитанной по видимой части
 *    строки, поэтому углы карточек не выходят за безопасный радиус 209 px ни при каком скролле;
 *    полоса индикатора разделов (414…434 px) остаётся свободной: сам индикатор теперь один на
 *    всё приложение и рисуется хостом поверх страниц (в v1 он был свой в каждой Activity);
 *  - строка = 72 px (36 dp) — тап-зона всей строки проходит требование R3 (≥ 36 dp);
 *  - первые три строки видны без прокрутки, последняя строка после прокрутки заканчивается
 *    на y = 360 px, то есть не упирается в нижнюю дугу круга.
 *
 * Кегли — как в v1 (20 px подпись/значение, 22 px заголовок), но не мельче 18 px.
 * Единицы ниже — пиксели устройства (OPW2: 466×466, 320 dpi, 1 dp = 2 px): так они совпадают
 * с токенами [Ui] и [RoundScreen], которые тоже заданы в px.
 *
 * Фон страницы **прозрачный**: вертикальный градиент рисует хост под всеми страницами, иначе
 * при свайпе между страницами были бы видны швы.
 */
class PultPage(host: SectionsActivity) : SectionPage(host) {

    override val root: View

    /** Раздел «Статус» — третий в карусели. */
    override val section: Int get() = Sections.PULT

    private val scroll: ScrollView
    private val column: LinearLayout

    /** Карточки-строки в порядке добавления: по индексу считается их y на экране. */
    private val cards = ArrayList<View>()

    init {
        val frame = FrameLayout(host).apply {
            // Страница занимает всё окно: хост двигает её целиком при свайпе.
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Градиент — на хосте (он общий для трёх страниц). Свой фон не рисуем: при свайпе
            // непрозрачная плашка страницы дала бы видимый шов и лишний слой отрисовки.
            setBackgroundColor(Color.TRANSPARENT)
        }
        root = frame

        // Заголовок: 98…126 px — в полосе, где ширина круга ещё ~316 px.
        val titleView = TextView(host).apply {
            text = "СТАТУС"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TITLE_TEXT_PX)
            typeface = Ui.labelTypeface()
            letterSpacing = 0.16f
            setTextColor(Ui.accent)
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
        }
        frame.addView(
            titleView,
            // Ширина ограничена ещё и явным TITLE_W: safeRowWidth на полосе 98…126 даёт 320 px,
            // и углы бокса выходили на 209.3 (замерено на устройстве). 312 px → угол 206.3.
            band(minOf(rowWidth(TITLE_TOP, TITLE_TOP + TITLE_H), TITLE_W), TITLE_TOP, TITLE_H),
        )

        // Прокручиваемая область 136…362 px. Сама вьюха прозрачная и на всю ширину экрана,
        // но каждой строке ширина считается по её видимой части (см. updateRowWidths),
        // а минимально возможная ширина в этой полосе — 328 px, поэтому видимые пиксели
        // карточек всегда внутри безопасного радиуса.
        column = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 0)
        }
        scroll = ScrollView(host).apply {
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
        frame.addView(scroll, band(ViewGroup.LayoutParams.MATCH_PARENT, SCROLL_TOP, SCROLL_H))

        // Индикатор разделов (полоса 414…434 px) здесь не создаётся: с переходом на одно окно
        // он один на всё приложение — [SectionsActivity] кладёт его поверх страниц и сам
        // подсвечивает активный раздел. Второй такой же в этой странице дал бы двойную отрисовку
        // тех же трёх точек (приглушённые точки стали бы заметно ярче).

        buildRows()
    }

    /**
     * Хост вызывает это каждый раз, когда страница стала видимой (и при возврате в раздел):
     * экран «живой», поэтому перечитываем настройки, батарею и права. Позицию прокрутки
     * сохраняем — как раньше делал `onResume` у Activity.
     */
    override fun onShown() {
        refreshRows()
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
        if (cards.isEmpty()) return
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
            Locale.US, "%02d:%02d", Prefs.targetHour(host), Prefs.targetMinute(host),
        )
        val armed = Prefs.alarmAt(host) > System.currentTimeMillis()
        val mask = Prefs.repeatDays(host)
        val repeat = (if (mask == RepeatDays.NONE) "выкл" else RepeatDays.label(mask)) +
            " · окно ±${Prefs.windowMin(host)} мин"
        val battery = batteryPct()

        val rows = ArrayList<Pair<String, String>>(8)
        rows.add("Будильник" to if (armed) "$time · взведён" else "$time · не взведён")
        rows.add("Повтор" to repeat)
        rows.add("Отложить" to "${Prefs.snoozeMin(host)} мин")
        rows.add("Ночной лог" to if (NightLogService.isRunning) "идёт" else "остановлен")
        val lastWake = Prefs.lastWakePhase(host)
        if (lastWake.isNotEmpty()) rows.add("Последний подъём" to lastWake)
        rows.add("Батарея" to if (battery in 0..100) "$battery%" else "н/д")
        rows.add("Разрешения" to permissionsText())
        rows.add("Версия" to appVersion())
        return rows
    }

    /** Карточка-плашка v1: точка акцентом, подпись слева, значение справа; высота 36 dp. */
    private fun makeRow(key: String, value: String): LinearLayout {
        val card = LinearLayout(host).apply {
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
            View(host).apply {
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
            TextView(host).apply {
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
            TextView(host).apply {
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
        (host.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
    } catch (_: Throwable) {
        false
    }

    private fun batteryPct(): Int = try {
        (host.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) {
        -1
    }

    private fun has(permission: String): Boolean =
        host.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun appVersion(): String = try {
        host.packageManager.getPackageInfo(host.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), host.resources.displayMetrics,
    ).toInt()

    /** Метрики экрана в px (OPW2: 466×466, 1 dp = 2 px). */
    private companion object {
        /** Полоса заголовка: 98…126 px. */
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
    }
}
