package io.github.smartalarm

import android.content.Intent
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
                // Листаем только влево и дальше по кругу: ночь → статус
                if (toLeft) {
                    startActivity(Intent(this, StatusActivity::class.java))
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                } else {
                    finish()
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
            background = verticalGradient()
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        root.addView(text("ПРОШЛАЯ НОЧЬ", 11f, Ui.ACCENT).apply { letterSpacing = 0.16f })

        chart = SleepChartView(this)
        root.addView(
            chart,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104))
                .apply { topMargin = dp(6) },
        )

        // Легенда фаз: цветные точки + подписи
        root.addView(
            legendRow(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) },
        )

        // Две «плашки» с главными числами
        root.addView(
            metricsRow(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
                .apply { topMargin = dp(8) },
        )

        phasesView = text("", 10f, Ui.ACCENT).apply {
            gravity = Gravity.START
            setPadding(dp(4), dp(8), dp(4), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(phasesView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        summaryView = text("загружаю…", 10f, Ui.TEXT_SECONDARY).apply {
            gravity = Gravity.START
            setPadding(dp(4), dp(6), dp(4), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(summaryView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        efficiencyView = text("", 10f, Ui.TEXT_SECONDARY).apply {
            gravity = Gravity.START
            setPadding(dp(4), dp(4), dp(4), 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(efficiencyView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        fileView = text("", 8f, Ui.TEXT_TERTIARY).apply {
            gravity = Gravity.START
            setPadding(dp(4), dp(8), dp(4), dp(6))
        }
        root.addView(fileView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // Контент вписан в квадрат нашей модели (R1–R2) и прокручивается вниз:
        // шрифт крупный, поэтому всё в один экран не влезает — это осознанно.
        val inset = RoundScreen.inscribedInsetPx() + dp(12)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = verticalGradient()
            setPadding(inset, dp(34), inset, dp(24))
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
            addView(root)
        }
        outer.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        contentView = outer
        // Индикатор разделов + контент: 2/3 — мы на «Ночи»
        val frame = android.widget.FrameLayout(this).apply {
            background = verticalGradient()
            addView(outer)
            addView(
                PageDotsView(this@StatsActivity).apply { setPage(1, 3) },
                android.widget.FrameLayout.LayoutParams(
                    (60 * resources.displayMetrics.density).toInt(),
                    (10 * resources.displayMetrics.density).toInt(),
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = (20 * resources.displayMetrics.density).toInt()
                },
            )
        }
        setContentView(frame)
    }

    /** Фон экрана: глубокий вертикальный градиент из токенов. */
    private fun verticalGradient(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(android.graphics.Color.parseColor(Ui.BG_TOP), android.graphics.Color.parseColor(Ui.BG_BOTTOM)),
        )

    /** Легенда: ● глубокий ● лёгкий ● бодрствование. */
    private fun legendRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        data class Item(val color: Int, val label: String)
        listOf(
            Item(Ui.phaseDeep, "глубокий"),
            Item(Ui.phaseLight, "лёгкий"),
            Item(Ui.phaseAwake, "бодрств."),
        ).forEach { item ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            cell.addView(android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(item.color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(4) }
            })
            cell.addView(text(item.label, 8f, Ui.TEXT_SECONDARY).apply { gravity = Gravity.CENTER_VERTICAL })
            row.addView(cell)
        }
        return row
    }

    /** Две плашки: эффективность и пробуждения — крупными числами. */
    private fun metricsRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        metricValue = text("—", 22f, Ui.TEXT).apply { typeface = Ui.clockTypeface() }
        metricLabel = text("эффективность", 8f, Ui.TEXT_SECONDARY)
        metricValue2 = text("—", 22f, Ui.TEXT).apply { typeface = Ui.clockTypeface() }
        metricLabel2 = text("пробуждений", 8f, Ui.TEXT_SECONDARY)
        row.addView(chip(metricValue, metricLabel), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(4) })
        row.addView(chip(metricValue2, metricLabel2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(4) })
        return row
    }

    private fun chip(value: TextView, label: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Ui.surface)
                setStroke(2, Ui.stroke)
            }
            addView(value)
            addView(label)
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

        // Фаза, в которой разбудил будильник (по HR + движению в этот момент)
        metricValue.text = "${n.efficiencyPct.toInt()}%"
        metricValue2.text = "${n.awakenings}"
        phasesView.text = buildString {
            if (n.wakePhase != SleepStats.Stage.UNKNOWN) {
                append("Разбудил в фазе: ").append(PhaseEstimator.label(n.wakePhase))
                if (n.wakeHr > 0) append(" · пульс ").append(n.wakeHr)
                append("\n")
            }
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
     * Выбор файла ночи: приоритет — файл, в котором есть строка срабатывания с записанной
     * фазой подъёма (иначе на экране нечего показать про пробуждение). Если таких нет —
     * самый информативный по размеру.
     */
    private fun bestProbeFile(): File? {
        val dir = getExternalFilesDir(null) ?: filesDir
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("probe_") && it.name.endsWith(".csv") }
            ?: return null
        if (files.isEmpty()) return null
        return files.filter { hasWakePhaseRow(it) }.maxByOrNull { it.length() }
            ?: files.maxWithOrNull(compareBy<File> { it.length() }.thenBy { it.lastModified() })
    }

    /** Есть ли в файле строка «будильник сработал» с непустой фазой (21-я колонка). */
    private fun hasWakePhaseRow(f: File): Boolean = try {
        f.useLines { lines ->
            lines.any { line ->
                val parts = line.trimEnd().split(',')
                parts.size >= 21 && parts[19] == "triggered" && parts[20].isNotBlank()
            }
        }
    } catch (_: Throwable) {
        false
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
