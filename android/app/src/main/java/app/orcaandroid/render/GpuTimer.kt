package app.orcaandroid.render

import android.opengl.GLES30.*

/**
 * GPU time per draw pass (EXT_disjoint_timer_query) for the MCP perf tool. Off until [enabled] is set, so normal use
 * issues no queries. GL thread only, except [report] and [enabled].
 * Queries can't nest: a [section] inside another one is simply not timed. Results are read [FRAMES] frames later, so
 * the GPU is never waited on; a slot whose results aren't ready by then, or a disjoint event, drops that frame.
 * On tiled GPUs a pass inside one render pass may report binning cost only: check that the passes add up to a sane frame.
 */
class GpuTimer {
    @Volatile var enabled = false
    @Volatile private var supported: Boolean? = null
    private val ids = IntArray(FRAMES * MAX)
    private val labels = Array(FRAMES) { arrayOfNulls<String>(MAX) }
    private val used = IntArray(FRAMES)
    private var slot = 0
    private var open = false
    private val one = IntArray(1)
    private val frameSum = LinkedHashMap<String, Long>()
    private val samples = HashMap<String, FloatArray>()
    private val counts = HashMap<String, Int>()
    private var frames = 0
    private var dropped = 0

    /** At the start of each frame: reads the results of the slot about to be reused. */
    fun beginFrame() {
        if (!enabled) return
        if (supported == null) {
            val ok = glGetString(GL_EXTENSIONS)?.contains("GL_EXT_disjoint_timer_query") == true
            if (ok) glGenQueries(ids.size, ids, 0)
            supported = ok
        }
        if (supported != true) return
        slot = (slot + 1) % FRAMES
        collect(slot)
    }

    fun begin(label: String): Boolean {
        if (!enabled || supported != true || open || used[slot] >= MAX) return false
        val i = used[slot]++
        labels[slot][i] = label
        glBeginQuery(TIME_ELAPSED, ids[slot * MAX + i])
        open = true
        return true
    }

    fun end() { glEndQuery(TIME_ELAPSED); open = false }

    inline fun <T> section(label: String, block: () -> T): T {
        val on = begin(label)
        try { return block() } finally { if (on) end() }
    }

    private fun collect(s: Int) {
        val n = used[s]
        used[s] = 0
        if (n == 0) return
        glGetIntegerv(GPU_DISJOINT, one, 0)
        if (one[0] != 0) { dropped++; return }
        val base = s * MAX
        glGetQueryObjectuiv(ids[base + n - 1], GL_QUERY_RESULT_AVAILABLE, one, 0)
        if (one[0] == 0) { dropped++; return }
        frameSum.clear()
        for (i in 0 until n) {
            glGetQueryObjectuiv(ids[base + i], GL_QUERY_RESULT, one, 0)
            val l = labels[s][i] ?: continue
            frameSum[l] = (frameSum[l] ?: 0L) + (one[0].toLong() and 0xffffffffL)
        }
        synchronized(this) {
            for ((l, ns) in frameSum) {
                val ring = samples.getOrPut(l) { FloatArray(SAMPLES) }
                val c = counts[l] ?: 0
                ring[c % SAMPLES] = ns / 1e6f
                counts[l] = c + 1
            }
            frames++
        }
    }

    /** One line per pass, slowest first: median / p95 ms and in how many measured frames it ran. */
    @Synchronized fun report(): String {
        if (supported == false) return "GPU timers: not supported here (no GL_EXT_disjoint_timer_query)."
        if (frames == 0) return "GPU timers: on, nothing measured yet (draw some frames, then call perf again)."
        fun ms(x: Float) = "%.2f".format(java.util.Locale.ROOT, x)
        val rows = samples.map { (label, ring) ->
            val n = minOf(counts[label] ?: 0, SAMPLES)
            val v = ring.copyOf(n).sorted()
            Triple(label, v[(n - 1) / 2], v[((n - 1) * 0.95).toInt()]) to n
        }.sortedByDescending { it.first.second }
        val total = rows.sumOf { it.first.second.toDouble() }.toFloat()
        return "GPU ms per pass over $frames measured frames ($dropped dropped), median / p95 [frames]; medians sum " +
            "${ms(total)}:\n" + rows.joinToString("\n") { (r, n) -> "  ${r.first}: ${ms(r.second)} / ${ms(r.third)} [$n]" }
    }

    @Synchronized fun reset() { samples.clear(); counts.clear(); frames = 0; dropped = 0 }

    companion object {
        const val FRAMES = 4
        const val MAX = 24
        const val SAMPLES = 240
        const val TIME_ELAPSED = 0x88BF // GL_TIME_ELAPSED_EXT
        const val GPU_DISJOINT = 0x8FBB // GL_GPU_DISJOINT_EXT
    }
}
