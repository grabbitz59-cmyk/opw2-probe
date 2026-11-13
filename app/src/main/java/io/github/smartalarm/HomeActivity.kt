package io.github.smartalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale

/**
 * РњРёРЅРёРјР°Р»СЊРЅС‹Р№ РїСЂРѕРґСѓРєС‚РѕРІС‹Р№ СЌРєСЂР°РЅ: РѕРґРЅРѕ СЂРµС€РµРЅРёРµ вЂ” В«РІРѕ СЃРєРѕР»СЊРєРѕВ» Рё В«СЂР°Р·Р±СѓРґРё РІ РѕРєРЅРµВ».
 *
 * Р’СЃС‘ РґРёР°РіРЅРѕСЃС‚РёС‡РµСЃРєРѕРµ (35 РєРЅРѕРїРѕРє) РѕСЃС‚Р°Р»РѕСЃСЊ РІ [ProbeActivity] Рё РѕС‚РєСЂС‹РІР°РµС‚СЃСЏ РїРѕ В«Р”РёР°РіРЅРѕСЃС‚РёРєР°В»,
 * С‡С‚РѕР±С‹ РЅР° 466Г—466 РЅРµ РїСЂРёС…РѕРґРёР»РѕСЃСЊ Р»РёСЃС‚Р°С‚СЊ 14 СЌРєСЂР°РЅРѕРІ.
 */
class HomeActivity : ComponentActivity() {

    private lateinit var timeView: TextView
    private lateinit var subView: TextView
    private lateinit var statusView: TextView
    private lateinit var primary: TextView
    private val chips = LinkedHashMap<Int, TextView>()

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
        if (missingPerms().isEmpty()) statusView.text = "СЂР°Р·СЂРµС€РµРЅРёСЏ РІС‹РґР°РЅС‹ В· РјРѕР¶РЅРѕ Р»РѕР¶РёС‚СЊСЃСЏ"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(applicationContext)
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        hour = Prefs.targetHour(this)
        minute = Prefs.targetMinute(this)
        windowMin = Prefs.windowMin(this)
        refresh()
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(6), dp(16), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "РЈРњРќР«Р™ Р‘РЈР”РР›Р¬РќРРљ"
            setTextColor(Color.parseColor("#546E7A"))
            textSize = 9f
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
        })

        timeView = TextView(this).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            textSize = 38f
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, 0)
            setOnClickListener { openSetup() }
        }
        root.addView(timeView, lp(top = 0))

        subView = TextView(this).apply {
            setTextColor(Color.parseColor("#80CBC4"))
            textSize = 10f
            gravity = Gravity.CENTER
        }
        root.addView(subView, lp(top = 0))

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (w in intArrayOf(15, 30, 45, 60)) {
            val chip = TextView(this).apply {
                text = "$w"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(5))
                setOnClickListener {
                    windowMin = w
                    Prefs.setWindowMin(this@HomeActivity, w)
                    refresh()
                }
            }
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) },
            )
            chips[w] = chip
        }
        root.addView(chipRow, lp(top = 6))

        primary = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#04211D"))
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onPrimary() }
        }
        root.addView(primary, lp(top = 6))

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 9f
            gravity = Gravity.CENTER
        }
        root.addView(statusView, lp(top = 5))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(
            small("РўРµСЃС‚ 2 РјРёРЅ") { testAlarm() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4) },
        )
        bottom.addView(
            small("Р”РёР°РіРЅРѕСЃС‚РёРєР°") {
                startActivity(Intent(this@HomeActivity, ProbeActivity::class.java))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(bottom, lp(top = 6))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(root)
        })
    }

    private fun small(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#B0BEC5"))
        setBackgroundResource(R.drawable.bg_chip_off)
        setPadding(0, dp(6), 0, dp(6))
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    // ---------- СЃРѕСЃС‚РѕСЏРЅРёРµ ----------

    private fun refresh() {
        timeView.text = String.format(Locale.US, "%02d:%02d", hour, minute)

        val at = targetEpoch()
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val now = Calendar.getInstance()
        val tomorrow = cal.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR)
        val diffMin = ((at - now.timeInMillis) / 60000).toInt().coerceAtLeast(0)
        subView.text = buildString {
            append(if (tomorrow) "Р·Р°РІС‚СЂР°" else "СЃРµРіРѕРґРЅСЏ")
            append(" С‡РµСЂРµР· ${diffMin / 60}С‡ ${diffMin % 60}Рј")
            append(" В· РѕРєРЅРѕ В±$windowMin РјРёРЅ")
        }

        for ((w, chip) in chips) {
            val on = w == windowMin
            chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            chip.setTextColor(if (on) Color.parseColor("#04211D") else Color.parseColor("#CFD8DC"))
        }

        val armed = isArmed()
        val running = NightLogService.isRunning
        if (armed && running) {
            primary.text = "РћРЎРўРђРќРћР’РРўР¬ РќРћР§Р¬"
            primary.setBackgroundResource(R.drawable.bg_cta_stop)
            primary.setTextColor(Color.WHITE)
        } else {
            primary.text = "РЎРћРҐР РђРќРРўР¬ Р РЎРџРђРўР¬"
            primary.setBackgroundResource(R.drawable.bg_cta)
            primary.setTextColor(Color.parseColor("#04211D"))
        }

        val missing = missingPerms()
        statusView.setTextColor(Color.parseColor(if (missing.isEmpty()) "#90A4AE" else "#FF8A65"))
        statusView.text = when {
            missing.isNotEmpty() -> "РЅРµС‚ РїСЂР°РІ: " + missing.joinToString(", ") { it.substringAfterLast('.') }
            else -> buildString {
                append(if (running) "Р»РѕРі РёРґС‘С‚" else "Р»РѕРі РІС‹РєР»")
                append(" В· HR ").append(HealthState.sourceNow())
                append(" В· ").append(batteryPct()).append('%')
            }
        }
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

    // ---------- РґРµР№СЃС‚РІРёСЏ ----------

    private fun onPrimary() {
        if (isArmed() && NightLogService.isRunning) {
            stopNight()
            return
        }
        val missing = missingPerms()
        if (missing.isNotEmpty()) {
            statusView.setTextColor(Color.parseColor("#FF8A65"))
            statusView.text = "СЃРЅР°С‡Р°Р»Р° СЂР°Р·СЂРµС€РµРЅРёСЏ"
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
            statusView.setTextColor(Color.parseColor("#90A4AE"))
            statusView.text = "Р·Р°РїСѓСЃРєР°СЋ РЅРѕС‡СЊвЂ¦"
        } catch (t: Throwable) {
            statusView.setTextColor(Color.parseColor("#FF8A65"))
            statusView.text = "РЅРµ СѓРґР°Р»РѕСЃСЊ Р·Р°РїСѓСЃС‚РёС‚СЊ: ${t.javaClass.simpleName}"
            return
        }

        // P0: С‡РµСЃС‚РЅС‹Р№ СЃС‚Р°С‚СѓСЃ вЂ” РЅРµ СЂР°РїРѕСЂС‚РѕРІР°С‚СЊ СѓСЃРїРµС…, РµСЃР»Рё СЃРµСЂРІРёСЃ РЅРµ РїРѕРґРЅСЏР»СЃСЏ
        handler.postDelayed({
            if (!NightLogService.isRunning) {
                statusView.setTextColor(Color.parseColor("#FF8A65"))
                statusView.text = "Р»РѕРі РќР• Р·Р°РїСѓСЃС‚РёР»СЃСЏ вЂ” РѕС‚РєСЂРѕР№ Р”РёР°РіРЅРѕСЃС‚РёРєСѓ"
            }
            refresh()
        }, 2_500L)
    }

    private fun stopNight() {
        AlarmLogic.disarm(this)
        val svc = Intent(this, NightLogService::class.java).apply {
            action = NightLogService.ACTION_STOP
        }
        try {
            startService(svc)
        } catch (_: Throwable) {
        }
        handler.postDelayed({ refresh() }, 1_200L)
    }

    private fun testAlarm() {
        sendBroadcast(Intent(this, AlarmTestReceiver::class.java))
        statusView.text = "С‚РµСЃС‚РѕРІС‹Р№ Р±СѓРґРёР»СЊРЅРёРє С‡РµСЂРµР· 2 СЃРµРє"
    }

    private fun openSetup() {
        startActivity(Intent(this, AlarmSetupActivity::class.java))
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private val bg = Color.parseColor("#0A0E10")
}
