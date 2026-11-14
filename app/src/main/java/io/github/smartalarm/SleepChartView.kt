package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * График ночи: пульс линией, движение столбиками, сон — подсветкой фона.
 *
 * Рисуется вручную (Canvas), без сторонних библиотек: на часах 466×466 это дешевле
 * и предсказуемее, чем тянуть MPAndroidChart в APK.
 */
class SleepChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var night: SleepStats.Night? = null

    private val bgPaint = Paint().apply { color = Color.parseColor("#11181B") }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1E2A2E")
        strokeWidth = 1f
    }
    private val hrPaint = Paint().apply {
        color = Color.parseColor("#80CBC4")
        strokeWidth = 2.5f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }
    private val sleepPaint = Paint().apply { color = Color.parseColor("#127A6E50") }
    private val motionPaint = Paint().apply {
        color = Color.parseColor("#4B5A60")
        isAntiAlias = true
    }
    private val alarmPaint = Paint().apply {
        color = Color.parseColor("#EF9A9A")
        strokeWidth = 2f
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#78909C")
        textSize = 18f
        isAntiAlias = true
    }

    fun setNight(n: SleepStats.Night?) {
        night = n
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val n = night
        if (n == null || n.series.size < 2) {
            canvas.drawText("нет данных", w / 2 - 45f, h / 2, labelPaint)
            return
        }

        val pad = 6f
        val hrBottom = h * 0.66f          // зона пульса
        val motionTop = h * 0.72f         // зона движения
        val plotW = w - pad * 2

        // сетка
        for (i in 0..2) {
            val y = pad + (hrBottom - pad) * i / 2f
            canvas.drawLine(pad, y, w - pad, y, gridPaint)
        }

        // подсветка сна
        val span = (n.endTs - n.startTs).coerceAtLeast(1L).toFloat()
        for (r in n.series) {
            if (!r.asleep) continue
            val x = pad + plotW * (r.ts - n.startTs) / span
            canvas.drawRect(x, pad, x + plotW / n.series.size.coerceAtLeast(1), hrBottom, sleepPaint)
        }

        // пульс
        val hrMin = if (n.hrMin > 0) n.hrMin - 5 else 50
        val hrMax = if (n.hrMax > 0) n.hrMax + 5 else 110
        val hrRange = (hrMax - hrMin).coerceAtLeast(1)
        val path = Path()
        var started = false
        for (r in n.series) {
            if (r.hr <= 0) {
                started = false
                continue
            }
            val x = pad + plotW * (r.ts - n.startTs) / span
            val y = hrBottom - (hrBottom - pad) * (r.hr - hrMin) / hrRange
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, hrPaint)

        // движение
        val maxMovesPct = n.series.maxOfOrNull { ArousalMath.movesPct(it.n, it.moves) } ?: 0.0
        val barW = (plotW / n.series.size).coerceAtLeast(1.2f)
        for (r in n.series) {
            if (!SleepStats.isMotion(r)) continue
            val x = pad + plotW * (r.ts - n.startTs) / span
            val strength: Float = if (maxMovesPct > 0) {
                (ArousalMath.movesPct(r.n, r.moves) / maxMovesPct).coerceIn(0.15, 1.0).toFloat()
            } else {
                (r.std / 2.0).coerceIn(0.15, 1.0).toFloat()
            }
            val barH = (h - pad - motionTop) * strength
            canvas.drawRect(x, h - pad - barH, x + barW, h - pad, motionPaint)
        }

        // срабатывание будильника
        if (n.alarmFired) {
            val firstAlarm = n.series.firstOrNull { it.alarmState == "triggered" }
            if (firstAlarm != null) {
                val x = pad + plotW * (firstAlarm.ts - n.startTs) / span
                canvas.drawLine(x, pad, x, h - pad, alarmPaint)
            }
        }
    }
}
