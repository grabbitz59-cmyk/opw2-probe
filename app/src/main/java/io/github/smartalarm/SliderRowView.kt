package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * Горизонтальный слайдер для выбора часа/минуты.
 *
 * Рисуется вручную: на круглом экране важны крупная тач-зона (высота строки 34 dp)
 * и попадание в среднюю полосу круга по ширине. Тянуть можно пальцем или ротари
 * (события скролла приходят как MotionEvent).
 */
class SliderRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var label: String = ""
        set(v) { field = v; invalidate() }
    var minValue: Int = 0
    var maxValue: Int = 23
    var step: Int = 1
    var value: Int = 0
        set(v) {
            field = v.coerceIn(minValue, maxValue)
            invalidate()
        }

    var onChange: ((Int) -> Unit)? = null

    private val trackRect = RectF()
    private val trackPaint = Paint().apply { isAntiAlias = true; color = Ui.surfaceHi }
    private val fillPaint = Paint().apply { isAntiAlias = true; color = Ui.accent }
    private val knobPaint = Paint().apply { isAntiAlias = true; color = Ui.text }
    private val labelPaint = Paint().apply {
        isAntiAlias = true
        color = Ui.textSecondary
        typeface = Ui.labelTypeface()
    }
    private val valuePaint = Paint().apply {
        isAntiAlias = true
        color = Ui.text
        typeface = Ui.labelTypeface()
        textAlign = Paint.Align.RIGHT
    }

    private val padH = 6f
    private val knobR = 14f

    private var downX = 0f
    private var downY = 0f
    private var startValue = 0
    private var dragging = false

    /** Полный диапазон значений за столько пикселей вертикального хода. */
    private val verticalSpanPx = 130f

    private fun fraction(): Float {
        val range = (maxValue - minValue).coerceAtLeast(1)
        return (value - minValue).toFloat() / range
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        labelPaint.textSize = h * 0.30f
        valuePaint.textSize = h * 0.34f
        val base = h * 0.36f
        canvas.drawText(label, padH, base, labelPaint)
        canvas.drawText(labelFor(value), w - padH, base, valuePaint)

        val trackH = h * 0.16f
        val cy = h * 0.74f
        val left = padH + knobR
        val right = w - padH - knobR
        trackRect.set(left, cy - trackH / 2f, right, cy + trackH / 2f)
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)

        val x = left + (right - left) * fraction()
        trackRect.set(left, cy - trackH / 2f, x, cy + trackH / 2f)
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, fillPaint)
        canvas.drawCircle(x, cy, knobR, knobPaint)
    }

    private fun labelFor(v: Int): String = String.format(java.util.Locale.US, "%02d", v)

    /**
     * Управление без горизонтального свайпа: он на Wear OS перехватывается системным
     * жестом «назад» и закрывает экран. Поэтому:
     *  - тап по шкале — сразу нужное значение (позиция X);
     *  - вертикальное движение пальцем — точная подстройка (вверх больше, вниз меньше).
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startValue = value
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = downY - event.y
                val dx = kotlin.math.abs(event.x - downX)
                if (!dragging && kotlin.math.abs(dy) > 12f && kotlin.math.abs(dy) > dx) {
                    dragging = true
                }
                if (dragging) {
                    val range = (maxValue - minValue).coerceAtLeast(1)
                    val stepFloat = dy / verticalSpanPx * range
                    val raw = startValue + (stepFloat / step).toInt() * step
                    val snapped = raw.coerceIn(minValue, maxValue)
                    if (snapped != value) {
                        value = snapped
                        onChange?.invoke(snapped)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) applyX(event.x)
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    private fun applyX(x: Float) {
        val left = padH + knobR
        val right = width - padH - knobR
        val len = (right - left).coerceAtLeast(1f)
        val f = ((x - left) / len).coerceIn(0f, 1f)
        val raw = minValue + f * (maxValue - minValue)
        val snapped = ((raw / step).roundToInt() * step).coerceIn(minValue, maxValue)
        if (snapped != value) {
            value = snapped
            onChange?.invoke(snapped)
        }
    }
}
