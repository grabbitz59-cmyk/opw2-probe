package io.github.smartalarm

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

/**
 * Токены дизайна v2 «Ночной инструмент». Источник правды: `docs/design-v2.md`.
 *
 * Устройство одно: OnePlus Watch 2 — круг 466×466 px, 320 dpi, 1 dp = 2 px.
 * Язык: гравированная шкала, тлеющий янтарь, бумажный condensed-шрифт, волосяные линейки.
 * Никаких карточек, теней и градиентов: только линейка (1 px), шкала и свет.
 *
 * Все размеры текста — в **px** (не sp), потому что на этом экране пиксель и есть единица
 * дизайна. Для TextView используется [TypedValue.COMPLEX_UNIT_PX].
 */
object Ui {

    // ---------------------------------------------------------------- палитра

    /** Фон всех экранов — плоский, без градиента. */
    const val BG = "#0A0A0B"
    const val BG_AMBIENT = "#000000"

    /** Обод: минутные и часовые риски шкалы. */
    const val BEZEL_MINOR = "#23262A"
    const val BEZEL_MAJOR = "#34383D"

    /** Волосяная линейка 1 px. */
    const val RULE = "#26272B"
    const val RULE_STRONG = "#3A3D42"

    /** Единственный акцент — янтарь. */
    const val EMBER = "#FFA24C"
    const val EMBER_HOT = "#FFD2A0"
    const val EMBER_DIM = "#8A6034"
    const val EMBER_ASH = "#2A2118"
    const val ON_EMBER = "#1A1206"

    /** Бумага: тёплый белый и два уровня притушённости. */
    const val PAPER = "#F2EDE6"
    const val PAPER_2 = "#A8A29A"
    const val PAPER_3 = "#6E6862"

    /** Семантика (не акцент): только остановка записи и отсутствие прав. */
    const val SIGNAL_STOP = "#E8654F"
    const val ON_STOP = "#FFF3EC"

    /** Фазы сна кодируются высотой и яркостью, не оттенком (принцип 3). */
    const val PHASE_AWAKE = "#3E4348"
    const val PHASE_NONE = "#17191C"

    // ---------------------------------------------------------------- типографика (px)

    const val SZ_HERO = 104f      // время подъёма
    const val SZ_HERO_AOD = 88f   // то же в ambient
    const val SZ_NOW = 36f        // строка «сейчас»
    const val SZ_STATE = 36f      // статус
    const val SZ_LABEL = 32f      // «ПОДЪЁМ», подписи
    const val SZ_DETAIL = 30f     // «ЧЕРЕЗ 7 Ч 12 М»
    const val SZ_TITLE = 28f      // заголовки экранов 2–3
    const val SZ_LEVER = 26f      // текст по дуге рычага
    const val SZ_ROW_LABEL = 26f
    const val SZ_ROW_VALUE = 34f
    const val SZ_LEGEND = 26f
    const val SZ_METRICS = 26f
    const val SZ_BIG = 52f        // «ЛЁГКИЙ»
    const val SZ_TELEMETRY = 24f
    const val SZ_TELEMETRY_SMALL = 22f

    const val LS_TIGHT = -0.02f
    const val LS_NOW = 0.16f
    const val LS_LABEL = 0.28f
    const val LS_STATE = 0.12f
    const val LS_DETAIL = 0.10f
    const val LS_LEVER = 0.12f
    const val LS_TITLE = 0.22f
    const val LS_ROW_LABEL = 0.18f
    const val LS_TELEMETRY = 0.06f

    // ---------------------------------------------------------------- геометрия

    const val CX = 233f
    const val CY = 233f
    /** Безопасный радиус контента: R − 24. */
    const val SAFE_R = 209f
    /** Внешний радиус обода (риски/дуга живут внутри 208). */
    const val BEZEL_OUT = 208f
    /** Внутренний радиус обода: контент не заходит внутрь этой окружности. */
    const val BEZEL_IN = 188f

    /**
     * Полоса индикатора раздела. Не выше y 60: на y 42 точка уже вне окружности r = 188,
     * где живёт обод, — индикатор наезжал бы на шкалу.
     */
    const val INDEX_TOP = 60f
    const val INDEX_BOTTOM = 72f

    // ---------------------------------------------------------------- цвета как int

    val bg: Int get() = Color.parseColor(BG)
    val bgAmbient: Int get() = Color.parseColor(BG_AMBIENT)
    val bezelMinor: Int get() = Color.parseColor(BEZEL_MINOR)
    val bezelMajor: Int get() = Color.parseColor(BEZEL_MAJOR)
    val rule: Int get() = Color.parseColor(RULE)
    val ember: Int get() = Color.parseColor(EMBER)
    val emberHot: Int get() = Color.parseColor(EMBER_HOT)
    val emberDim: Int get() = Color.parseColor(EMBER_DIM)
    val emberAsh: Int get() = Color.parseColor(EMBER_ASH)
    val onEmber: Int get() = Color.parseColor(ON_EMBER)
    val paper: Int get() = Color.parseColor(PAPER)
    val paper2: Int get() = Color.parseColor(PAPER_2)
    val paper3: Int get() = Color.parseColor(PAPER_3)
    val signalStop: Int get() = Color.parseColor(SIGNAL_STOP)
    val onStop: Int get() = Color.parseColor(ON_STOP)
    val phaseAwakeColor: Int get() = Color.parseColor(PHASE_AWAKE)
    val phaseNoneColor: Int get() = Color.parseColor(PHASE_NONE)

    /** Тинт: тот же цвет с прозрачностью `alpha` (0..255). */
    fun alpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    // ---------------------------------------------------------------- шрифты

    /** Гравировка: condensed без «воздушного» light. */
    fun condensed(): Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)

    /** Более плотный вес — для значений и героя, если condensed-medium доступен. */
    fun condensedMedium(): Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    // ---------------------------------------------------------------- фабрики

    /** Краска для canvas-текста: центрированная по X, размер в px, трекинг в em. */
    fun textPaint(px: Float, color: Int, letterSpacing: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = px
            typeface = condensed()
            this.letterSpacing = letterSpacing
            textAlign = Paint.Align.CENTER
        }

    /** Краска волосяной линии. */
    fun strokePaint(color: Int, width: Float): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = width
            style = Paint.Style.STROKE
        }

    /** Заливка. */
    fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

    /** TextView в токенах v2: размер в px, condensed, заданный трекинг и цвет. */
    fun label(
        ctx: Context,
        text: String,
        px: Float,
        color: Int,
        letterSpacing: Float = 0f,
        caps: Boolean = false,
    ): TextView = TextView(ctx).apply {
        this.text = if (caps) text.uppercase() else text
        setTextColor(color)
        typeface = condensed()
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        this.letterSpacing = letterSpacing
        includeFontPadding = false
        setSingleLine(true)
    }

    /** Цвет фазы сна: один янтарь, разная яркость (см. §4.3 design-v2). */
    fun phaseColor(stage: SleepStats.Stage): Int = when (stage) {
        SleepStats.Stage.DEEP -> ember
        SleepStats.Stage.LIGHT -> emberDim
        SleepStats.Stage.AWAKE -> phaseAwakeColor
        SleepStats.Stage.UNKNOWN -> phaseNoneColor
    }

    /** Русское короткое имя фазы для легенды и состояния. */
    fun phaseShort(stage: SleepStats.Stage): String = when (stage) {
        SleepStats.Stage.DEEP -> "ГЛУБ"
        SleepStats.Stage.LIGHT -> "ЛЁГК"
        SleepStats.Stage.AWAKE -> "БОДР"
        SleepStats.Stage.UNKNOWN -> "—"
    }

    // ---------------------------------------------------------------- совместимость

    /** Волосяная линейка повышенной яркости — разделитель блоков и дорожки слайдеров. */
    val ruleStrong: Int get() = Color.parseColor(RULE_STRONG)
}
