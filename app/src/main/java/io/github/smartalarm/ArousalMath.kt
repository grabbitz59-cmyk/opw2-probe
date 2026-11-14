package io.github.smartalarm

/**
 * Чистая математика «умного» будильника: без Android, без состояния.
 *
 * Вынесена отдельно, чтобы её проверяли JVM-тесты (`ArousalMathTest`), а не каждая
 * ночь на часах. Все пороги откалиброваны по реальным данным OPW2:
 *   спокойная рука: moves/n = 0.27–0.8 %, std = 0.21–0.32 м/с², p2p = 3.3–3.5 м/с²;
 *   движение/переворот: moves/n 5–10 %, std 1.1–1.7, p2p 8–12.
 */
object ArousalMath {

    const val BASELINE_WINDOW_MS = 45 * 60_000L
    const val BASELINE_MIN_SAMPLES = 8
    const val HR_RISE_BPM = 8.0f
    const val QUIET_FOR_ASLEEP_MS = 20 * 60_000L
    const val SAMPLE_MIN_GAP_MS = 60_000L

    const val MOTION_MOVES_PCT = 4.0
    const val MOTION_STD = 0.80
    const val MOTION_P2P = 8.0

    /** Доля «рывков» от числа сэмплов, %. При n=0 возвращает 0. */
    fun movesPct(n: Int, moves: Int): Double =
        if (n <= 0) 0.0 else 100.0 * moves / n

    /**
     * Движение в бакете.
     *
     * Пустой бакет (n == 0) движением НЕ считается: сенсорный батчинг на часах
     * регулярно отдаёт пустые окна, и раньше это ломало детект сна.
     */
    fun isMotion(n: Int, moves: Int, std: Double, p2p: Double): Boolean {
        if (n <= 0) return false
        return movesPct(n, moves) >= MOTION_MOVES_PCT || std > MOTION_STD || p2p > MOTION_P2P
    }

    /** Медиана; null для пустого списка. */
    fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
    }

    /** Значения HR из окна [now - windowMs, now]. */
    fun windowValues(samples: List<Pair<Long, Float>>, now: Long,
                     windowMs: Long = BASELINE_WINDOW_MS): List<Float> =
        samples.filter { it.first in (now - windowMs)..now }.map { it.second }

    /** Базовая линия: медиана уникальных значений окна, если их достаточно. */
    fun baseline(samples: List<Pair<Long, Float>>, now: Long,
                 windowMs: Long = BASELINE_WINDOW_MS,
                 minSamples: Int = BASELINE_MIN_SAMPLES): Float? {
        val vals = windowValues(samples, now, windowMs)
        if (vals.size < minSamples) return null
        return median(vals)
    }

    /** Подъём пульса над базой, значимый для arousal. */
    fun hrRise(hr: Float?, baseline: Float?, threshold: Float = HR_RISE_BPM): Boolean {
        if (hr == null || baseline == null) return false
        return (hr - baseline) >= threshold
    }

    /**
     * Добавлять ли значение в выборку: одно и то же значение чаще раза в [minGapMs]
     * не добавляем, иначе пассивный HR надувает выборку дублями и медиана «залипает».
     */
    fun shouldAddSample(lastValue: Float, lastAt: Long, newValue: Float, now: Long,
                        minGapMs: Long = SAMPLE_MIN_GAP_MS): Boolean {
        if (lastAt <= 0L) return true
        return !(newValue == lastValue && now - lastAt < minGapMs)
    }

    /** Косвенный детект сна: достаточно долго тихо и есть чем считать базу. */
    fun asleepInferred(quietMinutes: Long, uniqueHrSamples: Int): Boolean =
        quietMinutes >= QUIET_FOR_ASLEEP_MS / 60_000L && uniqueHrSamples >= BASELINE_MIN_SAMPLES

    /**
     * Можно ли реагировать на сигнал arousal. Раньше требовалось обязательное
     * `sawAsleep`, из-за чего системный сигнал AWAKE игнорировался, если косвенный
     * детект сна не сработал. Теперь достаточно либо детекта, либо 20 минут тишины.
     */
    fun arousalAllowed(sawAsleep: Boolean, quietMinutes: Long): Boolean =
        sawAsleep || quietMinutes >= QUIET_FOR_ASLEEP_MS / 60_000L
}
