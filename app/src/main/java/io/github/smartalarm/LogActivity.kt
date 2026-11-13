package io.github.smartalarm

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
import java.io.File

class LogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padSide = dp(34)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(padSide, dp(20), padSide, dp(38))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val close = Button(this).apply {
            text = "Закрыть"
            textSize = 11f
            isAllCaps = false
            minimumHeight = 0
            minHeight = 0
            setOnClickListener { finish() }
        }
        val save = Button(this).apply {
            text = "В файл"
            textSize = 11f
            isAllCaps = false
            minimumHeight = 0
            minHeight = 0
            setOnClickListener { saveToFile() }
        }
        top.addView(close, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(4) })
        top.addView(save, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4) })
        root.addView(top)

        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            text = UiLog.text().ifEmpty { "Лог пуст" }
        }
        scroll.addView(tv)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun saveToFile() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            File(dir, "screen_log.txt").writeText(UiLog.text())
        } catch (_: Throwable) {
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
