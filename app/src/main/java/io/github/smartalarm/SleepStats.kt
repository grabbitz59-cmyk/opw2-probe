package io.github.smartalarm

/**
 * Разбор и агрегация ночного CSV + оценка «фаз» по доступным данным.
 *
 * Важно: настоящих фаз (N1/N2/N3/REM) на OPW2 получить нельзя — нет HRV (сырой PPG
 * не содержит кардиокомпоненты) и нет системного сна наружу. Поэтому фазы здесь
 * **выведенные**: бодрствование по движению, глубокий/лёгкий сон по уровню пульса
 * относительно ночной медианы. На графике это полосы, как в OHealth, но подписаны
 * как оценочные.
 */
object SleepStats {

    const val BUCKET_MS = 30_000L
    private const val QUIET_BREAK_MS = 5 * 60_000L
    private const val MIN_EPISODE_BUCKETS = 2
    private const val DEEP_HR_FACTOR = 0.96f   // ниже медианы на 4 % — «глубже»

    enum class Stage { AWAKE, LIGHT, DEEP, UNKNOWN }

    data class Row(
        val ts: Long,
        val pct: Int,
        val hr: Int,
        val hrSource: String,
        val n: Int,
        val moves: Int,
        val std: Double,
        val p2p: Double,
        val asleep: Boolean,
        val alarmState: String,
        val wakePhase: String,
    )

    data class Night(
        val rows: Int,
        val startTs: Long,
        val endTs: Long,
        val coveragePct: Double,
        val hrMin: Int,
        val hrAvg: Int,
        val hrMax: Int,
        val hrWindows: Int,
        val awakenings: Int,
        val asleepPct: Double,
        val motionPct: Double,
        val batteryStart: Int,
        val batteryEnd: Int,
        val alarmFired: Boolean,
        val wakePhase: Stage,
        val wakeHr: Int,
        val series: List<Row>,
        // Новые показатели
        val stages: List<Stage>,
        val deepMin: Int,
        val lightMin: Int,
        val awakeMin: Int,
        val efficiencyPct: Double,
        val longestQuietMin: Int,
        val firstSampleTs: Long,
        val lastSampleTs: Long,
    ) {
        val durationMin: Int get() = ((endTs - startTs) / 60_000L).toInt()
    }

    fun parseLine(line: String): Row? {
        if (line.isBlank() || line.startsWith("#")) return null
        val c = line.split(',')
        if (c.size < 16) return null
        return try {
            Row(
                ts = c[1].toLong(),
                pct = c[3].toInt(),
                hr = c[8].toInt(),
                hrSource = if (c.size > 17) c[17] else "none",
                n = c[10].toInt(),
                moves = c[14].toInt(),
                std = c[12].toDouble(),
                p2p = c[13].toDouble(),
                asleep = c.size > 18 && c[18].equals("true", ignoreCase = true),
                alarmState = if (c.size > 19) c[19] else "",
                wakePhase = if (c.size > 20) c[20] else "",
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun parse(lines: List<String>): List<Row> = lines.mapNotNull { parseLine(it) }

    fun isMotion(row: Row): Boolean =
        ArousalMath.isMotion(row.n, row.moves, row.std, row.p2p)

    /** Бодрствование: эпизоды движения ≥2 окон через ≥5 минут тишины. */
    fun awakenings(rows: List<Row>): Int {
        var count = 0
        var run = 0
        var lastMotionTs = 0L
        for (r in rows) {
            if (isMotion(r)) {
                if (run == 0 && lastMotionTs > 0 && r.ts - lastMotionTs < QUIET_BREAK_MS) run = 1
                run++
                lastMotionTs = r.ts
            } else {
                if (run >= MIN_EPISODE_BUCKETS) count++
                run = 0
            }
        }
        if (run >= MIN_EPISODE_BUCKETS) count++
        return count
    }

    fun summarize(rows: List<Row>, bucketMs: Long = BUCKET_MS): Night? {
        if (rows.isEmpty()) return null
        val start = rows.first().ts
        val end = rows.last().ts
        val durationBuckets = ((end - start) / bucketMs).coerceAtLeast(1) + 1
        val coverage = 100.0 * rows.size / durationBuckets

        val hrValues = rows.map { it.hr }.filter { it > 0 }
        val hrMedian = hrValues.sorted().let {
            if (it.isEmpty()) 0 else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
        }
        val stages = rows.map { stageOf(it, hrMedian) }
        val bucketMin = (bucketMs / 60_000L).toInt().coerceAtLeast(1)

        val deepMin = stages.count { it == Stage.DEEP } * bucketMin
        val lightMin = stages.count { it == Stage.LIGHT } * bucketMin
        val awakeMin = stages.count { it == Stage.AWAKE } * bucketMin
        val trackedMin = deepMin + lightMin + awakeMin
        val efficiency = if (trackedMin == 0) 0.0 else 100.0 * (deepMin + lightMin) / trackedMin

        // самый длинный непрерывный спокойный отрезок (не движение)
        var longestMs = 0L
        var runStart = -1L
        for (r in rows) {
            if (isMotion(r)) {
                if (runStart > 0) longestMs = maxOf(longestMs, r.ts - runStart)
                runStart = -1L
            } else if (runStart < 0) {
                runStart = r.ts
            }
        }
        if (runStart > 0) longestMs = maxOf(longestMs, rows.last().ts - runStart)

        val motionCount = rows.count { isMotion(it) }
        val asleepCount = rows.count { it.asleep }
        val firstPct = rows.firstOrNull { it.pct in 0..100 }?.pct ?: -1
        val lastPct = rows.lastOrNull { it.pct in 0..100 }?.pct ?: -1

        return Night(
            rows = rows.size,
            startTs = start,
            endTs = end,
            coveragePct = coverage,
            hrMin = hrValues.minOrNull() ?: -1,
            hrAvg = if (hrValues.isEmpty()) -1 else hrValues.average().toInt(),
            hrMax = hrValues.maxOrNull() ?: -1,
            hrWindows = hrValues.size,
            awakenings = awakenings(rows),
            asleepPct = if (rows.isEmpty()) 0.0 else 100.0 * asleepCount / rows.size,
            motionPct = 100.0 * motionCount / rows.size,
            batteryStart = firstPct,
            batteryEnd = lastPct,
            alarmFired = rows.any { it.alarmState == "triggered" },
            wakePhase = rows.firstOrNull { it.alarmState == "triggered" }
                ?.let { PhaseEstimator.fromCode(it.wakePhase) } ?: Stage.UNKNOWN,
            wakeHr = rows.firstOrNull { it.alarmState == "triggered" }?.hr ?: -1,
            series = rows,
            stages = stages,
            deepMin = deepMin,
            lightMin = lightMin,
            awakeMin = awakeMin,
            efficiencyPct = efficiency,
            longestQuietMin = (longestMs / 60_000L).toInt(),
            firstSampleTs = rows.firstOrNull { it.hr > 0 }?.ts ?: start,
            lastSampleTs = rows.lastOrNull { it.hr > 0 }?.ts ?: end,
        )
    }

    /** Оценочная фаза: движение → бодрствование, иначе по пульсу относительно медианы. */
    fun stageOf(row: Row, hrMedian: Int): Stage {
        if (isMotion(row)) return Stage.AWAKE
        if (row.hr <= 0) return Stage.UNKNOWN
        if (hrMedian > 0 && row.hr <= hrMedian * DEEP_HR_FACTOR) return Stage.DEEP
        return Stage.LIGHT
    }

    fun hrCoveragePct(night: Night): Double =
        if (night.rows == 0) 0.0 else 100.0 * night.hrWindows / night.rows
}
