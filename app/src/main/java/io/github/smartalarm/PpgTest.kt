package io.github.smartalarm

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PpgTest {

    private data class Res(
        val type: Int,
        val name: String,
        val stringType: String,
        val registered: Boolean,
        val events: Int,
        val hz: Double,
        val valueSize: Int,
        val samples: List<Float>,
        val error: String?,
    )

    fun run(sm: SensorManager, durationMs: Long = 8000): String {
        val targets = sm.getSensorList(Sensor.TYPE_ALL).filter {
            it.stringType.startsWith("com.google.wear.sensor") ||
                it.type == Sensor.TYPE_HEART_RATE ||
                it.type == Sensor.TYPE_ACCELEROMETER
        }.sortedBy { it.stringType }

        val sb = StringBuilder()
        sb.appendLine("PPG/вендорные сенсоры: ${targets.size}, тест по ${durationMs / 1000} с каждый")
        sb.appendLine()

        for (s in targets) {
            val r = try {
                probe(sm, s, durationMs)
            } catch (t: Throwable) {
                Res(s.type, s.name, s.stringType, false, 0, 0.0, 0, emptyList(), t.toString())
            }
            sb.append("[${r.type}] ${r.name} (${r.stringType})")
            sb.appendLine()
            if (r.error != null) {
                sb.appendLine("    ERROR: ${r.error}")
            } else if (!r.registered) {
                sb.appendLine("    регистрация отклонена (registerListener=false)")
            } else {
                val hz = String.format(java.util.Locale.US, "%.1f", r.hz)
                sb.appendLine("    events=${r.events} hz=$hz values/event=${r.valueSize}")
                if (r.samples.isNotEmpty()) {
                    sb.appendLine("    первые значения: ${r.samples.joinToString(", ") { fmt(it) }}")
                }
                if (r.events == 0) sb.appendLine("    ВАЖНО: событий нет — сенсор закрыт для сторонних приложений")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun fmt(v: Float): String =
        String.format(java.util.Locale.US, "%.4g", v)

    private fun probe(sm: SensorManager, sensor: Sensor, durationMs: Long): Res {
        val thread = HandlerThread("ppg-${sensor.type}")
        thread.start()
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var events = 0
        var firstTs = 0L
        var lastTs = 0L
        var valueSize = 0
        val samples = ArrayList<Float>(16)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (events == 0) {
                    firstTs = e.timestamp
                    valueSize = e.values.size
                    for (i in 0 until minOf(4, e.values.size)) samples.add(e.values[i])
                }
                lastTs = e.timestamp
                events++
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val registered = sm.registerListener(listener, sensor, 20_000, handler)
        if (registered) {
            handler.postDelayed({ latch.countDown() }, durationMs)
            latch.await(durationMs + 5000, TimeUnit.MILLISECONDS)
            sm.unregisterListener(listener, sensor)
        }
        thread.quitSafely()

        val secs = if (events > 1) (lastTs - firstTs) / 1e9 else 0.0
        val hz = if (secs > 0) (events - 1) / secs else 0.0
        return Res(sensor.type, sensor.name, sensor.stringType, registered, events, hz, valueSize, samples, null)
    }
}
