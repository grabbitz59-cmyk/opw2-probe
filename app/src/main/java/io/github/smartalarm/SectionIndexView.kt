package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * Индикатор раздела: три риски в полосе `y 42…54` (см. docs/design-v2.md §3).
 *
 * Заменяет точки v1 (`PageDotsView`), которые лежали в нижней дуге круга, наезжали на контент
 * и были выключены на всех экранах. Риски — часть гравировки: активная янтарная и длиннее,
 * неактивные — 8 px тёмно-серые. Анимации нет: смена раздела = смена экрана.
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

    private val activePaint = Ui.strokePaint(Ui.ember, THICKNESS).apply {
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val idlePaint = Ui.strokePaint(IDLE_COLOR, THICKNESS).apply {
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(WIDTH_PX, HEIGHT_PX)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        // Центрируем группу: 3 риски, между ними STEP − THICKNESS.
        val total = 2 * (WIDTH_ACTIVE + STEP) + WIDTH_IDLE
        var x = (width - total) / 2f
        for (i in 1..3) {
            val isActive = i == active
            val len = if (isActive) WIDTH_ACTIVE else WIDTH_IDLE
            canvas.drawLine(x, cy, x + len, cy, if (isActive) activePaint else idlePaint)
            x += len + STEP
        }
    }

    private companion object {
        const val WIDTH_PX = 64
        const val HEIGHT_PX = 12
        const val WIDTH_ACTIVE = 16f
        const val WIDTH_IDLE = 8f
        const val THICKNESS = 2f

        /** 12 px зазор между рисками. */
        const val STEP = 12f
        const val IDLE_COLOR = 0xFF2A2D31.toInt()
    }
}
