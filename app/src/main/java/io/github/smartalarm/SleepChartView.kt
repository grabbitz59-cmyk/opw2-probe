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
 * График ночи в духе OHealth.
 *
 * Сверху полоса оценочных фаз (бодрствование / лёгкий / глубокий / нет данных),
 * ниже линия пульса с градиентной заливкой и столбики движения, внизу — время начала
 * и конца записи. Всё рисуется внутри «карточки» со скруглением, чтобы вписываться
 * в общий стиль приложения.
 *
 * Фазы оценочные (см. [SleepStats]): настоящих N1/N2/N3/REM на OPW2 не получить.
 */
class SleepChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var night: SleepStats.Night? = null

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

    private val gridPaint = Paint().apply {
        color = Color.argb(40, 159, 179, 184)
        strokeWidth = 1f
    }
    private val hrPaint = Paint().apply {
        color = Ui.accent
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val motionPaint = Paint().apply { color = Ui.surfaceHi; isAntiAlias = true }
    private val alarmPaint = Paint().apply { color = Ui.danger; strokeWidth = 2f }
    private val stagePaint = Paint().apply { isAntiAlias = true }
    private val labelPaint = Paint().apply {
        color = Ui.textTertiary
        textSize = 15f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val timePaint = Paint().apply {
        color = Ui.textTertiary
        textSize = 16f
        isAntiAlias = true
    }

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    fun setNight(n: SleepStats.Night?) {
        night = n
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // карточка
        val radius = dp(16f)
        cardRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint)
        canvas.drawRoundRect(cardRect, radius, radius, cardStroke)

        val n = night
        val pad = dp(10f)
        if (n == null || n.series.size < 2) {
            labelPaint.textSize = dp(11f)
            canvas.drawText("нет данных", w / 2f, h / 2f, labelPaint)
            return
        }

        val stageTop = pad
        val stageBottom = h * 0.20f
        val hrTop = h * 0.28f
        val hrBottom = h * 0.68f
        val motionTop = h * 0.74f
        val timeY = h - dp(6f)
        val plotW = w - pad * 2
        val span = (n.endTs - n.startTs).coerceAtLeast(1L).toFloat()
        val bucketW = (plotW * SleepStats.BUCKET_MS / span).coerceAtLeast(1f)

        // Полоса фаз
        for (i in n.series.indices) {
            val r = n.series[i]
            val x = pad + plotW * (r.ts - n.startTs) / span
            stagePaint.color = Ui.phaseColor(n.stages.getOrNull(i) ?: SleepStats.Stage.UNKNOWN)
            stagePaint.alpha = 235
            canvas.drawRect(x, stageTop, x + bucketW, stageBottom, stagePaint)
        }

        // Сетка пульса
        for (i in 0..2) {
            val y = hrTop + (hrBottom - hrTop) * i / 2f
            canvas.drawLine(pad, y, pad + plotW, y, gridPaint)
        }

        val hrMin = if (n.hrMin > 0) n.hrMin - 5 else 50
        val hrMax = if (n.hrMax > 0) n.hrMax + 5 else 110
        val hrRange = (hrMax - hrMin).coerceAtLeast(1)

        val line = Path()
        val area = Path()
        var started = false
        var lastX = pad
        for (r in n.series) {
            if (r.hr <= 0) {
                started = false
                continue
            }
            val x = pad + plotW * (r.ts - n.startTs) / span
            val y = hrBottom - (hrBottom - hrTop) * (r.hr - hrMin) / hrRange
            if (!started) {
                line.moveTo(x, y)
                area.moveTo(x, hrBottom)
                area.lineTo(x, y)
                started = true
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
            lastX = x
        }
        area.lineTo(lastX, hrBottom)
        area.close()

        fillPaint.shader = LinearGradient(
            0f, hrTop, 0f, hrBottom,
            Color.argb(90, 34, 224, 180), Color.argb(0, 34, 224, 180),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(area, fillPaint)
        fillPaint.shader = null
        canvas.drawPath(line, hrPaint)

        // Движение
        val maxMovesPct = n.series.maxOfOrNull { ArousalMath.movesPct(it.n, it.moves) } ?: 0.0
        for (r in n.series) {
            if (!SleepStats.isMotion(r)) continue
            val x = pad + plotW * (r.ts - n.startTs) / span
            val strength: Float = if (maxMovesPct > 0) {
                (ArousalMath.movesPct(r.n, r.moves) / maxMovesPct).coerceIn(0.2, 1.0).toFloat()
            } else {
                (r.std / 2.0).coerceIn(0.2, 1.0).toFloat()
            }
            val barH = (timeY - dp(4f) - motionTop) * strength
            canvas.drawRoundRect(
                x, timeY - dp(4f) - barH, x + bucketW, timeY - dp(4f),
                bucketW / 2f, bucketW / 2f, motionPaint,
            )
        }

        // Срабатывание будильника
        if (n.alarmFired) {
            n.series.firstOrNull { it.alarmState == "triggered" }?.let { r ->
                val x = pad + plotW * (r.ts - n.startTs) / span
                canvas.drawLine(x, stageTop, x, timeY - dp(2f), alarmPaint)
            }
        }

        // Время начала и конца
        canvas.drawText(timeFmt.format(Instant.ofEpochMilli(n.startTs)), pad, timeY, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(timeFmt.format(Instant.ofEpochMilli(n.endTs)), w - pad, timeY, timePaint)
        timePaint.textAlign = Paint.Align.LEFT
    }
}
