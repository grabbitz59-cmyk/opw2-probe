package io.github.smartalarm

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.util.Calendar

class AlarmSetupActivity : ComponentActivity() {

    private var hour = 7
    private var minute = 0
    private var windowMin = 30

    private lateinit var timeView: TextView
    private lateinit var hintView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        buildUi()
        refresh()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(10), dp(22), dp(26))
        }

        timeView = TextView(this).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(timeView, lp())

        hintView = TextView(this).apply {
            setTextColor(Color.parseColor("#80CBC4"))
            textSize = 9f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
            setOnClickListener { cycleWindow() }
        }
        root.addView(hintView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        row(root, "−1ч" to { shift(-60) }, "+1ч" to { shift(60) })
        row(root, "−5м" to { shift(-5) }, "+5м" to { shift(5) })

        wide(root, "СОХРАНИТЬ И СПАТЬ") { saveAndSleep() }


        scroll.addView(root)
        setContentView(scroll)
    }

    private fun cycleWindow() {
        windowMin = when (windowMin) {
            15 -> 30
            30 -> 45
            45 -> 60
            else -> 15
        }
        refresh()
    }

    private fun shift(deltaMinutes: Int) {
        val total = ((hour * 60 + minute + deltaMinutes) % 1440 + 1440) % 1440
        hour = total / 60
        minute = total % 60
        refresh()
    }

    private fun refresh() {
        timeView.text = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
        val at = targetEpoch()
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val now = Calendar.getInstance()
        val tomorrow = cal.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR)
        val diffMin = ((at - now.timeInMillis) / 60000).toInt()
        hintView.text = buildString {
            append("Окно $windowMin мин · ")
            append(if (tomorrow) "завтра" else "сегодня")
            append(" через ${diffMin / 60}ч ${diffMin % 60}м")
        }
    }

    private fun targetEpoch(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 60_000) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun saveAndSleep() {
        val at = targetEpoch()
        Prefs.setTargetHour(this, hour)
        Prefs.setTargetMinute(this, minute)
        Prefs.setWindowMin(this, windowMin)
        AlarmLogic.arm(this, at, windowMin)
        AlarmLogic.log(this, "UI arm ${String.format(java.util.Locale.US, "%02d:%02d", hour, minute)} window=$windowMin")

        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_START
            putExtra(NightLogService.EXTRA_MODE, NightLogService.MODE_ECO)
            putExtra(NightLogService.EXTRA_NOWAKELOCK, false)
        }
        ContextCompat.startForegroundService(this, svc)
        UiLog.add("Ночь: цель ${String.format(java.util.Locale.US, "%02d:%02d", hour, minute)} ±$windowMin мин, лог запущен")
        finish()
    }

    private fun row(parent: LinearLayout, left: Pair<String, () -> Unit>, right: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(smallBtn(left.first, left.second), LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) })
        r.addView(smallBtn(right.first, right.second), LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(4) })
        parent.addView(r, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(5) })
    }

    private fun smallBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minimumHeight = 0
        minHeight = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    private fun wide(parent: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minimumHeight = 0
            minHeight = 0
            setOnClickListener { onClick() }
        }
        parent.addView(b, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
        ).apply { bottomMargin = dp(4) })
        return b
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
