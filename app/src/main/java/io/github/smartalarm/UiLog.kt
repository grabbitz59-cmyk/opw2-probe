package io.github.smartalarm

object UiLog {
    private val sb = StringBuilder()

    @Synchronized
    fun add(line: String) {
        if (sb.length > 400_000) sb.delete(0, sb.length - 200_000)
        sb.append(line).append('\n')
    }

    @Synchronized
    fun text(): String = sb.toString()

    @Synchronized
    fun clear() {
        sb.setLength(0)
    }
}
