package io.github.smartalarm

import android.app.AlarmManager
import android.content.Context

/**
 * «Системный» будильник часов — тот, что заведён через `AlarmManager.setAlarmClock`
 * (собственное приложение часов, часы/телефон-компаньон, синхронизация).
 *
 * Важно про синхронизацию с телефоном:
 *  - публичного API «прочитать будильник телефона» в Android НЕТ;
 *  - `AlarmManager.getNextAlarmClock()` отдаёт будильник ТОЛЬКО этого устройства;
 *  - если телефон синхронизирует будильники на часы (функция «Alarm sync» в паре
 *    телефон↔часы), то синхронизированный будильник появляется здесь как системный;
 *  - полноценная синхронизация из любого телефона требует приложения-компаньона
 *    на телефоне (Data Layer), это отдельная задача.
 */
object SystemAlarm {

    data class Info(
        val triggerAt: Long,
        val creator: String,
        val isOurs: Boolean,
    )

    fun read(ctx: Context): Info? {
        return try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val clock = am.nextAlarmClock ?: return null
            val creator = try {
                clock.showIntent?.creatorPackage
            } catch (_: Throwable) {
                null
            } ?: "?"
            Info(clock.triggerTime, creator, creator == ctx.packageName)
        } catch (_: Throwable) {
            null
        }
    }
}
