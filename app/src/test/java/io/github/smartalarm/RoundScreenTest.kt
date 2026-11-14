package io.github.smartalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка правил скилла wear-os-ui на геометрии НАШЕЙ модели (OPW2: 466×466, круг).
 * Тесты стерегут именно те ошибки, что уже были: полноширинные кнопки у нижнего края.
 */
class RoundScreenTest {

    @Test
    fun `safe width is maximal in the middle of the screen`() {
        // 2 * sqrt(209^2 - 13^2) ≈ 417
        assertEquals(417f, RoundScreen.safeRowWidth(220f, 246f), 1.5f)
    }

    @Test
    fun `safe width shrinks toward the edges`() {
        val top = RoundScreen.safeRowWidth(40f, 104f)
        val bottom = RoundScreen.safeRowWidth(362f, 426f)
        assertTrue("у краёв уже, чем в центре", top < 300f && bottom < 300f)
        assertTrue(top > 100f && bottom > 100f)
    }

    @Test
    fun `row at very edge has no width`() {
        assertEquals(0f, RoundScreen.safeRowWidth(0f, 30f), 0.01f)
        assertEquals(0f, RoundScreen.safeRowWidth(440f, 466f), 0.01f)
    }

    @Test
    fun `old full-width bottom bar would be clipped - regression guard`() {
        // Так выглядел нижний ряд в 1.16: x 20..445 при y 398..454 — углы вне круга.
        assertFalse(RoundScreen.fits(20f, 398f, 445f, 454f))
    }

    @Test
    fun `status row fits`() {
        assertTrue(RoundScreen.fits(25f, 216f, 441f, 242f))
    }

    @Test
    fun `window pill fits`() {
        assertTrue(RoundScreen.fits(63f, 250f, 403f, 306f))
    }

    @Test
    fun `primary action fits`() {
        assertTrue(RoundScreen.fits(113f, 314f, 353f, 402f))
    }

    @Test
    fun `wide row must stay in the middle band`() {
        assertTrue(RoundScreen.fits(30f, 210f, 436f, 256f))
        // та же ширина, но ниже — уже не влезает
        assertFalse(RoundScreen.fits(30f, 380f, 436f, 426f))
    }

    @Test
    fun `inscribed square inset is about 68 px`() {
        val inset = RoundScreen.inscribedInsetPx()
        assertTrue("инсет $inset", inset in 62..74)
    }

    @Test
    fun `device constants match our watch`() {
        assertEquals(466, RoundScreen.WIDTH_PX)
        assertEquals(466, RoundScreen.HEIGHT_PX)
        // центр круга 233 → строка ровно по центру имеет ширину 2*(233-24)=418
        assertEquals(418f, RoundScreen.safeWidth(233f), 0.5f)
    }
}
