package io.github.smartalarm

/**
 * Живая оценка фазы сна в конкретный момент (для момента срабатывания будильника).
 *
 * Правила те же, что в [SleepStats.stageOf], но работают по «сейчас»:
 *  - недавнее движение (за последние ~2 минуты) → бодрствование;
 *  - пульс ниже ночной медианы на 4 % и больше → глубокий;
 *  - иначе лёгкий;
 *  - нет данных о пульсе → неизвестно.
 *
 * Честно: это оценочные фазы (по HR + актиграфии), а не N1/N2/N3/REM с полисомнографа.
 */
object PhaseEstimator {

    const val DEEP_HR_FACTOR = 0.96f

    /** Считаем «бодрствованием», если движение было не позже этого окна. */
    const val MOTION_RECENT_MS = 2 * 60_000L

    /**
     * Насколько старым может быть последний пульс, чтобы им ещё можно было оценить фазу.
     *
     * Ночью Health Services отдаёт пульс с большими паузами: в реальную ночь последний
     * сэмпл был за 41 минуту до срабатывания, и без запаса фаза всегда была бы «unknown».
     */
    const val MAX_HR_AGE_MIN = 75f

    fun estimate(hrNow: Float, hrMedian: Float?, motionRecent: Boolean): SleepStats.Stage =
        estimate(hrNow, hrAgeMin = 0f, hrMedian, hrMedian, motionRecent)

    /**
     * Расширенная оценка: медиана «сейчас» может быть недоступна (мало свежих сэмплов),
     * тогда берём медиану за всю ночь. Возраст пульса ограничиваем [MAX_HR_AGE_MIN].
     */
    fun estimate(
        hrNow: Float,
        hrAgeMin: Float,
        hrMedianWindow: Float?,
        hrMedianSession: Float?,
        motionRecent: Boolean,
    ): SleepStats.Stage {
        if (motionRecent) return SleepStats.Stage.AWAKE
        if (hrNow <= 0f || hrAgeMin > MAX_HR_AGE_MIN) return SleepStats.Stage.UNKNOWN
        val median = hrMedianWindow ?: hrMedianSession
        if (median == null || median <= 0f) return SleepStats.Stage.UNKNOWN
        return if (hrNow <= median * DEEP_HR_FACTOR) SleepStats.Stage.DEEP
        else SleepStats.Stage.LIGHT
    }

    fun label(stage: SleepStats.Stage): String = when (stage) {
        SleepStats.Stage.AWAKE -> "бодрствование"
        SleepStats.Stage.LIGHT -> "лёгкий сон"
        SleepStats.Stage.DEEP -> "глубокий сон"
        SleepStats.Stage.UNKNOWN -> "не определено"
    }

    /** Короткий код для CSV-колонки `wake_phase`. */
    fun code(stage: SleepStats.Stage): String = when (stage) {
        SleepStats.Stage.AWAKE -> "awake"
        SleepStats.Stage.LIGHT -> "light"
        SleepStats.Stage.DEEP -> "deep"
        SleepStats.Stage.UNKNOWN -> "unknown"
    }

    fun fromCode(code: String): SleepStats.Stage = when (code.trim().lowercase()) {
        "awake" -> SleepStats.Stage.AWAKE
        "light" -> SleepStats.Stage.LIGHT
        "deep" -> SleepStats.Stage.DEEP
        else -> SleepStats.Stage.UNKNOWN
    }
}
