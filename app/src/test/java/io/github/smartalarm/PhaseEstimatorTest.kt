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
        assertEquals(
            SleepStats.Stage.LIGHT,
            PhaseEstimator.estimate(hrNow = 57f, hrMedian = 60f, motionRecent = false),
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
}
