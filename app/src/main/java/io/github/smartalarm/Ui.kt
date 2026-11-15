package io.github.smartalarm

import android.graphics.Color
import android.graphics.Typeface

/**
 * Палитра, шрифты и метрики в одном месте — чтобы все экраны выглядели как одно приложение.
 *
 * Источник правды: docs/design-tokens.md. Устройство одно: OPW2, круг 466×466 px (233 dp).
 * Значения ниже — из токенов; менять их можно только вместе с токенами.
 */
object Ui {

    // ---------- фон и поверхности ----------
    const val BG = "#080C0E"              // запасной сплошной фон (ambient/края)
    const val BG_TOP = "#0A0F12"          // верх градиента экрана
    const val BG_BOTTOM = "#05080A"       // низ градиента экрана
    const val SURFACE = "#111A1E"         // карточки
    const val SURFACE_HI = "#18242A"      // нажатое/выделенное, фон кольца
    const val STROKE = "#1B262B"          // обводка карточек
    const val STROKE_HI = "#24343A"       // обводка активного

    // ---------- акцент ----------
    const val ACCENT = "#00D1A9"
    const val ACCENT_DIM = "#00A88A"
    const val ACCENT_SOFT = "#14322D"
    const val ACCENT_FROM = "#22E0B4"     // градиент: начало
    const val ACCENT_TO = "#00A9C0"       // градиент: конец
    const val ON_ACCENT = "#04211D"       // текст на акцентной заливке

    // ---------- семантика ----------
    const val DANGER = "#FF6B6B"
    const val DANGER_DIM = "#C0392B"

    // ---------- фазы сна ----------
    const val PHASE_DEEP = "#7C6BFF"
    const val PHASE_LIGHT = "#4FC3F7"
    const val PHASE_AWAKE = "#FFB74D"
    const val PHASE_NONE = "#37474F"

    // ---------- текст ----------
    const val TEXT = "#F2F7F7"
    const val TEXT_SECONDARY = "#9FB3B8"
    const val TEXT_TERTIARY = "#5E7279"

    // ---------- метрики экрана ----------
    /** Радиус безопасной зоны (233 − 24). */
    const val SAFE_RADIUS_PX = 209
    /**
     * Полоса индикатора разделов. В v1 точки стояли на 406…426 и наезжали на контент
     * (на главном — на кнопку); с 412…424 они целиком в круге и ниже контента экранов 2–3.
     */
    const val DOTS_TOP_PX = 412
    const val DOTS_BOTTOM_PX = 424

    val bg: Int get() = Color.parseColor(BG)
    val surface: Int get() = Color.parseColor(SURFACE)
    val surfaceHi: Int get() = Color.parseColor(SURFACE_HI)
    val stroke: Int get() = Color.parseColor(STROKE)
    val accent: Int get() = Color.parseColor(ACCENT)
    val accentDim: Int get() = Color.parseColor(ACCENT_DIM)
    val accentSoft: Int get() = Color.parseColor(ACCENT_SOFT)
    val accentFrom: Int get() = Color.parseColor(ACCENT_FROM)
    val accentTo: Int get() = Color.parseColor(ACCENT_TO)
    val onAccent: Int get() = Color.parseColor(ON_ACCENT)
    val danger: Int get() = Color.parseColor(DANGER)
    val text: Int get() = Color.parseColor(TEXT)
    val textSecondary: Int get() = Color.parseColor(TEXT_SECONDARY)
    val textTertiary: Int get() = Color.parseColor(TEXT_TERTIARY)

    val phaseDeep: Int get() = Color.parseColor(PHASE_DEEP)
    val phaseLight: Int get() = Color.parseColor(PHASE_LIGHT)
    val phaseAwake: Int get() = Color.parseColor(PHASE_AWAKE)
    val phaseNone: Int get() = Color.parseColor(PHASE_NONE)

    /** Тонкий шрифт для времени — как на вендорских циферблатах. */
    fun clockTypeface(): Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    /** Средний вес для подписей и кнопок. */
    fun labelTypeface(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Обычный вес для длинного текста (сводка ночи). */
    fun bodyTypeface(): Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    /** Цвет фазы сна. */
    fun phaseColor(stage: SleepStats.Stage): Int = when (stage) {
        SleepStats.Stage.DEEP -> phaseDeep
        SleepStats.Stage.LIGHT -> phaseLight
        SleepStats.Stage.AWAKE -> phaseAwake
        SleepStats.Stage.UNKNOWN -> phaseNone
    }
}
