package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Строка-леджер экрана «ПУЛЬТ» — `docs/design-v2.md` §4.4.
 *
 * Приборная строка: подпись слева, значение справа, волосяная линейка 1 px по низу.
 * Никакой карточки, фона, тени и скругления — только текст и линейка.
 *
 * Логический размер — 320 × 72 px; `onDraw` масштабируется от фактических `width/height`,
 * поэтому компонент можно ставить уже 320 px (на экране 3 он стоит 296 px из-за круга):
 * кегли остаются токенными, отступы и линейка садятся по месту.
 *
 * Hit-зона — вся площадь 72 px (36 dp ≥ R3). Нажатие не меняет фон: значение на время касания
 * становится `ember_hot` (мгновенно, без анимации) и возвращается на отпускании.
 */
class LedgerRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** Подпись слева: caps, `SZ_ROW_LABEL`, ls `LS_ROW_LABEL`, `paper_2`. */
    var label: String = ""
        set(v) {
            field = v.uppercase()
            invalidate()
        }

    /** Значение справа: caps, `SZ_ROW_VALUE`, `paper`; при [isActive] — `ember`. */
    var value: String = ""
        set(v) {
            field = v.uppercase()
            invalidate()
        }

    /** Строка «в работе»: значение в акценте (`ember`). */
    var isActive: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    /** Волосяная линейка по низу; у последней строки экрана выключается. */
    var showDivider: Boolean = true
        set(v) {
            field = v
            invalidate()
        }

    private var onRowTap: (() -> Unit)? = null

    private var pressed = false
    private var downX = 0f
    private var downY = 0f

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
    private val rulePaint = Ui.fillPaint(Ui.rule)

    /** Тап по строке. Хаптик `tick` и подсветку значения делает сам компонент. */
    fun setOnRowTap(l: () -> Unit) {
        onRowTap = l
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Логический размер 320 × 72: кегль едет за высотой, отступы — за шириной.
        val kx = w / LOGICAL_W
        val ky = h / LOGICAL_H

        labelPaint.textSize = Ui.SZ_ROW_LABEL * ky
        labelPaint.color = Ui.paper2
        valuePaint.textSize = Ui.SZ_ROW_VALUE * ky
        valuePaint.color = when {
            pressed -> Ui.emberHot
            isActive -> Ui.ember
            else -> Ui.paper
        }

        // Строка на экране 3 уже логических 320 px: если пара «подпись + значение»
        // не влезает по кеглю, поджимаем её по горизонтали, а не обрезаем и не наезжаем.
        labelPaint.textScaleX = 1f
        valuePaint.textScaleX = 1f
        val pad = PAD_PX * kx
        val inner = w - 2f * pad
        val natural = labelPaint.measureText(label) + valuePaint.measureText(value)
        if (natural + GAP_MIN_PX > inner && natural > 0f) {
            val k = ((inner - GAP_MIN_PX) / natural).coerceAtLeast(MIN_SCALE_X)
            labelPaint.textScaleX = k
            valuePaint.textScaleX = k
        }

        val midY = h / 2f
        canvas.drawText(
            label,
            pad,
            midY - (labelPaint.ascent() + labelPaint.descent()) / 2f,
            labelPaint,
        )
        canvas.drawText(
            value,
            w - pad,
            midY - (valuePaint.ascent() + valuePaint.descent()) / 2f,
            valuePaint,
        )
        if (showDivider) canvas.drawRect(0f, h - DIVIDER_PX, w, h, rulePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Свайп раздела тянет контент: касание, ушедшее дальше слопа, — не тап.
                if (pressed && (abs(event.x - downX) > SLOP_PX || abs(event.y - downY) > SLOP_PX)) {
                    pressed = false
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasPressed = pressed
                pressed = false
                invalidate()
                if (wasPressed) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onRowTap?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val LOGICAL_W = 320f
        const val LOGICAL_H = 72f

        /** Внутренний отступ подписи/значения от края строки — 16 px (§4.4). */
        const val PAD_PX = 16f

        /** Трекинг значения — 0.02 em (§4.4). */
        const val VALUE_TRACKING = 0.02f

        const val DIVIDER_PX = 1f

        /** Минимальный просвет между подписью и значением. */
        const val GAP_MIN_PX = 12f

        /** Предел поджатия пары «подпись — значение» по горизонтали. */
        const val MIN_SCALE_X = 0.85f

        /** Порог, после которого касание перестаёт быть нажатием. */
        const val SLOP_PX = 24f
    }
}
