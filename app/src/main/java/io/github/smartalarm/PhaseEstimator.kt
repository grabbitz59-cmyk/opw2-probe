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

    fun estimate(hrNow: Float, hrMedian: Float?, motionRecent: Boolean): SleepStats.Stage {
        if (motionRecent) return SleepStats.Stage.AWAKE
        if (hrNow <= 0f || hrMedian == null || hrMedian <= 0f) return SleepStats.Stage.UNKNOWN
        return if (hrNow <= hrMedian * DEEP_HR_FACTOR) SleepStats.Stage.DEEP
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
