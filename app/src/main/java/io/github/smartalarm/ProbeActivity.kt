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

        btn("4 Старт: ЭКОНОМ (HR 1/5 мин)") { startLog(NightLogService.MODE_ECO) }
        btn("5 Старт: ПОЛНЫЙ (HR 1/мин)") { startLog(NightLogService.MODE_FULL) }
        btn("6 СТОП лог") { stopLog() }
        btn("7 Путь к файлам / итог") {
            append("running=${NightLogService.isRunning}")
            append("csv=${NightLogService.currentPath.ifEmpty { Prefs.path(this) }}")
            append("dir=${extDir().absolutePath}")
            append(readSummary())
        }

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

    private fun startLog(mode: String) {
        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_START
            putExtra(NightLogService.EXTRA_MODE, mode)
        }
        ContextCompat.startForegroundService(this, svc)
        append("Запуск лога: $mode")
        updateStatus()
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
