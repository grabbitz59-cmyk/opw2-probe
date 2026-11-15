package io.github.smartalarm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Оценка фазы в момент подъёма: движение → бодрствование, пульс ниже медианы → глубокий.
 */
class PhaseEstimatorTest {

    @Test
    fun `recent motion means awake`() {
        assertEquals(
            SleepStats.Stage.AWAKE,
            PhaseEstimator.estimate(hrNow = 50f, hrMedian = 60f, motionRecent = true),
        )
    }

    @Test
    fun `pulse below median by four percent is deep`() {
        assertEquals(
            SleepStats.Stage.DEEP,
            PhaseEstimator.estimate(hrNow = 55f, hrMedian = 60f, motionRecent = false),
        )
    }

    @Test
    fun `pulse near median is light`() {
        assertEquals(
            SleepStats.Stage.LIGHT,
            PhaseEstimator.estimate(hrNow = 60f, hrMedian = 60f, motionRecent = false),
        )
        // 58 выше порога 60 * 0.96 = 57.6, значит это уже лёгкий сон
        assertEquals(
            SleepStats.Stage.LIGHT,
            PhaseEstimator.estimate(hrNow = 58f, hrMedian = 60f, motionRecent = false),
        )
    }

    @Test
    fun `no pulse data means unknown`() {
        assertEquals(
            SleepStats.Stage.UNKNOWN,
            PhaseEstimator.estimate(hrNow = 0f, hrMedian = 60f, motionRecent = false),
        )
        assertEquals(
            SleepStats.Stage.UNKNOWN,
            PhaseEstimator.estimate(hrNow = 60f, hrMedian = null, motionRecent = false),
        )
    }

    @Test
    fun `codes round trip for csv`() {
        for (stage in SleepStats.Stage.values()) {
            val code = PhaseEstimator.code(stage)
            assertEquals(stage, PhaseEstimator.fromCode(code))
        }
        assertEquals(SleepStats.Stage.UNKNOWN, PhaseEstimator.fromCode("что-то"))
    }

    @Test
    fun `labels are human readable`() {
        assertEquals("лёгкий сон", PhaseEstimator.label(SleepStats.Stage.LIGHT))
        assertEquals("глубокий сон", PhaseEstimator.label(SleepStats.Stage.DEEP))
        assertEquals("бодрствование", PhaseEstimator.label(SleepStats.Stage.AWAKE))
    }

    @Test
    fun `stale pulse uses night median instead of giving up`() {
        // Реальный случай с часов: пульс 63 bpm пришёл 41 минуту назад, медиана за ночь 65
        assertEquals(
            SleepStats.Stage.LIGHT,
            PhaseEstimator.estimate(
                hrNow = 63f,
                hrAgeMin = 41f,
                hrMedianWindow = null,
                hrMedianSession = 65f,
                motionRecent = false,
            ),
        )
        // Слишком старый пульс — уже не оцениваем
        assertEquals(
            SleepStats.Stage.UNKNOWN,
            PhaseEstimator.estimate(
                hrNow = 63f,
                hrAgeMin = 200f,
                hrMedianWindow = null,
                hrMedianSession = 65f,
                motionRecent = false,
            ),
        )
    }

    @Test
    fun `fresh window median wins over night median`() {
        // Свежие данные: медиана за 45 минут (70) важнее ночной (60)
        assertEquals(
            SleepStats.Stage.DEEP,
            PhaseEstimator.estimate(
                hrNow = 65f,
                hrAgeMin = 1f,
                hrMedianWindow = 70f,
                hrMedianSession = 60f,
                motionRecent = false,
            ),
        )
    }
}
