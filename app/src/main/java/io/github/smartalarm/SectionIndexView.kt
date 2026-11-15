package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Индикатор раздела: три риски в полосе `y 60…72` (см. docs/design-v2.md §3, §4.5).
 *
 * Заменяет точки нижней дуги из v1, которые наезжали на контент и были выключены на всех
 * экранах. Риски — часть гравировки: активная янтарная и длиннее, неактивные — короткие
 * тёмно-серые. Анимации нет: смена раздела = смена экрана.
 *
 * Группа центрируется по фактической сумме длин (активная риска может стоять в любой из трёх
 * позиций, поэтому просто «64 = 16 + 12 + 8 + 12 + 16» не работает).
 */
class SectionIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** 1..3 — активный раздел. */
    var active: Int = 1
        set(v) {
            if (field == v) return
            field = v.coerceIn(1, 3)
            invalidate()
        }

    private val activePaint = Ui.strokePaint(Ui.ember, THICKNESS).apply { strokeCap = Paint.Cap.ROUND }
    private val idlePaint = Ui.strokePaint(IDLE_COLOR, THICKNESS).apply { strokeCap = Paint.Cap.ROUND }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(WIDTH_PX, HEIGHT_PX)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val widths = FloatArray(3) { if (it == active - 1) WIDTH_ACTIVE else WIDTH_IDLE }
        val total = widths.sum() + STEP * 2
        var x = (width - total) / 2f
        for (i in 0 until 3) {
            val isActive = i == active - 1
            canvas.drawLine(x, cy, x + widths[i], cy, if (isActive) activePaint else idlePaint)
            x += widths[i] + STEP
        }
    }

    private companion object {
        const val WIDTH_PX = 64
        const val HEIGHT_PX = 12
        const val WIDTH_ACTIVE = 16f
        const val WIDTH_IDLE = 8f
        const val THICKNESS = 2f

        /** 12 px зазор между рисками; группа при активной крайней = 56 px. */
        const val STEP = 12f
        const val IDLE_COLOR = 0xFF2A2D31.toInt()
    }
}
