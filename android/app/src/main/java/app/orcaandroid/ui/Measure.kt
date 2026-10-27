package app.orcaandroid.ui

import app.orcaandroid.core.PickHit
import app.orcaandroid.core.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max

/** Two faces count as parallel within this angle (degrees). */
const val MEASURE_PARALLEL_DEG = 1f

/**
 * A placed measure point. [kind]: what it sits on, "vertex" / "edge" / "curve" / "ring" / "face" (flat) / "bed" / "point"
 * (a bare hit or a curved face). [point]: where it sits; a ring's is its centre. [normal]: a flat face's or the bed's normal,
 * a ring's axis. [diameter]: a ring's.
 */
data class MeasurePoint(val point: Vec3, val kind: String, val normal: Vec3? = null, val diameter: Float? = null) {
    /** A flat face or the bed: distances to it are measured square to it. */
    val isPlane get() = (kind == "face" || kind == "bed") && normal != null

    companion object {
        fun bed(p: Vec3) = MeasurePoint(p, "bed", Vec3(0f, 0f, 1f))

        /** The measure point for [hit]: a ring becomes its centre; a face is flat only when its outline lies in the hit's plane. */
        fun of(hit: PickHit): MeasurePoint {
            val f = hit.feature ?: return MeasurePoint(hit.point, "point")
            return when (f.kind) {
                "ring" -> ringOf(f.lines)?.let { (c, n) -> MeasurePoint(c, "ring", n, f.diameter) } ?: MeasurePoint(hit.point, "curve")
                "face" -> if (flat(f.lines, hit.point, hit.normal)) MeasurePoint(hit.point, "face", unit(hit.normal)) else MeasurePoint(hit.point, "point")
                else -> MeasurePoint(hit.point, f.kind)
            }
        }

        /** The centre (length-weighted segment midpoints) and axis of a closed loop of segments (x, y, z per end), or null. */
        fun ringOf(lines: FloatArray): Pair<Vec3, Vec3>? {
            val segs = segments(lines)
            var total = 0f
            var sum = Vec3.ZERO
            for ((a, b) in segs) {
                val l = (b - a).length()
                total += l
                sum += (a + b) * (0.5f * l)
            }
            if (total <= 0f) return null
            val c = sum * (1f / total)
            // Segment ends may run either way round: flip each turn to agree with the sum so far.
            var n = Vec3.ZERO
            for ((a, b) in segs) {
                val t = cross(a - c, b - c)
                n += if (t.dot(n) < 0f) t * -1f else t
            }
            return if (n.length() < 1e-9f) null else c to unit(n)
        }

        /** Whether every end of [lines] lies in the plane through [p] square to [n] (an empty outline: a closed smooth surface, not flat). */
        fun flat(lines: FloatArray, p: Vec3, n: Vec3): Boolean {
            val segs = segments(lines)
            if (segs.isEmpty() || n.length() < 1e-9f) return false
            val u = unit(n)
            val ends = segs.flatMap { listOf(it.first, it.second) }
            val extent = ends.maxOf { (it - p).length() }
            val tol = max(0.05f, extent * 0.005f)
            return ends.all { abs((it - p).dot(u)) <= tol }
        }

        private fun segments(lines: FloatArray) = (0 until lines.size / 6).map { i ->
            val k = i * 6
            Vec3(lines[k], lines[k + 1], lines[k + 2]) to Vec3(lines[k + 3], lines[k + 4], lines[k + 5])
        }
    }
}

/**
 * What two measure points give. [distance] / [delta]: point to point (ring centres for rings). [gap]: two parallel flat faces
 * (or a face and the bed), square to them. [toPlane]: a point and a flat face or the bed, square to it. [angle]: two faces
 * that aren't parallel (degrees, 0..90).
 */
data class MeasureResult(val distance: Float, val delta: Vec3, val gap: Float? = null, val toPlane: Float? = null, val angle: Float? = null)

fun measureResult(a: MeasurePoint, b: MeasurePoint): MeasureResult {
    val d = b.point - a.point
    val base = MeasureResult(d.length(), Vec3(abs(d.x), abs(d.y), abs(d.z)))
    val na = a.normal
    val nb = b.normal
    return when {
        a.isPlane && b.isPlane && na != null && nb != null -> {
            val c = abs(na.dot(nb)).coerceAtMost(1f)
            if (c >= cos(Math.toRadians(MEASURE_PARALLEL_DEG.toDouble())).toFloat()) base.copy(gap = abs(d.dot(na)))
            else base.copy(angle = Math.toDegrees(acos(c).toDouble()).toFloat())
        }
        a.isPlane && na != null -> base.copy(toPlane = abs(d.dot(na)))
        b.isPlane && nb != null -> base.copy(toPlane = abs(d.dot(nb)))
        else -> base
    }
}

private fun cross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
private fun unit(v: Vec3) = v.length().let { if (it < 1e-9f) v else v * (1f / it) }
