package io.github.smartalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepStatsTest {

    private val header =
        "ts,epoch_ms,mode,battery_pct,charging,batt_temp_c,screen_on,doze,hr_bpm,hr_age_s," +
            "acc_n,acc_mean,acc_std,acc_p2p,moves,hr_samples,uptime_s,hr_source,saw_asleep,alarm_state"

    private fun line(
        ts: Long,
        hr: Int = 60,
        n: Int = 750,
        moves: Int = 0,
        std: Double = 0.1,
        p2p: Double = 1.0,
        pct: Int = 80,
        asleep: Boolean = true,
        alarm: String = "armed",
        source: String = "passive",
    ): String = listOf(
        "2026-11-14T01:00:00Z", ts.toString(), "eco", pct.toString(), "false", "31.0",
        "false", "false", hr.toString(), "10", n.toString(), "9.8", std.toString(),
        p2p.toString(), moves.toString(), "5", "500", source, asleep.toString(), alarm,
    ).joinToString(",")

    @Test
    fun `parse skips comments and broken lines`() {
        assertNull(SleepStats.parseLine(""))
        assertNull(SleepStats.parseLine("# start mode=eco"))
        assertNull(SleepStats.parseLine(header))
        assertNull(SleepStats.parseLine("a,b,c"))
        assertNotNull(SleepStats.parseLine(line(1000)))
    }

    @Test
    fun `parse reads all relevant fields`() {
        val r = SleepStats.parseLine(line(ts = 5_000, hr = 72, pct = 66, source = "sensor"))!!
        assertEquals(5_000L, r.ts)
        assertEquals(72, r.hr)
        assertEquals(66, r.pct)
        assertEquals("sensor", r.hrSource)
        assertTrue(r.asleep)
        assertEquals("armed", r.alarmState)
    }

    @Test
    fun `parse tolerates old files without new columns`() {
        val old = "2026-11-13T10:00:00Z,1000,eco,55,false,30.0,false,false,70,20,500,9.8,0.1,1.2,3,4,99"
        val r = SleepStats.parseLine(old)!!
        assertEquals(70, r.hr)
        assertEquals("none", r.hrSource)
        assertFalse(r.asleep)
    }

    @Test
    fun `motion follows calibrated thresholds`() {
        assertFalse(SleepStats.isMotion(SleepStats.parseLine(line(1000))!!))
        val moving = SleepStats.parseLine(line(2000, n = 750, moves = 52, std = 1.68, p2p = 12.4))!!
        assertTrue(SleepStats.isMotion(moving))
        val empty = SleepStats.parseLine(line(3000, n = 0))!!
        assertFalse(SleepStats.isMotion(empty))
    }

    @Test
    fun `summarize computes coverage hr and battery`() {
        val rows = (0 until 10).map {
            SleepStats.parseLine(line(ts = it * 30_000L, hr = 60 + it, pct = 80 - it))!!
        }
        val night = SleepStats.summarize(rows)!!
        assertEquals(10, night.rows)
        assertEquals(60, night.hrMin)
        assertEquals(65, night.hrAvg)
        assertEquals(69, night.hrMax)
        assertEquals(10, night.hrWindows)
        assertEquals(80, night.batteryStart)
        assertEquals(71, night.batteryEnd)
        // 10 окон по 30 с = ровно 5 минут, покрытие 100 %
        assertEquals(100.0, night.coveragePct, 0.01)
    }

    @Test
    fun `coverage below hundred when buckets are missing`() {
        // Окна: 0, 30с, 10 мин — между ними дыра
        val rows = listOf(0L, 30_000L, 600_000L).map {
            SleepStats.parseLine(line(ts = it))!!
        }
        val night = SleepStats.summarize(rows)!!
        assertTrue(night.coveragePct < 10.0)
    }

    @Test
    fun `awakenings count episodes not micro movements`() {
        // 6 окон тишины, затем 3 окна движения (эпизод), затем тишина
        val quiet = (0 until 6).map { SleepStats.parseLine(line(ts = it * 30_000L))!! }
        val move = (6 until 9).map {
            SleepStats.parseLine(line(ts = it * 30_000L, moves = 60, std = 1.5, p2p = 10.0))!!
        }
        val after = (9 until 14).map { SleepStats.parseLine(line(ts = it * 30_000L))!! }
        assertEquals(1, SleepStats.awakenings(quiet + move + after))
    }

    @Test
    fun `single motion bucket is not an awakening`() {
        val rows = (0 until 12).map {
            val moving = it == 5
            SleepStats.parseLine(
                line(ts = it * 30_000L, moves = if (moving) 60 else 0,
                    std = if (moving) 1.5 else 0.1, p2p = if (moving) 10.0 else 1.0)
            )!!
        }
        assertEquals(0, SleepStats.awakenings(rows))
    }

    @Test
    fun `alarm fired is detected`() {
        val rows = (0 until 4).map {
            SleepStats.parseLine(line(ts = it * 30_000L, alarm = if (it >= 2) "triggered" else "armed"))!!
        }
        assertTrue(SleepStats.summarize(rows)!!.alarmFired)
    }

    @Test
    fun `empty summary is null`() {
        assertNull(SleepStats.summarize(emptyList()))
    }
}
