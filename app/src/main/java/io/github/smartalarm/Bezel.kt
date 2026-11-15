package io.github.smartalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Обод — шкала из 72 рисок через 5° (10 минут на деление), annulus `r 197…208`.
 * Дизайн: `docs/design-v2.md` §4.1.
 *
 * Обод — единственный элемент, который есть на **всех трёх экранах**: он и есть «инструмент».
 * На экране 1 обод рисует [NightDialView] (вместе с дугой отсчёта и маркером «сейчас»),
 * на экранах 2–3 — этот View как статичная рамка: без дуги, без маркера, без касаний.
 *
 * Рисование вынесено в [Bezel.drawTicks], чтобы на всех экранах риски совпадали пиксель в пиксель
 * и не разъезжались при правках.
 */
object Bezel {
    /** 72 риски: 360° / 5° = 72, то есть 10 минут на деление на 12-часовой шкале. */
    const val TICKS = 72
    const val TICKS_PER_HOUR = 6
    const val TICK_STEP_DEG = 5f
    const val TICK_R_IN = 197f
    const val TICK_R_MINOR_OUT = 203f
    const val TICK_R_MAJOR_OUT = 208f
    const val AMB_TICK_ALPHA = 150
    const val AMB_RING_ALPHA = 110
    const val AMB_RING_R = 200f

    /** Часовой угол (canvas: 0° = 3 часа, по часовой) для времени в минутах от полуночи. */
    fun angleOf(minutes: Int): Float {
        val span = 12 * 60
        val m = ((minutes % span) + span) % span
        return m / span.toFloat() * 360f - 90f
    }

    /** Рисует все 72 риски: minor 197…203, major (каждый час) 197…208. */
    fun drawTicks(canvas: Canvas, cx: Float, cy: Float, k: Float, minor: Paint, major: Paint) {
        val rIn = TICK_R_IN * k
        val rMinorOut = TICK_R_MINOR_OUT * k
        val rMajorOut = TICK_R_MAJOR_OUT * k
        for (i in 0 until TICKS) {
            val a = Math.toRadians((i * TICK_STEP_DEG - 90f).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val isMajor = i % TICKS_PER_HOUR == 0
            val outer = if (isMajor) rMajorOut else rMinorOut
            canvas.drawLine(
                cx + rIn * ca, cy + rIn * sa,
                cx + outer * ca, cy + outer * sa,
                if (isMajor) major else minor,
            )
        }
    }
}

/**
 * Статичный обод для экранов 2–3: те же 72 риски, что на главном, но без дуги и маркеров.
 * В AOD (R9) остаётся только тонкое кольцо `r = 200`, риски гаснут.
 */
class BezelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val tickMinor = Ui.strokePaint(Ui.bezelMinor, 2f)
    private val tickMajor = Ui.strokePaint(Ui.bezelMajor, 2f)
    private val ringPaint = Ui.strokePaint(Ui.paper3, 1f)

    private var ambient = false

    /** AOD: гасим риски, оставляем тонкое кольцо. */
    fun setAmbient(on: Boolean) {
        if (ambient == on) return
        ambient = on
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val base = minOf(width, height).toFloat()
        if (base <= 0f) return
        val k = base / 466f
        val cx = width / 2f
        val cy = height / 2f

        if (ambient) {
            ringPaint.strokeWidth = 1f * k
            ringPaint.color = Ui.alpha(Ui.paper3, Bezel.AMB_RING_ALPHA)
            canvas.drawCircle(cx, cy, Bezel.AMB_RING_R * k, ringPaint)
        }
        val alpha = if (ambient) Bezel.AMB_TICK_ALPHA else 255
        tickMinor.alpha = alpha
        tickMajor.alpha = alpha
        Bezel.drawTicks(canvas, cx, cy, k, tickMinor, tickMajor)
    }
}
