package io.github.smartalarm

import java.util.Calendar

/**
 * Повтор будильника по дням недели и расчёт следующего срабатывания.
 *
 * Чистая логика без Android — покрыта JVM-тестами. Маска дней: bit 0 = понедельник …
 * bit 6 = воскресенье (как `Calendar.DAY_OF_WEEK` минус 2).
 */
object RepeatDays {

    const val NONE = 0
    const val WEEKDAYS = 0b0011111      // Пн–Пт
    const val ALL = 0b1111111           // все дни

    fun bitOf(calendarDayOfWeek: Int): Int = (calendarDayOfWeek + 5) % 7

    fun includes(mask: Int, calendarDayOfWeek: Int): Boolean =
        (mask and (1 shl bitOf(calendarDayOfWeek))) != 0

    fun label(mask: Int): String = when (mask) {
        NONE -> "выкл"
        WEEKDAYS -> "будни"
        ALL -> "каждый день"
        else -> "выбрано"
    }

    fun nextMask(mask: Int): Int = when (mask) {
        NONE -> WEEKDAYS
        WEEKDAYS -> ALL
        else -> NONE
    }

    /**
     * Ближайший момент HH:MM, попадающий в выбранные дни.
     * Если время сегодня ещё не наступило и день разрешён — сегодня; иначе ищем вперёд
     * до 8 дней. Маска NONE трактуется как «сегодня или завтра».
     */
    fun nextOccurrence(nowMs: Long, hour: Int, minute: Int, mask: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        for (dayOffset in 0..8) {
            val candidate = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis <= nowMs + 30_000L) continue
            if (mask == NONE || includes(mask, candidate.get(Calendar.DAY_OF_WEEK))) {
                return candidate.timeInMillis
            }
        }
        return nowMs + 24 * 60 * 60 * 1000L
    }
}
