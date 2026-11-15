package io.github.smartalarm

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Кольцо прогресса на весь экран + время в центре.
 *
 * Единственное поддерживаемое устройство — OnePlus Watch 2: круг 466×466 px, 320 dpi.
 * Все размеры считаются от `min(w, h)`, поэтому циферблат не разваливается при смене
 * размера View (например, в превью), но рассчитан на 466.
 *
 * Состояния (см. docs/design-tokens.md):
 *  - взведён: кольцо градиентом `accent_from → accent_to`, мягкое свечение под дугой,
 *    радиальная точка на конце;
 *  - запись идёт: точка пульсирует 1.4 с (дыхание), см. [running];
 *  - не взведён: только фон кольца `surface_hi` и время;
 *  - **ambient (AOD)**: приглушённые кольцо и время, без градиента, свечения и пульсации,
 *    чтобы не выжигать AMOLED и не тратить батарею на AOD (см. [setDimmed]).
 *
 * Публичный API (используется HomeActivity — не менять без правки активити):
 * [targetAt], [armed], [running], [timeLabel], [setDimmed], [animateRing].
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // Цвета, которые в Ui.kt уже совпадают с токенами, берём оттуда — без второго хардкода.
    // Ui.SURFACE_HI (#1B2529) и Ui.TEXT (#FFFFFF) с docs/design-tokens.md не совпадают,
    // поэтому surface_hi / text / accent_from / accent_to заданы здесь строго по токенам.
    private val accentSolid: Int = Ui.accent
    private val accentDimColor: Int = Ui.accentDim
    private val textSecondaryColor: Int = Ui.textSecondary

    /** Фон кольца: `surface_hi` #18242A. */
    private val ringBg = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        color = RING_TRACK
    }

    /** Широкие полупрозрачные проходы под дугой — имитация glow (без BlurMaskFilter). */
    private val glowPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        color = accentSolid
    }

    /** Сама дуга прогресса: градиент `accent_from → accent_to`. */
    private val ringArc = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    /** Точка на конце дуги: радиальная заливка акцентом с прозрачным краем. */
    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    /** 12 часовых рисок по кругу. */
    private val tickPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = TICK_WEIGHT
        strokeCap = Paint.Cap.ROUND
        color = TEXT_MAIN
    }

    private val timePaint = Paint().apply {
        isAntiAlias = true
        color = TEXT_MAIN
        typeface = Ui.clockTypeface()
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.01f
    }

    private val arcRect = RectF()

    /** SweepGradient зависит только от центра — кэшируем, чтобы не создавать его каждый кадр. */
    private var sweepShader: SweepGradient? = null

    /** Кэш радиального градиента точки: пересоздаём только при заметном сдвиге/изменении радиуса. */
    private var dotShader: RadialGradient? = null
    private var dotShaderX = Float.NaN
    private var dotShaderY = Float.NaN
    private var dotShaderR = Float.NaN

    /** Метка ближайшего взведённого будильника (epoch millis), 0 — не взведён. */
    var targetAt: Long = 0L
        set(v) {
            field = v
            invalidate()
        }

    /** Взведён ли будильник: без этого дуга прогресса не рисуется. */
    var armed: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    /** Идёт запись сна: точка на конце дуги пульсирует. */
    var running: Boolean = false
        set(v) {
            field = v
            setPulse(v)
            invalidate()
        }

    /** Время в центре циферблата, формат "HH:mm". */
    var timeLabel: String = "07:00"
        set(v) {
            field = v
            invalidate()
        }

    /** Ambient/AOD: приглушённая отрисовка без анимаций. */
    private var dimmed = false

    /** Анимация «наполнения» кольца при взведении: 0..1. */
    private var fillFraction = 1f
    private var animator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    /** 0..1 — фаза дыхания точки при записи. */
    private var pulse = 0f

    /** Ambient/AOD: кольцо и время приглушены, без градиента, свечения и пульсации. */
    fun setDimmed(on: Boolean) {
        if (dimmed == on) return
        dimmed = on
        if (on) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulse = 0f
        } else {
            setPulse(running)
        }
        invalidate()
    }

    /** Запускает анимацию кольца от нуля до текущего прогресса (700 мс, decelerate). */
    fun animateRing() {
        if (dimmed) return
        animator?.cancel()
        fillFraction = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RING_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fillFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun setPulse(on: Boolean) {
        if (!on || dimmed) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulse = 0f
            return
        }
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_ANIM_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Доля оставшегося окна до будильника (12 часов) — прогресс кольца. */
    private fun progress(): Float {
        if (!armed || targetAt <= 0L) return 0f
        val left = (targetAt - System.currentTimeMillis()).coerceAtLeast(1L)
        val span = 12 * 60 * 60 * 1000L
        return (left.toFloat() / span).coerceIn(0.02f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Центр изменился — старый SweepGradient больше не годится.
        sweepShader = null
        nudgeDotShader()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = minOf(w, h)
        if (base <= 0f) return
        val cx = w / 2f
        val cy = h / 2f

        // Толщина кольца: 4.0% от min(w,h) в обычном режиме, 2.8% в ambient.
        val strokeFrac = if (dimmed) DIM_STROKE_FRAC else STROKE_FRAC
        val stroke = (base * strokeFrac).coerceAtLeast(3f)
        // Радиус — с отступом 24 px от края круга, середина кольца по этой окружности.
        val radius = base / 2f - RoundScreen.MARGIN_PX - stroke / 2f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 1. Фон кольца.
        ringBg.strokeWidth = stroke
        ringBg.color = if (dimmed) DIM_RING_TRACK else RING_TRACK
        canvas.drawCircle(cx, cy, radius, ringBg)

        val p = (progress() * fillFraction).coerceIn(0f, 1f)
        val sweep = 360f * p

        if (p > 0f) {
            if (dimmed) {
                // Ambient: ровная приглушённая дуга, без градиента и свечения.
                ringArc.shader = null
                ringArc.color = accentDimColor
                ringArc.strokeWidth = stroke
                canvas.drawArc(arcRect, START_ANGLE, sweep, false, ringArc)
            } else {
                // 2. Мягкое свечение: два широких полупрозрачных прохода под дугой.
                glowPaint.strokeWidth = stroke * GLOW_WIDE_STROKE
                glowPaint.alpha = GLOW_WIDE_ALPHA
                canvas.drawArc(arcRect, START_ANGLE, sweep, false, glowPaint)

                glowPaint.strokeWidth = stroke * GLOW_WIDEST_STROKE
                glowPaint.alpha = GLOW_WIDEST_ALPHA
                canvas.drawArc(arcRect, START_ANGLE, sweep, false, glowPaint)

                // 3. Основная дуга: плавный градиент accent_from → accent_to, без тёмных стопов.
                // Цвет при установленном shader не используется, но alpha берётся из него — WHITE = 255.
                ringArc.color = Color.WHITE
                ringArc.strokeWidth = stroke
                ringArc.shader = gradientShader(cx, cy)
                canvas.drawArc(arcRect, START_ANGLE, sweep, false, ringArc)
                ringArc.shader = null

                // 4. Точка на конце дуги: пульсирует при записи (radius stroke*1.5..2.2).
                val dotPulse = if (running) pulse else 0f
                val dotRadius = stroke * (DOT_MIN_SCALE + (DOT_MAX_SCALE - DOT_MIN_SCALE) * dotPulse)
                val endRad = Math.toRadians((START_ANGLE + sweep).toDouble())
                val dotX = cx + radius * cos(endRad).toFloat()
                val dotY = cy + radius * sin(endRad).toFloat()
                dotPaint.shader = dotShaderAt(dotX, dotY, dotRadius)
                canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
            }
        }

        // 5. Часовые риски по внутреннему краю кольца, поверх фона, но под временем.
        drawHourTicks(canvas, cx, cy, radius - stroke / 2f)

        // 6. Время: тонкий шрифт; в ambient — мельче и вторичным цветом.
        val timeSize = if (dimmed) DIM_TIME_SIZE_FRAC else TIME_SIZE_FRAC
        timePaint.textSize = (base * timeSize).coerceAtLeast(26f)
        timePaint.color = if (dimmed) textSecondaryColor else TEXT_MAIN
        canvas.drawText(timeLabel, cx, cy - base * TIME_Y_FRAC, timePaint)
    }

    /**
     * 12 коротких рисок по внутреннему краю кольца: длина 6 px, alpha 40 (в ambient — 20).
     * Снаружи кольца рисовать нельзя: внешний край кольца уже на 209 px, а правило R1
     * требует держать любой элемент внутри `R − m` (233 − 24), поэтому риски идут внутрь.
     */
    private fun drawHourTicks(canvas: Canvas, cx: Float, cy: Float, ringInner: Float) {
        val inner = ringInner - TICK_GAP
        val outer = inner - TICK_LENGTH
        tickPaint.color = if (dimmed) TEXT_DIM_TICK else TEXT_MAIN
        tickPaint.alpha = if (dimmed) DIM_TICK_ALPHA else TICK_ALPHA
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90.0)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            canvas.drawLine(cx + inner * ca, cy + inner * sa, cx + outer * ca, cy + outer * sa, tickPaint)
        }
        tickPaint.alpha = 255
    }

    private fun gradientShader(cx: Float, cy: Float): SweepGradient =
        sweepShader ?: SweepGradient(cx, cy, GRADIENT_COLORS, GRADIENT_STOPS).also { sweepShader = it }

    /**
     * Радиальная заливка точки: от accent_from в центре к accent к середине и в прозрачность
     * на краю. Градиент пересоздаётся только при сдвиге точки/радиуса больше чем на 1 px,
     * чтобы пульсация не плодила по объекту на кадр.
     */
    private fun dotShaderAt(x: Float, y: Float, r: Float): Shader {
        val rx = r.roundToInt().toFloat().coerceAtLeast(1f)
        val px = x.roundToInt().toFloat()
        val py = y.roundToInt().toFloat()
        val cached = dotShader
        if (cached != null &&
            rx == dotShaderR &&
            px == dotShaderX &&
            py == dotShaderY
        ) {
            return cached
        }
        dotShaderX = px
        dotShaderY = py
        dotShaderR = rx
        return RadialGradient(
            px,
            py,
            rx,
            intArrayOf(ACCENT_FROM, accentSolid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        ).also { dotShader = it }
    }

    /** Сбрасывает кэш точки (вызывается при смене размера — координаты уже другие). */
    private fun nudgeDotShader() {
        dotShader = null
        dotShaderX = Float.NaN
        dotShaderY = Float.NaN
        dotShaderR = Float.NaN
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Толщина кольца в долях от min(w,h). */
        const val STROKE_FRAC = 0.040f
        const val DIM_STROKE_FRAC = 0.028f

        /** Время: размер и вертикальный сдвиг в долях от min(w,h). */
        const val TIME_SIZE_FRAC = 0.205f
        const val DIM_TIME_SIZE_FRAC = 0.185f
        const val TIME_Y_FRAC = 0.055f

        /** Старт дуги — 12 часов. */
        const val START_ANGLE = -90f

        /** Свечение: множители толщины и alpha (0..255) для двух проходов. */
        const val GLOW_WIDE_STROKE = 2.2f
        const val GLOW_WIDEST_STROKE = 3.4f
        const val GLOW_WIDE_ALPHA = 18
        const val GLOW_WIDEST_ALPHA = 10

        /** Точка на конце: радиус в долях толщины кольца. */
        const val DOT_MIN_SCALE = 1.5f
        const val DOT_MAX_SCALE = 2.2f

        /** Часовые риски: длина 6 px, отступ от кольца 3 px, вес 2 px. */
        const val TICK_LENGTH = 6f
        const val TICK_GAP = 3f
        const val TICK_WEIGHT = 2f
        const val TICK_ALPHA = 40
        const val DIM_TICK_ALPHA = 20

        /** Движение по токенам: наполнение кольца 700 мс, дыхание точки 1400 мс. */
        const val RING_ANIM_MS = 700L
        const val PULSE_ANIM_MS = 1400L

        // --- Цвета строго из docs/design-tokens.md ---
        /** `surface_hi` — фон кольца. */
        val RING_TRACK: Int = Color.parseColor("#18242A")
        /** Приглушённый фон кольца в ambient. */
        val DIM_RING_TRACK: Int = Color.parseColor("#141C1F")
        /** `accent_from`. */
        val ACCENT_FROM: Int = Color.parseColor("#22E0B4")
        /** `accent_to`. */
        val ACCENT_TO: Int = Color.parseColor("#00A9C0")
        /** `text` — время в обычном режиме. */
        val TEXT_MAIN: Int = Color.parseColor("#F2F7F7")
        /** `text_tertiary` — тон рисок в ambient. */
        val TEXT_DIM_TICK: Int = Color.parseColor("#5E7279")

        /**
         * Четыре стопа «#22E0B4 → #00A9C0 → #22E0B4 → #22E0B4»: тёмных стопов в начале нет,
         * переход плавный, а совпадение первого и последнего цвета убирает шов на стыке 0°/360°.
         */
        val GRADIENT_COLORS: IntArray = intArrayOf(ACCENT_FROM, ACCENT_TO, ACCENT_FROM, ACCENT_FROM)
        val GRADIENT_STOPS: FloatArray = floatArrayOf(0f, 0.35f, 0.7f, 1f)
    }
}
