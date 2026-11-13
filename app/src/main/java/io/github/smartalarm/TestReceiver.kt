package io.github.smartalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import java.io.File

class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, "actions.txt").appendText("${java.time.Instant.now()} $action\n")
        val app = context.applicationContext

        when (action) {
            ACTION_SENSORS -> run(app, dir, "Sensors") { Reports.sensorDump(app) }
            ACTION_HS -> run(app, dir, "HealthServices") { Reports.healthServices() }
            ACTION_HC -> run(app, dir, "HealthConnect") { Reports.healthConnect(app) }
            ACTION_PPG -> {
                val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                run(app, dir, "Ppg") { PpgTest.run(sm) }
            }
            ACTION_RAW -> {
                val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                run(app, dir, "PpgRaw") { RawPpg.capture(sm, dir, 60_000) }
            }
            ACTION_START_ECO -> startLog(app, NightLogService.MODE_ECO, false)
            ACTION_START_ECO_NW -> startLog(app, NightLogService.MODE_ECO, true)
            ACTION_START_FULL -> startLog(app, NightLogService.MODE_FULL, false)
            ACTION_STOP_LOG -> {
                app.startService(
                    Intent(app, NightLogService::class.java).setAction(NightLogService.ACTION_STOP)
                )
            }
            ACTION_STOP_ALARM -> AlarmPlayer.stop(app)
            ACTION_DUMP_LOG -> {
                File(dir, "screen_log.txt").writeText(UiLog.text())
            }
        }
    }

    private fun startLog(ctx: Context, mode: String, noWake: Boolean) {
        val svc = Intent(ctx, NightLogService::class.java).apply {
            action = NightLogService.ACTION_START
            putExtra(NightLogService.EXTRA_MODE, mode)
            putExtra(NightLogService.EXTRA_NOWAKELOCK, noWake)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
    }

    private fun run(ctx: Context, dir: File, name: String, block: () -> String) {
        Thread {
            val text = try {
                block()
            } catch (t: Throwable) {
                "Ошибка: $t\n${t.stackTraceToString()}"
            }
            try {
                File(dir, "report_$name.txt").writeText(text)
            } catch (_: Throwable) {
            }
            UiLog.add("=== $name ===")
            UiLog.add(text)
            try {
                File(dir, "done.txt").appendText("${java.time.Instant.now()} $name\n")
            } catch (_: Throwable) {
            }
        }.start()
    }

    companion object {
        const val ACTION_SENSORS = "io.github.smartalarm.RUN_SENSORS"
        const val ACTION_HS = "io.github.smartalarm.RUN_HS"
        const val ACTION_HC = "io.github.smartalarm.RUN_HC"
        const val ACTION_PPG = "io.github.smartalarm.RUN_PPG"
        const val ACTION_RAW = "io.github.smartalarm.RUN_RAW"
        const val ACTION_START_ECO = "io.github.smartalarm.START_ECO"
        const val ACTION_START_ECO_NW = "io.github.smartalarm.START_ECO_NW"
        const val ACTION_START_FULL = "io.github.smartalarm.START_FULL"
        const val ACTION_STOP_LOG = "io.github.smartalarm.STOP_LOG"
        const val ACTION_STOP_ALARM = "io.github.smartalarm.STOP_ALARM"
        const val ACTION_DUMP_LOG = "io.github.smartalarm.DUMP_LOG"
    }
}
