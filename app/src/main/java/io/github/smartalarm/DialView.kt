package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

/**
 * Кольцо прогресса на весь экран + время в центре (стиль вендорского циферблата).
 *
 * Скилл wear-os-ui: кольцо рисуется с отступом [RoundScreen.SAFE_MARGIN_PX] от края,
 * поэтому оно никогда не обрезается дугой экрана; время стоит выше геометрического
 * центра, чтобы под ним поместились статус и кнопки.
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val ringBg = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        color = Ui.surfaceHi
    }
    private val ringArc = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()
    private val dotPaint = Paint().apply { isAntiAlias = true; color = Ui.accent }
    private val timePaint = Paint().apply {
        isAntiAlias = true
        color = Ui.text
        typeface = Ui.clockTypeface()
        textAlign = Paint.Align.CENTER
    }

    var targetAt: Long = 0L
        set(v) { field = v; invalidate() }
    var armed: Boolean = false
        set(v) { field = v; invalidate() }
    var running: Boolean = false
        set(v) { field = v; invalidate() }
    var timeLabel: String = "07:00"
        set(v) { field = v; invalidate() }

    private fun progress(): Float {
        if (!armed || targetAt <= 0L) return 0f
        val left = (targetAt - System.currentTimeMillis()).coerceAtLeast(1L)
        val span = 12 * 60 * 60 * 1000L
        return (left.toFloat() / span).coerceIn(0.02f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val stroke = (minOf(w, h) * 0.035f).coerceAtLeast(3f)
        val radius = minOf(w, h) / 2f - RoundScreen.SAFE_MARGIN_PX - stroke / 2f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        ringBg.strokeWidth = stroke
        ringArc.strokeWidth = stroke
        canvas.drawCircle(cx, cy, radius, ringBg)

        val p = progress()
        if (p > 0f) {
            ringArc.shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.parseColor("#0A3D35"), Ui.accentDim, Ui.accent,
                    Color.parseColor("#0A3D35"),
                ),
                floatArrayOf(0f, 0.35f, 0.75f, 1f),
            )
            canvas.drawArc(arcRect, -90f, 360f * p, false, ringArc)
            ringArc.shader = null

            val angle = Math.toRadians((-90f + 360f * p).toDouble())
            dotPaint.color = if (running) Ui.accent else Ui.accentDim
            canvas.drawCircle(
                cx + radius * Math.cos(angle).toFloat(),
                cy + radius * Math.sin(angle).toFloat(),
                stroke * 1.1f,
                dotPaint,
            )
        }

        // Время выше центра: под ним живут статус и кнопки
        val timeSize = (minOf(w, h) * 0.205f).coerceAtLeast(28f)
        timePaint.textSize = timeSize
        canvas.drawText(timeLabel, cx, cy - minOf(w, h) * 0.055f, timePaint)
    }
}
