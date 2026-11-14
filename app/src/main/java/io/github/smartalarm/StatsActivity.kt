package io.github.smartalarm

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
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
    private lateinit var phasesView: TextView
    private lateinit var efficiencyView: TextView
    private lateinit var fileView: TextView

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val screenW: Float get() = resources.displayMetrics.widthPixels.toFloat()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        load()
    }

    override fun onResume() {
        super.onResume()
        // После слайд-перехода возвращаем контент в исходное положение
        if (::contentView.isInitialized) {
            contentView.translationX = 0f
            contentView.alpha = 1f
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.bg)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        root.addView(text("ПРОШЛАЯ НОЧЬ", 9f, Ui.ACCENT).apply { letterSpacing = 0.18f })

        chart = SleepChartView(this).apply { setBackgroundResource(R.drawable.bg_card) }
        root.addView(
            chart,
            // 65dp = 130px при 320dpi: остаётся место на сводку и кнопку без прокрутки
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(65))
                .apply { topMargin = dp(3) },
        )

        summaryView = text("загружаю…", 9f, Ui.TEXT_SECONDARY).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(4), dp(2), 0)
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        root.addView(summaryView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        phasesView = text("", 9f, Ui.ACCENT).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(3), dp(2), 0)
        }
        root.addView(phasesView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        efficiencyView = text("", 9f, Ui.TEXT_SECONDARY).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(1), dp(2), 0)
        }
        root.addView(efficiencyView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        fileView = text("", 8f, Ui.TEXT_TERTIARY).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(4), dp(2), 0)
        }
        root.addView(fileView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // По скиллу wear-os-ui (R1–R2): у нижней дуги круга ширина падает почти до нуля,
        // поэтому фиксированной кнопки внизу НЕТ — выход системным свайпом/кнопкой «назад»,
        // как в вендорских приложениях. Контент ограничен вписанным квадратом нашей модели.
        val inset = RoundScreen.inscribedInsetPx() + dp(10)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg)
            setPadding(inset, dp(44), inset, dp(18))
        }
        outer.addView(
            ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(Ui.bg)
                addView(root)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        co    // ---------- циклическая прокрутка (R11–R13) ----------
    private fun commitSwipe(dx: Float) {
        if (kotlin.math.abs(dx) < 90f) {
            revert()
            return
        }
        val toLeft = dx < 0
        contentView.animate()
            .translationX(if (toLeft) -screenW else screenW)
            .alpha(0.35f)
            .setDuration(140L)
            .withEndAction {
                finish()
                if (toLeft) {
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                } else {
                    overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
                }
            }
            .start()
    }

    private fun revert() {
        contentView.animate().translationX(0f).alpha(1f).setDuration(160L).start()
    }
ntentView = outer
        // Циклическая прокрутка (R11–R13): жест ловит контейнер, а не Activity
        val wrap = SwipeWrapLayout(this)
        wrap.addView(outer)
        wrap.onDrag = { dx ->
            contentView.translationX = dx
            contentView.alpha = 1f - 0.25f * (kotlin.math.abs(dx) / screenW)
        }
        wrap.onCommit = { dx -> commitSwipe(dx) }
        wrap.onCancel = { revert() }
        setContentView(wrap)
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

        // Оценка фаз по пульсу и движению (не медицинские N1/N2/N3/REM — см. SleepStats)
        phasesView.text = buildString {
            append("≈ глубокий ").append(hm(n.deepMin))
            append(" · лёгкий ").append(hm(n.lightMin))
            append(" · бодрств. ").append(hm(n.awakeMin))
        }
        efficiencyView.text = buildString {
            append("Эффективность ").append(n.efficiencyPct.toInt()).append('%')
            append(" · самый спокойный отрезок ").append(hm(n.longestQuietMin))
        }

        // Честная метрика качества данных: низкое покрытие = ночь не записана
        summaryView.append(
            "\nПокрытие лога: ${n.coveragePct.toInt()}%" +
                if (n.coveragePct < 95) "  ← неполная ночь" else "  ✓"
        )

        fileView.text = "файл: ${file.name}"
    }

    private fun hm(minutes: Int): String =
        if (minutes < 60) "${minutes}м" else "${minutes / 60}ч ${minutes % 60}м"

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
