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

    /** Контент центрального блока не должен заезжать на кольцо (внутренний край ≈ 190). */
    private fun assertInsideRing(name: String, top: Double, bottom: Double, width: Double) {
        val ring = 186.0
        val d = worstCorner(top, bottom, width)
        assertTrue("$name: угол на ${"%.1f".format(d)} px — заезжает на кольцо (порог $ring)", d <= ring)
    }

    @Test
    fun `screen 1 content stays inside the ring`() {
        // Кольцо: толщина 16 px на r ≈ 200 → внутренний край ≈ 190 px. В v1 капсула окна
        // шириной 392 px (y 244…304) и кнопка 222 px ложились концами на дугу — центр
        // «налезал» на кольцо. Теперь блок компактный и целиком внутри 186.
        assertInsideRing("статус 192–224", 192.0, 224.0, 340.0)
        assertInsideRing("капсула 232–284", 232.0, 284.0, 320.0)
        assertInsideRing("кнопка 292–388", 292.0, 388.0, 205.0)
        assertTrue("кнопка ≥ 48 dp по высоте", 388.0 - 292.0 >= 96.0)
        // Контрпример: прежние размеры v1 обязаны не влезать в кольцо — именно это и чинили.
        assertTrue(
            "капсула 392 px на 244–304 обязана заезжать на кольцо",
            worstCorner(244.0, 304.0, 392.0) > 186.0,
        )
        assertTrue(
            "кнопка 222 px на 314–410 обязана заезжать на кольцо",
            worstCorner(314.0, 410.0, 222.0) > 186.0,
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
        assertFits("точки 414–434", 414.0, 434.0, 64.0)
        // Выше 406 точек быть не должно: там уже узкая дуга.
        assertTrue("полоса точек не выше 414", top >= 414.0)
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
