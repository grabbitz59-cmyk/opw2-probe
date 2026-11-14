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
        // 2 * sqrt(209^2 - 13^2) ≈ 417
        val middle = RoundScreen.safeRowWidth(220f, 246f, w, h, margin)
        assertEquals(417f, middle, 1.5f)
    }

    @Test
    fun `safe width shrinks toward the edges`() {
        val top = RoundScreen.safeRowWidth(40f, 104f, w, h, margin)
        val bottom = RoundScreen.safeRowWidth(362f, 426f, w, h, margin)
        assertTrue("у краёв уже, чем в центре", top < 300f && bottom < 300f)
        assertTrue(top > 100f && bottom > 100f)
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
    fun `status row fits`() {
        // y 216..242, ширина 416 (x 25..441)
        assertTrue(RoundScreen.fits(25f, 216f, 441f, 242f, w, h, margin))
    }

    @Test
    fun `window pill fits`() {
        // y 250..306, ширина 340 (x 63..403)
        assertTrue(RoundScreen.fits(63f, 250f, 403f, 306f, w, h, margin))
    }

    @Test
    fun `primary action fits`() {
        // y 314..402, ширина 240 (x 113..353)
        assertTrue(RoundScreen.fits(113f, 314f, 353f, 402f, w, h, margin))
    }

    @Test
    fun `wide row must stay in the middle band`() {
        assertTrue(RoundScreen.fits(30f, 210f, 436f, 256f, w, h, margin))
        // та же ширина, но ниже — уже не влезает
        assertFalse(RoundScreen.fits(30f, 380f, 436f, 426f, w, h, margin))
    }

    @Test
    fun `square screen ignores circle math`() {
        val full = RoundScreen.safeWidth(10f, 400f, 400f, margin, round = false)
        assertEquals(400f, full, 0.01f)
    }
}
