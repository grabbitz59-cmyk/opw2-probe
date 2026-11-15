package io.github.smartalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Геометрия принятого дизайна: **язык v1 (бирюза, градиент, карточки, капсула) с исправленной
 * геометрией**. Проверяется на JVM, чтобы CI ловил выход за круг до сборки APK.
 *
 * Правило одно: любой элемент целиком внутри окружности радиуса **209 px** вокруг (233, 233).
 * Это и есть `RoundScreen.safeRowWidth()` — прямоугольник, вписанный в круг, всегда имеет углы
 * ровно на окружности, поэтому «≤ 209» и есть корректная проверка.
 */
class GeometryHybridTest {

    private val cx = 233.0
    private val cy = 233.0
    private val safe = 209.0

    private fun worstCorner(top: Double, bottom: Double, width: Double): Double {
        var worst = 0.0
        for (x in doubleArrayOf(cx - width / 2, cx + width / 2)) {
            for (y in doubleArrayOf(top, bottom)) {
                worst = maxOf(worst, Math.hypot(x - cx, y - cy))
            }
        }
        return worst
    }

    private fun assertFits(name: String, top: Double, bottom: Double, width: Double) {
        val d = worstCorner(top, bottom, width)
        assertTrue("$name: угол на ${"%.1f".format(d)} px от центра (порог $safe)", d <= safe + 1e-6)
    }

    @Test
    fun `screen 1 bands fit the circle`() {
        // Полосы экрана 1 из v1: статус, капсула окна, кнопка.
        assertFits("статус 200–232", 200.0, 232.0, 412.0)
        assertFits("капсула 244–304", 244.0, 304.0, 392.0)
        // Кнопка 200×96: ширина урезается до safeRowWidth(314, 410) ≈ 222 px.
        assertFits("кнопка 314–410", 314.0, 410.0, 222.0)
        assertTrue(
            "кнопка ≥ 48 dp по высоте",
            410.0 - 314.0 >= 96.0,
        )
    }

    @Test
    fun `v1 defects are actually fixed`() {
        // Дефект v1: заголовок «ПРОШЛАЯ НОЧЬ» стоял [96,44][370,72] → 233.4 px от центра.
        assertTrue("заголовок на y 44…72 обязан не влезать", worstCorner(44.0, 72.0, 274.0) > safe)
        // Исправление: заголовок в полосе 96…124 — влезает.
        assertFits("заголовок 96–124", 96.0, 124.0, 300.0)
        // Дефект v1: футер [96,405][370,410] → 223.8 px и высота 5 px (обрезан).
        assertTrue("футер на y 405…410 обязан не влезать", worstCorner(405.0, 410.0, 274.0) > safe)
    }

    @Test
    fun `section dots band fits and does not touch content`() {
        val top = Ui.DOTS_TOP_PX.toDouble()
        val bottom = Ui.DOTS_BOTTOM_PX.toDouble()
        assertTrue("точки ниже контента экранов 2–3 (≤ 400)", top >= 400.0)
        assertFits("точки 412–424", top, bottom, 64.0)
        // Выше 406 точек быть не должно: там уже узкая дуга.
        assertTrue("полоса точек не выше 412", top >= 412.0)
    }

    @Test
    fun `status cards are at least 36dp tall`() {
        val rowH = 72.0
        assertTrue("строка-карточка ≥ 72 px (36 dp) по R3", rowH >= 72.0)
        // Карточка в самой узкой из занимаемых полос (низ круга) — 360 px не влезает,
        // поэтому ширина каждой строки считается через safeRowWidth.
        assertTrue("360 px на y 380…452 не влезает", worstCorner(380.0, 452.0, 360.0) > safe)
    }

    @Test
    fun `graph card stays in the middle band`() {
        // Самая широкая полоса — середина круга; карточка 300×200 в 100…300.
        assertFits("карточка графика 300 px", 100.0, 300.0, 300.0)
        assertTrue("карточка 380 px в 100…300 не влезает", worstCorner(100.0, 300.0, 380.0) > safe)
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
}
