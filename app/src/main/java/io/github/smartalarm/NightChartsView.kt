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
 * Экран «Ночь»: **два отдельних графика в двух карточках** вместо одной общей.
 *
 * Верхняя карточка — фазы сна: полоса фаз на всю ширину и столбики движения под ней.
 * Нижняя карточка — пульс: линия акцентом с заливкой и сеткой, на всю высоту карточки.
 *
 * Так каждый график получил свою высоту (≈100 px вместо 46 px в общей карточке) и читается
 * с руки. Обе карточки — в языке v1: `Ui.surface` + обводка `Ui.stroke`, радиус 32 px.
 * Внутри карточки рисуем на 4 px отступа, чтобы содержимое не прилипало к рамке.
 */
class NightChartsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var night: SleepStats.Night? = null

    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Ui.surface
    }
    private val cardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Ui.stroke
    }
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val motionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Ui.surfaceHi
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 159, 179, 184)
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
    private val alarmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.danger
        strokeWidth = 2f
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.textTertiary
        textSize = CAPTION_PX
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.textTertiary
        textSize = EMPTY_PX
        textAlign = Paint.Align.CENTER
    }

    private val cardRect = RectF()
    private val hrLine = Path()
    private val hrArea = Path()

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault())

    fun setNight(n: SleepStats.Night?) {
        night = n
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Две карточки: верх — фазы, низ — пульс, между ними зазор.
        val cardH = ((h - GAP_Y) / 2f).coerceAtLeast(40f)
        val topCard = RectF(0f, 0f, w, cardH)
        val bottomCard = RectF(0f, h - cardH, w, h)

        drawCard(canvas, topCard)
        drawCard(canvas, bottomCard)

        val n = night
        if (n == null || n.series.size < 2) {
            emptyPaint.textSize = EMPTY_PX
            canvas.drawText("нет данных", w / 2f, h / 2f + EMPTY_PX * 0.35f, emptyPaint)
            return
        }

        val plotL = PAD_X
        val plotR = (w - PAD_X).coerceAtLeast(plotL + 1f)
        val plotW = (plotR - plotL).coerceAtLeast(1f)
        val span = (n.endTs - n.startTs).coerceAtLeast(1L).toFloat()
        val bucketW = (plotW * SleepStats.BUCKET_MS / span).coerceAtLeast(1f)

        // ---------- карточка 1: фазы сна + движение ----------
        val stripTop = topCard.top + PAD_Y
        val stripBottom = stripTop + (cardH * PHASE_FRAC).coerceAtLeast(20f)
        for (i in n.series.indices) {
            val row = n.series[i]
            val x = plotL + plotW * (row.ts - n.startTs) / span
            val xRight = (x + bucketW).coerceAtMost(plotR).coerceAtLeast(x + 0.75f)
            stagePaint.color = Ui.phaseColor(n.stages.getOrNull(i) ?: SleepStats.Stage.UNKNOWN)
            stagePaint.alpha = 240
            canvas.drawRect(x, stripTop, xRight, stripBottom, stagePaint)
        }
        val motionBottom = topCard.bottom - PAD_Y
        val motionTop = stripBottom + MOTION_GAP
        val barBand = (motionBottom - motionTop).coerceAtLeast(1f)
        val maxMoves = n.series.maxOfOrNull { ArousalMath.movesPct(it.n, it.moves) } ?: 0.0
        motionPaint.color = Ui.surfaceHi
        for (row in n.series) {
            if (!SleepStats.isMotion(row)) continue
            val x = plotL + plotW * (row.ts - n.startTs) / span
            val strength = if (maxMoves > 0.0) {
                (ArousalMath.movesPct(row.n, row.moves) / maxMoves).coerceIn(0.2, 1.0).toFloat()
            } else {
                (row.std / 2.0).coerceIn(0.2, 1.0).toFloat()
            }
            val barH = barBand * strength
            canvas.drawRoundRect(
                x, motionBottom - barH, x + bucketW, motionBottom,
                bucketW / 2f, bucketW / 2f, motionPaint,
            )
        }
        captionPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("фазы", plotL, topCard.top + CAPTION_PX, captionPaint)
        captionPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("движение", plotR, topCard.top + CAPTION_PX, captionPaint)

        // ---------- карточка 2: пульс ----------
        val hrTop = bottomCard.top + PAD_Y + CAPTION_PX
        val hrBottom = bottomCard.bottom - PAD_Y
        for (i in 0..2) {
            val y = hrTop + (hrBottom - hrTop) * i / 2f
            canvas.drawLine(plotL, y, plotR, y, gridPaint)
        }
        val hrMin = if (n.hrMin > 0) n.hrMin - HR_PAD else DEFAULT_HR_LO
        val hrMax = if (n.hrMax > 0) n.hrMax + HR_PAD else DEFAULT_HR_HI
        val hrRange = (hrMax - hrMin).coerceAtLeast(1)

        hrLine.rewind()
        hrArea.rewind()
        fillPaint.shader = LinearGradient(
            0f, hrTop, 0f, hrBottom,
            Color.argb(90, 34, 224, 180), Color.argb(0, 34, 224, 180),
            Shader.TileMode.CLAMP,
        )
        var started = false
        var lastX = plotL
        for (row in n.series) {
            if (row.hr <= 0) {
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

        // Отметка срабатывания будильника — на обеих карточках.
        if (n.alarmFired) {
            n.series.firstOrNull { it.alarmState == "triggered" }?.let { row ->
                val x = plotL + plotW * (row.ts - n.startTs) / span
                canvas.drawLine(x, topCard.top + PAD_Y, x, motionBottom, alarmPaint)
                canvas.drawLine(x, hrTop, x, hrBottom, alarmPaint)
            }
        }

        captionPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            "пульс " + if (n.hrMin > 0) "${n.hrMin}–${n.hrMax}" else "н/д",
            plotL, bottomCard.top + CAPTION_PX, captionPaint,
        )
        captionPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            timeFmt.format(Instant.ofEpochMilli(n.startTs)) + "–" +
                timeFmt.format(Instant.ofEpochMilli(n.endTs)),
            plotR, bottomCard.top + CAPTION_PX, captionPaint,
        )
        captionPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawCard(canvas: Canvas, r: RectF) {
        cardRect.set(r)
        canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, cardFill)
        canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, cardEdge)
    }

    private companion object {
        const val CARD_RADIUS = 28f
        const val PAD_X = 16f
        const val PAD_Y = 10f
        const val GAP_Y = 8f
        const val PHASE_FRAC = 0.42f
        const val MOTION_GAP = 8f
        const val CAPTION_PX = 16f
        const val EMPTY_PX = 24f
        const val HR_PAD = 5
        const val DEFAULT_HR_LO = 50
        const val DEFAULT_HR_HI = 110
    }
}
