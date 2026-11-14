package io.github.smartalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Повтор по дням и расчёт следующего срабатывания (чистая логика).
 */
class RepeatDaysTest {

    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int,
    ): Long = Calendar.getInstance().apply {
        set(year, month - 1, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `mask bits map monday to zero`() {
        // Calendar: ВС=1, ПН=2, … СБ=7
        assertEquals(0, RepeatDays.bitOf(Calendar.MONDAY))
        assertEquals(6, RepeatDays.bitOf(Calendar.SUNDAY))
        assertTrue(RepeatDays.includes(RepeatDays.WEEKDAYS, Calendar.MONDAY))
        assertFalse(RepeatDays.includes(RepeatDays.WEEKDAYS, Calendar.SATURDAY))
        assertTrue(RepeatDays.includes(RepeatDays.ALL, Calendar.SUNDAY))
    }

    @Test
    fun `next mask cycles off - weekdays - all`() {
        assertEquals(RepeatDays.WEEKDAYS, RepeatDays.nextMask(RepeatDays.NONE))
        assertEquals(RepeatDays.ALL, RepeatDays.nextMask(RepeatDays.WEEKDAYS))
        assertEquals(RepeatDays.NONE, RepeatDays.nextMask(RepeatDays.ALL))
    }

    @Test
    fun `labels are human readable`() {
        assertEquals("выкл", RepeatDays.label(RepeatDays.NONE))
        assertEquals("будни", RepeatDays.label(RepeatDays.WEEKDAYS))
        assertEquals("каждый день", RepeatDays.label(RepeatDays.ALL))
    }

    @Test
    fun `without repeat next occurrence is today or tomorrow`() {
        // 14 ноября 2026, 21:00 — суббота
        val now = at(2026, 11, 14, 21, 0)
        val next = RepeatDays.nextOccurrence(now, 7, 0, RepeatDays.NONE)
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertTrue("в будущем", next > now)
    }

    @Test
    fun `weekdays mask skips weekend`() {
        // Пятница 13 ноября 2026, 23:00 → следующий будний день = понедельник 16-го
        val now = at(2026, 11, 13, 23, 0)
        val next = RepeatDays.nextOccurrence(now, 7, 0, RepeatDays.WEEKDAYS)
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `same day allowed when time is ahead`() {
        // Суббота 14 ноября 2026, 06:00, цель 07:00, маска «все дни»
        val now = at(2026, 11, 14, 6, 0)
        val next = RepeatDays.nextOccurrence(now, 7, 0, RepeatDays.ALL)
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(14, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `past time moves to next matching day`() {
        val now = at(2026, 11, 14, 9, 0)
        val next = RepeatDays.nextOccurrence(now, 7, 0, RepeatDays.ALL)
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
    }
}
