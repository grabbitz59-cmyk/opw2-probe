package io.github.smartalarm

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Страница 2 карусели — «ПРОШЛАЯ НОЧЬ»: прошлая ночь в **языке v1** (янтарный v2 забракован).
 *
 * Раньше это был отдельный `StatsActivity` (второй раздел приложения). Теперь раздел — страница
 * внутри одного окна [SectionsActivity]: свайп двигает вьюхи, создания Activity нет. Замерено
 * на часах: окно новой Activity появляется через 183–551 мс, и это время уходит из того же
 * главного потока, который рисует анимацию свайпа, — отсюда было «дёрганье». Вёрстка, данные
 * и координаты перенесены из `StatsActivity` без изменений; поменялась только обвязка:
 *
 *  - нет `onCreate` / `onResume` / `onDestroy` / `dispatchTouchEvent` — это делает хост;
 *  - нет `SectionSwipe` и `dispatchTouchEvent` — свайп и `onResume` обслуживает
 *    [SectionsActivity], номер раздела берётся из `Sections.NIGHT`;
 *  - нет `AmbientLifecycleObserver` (AOD в приложении убран совсем);
 *  - вместо `Activity` контекстом для View/TypedValue/resources служит `host`;
 *  - `runOnUiThread` — хостовый (`host.runOnUiThread`), файлы — `host.getExternalFilesDir(..)`;
 *  - чтение ночи (`reload`) вызывается из [onShown], то есть при каждом показе страницы,
 *    а не в конструкторе: на старте приложения CSV не читается дважды.
 *
 * **Фон страницы прозрачный.** Вертикальный градиент `BG_TOP → BG_BOTTOM` рисует хост под всеми
 * страницами; своя непрозрачная плашка дала бы шов и лишний слой отрисовки при свайпе.
 *
 * **Геометрия.** Всё лежит на абсолютных полосах `y` в координатах экрана 466 × 466 px
 * (1 dp = 2 px), и ширина каждой полосы считается через [RoundScreen.safeRowWidth] —
 * прямоугольник полосы гарантированно внутри круга `r = 209` (R1–R2). Блоки:
 *
 * | полоса, y   | элемент              | ширина | кегль |
 * |-------------|----------------------|--------|-------|
 * |  90…118     | заголовок            | 260 px | 22 px |
 * | 122…214     | карточка графика     | 336 px | 16 px (подписи времени) |
 * | 218…240     | легенда фаз          | 300 px | 18 px |
 * | 244…268     | фаза подъёма         | 312 px | 20 px |
 * | 272…318     | сводка (2 строки)    | 330 px | 18 px |
 * | 322…400     | чипы метрик 2 × 118  | 242 px | 44 px число / 18 px подпись |
 * | 414…434     | индикатор раздела    |  64 px | — (PageDotsView) |
 *
 * Карточка графика — единственный элемент шире 250 px, и она стоит в средней полосе
 * (`y` 122…214: лимит круга 354 px при 336 используемых). Что было сломано в v1 и чем
 * это закрыто:
 *  - заголовок v1 стоял `[96,44][370,72]` → угол на 233 px от центра; теперь полоса
 *    90…118 с шириной 260 px → максимум 193 px;
 *  - футер `≈ глубокий … бодрств.` v1 стоял `[96,405][370,410]` → 223.8 px и высота 5 px;
 *    теперь фаза подъёма — отдельная полоса 244…268 высотой 24 px при кегле 20 px
 *    (height ≥ кегль + 4), а сводка фаз ушла в две строки 18 px в полосе 272…318;
 *  - точек раздела не было (наезжали на контент) → теперь контент заканчивается на 400,
 *    а точки стоят в своей полосе 414…434.
 *
 * **Содержимое.** Заголовок, карточка графика ([SleepChartView]: сетка, полосы фаз, линия
 * пульса, столбики движения, подписи времени), легенда фаз, строка «разбудил», две строки
 * сводки, две плашки метрик и индикатор раздела.
 *
 * Данные не менялись: `probe_*.csv` из `getExternalFilesDir(null) ?: filesDir`, приоритет
 * файлу со `triggered` + `wake_phase`, [SleepStats.summarize] в фоновом `Thread`.
 */
class NightPage(host: SectionsActivity) : SectionPage(host) {

    override val root: View

    /** Раздел «Ночь» — второй в карусели (1-based), всего разделов три. */
    override val section: Int get() = Sections.NIGHT

    private lateinit var titleView: TextView
    private lateinit var chart: NightChartsView
    private lateinit var legendRow: LinearLayout
    private lateinit var wakeView: TextView
    private lateinit var summaryView: TextView
    private lateinit var chipsRow: LinearLayout

    /** Подсказка полноэкранного режима (видна только в развёрнутом графике). */
    private lateinit var hintView: TextView

    /** График развёрнут на весь круг: остальные блоки страницы скрыты. */
    private var expanded = false
    private lateinit var chipValue1: TextView
    private lateinit var chipLabel1: TextView
    private lateinit var chipValue2: TextView
    private lateinit var chipLabel2: TextView

    /** Только для подгонки кегля под ширину полосы: трекинг считаем сами. */
    private val fitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.labelTypeface()
        textAlign = Paint.Align.LEFT
    }

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    /**
     * Номер последнего запроса на чтение. Ответ устаревшего потока (пользователь успел
     * свайпнуть туда-обратно) не должен перетереть более свежие данные.
     */
    private var loadGeneration = 0

    init {
        // Построение вьюх — один раз на старте приложения (не на свайпе), поэтому тяжёлую часть
        // держим здесь; чтение CSV — только в [onShown].
        // Страница целиком — ScrollView: графики занимают верх, детали ночи открываются
        // вертикальной прокруткой (высота содержимого CONTENT_H > высоты экрана).
        val page = buildUi()
        root = ScrollView(host).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                page,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    CONTENT_H,
                ),
            )
        }
    }

    /** Страница стала текущей — перечитать ночь. */
    override fun onShown() {
        reload()
    }

    // ---------- вёрстка ----------

    private fun buildUi(): View {
        // Фон рисует хост (градиент под всеми страницами). Своя плашка не нужна: при свайпе
        // непрозрачный прямоугольник страницы дал бы шов с края экрана и лишний слой.
        val page = FrameLayout(host).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // 1. Заголовок: y 90…118, 22 px caps, акцент (height 28 >= кегль 22 + 4).
        titleView = label("ПРОШЛАЯ НОЧЬ", SZ_TITLE, Ui.accent, LS_TITLE)
        page.addView(titleView, band(BAND_TITLE_TOP, BAND_TITLE_BOTTOM, W_TITLE))
        fitOneLine(titleView, MIN_TITLE)

        // 2. Карточка графика: y 122…214, единственный широкий блок (в средней полосе).
        chart = NightChartsView(host)
        page.addView(chart, band(BAND_CHART_TOP, BAND_CHART_BOTTOM, W_CHART))

        // 3. Легенда фаз: ● глубокий ● лёгкий ● бодрств.
        legendRow = buildLegend()
        page.addView(legendRow, band(BAND_LEGEND_TOP, BAND_LEGEND_BOTTOM, W_LEGEND))

        // 4. Фаза подъёма: y 244…268, читаемые 20 px (в v1 это был обрезанный футер 8 sp).
        wakeView = label("читаю лог…", SZ_WAKE, Ui.textSecondary, 0f)
        page.addView(wakeView, band(BAND_WAKE_TOP, BAND_WAKE_BOTTOM, W_WAKE))
        fitOneLine(wakeView, MIN_WAKE)

        // 5. Сводка: две строки 18 px, y 272…318.
        summaryView = block()
        // Детали ночи: открываются вертикальной прокруткой (страница — ScrollView).
        page.addView(summaryView, FrameLayout.LayoutParams(300, 120).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = BAND_SUMMARY_TOP })

        // 6. Подсказка полноэкранного режима — видна только когда график развёрнут.
        hintView = label("ТАП — СВЕРНУТЬ", SZ_HINT, Ui.textTertiary, LS_HINT)
        page.addView(hintView, band(BAND_HINT_TOP, BAND_HINT_BOTTOM, W_HINT))
        hintView.visibility = View.GONE

        // 7. Тап по карточке разворачивает график на весь круг, повторный — сворачивает.
        chart.isClickable = true
        chart.setOnClickListener { toggleExpanded() }

        // 7. Индикатор раздела рисует хост (`SectionsActivity`) один на всё приложение
        //    в полосе y 414…434 — второй экземпляр здесь дублировал бы те же три точки.

        // Самопроверка уже после первой раскладки (иначе ширин ещё нет).
        page.postDelayed({ verifyWithinCircle(page) }, GEOMETRY_CHECK_DELAY_MS)
        return page
    }

    /**
     * Полоса `top…bottom` с абсолютной позицией (как в `StatsActivity`/`AlarmSetupActivity`)
     * и шириной, урезанной по дуге круга: `safeRowWidth` берёт максимум по обоим краям полосы,
     * поэтому даже высокий блок у дуги не обрежется. [EDGE_MARGIN_PX] — зазор до дуги,
     * чтобы угол прямоугольника не касался края экрана.
     */
    /**
     * Полноэкранный график: тап по карточке разворачивает её почти на весь круг
     * (340 × 240 px, y 112…352), остальные блоки страницы прячутся; повторный тап — назад.
     * Внутри [SleepChartView] раскладка считается от фактической высоты, поэтому карточка
     * просто растягивается: полоса фаз, пульс и столбики движения становятся крупнее.
     */
    private fun toggleExpanded() {
        expanded = !expanded
        val lp = chart.layoutParams as FrameLayout.LayoutParams
        if (expanded) {
            lp.topMargin = EXP_CHART_TOP
            lp.height = EXP_CHART_H
            lp.width = EXP_CHART_W.toInt()
        } else {
            lp.topMargin = BAND_CHART_TOP
            lp.height = BAND_CHART_BOTTOM - BAND_CHART_TOP
            lp.width = W_CHART.toInt()
        }
        chart.layoutParams = lp
        val chrome = if (expanded) View.INVISIBLE else View.VISIBLE
        titleView.visibility = chrome
        legendRow.visibility = chrome
        wakeView.visibility = chrome
        summaryView.visibility = chrome
        hintView.visibility = if (expanded) View.VISIBLE else View.GONE
    }
    private fun band(top: Int, bottom: Int, widthCap: Float): FrameLayout.LayoutParams {
        val safe = RoundScreen.safeRowWidth(top.toFloat(), bottom.toFloat()) - EDGE_MARGIN_PX
        val w = if (safe > 0f) minOf(widthCap, safe).toInt().coerceAtLeast(1) else widthCap.toInt()
        return FrameLayout.LayoutParams(w, bottom - top).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = top
        }
    }

    /** Однострочная подпись в типографике v1: caps, трекинг, центр. */
    private fun label(value: String, sizePx: Float, color: Int, tracking: Float): TextView =
        TextView(host).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            setTextColor(color)
            includeFontPadding = false
            gravity = Gravity.CENTER
            letterSpacing = tracking
            typeface = Ui.labelTypeface()
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

    /** Абзац сводки: две строки, обычный вес — длинный текст читается, а не обрезается. */
    private fun block(): TextView = TextView(host).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, SZ_SUMMARY)
        setTextColor(Ui.textSecondary)
        includeFontPadding = false
        gravity = Gravity.CENTER
        typeface = Ui.bodyTypeface()
        setLineSpacing(SUMMARY_LINE_GAP_PX, 1f)
        maxLines = SUMMARY_LINES
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Легенда: точка ⌀12 px + подпись 18 px, цвет точки — из `Ui.phaseColor`. */
    private fun buildLegend(): LinearLayout {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        data class Item(val color: Int, val caption: String)
        val items = listOf(
            Item(Ui.phaseDeep, "глубокий"),
            Item(Ui.phaseLight, "лёгкий"),
            Item(Ui.phaseAwake, "бодрств."),
        )
        items.forEachIndexed { index, item ->
            val cell = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (index > 0) setPadding(LEGEND_GAP_PX, 0, 0, 0)
            }
            cell.addView(
                View(host).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(item.color)
                    }
                },
                LinearLayout.LayoutParams(LEGEND_DOT_PX, LEGEND_DOT_PX).apply {
                    rightMargin = LEGEND_DOT_GAP_PX
                },
            )
            cell.addView(label(item.caption, SZ_LEGEND, Ui.textSecondary, 0f))
            row.addView(cell)
        }
        return row
    }

    /** Две плашки метрик: число 44 px тонким шрифтом, подпись 18 px под ним. */
    private fun buildChips(): LinearLayout {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        chipValue1 = label("—", SZ_CHIP_VALUE, Ui.text, 0f).apply { typeface = Ui.clockTypeface() }
        chipLabel1 = label("эффективность", SZ_CHIP_LABEL, Ui.textSecondary, 0f)
        chipValue2 = label("—", SZ_CHIP_VALUE, Ui.text, 0f).apply { typeface = Ui.clockTypeface() }
        chipLabel2 = label("пробуждений", SZ_CHIP_LABEL, Ui.textSecondary, 0f)
        row.addView(
            chip(chipValue1, chipLabel1),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = CHIP_GAP_PX / 2
            },
        )
        row.addView(
            chip(chipValue2, chipLabel2),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = CHIP_GAP_PX / 2
            },
        )
        return row
    }

    /** Плашка: поверхность `Ui.surface`, обводка `Ui.stroke`, радиус 14 dp. */
    private fun chip(value: TextView, caption: TextView): LinearLayout =
        LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(CHIP_PAD_X_DP), dp(CHIP_PAD_Y_DP), dp(CHIP_PAD_X_DP), dp(CHIP_PAD_Y_DP))
            background = GradientDrawable().apply {
                cornerRadius = CHIP_RADIUS_PX
                setColor(Ui.surface)
                setStroke(2, Ui.stroke)
            }
            addView(value)
            addView(caption)
        }

    // ---------- подгонка кегля (защита от обрезки текста) ----------

    /** Максимальная ширина строки на этом кегле, включая трекинг (в Paint его нет). */
    private fun measure(text: String, sizePx: Float, tracking: Float): Float {
        fitPaint.textSize = sizePx
        return fitPaint.measureText(text) + sizePx * tracking * text.length
    }

    /**
     * Уменьшает кегль, пока все строки не влезут в [maxWidth]. Благодаря этому
     * в полосе не бывает ни многоточия, ни обрезанного по высоте текста.
     */
    private fun fitSize(view: TextView, lines: List<String>, maxWidth: Float, minPx: Float) {
        val filled = lines.filter { it.isNotEmpty() }
        if (filled.isEmpty() || maxWidth <= 0f) return
        fitPaint.typeface = view.typeface
        var size = view.textSize
        while (size > minPx) {
            val widest = filled.maxOf { measure(it, size, view.letterSpacing) }
            if (widest <= maxWidth) break
            size -= 1f
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    /** Подгонка однострочной подписи под ширину её полосы. */
    private fun fitOneLine(view: TextView, minPx: Float) {
        val lp = view.layoutParams
        if (lp == null || lp.width <= 0) return
        fitSize(view, listOf(view.text.toString()), lp.width - TEXT_PAD_PX, minPx)
    }

    /** Подгонка плашек метрик: и число, и подпись — по внутренней ширине плашки. */
    private fun fitChips() {
        val inner = (W_CHIPS - CHIP_GAP_PX) / 2f - 2f * dp(CHIP_PAD_X_DP)
        fitSize(chipValue1, listOf(chipValue1.text.toString()), inner, MIN_CHIP_VALUE)
        fitSize(chipValue2, listOf(chipValue2.text.toString()), inner, MIN_CHIP_VALUE)
        fitSize(chipLabel1, listOf(chipLabel1.text.toString()), inner, MIN_CHIP_LABEL)
        fitSize(chipLabel2, listOf(chipLabel2.text.toString()), inner, MIN_CHIP_LABEL)
    }

    // ---------- данные (не менялись с v1) ----------

    /**
     * Чтение ночи — только по показу страницы ([onShown]), не на старте приложения:
     * файл разбирается в фоновом потоке, потому что `parse` + `summarize` на 5-часовом
     * логе — это десятки тысяч строк, и в главном потоке они превращаются в рывок.
     */
    private fun reload() {
        val generation = ++loadGeneration
        Thread {
            val file = bestProbeFile()
            val night = try {
                file?.let { f -> SleepStats.summarize(SleepStats.parse(f.readLines())) }
            } catch (_: Throwable) {
                null
            }
            host.runOnUiThread {
                // Пока читали, страницу могли показать ещё раз — рендерим только свежий ответ.
                if (generation == loadGeneration) render(file, night)
            }
        }.start()
    }

    private fun render(file: File?, n: SleepStats.Night?) {
        chart.setNight(n)
        if (file == null || n == null) {
            // Нет лога — «нет данных» словами, а не пустые места (урок v2).
            wakeView.text = "нет данных"
            wakeView.setTextColor(Ui.textSecondary)
            fitOneLine(wakeView, MIN_WAKE)
            chipValue1.text = "—"
            chipValue2.text = "—"
            fitChips()
            setSummary(
                listOf(
                    "ночного лога нет · probe_*.csv не найден",
                    "включи ночь на главном экране",
                ),
            )
            return
        }

        // Фаза, в которой разбудил будильник: подпись через PhaseEstimator (читаемо,
        // а не кодом из CSV) и пульс в этот момент.
        val wake = buildString {
            append("разбудил: ").append(PhaseEstimator.label(n.wakePhase))
            if (n.wakePhase != SleepStats.Stage.UNKNOWN && n.wakeHr > 0) {
                append(" · ").append(n.wakeHr).append(" уд")
            }
        }
        wakeView.text = wake
        wakeView.setTextColor(
            if (n.wakePhase == SleepStats.Stage.UNKNOWN) Ui.textSecondary else Ui.accent,
        )
        fitOneLine(wakeView, MIN_WAKE)

        // Плашек метрик на экране больше нет (место отдано графику), поэтому chipValue1/2
        // не трогаем: обращение к ним падало с UninitializedPropertyAccessException.

        // Сводка: две фиксированные строки. Имя файла — только если влезает: экран
        // вторичный, а полоса ограничена дугой круга.
        val start = timeFmt.format(Instant.ofEpochMilli(n.startTs))
        val end = timeFmt.format(Instant.ofEpochMilli(n.endTs))
        val cov = n.coveragePct.toInt()
        val covMark = if (cov < 95) " ⚠" else " ✓"
        val line1 = "$start–$end · ${hm(n.durationMin)} · покрытие $cov%$covMark"
        // Пульс рисовать цифрами больше не нужно: он и так на графике. Вместо плашек метрик
        // (убраны ради высоты графика) цифры эффективности и пробуждений ушли в эту строку.
        val effPart = "${n.efficiencyPct.toInt()}% эфф · ${n.awakenings} проб"
        val batPart = if (n.batteryStart in 0..100 && n.batteryEnd in 0..100) {
            "бат. ${n.batteryStart}→${n.batteryEnd}%"
        } else {
            "бат. н/д"
        }
        var line2 = "$effPart · $batPart"
        val filePart = " · файл " + file.name.removePrefix("probe_").removeSuffix(".csv")
        // Имя файла — служебное: показываем, только если строка с ним влезает в полосу.
        fitPaint.typeface = Ui.bodyTypeface()
        if (measure(line2 + filePart, SZ_SUMMARY, 0f) <= W_SUMMARY - TEXT_PAD_PX) {
            line2 += filePart
        }
        setSummary(listOf(line1, line2))
    }

    private fun setSummary(lines: List<String>) {
        summaryView.text = lines.joinToString("\n")
        fitSize(summaryView, lines, W_SUMMARY - TEXT_PAD_PX, MIN_SUMMARY)
    }

    private fun hm(minutes: Int): String =
        if (minutes < 60) "${minutes}м" else "${minutes / 60}ч ${minutes % 60}м"

    /**
     * Выбор файла ночи: приоритет — файл, в котором есть строка срабатывания с записанной
     * фазой подъёма (иначе на экране нечего показать про пробуждение). Если таких нет —
     * самый информативный по размеру.
     */
    private fun bestProbeFile(): File? {
        val dir = host.getExternalFilesDir(null) ?: host.filesDir
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("probe_") && it.name.endsWith(".csv") }
            ?: return null
        if (files.isEmpty()) return null
        return files.filter { hasWakePhaseRow(it) }.maxByOrNull { it.length() }
            ?: files.maxWithOrNull(compareBy<File> { it.length() }.thenBy { it.lastModified() })
    }

    /** Есть ли в файле строка «будильник сработал» с непустой фазой (21-я колонка). */
    private fun hasWakePhaseRow(f: File): Boolean = try {
        f.useLines { lines ->
            lines.any { line ->
                val parts = line.trimEnd().split(',')
                parts.size >= 21 && parts[19] == "triggered" && parts[20].isNotBlank()
            }
        }
    } catch (_: Throwable) {
        false
    }

    // ---------- самопроверка геометрии на устройстве ----------

    /**
     * Проверка R1 на живой вёрстке: каждый блок контента и индикатор обязаны лежать
     * внутри круга `r = 209`. Нарушение — в [UiLog], его видно из probe-лога.
     * Ширины уже посчитаны через [RoundScreen.safeRowWidth]; это контроль, а не расчёт.
     */
    private fun verifyWithinCircle(page: ViewGroup) {
        for (index in 0 until page.childCount) {
            val view = page.getChildAt(index)
            if (view.width <= 0 || view.visibility != View.VISIBLE) continue
            val inside = RoundScreen.fits(
                view.left.toFloat(),
                view.top.toFloat(),
                view.right.toFloat(),
                view.bottom.toFloat(),
            )
            if (!inside) {
                UiLog.add(
                    "night geometry: #$index [${view.left},${view.top}]" +
                        "[${view.right},${view.bottom}] вне r=${Ui.SAFE_RADIUS_PX}",
                )
            }
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), host.resources.displayMetrics,
    ).toInt()

    private companion object {
        /** Разделов в карусели три (совпадает с `Sections.HOME/NIGHT/PULT`). */
        const val PAGES = 3

        // ---------- вертикальные полосы, px (1 dp = 2 px на OPW2 466×466) ----------
        const val BAND_TITLE_TOP = 90
        const val BAND_TITLE_BOTTOM = 118
        const val BAND_CHART_TOP = 122
        const val BAND_CHART_BOTTOM = 352
        const val BAND_LEGEND_TOP = 358
        const val BAND_LEGEND_BOTTOM = 378
        const val BAND_WAKE_TOP = 382
        const val BAND_WAKE_BOTTOM = 400
        const val BAND_SUMMARY_TOP = 404

        /** Высота прокручиваемого содержимого: графики, легенда, фаза и детали ночи. */
        const val CONTENT_H = 540
        const val BAND_SUMMARY_BOTTOM = 390
        const val BAND_CHIPS_TOP = 322
        const val BAND_CHIPS_BOTTOM = 400
        const val BAND_DOTS_TOP = 414
        const val BAND_DOTS_BOTTOM = 434

        // ---------- ширины полос, px (все ≤ safeRowWidth своей полосы) ----------
        const val W_TITLE = 260f
        const val W_CHART = 340f
        const val W_LEGEND = 300f
        const val W_WAKE = 240f
        const val W_SUMMARY = 268f
        const val W_CHIPS = 242f

        // Полноэкранный график: 340×240 в y 112…352 (углы 208.7 <= 209).
        const val EXP_CHART_TOP = 112
        const val EXP_CHART_H = 240
        const val EXP_CHART_W = 340f

        // Подсказка под развёрнутым графиком.
        const val BAND_HINT_TOP = 366
        const val BAND_HINT_BOTTOM = 386
        const val W_HINT = 280f
        const val SZ_HINT = 16f
        const val LS_HINT = 0.18f
        const val W_DOTS = 64f

        // ---------- кегли, px ----------
        const val SZ_TITLE = 22f
        const val SZ_LEGEND = 18f
        const val SZ_WAKE = 20f
        const val SZ_SUMMARY = 18f
        const val SZ_CHIP_VALUE = 44f
        const val SZ_CHIP_LABEL = 18f

        /** Ниже этих кеглей не опускаемся: дизайн-минимум 16 px. */
        const val MIN_TITLE = 18f
        const val MIN_WAKE = 18f
        const val MIN_SUMMARY = 16f
        const val MIN_CHIP_LABEL = 15f
        const val MIN_CHIP_VALUE = 30f

        /** Зазор от прямоугольника полосы до дуги и запас внутри TextView. */
        const val EDGE_MARGIN_PX = 8f
        const val TEXT_PAD_PX = 6f

        /** Трекинг заголовка — как у v1-заголовков приложения. */
        const val LS_TITLE = 0.14f

        /** Сводка: две строки, межстрочный зазор 2 px. */
        const val SUMMARY_LINES = 2
        const val SUMMARY_LINE_GAP_PX = 2f

        // ---------- плашки ----------
        const val CHIP_GAP_PX = 6
        const val CHIP_RADIUS_PX = 28f
        const val CHIP_PAD_X_DP = 2
        const val CHIP_PAD_Y_DP = 1
        const val LEGEND_DOT_PX = 12
        const val LEGEND_DOT_GAP_PX = 6
        const val LEGEND_GAP_PX = 16

        /** Задержка самопроверки геометрии: после первой раскладки. */
        const val GEOMETRY_CHECK_DELAY_MS = 300L
    }
}
