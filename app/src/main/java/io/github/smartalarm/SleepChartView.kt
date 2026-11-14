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
 * График ночи в стиле вендорского приложения: акцентная линия пульса с градиентной
 * заливкой под ней, приглушённые столбики движения, подсветка сна и маркер срабатывания.
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
    private val sleepPaint = Paint().apply { color = Color.parseColor("#1400D1A9") }
    private val motionPaint = Paint().apply { color = Color.parseColor("#2E3C41"); isAntiAlias = true }
    private val alarmPaint = Paint().apply {
        color = Color.parseColor("#FF6B6B")
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint().apply {
        color = Ui.textTertiary
        textSize = 16f
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

        val pad = 6f
        val hrBottom = h * 0.70f
        val motionTop = h * 0.76f
        val plotW = w - pad * 2
        val span = (n.endTs - n.startTs).coerceAtLeast(1L).toFloat()

        for (i in 0..2) {
            val y = pad + (hrBottom - pad) * i / 2f
            canvas.drawLine(pad, y, pad + plotW, y, gridPaint)
        }

        // подсветка сна
        val bucketW = (plotW * 30_000f / span).coerceAtLeast(1f)
        for (r in n.series) {
            if (!r.asleep) continue
            val x = pad + plotW * (r.ts - n.startTs) / span
            canvas.drawRect(x, pad, x + bucketW, hrBottom, sleepPaint)
        }

        val hrMin = if (n.hrMin > 0) n.hrMin - 5 else 50
        val hrMax = if (n.hrMax > 0) n.hrMax + 5 else 110
        val hrRange = (hrMax - hrMin).coerceAtLeast(1)

        val line = Path()
        val area = Path()
        var started = false
        var firstX = 0f
        var lastX = 0f
        for (r in n.series) {
            if (r.hr <= 0) {
                started = false
                continue
            }
            val x = pad + plotW * (r.ts - n.startTs) / span
            val y = hrBottom - (hrBottom - pad) * (r.hr - hrMin) / hrRange
            if (!started) {
                line.moveTo(x, y)
                area.moveTo(x, hrBottom)
                area.lineTo(x, y)
                firstX = x
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
            0f, pad, 0f, hrBottom,
            Color.parseColor("#5500D1A9"), Color.parseColor("#0000D1A9"),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(area, fillPaint)
        fillPaint.shader = null
        canvas.drawPath(line, hrPaint)

        // движение
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

        // момент срабатывания
        if (n.alarmFired) {
            n.series.firstOrNull { it.alarmState == "triggered" }?.let { r ->
                val x = pad + plotW * (r.ts - n.startTs) / span
                canvas.drawLine(x, pad, x, h - pad, alarmPaint)
            }
        }
    }
}
