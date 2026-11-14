package io.github.smartalarm

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Карусель разделов: тянем влево — уходим на следующий, после последнего снова первый.
 *
 * Почему в одной Activity: право-свайп на Wear OS принадлежит системе («назад»), а
 * межактивити-переходы не дают надёжно перехватывать жест. Здесь обе страницы лежат
 * в одном окне, поэтому перехват работает так же, как на главном экране.
 *
 * Механика: текущая страница едет за пальцем, следующая подъезжает сбоку.
 * На отпускании либо доводим (и меняем индекс), либо возвращаем обе назад.
 * С двумя страницами индекс после доводки просто переключается 1 → 0 → 1 …
 */
class CyclePager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val pages = ArrayList<android.view.View>()
    private var index = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var neighbor: android.view.View? = null
    private var committing = false

    private val screenW: Float get() = resources.displayMetrics.widthPixels.toFloat()

    var onPageChanged: ((Int) -> Unit)? = null

    fun addPage(view: android.view.View) {
        pages.add(view)
        addView(view)
        if (pages.size > 1) view.translationX = screenW
    }

    fun pageCount(): Int = pages.size

    fun currentIndex(): Int = index

    /** Программный переход (например, по long-press). */
    fun showPage(target: Int, animate: Boolean = true) {
        if (pages.isEmpty()) return
        val next = ((target % pages.size) + pages.size) % pages.size
        if (next == index) return
        neighbor = pages[next]
        neighbor!!.translationX = screenW
        neighbor!!.bringToFront()
        if (!animate) {
            neighbor!!.translationX = 0f
            pages[index].translationX = screenW
            index = next
            onPageChanged?.invoke(index)
            return
        }
        val current = pages[index]
        current.animate().translationX(-screenW).alpha(0.35f).setDuration(180L)
            .withEndAction { settle(next, -screenW) }.start()
        neighbor!!.animate().translationX(0f).alpha(1f).setDuration(180L).start()
    }

    private fun thresholdPx(): Float = 90f

    private fun cancelPx(): Float = 24f * resources.displayMetrics.density

    private fun edgeGuardPx(): Float = 70f

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        super.requestDisallowInterceptTouchEvent(false)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (pages.size < 2) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                neighbor = null
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && downX > edgeGuardPx() &&
                    abs(dx) > cancelPx() && abs(dx) > abs(dy) * 1.4f
                ) {
                    dragging = true
                    startDrag(dx)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragging
                if (was) return true
            }
        }
        return dragging
    }

    private fun startDrag(dx: Float) {
        // Гасим возможные незавершённые анимации прошлого перехода (иначе их
        // withEndAction приходит позже и сбрасывает индекс — страница «залипает»)
        committing = false
        pages.forEach { it.animate().cancel() }
        val nextIndex = if (dx < 0) (index + 1) % pages.size else (index - 1 + pages.size) % pages.size
        neighbor = pages[nextIndex]
        // Следующая страница стоит с той стороны, откуда её тянем
        neighbor!!.translationX = if (dx < 0) screenW else -screenW
        neighbor!!.bringToFront()
        pages[index].bringToFront()
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
                    if (abs(dx) >= thresholdPx()) {
                        val toLeft = dx < 0
                        val current = pages[index]
                        val n = neighbor ?: return true
                        val nextIndex = if (toLeft) (index + 1) % pages.size
                        else (index - 1 + pages.size) % pages.size
                        committing = true
                        current.animate()
                            .translationX(if (toLeft) -screenW else screenW)
                            .alpha(0.35f)
                            .setDuration(160L)
                            .withEndAction {
                                if (committing) {
                                    committing = false
                                    settle(nextIndex, if (toLeft) -screenW else screenW)
                                }
                            }
                            .start()
                        n.animate().translationX(0f).alpha(1f).setDuration(160L).start()
                    } else {
                        revert()
                    }
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
        val current = pages[index]
        current.translationX = dx
        current.alpha = 1f - 0.25f * (abs(dx) / screenW)
        val n = neighbor ?: return
        n.translationX = if (dx < 0) screenW + dx else -screenW + dx
        n.alpha = 0.75f + 0.25f * (abs(dx) / screenW)
    }

    private fun revert() {
        pages[index].animate().translationX(0f).alpha(1f).setDuration(140L).start()
        neighbor?.animate()?.translationX(if (pages[index].translationX < 0) screenW else -screenW)
            ?.alpha(0.75f)?.setDuration(140L)?.start()
        neighbor = null
    }

    private fun settle(nextIndex: Int, exitingAt: Float) {
        val old = pages[index]
        old.translationX = if (exitingAt < 0) -screenW else screenW
        old.alpha = 0.35f
        val n = pages[nextIndex]
        n.translationX = 0f
        n.alpha = 1f
        n.bringToFront()
        index = nextIndex
        neighbor = null
        onPageChanged?.invoke(index)
    }
}
