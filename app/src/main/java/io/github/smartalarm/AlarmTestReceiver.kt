package io.github.smartalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

class AlarmTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Дедуп: дедлайн из сервиса и AlarmManager приходят почти одновременно,
        // иначе вибрация запускалась дважды и в alarm_fired.txt было две записи.
        if (!AlarmLogic.markFired()) return

        val now = System.currentTimeMillis()
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            java.io.File(dir, "alarm_fired.txt").appendText("fired=$now\n")
        } catch (_: Throwable) {
        }

        AlarmPlayer.start(context)

        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val nm0 = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            java.io.File(dir, "alarm_fired.txt").appendText(
                "ui=" + try { context.startActivity(
                    Intent(context, AlarmActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ); "started" } catch (t: Throwable) { "failed: $t" } +
                    " notifEnabled=" + nm0.areNotificationsEnabled() + "\n"
            )
        } catch (_: Throwable) {
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                enableVibration(false)
            }
        )

        val full = PendingIntent.getActivity(
            context, 91,
            Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context, 92,
            Intent(context, StopAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("БУДИЛЬНИК · ПОДЪЁМ")
            .setContentText("Нажмите СТОП, чтобы выключить")
            .setSmallIcon(R.drawable.ic_probe)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(Notification.Action.Builder(null, "СТОП", stop).build())
            .build()
        nm.notify(AlarmPlayer.ALARM_NOTIFICATION_ID, n)

        Handler(Looper.getMainLooper()).postDelayed({
            if (AlarmPlayer.active) AlarmPlayer.stop(context)
        }, AUTO_STOP_MS)
    }

    companion object {
        private const val CHANNEL_ID = "alarmtest"
        // 90 с было мало: если человек не успел дотянуться до часов, будильник замолкал.
        private const val AUTO_STOP_MS = 300_000L
    }
}

class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmPlayer.stop(context)
    }
}

class AlarmActivity : androidx.activity.ComponentActivity() {
    private val autoStop = Runnable { finishAlarm() }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(24, 24, 24, 24)
        }
        val title = android.widget.TextView(this).apply {
            text = "ПОДЪЁМ"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            gravity = android.view.Gravity.CENTER
        }
        val time = android.widget.TextView(this).apply {
            text = java.time.LocalTime.now().withNano(0).toString()
            setTextColor(android.graphics.Color.GRAY)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 4, 0, 16)
        }
        val hint = android.widget.TextView(this).apply {
            text = "удерживай кнопку, чтобы выключить"
            setTextColor(android.graphics.Color.parseColor("#80CBC4"))
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }
        val stop = android.widget.Button(this).apply {
            text = "ОСТАНОВИТЬ"
            textSize = 18f
            isAllCaps = false
            // Раньше гасило любое касание и любая кнопка: можно было выключить,
            // просто пошевелив рукой. Теперь — только удержание.
            setOnLongClickListener {
                finishAlarm()
                true
            }
        }
        root.addView(title)
        root.addView(time)
        root.addView(stop)
        root.addView(hint)
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed(autoStop, 300_000L)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Кнопки не гасят будильник, но и не должны уходить в систему.
        return true
    }

    private fun finishAlarm() {
        AlarmPlayer.stop(this)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
