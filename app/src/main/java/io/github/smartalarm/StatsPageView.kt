package io.github.smartalarm

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Вторая страница карусели: «прошлая ночь» — график в духе OHealth и сводка.
 *
 * Живёт как обычная View внутри [CyclePager], без собственной Activity: так жест
 * листания не конфликтует с системным «назад» и с переходами между окнами.
 */
class StatsPageView(context: Context) : LinearLayout(context) {

    private val chart: SleepChartView
    private val summaryView: TextView
    private val phasesView: TextView
    private val efficiencyView: TextView
    private val fileView: TextView

    private val main = Handler(Looper.getMainLooper())
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Ui.bg)
        setPadding(dp(2), dp(2), dp(2), dp(2))

        addView(TextView(context).apply {
            text = "ПРОШЛАЯ НОЧЬ"
            textSize = 9f
            letterSpacing = 0.18f
            typeface = Ui.labelTypeface()
            setTextColor(Ui.accent)
            gravity = Gravity.CENTER
        })

        chart = SleepChartView(context).apply { setBackgroundResource(R.drawable.bg_card) }
        addView(
            chart,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
                .apply { topMargin = dp(3) },
        )

        summaryView = label("загружаю…", 8f, Ui.TEXT_SECONDARY).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(4), dp(2), 0)
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        addView(summaryView)

        phasesView = label("", 8f, Ui.ACCENT).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(3), dp(2), 0)
        }
        addView(phasesView)

        efficiencyView = label("", 8f, Ui.TEXT_SECONDARY).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(1), dp(2), 0)
        }
        addView(efficiencyView)

        fileView = label("", 8f, Ui.TEXT_TERTIARY).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(4), dp(2), 0)
        }
        addView(fileView)
    }

    fun reload() {
        Thread {
            val file = bestProbeFile()
            val night = try {
                file?.let { f -> SleepStats.summarize(SleepStats.parse(f.readLines())) }
            } catch (_: Throwable) {
                null
            }
            main.post { render(file, night) }
        }.start()
    }

    private fun render(file: File?, n: SleepStats.Night?) {
        if (file == null || n == null) {
            chart.setNight(null)
            summaryView.text = "Нет ночного лога.\nЗапусти ночь на главном экране —\n" +
                "утром здесь появится график."

            phasesView.text = ""
            efficiencyView.text = ""
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
        val hrLine = if (n.hrMin > 0) "Пульс: ${n.hrMin}–${n.hrMax}, средний ${n.hrAvg}"
        else "Пульс: нет данных"

        summaryView.text = buildString {
            append("${timeFmt.format(Instant.ofEpochMilli(n.startTs))}–")
            append("${timeFmt.format(Instant.ofEpochMilli(n.endTs))} · ")
            append("${durationMin / 60}ч ${durationMin % 60}м · окон ${n.rows}\n")
            append(hrLine).append(" · пульс в ").append(SleepStats.hrCoveragePct(n).toInt()).append("% окон\n")
            append("Пробуждений: ").append(n.awakenings)
            append(" · движение ").append(n.motionPct.toInt()).append("% окон\n")
            append("Батарея: ").append(battery)
            if (n.alarmFired) append(" · будильник сработал")
            append("\nПокрытие лога: ").append(n.coveragePct.toInt()).append('%')
            if (n.coveragePct < 95) append("  ← неполная ночь")
        }

        phasesView.text = "≈ глубокий ${hm(n.deepMin)} · лёгкий ${hm(n.lightMin)} · бодрств. ${hm(n.awakeMin)}"
        efficiencyView.text = "Эффективность ${n.efficiencyPct.toInt()}% · спокойный отрезок ${hm(n.longestQuietMin)}"
        fileView.text = "файл: ${file.name}"
    }

    private fun hm(minutes: Int): String =
        if (minutes < 60) "${minutes}м" else "${minutes / 60}ч ${minutes % 60}м"

    private fun bestProbeFile(): File? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("probe_") && it.name.endsWith(".csv") }
            ?.maxWithOrNull(compareBy<File> { it.length() }.thenBy { it.lastModified() })
    }

    private fun label(value: String, size: Float, color: String): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
