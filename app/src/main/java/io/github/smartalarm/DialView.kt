package io.github.smartalarm

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Кольцо прогресса на весь экран + время в центре.
 *
 * Поддерживает два состояния по скиллу wear-os-ui:
 *  - обычное: полное кольцо с градиентом, точка на конце, пульсация при записи;
 *  - **ambient (R9)**: только приглушённые кольцо и время, без градиента и пульсации,
 *    чтобы не выжигать AMOLED и не тратить батарею на AOD.
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
        set(v) {
            field = v
            setPulse(v)
            invalidate()
        }
    var timeLabel: String = "07:00"
        set(v) { field = v; invalidate() }

    /** Ambient/AOD: приглушённая отрисовка без анимаций. */
    private var dimmed = false

    /** Анимация «наполнения» кольца при взведении. */
    private var fillFraction = 1f
    private var animator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var pulse = 0f

    fun setDimmed(on: Boolean) {
        dimmed = on
        if (on) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulse = 0f
        } else {
            setPulse(running)
        }
        invalidate()
    }

    /** Запускает анимацию кольца от нуля до текущего прогресса. */
    fun animateRing() {
        if (dimmed) return
        animator?.cancel()
        fillFraction = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fillFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun setPulse(on: Boolean) {
        if (!on || dimmed) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulse = 0f
            return
        }
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

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
        val base = minOf(w, h)
        val stroke = (base * if (dimmed) 0.028f else 0.035f).coerceAtLeast(3f)
        val radius = base / 2f - RoundScreen.MARGIN_PX - stroke / 2f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        ringBg.strokeWidth = stroke
        ringBg.color = if (dimmed) Color.parseColor("#141C1F") else Ui.surfaceHi
        ringArc.strokeWidth = stroke
        canvas.drawCircle(cx, cy, radius, ringBg)

        val p = progress() * fillFraction
        if (p > 0f) {
            if (dimmed) {
                ringArc.color = Ui.accentDim
                canvas.drawArc(arcRect, -90f, 360f * p, false, ringArc)
            } else {
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
                    stroke * (0.8f + 0.9f * pulse),
                    dotPaint,
                )
            }
        }

        val timeSize = (base * if (dimmed) 0.185f else 0.205f).coerceAtLeast(26f)
        timePaint.textSize = timeSize
        timePaint.color = if (dimmed) Ui.textSecondary else Ui.text
        canvas.drawText(timeLabel, cx, cy - base * 0.055f, timePaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
