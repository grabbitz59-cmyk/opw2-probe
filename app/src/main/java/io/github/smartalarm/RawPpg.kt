package io.github.smartalarm

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object RawPpg {

    private val SENSOR_TYPES = listOf(
        "com.google.wear.sensor.PPG_RAW_IR0",
        "com.google.wear.sensor.PPG_RAW_R0",
        "com.google.wear.sensor.PPG_RAW_G0",
    )

    fun capture(sm: SensorManager, dir: File, durationMs: Long = 60_000): String {
        val sensors = sm.getSensorList(Sensor.TYPE_ALL).filter { it.stringType in SENSOR_TYPES }
        if (sensors.isEmpty()) return "Нет доступа к PPG_RAW_* сенсорам"

        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "ppg_raw_${System.currentTimeMillis()}.csv")
        val w = BufferedWriter(FileWriter(file))
        w.write("# raw PPG capture, duration_ms=$durationMs, sensors=${sensors.map { it.stringType }}")
        w.newLine()
        w.write("ts_ns,sensor,v0;v1;v2;v3;v4;v5;v6;v7;v8;v9;v10;v11;v12;v13;v14;v15")
        w.newLine()

        val thread = HandlerThread("ppg-raw")
        thread.start()
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        val counts = HashMap<String, Int>()
        val first = HashMap<String, Long>()
        val last = HashMap<String, Long>()
        val values = HashMap<String, Int>()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val name = e.sensor.stringType
                counts[name] = (counts[name] ?: 0) + 1
                if (!first.containsKey(name)) first[name] = e.timestamp
                last[name] = e.timestamp
                values[name] = e.values.size
                try {
                    val sb = StringBuilder(160)
                    sb.append(e.timestamp).append(',').append(name.substringAfterLast('.'))
                    for (v in e.values) sb.append(',').append(String.format(java.util.Locale.US, "%.0f", v))
                    w.write(sb.toString())
                    w.newLine()
                } catch (_: Throwable) {
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        var registered = 0
        for (s in sensors) {
            if (sm.registerListener(listener, s, 20_000, handler)) registered++
        }
        if (registered == 0) {
            thread.quitSafely()
            try { w.close() } catch (_: Throwable) {}
            return "Ни один PPG_RAW сенсор не открылся"
        }

        handler.postDelayed({ latch.countDown() }, durationMs)
        latch.await(durationMs + 10_000, TimeUnit.MILLISECONDS)
        sm.unregisterListener(listener)
        thread.quitSafely()
        try { w.flush(); w.close() } catch (_: Throwable) {}

        val sb = StringBuilder()
        sb.appendLine("Файл: ${file.absolutePath}")
        sb.appendLine("Сенсоров открыто: $registered из ${sensors.size}")
        for ((name, n) in counts) {
            val secs = ((last[name] ?: 0L) - (first[name] ?: 0L)) / 1e9
            val hz = if (secs > 0) n / secs else 0.0
            sb.appendLine("  ${name.substringAfterLast('.')}: events=$n hz=${String.format(java.util.Locale.US, "%.1f", hz)} values=${values[name]}")
        }
        sb.appendLine("Размер: ${file.length() / 1024} КБ")
        return sb.toString()
    }
}
