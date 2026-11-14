package io.github.smartalarm

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Контейнер циклической прокрутки экранов.
 *
 * Перехватывает горизонтальный жест на уровне `onInterceptTouchEvent`, а не в Activity:
 * иначе внутренний `ScrollView` в средней полосе съедает drag, и свайп «через середину»
 * работал через раз (проверено на устройстве).
 *
 * Левый край (первые [EDGE_GUARD_PX]) не трогаем — это системный жест «назад».
 */
class SwipeWrapLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    /** Тянем за палец: передаём смещение в px. */
    var onDrag: ((Float) -> Unit)? = null

    /** Отпустили палец: передаём итоговое смещение. */
    var onCommit: ((Float) -> Unit)? = null

    /** Жест отменён системой. */
    var onCancel: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var hijacked = false

    private fun thresholdPx(): Float = 24f * resources.displayMetrics.density

    private fun edgeGuardPx(): Float = 70f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                hijacked = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!hijacked && downX > edgeGuardPx() &&
                    abs(dx) > thresholdPx() && abs(dx) > abs(dy) * 1.4f
                ) {
                    hijacked = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = hijacked
                hijacked = false
                if (was) return true
            }
        }
        return hijacked
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true

            MotionEvent.ACTION_MOVE -> {
                if (hijacked) {
                    onDrag?.invoke(ev.x - downX)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (hijacked) {
                    val dx = ev.x - downX
                    hijacked = false
                    onCommit?.invoke(dx)
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (hijacked) {
                    hijacked = false
                    onCancel?.invoke()
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }
}
