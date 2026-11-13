package io.github.smartalarm

import java.io.File
import java.time.Instant

object Summary {

    private data class Row(
        val ts: Long,
        val pct: Int,
        val temp: Double,
        val screen: Boolean,
        val doze: Boolean,
        val hr: Int,
        val accN: Int,
        val std: Double,
        val p2p: Double,
        val moves: Int,
        val hrs: Int,
    )

    fun build(file: File): String {
        if (!file.exists()) return "Нет файла: ${file.absolutePath}"
        val raw = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (raw.size < 2) return "Мало строк: ${raw.size}"
        val rows = raw.drop(1).mapNotNull { parse(it) }
        if (rows.isEmpty()) return "Не удалось разобрать CSV (${file.name})"

        val first = rows.first()
        val last = rows.last()
        val durMin = (last.ts - first.ts) / 60000
        val battStart = rows.firstOrNull { it.pct >= 0 }?.pct ?: -1
        val battEnd = rows.lastOrNull { it.pct >= 0 }?.pct ?: -1
        val temps = rows.map { it.temp }.filter { it > -100 }
        val hrs = rows.map { it.hr }.filter { it > 0 }
        val accN = rows.map { it.accN }
        val emptyBuckets = accN.count { it == 0 }
        val withMoves = rows.count { it.moves > 0 }
        val totalMoves = rows.sumOf { it.moves }
        val hrSamples = rows.sumOf { it.hrs }
        val meanStd = rows.map { it.std }.filter { it > 0 }.averageOrNull()
        val screenOn = rows.count { it.screen }
        val doze = rows.count { it.doze }

        val sb = StringBuilder()
        sb.appendLine("Файл: ${file.name}")
        sb.appendLine("Строк: ${rows.size}, окно ${durMin} мин")
        sb.appendLine("Начало: ${Instant.ofEpochMilli(first.ts)}")
        sb.appendLine("Конец:  ${Instant.ofEpochMilli(last.ts)}")
        sb.appendLine("Батарея: $battStart% -> $battEnd%  (дельта ${if (battStart >= 0 && battEnd >= 0) battStart - battEnd else -1}%)")
        if (temps.isNotEmpty()) {
            sb.appendLine("Темп. батареи: min ${fmt(temps.min())} C, max ${fmt(temps.max())} C")
        }
        sb.appendLine("Пульс: сэмплов $hrSamples, значений ${hrs.size}")
        if (hrs.isNotEmpty()) {
            sb.appendLine("  HR min ${hrs.min()} / avg ${hrs.average().toInt()} / max ${hrs.max()}")
        } else {
            sb.appendLine("  HR НЕТ — пульс не отдаётся (или нет разрешения BODY_SENSORS)")
        }
        sb.appendLine("Акселерометр: пустых окон $emptyBuckets из ${rows.size}")
        sb.appendLine("  окон с движением $withMoves, всего «рывков» $totalMoves")
        if (meanStd != null) sb.appendLine("  средний std магнитуды ${fmt(meanStd)}")
        sb.appendLine("Экран включён в $screenOn окнах, doze в $doze окнах")
        sb.appendLine()
        sb.appendLine("Первые 5 строк:")
        rows.take(5).forEach { r -> sb.appendLine("  ${Instant.ofEpochMilli(r.ts)} hr=${r.hr} n=${r.accN} moves=${r.moves} pct=${r.pct}") }
        sb.appendLine("Последние 5 строк:")
        rows.takeLast(5).forEach { r -> sb.appendLine("  ${Instant.ofEpochMilli(r.ts)} hr=${r.hr} n=${r.accN} moves=${r.moves} pct=${r.pct}") }
        return sb.toString()
    }

    fun latestCsv(dir: File): File? =
        dir.listFiles { f -> f.isFile && f.name.startsWith("probe_") && f.name.endsWith(".csv") }
            ?.maxByOrNull { it.lastModified() }

    private fun parse(line: String): Row? = try {
        val c = line.split(',')
        if (c.size < 17) null
        else Row(
            ts = c[1].toLong(),
            pct = c[3].toInt(),
            temp = c[5].toDouble(),
            screen = c[6].toBoolean(),
            doze = c[7].toBoolean(),
            hr = c[8].toInt(),
            accN = c[10].toInt(),
            std = c[12].toDouble(),
            p2p = c[13].toDouble(),
            moves = c[14].toInt(),
            hrs = c[15].toInt(),
        )
    } catch (_: Throwable) {
        null
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else sum() / size
}
