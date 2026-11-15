package io.github.smartalarm

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.wear.ambient.AmbientLifecycleObserver
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Экран 2 «НОЧЬ» — прошлая ночь в **языке v1** (янтарный v2 забракован): градиентный
 * фон `BG_TOP → BG_BOTTOM`, карточка со скруглением и обводкой `Ui.surface` / `Ui.stroke`,
 * полоса фаз цветами `Ui.phaseColor`, линия пульса акцентом с градиентной заливкой,
 * столбики движения `Ui.surfaceHi`, точки-легенда, плашки метрик с числом 44 px,
 * текст сводки. Починена только геометрия и добавлены состояния.
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
 * | 412…424     | индикатор раздела    |  64 px | — (PageDotsView) |
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
 *    а точки стоят в своей полосе 412…424.
 *
 * **Состояния.** AOD через [AmbientLifecycleObserver]: чёрный фон `#000000`, притушённые
 * заголовок и график без сетки/столбиков/анимаций, остальное скрыто. Отладочный вход —
 * `adb shell am start -n io.github.smartalarm/.StatsActivity --ez force_ambient true`.
 * Навигация по кругу разделов — [SectionSwipe] (`Sections.NIGHT`), ручной разбор свайпов
 * удалён: цикл 1 → 2 → 3 → 1 обеспечивает [SwipeSections].
 *
 * Данные не менялись: `probe_*.csv` из `getExternalFilesDir(null) ?: filesDir`, приоритет
 * файлу со `triggered` + `wake_phase`, [SleepStats.summarize] в фоновом `Thread`.
 */
class StatsActivity : ComponentActivity() {

    private lateinit var root: FrameLayout

    /** Слой, который тянется за пальцем при свайпе между разделами. */
    private lateinit var content: FrameLayout

    private lateinit var titleView: TextView
    private lateinit var chart: SleepChartView
    private lateinit var legendRow: LinearLayout
    private lateinit var wakeView: TextView
    private lateinit var summaryView: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var chipValue1: TextView
    private lateinit var chipLabel1: TextView
    private lateinit var chipValue2: TextView
    private lateinit var chipLabel2: TextView
    private lateinit var dots: PageDotsView
    private lateinit var swipe: SectionSwipe

    private var ambientObserver: AmbientLifecycleObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Только для подгонки кегля под ширину полосы: трекинг считаем сами. */
    private val fitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.labelTypeface()
        textAlign = Paint.Align.LEFT
    }

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupAmbient()
        // Отладка AOD без включённого AOD на устройстве (как в HomeActivity).
        if (intent?.getBooleanExtra("force_ambient", false) == true) {
            handler.postDelayed({ applyAmbient(true) }, DEMO_AMBIENT_DELAY_MS)
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        // После slide-перехода между разделами контент возвращаем на место.
        if (::swipe.isInitialized) swipe.reset()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Навигация по кругу разделов (R11–R13). Жест целиком обслуживает [SectionSwipe]:
     * он же тянет `content` за пальцем, доводит и открывает соседний раздел со
     * slide-переходом. Свой разбор свайпов и `finish()`/`startActivity` из v1 удалены.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::swipe.isInitialized) swipe.handle(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---------- AOD ----------

    private fun setupAmbient() {
        ambientObserver = AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(details: AmbientLifecycleObserver.AmbientDetails) {
                    applyAmbient(true)
                }

                override fun onUpdateAmbient() {
                    // Экран статичный: в AOD перерисовывать нечего (R9 — без анимаций).
                }

                override fun onExitAmbient() {
                    applyAmbient(false)
                }
            },
        )
    }

    /**
     * В покое остаются только чёрный фон, притушённый заголовок и график без сетки,
     * столбиков и анимаций: легенда, фаза, сводка, чипы и точки скрыты, чтобы AMOLED
     * не выжигался (R9).
     */
    private fun applyAmbient(on: Boolean) {
        if (!::root.isInitialized) return
        // В AOD — только чёрный фон (R9): градиент убираем, чтобы не светить AMOLED.
        val bg: Drawable = if (on) ColorDrawable(Color.BLACK) else screenGradient()
        root.background = bg
        content.background = if (on) ColorDrawable(Color.BLACK) else screenGradient()
        titleView.setTextColor(if (on) Ui.textTertiary else Ui.accent)
        chart.setAmbient(on)
        val v = if (on) View.INVISIBLE else View.VISIBLE
        legendRow.visibility = v
        wakeView.visibility = v
        summaryView.visibility = v
        chipsRow.visibility = v
        dots.visibility = v
    }

    // ---------- вёрстка ----------

    private fun buildUi() {
        // Фон градиентом лежит и на root, и на content: при свайпе контент уезжает
        // вместе со своим фоном, а root не оставляет «дыры» на краю экрана.
        root = FrameLayout(this).apply { background = screenGradient() }
        content = FrameLayout(this).apply { background = screenGradient() }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // 1. Заголовок: y 90…118, 22 px caps, акцент (height 28 >= кегль 22 + 4).
        titleView = label("ПРОШЛАЯ НОЧЬ", SZ_TITLE, Ui.accent, LS_TITLE)
        content.addView(titleView, band(BAND_TITLE_TOP, BAND_TITLE_BOTTOM, W_TITLE))
        fitOneLine(titleView, MIN_TITLE)

        // 2. Карточка графика: y 122…214, единственный широкий блок (в средней полосе).
        chart = SleepChartView(this)
        content.addView(chart, band(BAND_CHART_TOP, BAND_CHART_BOTTOM, W_CHART))

        // 3. Легенда фаз: ● глубокий ● лёгкий ● бодрств.
        legendRow = buildLegend()
        content.addView(legendRow, band(BAND_LEGEND_TOP, BAND_LEGEND_BOTTOM, W_LEGEND))

        // 4. Фаза подъёма: y 244…268, читаемые 20 px (в v1 это был обрезанный футер 8 sp).
        wakeView = label("читаю лог…", SZ_WAKE, Ui.textSecondary, 0f)
        content.addView(wakeView, band(BAND_WAKE_TOP, BAND_WAKE_BOTTOM, W_WAKE))
        fitOneLine(wakeView, MIN_WAKE)

        // 5. Сводка: две строки 18 px, y 272…318.
        summaryView = block()
        content.addView(summaryView, band(BAND_SUMMARY_TOP, BAND_SUMMARY_BOTTOM, W_SUMMARY))

        // 6. Плашки метрик: число 44 px + подпись, y 322…400.
        chipsRow = buildChips()
        content.addView(chipsRow, band(BAND_CHIPS_TOP, BAND_CHIPS_BOTTOM, W_CHIPS))

        // 7. Индикатор раздела 2/3 — отдельная полоса под контентом (y 412…424),
        //    поэтому он больше не наезжает ни на сводку, ни на чипы.
        dots = PageDotsView(this).apply { setSection(Sections.NIGHT, SECTIONS_TOTAL) }
        root.addView(dots, band(BAND_DOTS_TOP, BAND_DOTS_BOTTOM, W_DOTS))

        swipe = SectionSwipe(this, Sections.NIGHT, content)
        setContentView(root)
        // Самопроверка уже после первой раскладки (иначе ширин ещё нет).
        root.postDelayed({ verifyWithinCircle() }, GEOMETRY_CHECK_DELAY_MS)
    }

    /**
     * Полоса `top…bottom` с абсолютной позицией (как в [AlarmSetupActivity]) и шириной,
     * урезанной по дуге круга: `safeRowWidth` берёт максимум по обоим краям полосы,
     * поэтому даже высокий блок у дуги не обрежется. [EDGE_MARGIN_PX] — зазор до дуги,
     * чтобы угол прямоугольника не касался края экрана.
     */
    private fun band(top: Int, bottom: Int, widthCap: Float): FrameLayout.LayoutParams {
        val safe = RoundScreen.safeRowWidth(top.toFloat(), bottom.toFloat()) - EDGE_MARGIN_PX
        val w = if (safe > 0f) minOf(widthCap, safe).toInt().coerceAtLeast(1) else widthCap.toInt()
        return FrameLayout.LayoutParams(w, bottom - top).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = top
        }
    }

    /** Фон экрана: глубокий вертикальный градиент из токенов v1. */
    private fun screenGradient(): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor(Ui.BG_TOP), Color.parseColor(Ui.BG_BOTTOM)),
    )

    /** Однострочная подпись в типографике v1: caps, трекинг, центр. */
    private fun label(value: String, sizePx: Float, color: Int, tracking: Float): TextView =
        TextView(this).apply {
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
    private fun block(): TextView = TextView(this).apply {
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
        val row = LinearLayout(this).apply {
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
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (index > 0) setPadding(LEGEND_GAP_PX, 0, 0, 0)
            }
            cell.addView(
                View(this).apply {
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
        val row = LinearLayout(this).apply {
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
        LinearLayout(this).apply {
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

    private fun load() {
        Thread {
            val file = bestProbeFile()
            val night = try {
                file?.let { f -> SleepStats.summarize(SleepStats.parse(f.readLines())) }
            } catch (_: Throwable) {
                null
            }
            runOnUiThread { render(file, night) }
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

        chipValue1.text = "${n.efficiencyPct.toInt()}%"
        chipValue2.text = "${n.awakenings}"
        fitChips()

        // Сводка: две фиксированные строки. Имя файла — только если влезает: экран
        // вторичный, а полоса ограничена дугой круга.
        val start = timeFmt.format(Instant.ofEpochMilli(n.startTs))
        val end = timeFmt.format(Instant.ofEpochMilli(n.endTs))
        val cov = n.coveragePct.toInt()
        val covMark = if (cov < 95) " ⚠" else " ✓"
        val line1 = "$start–$end · ${hm(n.durationMin)} · покрытие $cov%$covMark"
        val hrPart = if (n.hrMin > 0) "пульс ${n.hrMin}–${n.hrMax} ср.${n.hrAvg}" else "пульс н/д"
        val batPart = if (n.batteryStart in 0..100 && n.batteryEnd in 0..100) {
            "бат. ${n.batteryStart}→${n.batteryEnd}%"
        } else {
            "бат. н/д"
        }
        var line2 = "$hrPart · $batPart"
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
        val dir = getExternalFilesDir(null) ?: filesDir
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
    private fun verifyWithinCircle() {
        val views = ArrayList<View>()
        for (i in 0 until content.childCount) views.add(content.getChildAt(i))
        if (::dots.isInitialized) views.add(dots)
        views.forEachIndexed { index, view ->
            if (view.width <= 0 || view.visibility != View.VISIBLE) return@forEachIndexed
            val inside = RoundScreen.fits(
                view.left.toFloat(),
                view.top.toFloat(),
                view.right.toFloat(),
                view.bottom.toFloat(),
            )
            if (!inside) {
                UiLog.add(
                    "stats geometry: #$index [${view.left},${view.top}]" +
                        "[${view.right},${view.bottom}] вне r=${Ui.SAFE_RADIUS_PX}",
                )
            }
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private companion object {
        // ---------- вертикальные полосы, px (1 dp = 2 px на OPW2 466×466) ----------
        const val BAND_TITLE_TOP = 90
        const val BAND_TITLE_BOTTOM = 118
        const val BAND_CHART_TOP = 122
        const val BAND_CHART_BOTTOM = 214
        const val BAND_LEGEND_TOP = 218
        const val BAND_LEGEND_BOTTOM = 240
        const val BAND_WAKE_TOP = 244
        const val BAND_WAKE_BOTTOM = 268
        const val BAND_SUMMARY_TOP = 272
        const val BAND_SUMMARY_BOTTOM = 318
        const val BAND_CHIPS_TOP = 322
        const val BAND_CHIPS_BOTTOM = 400
        const val BAND_DOTS_TOP = 412
        const val BAND_DOTS_BOTTOM = 424

        // ---------- ширины полос, px (все ≤ safeRowWidth своей полосы) ----------
        const val W_TITLE = 260f
        const val W_CHART = 336f
        const val W_LEGEND = 300f
        const val W_WAKE = 312f
        const val W_SUMMARY = 330f
        const val W_CHIPS = 242f
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

        // ---------- индикатор раздела ----------
        const val SECTIONS_TOTAL = 3

        // ---------- плашки ----------
        const val CHIP_GAP_PX = 6
        const val CHIP_RADIUS_PX = 28f
        const val CHIP_PAD_X_DP = 2
        const val CHIP_PAD_Y_DP = 1
        const val LEGEND_DOT_PX = 12
        const val LEGEND_DOT_GAP_PX = 6
        const val LEGEND_GAP_PX = 16

        /** Задержка отладочного входа в AOD (`--ez force_ambient true`). */
        const val DEMO_AMBIENT_DELAY_MS = 400L

        /** Задержка самопроверки геометрии: после первой раскладки. */
        const val GEOMETRY_CHECK_DELAY_MS = 300L
    }
}
