package io.github.smartalarm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.Locale

/**
 * Третий раздел карусели — «Статус»: короткая сводка о том, что настроено и что доступно.
 *
 * Навигация только левым свайпом: главный → ночь → статус → главный (по кругу).
 */
class StatusActivity : ComponentActivity() {

    private lateinit var contentView: LinearLayout

    private val screenW: Float get() = resources.displayMetrics.widthPixels.toFloat()

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::contentView.isInitialized) {
            contentView.translationX = 0f
            contentView.alpha = 1f
        }
    }

    // ---------- листание только влево: статус → главный ----------
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
                    val dx = ev.x - swipeDownX
                    if (dx <= -90f) goHome() else revert()
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

    private fun revert() {
        contentView.animate().translationX(0f).alpha(1f).setDuration(140L).start()
    }

    /** Замыкаем цикл: из статуса влево — снова главный (стек очищаем). */
    private fun goHome() {
        contentView.animate()
            .translationX(-screenW)
            .alpha(0.35f)
            .setDuration(140L)
            .withEndAction {
                startActivity(
                    Intent(this, HomeActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
            }
            .start()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.bg)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        root.addView(row("СТАТУС", 11f, Ui.ACCENT).apply { letterSpacing = 0.16f })

        val alarm = Prefs.alarmAt(this)
        val time = String.format(
            Locale.US, "%02d:%02d", Prefs.targetHour(this), Prefs.targetMinute(this),
        )
        val armed = alarm > System.currentTimeMillis()
        val battery = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        addInfo(root, "Будильник", if (armed) "$time · взведён" else "$time · не взведён")
        addInfo(
            root, "Повтор",
            when (val mask = Prefs.repeatDays(this)) {
                RepeatDays.NONE -> "выкл"
                else -> RepeatDays.label(mask)
            } + " · окно ±${Prefs.windowMin(this)} мин",
        )
        addInfo(root, "Отложить", "${Prefs.snoozeMin(this)} мин")
        addInfo(root, "Ночной лог", if (NightLogService.isRunning) "идёт" else "остановлен")
        addInfo(root, "Батарея", if (battery in 0..100) "$battery%" else "н/д")
        addInfo(
            root, "Разрешения",
            buildString {
                append(if (has(Manifest.permission.BODY_SENSORS)) "датчики ✓" else "датчики ✗")
                append(" · ")
                val notif = (getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager).areNotificationsEnabled()
                append(if (notif) "уведомления ✓" else "уведомления ✗")
            },
        )
        addInfo(root, "Версия", appVersion())

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.bg)
            isFillViewport = true
            addView(root)
        }

        val inset = RoundScreen.inscribedInsetPx() + dp(16)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg)
            setPadding(inset, dp(34), inset, dp(24))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        contentView = outer
        // Индикатор разделов: 3/3 — мы на «Статусе»
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Ui.bg)
            addView(outer)
            addView(
                PageDotsView(this@StatusActivity).apply { setPage(2, 3) },
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

    private fun addInfo(parent: LinearLayout, key: String, value: String) {
        parent.addView(
            row(value, 11f, Ui.TEXT_SECONDARY).apply {
                gravity = Gravity.START
                setPadding(dp(2), dp(5), dp(2), 0)
                text = "$key: $value"
            },
        )
    }

    private fun has(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun row(value: String, size: Float, color: String): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(color))
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()
}
