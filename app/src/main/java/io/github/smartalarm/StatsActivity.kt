package io.github.smartalarm

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * «Прошлая ночь»: график в духе OHealth (полоса фаз + пульс + движение) и сводка.
 *
 * Полезное здесь не только картинка: покрытие лога и доля окон со свежим пульсом
 * показывают, насколько данным можно верить.
 */
class StatsActivity : ComponentActivity() {

    private lateinit var chart: SleepChartView
    private lateinit var summaryView: TextView
    private lateinit var phasesView: TextView
    private lateinit var efficiencyView: TextView
    private lateinit var fileView: TextView
    private lateinit var contentView: LinearLayout

    private val screenW: Float get() = resources.displayMetrics.widthPixels.toFloat()

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::contentView.isInitialized) {
            contentView.translationX = 0f
            contentView.alpha = 1f
        }
    }

    // ---------- циклическая прокрутка (R11–R13) ----------
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var dragging = false

    /**
     * Листание по кругу: главный --влево--> история --влево--> главный --влево--> …
     * Горизонталь ловим только когда она заметно больше вертикали, поэтому
     * вертикальная прокрутка текста продолжает работать.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (!dragging && swipeDownX > 70f &&
                    kotlin.math.abs(dx) > 24f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f
                ) {
                    dragging = true
                }
                if (dragging && ::contentView.isInitialized) {
                    contentView.translationX = dx
                    contentView.alpha = 1f - 0.25f * (kotlin.math.abs(dx) / screenW)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    commitSwipe(ev.x - swipeDownX)
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    revert()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

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

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.bg)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        root.addView(text("ПРОШЛАЯ НОЧЬ", 11f, Ui.ACCENT).apply { letterSpacing = 0.16f })

        chart = SleepChartView(this).apply { setBackgroundResource(R.drawable.bg_card) }
        root.addView(
            chart,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96))
                .apply { topMargin = dp(4) },
        )

        summaryView = text("загружаю…", 11f, Ui.TEXT_SECONDARY).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(6), dp(2), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(summaryView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        phasesView = text("", 11f, Ui.ACCENT).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(6), dp(2), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(phasesView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        efficiencyView = text("", 11f, Ui.TEXT_SECONDARY).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(4), dp(2), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(efficiencyView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        fileView = text("", 9f, Ui.TEXT_TERTIARY).apply {
            gravity = Gravity.START
            setPadding(dp(2), dp(6), dp(2), dp(4))
        }
        root.addView(fileView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // Контент вписан в квадрат нашей модели (R1–R2) и прокручивается вниз:
        // шрифт крупный, поэтому всё в один экран не влезает — это осознанно.
        val inset = RoundScreen.inscribedInsetPx() + dp(16)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg)
            setPadding(inset, dp(34), inset, dp(24))
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Ui.bg)
            isVerticalScrollBarEnabled = true
            addView(root)
        }
        outer.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        contentView = outer
        // Горизонтальные жесты не обрабатываем: листание разделов живёт на главном,
        // а вертикальная прокрутка должна работать без конфликтов.
        setContentView(outer)
    }

    private fun load() {
        Thread {
            val file = bestProbeFile()
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
        sb.appendLine("Батарея: $battery" + if (n.alarmFired) " · будильник сработал" else "")
        summaryView.text = sb.toString().trim()

        phasesView.text = buildString {
            append("≈ глубокий ").append(hm(n.deepMin))
            append(" · лёгкий ").append(hm(n.lightMin))
            append(" · бодрств. ").append(hm(n.awakeMin))
        }
        efficiencyView.text = buildString {
            append("Эффективность ").append(n.efficiencyPct.toInt()).append('%')
            append(" · самый спокойный отрезок ").append(hm(n.longestQuietMin))
        }

        summaryView.append(
            "\nПокрытие лога: ${n.coveragePct.toInt()}%" +
                if (n.coveragePct < 95) "  ← неполная ночь" else "  ✓"
        )

        fileView.text = "файл: ${file.name}"
    }

    private fun hm(minutes: Int): String =
        if (minutes < 60) "${minutes}м" else "${minutes / 60}ч ${minutes % 60}м"

    /**
     * Берём самый ИНФОРМАТИВНЫЙ файл (по размеру), а не самый свежий:
     * после коротких тестовых прогонов «последним» оказывается файл на 2 строки.
     */
    private fun bestProbeFile(): File? {
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
