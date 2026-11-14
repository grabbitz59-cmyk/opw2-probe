package io.github.smartalarm

/**
 * Разбор и агрегация ночного CSV (без Android — покрыто JVM-тестами).
 *
 * Формат строки от [NightLogService]:
 * ts,epoch_ms,mode,battery_pct,charging,batt_temp_c,screen_on,doze,hr_bpm,hr_age_s,
 * acc_n,acc_mean,acc_std,acc_p2p,moves,hr_samples,uptime_s,hr_source,saw_asleep,alarm_state
 */
object SleepStats {

    const val BUCKET_MS = 30_000L
    private const val QUIET_BREAK_MS = 5 * 60_000L     // разрыв, который делит эпизоды движения
    private const val MIN_EPISODE_BUCKETS = 2          // короче — это микродвижение, не пробуждение

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
        val series: List<Row>,
    )

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
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun parse(lines: List<String>): List<Row> = lines.mapNotNull { parseLine(it) }

    /** Движение в окне — та же формула, что и в [ArousalMath]. */
    fun isMotion(row: Row): Boolean =
        ArousalMath.isMotion(row.n, row.moves, row.std, row.p2p)

    /**
     * Пробуждения: эпизоды движения длиной не меньше [MIN_EPISODE_BUCKETS] окон,
     * разделённые хотя бы [QUIET_BREAK_MS] тишины. Микровздрагивания не считаем.
     */
    fun awakenings(rows: List<Row>): Int {
        var count = 0
        var run = 0
        var lastMotionTs = 0L
        for (r in rows) {
            if (isMotion(r)) {
                if (run == 0 && lastMotionTs > 0 && r.ts - lastMotionTs < QUIET_BREAK_MS) {
                    run = 1 // продолжение эпизода после короткой паузы
                }
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
            asleepPct = 100.0 * asleepCount / rows.size,
            motionPct = 100.0 * motionCount / rows.size,
            batteryStart = firstPct,
            batteryEnd = lastPct,
            alarmFired = rows.any { it.alarmState == "triggered" },
            series = rows,
        )
    }

    /** Насколько свежим был пульс: доля окон с валидным HR. */
    fun hrCoveragePct(night: Night): Double =
        if (night.rows == 0) 0.0 else 100.0 * night.hrWindows / night.rows
}
