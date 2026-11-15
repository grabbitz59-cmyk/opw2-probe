package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Карточка графика ночи в языке v1: полоса оценённых фаз, линия пульса акцентом
 * с градиентной заливкой, столбики движения, отметка срабатывания будильника,
 * время начала и конца записи. Всё — внутри карточки со скруглением и обводкой
 * `Ui.surface` / `Ui.stroke`, как в остальном приложении.
 *
 * Геометрия зафиксирована под полосу экрана **y 122…214, ширина 336 px** (экран 2).
 * Логическая сетка `LOGICAL_W × LOGICAL_H` = 336 × 92 px; `onDraw` масштабирует её от
 * реальных `width/height`, поэтому View можно смотреть и в превью, и в другой полосе.
 *
 * Внутри карточки (логические px, 1 dp = 2 px на OPW2):
 *   • полоса фаз         y  10…28  (18 px)
 *   • полоса пульса      y  34…56  (22 px, 3 линии сетки)
 *   • столбики движения  y  60…68  (8 px)
 *   • подписи времени    базовая линия 84, кегль 16 px
 * Ни одна часть графика не выходит за прямоугольник карточки — поэтому внешний
 * прямоугольник (а значит и дуга круга) обрезать её не может.
 *
 * Ambient (AOD): `setAmbient(true)` гасит сетку и столбики, оставляя притушённые
 * полосу фаз и линию пульса на чёрной карточке — без анимаций (их здесь и нет),
 * чтобы AMOLED не выжигал экран (R9).
 *
 * Фазы оценочные (см. [SleepStats]): настоящих N1/N2/N3/REM на OPW2 не получить.
 */
class SleepChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var night: SleepStats.Night? = null
    private var ambient = false

    // Краски — поля класса: в onDraw не создаём новых объектов, только rewind у Path.
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Ui.surface
    }
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Ui.stroke
    }
    private val cardRect = RectF()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 159, 179, 184)
        strokeWidth = 1f
    }
    private val hrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.accent
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val motionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.surfaceHi
        style = Paint.Style.FILL
    }
    private val alarmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.danger
        strokeWidth = 2f
    }
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.textTertiary
        textSize = EMPTY_PX
        textAlign = Paint.Align.CENTER
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.textTertiary
        textSize = TIME_PX
        textAlign = Paint.Align.LEFT
    }

    /** Переиспользуемые ломаные: в onDraw только `rewind()`, без аллокаций. */
    private val hrLine = Path()
    private val hrArea = Path()

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    /** Данные ночи; `null` — состояние «нет данных» внутри карточки. */
    fun setNight(n: SleepStats.Night?) {
        night = n
        invalidate()
    }

    /**
     * Ambient (AOD): карточка становится чёрной, сетка и столбики движения исчезают,
     * полоса фаз и линия пульса остаются притушёнными. Анимаций в этом View нет,
     * поэтому замораживать в AOD нечего.
     */
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

        // Масштаб от логической сетки 336 × 92 px: на устройстве он равен 1.
        val sx = w / LOGICAL_W
        val sy = h / LOGICAL_H

        // ---------- карточка (v1: фон surface + обводка stroke, радиус 16 dp) ----------
        val radius = CARD_RADIUS * minOf(sx, sy)
        cardPaint.color = if (ambient) Color.BLACK else Ui.surface
        cardStroke.strokeWidth = if (ambient) 1f else 2f
        cardStroke.color = Ui.stroke
        cardStroke.alpha = if (ambient) 60 else 255
        cardRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint)
        canvas.drawRoundRect(cardRect, radius, radius, cardStroke)

        // Раскладка считается от фактической высоты, а не от логической сетки: карточка
        // выросла с 92 до 168 px, и «растягивать» всё пропорционально нельзя — подписи
        // времени от такого масштаба становились гигантскими. Теперь полосы пропорциональны,
        // а кегли фиксированные.
        val plotL = PAD_X
        val plotR = (w - PAD_X).coerceAtLeast(plotL + 1f)
        val plotW = (plotR - plotL).coerceAtLeast(1f)
        // Внизу — место под две подписи времени.
        val plotBottom = (h - PAD_Y - TIME_PX).coerceAtLeast(PAD_Y + 24f)
        val plotH = (plotBottom - PAD_Y).coerceAtLeast(24f)

        val stripTop = PAD_Y
        val stripH = (plotH * PHASE_FRAC).coerceAtLeast(22f)
        val stripBottom = stripTop + stripH
        val hrTop = stripBottom + GAP
        val hrBottom = (hrTop + (plotBottom - hrTop) * HR_FRAC).coerceAtLeast(hrTop + 18f)
        val motionTop = hrBottom + GAP
        val motionBottom = plotBottom
        val timeY = h - TIME_PX * 0.35f

        val n = night
        if (n == null || n.series.size < 2) {
            // «Нет данных» — не пустота: v1 рисовал здесь пустую карточку.
            emptyPaint.textSize = EMPTY_PX
            emptyPaint.color = Ui.textTertiary
            emptyPaint.alpha = if (ambient) AMBIENT_ALPHA else 255
            canvas.drawText(
                "нет данных",
                w / 2f,
                h / 2f + emptyPaint.textSize * 0.35f,
                emptyPaint,
            )
            return
        }

        val span = (n.endTs - n.startTs).coerceAtLeast(1L).toFloat()
        val bucketW = (plotW * SleepStats.BUCKET_MS / span).coerceAtLeast(1f)
        val tone = if (ambient) AMBIENT_ALPHA else 255

        // ---------- 1. полоса фаз: цвета строго из Ui.phaseColor ----------
        // Порядок важен: setColor() сбрасывает альфу, поэтому alpha ставим ПОСЛЕ цвета.
        for (i in n.series.indices) {
            val row = n.series[i]
            val x = plotL + plotW * (row.ts - n.startTs) / span
            // Последний бакет не должен вылезать за правый паддинг карточки.
            val xRight = (x + bucketW).coerceAtMost(plotR).coerceAtLeast(x + 0.75f)
            stagePaint.color = Ui.phaseColor(
                n.stages.getOrNull(i) ?: SleepStats.Stage.UNKNOWN,
            )
            stagePaint.alpha = if (ambient) AMBIENT_ALPHA else PHASE_ALPHA
            canvas.drawRect(x, stripTop, xRight, stripBottom, stagePaint)
        }

        // ---------- 2. сетка пульса: в ambient её нет (лишний свет и выжигание) ----------
        if (!ambient) {
            for (i in 0..2) {
                val y = hrTop + (hrBottom - hrTop) * i / 2f
                canvas.drawLine(plotL, y, plotR, y, gridPaint)
            }
        }

        // ---------- 3. пульс акцентом + градиентная заливка под линией ----------
        val hrMin = if (n.hrMin > 0) n.hrMin - HR_PAD else DEFAULT_HR_LO
        val hrMax = if (n.hrMax > 0) n.hrMax + HR_PAD else DEFAULT_HR_HI
        val hrRange = (hrMax - hrMin).coerceAtLeast(1)

        hrLine.rewind()
        hrArea.rewind()
        fillPaint.shader = LinearGradient(
            0f, hrTop, 0f, hrBottom,
            Color.argb(90 * tone / 255, 34, 224, 180),
            Color.argb(0, 34, 224, 180),
            Shader.TileMode.CLAMP,
        )
        hrPaint.alpha = tone

        var started = false
        var lastX = plotL
        for (row in n.series) {
            if (row.hr <= 0) {
                // Разрыв в данных: дорисовываем накопленный сегмент и начинаем новый.
                if (started) {
                    hrArea.lineTo(lastX, hrBottom)
                    hrArea.close()
                    canvas.drawPath(hrArea, fillPaint)
                    canvas.drawPath(hrLine, hrPaint)
                    hrLine.rewind()
                    hrArea.rewind()
                    started = false
                }
                continue
            }
            val x = plotL + plotW * (row.ts - n.startTs) / span
            val y = hrBottom - (hrBottom - hrTop) * (row.hr - hrMin) / hrRange
            if (!started) {
                hrLine.moveTo(x, y)
                hrArea.moveTo(x, hrBottom)
                hrArea.lineTo(x, y)
                started = true
            } else {
                hrLine.lineTo(x, y)
                hrArea.lineTo(x, y)
            }
            lastX = x
        }
        if (started) {
            hrArea.lineTo(lastX, hrBottom)
            hrArea.close()
            canvas.drawPath(hrArea, fillPaint)
            canvas.drawPath(hrLine, hrPaint)
        }
        fillPaint.shader = null

        // ---------- 4. столбики движения (Ui.surfaceHi), в ambient не рисуем ----------
        if (!ambient) {
            val maxMoves = n.series.maxOfOrNull { ArousalMath.movesPct(it.n, it.moves) } ?: 0.0
            val barBand = (motionBottom - motionTop).coerceAtLeast(1f)
            motionPaint.color = Ui.surfaceHi
            for (row in n.series) {
                if (!SleepStats.isMotion(row)) continue
                val x = plotL + plotW * (row.ts - n.startTs) / span
                val strength: Float = if (maxMoves > 0.0) {
                    (ArousalMath.movesPct(row.n, row.moves) / maxMoves).coerceIn(0.2, 1.0).toFloat()
                } else {
                    (row.std / 2.0).coerceIn(0.2, 1.0).toFloat()
                }
                val barH = barBand * strength
                canvas.drawRoundRect(
                    x,
                    motionBottom - barH,
                    x + bucketW,
                    motionBottom,
                    bucketW / 2f,
                    bucketW / 2f,
                    motionPaint,
                )
            }
        }

        // ---------- 5. отметка срабатывания будильника ----------
        if (n.alarmFired) {
            n.series.firstOrNull { it.alarmState == "triggered" }?.let { row ->
                val x = plotL + plotW * (row.ts - n.startTs) / span
                alarmPaint.alpha = tone
                canvas.drawLine(x, stripTop, x, motionBottom, alarmPaint)
            }
        }

        // ---------- 6. время начала и конца: единственные подписи в карточке ----------
        timePaint.textSize = TIME_PX
        timePaint.color = Ui.textTertiary
        timePaint.alpha = tone
        timePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(timeFmt.format(Instant.ofEpochMilli(n.startTs)), plotL, timeY, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(timeFmt.format(Instant.ofEpochMilli(n.endTs)), plotR, timeY, timePaint)
        timePaint.textAlign = Paint.Align.LEFT
    }

    private companion object {
        /** Логическая сетка карточки: полоса экрана 2 — y 122…214, ширина 336 px. */
        const val LOGICAL_W = 340f
        const val LOGICAL_H = 168f

        /** Радиус карточки 16 dp = 32 px. */
        const val CARD_RADIUS = 32f

        /** Внутренние отступы и полосы, логические px. */
        const val PAD_X = 18f
        const val PAD_Y = 12f
        const val PHASE_FRAC = 0.20f
        const val HR_FRAC = 0.55f
        const val GAP = 8f
        const val STRIP_TOP = 10f
        const val STRIP_H = 18f
        const val HR_TOP = 34f
        const val HR_BOTTOM = 56f
        const val MOTION_TOP = 60f
        const val MOTION_BOTTOM = 68f
        const val TIME_BASELINE = 84f

        /** Кегли подписей, px. */
        const val TIME_PX = 18f
        const val EMPTY_PX = 24f

        /** Альфа полосы фаз в норме и всё притушенное в AOD. */
        const val PHASE_ALPHA = 235
        const val AMBIENT_ALPHA = 90

        /** Запас по пульсу сверху/снизу и значения по умолчанию, если HR нет. */
        const val HR_PAD = 5
        const val DEFAULT_HR_LO = 50
        const val DEFAULT_HR_HI = 110
    }
}
