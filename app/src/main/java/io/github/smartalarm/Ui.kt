package io.github.smartalarm

import android.graphics.Color
import android.graphics.Typeface

/**
 * Палитра и шрифты в одном месте — чтобы экраны выглядели как одно приложение,
 * а не как набор разных Activity.
 */
object Ui {

    // Фон и поверхности
    const val BG = "#080C0E"            // почти чёрный, как у OHealth в тёмной теме
    const val SURFACE = "#131A1D"       // карточки/капсулы
    const val SURFACE_HI = "#1B2529"    // нажатое/выделенное

    // Акцент OnePlus/OHealth: тёплый зелёно-бирюзовый
    const val ACCENT = "#00D1A9"
    const val ACCENT_DIM = "#00A88A"
    const val ACCENT_SOFT = "#14322D"   // заливка под графиком

    // Семантика
    const val DANGER = "#FF6B6B"
    const val DANGER_DIM = "#C0392B"

    // Текст
    const val TEXT = "#FFFFFF"
    const val TEXT_SECONDARY = "#9FB3B8"
    const val TEXT_TERTIARY = "#5C7075"

    val bg: Int get() = Color.parseColor(BG)
    val surface: Int get() = Color.parseColor(SURFACE)
    val surfaceHi: Int get() = Color.parseColor(SURFACE_HI)
    val accent: Int get() = Color.parseColor(ACCENT)
    val accentDim: Int get() = Color.parseColor(ACCENT_DIM)
    val accentSoft: Int get() = Color.parseColor(ACCENT_SOFT)
    val danger: Int get() = Color.parseColor(DANGER)
    val text: Int get() = Color.parseColor(TEXT)
    val textSecondary: Int get() = Color.parseColor(TEXT_SECONDARY)
    val textTertiary: Int get() = Color.parseColor(TEXT_TERTIARY)

    /** Тонкий шрифт для времени — как на вендорских циферблатах. */
    fun clockTypeface(): Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    /** Средний вес для подписей и кнопок. */
    fun labelTypeface(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}
