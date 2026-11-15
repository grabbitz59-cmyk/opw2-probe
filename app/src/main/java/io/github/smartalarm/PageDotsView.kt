package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Индикатор разделов: три точки в нижней полосе круга.
 *
 * Активная — акцентом и крупнее, остальные — приглушённые. Плавно перетекает
 * (анимация радиуса/альфы), чтобы соответствовать «карусельному» листанию.
 */
class PageDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var current = 0
    private var total = 3
    private var phase = 0f

    private val active = Paint().apply { isAntiAlias = true; color = Ui.accent }
    private val idle = Paint().apply { isAntiAlias = true; color = Ui.phaseNone }

    private val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 220L
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
        // После перетекания возвращаем фазу в 0: в покое активная точка того же размера,
        // что и в v1, — без «раздутой» точки и без остаточной подсветки.
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                phase = 0f
                invalidate()
            }
        })
    }

    /**
     * Номер раздела — **1-based** (как `Sections.HOME/NIGHT/PULT`), внутри переводится в индекс.
     * Иначе индикатор подсвечивал соседний слот: при `setPage(2)` активной становилась вторая
     * точка, а при `setPage(3)` активной не было вовсе (замерено на устройстве).
     */
    fun setSection(section: Int, pages: Int = 3) {
        setPage(section - 1, pages)
    }

    fun setPage(index: Int, pages: Int = 3) {
        if (index == current && pages == total) return
        current = index
        total = pages
        animator.cancel()
        animator.start()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val gap = dp(9f)
        // Радиусы ограничены высотой вью: полоса индикатора 20 px (408…428 px), и активная
        // точка с подсветкой не должны обрезаться её границами.
        val maxR = height / 2f - 1f
        val baseR = minOf(dp(2.6f), maxR / 1.5f)
        val span = (total - 1) * gap
        var x = (width - span) / 2f
        for (i in 0 until total) {
            val isActive = i == current
            val radius = if (isActive) baseR * (1.35f + 0.15f * phase) else baseR
            val paint = if (isActive) active else idle
            paint.alpha = if (isActive) 255 else 120
            canvas.drawCircle(x, cy, radius, paint)
            x += gap
        }
        // мягкая подсветка вокруг активной точки на время анимации
        if (phase > 0.01f) {
            val cx = (width - span) / 2f + current * gap
            active.alpha = (70 * (1f - phase)).toInt().coerceIn(0, 70)
            canvas.drawCircle(cx, cy, minOf(baseR * 3.2f, maxR), active)
            active.alpha = 255
        }
    }
}
