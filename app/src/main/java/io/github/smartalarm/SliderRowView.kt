package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Горизонтальный слайдер выбора часа/минуты в языке v2 «Ночной инструмент».
 *
 * Никаких «капсул» и кружков: дорожка — волосяная линейка 2 px, заполнение — 2 px янтаря,
 * ползунок — вертикальная риска 4 × 36 px (гравированный штрих, а не точка). Подпись — caps
 * `row_label`, значение — `row_value`.
 *
 * Ввод: горизонтальный свайп системой забирается как «назад», поэтому работают тап по шкале
 * (сразу нужное значение по X), вертикальная подстройка пальцем и долгое нажатие (шаг 5 ↔ 1).
 * Высота строки 72 px — вся площадь является hit-зоной (R3).
 */
class SliderRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** Подпись строки; рисуется капителью — храним сразу в верхнем регистре. */
    var label: String = ""
        set(v) {
            field = v.uppercase()
            invalidate()
        }

    var minValue: Int = 0
    var maxValue: Int = 23
    var step: Int = 1
    var value: Int = 0
        set(v) {
            field = v.coerceIn(minValue, maxValue)
            invalidate()
        }

    var onChange: ((Int) -> Unit)? = null

    /** Долгое нажатие по шкале — например, переключение шага минут 5 ↔ 1. */
    var onLongPress: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        onLongPress?.invoke()
    }

    private val trackPaint = Ui.fillPaint(android.graphics.Color.parseColor(Ui.RULE_STRONG))
    private val fillPaint = Ui.fillPaint(Ui.ember)
    private val knobPaint = Ui.fillPaint(Ui.ember)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.condensed()
        textSize = Ui.SZ_ROW_LABEL
        letterSpacing = Ui.LS_ROW_LABEL
        textAlign = Paint.Align.LEFT
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.condensed()
        textSize = Ui.SZ_ROW_VALUE
        letterSpacing = VALUE_TRACKING
        textAlign = Paint.Align.RIGHT
    }

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

    private fun labelFor(v: Int): String = String.format(java.util.Locale.US, "%02d", v)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val nudge = h / LOGICAL_H
        labelPaint.textSize = Ui.SZ_ROW_LABEL * nudge
        valuePaint.textSize = Ui.SZ_ROW_VALUE * nudge

        // Полоса текста: подпись слева, значение справа, выровнены по базовой линии.
        val textBase = h * TEXT_BASE
        canvas.drawText(label, PAD_PX, textBase, labelPaint)
        canvas.drawText(labelFor(value), w - PAD_PX, textBase, valuePaint)

        // Дорожка у нижней кромки: риска ползунка стоит на ней и упирается в низ строки.
        val cy = h - RISER_H / 2f
        val left = PAD_PX
        val right = w - PAD_PX
        canvas.drawRect(left, cy - TRACK_W / 2f, right, cy + TRACK_W / 2f, trackPaint)

        val x = left + (right - left) * fraction()
        canvas.drawRect(left, cy - TRACK_W / 2f, x, cy + TRACK_W / 2f, fillPaint)
        canvas.drawRect(
            x - RISER_W / 2f, cy - RISER_H / 2f,
            x + RISER_W / 2f, cy + RISER_H / 2f,
            knobPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startValue = value
                dragging = false
                longPressFired = false
                handler.postDelayed(longPressRunnable, 600L)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = downY - event.y
                val dx = abs(event.x - downX)
                if (abs(dy) > 12f || dx > 12f) handler.removeCallbacks(longPressRunnable)
                if (!dragging && abs(dy) > 12f && abs(dy) > dx) {
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
                handler.removeCallbacks(longPressRunnable)
                if (!dragging && !longPressFired) applyX(event.x)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyX(x: Float) {
        val left = PAD_PX
        val right = width - PAD_PX
        val len = (right - left).coerceAtLeast(1f)
        val f = ((x - left) / len).coerceIn(0f, 1f)
        val raw = minValue + f * (maxValue - minValue)
        val snapped = ((raw / step).roundToInt() * step).coerceIn(minValue, maxValue)
        if (snapped != value) {
            value = snapped
            onChange?.invoke(snapped)
        }
    }

    private companion object {
        const val LOGICAL_H = 72f

        /** Отступ подписи/значения и краёв дорожки — 16 px (8 dp). */
        const val PAD_PX = 16f

        /** Базовая линия текста: подпись 26 px и значение 34 px сидят на одной линии. */
        const val TEXT_BASE = 0.375f

        const val TRACK_W = 2f
        const val RISER_W = 4f
        const val RISER_H = 36f

        /** Трекинг значения — 0.02 em (§4.4). */
        const val VALUE_TRACKING = 0.02f
    }
}
