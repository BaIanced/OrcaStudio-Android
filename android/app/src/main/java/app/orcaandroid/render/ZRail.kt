package app.orcaandroid.render

import app.orcaandroid.core.Vec3
import kotlin.math.abs

/**
 * The shape builder's Z rail: a lift handle above the lone selected box in [MeshPreviewView]; dragging it
 * moves the shape up or down. The lift sticks where the box's bottom or top meets the floor or a face of a
 * box below or above it.
 */
object ZRail {
    /** Height of the lift handle above the box's top, mm. */
    fun gap(min: Vec3, max: Vec3): Float = maxOf(4f, 0.25f * maxOf(max.x - min.x, max.y - min.y, max.z - min.z))

    /** The lift handle: [gap] above the centre of the box's top. */
    fun handle(min: Vec3, max: Vec3) = Vec3((min.x + max.x) / 2, (min.y + max.y) / 2, max.z + gap(min, max))

    /**
     * The lift for a raw drag of [raw] mm: the nearest snap within [reach] mm (the bottom [lo] or the top [hi]
     * landing on one of the [targets] heights, or with [mid] its middle), else [raw] rounded to [step]. Second is the snapped height, or null.
     */
    fun lift(raw: Float, lo: Float, hi: Float, targets: List<Float>, reach: Float, step: Float, mid: Boolean = false): Pair<Float, Float?> {
        var best: Float? = null
        var at: Float? = null
        var dist = reach
        for (t in targets) for (dz in if (mid) floatArrayOf(t - lo, t - hi, t - (lo + hi) / 2) else floatArrayOf(t - lo, t - hi)) {
            val e = abs(raw - dz)
            if (e <= dist) { dist = e; best = dz; at = t }
        }
        if (best != null) return best to at
        return Math.round(raw / step) * step to null
    }
}
