package io.github.smartalarm

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Редактор времени подъёма в языке v2 «Ночной инструмент».
 *
 * Смысл прежний: часы 0–23 и минуты 0–55 тянутся пальцем (долгое нажатие по шкале минут
 * переключает шаг 5 ↔ 1), крупное время сверху показывает результат, «СОХРАНИТЬ И СПАТЬ»
 * взводит будильник и запускает ночной лог (`saveAndSleep()` — логика не менялась).
 *
 * Изменился только язык: плоский фон `bg`, герой `hero_time` (104 px, condensed, `paper`),
 * подпись `row_label` капителью, кнопка — прямоугольная плита `ember_ash` с обводкой `ember`
 * без скругления (radius 0), при нажатии — заливка `ember` и текст `on_ember` (мгновенно).
 * Геометрия полос повторяет прежнюю: R1 (`r ≤ 209`) проверяется в [at].
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

        // Надпись над героем — та же роль, что label_caps на экране «ПОДЪЁМ».
        root.addView(
            Ui.label(this, "ПОДЪЁМ", Ui.SZ_ROW_LABEL, Ui.paper3, Ui.LS_ROW_LABEL, caps = true)
                .apply { gravity = Gravity.CENTER },
            at(topPx = 44, heightPx = 30),
        )

        timeView = Ui.label(this, "", Ui.SZ_HERO, Ui.paper, Ui.LS_TIGHT).apply {
            gravity = Gravity.CENTER
        }
        root.addView(timeView, at(topPx = 80, heightPx = 88))

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
        root.addView(hourSlider, at(topPx = 172, heightPx = 72, capPx = 380f))

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
        root.addView(minuteSlider, at(topPx = 246, heightPx = 72, capPx = 380f))

        // Плита главного действия: прямоугольник без скругления, обводка `ember`.
        val cta = TextView(this).apply {
            text = "СОХРАНИТЬ И СПАТЬ"
            typeface = Ui.condensed()
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.SZ_LEVER)
            letterSpacing = Ui.LS_LEVER
            includeFontPadding = false
            gravity = Gravity.CENTER
            setLineSpacing(2f, 1f)
            setTextColor(Ui.ember)
            background = ctaBackground(pressed = false)
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> setCtaStyle(v as TextView, pressed = true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        setCtaStyle(v as TextView, pressed = false)
                    else -> Unit
                }
                false
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                saveAndSleep()
            }
        }
        root.addView(cta, at(topPx = 322, heightPx = 96))

        setContentView(root)
        renderTime()
    }

    /** Плита действия: заливка `ember_ash` / при нажатии `ember`, обводка 2 px `ember`, radius 0. */
    private fun ctaBackground(pressed: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 0f
        setColor(if (pressed) Ui.ember else Ui.emberAsh)
        setStroke(2, Ui.ember)
    }

    private fun setCtaStyle(view: TextView, pressed: Boolean) {
        view.background = ctaBackground(pressed)
        view.setTextColor(if (pressed) Ui.onEmber else Ui.ember)
    }

    /**
     * Абсолютная полоса: `topMargin` в px, ширина урезана кругом (R1: `r = 209`).
     * Отступ 8 px от окружности — запас на сглаживание и на «оптический» край.
     */
    private fun at(topPx: Int, heightPx: Int, capPx: Float? = null): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = topPx
        }
        val dy = maxOf(abs(topPx - Ui.CY), abs((topPx + heightPx) - Ui.CY))
        val fit = if (dy >= Ui.SAFE_R) 0f else 2f * sqrt(Ui.SAFE_R * Ui.SAFE_R - dy * dy)
        val cap = capPx ?: Float.MAX_VALUE
        lp.width = when {
            fit > 1f -> minOf(cap, fit - MARGIN_PX).toInt().coerceAtLeast(1)
            capPx != null -> capPx.toInt()
            else -> ViewGroup.LayoutParams.MATCH_PARENT
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

    private companion object {
        /** Запас от окружности R1: контент не прилипает к дуге. */
        const val MARGIN_PX = 8f
    }
}
