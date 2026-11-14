package io.github.smartalarm

import android.content.res.Configuration
import android.content.res.Resources
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Геометрия круглого экрана (см. docs/skills/wear-os-ui.md, правила R1–R8).
 *
 * На OPW2 экран 466×466 px, центр (233, 233), радиус 233. Всё, что оказалось
 * в углах, физически обрезается дугой — поэтому ширину строк считаем от круга.
 */
object RoundScreen {

    /** Отступ внутрь круга, px (≈10 % радиуса). */
    const val SAFE_MARGIN_PX = 24f

    fun isRound(resources: Resources): Boolean =
        resources.configuration.isScreenRound

    /** Есть ли у экрана нижняя «бородка» (chin), съедающая место. */
    fun hasChin(resources: Resources): Boolean {
        val c: Configuration = resources.configuration
        if (!c.isScreenRound) return false
        // Эвристика Wear: квадратные часы с chin имеют заметный отступ снизу.
        return false
    }

    /**
     * Максимальная ширина строки, центр которой находится на высоте [centerYpx],
     * так чтобы она целиком лежала внутри круга с отступом [marginPx].
     */
    fun safeWidth(
        centerYpx: Float,
        screenW: Float = 466f,
        screenH: Float = 466f,
        marginPx: Float = SAFE_MARGIN_PX,
        round: Boolean = true,
    ): Float {
        if (!round) return screenW
        val cx = screenW / 2f
        val cy = screenH / 2f
        val radius = minOf(cx, cy) - marginPx
        val dy = abs(centerYpx - cy)
        if (dy >= radius) return 0f
        val half = sqrt(radius * radius - dy * dy)
        return (half * 2f).coerceAtMost(screenW - marginPx * 2f)
    }

    /**
     * Максимальная ширина строки между [topPx] и [bottomPx]: учитываются оба края,
     * а не только центр — иначе высокий блок у самого низа круга всё равно обрежется.
     */
    fun safeRowWidth(
        topPx: Float,
        bottomPx: Float,
        screenW: Float = 466f,
        screenH: Float = 466f,
        marginPx: Float = SAFE_MARGIN_PX,
    ): Float {
        val cx = screenW / 2f
        val cy = screenH / 2f
        val radius = minOf(cx, cy) - marginPx
        val dyMax = maxOf(abs(topPx - cy), abs(bottomPx - cy))
        if (dyMax >= radius) return 0f
        return (2f * sqrt(radius * radius - dyMax * dyMax)).coerceAtMost(screenW)
    }

    /** Прямоугольник целиком внутри круга? (без Android-типов — тестируется на JVM) */
    fun fits(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        screenW: Float = 466f,
        screenH: Float = 466f,
        marginPx: Float = SAFE_MARGIN_PX,
    ): Boolean {
        val cx = screenW / 2f
        val cy = screenH / 2f
        val r = minOf(cx, cy) - marginPx
        val corners = listOf(
            left to top, right to top,
            left to bottom, right to bottom,
        )
        return corners.all { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            dx * dx + dy * dy <= r * r
        }
    }

    /**
     * Подгоняет ширину детей [container] под круг: для каждой строки считает её центр
     * по фактической разметке и урезает ширину до безопасной (R1–R2).
     * Вызывать из `post {}` после первой раскладки.
     */
    fun applySafeWidths(container: android.view.ViewGroup, marginPx: Float = SAFE_MARGIN_PX) {
        val root = container.rootView
        val sw = root.width.toFloat()
        val sh = root.height.toFloat()
        if (sw <= 0f || sh <= 0f) return
        val round = isRound(container.resources)
        if (!round) return
        val loc = IntArray(2)
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.visibility != android.view.View.VISIBLE) continue
            child.getLocationInWindow(loc)
            val centerY = loc[1] + child.height / 2f
            val safe = safeWidth(centerY, sw, sh, marginPx, round = true)
            val lp = child.layoutParams
            if (safe > 0f && child.width > safe) {
                lp.width = safe.toInt()
                child.layoutParams = lp
            }
        }
    }
}
