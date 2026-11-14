package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * График ночи в духе OHealth: сверху полоса фаз (бодрствование / лёгкий / глубокий),
 * ниже линия пульса с градиентной заливкой и столбики движения, справа-снизу — маркер
 * срабатывания будильника.
 *
 * Фазы оценочные (см. [SleepStats]): настоящих N1/N2/N3/REM на OPW2 не получить.
 */
class SleepChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var night: SleepStats.Night? = null

    private val gridPaint = Paint().apply { color = Color.parseColor("#182226"); strokeWidth = 1f }
    private val hrPaint = Paint().apply {
        color = Ui.accent
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val motionPaint = Paint().apply { color = Color.parseColor("#2E3C41"); isAntiAlias = true }
    private val alarmPaint = Paint().apply { color = Color.parseColor("#FF6B6B"); strokeWidth = 1.5f }
    private val stagePaint = Paint().apply { isAntiAlias = true }
    private val labelPaint = Paint().apply {
        color = Ui.textTertiary
        textSize = 14f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    fun setNight(n: SleepStats.Night?) {
        night = n
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val n = night
        if (n == null || n.series.size < 2) {
            canvas.drawText("нет данных", w / 2f, h / 2f, labelPaint)
            return
        }

        val pad = 4f
        val stageTop = pad
        val stageBottom = h * 0.24f
        val hrTop = h * 0.32f
        val hrBottom = h * 0.74f
        val motionTop = h * 0.80f
        val plotW = w - pad * 2
        val span = (n.endTs - n.startTs).coerceAtLeast(1L).toFloat()
        val bucketW = (plotW * SleepStats.BUCKET_MS / span).coerceAtLeast(0.8f)

        // Полоса фаз
        for (i in n.series.indices) {
            val r = n.series[i]
            val x = pad + plotW * (r.ts - n.startTs) / span
            stagePaint.color = stageColor(n.stages.getOrNull(i) ?: SleepStats.Stage.UNKNOWN)
            canvas.drawRect(x, stageTop, x + bucketW, stageBottom, stagePaint)
        }

        // Сетка по пульсу
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
        var lastX = 0f
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
            Color.parseColor("#5500D1A9"), Color.parseColor("#0000D1A9"),
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
                (ArousalMath.movesPct(r.n, r.moves) / maxMovesPct).coerceIn(0.15, 1.0).toFloat()
            } else {
                (r.std / 2.0).coerceIn(0.15, 1.0).toFloat()
            }
            val barH = (h - pad - motionTop) * strength
            canvas.drawRect(x, h - pad - barH, x + bucketW, h - pad, motionPaint)
        }

        if (n.alarmFired) {
            n.series.firstOrNull { it.alarmState == "triggered" }?.let { r ->
                val x = pad + plotW * (r.ts - n.startTs) / span
                canvas.drawLine(x, pad, x, h - pad, alarmPaint)
            }
        }
    }

    private fun stageColor(stage: SleepStats.Stage): Int = when (stage) {
        SleepStats.Stage.AWAKE -> Color.parseColor("#FFB74D")
        SleepStats.Stage.LIGHT -> Color.parseColor("#4FC3F7")
        SleepStats.Stage.DEEP -> Color.parseColor("#7E57C2")
        SleepStats.Stage.UNKNOWN -> Color.parseColor("#37474F")
    }
}
