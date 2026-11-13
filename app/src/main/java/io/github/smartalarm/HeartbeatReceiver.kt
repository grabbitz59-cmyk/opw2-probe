package io.github.smartalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Heartbeat против «засыпания» ночного лога.
 *
 * Наблюдалось на OPW2: при работающем FGS `Wake Locks: size=0`, `mWakefulness=Asleep`,
 * и `Handler.postDelayed` (считается по uptime, который в suspend не идёт) срабатывает
 * с задержкой 39–121 с вместо 30, а за ночь покрытие падало до 21%.
 *
 * Точный alarm даёт системе повод разбудить приложение и, главное, временный allowlist
 * (temporaryAppAllowlist), который разрешает старт FGS из фона. Поэтому здесь:
 *   - сервис жив  → просим немедленно сбросить бакет (ACTION_FLUSH);
 *   - сервис умер → поднимаем его заново (allowlist это позволяет);
 *   - будильник потерял состояние после перезапуска процесса → перевзводим из Prefs.
 */
class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AlarmLogic.targetAt <= 0) {
            val stored = Prefs.alarmAt(context)
            if (stored > System.currentTimeMillis()) AlarmLogic.restoreFromPrefs(context)
        }

        if (!Prefs.isRunning(context)) return

        val svc = Intent(context, NightLogService::class.java)
        try {
            if (NightLogService.isRunning) {
                svc.action = NightLogService.ACTION_FLUSH
                context.startService(svc)
            } else {
                svc.action = NightLogService.ACTION_START
                svc.putExtra(NightLogService.EXTRA_MODE, Prefs.mode(context))
                svc.putExtra(NightLogService.EXTRA_NOWAKELOCK, Prefs.noWake(context))
                ContextCompat.startForegroundService(context, svc)
                AlarmLogic.log(context, "heartbeat: сервис был мёртв, поднимаю заново")
            }
        } catch (t: Throwable) {
            AlarmLogic.log(context, "heartbeat failed: $t")
        }
    }
}
