package io.github.smartalarm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.services.client.HealthServices
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

object Reports {

    val SLEEP_PERMISSION: String = HealthPermission.getReadPermission(SleepSessionRecord::class)

    fun sensorDump(ctx: Context): String {
        val sb = StringBuilder()
        try {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val all = sm.getSensorList(Sensor.TYPE_ALL).sortedBy { it.type }
            sb.appendLine("Всего сенсоров: ${all.size}")
            for (s in all) {
                sb.appendLine("[${s.type}] ${s.name}")
                sb.appendLine("    type=${s.stringType} vendor=${s.vendor} v${s.version}")
                sb.appendLine("    minDelay=${s.minDelay}us maxDelay=${s.maxDelay}us res=${s.resolution}")
                sb.appendLine("    wakeUp=${s.isWakeUpSensor} fifo=${s.fifoMaxEventCount}/${s.fifoReservedEventCount} reporting=${s.reportingMode}")
            }
            val hr = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            sb.appendLine()
            sb.appendLine("TYPE_HEART_RATE = ${hr?.name ?: "НЕТ"}")
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            sb.appendLine("TYPE_ACCELEROMETER = ${accel?.name ?: "НЕТ"}")
            val hrv = all.filter {
                it.stringType.contains("hrv", true) ||
                    it.stringType.contains("rr", true) ||
                    it.name.contains("HRV", true) ||
                    it.name.contains("PPG", true)
            }
            sb.appendLine("Похожие на HRV/PPG: ${hrv.map { "${it.name}(${it.stringType})" }}")
        } catch (t: Throwable) {
            sb.appendLine("Ошибка сенсоров: $t")
        }
        sb.appendLine("BOARD=${android.os.Build.BOARD} DEVICE=${android.os.Build.DEVICE} MODEL=${android.os.Build.MODEL}")
        sb.appendLine("SDK=${android.os.Build.VERSION.SDK_INT} RELEASE=${android.os.Build.VERSION.RELEASE}")
        return sb.toString()
    }

    private fun call(obj: Any?, name: String): Any? =
        try {
            obj?.javaClass?.methods?.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(obj)
        } catch (t: Throwable) {
            "ERR:$t"
        }

    fun healthServices(): String {
        val sb = StringBuilder()
        try {
            val client = HealthServices.getClient(AppCtx.get())
            sb.appendLine("client=${client.javaClass.simpleName}")
            val pm = call(client, "getPassiveMonitoringClient")
            if (pm == null) {
                sb.appendLine("passiveMonitoringClient = НЕТ")
            } else {
                val fut = call(pm, "getCapabilitiesAsync")
                val caps = try {
                    (fut as? java.util.concurrent.Future<*>)?.get(10, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    sb.appendLine("caps error: $t")
                    null
                }
                if (caps != null) {
                    sb.appendLine("--- PassiveMonitoringCapabilities ---")
                    sb.appendLine("dataTypes=${call(caps, "getSupportedDataTypesPassiveMonitoring")}")
                    sb.appendLine("goals=${call(caps, "getSupportedDataTypesPassiveGoals")}")
                    sb.appendLine("healthEvents=${call(caps, "getSupportedHealthEventTypes")}")
                    sb.appendLine("activityStates=${call(caps, "getSupportedUserActivityStates")}")
                }
            }
            val ex = call(client, "getExerciseClient")
            val exf = call(ex, "getCapabilitiesAsync")
            val excaps = try {
                (exf as? java.util.concurrent.Future<*>)?.get(10, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                sb.appendLine("exercise caps error: $t")
                null
            }
            if (excaps != null) {
                val types = call(excaps, "getSupportedExerciseTypes")
                sb.appendLine("exerciseTypes=${(types as? Collection<*>)?.size ?: types}")
            }
        } catch (t: Throwable) {
            sb.appendLine("Ошибка Health Services: $t")
        }
        return sb.toString()
    }

    fun healthConnect(ctx: Context): String {
        val sb = StringBuilder()
        try {
            val status = HealthConnectClient.getSdkStatus(ctx)
            sb.appendLine("sdkStatus=$status (2=AVAILABLE, 1=UNAVAILABLE, 3=NEEDS_UPDATE)")
            if (status != HealthConnectClient.SDK_AVAILABLE) {
                sb.appendLine("Health Connect на часах недоступен")
                return sb.toString()
            }
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = runBlocking { client.permissionController.getGrantedPermissions() }
            sb.appendLine("granted=$granted")
            if (SLEEP_PERMISSION !in granted) {
                sb.appendLine("Нет разрешения на сон — нажмите «HC: запросить сон»")
                return sb.toString()
            }
            val end = Instant.now()
            val start = end.minus(30, ChronoUnit.DAYS)
            val resp = runBlocking {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                    )
                )
            }
            sb.appendLine("Записей сна за 30 дней: ${resp.records.size}")
            resp.records.sortedByDescending { it.endTime }.take(12).forEach { r ->
                val mins = ChronoUnit.MINUTES.between(r.startTime, r.endTime)
                sb.appendLine("  ${r.startTime} -> ${r.endTime} (${mins} мин), этапов=${r.stages.size}, title=${r.title}")
                r.stages.take(8).forEach { st ->
                    sb.appendLine("      ${st.stage} ${st.startTime} -> ${st.endTime}")
                }
            }
        } catch (t: Throwable) {
            sb.appendLine("Ошибка Health Connect: $t")
        }
        return sb.toString()
    }
}

object AppCtx {
    private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }
    fun get(): Context = ctx ?: error("AppCtx not initialized")
}
