package app.orcaandroid.render

/** The last [SIZE] frames of a GL view: gap between frame starts and the time spent drawing (CPU side, GL thread). */
class FrameStats {
    private val starts = LongArray(SIZE)
    private val draws = LongArray(SIZE)
    private var count = 0

    @Synchronized fun record(start: Long, end: Long) {
        starts[count % SIZE] = start
        draws[count % SIZE] = end - start
        count++
    }

    /** One line: frames per second, frame gaps and draw times in ms (median / 95th percentile / worst), long frames. */
    @Synchronized fun report(): String {
        val n = minOf(count, SIZE)
        if (n < 2) return "No frames drawn yet."
        val first = count - n
        val order = (first until count).map { starts[it % SIZE] }
        val gaps = order.zipWithNexts().sorted()
        val drawMs = (first until count).map { draws[it % SIZE] / 1e6 }.sorted()
        val ageMs = (System.nanoTime() - order.last()) / 1e6
        fun pct(v: List<Double>, p: Double) = v[((v.size - 1) * p).toInt()]
        fun ms(x: Double) = "%.1f".format(java.util.Locale.ROOT, x)
        val span = (order.last() - order.first()) / 1e9
        return "Last $n frames over ${ms(span)} s: ${ms((n - 1) / span)} fps; frame gap ms median ${ms(pct(gaps, 0.5))}, " +
            "p95 ${ms(pct(gaps, 0.95))}, worst ${ms(gaps.last())}; ${gaps.count { it > 33.4 }} gaps over 33 ms; draw ms median " +
            "${ms(pct(drawMs, 0.5))}, p95 ${ms(pct(drawMs, 0.95))}, worst ${ms(drawMs.last())}; last frame ${ms(ageMs)} ms ago."
    }

    private fun List<Long>.zipWithNexts() = zipWithNext { a, b -> (b - a) / 1e6 }

    companion object { const val SIZE = 240 }
}
