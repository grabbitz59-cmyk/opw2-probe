package io.github.smartalarm

import android.graphics.Paint
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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Экран 2 «НОЧЬ» — статистика прошлой ночи (§3.3 `docs/design-v2.md`).
 *
 * Один вопрос за 0.5 с (R5): «как я спал и в какой фазе меня разбудили». Скролла нет:
 * гипнограмма + строка метрик + фаза подъёма. Длинная сводка (покрытие, батарея, файл)
 * переехала в служебные строки экрана 3.
 *
 * Данные — те же, что были в v1: ночной `probe_*.csv` из `getExternalFilesDir(null)`,
 * приоритет файлу со строкой `triggered` + `wake_phase`, разбор через [SleepStats].
 * Переписана только вёрстка.
 *
 * Геометрия: все полосы — абсолютные `topMargin` (как в [AlarmSetupActivity]), ширина
 * урезается так, чтобы **все четыре угла** прямоугольника лежали внутри `r = Ui.BEZEL_IN`
 * (контент не заходит в annulus обода 188…208). Тексты дополнительно ужимаются по кеглю,
 * поэтому в полосе не бывает многоточия.
 *
 * Навигация — свайпом через [SectionSwipe] (R11–R13), AOD — через [AmbientLifecycleObserver]:
 * в покое остаются заголовок и базовая линейка гипнограммы.
 */
class StatsActivity : ComponentActivity() {

    private lateinit var root: FrameLayout

    /** Контент, который тянется за пальцем при свайпе между разделами. */
    private lateinit var content: FrameLayout

    private lateinit var index: SectionIndexView
    private lateinit var bezel: BezelView
    private lateinit var titleView: TextView
    private lateinit var metricsView: TextView
    private lateinit var chart: HypnogramView
    private lateinit var legendRow: LinearLayout
    private lateinit var wakeLabel: TextView
    private lateinit var wakeValue: TextView
    private lateinit var swipe: SectionSwipe

    private var ambientObserver: AmbientLifecycleObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Только для измерения ширины строки; типографика — как у TextView из [Ui.label]. */
    private val fitPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.condensed()
        textAlign = Paint.Align.LEFT
        // Трекинг считаем сами: так `measureText` гарантированно отдаёт сумму литерал.
        letterSpacing = 0f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupAmbient()
        // Отладка AOD без включённого AOD на устройстве:
        // adb shell am start -n io.github.smartalarm/.StatsActivity --ez force_ambient true
        if (intent?.getBooleanExtra("force_ambient", false) == true) {
            handler.postDelayed({ applyAmbient(true) }, DEMO_AMBIENT_DELAY_MS)
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        // Возвращаемся на место после slide-перехода между разделами.
        if (::swipe.isInitialized) swipe.reset()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

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
                    // Экран статичный: перерисовывать в AOD нечего.
                }

                override fun onExitAmbient() {
                    applyAmbient(false)
                }
            },
        )
    }

    /**
     * В покое остаются только заголовок (притушённый) и базовая линейка гипнограммы —
     * ни подписей, ни фаз, ни янтаря: так AMOLED не выжигается.
     */
    private fun applyAmbient(on: Boolean) {
        if (!::root.isInitialized || !::content.isInitialized) return
        val bg = if (on) Ui.bgAmbient else Ui.bg
        root.setBackgroundColor(bg)
        content.setBackgroundColor(bg)
        titleView.setTextColor(if (on) Ui.paper3 else Ui.ember)
        if (::bezel.isInitialized) bezel.setAmbient(on)
        val v = if (on) View.INVISIBLE else View.VISIBLE
        index.visibility = v
        metricsView.visibility = v
        legendRow.visibility = v
        wakeLabel.visibility = v
        wakeValue.visibility = v
        chart.setAmbient(on)
    }

    // ---------- вёрстка ----------

    private fun buildUi() {
        // Фон плоский и лежит и на root, и на content (как в HomeActivity): при свайпе
        // контент уезжает вместе со своим фоном, а root не даёт «дыры» на краю.
        root = FrameLayout(this).apply { setBackgroundColor(Ui.bg) }
        content = FrameLayout(this).apply { setBackgroundColor(Ui.bg) }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Обод: та же шкала 72 риски, что на главном, — иначе экраны не читаются как один прибор.
        bezel = BezelView(this)
        content.addView(
            bezel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // y 60–72: индикатор раздела (2/3). Верх — из токена Ui.INDEX_TOP, не хардкод.
        index = SectionIndexView(this).apply { active = Sections.NIGHT }
        content.addView(index, band(top = Ui.INDEX_TOP.toInt(), height = 12, widthCap = 64f))

        // y 96–124: заголовок.
        titleView = label("ПРОШЛАЯ НОЧЬ", Ui.SZ_TITLE, Ui.ember, Ui.LS_TITLE)
        content.addView(titleView, band(top = 96, height = 28, widthCap = 300f))
        fitText(titleView, Ui.SZ_TITLE)

        // y 132–156: строка метрик «6Ч 42М · 58 УД · 92%».
        metricsView = label("—", Ui.SZ_METRICS, Ui.paper2, Ui.LS_DETAIL)
        content.addView(metricsView, band(top = 132, height = 24, widthCap = 320f))

        // y 164–286: гипнограмма 320 × 122.
        chart = HypnogramView(this)
        content.addView(chart, band(top = 164, height = 122, widthCap = 320f))

        // y 292–316: легенда тремя глифами (глубина кодируется и шириной глифа).
        legendRow = buildLegend()
        content.addView(legendRow, band(top = 292, height = 24, widthCap = 300f))

        // y 322–346: подпись фазы.
        wakeLabel = label("РАЗБУДИЛ В ФАЗЕ", Ui.SZ_LEGEND, Ui.paper3, Ui.LS_DETAIL)
        content.addView(wakeLabel, band(top = 322, height = 24, widthCap = 300f))
        fitText(wakeLabel, Ui.SZ_LEGEND)

        // y 348–396: значение фазы. Ширина ≤ 187 px — короткие формы («ЛЁГК.», «ГЛУБ.», «БОДР.»).
        wakeValue = label("—", Ui.SZ_BIG, Ui.ember, 0f)
        content.addView(wakeValue, band(top = 348, height = 48, widthCap = 187f))

        swipe = SectionSwipe(this, Sections.NIGHT, content)
        setContentView(root)
    }

    /** TextView в токенах v2: caps, трекинг, центр — фабрика [Ui.label]. */
    private fun label(text: String, px: Float, color: Int, letterSpacing: Float): TextView =
        Ui.label(this, text, px, color, letterSpacing, caps = true).apply {
            gravity = Gravity.CENTER
            // Последний рубеж: если кегль уже минимальный, длинная строка получит многоточие.
            ellipsize = TextUtils.TruncateAt.END
        }

    /**
     * Полоса `top…top+height` с абсолютной позицией (как в [AlarmSetupActivity]) и шириной,
     * урезанной так, чтобы прямоугольник целиком лежал внутри окружности обода `r = 188`,
     * но не шире таблицы дизайна.
     */
    private fun band(top: Int, height: Int, widthCap: Float): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = top
        }
        val bottom = (top + height).toFloat()
        // Модель RoundScreen (R = 209) — базовая; annulus обода (R = 188) жёстче, берём её.
        val inModel = RoundScreen.safeRowWidth(top.toFloat(), bottom)
        val insideBezel = safeRowWidthBezel(top.toFloat(), bottom)
        val safe = minOf(inModel, insideBezel) - ROW_SIDE_PAD_PX
        lp.width = if (safe > 0f) {
            minOf(widthCap, safe).toInt().coerceAtLeast(1)
        } else {
            widthCap.toInt()
        }
        return lp
    }

    /**
     * Максимальная ширина полосы, у которой **все четыре угла** лежат внутри `r = Ui.BEZEL_IN`
     * (188 px): контент не заходит в annulus обода 188…208 даже по диагонали.
     */
    private fun safeRowWidthBezel(top: Float, bottom: Float): Float {
        val dyMax = maxOf(abs(top - Ui.CY), abs(bottom - Ui.CY))
        if (dyMax >= Ui.BEZEL_IN) return 0f
        val r = Ui.BEZEL_IN
        return 2f * sqrt(r * r - dyMax * dyMax)
    }

    /**
     * Ужимает кегль под фактическую ширину полосы: caps + tracking в этой типографике широкие,
     * а строка обязана влезть целиком — без многоточия и без выхода за обод.
     * Ниже 24 px (§2.2 design-v2) не опускаемся.
     */
    private fun fitText(view: TextView, preferPx: Float) {
        val lp = view.layoutParams
        if (lp == null || lp.width <= 0) return
        val maxW = (lp.width - TEXT_SIDE_PAD_PX).toFloat()
        if (maxW <= 0f) return
        val text = view.text.toString()
        if (text.isEmpty()) return
        var size = preferPx
        while (size > Ui.SZ_TELEMETRY) {
            if (measuredWidth(text, size, view.letterSpacing) <= maxW) break
            size -= 1f
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    /** Ширина строки: сумма литерал + tracking (в `Paint.measureText` трекинга нет). */
    private fun measuredWidth(text: String, px: Float, letterSpacing: Float): Float {
        fitPaint.textSize = px
        val tracking = px * letterSpacing * text.length
        return fitPaint.measureText(text) + tracking
    }

    /**
     * Легенда: три пары «глиф + подпись». Ширина глифа кодирует глубину
     * (▍ глубже ▎ ▏), цвет — фаза из `Ui.phaseColor`, подпись — `Ui.phaseShort`.
     */
    private fun buildLegend(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val glyphs = arrayOf("▍", "▎", "▏")
        val colors = intArrayOf(Ui.ember, Ui.emberDim, Ui.phaseAwakeColor)
        val stages = arrayOf(SleepStats.Stage.DEEP, SleepStats.Stage.LIGHT, SleepStats.Stage.AWAKE)

        for (i in stages.indices) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (i > 0) setPadding(LEGEND_GAP_PX, 0, 0, 0)
            }

            val glyph = TextView(this).apply {
                text = glyphs[i]
                setTextColor(colors[i])
                typeface = Ui.condensed()
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.SZ_LEGEND)
                letterSpacing = 0f
                includeFontPadding = false
                setSingleLine(true)
                gravity = Gravity.CENTER
            }
            val caption = Ui.label(
                this,
                Ui.phaseShort(stages[i]),
                Ui.SZ_LEGEND,
                Ui.paper3,
                Ui.LS_DETAIL,
                caps = true,
            ).apply { gravity = Gravity.CENTER }

            cell.addView(
                glyph,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply { rightMargin = GLYPH_GAP_PX },
            )
            cell.addView(
                caption,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            row.addView(
                cell,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        return row
    }

    // ---------- данные (сохранено из v1) ----------

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
            // Нет лога: гипнограмма рисует «НЕТ ДАННЫХ», метрики и фаза — прочерк.
            metricsView.text = "—"
            fitText(metricsView, Ui.SZ_METRICS)
            wakeValue.text = "—"
            fitText(wakeValue, Ui.SZ_BIG)
            return
        }
        metricsView.text = metricsText(n)
        fitText(metricsView, Ui.SZ_METRICS)
        wakeValue.text = wakePhaseText(n.wakePhase)
        fitText(wakeValue, Ui.SZ_BIG)
    }

    /** «6Ч 42М · 58 УД · 92%»: сон · средний пульс · эффективность; неизвестное — «—». */
    private fun metricsText(n: SleepStats.Night): String {
        val duration = hmCaps(n.durationMin)
        val hr = if (n.hrAvg > 0) "${n.hrAvg} УД" else "—"
        val eff = if (n.efficiencyPct > 0.0) "${n.efficiencyPct.toInt()}%" else "—"
        return "$duration · $hr · $eff"
    }

    private fun hmCaps(totalMin: Int): String {
        val m = totalMin.coerceAtLeast(0)
        return "${m / 60}Ч ${m % 60}М"
    }

    /**
     * Фаза подъёма в 52 px при ширине ≤ 187 px: полное «БОДРСТВОВАНИЕ» физически не влезает,
     * поэтому короткие формы (≤ 5 символов) — 52 px остаётся кеглем героя этой строки.
     */
    private fun wakePhaseText(stage: SleepStats.Stage): String = when (stage) {
        SleepStats.Stage.DEEP -> "ГЛУБ."
        SleepStats.Stage.LIGHT -> "ЛЁГК."
        SleepStats.Stage.AWAKE -> "БОДР."
        SleepStats.Stage.UNKNOWN -> "—"
    }

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

    private companion object {
        const val DEMO_AMBIENT_DELAY_MS = 400L

        /** Зазор между прямоугольником полосы и окружностью обода r = 188. */
        const val ROW_SIDE_PAD_PX = 8f

        /** Запас внутри TextView при ужатии кегля. */
        const val TEXT_SIDE_PAD_PX = 2

        const val LEGEND_GAP_PX = 14
        const val GLYPH_GAP_PX = 2
    }
}
