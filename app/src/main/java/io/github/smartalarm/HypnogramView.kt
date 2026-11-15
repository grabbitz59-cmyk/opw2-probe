package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Гипнограмма экрана 2 «НОЧЬ» (§4.3 `docs/design-v2.md`): гребёнка оценённых фаз сна
 * и линия пульса над волосяной базовой линейкой.
 *
 * Заменяет график v1 (мёртвый код, файл удаляется). Никаких карточек, полос-«плашек» и фазовых цветов v1:
 * по принципу 3 фаза кодируется **высотой и яркостью** одного янтаря —
 * глубокий (`ember`) / лёгкий (`emberDim`) / бодрствование (`phaseAwake`) / нет данных (`phaseNone`).
 *
 * Логический размер — 320 × 122 px (полоса `y 164…286` экрана 466×466, внутри `r = 188`).
 * Реальные `width/height` масштабируются от него, поэтому View можно смотреть и в превью.
 *
 * Layout: полоса пульса считается сверху от самого высокого столбика (`DEEP_H` + зазор),
 * поэтому линия пульса не пересекает гребёнку при любой высоте View.
 *
 * В ambient (`setAmbient(true)`) остаётся только базовая линейка — она единственная часть
 * экрана, которая переживает AOD.
 */
class HypnogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var night: SleepStats.Night? = null
    private var ambient = false

    // Краски — поля класса: в onDraw ни одной аллокации.
    private val rulePaint: Paint = Ui.strokePaint(Ui.rule, RULE_W)
    private val pulsePaint: Paint = Ui.strokePaint(Ui.paper3, PULSE_W).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val emptyPaint: Paint = Ui.textPaint(Ui.SZ_TELEMETRY * 1.25f, Ui.paper3)

    private val deepPaint: Paint = Ui.fillPaint(Ui.phaseColor(SleepStats.Stage.DEEP))
    private val lightPaint: Paint = Ui.fillPaint(Ui.phaseColor(SleepStats.Stage.LIGHT))
    private val awakePaint: Paint = Ui.fillPaint(Ui.phaseColor(SleepStats.Stage.AWAKE))
    private val nonePaint: Paint = Ui.fillPaint(Ui.phaseColor(SleepStats.Stage.UNKNOWN))

    /** Переиспользуемая ломаная пульса: `rewind()` вместо новой Path на каждый кадр. */
    private val pulsePath = Path()

    /** Данные ночи; `null` — пустое состояние «НЕТ ДАННЫХ». */
    fun setNight(n: SleepStats.Night?) {
        night = n
        invalidate()
    }

    /** AOD: рисовать только базовую линейку. */
    fun setAmbient(on: Boolean) {
        if (ambient == on) return
        ambient = on
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Всё спроектировано под 320 × 122 — от него и масштабируем (превью, другие размеры).
        val scale = minOf(w / LOGICAL_W, h / LOGICAL_H)
        val baseY = h - RULE_W / 2f
        val n = night
        val empty = n == null || n.stages.isEmpty()

        if (!ambient && !empty && n != null) {
            // Пульс живёт над самым высоким столбиком с зазором COMB_GAP_PX — полосы не пересекаются.
            val pulseBandH = (baseY - DEEP_H * scale - COMB_GAP_PX).coerceAtLeast(0f)
            drawComb(canvas, n, w, baseY, scale)
            drawPulse(canvas, n, w, pulseBandH)
        }

        // Базовая линейка — единственное, что остаётся в AOD.
        canvas.drawLine(0f, baseY, w, baseY, rulePaint)

        if (!ambient && empty) {
            canvas.drawText(EMPTY_TEXT, w / 2f, h / 2f + emptyPaint.textSize * 0.35f, emptyPaint)
        }
    }

    /** Гребёнка фаз: шаг ≈ 4 px, столбик 2 px, высота от базовой линии вверх. */
    private fun drawComb(
        canvas: Canvas,
        night: SleepStats.Night,
        w: Float,
        baseY: Float,
        scale: Float,
    ) {
        val stages = night.stages
        val step = STEP_PX * scale
        if (step <= 0f) return

        // Сколько бакетов реально влезает; лишние прореживаем равномерно по всей ночи.
        val capacity = (w / step).toInt()
        if (capacity <= 0) return
        val slots = minOf(stages.size, capacity)
        if (slots <= 0) return

        // Раскладываем столбики по всей ширине: при полной ночи шаг ≈ 4 px (ровно по §4.3),
        // при коротком логе — равномерно без «дыры справа» и без обрезки хвоста.
        val slotStep = w / slots.toFloat()
        val barW = minOf(BAR_W_PX * scale, slotStep * 0.9f)
        for (i in 0 until slots) {
            val idx = if (stages.size <= capacity) i else i * stages.size / capacity
            if (idx >= stages.size) continue
            val stage = stages[idx]
            val x = i * slotStep
            val top = baseY - stageHeight(stage) * scale
            canvas.drawRect(x, top, x + barW, baseY, stagePaint(stage))
        }
    }

    /** Пульс: ломаная `night.series`, разрывы при `hr <= 0`, полоса `y 0…bandH`. */
    private fun drawPulse(canvas: Canvas, night: SleepStats.Night, w: Float, bandH: Float) {
        val series = night.series
        if (series.size < 2 || bandH <= 0f) return

        val hasHr = night.hrMin > 0 && night.hrMax > 0
        val lo = if (hasHr) night.hrMin - HR_PAD else DEFAULT_HR_LO
        val hi = if (hasHr) night.hrMax + HR_PAD else DEFAULT_HR_HI
        // Один и тот же пульс всю ночь — полоса выродилась бы в деление на ноль.
        val span = (hi - lo).toFloat()
        if (span <= 0f) return

        val stepX = w / (series.size - 1).toFloat()

        pulsePath.rewind()
        var started = false
        for (i in series.indices) {
            val hr = series[i].hr
            if (hr <= 0) {
                // Разрыв линии: следующая точка начнёт новый отрезок.
                started = false
                continue
            }
            val t = ((hr - lo).toFloat() / span).coerceIn(0f, 1f)
            val x = i * stepX
            val y = (1f - t) * bandH
            if (started) pulsePath.lineTo(x, y) else pulsePath.moveTo(x, y)
            started = true
        }
        canvas.drawPath(pulsePath, pulsePaint)
    }

    /** Высота столбика фазы, px (логические, до масштабирования). */
    private fun stageHeight(stage: SleepStats.Stage): Float = when (stage) {
        SleepStats.Stage.DEEP -> DEEP_H
        SleepStats.Stage.LIGHT -> 48f
        SleepStats.Stage.AWAKE -> 10f
        SleepStats.Stage.UNKNOWN -> 4f
    }

    /** Цвет столбика фазы — только из `Ui.phaseColor`, никаких hex в этом файле. */
    private fun stagePaint(stage: SleepStats.Stage): Paint = when (stage) {
        SleepStats.Stage.DEEP -> deepPaint
        SleepStats.Stage.LIGHT -> lightPaint
        SleepStats.Stage.AWAKE -> awakePaint
        SleepStats.Stage.UNKNOWN -> nonePaint
    }

    private companion object {
        /** Логический размер из §4.3: полоса `y 164…286` на экране 466×466. */
        const val LOGICAL_W = 320f
        const val LOGICAL_H = 122f

        /** Гребёнка: шаг 4 px, столбик 2 px, самый высокий столбик (DEEP) — 76 px. */
        const val STEP_PX = 4f
        const val BAR_W_PX = 2f
        const val DEEP_H = 76f

        /** Зазор между линией пульса и верхом самого высокого столбика. */
        const val COMB_GAP_PX = 1f

        /** Запас по пульсу сверху и снизу, когда данные есть. */
        const val HR_PAD = 5
        const val DEFAULT_HR_LO = 50
        const val DEFAULT_HR_HI = 110

        const val RULE_W = 1f
        const val PULSE_W = 1.5f

        const val EMPTY_TEXT = "НЕТ ДАННЫХ"
    }
}
