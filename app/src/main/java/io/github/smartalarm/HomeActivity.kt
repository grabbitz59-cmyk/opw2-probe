package io.github.smartalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import java.util.Calendar
import java.util.Locale

/**
 * Раздел 1 «ПОДЪЁМ» — главный экран. Дизайн: `docs/design-v2.md` §3.1, §3.2.
 *
 * Вся приборная панель — один [NightDialView] (обод, шкала, дуга отсчёта, время подъёма,
 * статус, дуга-рычаг). Активность отвечает за состояние и логику, а не за пиксели.
 *
 * Логика не менялась относительно v1: arm/disarm через [AlarmLogic], ночная запись через
 * [NightLogService], подхват системного будильника, разрешения, ambient (AOD), snooze и
 * повтор по дням — снаружи, в тех же сервисах и получателях.
 */
class HomeActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var dial: NightDialView
    private lateinit var index: SectionIndexView
    private lateinit var swipe: SectionSwipe
    private var ambient: AmbientLifecycleObserver? = null

    private var hour = 7
    private var minute = 0
    private var windowMin = 30

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5_000L)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(applicationContext)
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        buildUi()
        setupAmbient()
        // Отладка: adb shell am start -n io.github.smartalarm/.HomeActivity --ez force_ambient true
        if (intent?.getBooleanExtra("force_ambient", false) == true) {
            handler.postDelayed({ applyAmbient(true) }, 400L)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Ui.bg) }

        content = FrameLayout(this).apply { setBackgroundColor(Ui.bg) }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        dial = NightDialView(this).apply {
            onPrimaryTap = { onPrimary() }
            onTimeTap = { openSetup() }
        }
        content.addView(
            dial,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        index = SectionIndexView(this).apply { active = Sections.HOME }
        content.addView(
            index,
            FrameLayout.LayoutParams(64, 12).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = Ui.INDEX_TOP.toInt()
            },
        )

        swipe = SectionSwipe(this, Sections.HOME, content)
        setContentView(root)
    }

    /** Перехватываем жест на уровне Activity: дочерние View сами отсекают тап по слопу. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe.handle(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ ambient (R9)

    private fun setupAmbient() {
        ambient = AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(details: AmbientLifecycleObserver.AmbientDetails) {
                    applyAmbient(true)
                }

                override fun onUpdateAmbient() {
                    refresh()
                    dial.invalidate()
                }

                override fun onExitAmbient() {
                    applyAmbient(false)
                }
            },
        )
    }

    /**
     * AOD: чёрный фон, только два времени (подъём и «сейчас»), шкала alpha 30 % и тонкая дуга.
     * Ни кнопок, ни подписей, ни пульсации — так экран не выжигает AMOLED.
     */
    private fun applyAmbient(on: Boolean) {
        dial.setDimmed(on)
        index.visibility = if (on) View.INVISIBLE else View.VISIBLE
        content.background = if (on) {
            ColorDrawable(Color.parseColor(Ui.BG_AMBIENT))
        } else {
            ColorDrawable(Ui.bg)
        }
    }

    override fun onResume() {
        super.onResume()
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        swipe.reset()
        refresh()
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        swipe.reset()
    }

    // ------------------------------------------------------------------ состояние

    /**
     * Синхронизация с системным будильником часов: если на часах есть «чужой» будильник
     * (или будильник телефона при включённой синхронизации), а наш не взведён — берём его время.
     */
    private fun adoptSystemAlarm() {
        val info = SystemAlarm.read(this) ?: return
        if (info.isOurs || isArmed()) return
        val cal = Calendar.getInstance().apply { timeInMillis = info.triggerAt }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        if (h == hour && m == minute) return
        hour = h
        minute = m
        Prefs.setTargetHour(this, h)
        Prefs.setTargetMinute(this, m)
        AlarmLogic.log(
            this,
            "synced target from system alarm " +
                String.format(Locale.US, "%02d:%02d", h, m) + " (${info.creator})",
        )
    }

    private fun refresh() {
        adoptSystemAlarm()
        val armed = isArmed()
        val running = NightLogService.isRunning
        val target = currentTarget()
        val missing = missingPerms()

        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val diffMin = ((target - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)

        dial.nowText = "СЕЙЧАС " + String.format(Locale.US, "%02d:%02d", nowMin / 60, nowMin % 60)
        dial.nowMinutes = nowMin
        dial.targetText = String.format(Locale.US, "%02d:%02d", hour, minute)
        dial.armed = armed
        dial.recording = running
        dial.remainingMinutes = if (armed) diffMin else 0

        val windowText = if (Prefs.repeatDays(this) == RepeatDays.NONE) {
            "ОКНО ±$windowMin МИН"
        } else {
            "ОКНО ±$windowMin МИН · " + RepeatDays.label(Prefs.repeatDays(this)).uppercase()
        }

        when {
            missing.isNotEmpty() -> {
                dial.stateText = "НЕТ ПРАВ"
                dial.stateColor = Ui.signalStop
                dial.detailText = "НУЖНЫ ДАТЧИКИ"
                dial.leverText = "СПАТЬ"
            }

            running -> {
                dial.stateText = "ЗАПИСЬ · ${batteryPct()}%"
                dial.stateColor = Ui.ember
                dial.detailText = windowText
                dial.leverText = "СТОП"
            }

            armed -> {
                dial.stateText = if (diffMin == 0) "СЕЙЧАС" else "ВЗВЕДЁН"
                dial.stateColor = Ui.ember
                dial.detailText = deltaLabel(diffMin)
                dial.leverText = "НАЧАТЬ"
            }

            else -> {
                dial.stateText = "НЕ ВЗВЕДЁН"
                dial.stateColor = Ui.paper2
                dial.detailText = windowText
                dial.leverText = "СПАТЬ"
            }
        }
    }

    /** «ЧЕРЕЗ 7 Ч 12 М» / «ЧЕРЕЗ 12 М» — словами, как требует R7. */
    private fun deltaLabel(minutes: Int): String {
        val hh = minutes / 60
        val mm = minutes % 60
        return when {
            minutes <= 0 -> "СЕЙЧАС"
            hh == 0 -> "ЧЕРЕЗ $mm М"
            mm == 0 -> "ЧЕРЕЗ $hh Ч"
            else -> "ЧЕРЕЗ $hh Ч $mm М"
        }
    }

    private fun currentTarget(): Long {
        val stored = Prefs.alarmAt(this)
        val live = AlarmLogic.targetAt
        val at = if (live > 0) live else stored
        return if (at > System.currentTimeMillis()) at else targetEpoch()
    }

    private fun isArmed(): Boolean {
        val live = AlarmLogic.targetAt
        val stored = Prefs.alarmAt(this)
        val at = if (live > 0) live else stored
        return at > System.currentTimeMillis()
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

    private fun missingPerms(): List<String> {
        val wanted = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.BODY_SENSORS_BACKGROUND)
        return wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun batteryPct(): Int = try {
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) {
        -1
    }

    // ------------------------------------------------------------------ действия

    /** Единственное главное действие экрана — рычаг. */
    private fun onPrimary() {
        if (isArmed() && NightLogService.isRunning) {
            stopNight()
            return
        }
        val missing = missingPerms()
        if (missing.isNotEmpty()) {
            // Нет прав: рычаг превращается в запрос разрешений, отдельной кнопки не появляется.
            permLauncher.launch(missing.toTypedArray())
            return
        }

        val at = targetEpoch()
        Prefs.setTargetHour(this, hour)
        Prefs.setTargetMinute(this, minute)
        Prefs.setWindowMin(this, windowMin)
        AlarmLogic.arm(this, at, windowMin)
        AlarmLogic.log(this, "home arm ${String.format(Locale.US, "%02d:%02d", hour, minute)} w=$windowMin")

        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_START
            putExtra(NightLogService.EXTRA_MODE, NightLogService.MODE_ECO)
            putExtra(NightLogService.EXTRA_NOWAKELOCK, false)
        }
        try {
            ContextCompat.startForegroundService(this, svc)
        } catch (_: Throwable) {
            refresh()
            return
        }
        handler.postDelayed({
            if (!NightLogService.isRunning) AlarmLogic.log(this, "night log did not start")
            refresh()
        }, 2_500L)
    }

    private fun stopNight() {
        AlarmLogic.disarm(this)
        try {
            startService(Intent(this, NightLogService::class.java).apply {
                action = NightLogService.ACTION_STOP
            })
        } catch (_: Throwable) {
        }
        val ctx = applicationContext
        Thread {
            try {
                HealthPassive.stop(ctx)
            } catch (_: Throwable) {
            }
        }.start()
        handler.postDelayed({ refresh() }, 1_200L)
    }

    /** Редактор времени: тап по времени подъёма или долгое нажатие на приборной панели. */
    private fun openSetup() {
        startActivity(Intent(this, AlarmSetupActivity::class.java))
    }
}
