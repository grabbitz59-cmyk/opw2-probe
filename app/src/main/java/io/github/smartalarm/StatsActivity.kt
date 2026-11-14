package io.github.smartalarm

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Прошлая ночь»: график пульса/движения + сводка по последнему probe_*.csv.
 *
 * Полезное здесь — не только картинка: покрытие лога и доля окон со свежим пульсом
 * показывают, насколько данным вообще можно верить (ночь с покрытием 14 % — не ночь).
 */
class StatsActivity : ComponentActivity() {

    private lateinit var chart: SleepChartView
    private lateinit var summaryView: TextView
    private lateinit var fileView: TextView

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        load()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0A0E10"))
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        root.addView(text("ПРОШЛАЯ НОЧЬ", 9f, "#546E7A").apply { letterSpacing = 0.16f })

        chart = SleepChartView(this)
        root.addView(
            chart,
            // 65dp = 130px при 320dpi: остаётся место на сводку и кнопку без прокрутки
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(65))
                .apply { topMargin = dp(3) },
        )

        summaryView = text("загружаю…", 9f, "#B0BEC5").apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(4), dp(2), 0)
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        root.addView(summaryView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        fileView = text("", 8f, "#546E7A").apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(4), dp(2), 0)
        }
        root.addView(fileView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val close = TextView(this).apply {
            text = "ЗАКРЫТЬ"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#04211D"))
            setBackgroundResource(R.drawable.bg_cta)
            setPadding(0, dp(7), 0, dp(7))
            setOnClickListener { finish() }
        }

        // Кнопка — в фиксированном подвале вне скролла: контент бывает выше экрана,
        // и тогда ScrollView подрезал её у самой круглой кромки.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E10"))
            setPadding(dp(12), dp(8), dp(12), dp(14))
        }
        outer.addView(
            ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(Color.parseColor("#0A0E10"))
                addView(root)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        outer.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        setContentView(outer)
    }

    private fun load() {
        Thread {
            val file = latestProbeFile()
            val night = try {
                file?.let { f -> SleepStats.summarize(SleepStats.parse(f.readLines())) }
            } catch (_: Throwable) {
                null
            }
            runOnUiThread { render(file, night) }
        }.start()
    }

    private fun render(file: File?, n: SleepStats.Night?) {
        if (file == null || n == null) {
            chart.setNight(null)
            summaryView.text = "Нет ночного лога.\nЗапусти ночь на главном экране —\n" +
                "утром здесь появится график пульса и движений."
            fileView.text = ""
            return
        }

        chart.setNight(n)
        val durationMin = (n.endTs - n.startTs) / 60000
        val battery = if (n.batteryStart >= 0 && n.batteryEnd >= 0) {
            "${n.batteryStart}% → ${n.batteryEnd}% (−${n.batteryStart - n.batteryEnd}%)"
        } else {
            "н/д"
        }
        val hrLine = if (n.hrMin > 0) {
            "Пульс: ${n.hrMin}–${n.hrMax}, средний ${n.hrAvg}"
        } else {
            "Пульс: нет данных"
        }
        val hrCov = SleepStats.hrCoveragePct(n)

        val sb = StringBuilder()
        sb.appendLine("${timeFmt.format(Instant.ofEpochMilli(n.startTs))}–" +
            "${timeFmt.format(Instant.ofEpochMilli(n.endTs))} · " +
            "${durationMin / 60}ч ${durationMin % 60}м · окон ${n.rows}")
        sb.appendLine(hrLine + " · окна с пульсом ${hrCov.toInt()}%")
        sb.appendLine("Пробуждений: ${n.awakenings} · движение ${n.motionPct.toInt()}% окон")
        sb.appendLine("Сон подтверждён: ${n.asleepPct.toInt()}% окон")
        sb.appendLine("Батарея: $battery" + if (n.alarmFired) " · будильник сработал" else "")
        summaryView.text = sb.toString().trim()

        // Честная метрика качества данных: низкое покрытие = ночь не записана
        summaryView.append(
            "\nПокрытие лога: ${n.coveragePct.toInt()}%" +
                if (n.coveragePct < 95) "  ← неполная ночь" else "  ✓"
        )

        fileView.text = "файл: ${file.name}"
    }

    /**
     * Берём самый ИНФОРМАТИВНЫЙ файл, а не самый свежий: после коротких тестовых
     * прогонов «последним» оказывается файл на 2 строки, и график показывает пустоту.
     */
    private fun latestProbeFile(): File? {
        val dir = getExternalFilesDir(null) ?: filesDir
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("probe_") && it.name.endsWith(".csv") }
            ?.maxWithOrNull(compareBy<File> { it.length() }.thenBy { it.lastModified() })
    }

    private fun text(value: String, size: Float, color: String): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(color))
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
