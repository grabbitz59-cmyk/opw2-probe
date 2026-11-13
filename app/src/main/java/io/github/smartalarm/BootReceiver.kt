package io.github.smartalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Восстановление состояния после перезагрузки и обновления приложения.
 *
 * Было: обрабатывался только BOOT_COMPLETED, и будильник восстанавливался лишь
 * попутно из `NightLogService.onStartCommand` — то есть «по случаю».
 * Стало: будильник перевзводится из Prefs при загрузке, обновлении APK и смене
 * времени/пояса; ночной лог поднимается, только если он был запущен.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AlarmLogic.restoreFromPrefs(context)
                if (Prefs.isRunning(context)) startLog(context)
            }

            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                // targetAt — абсолютный epoch: после смены времени его надо перевзвести
                AlarmLogic.restoreFromPrefs(context)
            }
        }
    }

    private fun startLog(context: Context) {
        val svc = Intent(context, NightLogService::class.java).apply {
            action = NightLogService.ACTION_START
            putExtra(NightLogService.EXTRA_MODE, Prefs.mode(context))
            putExtra(NightLogService.EXTRA_NOWAKELOCK, Prefs.noWake(context))
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (t: Throwable) {
            AlarmLogic.log(context, "boot: не удалось поднять лог: $t")
        }
    }
}
