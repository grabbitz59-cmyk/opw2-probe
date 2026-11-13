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
                val v = p.value
                val bootMs = p.timeDurationFromBoot?.toMillis() ?: -1
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

private fun prop(obj: Any, suffix: String): Any? =
    try {
        obj.javaClass.methods.firstOrNull { it.name == "get$suffix" && it.parameterCount == 0 }?.invoke(obj)
    } catch (t: Throwable) {
        "ERR:$t"
    }

object HealthPassive {

    fun start(ctx: Context): String {
        return try {
            val client = HealthServices.getClient(ctx)
            val pm = client.passiveMonitoringClient
            val config = PassiveListenerConfig.Builder()
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .setShouldUserActivityInfoBeRequested(true)
                .build()
            val caps = pm.setPassiveListenerServiceAsync(PassiveService::class.java, config)
                .get(20, TimeUnit.SECONDS)
            HealthLog.raw(ctx, "${Instant.now()},${System.currentTimeMillis()},event,,passive_started")
            buildString {
                appendLine("PassiveMonitoring стартовал")
                appendLine("dataTypes=${prop(caps, "SupportedDataTypesPassiveMonitoring")}")
                appendLine("goals=${prop(caps, "SupportedDataTypesPassiveGoals")}")
                appendLine("activityStates=${prop(caps, "SupportedUserActivityStates")}")
            }
        } catch (t: Throwable) {
            HealthLog.raw(ctx, "${Instant.now()},${System.currentTimeMillis()},error,,start $t")
            "Ошибка старта PassiveMonitoring: $t"
        }
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
