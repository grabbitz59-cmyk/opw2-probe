package io.github.smartalarm

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Бесконечная карусель разделов: свайп влево — следующая страница, после последней снова первая.
 *
 * Ключевое отличие от предыдущих попыток: **обе страницы всегда VISIBLE**, меняются только их
 * `x`. Скрытие через `GONE` ломало доставку касаний — после первого перехода жесты переставали
 * доходить до пейджера (проверено трассировкой: `drag begin` больше не появлялся).
 *
 * Левый край экрана (первые ~70 px) не трогаем: там системный жест «назад».
 */
class CarouselPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val pages = ArrayList<View>()
    private var index = 0
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var anim: ValueAnimator? = null

    var onPageChanged: ((Int) -> Unit)? = null

    private val screenW: Float get() = resources.displayMetrics.widthPixels.toFloat()

    private fun other(): Int = (index + 1) % pages.size

    fun addPage(page: View) {
        page.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pages.add(page)
        addView(page)
        page.visibility = View.VISIBLE
        page.x = if (pages.size == 1) 0f else screenW
    }

    fun currentIndex(): Int = index

    fun showPage(target: Int) {
        if (pages.size < 2) return
        val t = ((target % pages.size) + pages.size) % pages.size
        if (t == index) return
        pages[index].x = screenW
        index = t
        pages[index].x = 0f
        onPageChanged?.invoke(index)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Дети просят не перехватывать — игнорируем, иначе горизонталь не доходит до нас
        super.requestDisallowInterceptTouchEvent(false)
    }

    private fun slopPx(): Float = 20f * resources.displayMetrics.density

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (pages.size < 2) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && downX > 70f && abs(dx) > slopPx() && abs(dx) > abs(dy) * 1.4f) {
                    dragging = true
                    anim?.cancel()
                    anim = null
                    // соседняя страница подъезжает с той стороны, куда тянем
                    pages[other()].x = if (dx < 0) screenW else -screenW
                    AlarmLogic.log(context, "carousel drag idx=$index dx=$dx")
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    applyDrag(ev.x - downX)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val dx = ev.x - downX
                    dragging = false
                    if (abs(dx) >= 90f) commit(dx) else revert(dx)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    revert(ev.x - downX)
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun applyDrag(dx: Float) {
        pages[index].x = dx
        val o = pages[other()]
        o.x = if (dx < 0) screenW + dx else -screenW + dx
    }

    private fun commit(dx: Float) {
        val toLeft = dx < 0
        val cur = pages[index]
        val nxt = pages[other()]
        val curFrom = cur.x
        val nxtFrom = nxt.x
        val curTo = if (toLeft) -screenW else screenW
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170L
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                cur.x = curFrom + (curTo - curFrom) * f
                nxt.x = nxtFrom + (0f - nxtFrom) * f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // обе остаются видимыми: ушедшая просто паркуется за краем
                    cur.x = screenW
                    nxt.x = 0f
                    index = other()
                    AlarmLogic.log(context, "carousel settle idx=$index")
                    onPageChanged?.invoke(index)
                }
            })
            start()
        }
    }

    private fun revert(dx: Float) {
        val cur = pages[index]
        val nxt = pages[other()]
        val curFrom = cur.x
        val nxtFrom = nxt.x
        val nxtTo = if (curFrom < 0) screenW else -screenW
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140L
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                cur.x = curFrom + (0f - curFrom) * f
                nxt.x = nxtFrom + (nxtTo - nxtFrom) * f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    cur.x = 0f
                    nxt.x = screenW
                }
            })
            start()
        }
    }
}
