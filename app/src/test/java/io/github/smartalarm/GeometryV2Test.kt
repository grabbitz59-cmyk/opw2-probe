package io.github.smartalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Геометрия дизайна v2 «Ночной инструмент» (docs/design-v2.md §2.3).
 *
 * Без Android-типов: только числа и [RoundScreen], чтобы тест жил на JVM и гонялся в CI
 * до сборки APK. Правила:
 *  - **залитые элементы** (строки, график, легенда, индикатор, разделитель) — внутри `r ≤ 196`,
 *    иначе наезжают на риски шкалы (обод начинается на 197);
 *  - **текстовые полосы** — внутри безопасного радиуса 209: буквы занимают середину полосы,
 *    а не её углы;
 *  - рычаг и зона касания — внутри 209, зона ≥ 96 px (48 dp).
 */
class GeometryV2Test {

    private val cx = 233.0
    private val cy = 233.0
    private val safe = 209.0
    private val bezelClear = 188.0

    /** Максимальное расстояние от центра до угла прямоугольника. */
    private fun worstCorner(top: Double, bottom: Double, width: Double, left: Double? = null): Double {
        val x1 = left ?: (cx - width / 2)
        val x2 = x1 + width
        var worst = 0.0
        for (x in doubleArrayOf(x1, x2)) {
            for (y in doubleArrayOf(top, bottom)) {
                worst = maxOf(worst, Math.hypot(x - cx, y - cy))
            }
        }
        return worst
    }

    private fun assertSolid(name: String, top: Double, bottom: Double, width: Double, left: Double? = null) {
        val d = worstCorner(top, bottom, width, left)
        assertTrue(
            "$name: угол на ${"%.1f".format(d)} px от центра, обод начинается на 188",
            d <= bezelClear,
        )
    }

    private fun assertTextBand(name: String, top: Double, bottom: Double, width: Double) {
        val d = worstCorner(top, bottom, width)
        assertTrue(
            "$name: полоса ${"%.1f".format(d)} px должна быть внутри $safe",
            d <= safe + 1e-9,
        )
    }

    @Test
    fun `bezel and lever stay inside safe radius`() {
        assertTrue("внешний радиус обода 208 ≤ 209", 208.0 <= safe)
        assertTrue("рычаг 206 ≤ 209", 206.0 <= safe)
        for (deg in doubleArrayOf(52.0, 90.0, 128.0)) {
            val a = Math.toRadians(deg)
            for (r in doubleArrayOf(172.0, 206.0)) {
                val x = cx + r * Math.cos(a)
                val y = cy + r * Math.sin(a)
                assertTrue(
                    "точка рычага (r=$r, θ=$deg) внутри круга",
                    Math.hypot(x - cx, y - cy) <= safe + 1e-6,
                )
            }
        }
    }

    @Test
    fun `lever hit zone is at least 48dp and does not reach the text`() {
        val rIn = 110.0
        val rOut = safe
        assertTrue("толщина зоны касания ≥ 96 px (48 dp)", rOut - rIn >= 96.0)
        // Верхний край зоны (±46° от вертикали) поднимается не выше 310 —
        // ниже полосы деталей (330…354) и полосы статуса (296…324).
        val dy = rIn * Math.cos(Math.toRadians(46.0))
        assertTrue("зона касания не доходит до текста деталей", cy + dy <= 310.0)
    }

    @Test
    fun `screen 1 tap zones`() {
        assertTrue("зона тапа по времени ≥ 96 px (48 dp)", 300.0 - 182.0 >= 96.0)
        assertSolid("зона тапа по времени (260 px)", 182.0, 300.0, 260.0)
    }

    @Test
    fun `screen 1 text bands fit the circle`() {
        // Ширины — из карты экрана 1 (§3.1); фактические строки короче полос.
        assertTextBand("сейчас", 100.0, 132.0, 322.0)
        assertTextBand("подъём", 150.0, 176.0, 383.0)
        assertTextBand("герой", 182.0, 290.0, 401.0)
        assertTextBand("статус", 296.0, 324.0, 376.0)
        assertTextBand("деталь", 330.0, 354.0, 340.0)
    }

    @Test
    fun `section index band clears the bezel`() {
        assertSolid("индикатор раздела", Ui.INDEX_TOP.toDouble(), Ui.INDEX_BOTTOM.toDouble(), 64.0)
        assertTrue("полоса индикатора не выше 60 (иначе уйдёт за 196)", Ui.INDEX_TOP >= 60f)
    }

    @Test
    fun `screen 2 elements clear the bezel`() {
        // Фактические ширины из StatsActivity: 188-правило подрезает табличные максимумы.
        assertTextBand("заголовок", 96.0, 124.0, 249.0)
        assertSolid("гипнограмма", 164.0, 286.0, 320.0)
        assertSolid("легенда", 292.0, 316.0, 300.0)
        assertTextBand("метрики", 132.0, 156.0, 309.0)
        assertTextBand("подпись фазы", 322.0, 346.0, 292.0)
        assertTextBand("фаза подъёма", 348.0, 396.0, 179.0)
    }

    @Test
    fun `screen 3 elements clear the bezel`() {
        assertSolid("строка 1", 132.0, 204.0, 296.0)
        assertSolid("строка 2", 204.0, 276.0, 296.0)
        assertSolid("строка 3", 276.0, 348.0, 296.0)
        assertSolid("разделитель", 348.0, 349.0, 295.0)
        assertSolid("телеметрия 1", 350.0, 376.0, 240.0)
        assertSolid("телеметрия 2", 374.0, 400.0, 170.0)
        // Версия прижата вправо: правый край на x = 365 (left 245 + 120).
        assertSolid("версия", 100.0, 122.0, 120.0, left = 245.0)
        // Заголовок — текстовая полоса: 300 при r = 188 даёт 203.2, поэтому в коде 257.
        assertTextBand("заголовок ПУЛЬТ", 96.0, 124.0, 257.0)
    }

    @Test
    fun `rows are 296 and would not fit at 320`() {
        val d296 = worstCorner(276.0, 348.0, 296.0)
        val d320 = worstCorner(276.0, 348.0, 320.0)
        assertTrue("296 px на y 348: ${"%.1f".format(d296)} ≤ 188", d296 <= bezelClear)
        assertTrue("320 px на y 348 не влезает: ${"%.1f".format(d320)} > 188", d320 > bezelClear)
    }

    @Test
    fun `arc start angle matches the clock position`() {
        // 23:40 → 1420 мин → на 12-часовой шкале 11:40 → 350° от полудня; canvas-угол = 350 − 90.
        assertEquals(260.0, angleOf(23 * 60 + 40), 0.001)
        // 07:12 → 126° (низ-лево, позиция 7:12).
        assertEquals(126.0, angleOf(7 * 60 + 12), 0.001)
        // 00:00 — ровно 12 часов, верх (−90° в canvas).
        assertEquals(-90.0, angleOf(0), 0.001)
    }

    @Test
    fun `countdown arc length equals remaining time`() {
        assertEquals(216f, (432 / (12f * 60f)) * 360f, 0.01f)
        assertEquals(360f, (minOf(13 * 60, 12 * 60) / (12f * 60f)) * 360f, 0.01f)
    }

    @Test
    fun `sections cycle both ways`() {
        assertEquals(2, Sections.next(1))
        assertEquals(3, Sections.next(2))
        assertEquals(1, Sections.next(3))
        assertEquals(3, Sections.prev(1))
        assertEquals(1, Sections.prev(2))
        assertEquals(2, Sections.prev(3))
    }

    /** Копия формулы из NightDialView: угол canvas для времени в минутах от полуночи. */
    private fun angleOf(minutes: Int): Double {
        val span = 12 * 60
        val m = ((minutes % span) + span) % span
        return m / span.toDouble() * 360.0 - 90.0
    }
}
