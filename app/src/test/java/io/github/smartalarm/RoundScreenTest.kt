package io.github.smartalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * Проверка правил скилла wear-os-ui на геометрии нашего экрана (466×466, круг).
 * Тесты стерегут именно те ошибки, что уже были: полноширинные кнопки у нижнего края.
 */
class RoundScreenTest {

    private val w = 466f
    private val h = 466f
    private val margin = 24f

    @Test
    fun `safe width is maximal in the middle of the screen`() {
        val middle = RoundScreen.safeRowWidth(220f, 246f, w, h, margin)
        // 2 * (233 - 24) = 418
        assertEquals(418f, middle, 1f)
    }

    @Test
    fun `safe width shrinks toward the edges`() {
        val top = RoundScreen.safeRowWidth(40f, 104f, w, h, margin)
        val bottom = RoundScreen.safeRowWidth(362f, 426f, w, h, margin)
        assertTrue("у краёв уже, чем в центре", top < 300f && bottom < 300f)
        assertTrue(top > 0f && bottom > 0f)
    }

    @Test
    fun `row at very edge has no width`() {
        assertEquals(0f, RoundScreen.safeRowWidth(0f, 30f, w, h, margin), 0.01f)
        assertEquals(0f, RoundScreen.safeRowWidth(440f, 466f, w, h, margin), 0.01f)
    }

    @Test
    fun `old full-width bottom bar would be clipped - regression guard`() {
        // Так выглядел нижний ряд в 1.16: x 20..445 при y 398..454 — углы вне круга.
        assertFalse(RoundScreen.fits(20f, 398f, 445f, 454f, w, h, margin))
    }

    @Test
    fun `planned rows fit inside the circle`() {
        // Чипсы окна: y 300..364, ширина 380
        assertTrue("чипсы окна", RoundScreen.fits(43f, 300f, 423f, 364f, w, h, margin))
        // Основная кнопка: y 372..460 — у нижнего края, ширина 200
        assertTrue("CTA", RoundScreen.fits(133f, 372f, 333f, 460f, w, h, margin))
    }

    @Test
    fun `full-width row in the middle is allowed`() {
        assertTrue(RoundScreen.fits(24f, 210f, 442f, 256f, w, h, margin))
    }

    @Test
    fun `square screen ignores circle math`() {
        val full = RoundScreen.safeWidth(10f, 400f, 400f, margin, round = false)
        assertEquals(400f, full, 0.01f)
    }
}
