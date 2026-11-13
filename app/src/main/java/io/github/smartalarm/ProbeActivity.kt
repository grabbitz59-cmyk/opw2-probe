package io.github.smartalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import java.io.File

class ProbeActivity : ComponentActivity() {

    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var root: LinearLayout

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        append("Разрешения: $result")
        updateStatus()
    }

    private val hcLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        append("HC выдано: $granted")
        if (granted.isNotEmpty()) runReport("HealthConnect") { Reports.healthConnect(this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(applicationContext)
        buildUi()
        requestBasePermissions()
        append("Готов. Сначала «1 Сенсоры» и «2 Health Services».")
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        statusView = TextView(this).apply {
            setTextColor(Color.GRAY)
            textSize = 10f
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(statusView, lp())

        btn("1 Сенсоры") { runReport("Sensors") { Reports.sensorDump(this) } }
        btn("2 Health Services") { runReport("HealthServices") { Reports.healthServices() } }
        btn("3 Health Connect: статус") { runReport("HealthConnect") { Reports.healthConnect(this) } }
        btn("3b HC: запросить сон") {
            runCatching {
                hcLauncher.launch(setOf(Reports.SLEEP_PERMISSION))
            }.onFailure { append("HC launch error: $it") }
        }

        btn("4 Старт: ЭКОНОМ (HR 1/5 мин)") { startLog(NightLogService.MODE_ECO, false) }
        btn("5 Старт: ПОЛНЫЙ (HR 1/мин)") { startLog(NightLogService.MODE_FULL, false) }
        btn("4b ЭКОНОМ без wakelock") { startLog(NightLogService.MODE_ECO, true) }
        btn("6 СТОП лог") { stopLog() }
        btn("7 Путь к файлам / итог") {
            append("running=${NightLogService.isRunning}")
            append("csv=${NightLogService.currentPath.ifEmpty { Prefs.path(this) }}")
            append("dir=${extDir().absolutePath}")
            append(readSummary())
        }
        btn("8 Сводка по последнему логу") {
            Thread {
                val f = Summary.latestCsv(extDir())
                val text = if (f == null) "нет CSV в ${extDir().absolutePath}" else Summary.build(f)
                runOnUiThread { append("=== Сводка ===\n$text") }
            }.start()
        }
        btn("9 Тест будильника через 2 мин") { testAlarm() }
        btn("P Тест PPG / вендорных сенсоров") {
            val sm = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
            runReport("Ppg") { PpgTest.run(sm, 8000) }
        }
        btn("10 Разрешения и статус") { append(permsReport()) }

        val logScroll = ScrollView(this)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        }
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(4) }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun btn(label: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { onClick() }
        }
        root.addView(b, lp())
    }

    private fun requestBasePermissions() {
        val need = mutableListOf<String>()
        val wanted = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.BODY_SENSORS_BACKGROUND)
        }
        for (p in wanted) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p)
        }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }

    private fun updateStatus() {
        val portraits = listOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BODY_SENSORS_BACKGROUND,
        ).joinToString(" ") { p ->
            val ok = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
            "${p.substringAfterLast('.')}=${if (ok) "OK" else "NO"}"
        }
        statusView.text = "$portraits | log=${NightLogService.isRunning}"
    }

    private fun startLog(mode: String, noWake: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            append("Сначала выдайте BODY_SENSORS: запрашиваю…")
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.BODY_SENSORS,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            )
            return
        }
        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_START
            putExtra(NightLogService.EXTRA_MODE, mode)
            putExtra(NightLogService.EXTRA_NOWAKELOCK, noWake)
        }
        ContextCompat.startForegroundService(this, svc)
        append("Запуск лога: $mode${if (noWake) " (без wakelock)" else ""}")
        updateStatus()
    }

    private fun testAlarm() {
        val am = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = android.app.PendingIntent.getBroadcast(
            this, 77,
            Intent(this, AlarmTestReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + 120_000
        try {
            am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(at, pi), pi)
            append("Будильник поставлен на ${java.time.Instant.ofEpochMilli(at)} (через 2 мин)")
        } catch (t: Throwable) {
            append("setAlarmClock не сработал: $t — ставлю обычный")
            try {
                am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                append("Обычный будильник на ${java.time.Instant.ofEpochMilli(at)}")
            } catch (t2: Throwable) {
                append("Будильник не поставился: $t2")
            }
        }
        append("Теперь можно свернуть приложение или переключить часы в энергосбережение и ждать вибрации.")
    }

    private fun stopLog() {
        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_STOP
        }
        startService(svc)
        append("Стоп лога")
        updateStatus()
    }

    private fun permsReport(): String {
        val sb = StringBuilder()
        val wanted = listOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.BODY_SENSORS_BACKGROUND,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.VIBRATE,
        )
        for (p in wanted) {
            val ok = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
            sb.appendLine("${p.substringAfterLast('.')} = ${if (ok) "OK" else "НЕТ"}")
        }
        val am = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        sb.appendLine("exactAlarms = ${if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true}")
        sb.appendLine("running = ${NightLogService.isRunning}")
        sb.appendLine("dir = ${extDir().absolutePath}")
        sb.appendLine("errorLog:")
        sb.appendLine(readErrorLog())
        return sb.toString()
    }

    private fun readErrorLog(): String = try {
        File(extDir(), "probe_error.txt").readText()
    } catch (_: Throwable) {
        "нет"
    }

    private fun extDir(): File = getExternalFilesDir(null) ?: filesDir

    private fun readSummary(): String = try {
        File(extDir(), "summary.txt").readText()
    } catch (t: Throwable) {
        "summary: $t"
    }

    private fun runReport(name: String, block: () -> String) {
        append("… $name")
        Thread {
            val text = try {
                block()
            } catch (t: Throwable) {
                "Ошибка: $t"
            }
            try {
                File(extDir(), "report_$name.txt").writeText(text)
            } catch (_: Throwable) {
            }
            runOnUiThread { append("=== $name ===\n$text") }
        }.start()
    }

    private fun append(line: String) {
        val old = logView.text?.toString().orEmpty()
        val merged = if (old.length > 20_000) old.takeLast(8_000) else old
        logView.text = "$merged$line\n"
        (logView.parent as? View)?.let { v ->
            (v as? ScrollView)?.post { v.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
