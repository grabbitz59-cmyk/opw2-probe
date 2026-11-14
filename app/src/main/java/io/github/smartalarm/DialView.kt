package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/**
 * Круглый «циферблат» главного экрана: кольцо прогресса до подъёма, время в центре,
 * под ним — сколько осталось и статус.
 *
 * Рисуется вручную, чтобы выглядеть как вендорский экран часов: круг сам задаёт
 * композицию, не нужно бороться с прямоугольной вёрсткой на круглом дисплее.
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
    private val dotPaint = Paint().apply { isAntiAlias = true; color = Color.WHITE }
    private val timePaint = Paint().apply {
        isAntiAlias = true
        color = Ui.text
        typeface = Ui.clockTypeface()
        textAlign = Paint.Align.CENTER
    }
    private val subtitlePaint = Paint().apply {
        isAntiAlias = true
        color = Ui.accent
        typeface = Ui.labelTypeface()
        textAlign = Paint.Align.CENTER
    }
    private val statusPaint = Paint().apply {
        isAntiAlias = true
        color = Ui.textTertiary
        typeface = Ui.labelTypeface()
        textAlign = Paint.Align.CENTER
    }
    private val bounds = Rect()

    var targetAt: Long = 0L
        set(v) { field = v; invalidate() }
    var armed: Boolean = false
        set(v) { field = v; invalidate() }
    var running: Boolean = false
        set(v) { field = v; invalidate() }
    var timeLabel: String = "07:00"
        set(v) { field = v; invalidate() }
    var subtitle: String = ""
        set(v) { field = v; invalidate() }
    var status: String = ""
        set(v) { field = v; invalidate() }

    /** Доля суток до цели: используется для длины дуги. */
    private fun progress(): Float {
        if (!armed || targetAt <= 0L) return 0f
        val now = System.currentTimeMillis()
        val left = (targetAt - now).coerceAtLeast(1L)
        val span = 12 * 60 * 60 * 1000L // 12 часов — «полный круг»
        return (left.toFloat() / span).coerceIn(0.02f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val stroke = (minOf(w, h) * 0.055f).coerceAtLeast(4f)
        val radius = minOf(w, h) / 2f - stroke / 2f - 2f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        ringBg.strokeWidth = stroke
        ringArc.strokeWidth = stroke
        canvas.drawCircle(cx, cy, radius, ringBg)

        val p = progress()
        if (p > 0f) {
            // Градиент по кольцу: от приглушённого к яркому акценту
            ringArc.shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.parseColor("#0A3D35"), Ui.accentDim, Ui.accent, Color.parseColor("#0A3D35"),
                ),
                floatArrayOf(0f, 0.35f, 0.75f, 1f),
            )
            ringArc.color = Ui.accent
            canvas.drawArc(arcRect, -90f, 360f * p, false, ringArc)
            ringArc.shader = null

            // Точка на конце дуги
            val angle = Math.toRadians((-90f + 360f * p).toDouble())
            val dx = cx + radius * Math.cos(angle).toFloat()
            val dy = cy + radius * Math.sin(angle).toFloat()
            dotPaint.color = if (running) Ui.accent else Ui.accentDim
            canvas.drawCircle(dx, dy, stroke * 0.85f, dotPaint)
        }

        // Время в центре
        val timeSize = (minOf(w, h) * 0.30f).coerceAtLeast(18f)
        timePaint.textSize = timeSize
        timePaint.typeface = if (armed) Ui.clockTypeface() else Ui.clockTypeface()
        canvas.drawText(timeLabel, cx, cy + timeSize * 0.16f, timePaint)

        subtitlePaint.textSize = (minOf(w, h) * 0.085f).coerceAtLeast(8f)
        subtitlePaint.color = if (armed) Ui.accent else Ui.textTertiary
        canvas.drawText(subtitle, cx, cy + timeSize * 0.72f, subtitlePaint)

        statusPaint.textSize = (minOf(w, h) * 0.070f).coerceAtLeast(7f)
        canvas.drawText(status, cx, cy + timeSize * 0.72f + statusPaint.textSize * 1.6f, statusPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = minOf(measuredWidth, measuredHeight)
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec),
        )
    }

    /** Локализованный HH:MM из epoch. */
    fun clockOf(epochMs: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        return String.format(
            Locale.US, "%02d:%02d",
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
        )
    }
}
