package io.github.smartalarm

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Экран 1 «ПОДЪЁМ»: весь ночной инструмент в одном View — обод со шкалой, дуга отсчёта,
 * время подъёма, статус и дуга-рычаг. Дизайн: `docs/design-v2.md` §3.1, §4.1, §4.2.
 *
 * Почему один View, а не набор TextView:
 *  - все элементы привязаны к радиусу и углу (шкала, дуга, рычаг), а не к сетке — их невозможно
 *    «сдвинуть» layout-параметром и легко удержать внутри круга 209 px;
 *  - рычаг — annulus-сектор; его hit-тест всё равно живёт в `onTouchEvent` и не должен
 *    конкурировать с дочерними View за касание.
 *
 * Анимируется только дыхание янтаря при записи; fade-переходов нет (принцип 6).
 */
class NightDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // ------------------------------------------------------------------ состояние

    /** «СЕЙЧАС 23:40» — текущее время строкой. */
    var nowText: String = "СЕЙЧАС 00:00"
        set(v) { field = v; invalidate() }

    /** Время подъёма «07:12» — герой экрана. */
    var targetText: String = "07:00"
        set(v) { field = v; invalidate() }

    /** `НЕ ВЗВЕДЁН` / `ВЗВЕДЁН` / `ЗАПИСЬ · 84%` / `НЕТ ПРАВ` — словами (R7). */
    var stateText: String = "НЕ ВЗВЕДЁН"
        set(v) { field = v; invalidate() }

    /** `ОКНО ±30 МИН · БУДНИ` или `ЧЕРЕЗ 7 Ч 12 М`. */
    var detailText: String = ""
        set(v) { field = v; invalidate() }

    /** Цвет статуса: `paper2` / `ember` / `signalStop`. */
    var stateColor: Int = Ui.paper2
        set(v) { field = v; invalidate() }

    /** Взведён: герой и дуга становятся янтарными. */
    var armed: Boolean = false
        set(v) { if (field == v) return; field = v; invalidate() }

    /** Идёт запись: точка на дуге дышит, рычаг — «стоп». */
    var recording: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            setBreathing(v)
            invalidate()
        }

    /** Текст на дуге-рычаге: `СПАТЬ` / `НАЧАТЬ` / `СТОП`. */
    var leverText: String = "СПАТЬ"
        set(v) { field = v; invalidate() }

    /** Минуты от полуночи для маркера «сейчас» (-1 — не рисовать). */
    var nowMinutes: Int = -1
        set(v) { field = v; invalidate() }

    /** Осталось минут до подъёма — длина дуги (≤ 0 — дуги нет). */
    var remainingMinutes: Int = 0
        set(v) { field = v; invalidate() }

    /** Главное действие (рычаг). */
    var onPrimaryTap: (() -> Unit)? = null

    /** Тап по времени подъёма / долгое нажатие — редактор времени. */
    var onTimeTap: (() -> Unit)? = null

    /** Ambient/AOD: только два времени, шкала и тонкая дуга. */
    private var dimmed = false

    /** 0..1 — фаза дыхания точки при записи. */
    private var breath = 0f
    private var breathAnimator: ValueAnimator? = null

    /** Нажатие: подсветка рычага/времени и отсечение клика при жесте-свайпе. */
    private var leverPressed = false
    private var heroPressed = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressFired = false
    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable {
        if (!moved) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTimeTap?.invoke()
        }
        longPressFired = true
    }

    // ------------------------------------------------------------------ краски

    private val tickMinor = Ui.strokePaint(Ui.bezelMinor, 2f)
    private val tickMajor = Ui.strokePaint(Ui.bezelMajor, 2f)
    private val ringPaint = Ui.strokePaint(Ui.paper3, 1f)
    private val nowMarker = Ui.strokePaint(Ui.paper2, 3f)
    private val arcPaint = Ui.strokePaint(Ui.ember, ARC_W).apply { strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Ui.fillPaint(Ui.emberHot)
    private val leverFill = Ui.fillPaint(Ui.emberAsh)
    private val leverEdge = Ui.strokePaint(Ui.emberDim, 1f)

    private val nowPaint = Ui.textPaint(Ui.SZ_NOW, Ui.paper3, Ui.LS_NOW)
    private val labelPaint = Ui.textPaint(Ui.SZ_LABEL, Ui.paper3, Ui.LS_LABEL)
    private val heroPaint = Ui.textPaint(Ui.SZ_HERO, Ui.paper, Ui.LS_TIGHT)
    private val statePaint = Ui.textPaint(Ui.SZ_STATE, Ui.paper2, Ui.LS_STATE)
    private val detailPaint = Ui.textPaint(Ui.SZ_DETAIL, Ui.paper3, Ui.LS_DETAIL)
    private val leverTextPaint = Ui.textPaint(Ui.SZ_LEVER, Ui.onEmber, Ui.LS_LEVER)

    private val leverPath = Path()
    private val arcRect = RectF()
    private val fm = Paint.FontMetrics()

    // ------------------------------------------------------------------ публичное

    /** Ambient: гасим всё до двух времён, шкалы и тонкой дуги, анимации глушим. */
    fun setDimmed(on: Boolean) {
        if (dimmed == on) return
        dimmed = on
        if (on) {
            breathAnimator?.cancel()
            breathAnimator = null
            breath = 0f
        } else {
            setBreathing(recording)
        }
        leverPressed = false
        heroPressed = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        breathAnimator?.cancel()
        breathAnimator = null
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ ввод

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dimmed) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                moved = false
                longPressFired = false
                leverPressed = inLever(ev.x, ev.y)
                heroPressed = inHero(ev.x, ev.y)
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, LONG_PRESS_MS)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(ev.x - downX, ev.y - downY) > SLOP) {
                    moved = true
                    handler.removeCallbacks(longPress)
                    if (leverPressed || heroPressed) {
                        leverPressed = false
                        heroPressed = false
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val wasLever = leverPressed
                val wasHero = heroPressed
                leverPressed = false
                heroPressed = false
                invalidate()
                if (moved || longPressFired) return true
                if (wasLever) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onPrimaryTap?.invoke()
                } else if (wasHero) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onTimeTap?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                leverPressed = false
                heroPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** Рычаг: annulus-сектор r 110…209, ±46° от вертикали «вниз» (≈96 × 250 px касания). */
    private fun inLever(x: Float, y: Float): Boolean {
        val dx = x - CX
        val dy = y - CY
        val r = hypot(dx, dy)
        if (r < LEVER_HIT_R_IN || r > Ui.SAFE_R) return false
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        var diff = abs(angle - 90f)
        if (diff > 180f) diff = 360f - diff
        return diff <= LEVER_HIT_HALF_ANGLE
    }

    /** Время подъёма: полоса y 182…300, |x − 233| ≤ 130. */
    private fun inHero(x: Float, y: Float): Boolean =
        abs(x - CX) <= HERO_HALF_W && y >= HERO_BAND_TOP && y <= HERO_BAND_BOTTOM

    // ------------------------------------------------------------------ отрисовка

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = minOf(w, h)
        if (base <= 0f) return
        // Всё спроектировано под 466 px; в превью/другом размере масштабируем от центра.
        val k = base / REF_PX
        val cx = w / 2f
        val cy = h / 2f

        drawBezel(canvas, cx, cy, k)
        drawCountdown(canvas, cx, cy, k)
        if (!dimmed) drawLever(canvas, cx, cy, k)
        drawTexts(canvas, cx, cy, k)
    }

    /** Обод: те же 72 риски, что на экранах 2–3 (общий код — [Bezel.drawTicks]). */
    private fun drawBezel(canvas: Canvas, cx: Float, cy: Float, k: Float) {
        val alpha = if (dimmed) Bezel.AMB_TICK_ALPHA else 255
        tickMinor.alpha = alpha
        tickMajor.alpha = alpha
        Bezel.drawTicks(canvas, cx, cy, k, tickMinor, tickMajor)
        if (dimmed) {
            // R9: в AOD на экране должно остаться «тонкое кольцо». Риски при 30 % яркости дают
            // на чёрном около (10,11,12) — это невидимо, поэтому кольцо рисуется отдельно,
            // 1 px бумагой с alpha AMB_RING_ALPHA, и шкала поверх него читается как шкала.
            ringPaint.strokeWidth = 1f * k
            ringPaint.color = Ui.alpha(Ui.paper3, AMB_RING_ALPHA)
            canvas.drawCircle(cx, cy, AMB_RING_R * k, ringPaint)
        }
        if (nowMinutes >= 0) {
            val a = Math.toRadians(angleOf(nowMinutes).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            canvas.drawLine(
                cx + NOW_R_IN * k * ca, cy + NOW_R_IN * k * sa,
                cx + NOW_R_OUT * k * ca, cy + NOW_R_OUT * k * sa,
                nowMarker,
            )
        }
    }

    /** Дуга отсчёта: длина = `осталось / 12 ч · 360°`, старт на угле текущего времени. */
    private fun drawCountdown(canvas: Canvas, cx: Float, cy: Float, k: Float) {
        if (remainingMinutes <= 0) return
        val r = ARC_R * k
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        val sweep = (remainingMinutes.coerceAtMost(12 * 60) / (12f * 60f)) * 360f
        val start = angleOf(if (nowMinutes >= 0) nowMinutes else 0)
        if (dimmed) {
            arcPaint.strokeWidth = 2f * k
            arcPaint.color = Ui.emberDim
            canvas.drawArc(arcRect, start, sweep, false, arcPaint)
            return
        }
        arcPaint.strokeWidth = ARC_W * k
        arcPaint.color = Ui.ember
        canvas.drawArc(arcRect, start, sweep, false, arcPaint)

        // Точка на конце дуги; при записи — дыхание Ø 12…26 px.
        val end = Math.toRadians((start + sweep).toDouble())
        val px = cx + r * cos(end).toFloat()
        val py = cy + r * sin(end).toFloat()
        val dotR = (DOT_R + (DOT_R_MAX - DOT_R) * (if (recording) breath else 0f)) * k
        dotPaint.color = Ui.emberHot
        canvas.drawCircle(px, py, dotR, dotPaint)
    }

    /**
     * Дуга-рычаг: annulus-сектор r 172…206, θ ∈ [52°, 128°] (76° внизу круга),
     * подпись идёт по дуге — так текст не конфликтует с формой.
     */
    private fun drawLever(canvas: Canvas, cx: Float, cy: Float, k: Float) {
        val rOut = LEVER_R_OUT * k
        val rIn = LEVER_R_IN * k
        leverPath.reset()
        val outer = RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
        val inner = RectF(cx - rIn, cy - rIn, cx + rIn, cy + rIn)
        leverPath.addArc(outer, LEVER_START, LEVER_SWEEP)
        leverPath.arcTo(inner, LEVER_START + LEVER_SWEEP, -LEVER_SWEEP)
        leverPath.close()

        val stop = recording
        val base = when {
            stop -> Ui.signalStop
            armed -> Ui.ember
            // Неактивный рычаг: пепел на 1.25:1 к фону не читался как форма (замер на устройстве),
            // поэтому заливка чуть светлее и кромка толще — рычаг видно, но акцентом он не стал.
            else -> Ui.emberAshHi
        }
        leverFill.color = if (leverPressed) lighten(base, if (stop || armed) 0.9f else 1.2f) else base
        canvas.drawPath(leverPath, leverFill)
        if (!armed && !stop) {
            leverEdge.strokeWidth = 2f * k
            leverEdge.color = Ui.alpha(Ui.ember, if (leverPressed) 255 else 200)
            canvas.drawPath(leverPath, leverEdge)
        }

        leverTextPaint.color = when {
            stop -> Ui.onStop
            armed -> Ui.onEmber
            else -> Ui.ember
        }
        drawTextOnArc(canvas, cx, cy, (rIn + rOut) / 2f, leverText, leverTextPaint)
    }

    /** Раскладка текста по дуге: каждый символ поворачивается вокруг центра. */
    private fun drawTextOnArc(canvas: Canvas, cx: Float, cy: Float, r: Float, text: String, paint: Paint) {
        if (text.isEmpty() || r <= 0f) return
        val gap = paint.letterSpacing * paint.textSize
        val widths = FloatArray(text.length) { paint.measureText(text[it].toString()) + gap }
        val total = widths.sum()
        val totalDeg = ((total / r) * (180.0 / Math.PI)).toFloat()
        paint.getFontMetrics(fm)
        // Полуразность метрик: чтобы центр глифа сидел точно на окружности r.
        val centerShift = (fm.ascent + fm.descent) / 2f
        var angle = 90f - totalDeg / 2f
        var acc = 0f
        for (i in text.indices) {
            val charAngle = angle + ((acc + widths[i] / 2f) / r) * (180.0 / Math.PI).toFloat()
            canvas.save()
            canvas.rotate(charAngle - 90f, cx, cy)
            canvas.drawText(text[i].toString(), cx, cy + r - centerShift, paint)
            canvas.restore()
            acc += widths[i]
        }
    }

    /** Тексты: «сейчас», «ПОДЪЁМ», герой, статус, деталь (в ambient — только два времени). */
    private fun drawTexts(canvas: Canvas, cx: Float, cy: Float, k: Float) {
        if (dimmed) {
            band(canvas, cx, cy, k, targetText, heroPaint, Ui.SZ_HERO_AOD, Ui.paper2, HERO_BAND_TOP, HERO_BAND_BOTTOM)
            band(canvas, cx, cy, k, nowText, nowPaint, AMB_NOW_SIZE, Ui.paper3, AMB_NOW_TOP, AMB_NOW_BOTTOM)
            return
        }
        band(canvas, cx, cy, k, nowText, nowPaint, Ui.SZ_NOW, Ui.paper3, NOW_TOP, NOW_BOTTOM)
        band(canvas, cx, cy, k, "ПОДЪЁМ", labelPaint, Ui.SZ_LABEL, Ui.paper3, LABEL_TOP, LABEL_BOTTOM)
        band(
            canvas, cx, cy, k, targetText, heroPaint, Ui.SZ_HERO,
            if (armed) Ui.ember else Ui.paper, HERO_BAND_TOP, HERO_BAND_BOTTOM,
        )
        band(canvas, cx, cy, k, stateText, statePaint, Ui.SZ_STATE, stateColor, STATE_TOP, STATE_BOTTOM)
        if (detailText.isNotEmpty()) {
            band(canvas, cx, cy, k, detailText, detailPaint, Ui.SZ_DETAIL, Ui.paper3, DETAIL_TOP, DETAIL_BOTTOM)
        }
    }

    /** Центрирует строку по X и по вертикали в полосе [top, bottom] (координаты при 466 px). */
    @Suppress("LongParameterList")
    private fun band(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        k: Float,
        text: String,
        paint: Paint,
        sizePx: Float,
        color: Int,
        top: Float,
        bottom: Float,
    ) {
        if (text.isEmpty()) return
        paint.textSize = sizePx * k
        paint.color = color
        paint.getFontMetrics(fm)
        val t = cy + (top - CY) * k
        val b = cy + (bottom - CY) * k
        val baseline = t + (b - t - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.drawText(text, cx, baseline, paint)
    }

    // ------------------------------------------------------------------ служебное

    /** Угол canvas (0° = 3 часа, по часовой) для времени в минутах от полуночи. */
    private fun angleOf(minutes: Int): Float {
        val span = 12 * 60
        val m = ((minutes % span) + span) % span
        return m / span.toFloat() * 360f - 90f
    }

    private fun setBreathing(on: Boolean) {
        breathAnimator?.cancel()
        breathAnimator = null
        if (!on || dimmed) {
            breath = 0f
            return
        }
        breathAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                breath = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Сдвиг яркости для нажатого состояния: 12 % вверх/вниз, мгновенно. */
    private fun lighten(color: Int, factor: Float): Int {
        fun ch(v: Int) = (v * factor).toInt().coerceIn(0, 255)
        return android.graphics.Color.argb(
            android.graphics.Color.alpha(color),
            ch(android.graphics.Color.red(color)),
            ch(android.graphics.Color.green(color)),
            ch(android.graphics.Color.blue(color)),
        )
    }

    private companion object {
        /** Референсный размер: все константы ниже — в px при экране 466. */
        const val REF_PX = 466f
        const val CX = 233f
        const val CY = 233f

        // --- обод: геометрия рисок живёт в Bezel (общий код для всех трёх экранов) ---

        // --- маркер «сейчас» ---
        const val NOW_R_IN = 195f
        const val NOW_R_OUT = 207f

        // --- дуга отсчёта ---
        const val ARC_R = 190f
        const val ARC_W = 4f
        const val DOT_R = 6f
        const val DOT_R_MAX = 13f
        const val BREATH_MS = 1400L

        // --- рычаг: annulus-сектор внизу, внутри обода (обод и дуга отсчёта остаются чистыми) ---
        const val LEVER_R_OUT = 186f
        const val LEVER_R_IN = 152f
        const val LEVER_START = 52f
        const val LEVER_SWEEP = 76f
        const val LEVER_HIT_R_IN = 110f
        const val LEVER_HIT_HALF_ANGLE = 46f

        // --- зона тапа по времени ---
        const val HERO_HALF_W = 130f

        const val SLOP = 24f
        const val LONG_PRESS_MS = 500L

        // --- полосы текста (y при 466) ---
        const val NOW_TOP = 100f
        const val NOW_BOTTOM = 132f
        const val LABEL_TOP = 150f
        const val LABEL_BOTTOM = 176f
        const val HERO_BAND_TOP = 182f
        const val HERO_BAND_BOTTOM = 290f
        const val STATE_TOP = 296f
        const val STATE_BOTTOM = 324f
        const val DETAIL_TOP = 330f
        const val DETAIL_BOTTOM = 354f

        // --- полосы в ambient ---
        const val AMB_NOW_TOP = 300f
        const val AMB_NOW_BOTTOM = 330f
        const val AMB_NOW_SIZE = 24f

        /** AOD: риски светлее обычного «гашения», плюс отдельное тонкое кольцо (R9). */
        const val AMB_TICK_ALPHA = 150
        const val AMB_RING_ALPHA = 110
        const val AMB_RING_R = 200f
    }
}
