package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Индикатор разделов: три точки, текущая — акцентом, остальные приглушены.
 *
 * Стоит в нижней полосе круга (там как раз есть место под узкую строку),
 * поэтому размеры маленькие и позиция считается от центра.
 */
class PageDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var current = 0
    private var total = 3

    private val active = Paint().apply { isAntiAlias = true; color = Ui.accent }
    private val idle = Paint().apply { isAntiAlias = true; color = android.graphics.Color.parseColor("#33474B") }

    fun setPage(index: Int, pages: Int = 3) {
        current = index
        total = pages
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val r = dp(3.5f)
        val gap = dp(8f)
        val span = (total - 1) * gap
        var x = (width - span) / 2f
        for (i in 0 until total) {
            canvas.drawCircle(x, cy, if (i == current) r * 1.15f else r * 0.85f, if (i == current) active else idle)
            x += gap
        }
    }
}
