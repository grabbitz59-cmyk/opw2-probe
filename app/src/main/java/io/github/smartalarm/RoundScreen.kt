package io.github.smartalarm

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Геометрия экрана, привязанная к нашей модели: OnePlus Watch 2 (OPWWE231).
 *
 * Только она и поддерживается: **466 × 466 px, круглый, 320 dpi, без chin**.
 * Квадратные и маленькие экраны не целим — по скиллу wear-os-ui это отдельный продукт,
 * а не режим той же вёрстки.
 *
 * Правило R1: элемент внутри круга `dx² + dy² ≤ (R − m)²`, R = 233 px, m = 24 px.
 */
object RoundScreen {

    const val WIDTH_PX = 466
    const val HEIGHT_PX = 466
    const val MARGIN_PX = 24f

    private const val CX = WIDTH_PX / 2f      // 233
    private const val CY = HEIGHT_PX / 2f     // 233
    private val R = CX - MARGIN_PX            // 209

    /**
     * Максимальная ширина строки между [topPx] и [bottomPx], целиком лежащей в круге.
     * Считаем по обоим краям строки, а не по центру — иначе высокий блок у дуги обрежется.
     */
    fun safeRowWidth(topPx: Float, bottomPx: Float): Float {
        val dyMax = maxOf(abs(topPx - CY), abs(bottomPx - CY))
        if (dyMax >= R) return 0f
        return 2f * sqrt(R * R - dyMax * dyMax)
    }

    /** Максимальная ширина строки с центром на [centerYpx]. */
    fun safeWidth(centerYpx: Float): Float = safeRowWidth(centerYpx, centerYpx)

    /** Прямоугольник целиком внутри круга? Без Android-типов — проверяется на JVM. */
    fun fits(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val corners = listOf(
            left to top, right to top,
            left to bottom, right to bottom,
        )
        return corners.all { (x, y) ->
            val dx = x - CX
            val dy = y - CY
            dx * dx + dy * dy <= R * R
        }
    }

    /**
     * Горизонтальный отступ от края экрана до вписанного квадрата (466 / √2 ≈ 330 px):
     * контент внутри этого отступа гарантированно не обрезается дугой при любом скролле.
     */
    fun inscribedInsetPx(): Int = ((WIDTH_PX - WIDTH_PX / 1.4142f) / 2f).toInt()
}
