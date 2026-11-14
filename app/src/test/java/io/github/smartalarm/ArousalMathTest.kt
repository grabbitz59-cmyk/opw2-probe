package io.github.smartalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArousalMathTest {

    // ---------- движение ----------

    @Test
    fun `empty bucket is not motion`() {
        // Сенсорный батчинг отдаёт пустые окна — это не движение (ломало детект сна).
        assertFalse(ArousalMath.isMotion(n = 0, moves = 0, std = 0.0, p2p = 0.0))
    }

    @Test
    fun `still wrist from real measurement is not motion`() {
        // Реальные данные: спокойная рука, n=375, moves=1, std=0.212, p2p=3.351
        assertFalse(ArousalMath.isMotion(375, 1, 0.212, 3.351))
        // И второе окно: n=125, moves=1, std=0.324, p2p=3.472
        assertFalse(ArousalMath.isMotion(125, 1, 0.324, 3.472))
    }

    @Test
    fun `turning in bed is motion`() {
        // Реальные данные: n=750, moves=52, std=1.684, p2p=12.415
        assertTrue(ArousalMath.isMotion(750, 52, 1.684, 12.415))
    }

    @Test
    fun `each motion criterion triggers`() {
        assertTrue(ArousalMath.isMotion(100, 5, 0.0, 0.0))    // 5 % рывков
        assertTrue(ArousalMath.isMotion(100, 0, 0.9, 0.0))    // std выше порога
        assertTrue(ArousalMath.isMotion(100, 0, 0.0, 8.5))    // p2p выше порога
    }

    @Test
    fun `borderline below thresholds is not motion`() {
        assertFalse(ArousalMath.isMotion(100, 3, 0.79, 7.9))
    }

    @Test
    fun `moves percent is zero for empty bucket`() {
        assertEquals(0.0, ArousalMath.movesPct(0, 0), 1e-9)
        assertEquals(4.0, ArousalMath.movesPct(100, 4), 1e-9)
    }

    // ---------- база и подъём пульса ----------

    @Test
    fun `median odd and even`() {
        assertEquals(3.0f, ArousalMath.median(listOf(1f, 3f, 2f))!!, 1e-6f)
        assertEquals(2.5f, ArousalMath.median(listOf(1f, 2f, 3f, 4f))!!, 1e-6f)
        assertNull(ArousalMath.median(emptyList()))
    }

    @Test
    fun `baseline needs enough samples`() {
        val now = 10_000_000L
        val few = (0 until 7).map { now - it * 1000L to 60f }
        assertNull(ArousalMath.baseline(few, now))
        val enough = (0 until 8).map { now - it * 1000L to 60f }
        assertEquals(60f, ArousalMath.baseline(enough, now)!!, 1e-6f)
    }

    @Test
    fun `baseline ignores old samples`() {
        val now = 100_000_000L
        val old = (0 until 10).map { now - ArousalMath.BASELINE_WINDOW_MS - 60_000L to 30f }
        val fresh = (0 until 8).map { now - it * 1000L to 70f }
        assertEquals(70f, ArousalMath.baseline(old + fresh, now)!!, 1e-6f)
    }

    @Test
    fun `hr rise uses eight bpm threshold`() {
        assertTrue(ArousalMath.hrRise(hr = 92f, baseline = 80f))   // +12
        assertTrue(ArousalMath.hrRise(hr = 88f, baseline = 80f))   // +8 ровно
        assertFalse(ArousalMath.hrRise(hr = 85f, baseline = 80f))  // +5, шум
        assertFalse(ArousalMath.hrRise(hr = null, baseline = 80f))
        assertFalse(ArousalMath.hrRise(hr = 90f, baseline = null))
    }

    // ---------- детект сна ----------

    @Test
    fun `asleep needs quiet time and samples`() {
        assertFalse(ArousalMath.asleepInferred(quietMinutes = 19, uniqueHrSamples = 20))
        assertFalse(ArousalMath.asleepInferred(quietMinutes = 25, uniqueHrSamples = 7))
        assertTrue(ArousalMath.asleepInferred(quietMinutes = 20, uniqueHrSamples = 8))
    }

    @Test
    fun `arousal allowed with sleep evidence or long quiet`() {
        assertTrue(ArousalMath.arousalAllowed(sawAsleep = true, quietMinutes = 5))
        assertTrue(ArousalMath.arousalAllowed(sawAsleep = false, quietMinutes = 20))
        assertFalse(ArousalMath.arousalAllowed(sawAsleep = false, quietMinutes = 10))
    }

    // ---------- выборка HR ----------

    @Test
    fun `duplicate hr is not added too often`() {
        val now = 1_000_000L
        assertFalse(ArousalMath.shouldAddSample(lastValue = 80f, lastAt = now - 10_000L,
            newValue = 80f, now = now))
        assertTrue(ArousalMath.shouldAddSample(lastValue = 80f, lastAt = now - 61_000L,
            newValue = 80f, now = now))
        assertTrue(ArousalMath.shouldAddSample(lastValue = 80f, lastAt = now - 1_000L,
            newValue = 81f, now = now))
        assertTrue(ArousalMath.shouldAddSample(lastValue = -1f, lastAt = 0L,
            newValue = 70f, now = now))
    }
}
