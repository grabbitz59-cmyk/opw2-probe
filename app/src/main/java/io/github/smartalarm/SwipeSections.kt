package io.github.smartalarm

import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity

/**
 * Навигация между тремя разделами — свайпом из центральной зоны (R11–R13).
 *
 * Правила:
 *  - свайп от левого края — системный «назад», мы его не перехватываем: жест считается только
 *    если он начался дальше [GUARD_PX] от края экрана;
 *  - длина жеста ≥ [COMMIT_PX], горизонталь заметно больше вертикали ([SLOPE]);
 *  - разделы циклические: `1 → 2 → 3 → 1` влево и `1 → 3 → 2 → 1` вправо;
 *  - во время жеста контент тянется за пальцем (`translationX = dx`) с лёгким затемнением,
 *    на отпускании — либо возврат, либо доводка и slide-переход. **Fade между разделами запрещён.**
 *
 * Стек схлопывается через `CLEAR_TOP | SINGLE_TOP`, чтобы после серии свайпов история не росла
 * и назад вело в предыдущий раздел, а не в случайную Activity.
 */
object Sections {

    const val HOME = 1
    const val NIGHT = 2
    const val PULT = 3

    /** Следующий раздел по часовой стрелке (свайп влево): 1 → 2 → 3 → 1. */
    fun next(from: Int): Int = (from % 3) + 1

    /** Предыдущий раздел (свайп вправо). */
    fun prev(from: Int): Int = ((from + 1) % 3) + 1

    fun classOf(section: Int): Class<out android.app.Activity> = when (section) {
        NIGHT -> StatsActivity::class.java
        PULT -> StatusActivity::class.java
        else -> HomeActivity::class.java
    }

    /** `R.anim.slide_in_*` enter/exit для направления. */
    fun animEnter(forward: Boolean): Int =
        if (forward) R.anim.slide_in_right else R.anim.slide_in_left

    fun animExit(forward: Boolean): Int =
        if (forward) R.anim.slide_out_left else R.anim.slide_out_right
}

/**
 * Обработчик жеста для одной Activity. Держит состояние и рисует «следование за пальцем».
 *
 * Использование в Activity:
 * ```
 * private lateinit var swipe: SectionSwipe
 * override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
 *     swipe.handle(ev)
 *     return super.dispatchTouchEvent(ev)
 * }
 * ```
 * Возвращать `true` не нужно: дочерние View сами отсекают тапы по слопу (> [SLOP_PX] движения
 * касания не считаются нажатием), поэтому жест и тап не конфликтуют.
 */
class SectionSwipe(
    private val activity: ComponentActivity,
    private val index: Int,
    private val content: View,
) {

    private var downX = 0f
    private var downY = 0f
    private var eligible = false
    private var dragging = false
    private var committed = false

    fun handle(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                eligible = downX > GUARD_PX && downX < content.width - GUARD_PX
                dragging = false
                committed = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!eligible || committed) return
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging) {
                    if (kotlin.math.abs(dx) < SLOP_PX) return
                    if (kotlin.math.abs(dx) <= kotlin.math.abs(dy) * SLOPE) {
                        eligible = false
                        return
                    }
                    dragging = true
                }
                content.translationX = dx
                content.alpha = 1f - ALPHA_MAX * (kotlin.math.abs(dx) / content.width)
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return
                val dx = ev.x - downX
                if (kotlin.math.abs(dx) >= COMMIT_PX) commit(dx < 0f) else revert()
                dragging = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) revert()
                dragging = false
            }
        }
    }

    private fun commit(forward: Boolean) {
        committed = true
        activity.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val target = if (forward) Sections.next(index) else Sections.prev(index)
        val w = if (content.width > 0) content.width.toFloat() else 466f
        // Доводка до края (180 мс), затем старт следующего раздела со slide-переходом 220 мс.
        content.animate()
            .translationX(if (forward) -w else w)
            .setDuration(COMMIT_MS)
            .withEndAction(goTo(target, forward))
            .start()
    }

    private fun goTo(section: Int, forward: Boolean): Runnable = Runnable {
        val intent = Intent(activity, Sections.classOf(section))
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)
        activity.overridePendingTransition(Sections.animEnter(forward), Sections.animExit(forward))
        // Возврат в текущий экран (если его не уничтожили) должен быть без сдвига.
        content.post {
            content.translationX = 0f
            content.alpha = 1f
        }
    }

    private fun revert() {
        content.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(REVERT_MS)
            .start()
    }

    /** Сбрасывается в onResume — на случай, если Activity вернулась с ненулевым сдвигом. */
    fun reset() {
        content.translationX = 0f
        content.alpha = 1f
        dragging = false
        committed = false
    }

    private companion object {
        /** Старт жеста не ближе 70 px к краю — левое ребро отдаётся системе. */
        const val GUARD_PX = 70f

        /** Минимальное горизонтальное движение, чтобы счесть это свайпом. */
        const val SLOP_PX = 24f

        /** Коммит жеста: не короче 90 px. */
        const val COMMIT_PX = 90f

        /** Горизонталь должна быть заметно больше вертикали. */
        const val SLOPE = 1.4f

        const val COMMIT_MS = 180L
        const val REVERT_MS = 140L
        const val ALPHA_MAX = 0.22f
    }
}
