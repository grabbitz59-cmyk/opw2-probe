package io.github.smartalarm

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.data.UserActivityInfo
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class PassiveService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        HealthLog.recordData(applicationContext, dataPoints)
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        HealthLog.recordActivity(applicationContext, info)
    }

    override fun onPermissionLost() {
        HealthLog.raw(applicationContext, "EVENT,permission_lost")
    }
}

object HealthState {
    @Volatile var hr: Float = -1f
        private set
    @Volatile var hrTs: Long = 0
        private set
    @Volatile var hrAccuracy: String = ""
        private set
    /** sensor | passive | none — откуда пришло последнее валидное значение */
    @Volatile var hrSource: String = "none"
        private set
    @Volatile var state: String = "?"
        private set
    @Volatile var stateTs: Long = 0
        private set

    /**
     * @param source "sensor" — собственный TYPE_HEART_RATE, "passive" — Health Services.
     * Раньше сюда писал только passive-путь, из-за чего AlarmLogic видел hr=null,
     * пока passive monitoring молчал.
     */
    fun updateHr(v: Float, ts: Long, acc: String, source: String = "passive") {
        if (v > 0f) {
            hr = v
            hrTs = ts
            hrAccuracy = acc
            hrSource = source
        }
    }

    /** Возвращает источник свежего HR или "none", если данных нет. */
    fun sourceNow(maxAgeMs: Long = 300_000L): String =
        if (hr > 0f && System.currentTimeMillis() - hrTs < maxAgeMs) hrSource else "none"

    fun updateState(s: String, ts: Long) {
        state = s
        stateTs = ts
    }

    fun freshHr(maxAgeMs: Long = 300_000L): Float? =
        if (hr > 0f && System.currentTimeMillis() - hrTs < maxAgeMs) hr else null

    fun freshestate(maxAgeMs: Long = 900_000L): String? =
        if (state != "?" && System.currentTimeMillis() - stateTs < maxAgeMs) state else null
}

object HealthLog {
    private const val FILE = "health_log.csv"
    private val lock = Any()

    private fun file(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, FILE)
        if (!f.exists()) f.writeText("ts,epoch_ms,kind,value,extra\n")
        return f
    }

    fun raw(ctx: Context, line: String) {
        synchronized(lock) {
            try {
                file(ctx).appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
    }

    fun recordData(ctx: Context, container: DataPointContainer) {
        try {
            val hr = container.getData(DataType.HEART_RATE_BPM)
            val hrPoints = hr.filterIsInstance<SampleDataPoint<*>>()
            for (p in hrPoints) {
                val ts = Instant.now().toString()
                val ms = System.currentTimeMillis()
                val v = (p.value as? Number)?.toFloat() ?: -1f
                val bootMs = p.timeDurationFromBoot?.toMillis() ?: -1
                HealthState.updateHr(v, ms, p.accuracy.toString())
                synchronized(lock) {
                    file(ctx).appendText("$ts,$ms,hr,$v,boot=${bootMs}acc=${p.accuracy}\n")
                }
            }
            if (hrPoints.isEmpty()) {
                val types = container.dataTypes.joinToString(";") { it.name }
                raw(ctx, "${Instant.now()},${System.currentTimeMillis()},nohr,,types=$types")
            }
        } catch (t: Throwable) {
            raw(ctx, "${Instant.now()},${System.currentTimeMillis()},error,,$t")
        }
    }

    fun recordActivity(ctx: Context, info: UserActivityInfo) {
        try {
            val state = info.userActivityState
            val ts = Instant.now().toString()
            HealthState.updateState(state.name, System.currentTimeMillis())
            if (state.name == "USER_ACTIVITY_ASLEEP") {
                AlarmLogic.onAsleep(ctx)
            } else {
                AlarmLogic.onArousalSignal(ctx, "->${state.name}")
            }
            raw(
                ctx,
                "$ts,${System.currentTimeMillis()},activity,${state.name},since=${info.stateChangeTime}"
            )
        } catch (t: Throwable) {
            raw(ctx, "${Instant.now()},${System.currentTimeMillis()},error,,$t")
        }
    }

    fun tail(ctx: Context, n: Int = 40): String = try {
        file(ctx).readLines().takeLast(n).joinToString("\n")
    } catch (t: Throwable) {
        "нет лога: $t"
    }
}

private fun prop(obj: Any?, suffix: String): Any? =
    try {
        if (obj == null) "null"
        else obj.javaClass.methods.firstOrNull { it.name == "get$suffix" && it.parameterCount == 0 }?.invoke(obj)
    } catch (t: Throwable) {
        "ERR:$t"
    }

object HealthPassive {

    fun start(ctx: Context): String {
        val sb = StringBuilder()
        val client = try {
            HealthServices.getClient(ctx)
        } catch (t: Throwable) {
            return "нет клиента Health Services: $t"
        }
        val pm = client.passiveMonitoringClient

        val variants = listOf<Pair<String, PassiveListenerConfig>>(
            "hr+activity" to PassiveListenerConfig.Builder()
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .setShouldUserActivityInfoBeRequested(true)
                .build(),
            "hr" to PassiveListenerConfig.Builder()
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .build(),
        )

        for ((name, cfg) in variants) {
            try {
                val caps = pm.setPassiveListenerServiceAsync(PassiveService::class.java, cfg)
                    .get(20, TimeUnit.SECONDS)
                sb.appendLine("OK variant=$name")
                sb.appendLine("dataTypes=${prop(caps, "SupportedDataTypesPassiveMonitoring")}")
                sb.appendLine("goals=${prop(caps, "SupportedDataTypesPassiveGoals")}")
                sb.appendLine("activityStates=${prop(caps, "SupportedUserActivityStates")}")
                HealthLog.raw(ctx, "${Instant.now()},${System.currentTimeMillis()},event,,passive_started:$name")
                return sb.toString()
            } catch (t: Throwable) {
                sb.appendLine("FAIL variant=$name: $t")
                sb.appendLine(t.stackTraceToString().take(1200))
                try {
                    pm.clearPassiveListenerServiceAsync().get(10, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                }
            }
        }
        return sb.toString()
    }

    fun stop(ctx: Context): String {
        return try {
            val client = HealthServices.getClient(ctx)
            client.passiveMonitoringClient.clearPassiveListenerServiceAsync().get(15, TimeUnit.SECONDS)
            HealthLog.raw(ctx, "${Instant.now()},${System.currentTimeMillis()},event,,passive_stopped")
            "PassiveMonitoring остановлен"
        } catch (t: Throwable) {
            "Ошибка стопа: $t"
        }
    }
}
