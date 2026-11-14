package io.github.smartalarm

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Бесконечная карусель разделов: свайп влево — следующая страница, после последней
 * снова первая. Тянется за пальцем, доводится анимацией.
 *
 * Состояние детерминированное и не зависит от порядка коллбеков:
 *  - видима всегда ровно одна страница (`visibility`), остальные GONE и стоят в x=0;
 *  - на время перетягивания соседняя страница показывается и ставится сбоку;
 *  - итог перехода выставляется ровно один раз в конце анимации по флагу [busy].
 * Плюс игнорируется `requestDisallowInterceptTouchEvent` от детей (см. комментарий ниже).
 */
class CarouselPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val pages = ArrayList<View>()
    private var index = 0
    private var neighborIndex = -1
    private var dragging = false
    private var busy = false
    private var downX = 0f
    private var downY = 0f
    private var anim: ValueAnimator? = null

    var onPageChanged: ((Int) -> Unit)? = null

    private val screenW: Float get() = resources.displayMetrics.widthPixels.toFloat()

    fun addPage(page: View) {
        page.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pages.add(page)
        addView(page)
        page.x = 0f
        page.visibility = if (pages.size == 1) VISIBLE else GONE
    }

    fun currentIndex(): Int = index

    fun pageCount(): Int = pages.size

    /** Программный переход (например, long-press по циферблату). */
    fun showPage(target: Int, animate: Boolean = false) {
        if (pages.size < 2) return
        val t = ((target % pages.size) + pages.size) % pages.size
        if (t == index) return
        if (!animate) {
            pages[index].visibility = GONE
            pages[index].x = 0f
            index = t
            pages[index].x = 0f
            pages[index].visibility = VISIBLE
            onPageChanged?.invoke(index)
        }
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Дети (например, ScrollView) просят не перехватывать — игнорируем,
        // иначе горизонтальный жест не доходит до карусели.
        super.requestDisallowInterceptTouchEvent(false)
    }

    private fun slopPx(): Float = 24f * resources.displayMetrics.density

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
                if (!dragging && !busy && downX > 70f &&
                    abs(dx) > slopPx() && abs(dx) > abs(dy) * 1.4f
                ) {
                    dragging = true
                    beginDrag(dx)
                }
            }
        }
        return dragging
    }

    private fun beginDrag(dx: Float) {
        AlarmLogic.log(context, "carousel drag begin idx=$index dx=$dx")
        anim?.cancel()
        anim = null
        neighborIndex = if (dx < 0) (index + 1) % pages.size else (index - 1 + pages.size) % pages.size
        val n = pages[neighborIndex]
        n.visibility = VISIBLE
        n.x = if (dx < 0) screenW else -screenW
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
                    if (abs(dx) >= 90f) commit(dx) else revert()
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    revert()
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun applyDrag(dx: Float) {
        pages[index].x = dx
        if (neighborIndex >= 0) {
            val n = pages[neighborIndex]
            n.x = if (dx < 0) screenW + dx else -screenW + dx
        }
    }

    private fun commit(dx: Float) {
        if (neighborIndex < 0) {
            revert()
            return
        }
        val toLeft = dx < 0
        val cur = pages[index]
        val next = pages[neighborIndex]
        val curFrom = cur.x
        val nextFrom = next.x
        val curTo = if (toLeft) -screenW else screenW
        val nextTo = 0f
        busy = true
        AlarmLogic.log(context, "carousel commit $index -> $neighborIndex dx=$dx")
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                cur.x = curFrom + (curTo - curFrom) * f
                next.x = nextFrom + (nextTo - nextFrom) * f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    cur.visibility = GONE
                    cur.x = 0f
                    index = neighborIndex
                    neighborIndex = -1
                    next.x = 0f
                    next.visibility = VISIBLE
                    busy = false
                    AlarmLogic.log(context, "carousel settle idx=$index")
                    onPageChanged?.invoke(index)
                }
            })
            start()
        }
    }

    private fun revert() {
        if (neighborIndex < 0) return
        val cur = pages[index]
        val next = pages[neighborIndex]
        val curFrom = cur.x
        val nextFrom = next.x
        val nextTo = if (curFrom < 0) screenW else -screenW
        busy = true
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150L
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                cur.x = curFrom + (0f - curFrom) * f
                next.x = nextFrom + (nextTo - nextFrom) * f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    next.visibility = GONE
                    next.x = 0f
                    cur.x = 0f
                    neighborIndex = -1
                    busy = false
                }
            })
            start()
        }
    }
}
