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
import android.view.MotionEvent
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

    private lateinit var previewView: TextView
    private lateinit var statusView: TextView

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
        append("Готов. Начните с кнопки «Разрешения».")
    }

    override fun onResume() {
        super.onResume()
        if (AlarmPlayer.active) {
            AlarmPlayer.stop(this)
            append("Будильник остановлен открытием приложения")
        }
        updateStatus()
        refreshPreview()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setOnGenericMotionListener { v, e ->
            if (e.action == MotionEvent.ACTION_SCROLL) {
                v.scrollBy(0, (-e.getAxisValue(MotionEvent.AXIS_SCROLL) * dp(80)).toInt())
                true
            } else {
                false
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(14), dp(32), dp(40))
        }

        statusView = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 9f
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(statusView, lp())

        btn(root, "Разрешения") { append(permsReport()) }
        btn(root, "ЛОГ") {
            startActivity(Intent(this, LogActivity::class.java))
        }

        previewView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 8f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(4))
            height = dp(52)
        }
        root.addView(previewView, lp())

        section(root, "ДАТЧИКИ")
        btn(root, "1 Сенсоры") { runReport("Sensors") { Reports.sensorDump(this) } }
        btn(root, "2 Health Services") { runReport("HealthServices") { Reports.healthServices() } }
        btn(root, "3 Health Connect") { runReport("HealthConnect") { Reports.healthConnect(this) } }
        btn(root, "3b HC: запросить сон") {
            runCatching {
                hcLauncher.launch(setOf(Reports.SLEEP_PERMISSION))
            }.onFailure { append("HC launch error: $it") }
        }
        btn(root, "P Тест PPG (1 мин)") {
            val sm = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
            runReport("Ppg") { PpgTest.run(sm) }
        }
        btn(root, "R Запись PPG 60 с") {
            val sm = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
            runReport("PpgRaw") { RawPpg.capture(sm, extDir(), 60_000) }
        }

        section(root, "БУДИЛЬНИК")
        btn(root, "9 Будильник через 2 мин") { testAlarm() }
        btn(root, "9b Будильник СЕЙЧАС") { testAlarmNow() }

        section(root, "СИСТЕМНЫЙ HR / СОН")
        btn(root, "PASS старт") {
            Thread {
                append(HealthPassive.start(this))
                runOnUiThread { refreshPreview() }
            }.start()
        }
        btn(root, "PASS стоп") {
            Thread { append(HealthPassive.stop(this)) }.start()
        }
        btn(root, "PASS лог") {
            Thread { append(HealthLog.tail(this, 40)) }.start()
        }

        section(root, "НОЧНОЙ ЛОГ")
        btn(root, "4 Старт: ЭКОНОМ") { startLog(NightLogService.MODE_ECO, false) }
        btn(root, "4b ЭКОНОМ без wakelock") { startLog(NightLogService.MODE_ECO, true) }
        btn(root, "5 Старт: ПОЛНЫЙ") { startLog(NightLogService.MODE_FULL, false) }
        btn(root, "6 СТОП лог") { stopLog() }
        btn(root, "7 Путь / итог") {
            append("running=${NightLogService.isRunning}")
            append("csv=${NightLogService.currentPath.ifEmpty { Prefs.path(this) }}")
            append("dir=${extDir().absolutePath}")
            append(readSummary())
        }
        btn(root, "8 Сводка по логу") {
            Thread {
                val f = Summary.latestCsv(extDir())
                val text = if (f == null) "нет CSV в ${extDir().absolutePath}" else Summary.build(f)
                runOnUiThread { append("=== Сводка ===\n$text") }
            }.start()
        }

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun section(parent: LinearLayout, title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#80CBC4"))
            textSize = 9f
            setPadding(0, dp(10), 0, dp(2))
        }
        parent.addView(tv, lp())
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(4) }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun btn(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            textSize = 10.5f
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { onClick() }
        }
        parent.addView(b, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { bottomMargin = dp(5) })
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
        val grants = listOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BODY_SENSORS_BACKGROUND,
        ).joinToString(" ") { p ->
            val ok = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
            "${p.substringAfterLast('.').replace("BODY_", "")}=${if (ok) "OK" else "NO"}"
        }
        statusView.text = "$grants\n${installInfo()} | log=${NightLogService.isRunning}"
    }

    private fun installInfo(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "v${pi.versionName}"
    } catch (t: Throwable) {
        "v?"
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
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        sb.appendLine("notificationsEnabled = ${nm.areNotificationsEnabled()}")
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

    private fun testAlarmNow() {
        append("Будильник: СЕЙЧАС")
        sendBroadcast(Intent(this, AlarmTestReceiver::class.java))
    }

    private fun startLog(mode: String, noWake: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            append("Нет BODY_SENSORS — запрашиваю")
            permLauncher.launch(
                arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.POST_NOTIFICATIONS)
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
            append("Будильник на ${java.time.Instant.ofEpochMilli(at)}")
        } catch (t: Throwable) {
            append("setAlarmClock: $t — обычный")
            try {
                am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi)
                append("Обычный на ${java.time.Instant.ofEpochMilli(at)}")
            } catch (t2: Throwable) {
                append("Не поставился: $t2")
            }
        }
    }

    private fun stopLog() {
        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_STOP
        }
        startService(svc)
        append("Стоп лога")
        updateStatus()
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
            runOnUiThread {
                append("=== $name ===")
                append(text)
            }
        }.start()
    }

    private fun append(line: String) {
        UiLog.add(line)
        runOnUiThread { refreshPreview() }
    }

    private fun refreshPreview() {
        if (!::previewView.isInitialized) return
        val all = UiLog.text().trimEnd('\n').lines()
        previewView.text = all.takeLast(3).joinToString("\n")
    }
}
