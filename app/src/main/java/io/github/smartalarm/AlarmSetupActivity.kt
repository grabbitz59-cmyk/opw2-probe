package io.github.smartalarm

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale

/**
 * Выбор времени будильника слайдами: часы и минуты тянутся пальцем,
 * крупное время сверху показывает результат.
 *
 * Геометрия — по скиллу wear-os-ui для нашей модели: всё в средней полосе круга,
 * кнопка 44 dp, ширины считаются через [RoundScreen].
 */
class AlarmSetupActivity : ComponentActivity() {

    private var hour = 7
    private var minute = 0

    private lateinit var timeView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        buildUi()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.bg) }

        root.addView(label("БУДИЛЬНИК · ТАП И ТЯНИ ↑↓"), at(topPx = 44, heightPx = 22))

        timeView = TextView(this).apply {
            typeface = Ui.clockTypeface()
            setTextColor(Ui.text)
            textSize = 40f
            gravity = Gravity.CENTER
        }
        root.addView(timeView, at(topPx = 66, heightPx = 90))

        val hourSlider = SliderRowView(this).apply {
            label = "часы"
            minValue = 0
            maxValue = 23
            step = 1
            value = hour
            onChange = { v ->
                hour = v
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                renderTime()
            }
        }
        root.addView(hourSlider, at(topPx = 168, heightPx = 68, widthCap = 380f))

        val minuteSlider = SliderRowView(this).apply {
            label = "минуты · шаг 5"
            minValue = 0
            maxValue = 55
            step = 5
            value = (minute / 5) * 5
            onChange = { v ->
                minute = v
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                renderTime()
            }
            // Долгое нажатие по шкале минут: шаг 5 ↔ 1 (точность до минуты)
            onLongPress = {
                step = if (step == 5) 1 else 5
                maxValue = if (step == 1) 59 else 55
                label = "минуты · шаг $step"
                minute = (minute / step) * step
                value = minute
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                renderTime()
            }
        }
        root.addView(minuteSlider, at(topPx = 242, heightPx = 68, widthCap = 380f))

        val cta = TextView(this).apply {
            text = "СОХРАНИТЬ И СПАТЬ"
            textSize = 12f
            typeface = Ui.labelTypeface()
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#04211D"))
            setBackgroundResource(R.drawable.bg_cta)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                saveAndSleep()
            }
        }
        root.addView(cta, at(topPx = 322, heightPx = 88, widthCap = 216f))

        setContentView(root)
        renderTime()
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 9f
        letterSpacing = 0.18f
        typeface = Ui.labelTypeface()
        setTextColor(Ui.accent)
        gravity = Gravity.CENTER
    }

    /** Абсолютная позиция + ширина, урезанная по кругу (R1–R2). */
    private fun at(topPx: Int, heightPx: Int, widthCap: Float? = null): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, heightPx,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = topPx
        }
        val safe = RoundScreen.safeRowWidth(topPx.toFloat(), (topPx + heightPx).toFloat())
        lp.width = when {
            safe <= 0f && widthCap == null -> ViewGroup.LayoutParams.MATCH_PARENT
            safe <= 0f -> widthCap!!.toInt()
            widthCap != null -> minOf(widthCap, safe).toInt()
            else -> safe.toInt()
        }
        return lp
    }

    private fun renderTime() {
        timeView.text = String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    private fun targetEpoch(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis() + 60_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun saveAndSleep() {
        val at = targetEpoch()
        Prefs.setTargetHour(this, hour)
        Prefs.setTargetMinute(this, minute)
        AlarmLogic.arm(this, at, Prefs.windowMin(this))
        AlarmLogic.log(
            this,
            "setup arm ${String.format(Locale.US, "%02d:%02d", hour, minute)} " +
                "w=${Prefs.windowMin(this)}",
        )
        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_START
            putExtra(NightLogService.EXTRA_MODE, NightLogService.MODE_ECO)
            putExtra(NightLogService.EXTRA_NOWAKELOCK, false)
        }
        try {
            ContextCompat.startForegroundService(this, svc)
        } catch (_: Throwable) {
        }
        finish()
    }
}
